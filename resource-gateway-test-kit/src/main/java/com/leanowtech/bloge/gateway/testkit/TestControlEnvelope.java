package com.leanowtech.bloge.gateway.testkit;

import java.util.Objects;

/** Optional HTTP test-control envelope; its asset references are always exact.
 * @param purpose request purpose
 * @param scenario optional primary scenario reference
 * @param worldModel optional primary world reference
 * @param correlationId request correlation id
 * @param functionControl optional exact function-control asset reference
 */
public record TestControlEnvelope(
        String purpose,
        TestControlAssetReference scenario,
        TestControlAssetReference worldModel,
        String correlationId,
        TestControlAssetReference functionControl) {

    /**
     * Creates a compatibility envelope with no function-control reference.
     * @param purpose request purpose
     * @param scenario optional primary scenario reference
     * @param worldModel optional primary world reference
     * @param correlationId request correlation id
     */
    public TestControlEnvelope(String purpose, TestControlAssetReference scenario,
                               TestControlAssetReference worldModel, String correlationId) {
        this(purpose, scenario, worldModel, correlationId, null);
    }

    /** Validates exactly-one primary reference and freezes text values. */
    public TestControlEnvelope {
        purpose = text(purpose);
        correlationId = text(correlationId);
        if ((scenario == null) == (worldModel == null)) {
            throw invalid();
        }
    }

    /**
     * Returns the envelope's exactly-one primary asset reference.
     * @return the scenario or world primary reference
     */
    public TestControlAssetReference primaryReference() {
        return scenario == null ? worldModel : scenario;
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "Invalid test-control envelope");
        if (value.isBlank() || value.length() > TestControlProtocolLimits.MAX_STRING_CHARS
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return value.trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid test-control envelope");
    }
}
