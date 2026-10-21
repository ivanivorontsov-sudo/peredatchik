package ru.peredatchik.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * Защищённый канал поверх любого потока (TCP, Bluetooth RFCOMM, ретранслятор).
 *
 * Рукопожатие: обе стороны шлют открытый hello {id,name,spub,epub,n}.
 * Ключи: HKDF(ECDH(eph,eph) || ECDH(static,static), salt = SHA256(helloC||helloS), "peredatchik v1") -> 2x32 байта.
 * Статический ECDH аутентифицирует обе стороны по закреплённым ключам. Новое устройство
 * допускается только в окне сопряжения и только с HMAC(код, транскрипт).
 * Кадры: [длина u32 BE][AES-256-GCM(тип||данные)], nonce = 0000||счётчик u64 BE.
 */
public final class SecureChannel implements Closeable {
    public static final int VERSION = 1;
    public static final int MAX_FRAME = (1 << 20) + 1024;
    public static final byte T_JSON = 'J', T_DATA = 'D';

    private final DataInputStream in;
    private final DataOutputStream out;
    private final Closeable link;
    private byte[] kSend, kRecv;
    private long sendCtr, recvCtr;
    public Peer peer;
    public boolean newlyPaired;

    private SecureChannel(InputStream in, OutputStream out, Closeable link) {
        this.in = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        this.out = new DataOutputStream(new BufferedOutputStream(out, 1 << 16));
        this.link = link;
    }

    // ---------- кадры ----------
    private void writeFrame(byte[] b) throws IOException {
        out.writeInt(b.length); out.write(b); out.flush();
    }

    private byte[] readFrame() throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_FRAME) throw new ProtocolException("слишком большой кадр");
        byte[] b = new byte[n]; in.readFully(b); return b;
    }

    private static byte[] nonce(long c) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[4 + i] = (byte) (c >>> (56 - 8 * i));
        return n;
    }

    public void send(byte type, byte[] data, int off, int len) throws IOException {
        byte[] p = new byte[len + 1]; p[0] = type; System.arraycopy(data, off, p, 1, len);
        try { writeFrame(Crypto.gcm(true, kSend, nonce(sendCtr++), p)); }
        catch (GeneralSecurityException e) { throw new IOException(e); }
    }

    public void sendJson(Map<String, Object> m) throws IOException {
        byte[] b = Json.write(m).getBytes(StandardCharsets.UTF_8); send(T_JSON, b, 0, b.length);
    }

    /** Возвращает расшифрованный кадр: [0] — тип. */
    public byte[] recv() throws IOException {
        byte[] f = readFrame();
        try { return Crypto.gcm(false, kRecv, nonce(recvCtr++), f); }
        catch (GeneralSecurityException e) { throw new ProtocolException("ошибка расшифровки — канал подменён или повреждён"); }
    }

    public Map<String, Object> recvJson() throws IOException {
        byte[] p = recv();
        if (p[0] != T_JSON) throw new ProtocolException("ожидалось сообщение, пришли данные");
        return Json.obj(new String(p, 1, p.length - 1, StandardCharsets.UTF_8));
    }

    @Override public void close() { try { if (link != null) link.close(); } catch (IOException ignored) {} }

    // ---------- рукопожатие ----------
    private static byte[] hello(Identity me, KeyPair eph, byte[] n) {
        return Json.write(Json.map("t", "hello", "v", (long) VERSION, "id", me.id, "name", me.name,
                "spub", b64(me.pub), "epub", b64(Crypto.encodePub(eph.getPublic())), "n", b64(n))).getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    private static byte[] unb64(Object s) { return Base64.getDecoder().decode((String) s); }

    private static final byte[] INFO = "peredatchik v1".getBytes(StandardCharsets.US_ASCII);

    private void derive(Identity me, KeyPair eph, Map<String, Object> other, byte[] th, boolean client) {
        byte[] otherS = unb64(other.get("spub")), otherE = unb64(other.get("epub"));
        byte[] e = Crypto.ecdh(eph.getPrivate(), Crypto.decodePub(otherE));
        byte[] s = Crypto.ecdh(me.priv, Crypto.decodePub(otherS));
        byte[] ikm = new byte[64]; System.arraycopy(e, 0, ikm, 0, 32); System.arraycopy(s, 0, ikm, 32, 32);
        byte[] okm = Crypto.hkdf(ikm, th, INFO, 64);
        byte[] c2s = Arrays.copyOfRange(okm, 0, 32), s2c = Arrays.copyOfRange(okm, 32, 64);
        kSend = client ? c2s : s2c; kRecv = client ? s2c : c2s;
    }

    private static Map<String, Object> checkHello(byte[] raw) throws ProtocolException {
        Map<String, Object> h;
        try { h = Json.obj(new String(raw, StandardCharsets.UTF_8)); } catch (RuntimeException e) { throw new ProtocolException("это не «Передатчик»"); }
        if (!"hello".equals(h.get("t"))) throw new ProtocolException("это не «Передатчик»");
        if (Json.num(h.get("v")) != VERSION) throw new ProtocolException("несовместимая версия протокола");
        byte[] spub = unb64(h.get("spub"));
        if (!Crypto.fingerprint(spub).equals(h.get("id"))) throw new ProtocolException("id не совпадает с ключом");
        return h;
    }

    /**
     * Исходящее соединение. pairCode != null — сопряжение с новым устройством;
     * expectId (из QR) — дополнительно проверяется отпечаток ключа.
     */
    public static SecureChannel client(InputStream is, OutputStream os, Closeable link, Identity me, PeerStore store,
                                       String pairCode, String expectId) throws IOException {
        SecureChannel ch = new SecureChannel(is, os, link);
        try {
            KeyPair eph = Crypto.generate();
            byte[] hc = hello(me, eph, Crypto.random(16));
            ch.writeFrame(hc);
            byte[] hsRaw = ch.readFrame();
            Map<String, Object> hs = checkHello(hsRaw);
            byte[] th = Crypto.sha256(hc, hsRaw);
            ch.derive(me, eph, hs, th, true);
            String id = (String) hs.get("id");
            byte[] spub = unb64(hs.get("spub"));
            if (expectId != null && !expectId.equalsIgnoreCase(id)) throw new ProtocolException("отпечаток ключа не совпадает с QR-кодом");
            Peer known = store.get(id);
            if (pairCode == null) {
                if (known == null) throw new ProtocolException("устройство не сопряжено");
                if (!Crypto.eq(known.pub, spub)) throw new ProtocolException("ключ устройства изменился — сопрягите заново");
                ch.sendJson(Json.map("t", "auth"));
            } else {
                byte[] mac = Crypto.hmac(Crypto.codeKey(pairCode), "C".getBytes(), th);
                ch.sendJson(Json.map("t", "pair", "mac", b64(mac)));
            }
            Map<String, Object> r = ch.recvJson();
            if ("err".equals(r.get("t"))) throw new ProtocolException(String.valueOf(r.get("msg")));
            if (!"ok".equals(r.get("t"))) throw new ProtocolException("неожиданный ответ");
            if (pairCode != null) {
                byte[] mac = Crypto.hmac(Crypto.codeKey(pairCode), "S".getBytes(), th);
                if (r.get("mac") == null || !Crypto.eq(mac, unb64(r.get("mac")))) throw new ProtocolException("ответ сопряжения не подтверждён");
                Peer p = known != null && Crypto.eq(known.pub, spub) ? known : new Peer(id, spub, (String) hs.get("name"));
                p.name = (String) hs.get("name");
                store.put(p);
                ch.newlyPaired = true;
                known = p;
            }
            ch.peer = known;
            return ch;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException ? (IOException) e : new ProtocolException("ошибка рукопожатия: " + e.getMessage());
        }
    }

    /** Входящее соединение. pairing — текущее окно сопряжения (может быть null). */
    public static SecureChannel server(InputStream is, OutputStream os, Closeable link, Identity me, PeerStore store,
                                       Pairing pairing) throws IOException {
        SecureChannel ch = new SecureChannel(is, os, link);
        try {
            byte[] hcRaw = ch.readFrame();
            Map<String, Object> hc = checkHello(hcRaw);
            KeyPair eph = Crypto.generate();
            byte[] hs = hello(me, eph, Crypto.random(16));
            ch.writeFrame(hs);
            byte[] th = Crypto.sha256(hcRaw, hs);
            ch.derive(me, eph, hc, th, false);
            String id = (String) hc.get("id");
            byte[] spub = unb64(hc.get("spub"));
            Peer known = store.get(id);
            Map<String, Object> m = ch.recvJson();
            if ("auth".equals(m.get("t"))) {
                if (known == null || !Crypto.eq(known.pub, spub)) {
                    ch.sendJson(Json.map("t", "err", "msg", "устройство не сопряжено с «" + me.name + "»"));
                    throw new ProtocolException("попытка подключения несопряжённого устройства");
                }
                ch.sendJson(Json.map("t", "ok"));
            } else if ("pair".equals(m.get("t"))) {
                if (pairing == null || !pairing.active()) {
                    ch.sendJson(Json.map("t", "err", "msg", "на «" + me.name + "» не открыто окно сопряжения"));
                    throw new ProtocolException("сопряжение не открыто");
                }
                byte[] mac = Crypto.hmac(Crypto.codeKey(pairing.code), "C".getBytes(), th);
                if (m.get("mac") == null || !Crypto.eq(mac, unb64(m.get("mac")))) {
                    pairing.fail();
                    ch.sendJson(Json.map("t", "err", "msg", "неверный код сопряжения"));
                    throw new ProtocolException("неверный код");
                }
                pairing.done();
                Peer p = new Peer(id, spub, (String) hc.get("name"));
                if (known != null && Crypto.eq(known.pub, spub)) { p.lastHost = known.lastHost; p.lastPort = known.lastPort; p.btAddress = known.btAddress; }
                store.put(p);
                known = p;
                ch.newlyPaired = true;
                ch.sendJson(Json.map("t", "ok", "mac", b64(Crypto.hmac(Crypto.codeKey(pairing.code), "S".getBytes(), th))));
            } else throw new ProtocolException("неожиданное сообщение");
            known.name = (String) hc.get("name");
            ch.peer = known;
            return ch;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException ? (IOException) e : new ProtocolException("ошибка рукопожатия: " + e.getMessage());
        }
    }
}
