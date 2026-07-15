package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;

/** Versioned bounded batch request. Batch items run independently through the same kernel. */
public record TestExecutionBatchRequest(String schemaVersion, List<TestExecutionApiRequest> executions) {
    public static final String SCHEMA_VERSION = "bloge.testExecutionBatchRequest.v1";
    public static final int MAX_EXECUTIONS = 100;

    /** Creates an immutable item list. Size is enforced by the service before work begins. */
    public TestExecutionBatchRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        executions = executions == null ? List.of() : List.copyOf(executions);
    }
}
