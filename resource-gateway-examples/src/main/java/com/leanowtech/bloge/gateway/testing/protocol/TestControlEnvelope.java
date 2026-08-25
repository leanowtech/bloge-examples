package com.leanowtech.bloge.gateway.testing.protocol;

import java.util.Objects;

/** Immutable semantic envelope carried by {@code X-BLOGE-Test-Envelope}. */
public record TestControlEnvelope(
        String purpose,
        TestAssetReference scenario,
        TestAssetReference worldModel,
        String correlationId) {

    public TestControlEnvelope {
        purpose = requireText(purpose);
        correlationId = requireText(correlationId);
        if ((scenario == null) == (worldModel == null)) {
            throw new IllegalArgumentException("exactly one asset reference is required");
        }
    }

    public TestAssetReference assetReference() {
        return scenario != null ? scenario : worldModel;
    }

    public boolean referencesScenario() {
        return scenario != null;
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "envelope value is required");
        if (value.isBlank() || value.codePointCount(0, value.length()) > TestControlProtocolLimits.MAX_STRING_CHARS) {
            throw new IllegalArgumentException("invalid envelope value");
        }
        return value;
    }
}
