package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;

import java.util.Objects;

/** One slot in a staged batch, either newly prepared or retaining its old binding. */
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

    /** A slot that copies its previous active locator under the new command id. */
    record Retained(String slot, ActiveSecretBinding oldBinding) implements PendingSecretOperation {
        public Retained {
            SlotRules.require(slot);
            if (oldBinding == null) throw new IllegalArgumentException("old binding is required");
        }

        /** Old provider locator is not a JSON field. */
        @JsonIgnore @Override public ActiveSecretBinding oldBinding() { return oldBinding; }
        @Override public SecretSourceMode mode() { return SecretSourceMode.KEEP_EXISTING; }
        @Override public String toString() {
            return "PendingSecretOperation.Retained[slot=" + slot + "]";
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
