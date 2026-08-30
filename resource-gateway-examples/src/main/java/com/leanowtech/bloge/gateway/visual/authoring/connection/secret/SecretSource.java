package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;

/** Input to preparation. Providers must authorize references within the operation scope. */
public sealed interface SecretSource permits SecretSource.Value, SecretSource.Reference {
    record Value(DestroyableSecret secret) implements SecretSource {
        public Value { if (secret == null) throw new NullPointerException("secret"); }
        /** Secret material is caller-owned and is never a wire property. */
        @JsonIgnore
        @Override public DestroyableSecret secret() { return secret; }
        @Override public String toString() { return "Value[REDACTED]"; }
    }

    record Reference(SecretReference reference) implements SecretSource {
        public Reference { if (reference == null) throw new NullPointerException("reference"); }
        /** Scope-bound opaque handle; providers must compare its scope to the context exactly. */
        @JsonIgnore
        @Override public SecretReference reference() { return reference; }
        @Override public String toString() { return "Reference[REDACTED]"; }
    }
}
