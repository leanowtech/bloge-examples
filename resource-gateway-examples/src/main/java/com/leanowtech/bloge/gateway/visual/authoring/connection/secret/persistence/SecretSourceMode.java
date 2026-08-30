package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

/** Durable mode of a secret slot; plaintext is never represented by this enum. */
public enum SecretSourceMode {
    /** The caller supplied plaintext to a provider. */ VALUE,
    /** The caller supplied an existing scope-bound secret reference. */ SECRET_REF,
    /** The current active locator is retained. */ KEEP_EXISTING
}
