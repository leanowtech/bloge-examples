package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;

/** Server-owned authenticated encryption port for redacted world-draft values. */
public interface WorldDraftPayloadProtector {
    String protect(byte[] plaintext, String associatedData);

    byte[] unprotect(String envelope, String associatedData);

    String activeKeyId();

    static WorldDraftPayloadProtector fromConfiguration(String activeKeyId, String keyRing) {
        return new ConfiguredWorldDraftPayloadProtector(
                AuthoringFixturePayloadProtector.fromConfiguration(activeKeyId, keyRing));
    }
}

final class ConfiguredWorldDraftPayloadProtector implements WorldDraftPayloadProtector {
    private final AuthoringFixturePayloadProtector delegate;

    ConfiguredWorldDraftPayloadProtector(AuthoringFixturePayloadProtector delegate) {
        if (delegate == null) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.INVALID_INPUT);
        this.delegate = delegate;
    }

    @Override public String protect(byte[] plaintext, String associatedData) {
        return delegate.protect(plaintext, associatedData);
    }

    @Override public byte[] unprotect(String envelope, String associatedData) {
        return delegate.unprotect(envelope, associatedData);
    }

    @Override public String activeKeyId() { return delegate.activeKeyId(); }
}
