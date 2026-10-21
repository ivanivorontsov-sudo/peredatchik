package ru.peredatchik.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Поток к собеседнику: TCP, Bluetooth, ретранслятор. */
public final class Link implements Closeable {
    public final InputStream in;
    public final OutputStream out;
    public final String description;
    private final Closeable c;

    public Link(InputStream in, OutputStream out, Closeable c, String description) {
        this.in = in; this.out = out; this.c = c; this.description = description;
    }

    @Override public void close() { try { c.close(); } catch (IOException ignored) {} }

    public static Link tcp(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setTcpNoDelay(true);
            s.setSoTimeout(60_000);
        } catch (IOException e) { s.close(); throw e; }
        return new Link(s.getInputStream(), s.getOutputStream(), s, host + ":" + port);
    }

    public static Link of(Socket s, String d) throws IOException {
        s.setTcpNoDelay(true);
        return new Link(s.getInputStream(), s.getOutputStream(), s, d);
    }

    /**
     * Подключение через ретранслятор. role: "s" — ждать входящего, "c" — подключиться к ждущему.
     * Ретранслятор просто склеивает два сокета; содержимое зашифровано сквозным образом.
     */
    public static Link relay(String host, int port, String room, String role, int waitMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setTcpNoDelay(true);
            s.getOutputStream().write(("PRDRELAY1 " + room + " " + role + "\n").getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            s.setSoTimeout(waitMs);
            String line = readLine(s.getInputStream());
            if (!"OK".equals(line)) throw new IOException("ретранслятор: " + line);
            s.setSoTimeout(60_000);
        } catch (IOException e) { s.close(); throw e; }
        return new Link(s.getInputStream(), s.getOutputStream(), s, "ретранслятор " + host);
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("соединение закрыто");
            if (b == '\n') return sb.toString().trim();
            if (sb.length() > 200) throw new IOException("слишком длинная строка");
            sb.append((char) b);
        }
    }
}
