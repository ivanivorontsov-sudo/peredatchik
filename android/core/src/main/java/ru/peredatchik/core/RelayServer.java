package ru.peredatchik.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Простой ретранслятор: склеивает «s» и «c» с одной комнатой. Видит только зашифрованные байты.
 * Эквивалент есть в Peredatchik.Host (команда relay) для размещения на VPS.
 */
public final class RelayServer implements Closeable {
    private final ServerSocket ss;
    private final Map<String, Socket> waiting = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public RelayServer(int port) throws IOException {
        ss = new ServerSocket(port);
        Thread t = new Thread(this::loop, "relay"); t.setDaemon(true); t.start();
    }

    public int port() { return ss.getLocalPort(); }

    private void loop() {
        while (running) {
            try {
                Socket s = ss.accept();
                new Thread(() -> handle(s)).start();
            } catch (IOException e) { if (!running) return; }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10_000);
            String[] p = Link.readLine(s.getInputStream()).split(" ");
            s.setSoTimeout(0);
            if (p.length != 3 || !p[0].equals("PRDRELAY1") || !p[1].matches("[0-9a-f]{32}")) { s.close(); return; }
            if (p[2].equals("s")) {
                Socket old = waiting.put(p[1], s);
                if (old != null) old.close();
            } else {
                Socket peer = waiting.remove(p[1]);
                if (peer == null) { s.getOutputStream().write("ERR получатель не в сети\n".getBytes(StandardCharsets.UTF_8)); s.close(); return; }
                byte[] ok = "OK\n".getBytes(StandardCharsets.US_ASCII);
                peer.getOutputStream().write(ok); s.getOutputStream().write(ok);
                pipe(s, peer); pipe(peer, s);
            }
        } catch (IOException e) { try { s.close(); } catch (IOException ignored) {} }
    }

    private static void pipe(Socket a, Socket b) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[65536];
            try { InputStream in = a.getInputStream(); OutputStream out = b.getOutputStream(); int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException ignored) {}
            try { a.close(); } catch (IOException ignored) {}
            try { b.close(); } catch (IOException ignored) {}
        });
        t.setDaemon(true); t.start();
    }

    @Override public void close() throws IOException { running = false; ss.close(); }
}
