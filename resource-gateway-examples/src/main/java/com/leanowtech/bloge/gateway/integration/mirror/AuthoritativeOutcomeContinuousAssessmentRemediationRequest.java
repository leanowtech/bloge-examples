package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strict compare-and-set command for re-queuing one quarantined continuous assessment.
 *
 * <p>The caller must bind the exact projection and lifecycle head it reviewed. Retry timing,
 * failure-budget reset, worker ownership, database time, and the replacement projection remain
 * server-owned.</p>
 *
 * @param schemaVersion exact command protocol version
 * @param commandId caller-stable idempotency identity
 * @param expectedProjectionFingerprint exact quarantined projection reviewed by the caller
 * @param expectedLifecycleHeadOrdinal exact lifecycle ordinal reviewed by the caller
 * @param expectedLifecycleHeadFingerprint exact lifecycle head reviewed by the caller
 * @param reasonCode bounded machine-readable remediation reason
 */
public record AuthoritativeOutcomeContinuousAssessmentRemediationRequest(
        String schemaVersion,
        String commandId,
        String expectedProjectionFingerprint,
        long expectedLifecycleHeadOrdinal,
        String expectedLifecycleHeadFingerprint,
        String reasonCode
) {
    /** Current continuous-assessment remediation command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentRemediationRequest.v1";
    /** Largest canonical remediation command. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            64 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates immutable command identity and both caller-owned compare-and-set fences. */
    public AuthoritativeOutcomeContinuousAssessmentRemediationRequest {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        commandId = normalized(commandId);
        expectedProjectionFingerprint =
                normalized(expectedProjectionFingerprint);
        expectedLifecycleHeadFingerprint =
                normalized(expectedLifecycleHeadFingerprint);
        reasonCode = normalized(reasonCode)
                .toUpperCase(Locale.ROOT);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !IDENTIFIER.matcher(commandId).matches()
                || !FINGERPRINT.matcher(
                expectedProjectionFingerprint).matches()
                || expectedLifecycleHeadOrdinal < 1
                || !FINGERPRINT.matcher(
                expectedLifecycleHeadFingerprint).matches()
                || !CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "Continuous assessment remediation command is invalid");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
