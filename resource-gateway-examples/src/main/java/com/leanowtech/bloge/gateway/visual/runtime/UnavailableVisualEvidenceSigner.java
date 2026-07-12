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
}
