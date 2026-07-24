package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical aggregate-run identity derived from full enterprise scope and caller request identity.
 */
public final class ScenarioRehearsalRunIdentity {
    private static final String DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_RUN_ID_V1";
    private static final Pattern RUN_ID =
            Pattern.compile("scenario-[a-f0-9]{64}");

    private ScenarioRehearsalRunIdentity() {
    }

    /**
     * Derives the only valid run identity for one scoped idempotency request.
     *
     * @param mapper canonical protocol mapper
     * @param scope complete enterprise scope
     * @param requestId validated aggregate request identity
     * @return {@code scenario-} followed by the domain-separated SHA-256 material
     */
    public static String derive(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            String requestId) {
        String request = requestId == null ? "" : requestId.trim();
        if (request.isBlank()) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal request id is required");
        }
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(mapper, "mapper"),
                Map.of(
                        "domain", DOMAIN,
                        "scope", Objects.requireNonNull(scope, "scope"),
                        "requestId", request));
        return "scenario-"
                + fingerprint.substring("sha256:".length());
    }

    /**
     * Checks the portable run-id representation without deriving its scoped material.
     *
     * @param value candidate run id
     * @return true only for the canonical textual shape
     */
    public static boolean hasCanonicalShape(String value) {
        return RUN_ID.matcher(
                value == null ? "" : value.trim()).matches();
    }
}
