package com.leanowtech.bloge.gateway.testing.correctness.publication;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.AttemptStage;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.PublicationAttempt;

/** Durable payload-free Saga state and optional deterministic compilation report. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredCorrectnessPublicationAttempt(
        String schemaVersion,
        EnterpriseScope scope,
        PublicationAttempt attempt,
        CorrectnessCompilationReport compilationReport
) {
    public static final String SCHEMA_VERSION = "bloge.storedCorrectnessPublicationAttempt.v1";

    public StoredCorrectnessPublicationAttempt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported stored Publication Attempt schemaVersion");
        }
        if (scope == null || attempt == null) {
            throw new IllegalArgumentException("Publication Attempt scope and value are required");
        }
        if (attempt.stage() == AttemptStage.PREPARING && compilationReport != null) {
            throw new IllegalArgumentException(
                    "Preparing attempt cannot carry a compilation report");
        }
        if (SetOfCompiledStages.contains(attempt.stage()) && compilationReport == null) {
            throw new IllegalArgumentException(
                    "Compiled, registering, or committed attempt requires a compilation report");
        }
        if (compilationReport != null
                && !attempt.coordinate().equals(compilationReport.coordinate())) {
            throw new IllegalArgumentException(
                    "Publication Attempt and compilation report coordinates differ");
        }
    }

    private static final class SetOfCompiledStages {
        private SetOfCompiledStages() {
        }

        static boolean contains(AttemptStage stage) {
            return stage == AttemptStage.COMPILED
                    || stage == AttemptStage.REGISTERING
                    || stage == AttemptStage.COMMITTED;
        }
    }
}
