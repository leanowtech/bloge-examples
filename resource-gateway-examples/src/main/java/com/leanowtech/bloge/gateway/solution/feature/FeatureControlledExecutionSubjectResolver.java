package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.List;
import java.util.Map;

/**
 * Freezes the exact graph, library set, and server-owned coverage denominator used by a Feature
 * controlled suite.
 *
 * <p>The suite service calls this boundary before execution and again before accepting evidence.
 * A repository-backed implementation therefore prevents a caller-declared evaluation reference or
 * coverage denominator from being recorded as if it had actually been executed.</p>
 */
public interface FeatureControlledExecutionSubjectResolver {

    /** Resolves one exact controlled execution subject and rejects incomplete coverage claims. */
    Subject freeze(String evaluationRef,
                   List<String> libraryRefs,
                   List<String> claimedCoverageTargets,
                   IntegrationRequestContext identity);

    /** Re-resolves the subject and rejects any graph, library, or coverage drift. */
    default void requireCurrent(Subject expected,
                                String evaluationRef,
                                List<String> libraryRefs,
                                List<String> claimedCoverageTargets,
                                IntegrationRequestContext identity) {
        if (!expected.equals(freeze(
                evaluationRef, libraryRefs, claimedCoverageTargets, identity))) {
            throw new AgentTddToolException(
                    "FEATURE_SUITE_EVIDENCE_STALE", "Feature suite evidence is not current.");
        }
    }

    /**
     * Creates a deterministic in-memory seam for service tests that have no graph repository.
     * Production wiring must use the repository-backed resolver.
     */
    static FeatureControlledExecutionSubjectResolver trusting(ObjectMapper mapper) {
        return (evaluationRef, libraryRefs, claimedCoverageTargets, identity) -> {
            List<String> targets = claimedCoverageTargets == null ? List.of()
                    : claimedCoverageTargets.stream().distinct().sorted().toList();
            if (targets.isEmpty()) {
                throw new AgentTddToolException(
                        "FEATURE_SUITE_COVERAGE_INVALID", "Feature coverage obligations are unavailable.");
            }
            return new Subject(
                    fingerprint(mapper, evaluationRef), 0,
                    fingerprint(mapper, Map.of("evaluationRef", evaluationRef)),
                    fingerprint(mapper, libraryRefs == null ? List.of() : libraryRefs),
                    fingerprint(mapper, targets), targets);
        };
    }

    /** Payload-free immutable coordinate of the exact execution subject. */
    record Subject(String evaluationRefFingerprint,
                   long graphRevision,
                   String graphFingerprint,
                   String libraryFingerprint,
                   String coverageObligationsFingerprint,
                   List<String> coverageObligations) {
        /** Validates and freezes the coordinate. */
        public Subject {
            evaluationRefFingerprint = requiredFingerprint(
                    evaluationRefFingerprint, "evaluationRefFingerprint");
            graphFingerprint = requiredFingerprint(graphFingerprint, "graphFingerprint");
            libraryFingerprint = requiredFingerprint(libraryFingerprint, "libraryFingerprint");
            coverageObligationsFingerprint = requiredFingerprint(
                    coverageObligationsFingerprint, "coverageObligationsFingerprint");
            if (graphRevision < 0) throw new IllegalArgumentException("graphRevision must be non-negative");
            coverageObligations = coverageObligations == null ? List.of()
                    : coverageObligations.stream().distinct().sorted().toList();
            if (coverageObligations.isEmpty()) {
                throw new IllegalArgumentException("coverageObligations are required");
            }
        }
    }

    private static String fingerprint(ObjectMapper mapper, Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, 16 * 1024 * 1024);
    }

    private static String requiredFingerprint(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 fingerprint");
        }
        return normalized;
    }
}
