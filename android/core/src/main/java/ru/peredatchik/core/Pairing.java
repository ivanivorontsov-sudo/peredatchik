package ru.peredatchik.core;

import java.security.SecureRandom;

/** Окно сопряжения: одноразовый 6-значный код, срок действия, лимит попыток. */
public final class Pairing {
    public final String code;
    private final long expiresAt;
    private int failures;
    private boolean used;

    public Pairing(String code, long ttlMs) { this.code = code; this.expiresAt = System.currentTimeMillis() + ttlMs; }

    public static Pairing random(long ttlMs) {
        return new Pairing(String.format("%06d", new SecureRandom().nextInt(1_000_000)), ttlMs);
    }

    public synchronized boolean active() { return !used && failures < 5 && System.currentTimeMillis() < expiresAt; }
    public synchronized void fail() { failures++; }
    public synchronized void done() { used = true; }
    public long secondsLeft() { return Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000); }
}
