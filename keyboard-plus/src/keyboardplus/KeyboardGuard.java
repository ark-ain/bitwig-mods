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
 * Keyboard+: on Linux (X11 or Xwayland), keys pressed in a plug-in window go to Bitwig.
 *
 * Plug-in windows belong to Bitwig's plug-in host processes (BitwigPluginHost-*). The guard puts a
 * passive key grab on each of them (every key, or with keys=space only unmodified Space), so while
 * one of them has the keyboard focus the keys come to this connection instead of the plug-in. It
 * sends them on to Bitwig's own window, as if they had been pressed there.
 *
 * A grab can't tell whether a text field inside the plug-in has the focus (Linux offers no such
 * information). Exclude those plug-ins by title, or switch the mod off and on with its action.
 */
final class KeyboardGuard implements Runnable {
    static final String MOD_ID = "keyboard-plus";
    static final Bml.Mod MOD = Bml.mod(MOD_ID);
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile KeyboardGuard instance;

    private static final int XK_SPACE = 0x20, XK_NUM_LOCK = 0xff7f;
    private static final long RESCAN_MS = 3_000, DEBOUNCE_MS = 200;

    private final long self = ProcessHandle.current().pid();
    private volatile boolean enabled;
    private volatile boolean toggled;
    private final boolean diagnostics;
    private final Pattern include, exclude;

    private X11 x;
    private List<Integer> spaceKeycodes = List.of();
    private int[] modifierSets = {0};
    private final Map<Long, String> grabbed = new LinkedHashMap<>();   // plug-in window -> title
    private final Map<Integer, Long> grabRequests = new HashMap<>();   // request sequence -> window
    private long bitwigWindow;
    private String lastReport = "";
    private final Set<Integer> held = new HashSet<>();
    /** The keyboard is actively grabbed while keys are down, so every release reaches Bitwig. */
    private boolean keyboardGrabbed;
    /** true: every key (and modifier) goes to Bitwig; false: only unmodified Space. */
    private final boolean allKeys;
    /** Whether a held key's auto-repeat reaches Bitwig (off: one press and one release, as on a piano). */
    private final boolean repeats;
    private static final int ANY_KEY = 0, ANY_MODIFIER = 0x8000;

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

    /** The mod's action: switches the guard off (Space stays with the plug-ins) or on again. */
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
        MOD.log(g.enabled ? "on: keys in plug-in windows go to Bitwig" : "off: keys stay with the plug-ins");
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
                MOD.log("connected to the X server; " + (allKeys ? "the keyboard" : "Space") + " in plug-in windows "
                        + (enabled ? "goes to Bitwig" : "is left alone (off)"));
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
                if (x != null) x.close();
                x = null;
                grabbed.clear();
                grabRequests.clear();
                bitwigWindow = 0;
                held.clear();
                keyboardGrabbed = false;
            }
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            wait = Math.min(wait * 2, 30_000);
        }
    }

    private void session() throws IOException {
        spaceKeycodes = x.keycodesFor(XK_SPACE);
        if (spaceKeycodes.isEmpty()) throw new IOException("no keycode for Space in the keyboard map");
        int numLock = x.modifierMaskOf(x.keycodesFor(XK_NUM_LOCK));
        modifierSets = numLock == 0
                ? new int[]{0, X11.LOCK_MASK}
                : new int[]{0, X11.LOCK_MASK, numLock, X11.LOCK_MASK | numLock};
        x.watchRoot();
        long nextScan = 0, dirtySince = -1;
        for (;;) {
            long now = System.currentTimeMillis();
            if (toggled) { toggled = false; if (!enabled) releaseAll(); nextScan = 0; }
            if (now >= nextScan || (dirtySince >= 0 && now - dirtySince >= DEBOUNCE_MS)) {
                scan();
                nextScan = System.currentTimeMillis() + RESCAN_MS;
                dirtySince = -1;
            }
            long timeout = Math.max(1, Math.min(nextScan, dirtySince >= 0 ? dirtySince + DEBOUNCE_MS : nextScan) - System.currentTimeMillis());
            X11.Event e = x.nextEvent(Math.min(timeout, 500));
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
        Map<Long, String> plugins = new LinkedHashMap<>();
        List<Found> bitwig = new ArrayList<>();
        walk(x.root, 0, hosts, plugins, bitwig, null);

        bitwigWindow = pickBitwigWindow(bitwig, plugins);

        if (!enabled) { releaseAll(); report(hosts, plugins, bitwig); return; }
        Map<Long, String> wanted = new LinkedHashMap<>();
        for (var p : plugins.entrySet()) if (accepted(p.getValue())) wanted.put(p.getKey(), p.getValue());

        for (var it = grabbed.entrySet().iterator(); it.hasNext(); ) {
            var g = it.next();
            if (wanted.containsKey(g.getKey())) continue;
            if (plugins.containsKey(g.getKey())) ungrab(g.getKey()); // still there but now excluded
            it.remove();
        }
        boolean any = false;
        for (var w : wanted.entrySet()) {
            if (grabbed.containsKey(w.getKey())) continue;
            if (allKeys) grabRequests.put(x.grabKey(w.getKey(), ANY_KEY, ANY_MODIFIER), w.getKey());
            else for (int kc : spaceKeycodes)
                for (int mods : modifierSets) grabRequests.put(x.grabKey(w.getKey(), kc, mods), w.getKey());
            grabbed.put(w.getKey(), w.getValue());
            any = true;
        }
        if (any) x.sync();                      // errors for the grabs are now queued
        report(hosts, plugins, bitwig);
    }

    /**
     * Finds plug-in host windows (the topmost window of a plug-in host in each branch) and Bitwig's
     * own client windows. Descends through window-manager frames and into Bitwig's windows, which
     * may hold an embedded plug-in window, but not into other applications.
     */
    private void walk(long parent, int depth, Set<Long> hosts, Map<Long, String> plugins, List<Found> bitwig, String parentTitle) throws IOException {
        if (depth > 4) return;
        for (long w : x.children(parent)) {
            long pid = x.pid(w, true);
            boolean client = x.hasWmProtocols(w);
            if (pid > 0 && hosts.contains(pid)) {
                String title = x.title(w);
                if (title.isEmpty() && parentTitle != null) title = parentTitle;
                plugins.put(w, title);
                continue;
            }
            if (pid == self) {
                String title = x.title(w);
                if (client) bitwig.add(new Found(w, title));
                walk(w, depth + 1, hosts, plugins, bitwig, title.isEmpty() ? parentTitle : title);
            } else if (!client && depth < 2) {
                walk(w, depth + 1, hosts, plugins, bitwig, parentTitle);   // a frame: look inside
            }
        }
    }

    private long pickBitwigWindow(List<Found> bitwig, Map<Long, String> plugins) throws IOException {
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

    private void ungrab(long window) throws IOException {
        if (allKeys) { x.ungrabKey(window, ANY_KEY, ANY_MODIFIER); return; }
        for (int kc : spaceKeycodes) for (int mods : modifierSets) x.ungrabKey(window, kc, mods);
    }

    private void releaseAll() throws IOException {
        if (grabbed.isEmpty()) return;
        for (long w : grabbed.keySet()) ungrab(w);
        grabbed.clear();
        x.sync();
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

    private void report(Set<Long> hosts, Map<Long, String> plugins, List<Found> bitwig) {
        StringBuilder b = new StringBuilder();
        b.append("plug-in hosts ").append(hosts).append("; Bitwig window ").append(hex(bitwigWindow));
        for (var p : plugins.entrySet())
            b.append("\n  plug-in window ").append(hex(p.getKey())).append(" \"").append(p.getValue()).append('"')
             .append(grabbed.containsKey(p.getKey()) ? " (keys taken)" : enabled ? " (excluded)" : " (off)");
        if (diagnostics) for (Found f : bitwig) b.append("\n  Bitwig window ").append(hex(f.window)).append(" \"").append(f.title).append('"');
        String r = b.toString();
        if (!r.equals(lastReport)) {
            lastReport = r;
            if (diagnostics || !plugins.isEmpty() || bitwigWindow == 0) MOD.log(r);
        }
    }

    // ------------------------------------------------------------------ keys

    private void error(X11.Event e) {
        int code = Byte.toUnsignedInt(e.data().get(1));
        Long w = grabRequests.remove(e.sequence());
        if (w != null) {
            if (code == X11.BAD_ACCESS) MOD.log("the keys are already grabbed by another program in window " + hex(w) + "; left alone");
            else if (code != X11.BAD_WINDOW) MOD.log("grab failed in window " + hex(w) + " (X11 error " + code + ")");
        } else if (code != X11.BAD_WINDOW && diagnostics) {
            MOD.log("X11 error " + code + " for request " + e.sequence());
        }
    }

    private boolean ours(int keycode) {
        return allKeys || spaceKeycodes.contains(keycode);
    }

    private void keyPress(X11.Event e) throws IOException {
        int keycode = Byte.toUnsignedInt(e.data().get(1));
        if (!ours(keycode)) return;
        int time = e.data().getInt(4), state = Short.toUnsignedInt(e.data().getShort(28));
        boolean repeat = !held.add(keycode);    // detectable auto-repeat: a press while already down
        // A passive grab ends when the key that started it is released, even if others are still
        // down: their releases would go to the plug-in and Bitwig would keep the notes on.
        if (!keyboardGrabbed) {
            long window = Integer.toUnsignedLong(e.data().getInt(12));
            keyboardGrabbed = x.grabKeyboard(window, time) == 0;
        }
        if (repeat && !repeats) return;
        if (bitwigWindow == 0) {
            if (!repeat) MOD.log("a key in a plug-in window, but Bitwig's window wasn't found; nothing sent");
            return;
        }
        x.sendKey(bitwigWindow, X11.KEY_PRESS, keycode, state, time);
        if (diagnostics && !repeat) {
            long window = Integer.toUnsignedLong(e.data().getInt(12));
            MOD.log("key " + keycode + " (state " + state + ") in plug-in window " + hex(window) + " \""
                    + grabbed.getOrDefault(window, "?") + "\" sent to Bitwig " + hex(bitwigWindow));
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
            if (repeats && bitwigWindow != 0)
                x.sendKey(bitwigWindow, X11.KEY_PRESS, keycode, Short.toUnsignedInt(next.data().getShort(28)), next.data().getInt(4));
            return;
        }
        held.remove(keycode);
        if (bitwigWindow != 0) x.sendKey(bitwigWindow, X11.KEY_RELEASE, keycode, state, time);
        if (held.isEmpty() && keyboardGrabbed) { x.ungrabKeyboard(); keyboardGrabbed = false; }
        if (next != null) redispatch(next);
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
