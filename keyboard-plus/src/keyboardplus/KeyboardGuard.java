package keyboardplus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import bml.api.Bml;

/**
 * Keyboard+: on Linux (X11 or Xwayland), keys pressed in a plug-in window also go to Bitwig.
 *
 * Plug-in windows belong to Bitwig's plug-in host processes (BitwigPluginHost-*). The guard listens
 * to the key events of those windows alongside the plug-in (X11 lets several clients receive the
 * keys of one window), and sends a copy of each key to Bitwig's own window. Nothing is grabbed: the
 * plug-in, and every other program, gets its keys exactly as before.
 *
 * Key events go to the deepest focused window some client listens on, and stop there. So the guard
 * listens only on the windows of the plug-in's tree where the plug-in itself listens: delivery to
 * the plug-in is unchanged.
 */
final class KeyboardGuard implements Runnable {
    static final String MOD_ID = "keyboard-plus";
    static final Bml.Mod MOD = Bml.mod(MOD_ID);
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile KeyboardGuard instance;

    private static final int XK_SPACE = 0x20;
    private static final long RESCAN_MS = 2_000, DEBOUNCE_MS = 150, KEYMAP_CHECK_MS = 250;
    private static final int KEYS = X11.KEY_PRESS_MASK | X11.KEY_RELEASE_MASK;

    private final long self = ProcessHandle.current().pid();
    private volatile boolean enabled;
    private volatile boolean toggled;
    private final boolean diagnostics;
    private final Pattern include, exclude;
    /** true: every key (and modifier) is copied to Bitwig; false: only unmodified Space. */
    private final boolean allKeys;
    /** Whether a held key's auto-repeat reaches Bitwig (off: one press and one release, as on a piano). */
    private final boolean repeats;

    private X11 x;
    private List<Integer> spaceKeycodes = List.of();
    /** Windows this client listens on -> the event mask set there. */
    private final Map<Long, Integer> listening = new HashMap<>();
    /** Plug-in window (top of its tree) -> title, for the windows whose keys are copied. */
    private final Map<Long, String> plugins = new LinkedHashMap<>();
    /** Each listened window -> the title of its plug-in. */
    private final Map<Long, String> windowTitle = new HashMap<>();
    private long bitwigWindow;
    private String lastReport = "";
    /** Keys whose press was copied to Bitwig and whose release hasn't been. */
    private final Set<Integer> held = new HashSet<>();
    private int lastState, lastTime;

    private KeyboardGuard() {
        enabled = MOD.settings().getBoolean("enabled", true);
        diagnostics = MOD.settings().getBoolean("diagnostics", false);
        allKeys = !MOD.settings().get("keys", "all").trim().equalsIgnoreCase("space");
        repeats = MOD.settings().getBoolean("repeat", false);
        include = pattern("include");
        exclude = pattern("exclude");
    }

    static void start() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            MOD.log("not Linux: nothing to do");
            return;
        }
        if (!STARTED.compareAndSet(false, true)) return;
        KeyboardGuard g = new KeyboardGuard();
        instance = g;
        Thread t = new Thread(g, MOD_ID);
        t.setDaemon(true);
        t.start();
    }

    /** The mod's action: stops copying keys to Bitwig, or starts again. */
    static void toggle() {
        KeyboardGuard g = instance;
        if (g == null) return;
        g.enabled = !g.enabled;
        g.toggled = true;
        try {
            MOD.settings().putBoolean("enabled", g.enabled);
            MOD.settings().flush();
        } catch (Throwable t) {
            MOD.log("could not save the on/off state", t);
        }
        MOD.log(g.enabled ? "on: keys in plug-in windows also go to Bitwig" : "off: keys in plug-in windows go to the plug-in only");
    }

    private static Pattern pattern(String key) {
        String s = MOD.settings().get(key, "").trim();
        if (s.isEmpty()) return null;
        try {
            return Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            MOD.log("setting " + key + " is not a valid regular expression, ignored: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ loop

    @Override
    public void run() {
        long wait = 2_000;
        for (int attempt = 0; ; attempt++) {
            try {
                x = X11.open();
                wait = 2_000;
                MOD.log("connected to the X server; " + (allKeys ? "keys" : "Space") + " in plug-in windows "
                        + (enabled ? "also go to Bitwig" : "go to the plug-in only (off)"));
                session();
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("No X11 display")) {
                    MOD.log(e.getMessage());
                    return;
                }
                if (attempt < 5 || attempt % 20 == 0) MOD.log("X11: " + e.getMessage() + "; retrying");
            } catch (Throwable t) {
                MOD.log("stopped after an unexpected error", t);
                return;
            } finally {
                if (x != null) x.close();      // closing the connection drops every selection made on it
                x = null;
                listening.clear();
                plugins.clear();
                windowTitle.clear();
                bitwigWindow = 0;
                held.clear();
            }
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            wait = Math.min(wait * 2, 30_000);
        }
    }

    private void session() throws IOException {
        spaceKeycodes = x.keycodesFor(XK_SPACE);
        if (spaceKeycodes.isEmpty()) throw new IOException("no keycode for Space in the keyboard map");
        x.watchRoot();
        long nextScan = 0, dirtySince = -1, nextKeymapCheck = 0;
        for (;;) {
            long now = System.currentTimeMillis();
            if (toggled) { toggled = false; nextScan = 0; }
            if (now >= nextScan || (dirtySince >= 0 && now - dirtySince >= DEBOUNCE_MS)) {
                scan();
                nextScan = System.currentTimeMillis() + RESCAN_MS;
                dirtySince = -1;
            }
            if (!held.isEmpty() && now >= nextKeymapCheck) {
                releaseKeysNoLongerDown();
                nextKeymapCheck = now + KEYMAP_CHECK_MS;
            }
            long until = Math.min(nextScan, dirtySince >= 0 ? dirtySince + DEBOUNCE_MS : nextScan);
            if (!held.isEmpty()) until = Math.min(until, nextKeymapCheck);
            X11.Event e = x.nextEvent(Math.max(1, Math.min(until - System.currentTimeMillis(), 500)));
            if (e == null) continue;
            switch (e.type()) {
                case 0 -> error(e);
                case X11.KEY_PRESS -> keyPress(e);
                case X11.KEY_RELEASE -> keyRelease(e);
                case 16, 17, 18, 19, 21 -> { if (dirtySince < 0) dirtySince = System.currentTimeMillis(); } // Create/Destroy/Unmap/Map/Reparent
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ windows

    private record Found(long window, String title) {}

    private void scan() throws IOException {
        Set<Long> hosts = pluginHostPids();
        Map<Long, String> found = new LinkedHashMap<>();
        List<Found> bitwig = new ArrayList<>();
        walk(x.root, 0, hosts, found, bitwig, null);
        bitwigWindow = pickBitwigWindow(bitwig);

        plugins.clear();
        if (enabled) for (var p : found.entrySet()) if (accepted(p.getValue())) plugins.put(p.getKey(), p.getValue());

        Map<Long, Integer> wanted = new HashMap<>();
        windowTitle.clear();
        for (var p : plugins.entrySet()) listenInTree(p.getKey(), p.getValue(), wanted);

        for (var w : wanted.entrySet()) {
            Integer had = listening.get(w.getKey());
            if (had == null || had.intValue() != w.getValue()) x.selectInput(w.getKey(), w.getValue());
        }
        for (var it = listening.entrySet().iterator(); it.hasNext(); ) {
            var l = it.next();
            if (!wanted.containsKey(l.getKey())) { x.selectInput(l.getKey(), 0); it.remove(); }
        }
        listening.putAll(wanted);
        x.sync();
        report(hosts, found, bitwig);
    }

    /**
     * Decides where to listen in one plug-in window's tree. On a window where another client (the
     * plug-in) listens to keys, listen to the same key events: the keys stop there anyway. Everywhere
     * else, only watch for new child windows. If nobody listens to keys anywhere in the tree, listen on
     * its top window, where the keys would otherwise be dropped.
     */
    private void listenInTree(long top, String title, Map<Long, Integer> wanted) throws IOException {
        List<Long> stack = new ArrayList<>(List.of(top));
        Map<Long, Integer> depth = new HashMap<>(Map.of(top, 0));
        boolean anyKeys = false;
        while (!stack.isEmpty()) {
            long w = stack.remove(stack.size() - 1);
            int[] masks = x.eventMasks(w);
            if (masks == null) continue;
            // X only gives the union of all clients' masks: where this client already listens to keys,
            // the union can't say whether the plug-in still does. Keep the choice made when it could.
            Integer had = listening.get(w);
            int keys = had != null && (had & KEYS) != 0 && (masks[1] & KEYS) != 0
                    ? had & KEYS
                    : masks[0] & ~masks[1] & KEYS;
            if (keys != 0) anyKeys = true;
            wanted.put(w, keys | X11.SUBSTRUCTURE_NOTIFY_MASK);
            windowTitle.put(w, title);
            int d = depth.get(w);
            if (d < 8) for (long c : x.children(w)) { stack.add(c); depth.put(c, d + 1); }
        }
        if (!anyKeys) wanted.put(top, KEYS | X11.SUBSTRUCTURE_NOTIFY_MASK);
    }

    /**
     * Finds plug-in host windows (the topmost window of a plug-in host in each branch) and Bitwig's
     * own client windows. Descends through window-manager frames and into Bitwig's windows, which
     * may hold an embedded plug-in window, but not into other applications.
     */
    private void walk(long parent, int depth, Set<Long> hosts, Map<Long, String> found, List<Found> bitwig, String parentTitle) throws IOException {
        if (depth > 4) return;
        for (long w : x.children(parent)) {
            long pid = x.pid(w, true);
            boolean client = x.hasWmProtocols(w);
            if (pid > 0 && hosts.contains(pid)) {
                String title = x.title(w);
                if (title.isEmpty() && parentTitle != null) title = parentTitle;
                found.put(w, title);
                continue;
            }
            if (pid == self) {
                String title = x.title(w);
                if (client) bitwig.add(new Found(w, title));
                walk(w, depth + 1, hosts, found, bitwig, title.isEmpty() ? parentTitle : title);
            } else if (!client && depth < 2) {
                walk(w, depth + 1, hosts, found, bitwig, parentTitle);   // a frame: look inside
            }
        }
    }

    private long pickBitwigWindow(List<Found> bitwig) throws IOException {
        long fallback = 0;
        for (Found f : bitwig) {
            if (x.mapState(f.window) != 2) continue;
            if (f.title.toLowerCase().contains("bitwig studio")) return f.window;
            if (fallback == 0) fallback = f.window;
        }
        return fallback;
    }

    private boolean accepted(String title) {
        if (include != null && !include.matcher(title).find()) return false;
        return exclude == null || !exclude.matcher(title).find();
    }

    private Set<Long> pluginHostPids() {
        Set<Long> out = new HashSet<>();
        ProcessHandle.allProcesses().forEach(p -> {
            Optional<String> cmd = p.info().command();
            if (cmd.isEmpty()) return;
            String c = cmd.get();
            String name = c.substring(c.lastIndexOf('/') + 1);
            if (name.startsWith("BitwigPluginHost")) out.add(p.pid());
        });
        return out;
    }

    private void report(Set<Long> hosts, Map<Long, String> found, List<Found> bitwig) {
        StringBuilder b = new StringBuilder();
        b.append("plug-in hosts ").append(hosts).append("; Bitwig window ").append(hex(bitwigWindow));
        for (var p : found.entrySet()) {
            b.append("\n  plug-in window ").append(hex(p.getKey())).append(" \"").append(p.getValue()).append('"');
            if (!plugins.containsKey(p.getKey())) { b.append(enabled ? " (excluded)" : " (off)"); continue; }
            int keyWindows = 0;
            for (var l : listening.entrySet())
                if ((l.getValue() & KEYS) != 0 && p.getValue().equals(windowTitle.get(l.getKey()))) keyWindows++;
            b.append(" (keys copied to Bitwig, listening on ").append(keyWindows).append(keyWindows == 1 ? " window)" : " windows)");
        }
        if (diagnostics) for (Found f : bitwig) b.append("\n  Bitwig window ").append(hex(f.window)).append(" \"").append(f.title).append('"');
        String r = b.toString();
        if (!r.equals(lastReport)) {
            lastReport = r;
            if (diagnostics || !found.isEmpty() || bitwigWindow == 0) MOD.log(r);
        }
    }

    // ------------------------------------------------------------------ keys

    private void error(X11.Event e) {
        int code = Byte.toUnsignedInt(e.data().get(1));
        if (code != X11.BAD_WINDOW && diagnostics) MOD.log("X11 error " + code + " for request " + e.sequence());
    }

    private boolean ours(int keycode) {
        return allKeys || spaceKeycodes.contains(keycode);
    }

    private void keyPress(X11.Event e) throws IOException {
        if (!enabled) return;
        int keycode = Byte.toUnsignedInt(e.data().get(1));
        if (!ours(keycode)) return;
        int time = e.data().getInt(4), state = Short.toUnsignedInt(e.data().getShort(28));
        if (!allKeys && (state & 0xff & ~(X11.LOCK_MASK | 0x10)) != 0) return; // keys=space: unmodified only (Lock/NumLock ignored)
        boolean repeat = !held.add(keycode);    // detectable auto-repeat: a press while already down
        if (repeat && !repeats) return;
        lastState = state; lastTime = time;
        if (bitwigWindow == 0) {
            if (!repeat) MOD.log("a key in a plug-in window, but Bitwig's window wasn't found; nothing sent");
            return;
        }
        x.sendKey(bitwigWindow, X11.KEY_PRESS, keycode, state, time);
        if (diagnostics && !repeat) {
            long window = Integer.toUnsignedLong(e.data().getInt(12));
            MOD.log("key " + keycode + " (state " + state + ") in plug-in window " + hex(window) + " \""
                    + windowTitle.getOrDefault(window, "?") + "\" copied to Bitwig " + hex(bitwigWindow));
        }
    }

    private void keyRelease(X11.Event e) throws IOException {
        int keycode = Byte.toUnsignedInt(e.data().get(1));
        if (!held.contains(keycode)) return;
        int time = e.data().getInt(4), state = Short.toUnsignedInt(e.data().getShort(28));
        // Non-detectable auto-repeat sends Release+Press pairs with the same time: the key is still down.
        X11.Event next = x.nextEvent(8);
        if (next != null && next.type() == X11.KEY_PRESS && Byte.toUnsignedInt(next.data().get(1)) == keycode
                && Math.abs(next.data().getInt(4) - time) <= 2) {
            if (repeats && enabled && bitwigWindow != 0)
                x.sendKey(bitwigWindow, X11.KEY_PRESS, keycode, Short.toUnsignedInt(next.data().getShort(28)), next.data().getInt(4));
            return;
        }
        release(keycode, state, time);
        if (next != null) redispatch(next);
    }

    private void release(int keycode, int state, int time) throws IOException {
        held.remove(keycode);
        if (bitwigWindow != 0) x.sendKey(bitwigWindow, X11.KEY_RELEASE, keycode, state, time);
    }

    /**
     * A key copied to Bitwig whose release this client never saw (the focus left the plug-in window
     * while it was down) would stay down in Bitwig. Ask the server which keys are down and release
     * the others.
     */
    private void releaseKeysNoLongerDown() throws IOException {
        byte[] keymap = x.queryKeymap();
        for (Integer kc : new ArrayList<>(held)) {
            boolean down = (keymap[kc >> 3] & (1 << (kc & 7))) != 0;
            if (!down) {
                release(kc, lastState, lastTime);
                if (diagnostics) MOD.log("key " + kc + " released outside the plug-in window: release sent to Bitwig");
            }
        }
    }

    private void redispatch(X11.Event e) throws IOException {
        switch (e.type()) {
            case 0 -> error(e);
            case X11.KEY_PRESS -> keyPress(e);
            case X11.KEY_RELEASE -> keyRelease(e);
            default -> { }                      // a window change: the periodic scan catches it
        }
    }

    private static String hex(long w) { return "0x" + Long.toHexString(w); }
}
