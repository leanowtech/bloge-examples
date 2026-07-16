package com.leanowtech.bloge.gateway.testing.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned caller contract for creating one exactly recoverable durable graph test.
 *
 * <p>Only a stored immutable fixture is accepted. Owner, lease, provider state, authority, replay
 * values, and other control facts are server-derived and cannot be supplied through this request.</p>
 *
 * @param schemaVersion public request version
 * @param clientRequestId caller-stable idempotency key
 * @param target exact graph locator and content fingerprint
 * @param executionPurpose caller intent; only {@code GRAPH_CONTRACT_TEST} is accepted
 * @param context bounded business graph input; control keys are rejected
 * @param fixtureBundleRef exact immutable governed fixture revision
 */
public record DurableTestExecutionCreateRequest(
        String schemaVersion,
        String clientRequestId,
        TestExecutionApiRequest.Target target,
        String executionPurpose,
        Map<String, Object> context,
        TestExecutionApiRequest.FixtureBundleRef fixtureBundleRef) {

    /** Current public durable creation request version. */
    public static final String SCHEMA_VERSION = "bloge.durableTestExecutionCreateRequest.v1";

    /** Normalizes nullable text and snapshots the top-level business context. */
    public DurableTestExecutionCreateRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        executionPurpose = normalized(executionPurpose).toUpperCase(java.util.Locale.ROOT);
        context = context == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
