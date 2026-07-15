package com.leanowtech.bloge.gateway.testing.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Server-derived verdict over one v2 suite's typed semantic requirements.
 *
 * <p>Missing means complete evidence disproved coverage. Unavailable means the required fact could
 * not be evaluated, for example because sanitized output removed a decision path. Both states block
 * semantic satisfaction and promotion.</p>
 *
 * @param status semantic coverage status
 * @param required exact signed suite requirements
 * @param observed server-derived satisfied requirements
 * @param missingRequirementIds requirements absent from complete evidence
 * @param unavailable requirements whose source facts could not be trusted or observed
 */
public record SemanticCoverageVerdict(
        Status status,
        List<SemanticCoveragePolicy.Requirement> required,
        List<Observation> observed,
        List<String> missingRequirementIds,
        List<Unavailable> unavailable
) {
    /** Semantic evaluation status. */
    public enum Status {
        NOT_EVALUATED,
        SATISFIED,
        UNSATISFIED,
        INCOMPLETE
    }

    /** Canonicalizes set-like verdict collections. */
    public SemanticCoverageVerdict {
        status = status == null ? Status.NOT_EVALUATED : status;
        required = sortedRequirements(required);
        observed = sortedObservations(observed);
        missingRequirementIds = sortedStrings(missingRequirementIds);
        unavailable = sortedUnavailable(unavailable);
    }

    /** @return running-checkpoint placeholder */
    public static SemanticCoverageVerdict notEvaluated(
            List<SemanticCoveragePolicy.Requirement> required) {
        return new SemanticCoverageVerdict(Status.NOT_EVALUATED, required,
                List.of(), List.of(), List.of());
    }

    /**
     * One requirement satisfied by one or more child cases.
     *
     * @param requirementId exact suite requirement id
     * @param kind observed semantic kind
     * @param caseIds ordered child cases that supplied the fact
     */
    public record Observation(String requirementId, SemanticCoveragePolicy.Kind kind,
                              List<String> caseIds) {
        /** Normalizes the observation identity and canonicalizes case ids. */
        public Observation {
            requirementId = normalized(requirementId);
            caseIds = sortedStrings(caseIds);
        }
    }

    /**
     * One requirement that cannot be evaluated from trusted sanitized evidence.
     *
     * @param requirementId exact suite requirement id
     * @param reasonCode stable fail-closed reason code
     */
    public record Unavailable(String requirementId, String reasonCode) {
        /** Normalizes machine identities. */
        public Unavailable {
            requirementId = normalized(requirementId);
            reasonCode = normalized(reasonCode);
        }
    }

    private static List<SemanticCoveragePolicy.Requirement> sortedRequirements(
            List<SemanticCoveragePolicy.Requirement> values) {
        List<SemanticCoveragePolicy.Requirement> sorted = new ArrayList<>(
                values == null ? List.of() : new LinkedHashSet<>(values));
        sorted.sort(Comparator.comparing(SemanticCoveragePolicy.Requirement::requirementId));
        return List.copyOf(sorted);
    }

    private static List<Observation> sortedObservations(List<Observation> values) {
        List<Observation> sorted = new ArrayList<>(
                values == null ? List.of() : new LinkedHashSet<>(values));
        sorted.sort(Comparator.comparing(Observation::requirementId));
        return List.copyOf(sorted);
    }

    private static List<Unavailable> sortedUnavailable(List<Unavailable> values) {
        List<Unavailable> sorted = new ArrayList<>(
                values == null ? List.of() : new LinkedHashSet<>(values));
        sorted.sort(Comparator.comparing(Unavailable::requirementId));
        return List.copyOf(sorted);
    }

    private static List<String> sortedStrings(List<String> values) {
        List<String> sorted = new ArrayList<>(
                values == null ? List.of() : new LinkedHashSet<>(values));
        sorted.replaceAll(SemanticCoverageVerdict::normalized);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
