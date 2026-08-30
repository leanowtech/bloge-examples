package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

/** Input to preparation. Providers must authorize references within the operation scope. */
public sealed interface SecretSource permits SecretSource.Value, SecretSource.Reference {
    record Value(DestroyableSecret secret) implements SecretSource {
        public Value { if (secret == null) throw new NullPointerException("secret"); }
        @Override public String toString() { return "Value[REDACTED]"; }
    }

    record Reference(String ref) implements SecretSource {
        public Reference { SecretValidation.text(ref, "ref", 512); }
        @Override public String toString() { return "Reference[REDACTED]"; }
    }
}
