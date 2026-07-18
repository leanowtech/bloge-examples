package com.leanowtech.bloge.gateway.testing.api;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free immutable identity exposed to a suite-stability execution controller.
 *
 * @param stabilityRunId deterministic parent run identity
 * @param tenantId verified tenant scope
 * @param environmentId verified non-production environment
 * @param clientRequestId caller-stable parent idempotency identity
 * @param requestFingerprint canonical immutable execution fingerprint
 * @param classification frozen suite classification
 */
public record TestSuiteStabilityExecutionDescriptor(
        String stabilityRunId,
        String tenantId,
        String environmentId,
        String clientRequestId,
        String requestFingerprint,
        String classification) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Requires a complete credential-free execution identity. */
    public TestSuiteStabilityExecutionDescriptor {
        stabilityRunId = normalized(stabilityRunId);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId).toLowerCase(Locale.ROOT);
        clientRequestId = normalized(clientRequestId);
        requestFingerprint = normalized(requestFingerprint);
        classification = normalized(classification).toUpperCase(Locale.ROOT);
        if (!valid(stabilityRunId) || !valid(tenantId)
                || !Set.of("test", "staging").contains(environmentId)
                || !valid(clientRequestId)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(classification)) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability execution descriptor");
        }
    }

    private static boolean valid(String value) {
        return IDENTIFIER.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
