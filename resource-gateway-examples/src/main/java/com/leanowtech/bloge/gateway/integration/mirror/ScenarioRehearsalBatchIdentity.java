package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable full-scope identity derivation for durable Scenario rehearsal batches. */
public final class ScenarioRehearsalBatchIdentity {
    private static final String DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_ID_V1";
    private static final Pattern BATCH_ID =
            Pattern.compile("scenario-batch-[a-f0-9]{64}");

    private ScenarioRehearsalBatchIdentity() {
    }

    /**
     * Derives one stable batch id from complete enterprise scope and caller request identity.
     *
     * @param mapper canonical protocol mapper
     * @param scope complete enterprise scope
     * @param requestId caller idempotency identity
     * @return canonical batch identity
     */
    public static String derive(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            String requestId) {
        LinkedHashMap<String, Object> material =
                new LinkedHashMap<>();
        material.put("domain", DOMAIN);
        material.put("scope", Objects.requireNonNull(scope, "scope"));
        String id = requestId == null ? "" : requestId.trim();
        if (id.isBlank()) {
            throw new IllegalArgumentException(
                    "requestId must not be blank");
        }
        material.put("requestId", id);
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(mapper, "mapper"), material);
        return "scenario-batch-"
                + fingerprint.substring("sha256:".length());
    }

    /** @return whether the value has the canonical batch-id shape */
    public static boolean hasCanonicalShape(String value) {
        return value != null && BATCH_ID.matcher(value).matches();
    }
}
