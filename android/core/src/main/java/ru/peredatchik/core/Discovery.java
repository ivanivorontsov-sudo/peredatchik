package ru.peredatchik.core;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Поиск в локальной сети: UDP-широковещание раз в 2 с на порт 47231. */
public final class Discovery {
    public static final int PORT = 47231;

    public static final class Seen {
        public final String id, name, host; public final int port; public final long time;
        Seen(String id, String name, String host, int port) { this.id = id; this.name = name; this.host = host; this.port = port; this.time = System.currentTimeMillis(); }
        public boolean fresh() { return System.currentTimeMillis() - time < 10_000; }
    }

    private final Identity me;
    private final int tcpPort;
    private final int udpPort;
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();
    private volatile boolean running;
    private DatagramSocket sock;
    public volatile Runnable onChange;

    public Discovery(Identity me, int tcpPort) { this(me, tcpPort, PORT); }
    public Discovery(Identity me, int tcpPort, int udpPort) { this.me = me; this.tcpPort = tcpPort; this.udpPort = udpPort; }

    public Seen get(String id) { Seen s = seen.get(id); return s != null && s.fresh() ? s : null; }
    public List<Seen> all() { List<Seen> l = new ArrayList<>(); for (Seen s : seen.values()) if (s.fresh()) l.add(s); return l; }

    public void start() {
        if (running) return;
        running = true;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.setBroadcast(true);
            sock.bind(new InetSocketAddress(udpPort));
        } catch (SocketException e) { running = false; return; }
        Thread rx = new Thread(this::receiveLoop, "prd-disc-rx"); rx.setDaemon(true); rx.start();
        Thread tx = new Thread(this::announceLoop, "prd-disc-tx"); tx.setDaemon(true); tx.start();
    }

    public void stop() { running = false; if (sock != null) sock.close(); }

    private byte[] packet() {
        return Json.write(Json.map("app", "peredatchik", "v", 1L, "id", me.id, "name", me.name, "port", (long) tcpPort))
                .getBytes(StandardCharsets.UTF_8);
    }

    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> l = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress a : ni.getInterfaceAddresses()) if (a.getBroadcast() != null) l.add(a.getBroadcast());
            }
        } catch (Exception ignored) {}
        try { l.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        return l;
    }

    /** Локальные IPv4-адреса (для QR и подсказок). */
    public static List<String> localAddresses() {
        List<String> l = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress a : ni.getInterfaceAddresses())
                    if (a.getAddress() instanceof Inet4Address) l.add(a.getAddress().getHostAddress());
            }
        } catch (Exception ignored) {}
        return l;
    }

    private void announceLoop() {
        while (running) {
            byte[] p = packet();
            for (InetAddress a : broadcastAddresses()) {
                try { sock.send(new DatagramPacket(p, p.length, a, udpPort)); } catch (Exception ignored) {}
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                sock.receive(dp);
                Map<String, Object> m = Json.obj(new String(dp.getData(), 0, dp.getLength(), StandardCharsets.UTF_8));
                if (!"peredatchik".equals(m.get("app"))) continue;
                String id = (String) m.get("id");
                if (id == null || id.equals(me.id)) continue;
                Seen old = seen.put(id, new Seen(id, (String) m.get("name"), dp.getAddress().getHostAddress(), (int) Json.num(m.get("port"))));
                Runnable r = onChange;
                if (old == null && r != null) r.run();
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }
}
