package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle.Assertion;

import java.util.List;

/** Deterministic, complete source map from typed Assertion Set specs to evaluator work. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssertionCompilationReport(
        String schemaVersion,
        String sourceFingerprint,
        CompilationCompatibility compatibility,
        List<AssertionDisposition> dispositions,
        List<Assertion> runtimeAssertions,
        int evidenceAssertionCount,
        int gateExpectationCount
) {
    public static final String SCHEMA_VERSION = "bloge.assertionCompilationReport.v1";

    public enum DispositionStatus {
        COMPILED_RUNTIME,
        BOUND_EVIDENCE,
        RETAINED_GATE,
        UNSUPPORTED
    }

    public AssertionCompilationReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported assertion compilation schemaVersion");
        }
        if (sourceFingerprint == null
                || !sourceFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact Assertion Set source fingerprint is required");
        }
        if (compatibility == null) {
            throw new IllegalArgumentException("Compilation compatibility is required");
        }
        dispositions = dispositions == null ? List.of() : List.copyOf(dispositions);
        runtimeAssertions = runtimeAssertions == null ? List.of() : List.copyOf(runtimeAssertions);
        if (evidenceAssertionCount < 0 || gateExpectationCount < 0) {
            throw new IllegalArgumentException("Compilation counters must not be negative");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssertionDisposition(
            String assertionId,
            EvaluationKind evaluationKind,
            String capability,
            DispositionStatus status,
            String reasonCode,
            int loweredAssertionCount
    ) {
        public AssertionDisposition {
            assertionId = required(assertionId, "assertionId");
            if (evaluationKind == null || status == null) {
                throw new IllegalArgumentException("Disposition kind and status are required");
            }
            capability = required(capability, "capability");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            if (status == DispositionStatus.UNSUPPORTED && reasonCode.isEmpty()) {
                throw new IllegalArgumentException("Unsupported disposition requires reasonCode");
            }
            if (loweredAssertionCount < 0) {
                throw new IllegalArgumentException("loweredAssertionCount must not be negative");
            }
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
