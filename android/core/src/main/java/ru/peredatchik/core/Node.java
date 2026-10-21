package ru.peredatchik.core;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Узел «Передатчика»: принимает и отправляет. Обе стороны равноправны. */
public final class Node {
    public static final int DEFAULT_PORT = 47232;

    public interface Listener {
        default void onReceived(Peer from, List<String> saved) {}
        default void onPaired(Peer p) {}
        default void onIncomingProgress(Peer from, String name, int i, int n, long done, long total) {}
        default void onError(String msg) {}
    }

    /** Платформенный способ открыть Bluetooth-поток к устройству. */
    public interface BluetoothOpener { Link open(Peer p) throws IOException; }

    public final Identity me;
    public final PeerStore store;
    private final Sink sink;
    private final Listener listener;
    public volatile Pairing pairing;
    public volatile BluetoothOpener bluetooth;
    public volatile String relayHost; public volatile int relayPort = 47233;
    public volatile int connectTimeoutMs = 2500;
    private ServerSocket server;
    private int port;
    public Discovery discovery;
    private volatile boolean running;
    private final Map<String, Thread> relayWaiters = new ConcurrentHashMap<>();

    public Node(Identity me, PeerStore store, Sink sink, Listener listener) {
        this.me = me; this.store = store; this.sink = sink; this.listener = listener;
    }

    public int port() { return port; }

    /** Запустить TCP-приём (на всех интерфейсах) и обнаружение. port=0 — любой свободный. */
    public void start(int wantPort, boolean discover) throws IOException {
        running = true;
        server = new ServerSocket();
        server.setReuseAddress(true);
        try { server.bind(new InetSocketAddress(wantPort)); }
        catch (BindException e) { server.bind(new InetSocketAddress(0)); }
        port = server.getLocalPort();
        Thread t = new Thread(this::acceptLoop, "prd-accept"); t.setDaemon(true); t.start();
        if (discover) { discovery = new Discovery(me, port); discovery.start(); }
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (discovery != null) discovery.stop();
        for (Thread t : relayWaiters.values()) t.interrupt();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setSoTimeout(60_000);
                Link l = Link.of(s, s.getInetAddress().getHostAddress());
                new Thread(() -> handleIncoming(l), "prd-in").start();
            } catch (IOException e) { if (!running) return; }
        }
    }

    /** Обработать входящий поток (TCP, Bluetooth, ретранслятор). */
    public void handleIncoming(Link l) {
        SecureChannel ch = null;
        try {
            ch = SecureChannel.server(l.in, l.out, l, me, store, pairing);
            Peer p = ch.peer;
            if (l.description != null && l.description.matches("[0-9.]+")) { p.lastHost = l.description; store.put(p); }
            if (ch.newlyPaired) { listener.onPaired(p); startRelayWaiter(p); }
            final Peer from = p;
            List<String> saved = Transfer.receive(ch, sink, (n, i, c, d, t) -> listener.onIncomingProgress(from, n, i, c, d, t));
            if (!saved.isEmpty()) listener.onReceived(p, saved);
        } catch (IOException e) {
            listener.onError("Приём: " + e.getMessage());
        } finally {
            if (ch != null) ch.close(); else l.close();
        }
    }

    // ---------- исходящие ----------

    /** Список попыток подключения для способа. */
    private List<Attempt> attempts(Peer p, Method m, String manualHost, int manualPort) {
        List<Attempt> a = new ArrayList<>();
        Discovery.Seen seen = discovery != null ? discovery.get(p.id) : null;
        boolean wifi = m == Method.AUTO || m == Method.WIFI;
        if (wifi && seen != null) a.add(new Attempt("Wi-Fi " + seen.host, () -> Link.tcp(seen.host, seen.port, connectTimeoutMs)));
        if (wifi && p.lastHost != null && (seen == null || !p.lastHost.equals(seen.host)))
            a.add(new Attempt("Wi-Fi " + p.lastHost, () -> Link.tcp(p.lastHost, p.lastPort > 0 ? p.lastPort : DEFAULT_PORT, connectTimeoutMs)));
        if (m == Method.AUTO || m == Method.USB_ADB)
            a.add(new Attempt("USB (adb reverse)", () -> Link.tcp("127.0.0.1", DEFAULT_PORT + 20, 800)));
        if ((m == Method.AUTO || m == Method.BLUETOOTH) && bluetooth != null && p.btAddress != null)
            a.add(new Attempt("Bluetooth", () -> bluetooth.open(p)));
        if ((m == Method.AUTO || m == Method.RELAY) && relayHost != null && !relayHost.isEmpty())
            a.add(new Attempt("интернет-ретранслятор", () -> Link.relay(relayHost, relayPort, Peer.relayRoom(me.pub, p.pub), "c", 8000)));
        if (m == Method.MANUAL && manualHost != null)
            a.add(new Attempt("вручную " + manualHost, () -> Link.tcp(manualHost, manualPort > 0 ? manualPort : DEFAULT_PORT, 5000)));
        return a;
    }

    private interface Opener { Link open() throws IOException; }
    private static final class Attempt { final String name; final Opener o; Attempt(String n, Opener o) { name = n; this.o = o; } }

    /** Открыть защищённый канал к сопряжённому устройству. */
    public SecureChannel connect(Peer p, Method m, String manualHost, int manualPort, StringBuilder used) throws IOException {
        List<Attempt> list = attempts(p, m, manualHost, manualPort);
        if (list.isEmpty()) throw new IOException("нет доступного способа связи для «" + p.name + "» (" + m.title + ")");
        StringBuilder errs = new StringBuilder();
        for (Attempt a : list) {
            Link l = null;
            try {
                l = a.o.open();
                SecureChannel ch = SecureChannel.client(l.in, l.out, l, me, store, null, null);
                if (used != null) used.append(a.name);
                return ch;
            } catch (ProtocolException e) {
                throw e; // собеседник найден, но отказал — дальше пробовать бессмысленно
            } catch (IOException e) {
                if (l != null) l.close();
                errs.append("\n• ").append(a.name).append(": ").append(e.getMessage());
            }
        }
        throw new IOException("«" + p.name + "» недоступно:" + errs);
    }

    /** Отправить файлы, с автоматической докачкой при обрыве (до 3 попыток). */
    public String send(Peer p, List<FileSource> files, Method m, String manualHost, int manualPort, Progress pr) throws IOException {
        List<String> shas = new ArrayList<>();
        for (FileSource f : files) shas.add(Transfer.sha256Hex(f));
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            StringBuilder used = new StringBuilder();
            SecureChannel ch = null;
            try {
                ch = connect(p, m, manualHost, manualPort, used);
                Transfer.send(ch, files, shas, pr);
                return used.toString();
            } catch (ProtocolException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (ch == null) throw e; // не смогли даже подключиться
                try { Thread.sleep(1000); } catch (InterruptedException ie) { throw e; }
            } finally { if (ch != null) ch.close(); }
        }
        throw last;
    }

    /** Сопряжение с устройством, у которого открыто окно с кодом. */
    public Peer pair(Link l, String code, String expectId) throws IOException {
        SecureChannel ch = SecureChannel.client(l.in, l.out, l, me, store, code, expectId);
        try {
            Peer p = ch.peer;
            if (l.description != null && l.description.contains(":")) {
                String h = l.description.substring(0, l.description.lastIndexOf(':'));
                if (h.matches("[0-9.]+") && !h.equals("127.0.0.1")) { p.lastHost = h; p.lastPort = Integer.parseInt(l.description.substring(l.description.lastIndexOf(':') + 1)); }
            }
            store.put(p);
            ch.sendJson(Json.map("t", "bye"));
            listener.onPaired(p);
            startRelayWaiter(p);
            return p;
        } finally { ch.close(); }
    }

    // ---------- ретранслятор (ожидание входящих) ----------

    public void startRelayWaiters() { for (Peer p : store.all()) startRelayWaiter(p); }

    public void startRelayWaiter(Peer p) {
        if (relayHost == null || relayHost.isEmpty() || relayWaiters.containsKey(p.id)) return;
        Thread t = new Thread(() -> {
            while (running && relayHost != null && !relayHost.isEmpty()) {
                try {
                    Link l = Link.relay(relayHost, relayPort, Peer.relayRoom(me.pub, p.pub), "s", 0);
                    new Thread(() -> handleIncoming(l), "prd-relay-in").start();
                } catch (IOException e) {
                    try { Thread.sleep(15_000); } catch (InterruptedException ie) { break; }
                }
            }
            relayWaiters.remove(p.id);
        }, "prd-relay-wait");
        t.setDaemon(true);
        relayWaiters.put(p.id, t);
        t.start();
    }

    public void stopRelayWaiters() { for (Thread t : relayWaiters.values()) t.interrupt(); relayWaiters.clear(); }

    /** Строка для QR-кода сопряжения: prd1|host,host2|port|code|id|name */
    public String pairingUri(String code) {
        return "prd1|" + String.join(",", Discovery.localAddresses()) + "|" + port + "|" + code + "|" + me.id + "|" + me.name;
    }
}
