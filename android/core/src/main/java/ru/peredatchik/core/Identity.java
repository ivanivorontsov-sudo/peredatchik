package ru.peredatchik.core;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Base64;

/** Долговременный ключ устройства. Хранится платформой (privB64 + pubB64). */
public final class Identity {
    public final PrivateKey priv;
    public final byte[] pub;
    public final String id;
    public volatile String name;

    public Identity(PrivateKey priv, byte[] pub, String name) {
        this.priv = priv; this.pub = pub; this.name = name; this.id = Crypto.fingerprint(pub);
    }

    public static Identity create(String name) {
        KeyPair kp = Crypto.generate();
        return new Identity(kp.getPrivate(), Crypto.encodePub(kp.getPublic()), name);
    }

    public static Identity load(String privB64, String pubB64, String name) {
        return new Identity(Crypto.decodePriv(Base64.getDecoder().decode(privB64)), Base64.getDecoder().decode(pubB64), name);
    }

    public String privB64() { return Base64.getEncoder().encodeToString(priv.getEncoded()); }
    public String pubB64() { return Base64.getEncoder().encodeToString(pub); }
}
