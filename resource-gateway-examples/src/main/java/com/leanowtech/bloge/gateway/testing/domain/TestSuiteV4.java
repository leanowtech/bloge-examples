package com.leanowtech.bloge.gateway.testing.domain;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable suite generation for deterministic, bounded property execution.
 *
 * <p>V4 retains the complete generator policy, honest quantification, generation gaps, and
 * root-to-shrink lineage as canonical fields. A property suite is therefore a reviewable closure
 * over one exact {@code TestPropertyCasePlan}, not a mutable request to generate more cases at run
 * time. Inputs and metadata are recursively frozen so the fingerprinted value cannot be changed
 * through a caller-owned collection after construction.</p>
 *
 * <p>This protocol describes executable intent but does not itself claim that a runtime can emit
 * V4 evidence. Capability discovery and the suite runner remain fail closed until the matching
 * evidence and attestation generation is available.</p>
 *
 * @param schemaVersion exact V4 suite schema version
 * @param suiteId stable suite identifier
 * @param revision immutable content-derived revision
 * @param target exact graph or operator target
 * @param classification maximum data classification
 * @param cases ordered root and shrink cases
 * @param coveragePolicy structural coverage policy
 * @param semanticCoveragePolicy orchestration-semantic policy, empty in V4
 * @param promotionPolicy future evidence gate for the complete frozen case closure
 * @param evaluationMode fixed property execution mode
 * @param quantification fixed bounded-sampling meaning
 * @param exhaustive fixed false because V4 never proves the complete input space
 * @param propertyPlanFingerprint exact reviewed property-plan fingerprint
 * @param inputSchemaFingerprint exact projected input-schema fingerprint
 * @param generationPolicy exact deterministic generation policy
 * @param sourcePlanStatus generated or explicitly accepted partial plan
 * @param generationGapsAccepted whether a partial plan's disclosed gaps were accepted
 * @param generationGaps stable generation limitations copied from the exact plan
 * @param propertyTrials ordered root and shrink lineage closure
 * @param metadata bounded provenance facts
 */
public record TestSuiteV4(
        String schemaVersion,
        String suiteId,
        long revision,
        TestSuite.Target target,
        String classification,
        List<TestSuite.TestCase> cases,
        TestSuite.CoveragePolicy coveragePolicy,
        SemanticCoveragePolicy semanticCoveragePolicy,
        TestSuite.PromotionPolicy promotionPolicy,
        EvaluationMode evaluationMode,
        Quantification quantification,
        boolean exhaustive,
        String propertyPlanFingerprint,
        String inputSchemaFingerprint,
        PropertyGenerationPolicy generationPolicy,
        SourcePlanStatus sourcePlanStatus,
        boolean generationGapsAccepted,
        List<PropertyGenerationGap> generationGaps,
        List<PropertyTrialRef> propertyTrials,
        Map<String, Object> metadata
) implements TestSuiteProtocol {
    /** Current immutable property-suite protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuite.v4";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CASE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** Freezes all nested protocol values and validates the complete case lineage. */
    public TestSuiteV4 {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteId = normalized(suiteId);
        classification = defaulted(classification, "INTERNAL").toUpperCase(Locale.ROOT);
        cases = immutableCases(cases);
        coveragePolicy = coveragePolicy == null
                ? TestSuite.CoveragePolicy.defaults() : coveragePolicy;
        semanticCoveragePolicy = semanticCoveragePolicy == null
                ? SemanticCoveragePolicy.empty() : semanticCoveragePolicy;
        promotionPolicy = promotionPolicy == null
                ? TestSuite.PromotionPolicy.defaults() : promotionPolicy;
        evaluationMode = evaluationMode == null
                ? EvaluationMode.PROPERTY_EXECUTION : evaluationMode;
        quantification = quantification == null
                ? Quantification.BOUNDED_SAMPLED : quantification;
        propertyPlanFingerprint = normalized(propertyPlanFingerprint);
        inputSchemaFingerprint = normalized(inputSchemaFingerprint);
        generationPolicy = Objects.requireNonNull(generationPolicy, "generationPolicy");
        sourcePlanStatus = Objects.requireNonNull(sourcePlanStatus, "sourcePlanStatus");
        generationGaps = sortedGaps(generationGaps);
        propertyTrials = propertyTrials == null ? List.of() : List.copyOf(propertyTrials);
        metadata = immutableMap(metadata);

        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(propertyPlanFingerprint).matches()
                || !FINGERPRINT.matcher(inputSchemaFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Property suite requires its exact version and canonical fingerprints");
        }
        if (evaluationMode != EvaluationMode.PROPERTY_EXECUTION
                || quantification != Quantification.BOUNDED_SAMPLED || exhaustive) {
            throw new IllegalArgumentException(
                    "Property suite V4 is bounded sampled, non-exhaustive property execution");
        }
        if (sourcePlanStatus == SourcePlanStatus.GENERATED
                && (!generationGaps.isEmpty() || generationGapsAccepted
                || propertyTrials.size() != generationPolicy.requestedTrials())) {
            throw new IllegalArgumentException(
                    "Generated property suite requires all requested trials and no accepted gaps");
        }
        if (sourcePlanStatus == SourcePlanStatus.PARTIAL
                && (generationGaps.isEmpty() || !generationGapsAccepted)) {
            throw new IllegalArgumentException(
                    "Partial property suite requires explicit acceptance of disclosed gaps");
        }
        validateClosure(cases, propertyTrials, generationPolicy);
    }

    /** Evaluation mode owned by V4. */
    public enum EvaluationMode {
        /** Execute the exact frozen inputs under one governed fixture revision. */
        PROPERTY_EXECUTION
    }

    /** Honest quantifier attached to every V4 suite. */
    public enum Quantification {
        /** A finite deterministic sample that cannot prove the complete input space. */
        BOUNDED_SAMPLED
    }

    /** Source planning outcomes that contain at least one usable trial. */
    public enum SourcePlanStatus {
        /** Every requested unique trial was generated. */
        GENERATED,
        /** Usable trials exist, but one or more disclosed generation gaps remain. */
        PARTIAL
    }

    /** Stable generator limitation codes copied from the exact property plan. */
    public enum GenerationGapCode {
        OPAQUE_INPUT_SCHEMA,
        INVALID_INPUT_SCHEMA,
        BLOGE_SCHEMA_PROJECTION_WARNING,
        CONSTRAINT_NOT_GENERATED,
        UNIQUE_TRIAL_LIMIT_REACHED,
        CANDIDATE_NOT_PROVEN,
        DEPTH_LIMIT_REACHED,
        COLLECTION_LIMIT_REACHED,
        CASE_LIMIT_REACHED
    }

    /**
     * Exact deterministic policy used to create the frozen cases.
     *
     * @param generatorVersion algorithm generation
     * @param seed caller-selected deterministic seed
     * @param requestedTrials requested unique root trials
     * @param maxShrinkSteps maximum shrink candidates per root
     * @param maxCases maximum root plus shrink closure
     * @param maxGenerationAttempts bounded attempts per unique root
     * @param maxDepth maximum generated schema depth
     * @param maxCollectionItems maximum generated string or collection size
     * @param verificationMode validator boundary used to prove every input
     */
    public record PropertyGenerationPolicy(
            String generatorVersion,
            long seed,
            int requestedTrials,
            int maxShrinkSteps,
            int maxCases,
            int maxGenerationAttempts,
            int maxDepth,
            int maxCollectionItems,
            String verificationMode
    ) {
        /** Normalizes and bounds every policy dimension. */
        public PropertyGenerationPolicy {
            generatorVersion = normalized(generatorVersion);
            verificationMode = normalized(verificationMode);
            if (generatorVersion.isBlank() || verificationMode.isBlank()
                    || requestedTrials < 1 || requestedTrials > 16
                    || maxShrinkSteps < 0 || maxShrinkSteps > 5
                    || maxCases < 1 || maxCases > 96
                    || maxGenerationAttempts < 1 || maxGenerationAttempts > 10_000
                    || maxDepth < 1 || maxDepth > 64
                    || maxCollectionItems < 1 || maxCollectionItems > 10_000
                    || (long) requestedTrials * (maxShrinkSteps + 1L) > maxCases) {
                throw new IllegalArgumentException("Property generation policy is inconsistent");
            }
        }
    }

    /**
     * Stable generation gap without generated payload data.
     *
     * @param code machine-readable gap category
     * @param schemaPath bounded schema location
     * @param keyword affected constraint or resource bound
     */
    public record PropertyGenerationGap(
            GenerationGapCode code,
            String schemaPath,
            String keyword
    ) {
        /** Normalizes non-sensitive location fields. */
        public PropertyGenerationGap {
            code = Objects.requireNonNull(code, "code");
            schemaPath = normalized(schemaPath);
            keyword = normalized(keyword);
        }
    }

    /**
     * Canonical root trial and its ordered linear shrink path.
     *
     * @param trialId root case id
     * @param inputFingerprint canonical root input fingerprint
     * @param complexity deterministic root simplification score
     * @param shrinkPath ordered strictly simpler candidates
     */
    public record PropertyTrialRef(
            String trialId,
            String inputFingerprint,
            int complexity,
            List<PropertyShrinkRef> shrinkPath
    ) {
        /** Normalizes the reference and freezes its shrink closure. */
        public PropertyTrialRef {
            trialId = normalized(trialId);
            inputFingerprint = normalized(inputFingerprint);
            shrinkPath = shrinkPath == null ? List.of() : List.copyOf(shrinkPath);
        }
    }

    /**
     * Canonical coordinate of one precomputed shrink candidate.
     *
     * @param caseId shrink case id
     * @param parentCaseId immediately preceding root or shrink case
     * @param step one-based shrink step
     * @param inputFingerprint canonical candidate input fingerprint
     * @param complexity strictly smaller simplification score
     */
    public record PropertyShrinkRef(
            String caseId,
            String parentCaseId,
            int step,
            String inputFingerprint,
            int complexity
    ) {
        /** Normalizes structural identifiers and fingerprints. */
        public PropertyShrinkRef {
            caseId = normalized(caseId);
            parentCaseId = normalized(parentCaseId);
            inputFingerprint = normalized(inputFingerprint);
        }
    }

    private static void validateClosure(
            List<TestSuite.TestCase> cases,
            List<PropertyTrialRef> trials,
            PropertyGenerationPolicy policy) {
        if (trials.isEmpty() || trials.size() > policy.requestedTrials()
                || cases.isEmpty() || cases.size() > policy.maxCases()) {
            throw new IllegalArgumentException("Property suite exceeds its generation policy");
        }
        List<String> closure = new ArrayList<>();
        Set<String> inputFingerprints = new LinkedHashSet<>();
        for (int index = 0; index < trials.size(); index++) {
            PropertyTrialRef trial = Objects.requireNonNull(trials.get(index), "propertyTrial");
            String expectedId = "property-%03d".formatted(index + 1);
            requireCoordinate(trial.trialId(), expectedId, trial.inputFingerprint(), trial.complexity());
            if (!inputFingerprints.add(trial.inputFingerprint())
                    || trial.shrinkPath().size() > policy.maxShrinkSteps()) {
                throw new IllegalArgumentException(
                        "Property root fingerprints must be unique and shrink paths bounded");
            }
            closure.add(trial.trialId());
            String parent = trial.trialId();
            int previousComplexity = trial.complexity();
            for (int offset = 0; offset < trial.shrinkPath().size(); offset++) {
                PropertyShrinkRef shrink = Objects.requireNonNull(
                        trial.shrinkPath().get(offset), "propertyShrink");
                String expectedShrinkId = expectedId + "-shrink-%03d".formatted(offset + 1);
                requireCoordinate(shrink.caseId(), expectedShrinkId,
                        shrink.inputFingerprint(), shrink.complexity());
                if (shrink.step() != offset + 1 || !parent.equals(shrink.parentCaseId())
                        || shrink.complexity() >= previousComplexity) {
                    throw new IllegalArgumentException(
                            "Property shrink path must be linear and strictly simpler");
                }
                closure.add(shrink.caseId());
                parent = shrink.caseId();
                previousComplexity = shrink.complexity();
            }
        }
        List<String> caseIds = cases.stream().map(TestSuite.TestCase::caseId).toList();
        Set<TestSuite.FixtureBundleRef> fixtures = new LinkedHashSet<>();
        if (!closure.equals(caseIds) || cases.stream()
                .anyMatch(testCase -> testCase.caseType() != TestSuite.CaseType.PROPERTY)
                || cases.stream().map(TestSuite.TestCase::fixtureBundleRef).anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Property cases must exactly match the ordered lineage closure");
        }
        cases.stream().map(TestSuite.TestCase::fixtureBundleRef).forEach(fixtures::add);
        if (fixtures.size() != 1) {
            throw new IllegalArgumentException(
                    "Every property case must use one exact governed fixture revision");
        }
    }

    private static void requireCoordinate(
            String caseId, String expectedId, String inputFingerprint, int complexity) {
        if (!expectedId.equals(caseId) || !CASE_ID.matcher(caseId).matches()
                || !FINGERPRINT.matcher(inputFingerprint).matches() || complexity < 0) {
            throw new IllegalArgumentException("Property lineage coordinate is invalid");
        }
    }

    private static List<PropertyGenerationGap> sortedGaps(List<PropertyGenerationGap> values) {
        if (values == null) {
            return List.of();
        }
        List<PropertyGenerationGap> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        if (sorted.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Property generation gaps cannot contain null");
        }
        sorted.sort(Comparator.comparing((PropertyGenerationGap value) -> value.code().name())
                .thenComparing(PropertyGenerationGap::schemaPath)
                .thenComparing(PropertyGenerationGap::keyword));
        return List.copyOf(sorted);
    }

    private static List<TestSuite.TestCase> immutableCases(List<TestSuite.TestCase> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> {
            Objects.requireNonNull(value, "testCase");
            return new TestSuite.TestCase(value.caseId(), value.caseType(), deepFreeze(value.input()),
                    value.fixtureBundleRef(), value.tags(), immutableMap(value.metadata()));
        }).toList();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        if (values == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> frozen = new LinkedHashMap<>();
        values.forEach((key, value) -> frozen.put(key, deepFreeze(value)));
        return Collections.unmodifiableMap(frozen);
    }

    private static Object deepFreeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> frozen = new LinkedHashMap<>();
            map.forEach((key, nested) -> frozen.put(key, deepFreeze(nested)));
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(TestSuiteV4::deepFreeze).toList());
        }
        if (value instanceof Set<?> set) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(
                    set.stream().map(TestSuiteV4::deepFreeze).toList()));
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> frozen = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                frozen.add(deepFreeze(Array.get(value, index)));
            }
            return Collections.unmodifiableList(frozen);
        }
        return value;
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
