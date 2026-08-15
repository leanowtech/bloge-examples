package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;

/** Payload-free governed-run receipt with an optional terminal correctness evidence companion. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessRunResponse(
        String schemaVersion,
        Status status,
        TestSuiteExecutionResponse suiteExecution,
        StoredCorrectnessEvidenceCompanion evidenceCompanion
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessRunResponse.v1";

    public enum Status { RUNNING, EVIDENCE_AVAILABLE }

    public CorrectnessRunResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || status == null || suiteExecution == null) {
            throw new IllegalArgumentException("Complete correctness run response is required");
        }
        if ((status == Status.RUNNING) != (evidenceCompanion == null)) {
            throw new IllegalArgumentException(
                    "Running responses cannot carry terminal evidence and terminal responses require it");
        }
        if (evidenceCompanion != null
                && !evidenceCompanion.companion().suiteRunId()
                .equals(suiteExecution.suiteRunId())) {
            throw new IllegalArgumentException(
                    "Correctness run response and evidence companion differ");
        }
    }
}
