package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Payload-bearing authoring command for one controlled Feature test suite.
 *
 * <p>This value may exist in request memory and the encrypted Fixture material vault only. It must
 * never be stored in {@code agent_tdd_assets} or returned by a payload-free projection.</p>
 *
 * @param featureRef scoped Feature under test
 * @param evaluationRef candidate implementation binding proved by this suite
 * @param expectedRevision exact current suite revision; zero creates a suite
 * @param libraryRefs exact libraries used to compile the Feature graph
 * @param requiredCoverageTargets complete controlled coverage obligation set
 * @param cases protected controlled cases
 */
public record FeatureControlledSuiteDefinition(
        String featureRef,
        String evaluationRef,
        long expectedRevision,
        List<String> libraryRefs,
        List<String> requiredCoverageTargets,
        List<Case> cases
) {
    /** Validates the public suite command and freezes all payload-bearing collections. */
    public FeatureControlledSuiteDefinition {
        featureRef = required(featureRef, "featureRef");
        evaluationRef = required(evaluationRef, "evaluationRef");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must be >= 0");
        libraryRefs = normalizedList(libraryRefs, false, "libraryRefs");
        requiredCoverageTargets = normalizedList(
                requiredCoverageTargets, true, "requiredCoverageTargets");
        cases = cases == null ? List.of() : cases.stream().map(java.util.Objects::requireNonNull).toList();
        if (cases.isEmpty()) throw new IllegalArgumentException("cases are required");
        if (cases.stream().map(Case::caseId).distinct().count() != cases.size()) {
            throw new IllegalArgumentException("caseId must be unique");
        }
        LinkedHashSet<String> obligations = new LinkedHashSet<>(requiredCoverageTargets);
        if (cases.stream().flatMap(testCase -> testCase.coverageTargets().stream())
                .anyMatch(target -> !obligations.contains(target))) {
            throw new IllegalArgumentException("case coverageTargets must belong to the required set");
        }
    }

    /** Returns the protected material without the optimistic-lock coordinate. */
    public FeatureControlledSuiteDefinition protectedMaterial() {
        return new FeatureControlledSuiteDefinition(
                featureRef, evaluationRef, 0, libraryRefs, requiredCoverageTargets, cases);
    }

    /** One business-intent case and its controlled dependency behavior. */
    public record Case(
            String caseId,
            String intent,
            JsonNode givenInputs,
            List<NodeBehavior> nodeBehaviors,
            JsonNode expectedOutput,
            List<String> coverageTargets
    ) {
        /** Validates required case material and makes defensive payload copies. */
        public Case {
            caseId = required(caseId, "caseId");
            intent = required(intent, "intent");
            if (givenInputs == null || !givenInputs.isObject()) {
                throw new IllegalArgumentException("givenInputs must be an object");
            }
            givenInputs = givenInputs.deepCopy();
            nodeBehaviors = nodeBehaviors == null ? List.of()
                    : nodeBehaviors.stream().map(java.util.Objects::requireNonNull).toList();
            if (nodeBehaviors.stream().map(NodeBehavior::nodeId).distinct().count()
                    != nodeBehaviors.size()) {
                throw new IllegalArgumentException("node behavior ids must be unique per case");
            }
            if (expectedOutput == null || expectedOutput.isMissingNode()) {
                throw new IllegalArgumentException("expectedOutput is required");
            }
            expectedOutput = expectedOutput.deepCopy();
            coverageTargets = normalizedList(coverageTargets, true, "coverageTargets");
        }

        @Override
        public JsonNode givenInputs() {
            return givenInputs.deepCopy();
        }

        @Override
        public JsonNode expectedOutput() {
            return expectedOutput.deepCopy();
        }
    }

    /** One graph-node dependency directive compiled by the existing Agent TDD behavior compiler. */
    public record NodeBehavior(String nodeId, JsonNode behavior) {
        /** Requires an object directive with an explicit behavior kind. */
        public NodeBehavior {
            nodeId = required(nodeId, "nodeId");
            if (behavior == null || !behavior.isObject()
                    || !behavior.path("behavior").isTextual()
                    || behavior.path("behavior").asText().isBlank()) {
                throw new IllegalArgumentException("behavior directive is required");
            }
            behavior = behavior.deepCopy();
        }

        @Override
        public JsonNode behavior() {
            return behavior.deepCopy();
        }
    }

    private static List<String> normalizedList(List<String> values, boolean required, String field) {
        List<String> normalized = values == null ? List.of() : values.stream()
                .map(value -> FeatureControlledSuiteDefinition.required(value, field + " entry"))
                .distinct().sorted().toList();
        if (required && normalized.isEmpty()) throw new IllegalArgumentException(field + " are required");
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
