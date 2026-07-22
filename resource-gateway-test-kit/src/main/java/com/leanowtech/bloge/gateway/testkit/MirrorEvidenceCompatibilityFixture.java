package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Fixed signed producer-consumer fixture for the Stage 1 mirror evidence protocol.
 *
 * @param bundle strict payload-free mirror evidence bundle
 * @param verificationKey fixed public key that verifies the bundle attestation
 */
public record MirrorEvidenceCompatibilityFixture(
        JsonNode bundle,
        EvidenceVerificationKey verificationKey
) {
    /** Detaches mutable JSON while retaining the immutable public key descriptor. */
    public MirrorEvidenceCompatibilityFixture {
        if (bundle == null || !bundle.isObject()) {
            throw new IllegalArgumentException("Mirror evidence fixture bundle is invalid");
        }
        bundle = bundle.deepCopy();
        verificationKey = Objects.requireNonNull(verificationKey, "verificationKey");
    }

    /**
     * Returns a detached bundle so callers cannot mutate the process-wide compatibility fixture.
     *
     * @return mutable independent bundle copy
     */
    @Override
    public JsonNode bundle() {
        return bundle.deepCopy();
    }

    MirrorEvidenceCompatibilityFixture detachedCopy() {
        return new MirrorEvidenceCompatibilityFixture(bundle, verificationKey);
    }
}
