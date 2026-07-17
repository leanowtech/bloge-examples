package com.leanowtech.bloge.gateway.testing.api;

import java.util.Locale;

/**
 * Versioned caller contract for creating one exactly recoverable durable operator unit test.
 *
 * <p>The path and {@link #target()} must identify the same exact synchronous runtime binding. Only
 * a stored immutable fixture is accepted. The arbitrary JSON {@link #input()} is converted to the
 * binding's declared Java input type after identity and target fingerprint verification. The exact
 * fixture and remaining authorization closure are verified before any execution authority is
 * reserved. Owner, lease, provider state, and all other control facts are server-owned.</p>
 *
 * @param schemaVersion public request version
 * @param clientRequestId caller-stable idempotency key shared with durable graph creation
 * @param target exact operator locator and content fingerprint
 * @param executionPurpose caller intent; only {@code OPERATOR_UNIT_TEST} is accepted
 * @param input bounded formal operator input, never a test-control envelope
 * @param fixtureBundleRef exact immutable governed fixture revision
 */
public record DurableOperatorTestExecutionCreateRequest(
        String schemaVersion,
        String clientRequestId,
        TestExecutionApiRequest.Target target,
        String executionPurpose,
        Object input,
        TestExecutionApiRequest.FixtureBundleRef fixtureBundleRef) {

    /** Current public durable operator creation request version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableOperatorTestExecutionCreateRequest.v1";

    /** Normalizes nullable protocol text without changing the caller's input value. */
    public DurableOperatorTestExecutionCreateRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        executionPurpose = normalized(executionPurpose).toUpperCase(Locale.ROOT);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
