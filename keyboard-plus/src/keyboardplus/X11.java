package keyboardplus;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal X11 client speaking the protocol directly over the display socket (no Xlib, no JNA).
 * Works in an X11 session and for X11 applications under Wayland (Xwayland).
 *
 * Connection setup, authentication and the property/X-Resource requests follow Patchbay's
 * X11WindowClose (MIT licence, (c) 2026 shaboogen). Added here: a non-blocking reader with an
 * event queue, key grabs, keyboard mapping and SendEvent for key events.
 */
final class X11 implements Closeable {
    static final int BAD_WINDOW = 3, BAD_ACCESS = 10;
    static final int ATOM_ATOM = 4, ATOM_CARDINAL = 6, ATOM_STRING = 31, ATOM_WM_NAME = 39, ATOM_WM_CLASS = 67;
    static final int KEY_PRESS = 2, KEY_RELEASE = 3;
    static final int LOCK_MASK = 1 << 1;
    private static final long REPLY_TIMEOUT_MS = 5_000;

    /** An event or an error the server sent, as its 32 bytes. Errors have type 0. */
    record Event(ByteBuffer data) {
        int type() { return Byte.toUnsignedInt(data.get(0)) & 0x7f; }
        int sequence() { return Short.toUnsignedInt(data.getShort(2)); }
    }

    private final SocketChannel channel;
    private final Selector selector;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    long root;
    int minKeycode, maxKeycode;
    private int sequence;
    int wmProtocols, netWmPid, netWmName, utf8String, xresOpcode = -1;

    private X11(SocketChannel channel) throws IOException {
        this.channel = channel;
        this.selector = Selector.open();
    }

    static X11 open() throws IOException {
        String display = System.getenv("DISPLAY");
        if (display == null || display.isBlank()) throw new IOException("No X11 display (DISPLAY is not set): Bitwig isn't running under X11/Xwayland.");
        int colon = display.lastIndexOf(':');
        if (colon < 0) throw new IOException("Unsupported DISPLAY: " + display);
        String host = display.substring(0, colon), rest = display.substring(colon + 1);
        int dot = rest.indexOf('.');
        int number, screen;
        try {
            number = Integer.parseInt(dot < 0 ? rest : rest.substring(0, dot));
            screen = dot < 0 ? 0 : Integer.parseInt(rest.substring(dot + 1));
        } catch (NumberFormatException ex) { throw new IOException("Unsupported DISPLAY: " + display); }
        boolean local = host.isEmpty() || host.equals("unix");
        SocketChannel ch;
        if (local) {
            ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of("/tmp/.X11-unix/X" + number));
        } else {
            ch = SocketChannel.open(new InetSocketAddress(host, 6000 + number));
        }
        X11 x = new X11(ch);
        try {
            x.setup(Integer.toString(number), screen, local);
            ch.configureBlocking(false);
            ch.register(x.selector, SelectionKey.OP_READ);
            x.internAtoms();
            return x;
        } catch (IOException | RuntimeException ex) {
            x.close();
            throw ex;
        }
    }

    @Override public void close() {
        try { selector.close(); } catch (IOException ignored) {}
        try { channel.close(); } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ events

    /** The next event or error, waiting up to {@code timeoutMs}; null when none came. */
    Event nextEvent(long timeoutMs) throws IOException {
        if (!events.isEmpty()) return events.poll();
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteBuffer head = readWithin(32, deadline, true);
        if (head == null) return null;
        int type = Byte.toUnsignedInt(head.get(0));
        if (type == 1) { skipReply(head, deadline); return null; } // a stray reply
        return new Event(head);
    }

    // ------------------------------------------------------------------ windows

    List<Long> children(long window) throws IOException {
        ByteBuffer b = request(15, 0, 2);       // QueryTree
        b.putInt((int) window);
        ByteBuffer r = call(b);
        if (r == null) return List.of();
        int n = Short.toUnsignedInt(r.getShort(16));
        List<Long> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(Integer.toUnsignedLong(r.getInt(32 + 4 * i)));
        return out;
    }

    /** 0 unmapped, 1 unviewable, 2 viewable; -1 if the window is gone. */
    int mapState(long window) throws IOException {
        ByteBuffer b = request(3, 0, 2);        // GetWindowAttributes
        b.putInt((int) window);
        ByteBuffer r = call(b);
        return r == null ? -1 : Byte.toUnsignedInt(r.get(26));
    }

    int[] property32(long window, int property, int type) throws IOException {
        ByteBuffer r = getProperty(window, property, type, 64);
        if (r == null || r.get(1) != 32 || r.getInt(8) != type) return null;
        int n = r.getInt(16);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = r.getInt(32 + 4 * i);
        return out;
    }

    String propertyString(long window, int property, int type) throws IOException {
        ByteBuffer r = getProperty(window, property, type, 256);
        if (r == null || r.get(1) != 8 || r.getInt(8) != type) return null;
        byte[] bytes = new byte[r.getInt(16)];
        r.get(32, bytes);
        return new String(bytes, type == ATOM_STRING ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
    }

    private ByteBuffer getProperty(long window, int property, int type, int maxWords) throws IOException {
        ByteBuffer b = request(20, 0, 6);       // GetProperty, delete=false
        b.putInt((int) window).putInt(property).putInt(type).putInt(0).putInt(maxWords);
        return call(b);
    }

    /** The window's title (_NET_WM_NAME, else WM_NAME), or "". */
    String title(long window) throws IOException {
        String name = netWmName != 0 ? propertyString(window, netWmName, utf8String) : null;
        if (name == null) name = propertyString(window, ATOM_WM_NAME, ATOM_STRING);
        return name == null ? "" : name;
    }

    String wmClass(long window) throws IOException {
        String c = propertyString(window, ATOM_WM_CLASS, ATOM_STRING);
        return c == null ? "" : c.replace('\0', ' ').trim();
    }

    /** The PID of the process owning the window: _NET_WM_PID, else the X server's record (X-Resource). */
    long pid(long window, boolean tryNetWmPid) throws IOException {
        if (tryNetWmPid && netWmPid != 0) {
            int[] p = property32(window, netWmPid, ATOM_CARDINAL);
            if (p != null && p.length > 0 && p[0] != 0) return Integer.toUnsignedLong(p[0]);
        }
        return clientPid(window);
    }

    private long clientPid(long window) throws IOException {
        if (xresOpcode < 0) return -1;
        ByteBuffer b = request(xresOpcode, 4, 4);   // XResQueryClientIds, one spec
        b.putInt(1).putInt((int) window).putInt(2); // mask: LocalClientPID
        ByteBuffer r = call(b);
        if (r == null || r.getInt(8) < 1 || r.limit() < 48 || r.getInt(40) < 4) return -1;
        return Integer.toUnsignedLong(r.getInt(44));
    }

    boolean hasWmProtocols(long window) throws IOException {
        return wmProtocols != 0 && property32(window, wmProtocols, ATOM_ATOM) != null;
    }

    /** Asks for SubstructureNotify on the root, so new and closed windows are seen. */
    void watchRoot() throws IOException {
        ByteBuffer b = request(2, 0, 4);        // ChangeWindowAttributes
        b.putInt((int) root).putInt(0x800).putInt(0x80000); // CWEventMask: SubstructureNotifyMask
        send(b);
    }

    // ------------------------------------------------------------------ keyboard

    /** The keycodes carrying a keysym, from the server's keyboard mapping. */
    List<Integer> keycodesFor(int keysym) throws IOException {
        int count = maxKeycode - minKeycode + 1;
        ByteBuffer b = request(101, 0, 2);      // GetKeyboardMapping
        b.put((byte) minKeycode).put((byte) count).putShort((short) 0);
        ByteBuffer r = call(b);
        List<Integer> out = new ArrayList<>();
        if (r == null) return out;
        int per = Byte.toUnsignedInt(r.get(1));
        for (int k = 0; k < count; k++)
            for (int i = 0; i < per; i++)
                if (r.getInt(32 + 4 * (k * per + i)) == keysym) { out.add(minKeycode + k); break; }
        return out;
    }

    /** The modifier mask holding a keycode (e.g. NumLock's), or 0. */
    int modifierMaskOf(List<Integer> keycodes) throws IOException {
        ByteBuffer r = call(request(119, 0, 1)); // GetModifierMapping
        if (r == null) return 0;
        int per = Byte.toUnsignedInt(r.get(1));
        for (int mod = 0; mod < 8; mod++)
            for (int i = 0; i < per; i++) {
                int kc = Byte.toUnsignedInt(r.get(32 + mod * per + i));
                if (kc != 0 && keycodes.contains(kc)) return 1 << mod;
            }
        return 0;
    }

    /** Passive grab of one key with exact modifiers on a window; returns the request's sequence. */
    int grabKey(long window, int keycode, int modifiers) throws IOException {
        ByteBuffer b = request(33, 0, 4);       // GrabKey, owner_events=false
        b.putInt((int) window).putShort((short) modifiers).put((byte) keycode)
         .put((byte) 1).put((byte) 1);          // pointer and keyboard: asynchronous
        send(b);
        return sequence;
    }

    void ungrabKey(long window, int keycode, int modifiers) throws IOException {
        ByteBuffer b = request(34, keycode, 3); // UngrabKey
        b.putInt((int) window).putShort((short) modifiers).putShort((short) 0);
        send(b);
    }

    /** Active keyboard grab: every key comes here until {@link #ungrabKeyboard}. Returns X's status (0 = success). */
    int grabKeyboard(long window, int time) throws IOException {
        ByteBuffer b = request(31, 0, 4);       // GrabKeyboard, owner_events=false
        b.putInt((int) window).putInt(time).put((byte) 1).put((byte) 1).putShort((short) 0); // async, async
        ByteBuffer r = call(b);
        return r == null ? -1 : Byte.toUnsignedInt(r.get(1));
    }

    void ungrabKeyboard() throws IOException {
        ByteBuffer b = request(32, 0, 2);       // UngrabKeyboard
        b.putInt(0);                            // CurrentTime
        send(b);
    }

    /** Sends a synthetic KeyPress or KeyRelease to the window's owner. */
    void sendKey(long window, int type, int keycode, int state, int time) throws IOException {
        ByteBuffer b = request(25, 0, 11);      // SendEvent, propagate=false
        b.putInt((int) window);
        b.putInt(0);                            // empty event mask: delivered to the window's creator
        b.put((byte) type).put((byte) keycode).putShort((short) 0);
        b.putInt(time).putInt((int) root).putInt((int) window).putInt(0); // time, root, event, child
        b.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0); // root x/y, event x/y
        b.putShort((short) state).put((byte) 1).put((byte) 0); // state, same_screen, pad
        send(b);
    }

    /** Round trip: every earlier request has been handled; their errors are now in the queue. */
    void sync() throws IOException {
        call(request(43, 0, 1));                // GetInputFocus
    }

    /** The window holding the keyboard focus (0 none, 1 PointerRoot). */
    long inputFocus() throws IOException {
        ByteBuffer r = call(request(43, 0, 1));
        return r == null ? 0 : Integer.toUnsignedLong(r.getInt(8));
    }

    // ------------------------------------------------------------------ setup

    private void internAtoms() throws IOException {
        wmProtocols = atom("WM_PROTOCOLS");
        netWmPid = atom("_NET_WM_PID");
        netWmName = atom("_NET_WM_NAME");
        utf8String = atom("UTF8_STRING");
        if (netWmName != 0 && utf8String == 0) netWmName = 0;
        int xres = extensionOpcode("X-Resource");
        if (xres >= 0) {
            ByteBuffer b = request(xres, 0, 2);     // XResQueryVersion 1.2
            b.put((byte) 1).put((byte) 2).putShort((short) 0);
            ByteBuffer r = call(b);
            if (r != null) {
                int major = Short.toUnsignedInt(r.getShort(8)), minor = Short.toUnsignedInt(r.getShort(10));
                if (major > 1 || (major == 1 && minor >= 2)) xresOpcode = xres;
            }
        }
    }

    private int atom(String name) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer b = request(16, 0, 2 + (n.length + 3) / 4);  // InternAtom (created if needed: a client may set it later)
        b.putShort((short) n.length).putShort((short) 0).put(n);
        ByteBuffer r = call(b);
        return r == null ? 0 : r.getInt(8);
    }

    private int extensionOpcode(String name) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer b = request(98, 0, 2 + (n.length + 3) / 4);  // QueryExtension
        b.putShort((short) n.length).putShort((short) 0).put(n);
        ByteBuffer r = call(b);
        return r == null || r.get(8) == 0 ? -1 : Byte.toUnsignedInt(r.get(9));
    }

    private void setup(String displayNumber, int screen, boolean local) throws IOException {
        byte[][] auth = XAuthority.cookie(displayNumber, local);
        byte[] name = auth[0], data = auth[1];
        ByteBuffer b = ByteBuffer.allocate(12 + pad(name.length) + pad(data.length)).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'l').put((byte) 0).putShort((short) 11).putShort((short) 0);
        b.putShort((short) name.length).putShort((short) data.length).putShort((short) 0);
        b.put(name).position(12 + pad(name.length)).put(data).position(b.capacity());
        writeBlocking(b);
        ByteBuffer head = readBlocking(8);
        int status = head.get(0);
        ByteBuffer body = readBlocking(Short.toUnsignedInt(head.getShort(6)) * 4);
        if (status != 1) {
            int len = status == 0 ? Byte.toUnsignedInt(head.get(1)) : body.limit();
            byte[] reason = new byte[Math.min(len, body.limit())];
            body.get(0, reason);
            throw new IOException("The X server refused the connection: " + new String(reason, StandardCharsets.ISO_8859_1).trim());
        }
        int vendor = Short.toUnsignedInt(body.getShort(16));
        int screens = Byte.toUnsignedInt(body.get(20)), formats = Byte.toUnsignedInt(body.get(21));
        minKeycode = Byte.toUnsignedInt(body.get(26));
        maxKeycode = Byte.toUnsignedInt(body.get(27));
        int at = 32 + pad(vendor) + 8 * formats;
        if (screen >= screens) screen = 0;
        for (int s = 0; s < screen; s++) {
            int depths = Byte.toUnsignedInt(body.get(at + 39));
            at += 40;
            for (int d = 0; d < depths; d++) at += 8 + 24 * Short.toUnsignedInt(body.getShort(at + 2));
        }
        root = Integer.toUnsignedLong(body.getInt(at));
    }

    // ------------------------------------------------------------------ wire

    static final class XError extends IOException {
        final int code;
        XError(int code, int seq) { super("X11 error " + code + " for request " + seq); this.code = code; }
    }

    private ByteBuffer request(int opcode, int data, int words) {
        ByteBuffer b = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) opcode).put((byte) data).putShort((short) words);
        return b;
    }

    private void send(ByteBuffer b) throws IOException {
        b.position(b.capacity());
        b.flip();
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
        while (b.hasRemaining()) {
            if (channel.write(b) == 0) {
                if (System.currentTimeMillis() > deadline) throw new IOException("X server not accepting requests");
                Thread.onSpinWait();
            }
        }
        sequence = (sequence + 1) & 0xffff;
    }

    /** Sends a request with a reply; null if the window it names is gone. */
    private ByteBuffer call(ByteBuffer b) throws IOException {
        send(b);
        int expected = sequence;
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
        for (;;) {
            ByteBuffer head = readWithin(32, deadline, false);
            int type = Byte.toUnsignedInt(head.get(0)), seq = Short.toUnsignedInt(head.getShort(2));
            if (type == 0 && seq == expected) {
                int code = Byte.toUnsignedInt(head.get(1));
                if (code == BAD_WINDOW) return null;
                throw new XError(code, seq);
            }
            if (type == 1) {
                ByteBuffer full = completeReply(head, deadline);
                if (seq == expected) return full;
                continue;                       // an earlier reply nobody waits for
            }
            events.add(new Event(head));        // an event, or an error for an earlier request
        }
    }

    private ByteBuffer completeReply(ByteBuffer head, long deadline) throws IOException {
        long extra = Integer.toUnsignedLong(head.getInt(4)) * 4;
        if (extra > 64L * 1024 * 1024) throw new IOException("X11 reply too large");
        ByteBuffer full = ByteBuffer.allocate(32 + (int) extra).order(ByteOrder.LITTLE_ENDIAN);
        full.put(head);
        if (extra > 0) full.put(readWithin((int) extra, deadline, false));
        return full.flip();
    }

    private void skipReply(ByteBuffer head, long deadline) throws IOException {
        completeReply(head, Math.max(deadline, System.currentTimeMillis() + REPLY_TIMEOUT_MS));
    }

    /**
     * Reads n bytes. With {@code mayTimeOut}, returns null if not even the first byte came by the
     * deadline; once a message has started it is read to the end.
     */
    private ByteBuffer readWithin(int n, long deadline, boolean mayTimeOut) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int got = channel.read(b);
            if (got < 0) throw new EOFException("The X server closed the connection.");
            if (got > 0) continue;
            long now = System.currentTimeMillis();
            boolean started = b.position() > 0;
            if (now >= deadline) {
                if (mayTimeOut && !started) return null;
                if (!started || now >= deadline + REPLY_TIMEOUT_MS) throw new IOException("X server not answering");
            }
            selector.selectedKeys().clear();
            selector.select(Math.max(1, (started ? deadline + REPLY_TIMEOUT_MS : deadline) - now));
        }
        return b.flip();
    }

    private void writeBlocking(ByteBuffer b) throws IOException {
        b.flip();
        while (b.hasRemaining()) channel.write(b);
    }

    private ByteBuffer readBlocking(int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) if (channel.read(b) < 0) throw new EOFException("The X server closed the connection.");
        return b.flip();
    }

    private static int pad(int n) { return (n + 3) & ~3; }

    /** The MIT-MAGIC-COOKIE-1 for a display from $XAUTHORITY (or ~/.Xauthority); none if absent. */
    private static final class XAuthority {
        private static final int FAMILY_LOCAL = 256, FAMILY_WILD = 65535;

        static byte[][] cookie(String displayNumber, boolean local) {
            byte[][] none = {new byte[0], new byte[0]};
            String file = System.getenv("XAUTHORITY");
            Path path = file != null && !file.isBlank() ? Path.of(file) : Path.of(System.getProperty("user.home", ""), ".Xauthority");
            if (!Files.isRegularFile(path)) return none;
            String host = hostname();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                for (;;) {
                    int family;
                    try { family = in.readUnsignedShort(); } catch (EOFException end) { return none; }
                    String address = new String(field(in), StandardCharsets.ISO_8859_1);
                    String number = new String(field(in), StandardCharsets.ISO_8859_1);
                    byte[] name = field(in), data = field(in);
                    boolean hostMatches = family == FAMILY_WILD || (local ? family == FAMILY_LOCAL && address.equals(host) : family != FAMILY_LOCAL);
                    if (hostMatches && (number.isEmpty() || number.equals(displayNumber))
                            && new String(name, StandardCharsets.ISO_8859_1).equals("MIT-MAGIC-COOKIE-1"))
                        return new byte[][]{name, data};
                }
            } catch (IOException unreadable) {
                return none;
            }
        }

        private static byte[] field(DataInputStream in) throws IOException {
            byte[] b = new byte[in.readUnsignedShort()];
            in.readFully(b);
            return b;
        }

        private static String hostname() {
            try { return Files.readString(Path.of("/proc/sys/kernel/hostname")).trim(); }
            catch (IOException ex) {
                String h = System.getenv("HOSTNAME");
                return h == null ? "" : h;
            }
        }
    }
}
