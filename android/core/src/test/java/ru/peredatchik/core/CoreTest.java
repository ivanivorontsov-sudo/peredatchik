package ru.peredatchik.core;

import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class CoreTest {
    File tmp;
    @Before public void setUp() throws IOException { tmp = Files.createTempDirectory("prd").toFile(); }

    static class Rec implements Node.Listener {
        final List<String> got = Collections.synchronizedList(new ArrayList<>());
        final List<String> errs = Collections.synchronizedList(new ArrayList<>());
        public void onReceived(Peer f, List<String> s) { got.addAll(s); }
        public void onError(String m) { errs.add(m); }
    }

    File rnd(String name, int size) throws IOException {
        File f = new File(tmp, name); byte[] b = new byte[size]; new Random(size).nextBytes(b); Files.write(f.toPath(), b); return f;
    }

    void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 200 && !c.getAsBoolean(); i++) Thread.sleep(50);
        assertTrue(c.getAsBoolean());
    }

    @Test public void jsonRoundTrip() {
        String s = Json.write(Json.map("a", "При\"вет\n", "n", 5L, "l", Arrays.asList(1L, true, null)));
        Map<String, Object> m = Json.obj(s);
        assertEquals("При\"вет\n", m.get("a")); assertEquals(5L, m.get("n"));
    }

    @Test public void keyEncodingAndEcdh() {
        Identity a = Identity.create("a"), b = Identity.create("b");
        assertArrayEquals(a.pub, Crypto.encodePub(Crypto.decodePub(a.pub)));
        assertArrayEquals(Crypto.ecdh(a.priv, Crypto.decodePub(b.pub)), Crypto.ecdh(b.priv, Crypto.decodePub(a.pub)));
        Identity a2 = Identity.load(a.privB64(), a.pubB64(), "a");
        assertEquals(a.id, a2.id);
    }

    @Test public void hkdfRfc5869Case1() {
        byte[] ikm = new byte[22]; Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = new byte[13]; for (int i = 0; i < 13; i++) salt[i] = (byte) i;
        byte[] info = new byte[10]; for (int i = 0; i < 10; i++) info[i] = (byte) (0xf0 + i);
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                Crypto.hex(Crypto.hkdf(ikm, salt, info, 42)));
    }

    @Test public void pairThenSendMultipleFiles() throws Exception {
        Rec ra = new Rec();
        Node pc = new Node(Identity.create("ПК"), new MemoryPeerStore(), new DirSink(new File(tmp, "inPC")), ra);
        Node ph = new Node(Identity.create("Телефон"), new MemoryPeerStore(), new DirSink(new File(tmp, "inPh")), new Rec());
        pc.pairing = new Pairing("123456", 60_000);
        pc.start(0, false); ph.start(0, false);
        Peer p = ph.pair(Link.tcp("127.0.0.1", pc.port(), 2000), "123456", pc.me.id);
        assertEquals(pc.me.id, p.id);
        assertNotNull(pc.store.get(ph.me.id));
        File f1 = rnd("фото 1.jpg", 1_500_000), f2 = rnd("doc.pdf", 10), f3 = rnd("empty.txt", 0);
        String used = ph.send(p, Arrays.asList(FileSource.of(f1), FileSource.of(f2), FileSource.of(f3)), Method.MANUAL, "127.0.0.1", pc.port(), Progress.NONE);
        assertTrue(used.contains("вручную"));
        waitFor(() -> ra.got.size() == 3);
        assertArrayEquals(Files.readAllBytes(f1.toPath()), Files.readAllBytes(new File(tmp, "inPC/фото 1.jpg").toPath()));
        // обратное направление (смена ролей): ПК -> телефон
        Rec rb = new Rec();
        Node ph2 = ph; // тот же узел
        Peer back = pc.store.get(ph.me.id);
        pc.send(back, Collections.singletonList(FileSource.of(f2)), Method.MANUAL, "127.0.0.1", ph.port(), Progress.NONE);
        waitFor(() -> new File(tmp, "inPh/doc.pdf").exists());
        // повтор того же имени -> суффикс
        ph.send(p, Collections.singletonList(FileSource.of(f2)), Method.MANUAL, "127.0.0.1", pc.port(), Progress.NONE);
        waitFor(() -> new File(tmp, "inPC/doc (2).pdf").exists());
        pc.stop(); ph.stop();
    }

    @Test public void wrongCodeAndUnpairedRejected() throws Exception {
        Node pc = new Node(Identity.create("ПК"), new MemoryPeerStore(), new DirSink(new File(tmp, "in")), new Rec());
        Node ph = new Node(Identity.create("Тел"), new MemoryPeerStore(), new DirSink(new File(tmp, "in2")), new Rec());
        pc.pairing = new Pairing("111111", 60_000);
        pc.start(0, false);
        try { ph.pair(Link.tcp("127.0.0.1", pc.port(), 2000), "222222", null); fail(); }
        catch (ProtocolException e) { assertTrue(e.getMessage(), e.getMessage().contains("неверный код")); }
        // отправка без сопряжения: подделаем peer
        Peer fake = new Peer(pc.me.id, pc.me.pub, "ПК");
        ph.store.put(fake);
        try { ph.send(fake, Collections.singletonList(FileSource.of(rnd("a", 5))), Method.MANUAL, "127.0.0.1", pc.port(), Progress.NONE); fail(); }
        catch (ProtocolException e) { assertTrue(e.getMessage(), e.getMessage().contains("не сопряжено")); }
        // подмена ключа: другой узел выдаёт себя за ПК
        Node evil = new Node(Identity.create("ПК"), new MemoryPeerStore(), new DirSink(new File(tmp, "e")), new Rec());
        evil.start(0, false);
        try { ph.send(fake, Collections.singletonList(FileSource.of(rnd("b", 5))), Method.MANUAL, "127.0.0.1", evil.port(), Progress.NONE); fail(); }
        catch (ProtocolException e) { /* id не совпадает */ }
        // пять неверных кодов закрывают окно
        for (int i = 0; i < 5; i++) try { ph.pair(Link.tcp("127.0.0.1", pc.port(), 2000), "00000" + i, null); } catch (ProtocolException ignored) {}
        try { ph.pair(Link.tcp("127.0.0.1", pc.port(), 2000), "111111", null); fail(); } catch (ProtocolException e) { assertTrue(e.getMessage().contains("не открыто")); }
        pc.stop(); evil.stop();
    }

    @Test public void resumeFromPartial() throws Exception {
        Rec ra = new Rec();
        File inbox = new File(tmp, "in");
        Node pc = new Node(Identity.create("ПК"), new MemoryPeerStore(), new DirSink(inbox), ra);
        Node ph = new Node(Identity.create("Тел"), new MemoryPeerStore(), new DirSink(new File(tmp, "x")), new Rec());
        pc.pairing = new Pairing("123123", 60_000); pc.start(0, false);
        Peer p = ph.pair(Link.tcp("127.0.0.1", pc.port(), 2000), "123123", null);
        File big = rnd("big.bin", 3_000_000);
        String sha = Transfer.sha256Hex(FileSource.of(big));
        File part = new File(inbox, ".partial/" + ph.me.id + "-" + sha.substring(0, 16) + ".part");
        part.getParentFile().mkdirs();
        Files.write(part.toPath(), Arrays.copyOf(Files.readAllBytes(big.toPath()), 1_000_000));
        long[] first = {-1};
        ph.send(p, Collections.singletonList(FileSource.of(big)), Method.MANUAL, "127.0.0.1", pc.port(),
                (n, i, c, d, t) -> { if (first[0] < 0) first[0] = d; });
        assertEquals(1_000_000, first[0]);
        waitFor(() -> ra.got.size() == 1);
        assertArrayEquals(Files.readAllBytes(big.toPath()), Files.readAllBytes(new File(inbox, "big.bin").toPath()));
        // испорченная часть -> sha не сходится -> ошибка, часть удалена, повтор успешен
        Files.write(part.toPath(), new byte[500]);
        try { ph.send(p, Collections.singletonList(FileSource.of(big)), Method.MANUAL, "127.0.0.1", pc.port(), Progress.NONE); fail(); }
        catch (ProtocolException e) { assertTrue(e.getMessage().contains("повреждён")); }
        assertFalse(part.exists());
        ph.send(p, Collections.singletonList(FileSource.of(big)), Method.MANUAL, "127.0.0.1", pc.port(), Progress.NONE);
        waitFor(() -> ra.got.size() == 2);
        pc.stop();
    }

    @Test public void viaRelay() throws Exception {
        RelayServer relay = new RelayServer(0);
        Rec ra = new Rec();
        Node pc = new Node(Identity.create("ПК"), new MemoryPeerStore(), new DirSink(new File(tmp, "in")), ra);
        Node ph = new Node(Identity.create("Тел"), new MemoryPeerStore(), new DirSink(new File(tmp, "x")), new Rec());
        pc.pairing = new Pairing("654321", 60_000); pc.start(0, false); ph.start(0, false);
        Peer p = ph.pair(Link.tcp("127.0.0.1", pc.port(), 2000), "654321", null);
        pc.relayHost = "127.0.0.1"; pc.relayPort = relay.port(); pc.startRelayWaiters();
        ph.relayHost = "127.0.0.1"; ph.relayPort = relay.port();
        Thread.sleep(500);
        p.lastHost = null;
        String used = ph.send(p, Collections.singletonList(FileSource.of(rnd("r.jpg", 200_000))), Method.RELAY, null, 0, Progress.NONE);
        assertTrue(used.contains("ретранслятор"));
        waitFor(() -> ra.got.size() == 1);
        pc.stop(); ph.stop(); relay.close();
    }

    @Test public void discoveryFindsPeer() throws Exception {
        Identity a = Identity.create("A"), b = Identity.create("B");
        int port = 47000 + new Random().nextInt(500);
        Discovery da = new Discovery(a, 1111, port), db = new Discovery(b, 2222, port);
        da.start(); db.start();
        try {
            waitFor(() -> da.get(b.id) != null || db.get(a.id) != null);
        } finally { da.stop(); db.stop(); }
    }

    @Test public void safeNames() {
        assertEquals("evil.txt", Mime.safeName("../../evil.txt"));
        assertEquals("a_b.png", Mime.safeName("C:\\x\\a:b.png"));
        assertEquals("file", Mime.safeName(".."));
    }
}
