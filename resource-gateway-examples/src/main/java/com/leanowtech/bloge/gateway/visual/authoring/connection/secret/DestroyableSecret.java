package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A caller-owned secret material holder. The supplied array is cloned, so closing
 * this object cannot erase the caller's original immutable-in-practice input array.
 * Use {@link #borrow(Consumer)} for a bounded temporary copy that is erased in a
 * {@code finally} block; there is deliberately no uncontrolled value-copy accessor.
 */
public final class DestroyableSecret implements AutoCloseable {
    private char[] value;

    public DestroyableSecret(char[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length == 0 || value.length > 16_384) throw new IllegalArgumentException("secret value length is invalid");
        this.value = value.clone();
    }

    /** Supplies a temporary copy to a provider callback and erases it afterwards. */
    public void borrow(Consumer<char[]> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        final char[] borrowed;
        synchronized (this) {
            if (value == null) throw new IllegalStateException("secret is closed");
            borrowed = value.clone();
        }
        try {
            consumer.accept(borrowed);
        } finally {
            Arrays.fill(borrowed, '\0');
        }
    }

    public synchronized boolean isClosed() { return value == null; }

    @Override public synchronized void close() {
        if (value != null) { Arrays.fill(value, '\0'); value = null; }
    }

    @Override public String toString() { return "DestroyableSecret[REDACTED]"; }
}
