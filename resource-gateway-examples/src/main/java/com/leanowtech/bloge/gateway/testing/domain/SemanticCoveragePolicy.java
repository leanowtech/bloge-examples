package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Typed orchestration-semantic requirements frozen into a v2 suite revision.
 *
 * <p>Requirement identifiers are suite-local stable identities. The policy is canonicalized by
 * identifier so declaration order cannot change a suite fingerprint. A requirement describes what
 * must be observed; only server-derived child evidence can satisfy it.</p>
 *
 * @param requirements bounded, uniquely identified semantic requirements
 */
public record SemanticCoveragePolicy(List<Requirement> requirements) {

    /** Canonicalizes requirement order and rejects duplicate identities. */
    public SemanticCoveragePolicy {
        List<Requirement> safe = requirements == null ? List.of() : List.copyOf(requirements);
        Set<String> ids = new HashSet<>();
        for (Requirement requirement : safe) {
            if (requirement == null || !ids.add(requirement.requirementId())) {
                throw new IllegalArgumentException(
                        "Semantic coverage requirements require unique non-null identities");
            }
        }
        List<Requirement> sorted = new ArrayList<>(safe);
        sorted.sort(Comparator.comparing(Requirement::requirementId));
        requirements = List.copyOf(sorted);
    }

    /** @return an explicit policy with no semantic promotion requirements */
    public static SemanticCoveragePolicy empty() {
        return new SemanticCoveragePolicy(List.of());
    }

    /** Supported semantic fact kinds. */
    public enum Kind {
        BRANCH_TRANSFERRED,
        BRANCH_SKIPPED,
        DECISION_RULE,
        RETRY,
        FALLBACK,
        TIMEOUT,
        COMPENSATION
    }

    /**
     * One typed semantic requirement.
     *
     * <p>Jackson dispatches by the existing {@code kind} property without adding an artificial
     * wrapper or changing the canonical JSON shape.</p>
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = BranchRequirement.class, name = "BRANCH_TRANSFERRED"),
            @JsonSubTypes.Type(value = BranchRequirement.class, name = "BRANCH_SKIPPED"),
            @JsonSubTypes.Type(value = DecisionRuleRequirement.class, name = "DECISION_RULE"),
            @JsonSubTypes.Type(value = RetryRequirement.class, name = "RETRY"),
            @JsonSubTypes.Type(value = SiteRequirement.class, name = "FALLBACK"),
            @JsonSubTypes.Type(value = SiteRequirement.class, name = "TIMEOUT"),
            @JsonSubTypes.Type(value = SiteRequirement.class, name = "COMPENSATION")
    })
    public sealed interface Requirement permits BranchRequirement, DecisionRuleRequirement,
            RetryRequirement, SiteRequirement {
        /** @return stable suite-local requirement identity */
        String requirementId();

        /** @return semantic fact kind */
        Kind kind();
    }

    /**
     * Required transferred or skipped branch edge.
     *
     * @param requirementId stable requirement identity
     * @param kind {@code BRANCH_TRANSFERRED} or {@code BRANCH_SKIPPED}
     * @param fromInvocationSiteId structural source site
     * @param toInvocationSiteId structural destination site
     */
    public record BranchRequirement(String requirementId, Kind kind,
                                    String fromInvocationSiteId,
                                    String toInvocationSiteId) implements Requirement {
        /** Normalizes the structural coordinate and enforces the branch kind. */
        public BranchRequirement {
            requirementId = normalized(requirementId);
            fromInvocationSiteId = normalized(fromInvocationSiteId);
            toInvocationSiteId = normalized(toInvocationSiteId);
            if (kind != Kind.BRANCH_TRANSFERRED && kind != Kind.BRANCH_SKIPPED) {
                throw new IllegalArgumentException("Branch requirement kind is invalid");
            }
        }
    }

    /**
     * Required decision-table output observation.
     *
     * @param requirementId stable requirement identity
     * @param kind fixed {@code DECISION_RULE}
     * @param invocationSiteId structural decision site
     * @param outputJsonPointer JSON Pointer into the sanitized node output
     * @param expectedScalar expected string, number, boolean, or null
     */
    public record DecisionRuleRequirement(String requirementId, Kind kind,
                                          String invocationSiteId,
                                          String outputJsonPointer,
                                          Object expectedScalar) implements Requirement {
        /** Normalizes coordinates and enforces the decision-rule kind. */
        public DecisionRuleRequirement {
            requirementId = normalized(requirementId);
            invocationSiteId = normalized(invocationSiteId);
            outputJsonPointer = normalized(outputJsonPointer);
            if (kind != Kind.DECISION_RULE) {
                throw new IllegalArgumentException("Decision-rule requirement kind is invalid");
            }
        }
    }

    /**
     * Required retry observation.
     *
     * @param requirementId stable requirement identity
     * @param kind fixed {@code RETRY}
     * @param invocationSiteId structural retried site
     * @param minimumAttempts minimum observed delegate attempts in one occurrence
     */
    public record RetryRequirement(String requirementId, Kind kind,
                                   String invocationSiteId,
                                   int minimumAttempts) implements Requirement {
        /** Normalizes the site and enforces a meaningful retry threshold. */
        public RetryRequirement {
            requirementId = normalized(requirementId);
            invocationSiteId = normalized(invocationSiteId);
            if (kind != Kind.RETRY || minimumAttempts < 2) {
                throw new IllegalArgumentException("Retry requirement must require at least two attempts");
            }
        }
    }

    /**
     * Required fallback, timeout, or compensation observation at one site.
     *
     * @param requirementId stable requirement identity
     * @param kind {@code FALLBACK}, {@code TIMEOUT}, or {@code COMPENSATION}
     * @param invocationSiteId structural site
     * @param errorCode optional stable timeout error code; blank for any timeout
     */
    public record SiteRequirement(String requirementId, Kind kind,
                                  String invocationSiteId,
                                  String errorCode) implements Requirement {
        /** Normalizes coordinates and rejects unrelated fact kinds. */
        public SiteRequirement {
            requirementId = normalized(requirementId);
            invocationSiteId = normalized(invocationSiteId);
            errorCode = normalized(errorCode);
            if (kind != Kind.FALLBACK && kind != Kind.TIMEOUT && kind != Kind.COMPENSATION) {
                throw new IllegalArgumentException("Site semantic requirement kind is invalid");
            }
            if (kind != Kind.TIMEOUT && !errorCode.isBlank()) {
                throw new IllegalArgumentException("Only timeout requirements may declare errorCode");
            }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
