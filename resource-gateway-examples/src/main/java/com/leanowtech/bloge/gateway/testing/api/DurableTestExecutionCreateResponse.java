package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * Payload-free result of one public durable execution creation command.
 *
 * @param schemaVersion public response version
 * @param execution complete durable execution view at the initial suspension
 * @param idempotentReplay whether an earlier committed command result was replayed
 */
public record DurableTestExecutionCreateResponse(
        String schemaVersion,
        DurableTestExecutionQueryResponse execution,
        boolean idempotentReplay) {

    /** Current public durable creation response version. */
    public static final String SCHEMA_VERSION = "bloge.durableTestExecutionCreateResponse.v1";

    /** Requires the exact suspended and recoverable initial execution view. */
    public DurableTestExecutionCreateResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported durable execution creation response version");
        }
        execution = Objects.requireNonNull(execution, "execution");
        if (!"SUSPENDED".equals(execution.status())
                || !execution.recoverable()
                || execution.migrationRequired()) {
            throw new IllegalArgumentException(
                    "Durable creation response requires a recoverable suspended execution");
        }
    }
}
