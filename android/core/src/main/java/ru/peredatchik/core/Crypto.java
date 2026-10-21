package ru.peredatchik.core;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;

/** ECDH P-256 + HKDF-SHA256 + AES-256-GCM. Тот же набор реализован в C# (Peredatchik.Core). */
public final class Crypto {
    private Crypto() {}
    private static final SecureRandom RNG = new SecureRandom();
    private static ECParameterSpec params;

    static synchronized ECParameterSpec params() {
        if (params == null) {
            try {
                AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
                ap.init(new ECGenParameterSpec("secp256r1"));
                params = ap.getParameterSpec(ECParameterSpec.class);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        return params;
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RNG);
            return g.generateKeyPair();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Несжатая точка 0x04||X||Y, 65 байт. */
    public static byte[] encodePub(PublicKey k) {
        ECPoint w = ((ECPublicKey) k).getW();
        byte[] out = new byte[65]; out[0] = 4;
        fixed(w.getAffineX(), out, 1); fixed(w.getAffineY(), out, 33);
        return out;
    }

    private static void fixed(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();
        int start = Math.max(0, b.length - 32), len = b.length - start;
        System.arraycopy(b, start, out, off + 32 - len, len);
    }

    public static PublicKey decodePub(byte[] p) {
        if (p.length != 65 || p[0] != 4) throw new IllegalArgumentException("плохой открытый ключ");
        try {
            ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(p, 1, 33)), new BigInteger(1, Arrays.copyOfRange(p, 33, 65)));
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, params()));
        } catch (GeneralSecurityException e) { throw new IllegalArgumentException(e); }
    }

    public static PrivateKey decodePriv(byte[] pkcs8) {
        try { return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8)); }
        catch (GeneralSecurityException e) { throw new IllegalArgumentException(e); }
    }

    public static byte[] ecdh(PrivateKey priv, PublicKey pub) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(priv); ka.doPhase(pub, true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    public static byte[] hmac(byte[] key, byte[]... data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] d : data) m.update(d);
            return m.doFinal();
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    public static byte[] sha256(byte[]... data) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256"); for (byte[] d : data) md.update(d); return md.digest(); }
        catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) {
        byte[] prk = hmac(salt, ikm);
        byte[] out = new byte[len], t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < len; i++) {
            t = hmac(prk, t, info, new byte[]{(byte) i});
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n); pos += n;
        }
        return out;
    }

    public static byte[] gcm(boolean enc, byte[] key, byte[] nonce, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return c.doFinal(data);
    }

    public static byte[] random(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString();
    }

    /** Отпечаток ключа: первые 8 байт SHA-256, hex. Он же — id устройства. */
    public static String fingerprint(byte[] pub) { return hex(Arrays.copyOf(sha256(pub), 8)); }

    public static byte[] codeKey(String code) { return sha256(("peredatchik-code:" + code.trim()).getBytes(StandardCharsets.UTF_8)); }

    public static boolean eq(byte[] a, byte[] b) { return MessageDigest.isEqual(a, b); }
}
