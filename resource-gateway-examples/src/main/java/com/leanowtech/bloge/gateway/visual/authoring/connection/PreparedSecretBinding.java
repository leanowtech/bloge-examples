package com.leanowtech.bloge.gateway.visual.authoring.connection;

import java.util.Objects;

/**
 * Narrow seam between a future Secret Store staging operation and Connection
 * authority. It carries an opaque handle only; plaintext never enters this
 * module's authority.
 *
 * @param slot auth slot, one of {@code token}, {@code password}, or {@code value}
 * @param reference scope-authorized opaque Secret Store reference
 */
public record PreparedSecretBinding(String slot, SecretReference reference) {
    public PreparedSecretBinding {
        if (slot == null || !slot.matches("token|password|value")) {
            throw new IllegalArgumentException("secret slot is invalid");
        }
        Objects.requireNonNull(reference, "reference");
    }

    @Override
    public String toString() {
        return "PreparedSecretBinding[slot=" + slot + ", reference=REDACTED]";
    }
}
