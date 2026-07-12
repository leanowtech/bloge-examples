package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.Optional;

final class UnavailableVisualEvidenceSigner implements VisualEvidenceSigner {
    static final UnavailableVisualEvidenceSigner INSTANCE = new UnavailableVisualEvidenceSigner();

    private UnavailableVisualEvidenceSigner() {
    }

    @Override
    public VisualRunEvidenceSeal seal(String materialFingerprint) {
        return VisualRunEvidenceSeal.unsigned();
    }

    @Override
    public Verification verify(VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
        return Verification.unavailable("Evidence signing authority is unavailable.");
    }

    @Override
    public Optional<VerificationKey> key(String keyId) {
        return Optional.empty();
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public KeyResolution resolveKey(String keyId) {
        return KeyResolution.providerUnavailable("Evidence signing authority is unavailable.");
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("", "UNAVAILABLE", "", false, "UNAVAILABLE", "",
                false, false, 0, null, null, 0, 0, null);
    }
}
