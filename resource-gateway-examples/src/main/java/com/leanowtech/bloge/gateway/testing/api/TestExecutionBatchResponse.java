package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;

/** Immutable response for a bounded batch of independent controlled runs. */
public record TestExecutionBatchResponse(String schemaVersion, List<TestExecutionApiResponse> executions) {
    public static final String SCHEMA_VERSION = "bloge.testExecutionBatchResponse.v1";

    public TestExecutionBatchResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        executions = executions == null ? List.of() : List.copyOf(executions);
    }
}
