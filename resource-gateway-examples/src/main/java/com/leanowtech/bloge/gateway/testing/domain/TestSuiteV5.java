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
 * Immutable, bounded pure-DSL mutation suite.
 *
 * <p>V5 freezes one exact mutation authoring plan together with the exact governed business suite
 * used as its oracle. It contains mutation coordinates and fingerprints, never executable DSL.
 * A runner must regenerate each graph from the current baseline through the mutation planner and
 * reject every source, graph-artifact, target, plan, suite, or fixture drift before execution.</p>
 *
 * <p>The suite bounds both dimensions of the execution matrix. At most 16 business cases and 16
 * mutants may be frozen, and their product may not exceed 256 mutant-case work units. The baseline
 * is evaluated separately and never contributes to the mutation-score denominator.</p>
 *
 * @param schemaVersion exact V5 suite schema version
 * @param suiteId stable mutation-suite identifier
 * @param revision immutable content-derived revision
 * @param target exact baseline graph target
 * @param classification maximum data classification
 * @param cases exact ordered case closure copied from the oracle suite
 * @param coveragePolicy oracle suite structural coverage policy
 * @param semanticCoveragePolicy oracle suite semantic coverage policy when present
 * @param promotionPolicy ordinary case promotion policy retained for provenance
 * @param evaluationMode fixed pure-DSL mutation mode
 * @param sourceFormat exact recoverable BLOGE DSL format
 * @param baselineSourceFingerprint exact recoverable baseline source fingerprint
 * @param baselineGraphArtifactFingerprint exact baseline graph-artifact fingerprint
 * @param mutationPlanFingerprint exact reviewed mutation-plan fingerprint
 * @param mutationPolicy deterministic planner and compiler proof policy
 * @param sourcePlanStatus complete or explicitly accepted partial source plan
 * @param planningGapsAccepted whether disclosed partial-plan gaps were accepted
 * @param planningGaps payload-free limitations copied from the exact plan
 * @param mutants ordered complete mutant closure copied from the exact plan
 * @param oracleSuiteRef exact immutable governed business suite
 * @param scorePolicy future score and gate policy over terminal mutant outcomes
 * @param metadata bounded provenance facts
 */
public record TestSuiteV5(
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
        String sourceFormat,
        String baselineSourceFingerprint,
        String baselineGraphArtifactFingerprint,
        String mutationPlanFingerprint,
        MutationPolicy mutationPolicy,
        SourcePlanStatus sourcePlanStatus,
        boolean planningGapsAccepted,
        List<PlanningGap> planningGaps,
        List<MutantRef> mutants,
        OracleSuiteRef oracleSuiteRef,
        MutationScorePolicy scorePolicy,
        Map<String, Object> metadata
) implements TestSuiteProtocol {
    /** Current immutable mutation-suite protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuite.v5";
    /** Maximum oracle cases frozen in one synchronous generation-one mutation suite. */
    public static final int MAX_CASES = 16;
    /** Maximum mutants frozen in one synchronous generation-one mutation suite. */
    public static final int MAX_MUTANTS = 16;
    /** Maximum bounded mutant-case work units in one immutable suite. */
    public static final int MAX_MUTANT_CASE_EXECUTIONS = 256;
    /** Exact generation-one planner identity. */
    public static final String PLANNER_VERSION = "pure-dsl-mutations-v1";
    /** Exact generation-one recoverable source format. */
    public static final String SOURCE_FORMAT = "bloge-dsl.ast.v1";
    /** Exact generation-one baseline and candidate compiler proof. */
    public static final String VERIFICATION_MODE = "BLOGE_DSL_AST_RECOMPILE_PROOF";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern MUTANT_ID = Pattern.compile("mutant-[0-9]{3}");

    /** Recursively freezes the suite and validates its complete bounded proof closure. */
    public TestSuiteV5 {
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
                ? EvaluationMode.PURE_DSL_MUTATION : evaluationMode;
        sourceFormat = normalized(sourceFormat);
        baselineSourceFingerprint = normalized(baselineSourceFingerprint);
        baselineGraphArtifactFingerprint = normalized(baselineGraphArtifactFingerprint);
        mutationPlanFingerprint = normalized(mutationPlanFingerprint);
        mutationPolicy = Objects.requireNonNull(mutationPolicy, "mutationPolicy");
        sourcePlanStatus = Objects.requireNonNull(sourcePlanStatus, "sourcePlanStatus");
        planningGaps = sortedGaps(planningGaps);
        mutants = mutants == null ? List.of() : List.copyOf(mutants);
        oracleSuiteRef = Objects.requireNonNull(oracleSuiteRef, "oracleSuiteRef");
        scorePolicy = Objects.requireNonNull(scorePolicy, "scorePolicy");
        metadata = immutableMap(metadata);

        if (!SCHEMA_VERSION.equals(schemaVersion)
                || target == null || !"GRAPH".equals(target.kind())
                || evaluationMode != EvaluationMode.PURE_DSL_MUTATION
                || !SOURCE_FORMAT.equals(sourceFormat)
                || !fingerprint(baselineSourceFingerprint)
                || !fingerprint(baselineGraphArtifactFingerprint)
                || !fingerprint(mutationPlanFingerprint)) {
            throw new IllegalArgumentException(
                    "Mutation suite requires its exact version, graph target, mode, and fingerprints");
        }
        if (cases.isEmpty() || cases.size() > MAX_CASES
                || mutants.isEmpty() || mutants.size() > MAX_MUTANTS
                || (long) cases.size() * mutants.size() > MAX_MUTANT_CASE_EXECUTIONS) {
            throw new IllegalArgumentException("Mutation suite execution matrix exceeds its bounds");
        }
        if (sourcePlanStatus == SourcePlanStatus.GENERATED
                && (!planningGaps.isEmpty() || planningGapsAccepted)) {
            throw new IllegalArgumentException(
                    "Generated mutation suite cannot accept or contain planning gaps");
        }
        if (sourcePlanStatus == SourcePlanStatus.PARTIAL
                && (planningGaps.isEmpty() || !planningGapsAccepted)) {
            throw new IllegalArgumentException(
                    "Partial mutation suite requires explicit acceptance of planning gaps");
        }
        requireMutantClosure(mutants, mutationPolicy);
    }

    /** Evaluation mode owned by V5. */
    public enum EvaluationMode {
        /** Regenerate and execute only pure orchestration DSL mutations. */
        PURE_DSL_MUTATION
    }

    /** Usable source-plan outcomes accepted by V5. */
    public enum SourcePlanStatus {
        /** Every discovered supported site fit within the reviewed bound. */
        GENERATED,
        /** Usable mutants exist and every omitted or unsupported site is disclosed. */
        PARTIAL
    }

    /** Exact generation-one mutation operators. */
    public enum MutationKind {
        BRANCH_MODE_TOGGLED,
        BRANCH_CASE_TARGET_REPLACED,
        BRANCH_OTHERWISE_TARGET_REPLACED,
        DECISION_CONDITION_NEGATED,
        DECISION_FIRST_RULE_ORDER_SWAPPED,
        DECISION_HIT_POLICY_RELAXED,
        TRANSFORM_BINDINGS_SWAPPED,
        FALLBACK_REMOVED,
        RETRY_ATTEMPTS_DECREMENTED
    }

    /** Stable planning gaps copied from the exact source plan. */
    public enum PlanningGapCode {
        RECOVERABLE_DSL_SOURCE_UNAVAILABLE,
        UNSUPPORTED_DSL_SOURCE_FORMAT,
        DSL_SOURCE_DECODE_FAILED,
        BASELINE_RECOMPILATION_FAILED,
        BASELINE_RECOMPILATION_MISMATCH,
        IMPORTED_GRAPH_MUTATION_UNSUPPORTED,
        EXTENSION_MUTATION_UNSUPPORTED,
        NESTED_SCOPE_NOT_EXPANDED,
        MUTANT_COMPILATION_REJECTED,
        MUTANT_DUPLICATE_REJECTED,
        MUTANT_LIMIT_REACHED,
        NO_SUPPORTED_MUTATION_SITE
    }

    /**
     * Exact authoring policy copied into the immutable suite.
     *
     * @param plannerVersion deterministic planner generation
     * @param maxMutants reviewed generation bound
     * @param sourceFormat required recoverable source format
     * @param verificationMode baseline and candidate compiler proof
     * @param externalOperatorMutation fixed false
     * @param equivalentMutantDetection fixed false in generation one
     */
    public record MutationPolicy(
            String plannerVersion,
            int maxMutants,
            String sourceFormat,
            String verificationMode,
            boolean externalOperatorMutation,
            boolean equivalentMutantDetection
    ) {
        /** Validates generation-one policy invariants. */
        public MutationPolicy {
            plannerVersion = normalized(plannerVersion);
            sourceFormat = normalized(sourceFormat);
            verificationMode = normalized(verificationMode);
            if (!PLANNER_VERSION.equals(plannerVersion)
                    || maxMutants < 1 || maxMutants > MAX_MUTANTS
                    || !SOURCE_FORMAT.equals(sourceFormat)
                    || !VERIFICATION_MODE.equals(verificationMode)
                    || externalOperatorMutation || equivalentMutantDetection) {
                throw new IllegalArgumentException("Mutation suite policy is inconsistent");
            }
        }
    }

    /**
     * Payload-free planning limitation.
     *
     * @param code machine-readable category
     * @param astPath bounded structural location
     * @param mutationKind affected mutation operator or empty for source-level gaps
     */
    public record PlanningGap(PlanningGapCode code, String astPath, String mutationKind) {
        /** Normalizes safe structural fields. */
        public PlanningGap {
            code = Objects.requireNonNull(code, "code");
            astPath = normalized(astPath);
            mutationKind = normalized(mutationKind);
            if (!astPath.startsWith("/") || astPath.length() > 2_048
                    || mutationKind.length() > 128) {
                throw new IllegalArgumentException("Mutation planning gap is invalid");
            }
        }
    }

    /**
     * Exact planned mutant coordinate without executable source.
     *
     * @param mutantId stable suite-local coordinate
     * @param kind mutation operator
     * @param astPath structural source location
     * @param sourceLine one-based source line
     * @param sourceColumn one-based source column
     * @param mutantSourceFingerprint exact mutated AST fingerprint
     * @param mutantGraphArtifactFingerprint exact compiled graph fingerprint
     * @param mutantTargetFingerprint exact graph plus dependency fingerprint
     * @param equivalenceClassification fixed UNKNOWN before execution
     */
    public record MutantRef(
            String mutantId,
            MutationKind kind,
            String astPath,
            int sourceLine,
            int sourceColumn,
            String mutantSourceFingerprint,
            String mutantGraphArtifactFingerprint,
            String mutantTargetFingerprint,
            EquivalenceClassification equivalenceClassification
    ) {
        /** Normalizes and validates all content identities. */
        public MutantRef {
            mutantId = normalized(mutantId);
            kind = Objects.requireNonNull(kind, "kind");
            astPath = normalized(astPath);
            mutantSourceFingerprint = normalized(mutantSourceFingerprint);
            mutantGraphArtifactFingerprint = normalized(mutantGraphArtifactFingerprint);
            mutantTargetFingerprint = normalized(mutantTargetFingerprint);
            equivalenceClassification = equivalenceClassification == null
                    ? EquivalenceClassification.UNKNOWN : equivalenceClassification;
            if (!MUTANT_ID.matcher(mutantId).matches() || !astPath.startsWith("/")
                    || sourceLine < 1 || sourceColumn < 1
                    || !fingerprint(mutantSourceFingerprint)
                    || !fingerprint(mutantGraphArtifactFingerprint)
                    || !fingerprint(mutantTargetFingerprint)
                    || equivalenceClassification != EquivalenceClassification.UNKNOWN) {
                throw new IllegalArgumentException("Mutation suite mutant identity is inconsistent");
            }
        }
    }

    /** Pre-execution equivalence state. */
    public enum EquivalenceClassification {
        /** No observable equivalence claim has been established. */
        UNKNOWN
    }

    /**
     * Exact immutable business suite used as the mutation oracle.
     *
     * @param suiteId stable oracle suite id
     * @param revision exact positive oracle revision
     * @param fingerprint exact oracle content fingerprint
     * @param schemaVersion exact concrete oracle suite generation
     */
    public record OracleSuiteRef(
            String suiteId,
            long revision,
            String fingerprint,
            String schemaVersion
    ) {
        /** Validates the full immutable oracle coordinate. */
        public OracleSuiteRef {
            suiteId = normalized(suiteId);
            fingerprint = normalized(fingerprint);
            schemaVersion = normalized(schemaVersion);
            if (suiteId.isBlank() || suiteId.length() > 255 || revision <= 0
                    || !TestSuite.SCHEMA_VERSION.equals(schemaVersion)
                    && !TestSuiteV2.SCHEMA_VERSION.equals(schemaVersion)
                    && !TestSuiteV4.SCHEMA_VERSION.equals(schemaVersion)
                    || !TestSuiteV5.fingerprint(fingerprint)) {
                throw new IllegalArgumentException("Mutation oracle suite reference is invalid");
            }
        }
    }

    /**
     * Score policy frozen before execution.
     *
     * <p>Generation one never removes mutants as equivalent. Inconclusive mutants are excluded
     * from the numeric denominator but independently bounded; exceeding the bound must block a
     * future gate instead of inflating the score.</p>
     *
     * @param minimumScoreBasisPoints required killed percentage in basis points
     * @param maximumInconclusiveMutants maximum tolerated inconclusive outcomes
     * @param requireNoSurvivors whether any survived mutant blocks promotion regardless of score
     * @param excludeEquivalentMutants fixed false until equivalence proof is implemented
     */
    public record MutationScorePolicy(
            int minimumScoreBasisPoints,
            int maximumInconclusiveMutants,
            boolean requireNoSurvivors,
            boolean excludeEquivalentMutants
    ) {
        /** Bounds score and honesty policy. */
        public MutationScorePolicy {
            if (minimumScoreBasisPoints < 0 || minimumScoreBasisPoints > 10_000
                    || maximumInconclusiveMutants < 0
                    || maximumInconclusiveMutants > MAX_MUTANTS
                    || excludeEquivalentMutants) {
                throw new IllegalArgumentException("Mutation score policy is inconsistent");
            }
        }
    }

    private static void requireMutantClosure(List<MutantRef> mutants, MutationPolicy policy) {
        if (mutants.size() > policy.maxMutants()) {
            throw new IllegalArgumentException("Mutation suite exceeds its planner bound");
        }
        Set<String> sourceFingerprints = new LinkedHashSet<>();
        for (int index = 0; index < mutants.size(); index++) {
            MutantRef mutant = Objects.requireNonNull(mutants.get(index), "mutant");
            if (!("mutant-%03d".formatted(index + 1)).equals(mutant.mutantId())
                    || !sourceFingerprints.add(mutant.mutantSourceFingerprint())) {
                throw new IllegalArgumentException(
                        "Mutation suite requires ordered unique mutant identities");
            }
        }
    }

    private static List<PlanningGap> sortedGaps(List<PlanningGap> values) {
        if (values == null) {
            return List.of();
        }
        List<PlanningGap> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        if (sorted.size() > 512 || sorted.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Mutation suite planning gaps are invalid");
        }
        sorted.sort(Comparator.comparing((PlanningGap value) -> value.code().name())
                .thenComparing(PlanningGap::astPath)
                .thenComparing(PlanningGap::mutationKind));
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
            return Collections.unmodifiableList(list.stream().map(TestSuiteV5::deepFreeze).toList());
        }
        if (value instanceof Set<?> set) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(
                    set.stream().map(TestSuiteV5::deepFreeze).toList()));
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

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
