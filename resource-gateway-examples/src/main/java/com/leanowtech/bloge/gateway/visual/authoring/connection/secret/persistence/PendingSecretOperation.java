package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;

/** One slot in a staged batch, either newly prepared or retaining its old active locator. */
public sealed interface PendingSecretOperation permits PendingSecretOperation.Prepared,
        PendingSecretOperation.Retained {
    /** Slot name, one of {@code token}, {@code password}, or {@code value}. */
    String slot();

    /** Source mode persisted for this slot. */
    SecretSourceMode mode();

    /** A provider-prepared slot; preparation is never serialized. */
    record Prepared(String slot, SecretSourceMode mode, PreparedExternalSecret prepared)
            implements PendingSecretOperation {
        public Prepared {
            SlotRules.require(slot);
            if (mode == null || mode == SecretSourceMode.KEEP_EXISTING || prepared == null) {
                throw new IllegalArgumentException("prepared operation is invalid");
            }
            if (!slot.equals(prepared.context().slot())) {
                throw new IllegalArgumentException("prepared slot is invalid");
            }
        }

        /** Provider lease and locator are persistence-only sensitive values. */
        @JsonIgnore @Override public PreparedExternalSecret prepared() { return prepared; }
        @Override public String toString() {
            return "PendingSecretOperation.Prepared[slot=" + slot + ", mode=" + mode + "]";
        }
    }

    /** A slot whose prior active binding is resolved and snapshotted by the store. */
    record Retained(String slot, ConnectionRevisionCoordinate source) implements PendingSecretOperation {
        public Retained {
            SlotRules.require(slot);
            if (source == null) throw new IllegalArgumentException("source coordinate is required");
        }

        @Override public SecretSourceMode mode() { return SecretSourceMode.KEEP_EXISTING; }
        @Override public String toString() {
            return "PendingSecretOperation.Retained[slot=" + slot + ", source=" + source + "]";
        }
    }

    /** Shared validation for the three secret slots. */
    final class SlotRules {
        private SlotRules() { }
        static void require(String slot) {
            if (!"token".equals(slot) && !"password".equals(slot) && !"value".equals(slot)) {
                throw new IllegalArgumentException("slot is invalid");
            }
        }
    }
}
