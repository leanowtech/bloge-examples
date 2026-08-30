package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import java.util.Arrays;
import java.util.Objects;

/** A one-owner secret material holder. Its backing characters are erased on close. */
public final class DestroyableSecret implements AutoCloseable {
    private char[] value;

    public DestroyableSecret(char[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length == 0 || value.length > 16_384) throw new IllegalArgumentException("secret value length is invalid");
        this.value = value.clone();
    }

    /** Returns a defensive copy; throws after close so erased material cannot be read. */
    public synchronized char[] value() {
        if (value == null) throw new IllegalStateException("secret is closed");
        return value.clone();
    }

    public synchronized boolean isClosed() { return value == null; }

    @Override public synchronized void close() {
        if (value != null) { Arrays.fill(value, '\0'); value = null; }
    }

    @Override public String toString() { return "DestroyableSecret[REDACTED]"; }
}
