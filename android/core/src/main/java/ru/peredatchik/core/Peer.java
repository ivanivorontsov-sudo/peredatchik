package ru.peredatchik.core;

import java.util.Base64;
import java.util.Map;

/** Сопряжённое устройство с закреплённым ключом. */
public final class Peer {
    public final String id;
    public final byte[] pub;
    public volatile String name;
    public volatile String lastHost;
    public volatile int lastPort;
    public volatile String btAddress;

    public Peer(String id, byte[] pub, String name) { this.id = id; this.pub = pub; this.name = name; }

    public Map<String, Object> toMap() {
        return Json.map("id", id, "pub", Base64.getEncoder().encodeToString(pub), "name", name,
                "host", lastHost, "port", (long) lastPort, "bt", btAddress);
    }

    public static Peer fromMap(Map<String, Object> m) {
        byte[] pub = Base64.getDecoder().decode((String) m.get("pub"));
        Peer p = new Peer(Crypto.fingerprint(pub), pub, (String) m.get("name"));
        p.lastHost = (String) m.get("host");
        p.lastPort = (int) Json.num(m.get("port"));
        p.btAddress = (String) m.get("bt");
        return p;
    }

    /** Комната на ретрансляторе: одинаково вычисляется обеими сторонами. */
    public static String relayRoom(byte[] a, byte[] b) {
        boolean ab = Crypto.hex(a).compareTo(Crypto.hex(b)) < 0;
        return Crypto.hex(Crypto.sha256(ab ? a : b, ab ? b : a)).substring(0, 32);
    }
}
