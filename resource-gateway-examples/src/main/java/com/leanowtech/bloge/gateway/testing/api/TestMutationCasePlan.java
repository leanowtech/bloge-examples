package com.leanowtech.bloge.gateway.testing.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed bounded mutation plan over one exact recoverable BLOGE DSL graph.
 *
 * <p>The plan contains no executable source or business literals. Each entry identifies a
 * deterministic AST rewrite, its structural location, and the fingerprints of the resulting DSL
 * definition, graph artifact, and complete target. A later mutation runner must regenerate every
 * mutant from the exact baseline source and reject any fingerprint mismatch before execution.</p>
 *
 * <p>This is an authoring asset, not mutation evidence or a mutation score. In particular,
 * {@link EquivalenceClassification#UNKNOWN} is deliberately explicit because compilation alone
 * cannot prove that a mutant changes observable behavior.</p>
 *
 * @param schemaVersion exact mutation-plan protocol version
 * @param target exact baseline graph target
 * @param sourceFormat recoverable graph definition format
 * @param sourceFingerprint exact baseline definition payload fingerprint
 * @param graphArtifactFingerprint canonical baseline graph artifact fingerprint
 * @param planFingerprint canonical fingerprint of the complete plan except this field
 * @param status planning completeness
 * @param policy deterministic mutation bounds and verification mode
 * @param mutants ordered independently compiling mutants
 * @param gaps stable limitations encountered while planning
 */
public record TestMutationCasePlan(
        String schemaVersion,
        TestExecutionApiRequest.Target target,
        String sourceFormat,
        String sourceFingerprint,
        String graphArtifactFingerprint,
        String planFingerprint,
        Status status,
        MutationPolicy policy,
        List<PlannedMutant> mutants,
        List<PlanningGap> gaps
) {
    /** Current pure-DSL mutation-plan protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testMutationCasePlan.v1";
    /** Exact deterministic planner generation accepted by this protocol. */
    public static final String PLANNER_VERSION = "pure-dsl-mutations-v1";
    /** Exact recoverable BLOGE DSL source format accepted by this protocol. */
    public static final String SOURCE_FORMAT = "bloge-dsl.ast.v1";
    /** Exact baseline and candidate proof mode accepted by this protocol. */
    public static final String VERIFICATION_MODE = "BLOGE_DSL_AST_RECOMPILE_PROOF";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern MUTANT_ID = Pattern.compile("mutant-[0-9]{3}");

    /** Freezes and validates the complete plan closure. */
    public TestMutationCasePlan {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        target = Objects.requireNonNull(target, "target");
        sourceFormat = normalized(sourceFormat);
        sourceFingerprint = normalized(sourceFingerprint);
        graphArtifactFingerprint = normalized(graphArtifactFingerprint);
        planFingerprint = normalized(planFingerprint);
        status = Objects.requireNonNull(status, "status");
        policy = Objects.requireNonNull(policy, "policy");
        mutants = mutants == null ? List.of() : List.copyOf(mutants);
        gaps = sortedGaps(gaps);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !"GRAPH".equals(target.kind())
                || sourceFormat.isBlank() || sourceFormat.length() > 128
                || !fingerprint(sourceFingerprint)
                || !fingerprint(graphArtifactFingerprint)
                || !fingerprint(planFingerprint)) {
            throw new IllegalArgumentException(
                    "Mutation plan requires its exact version, graph target, bounded source format,"
                            + " and fingerprints");
        }
        if (mutants.size() > policy.maxMutants()) {
            throw new IllegalArgumentException("Mutation plan exceeds maxMutants");
        }
        if (gaps.size() > 512) {
            throw new IllegalArgumentException("Mutation plan contains too many gaps");
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> definitions = new LinkedHashSet<>();
        for (int index = 0; index < mutants.size(); index++) {
            PlannedMutant mutant = Objects.requireNonNull(mutants.get(index), "mutant");
            String expectedId = "mutant-%03d".formatted(index + 1);
            if (!expectedId.equals(mutant.mutantId()) || !ids.add(mutant.mutantId())
                    || !definitions.add(mutant.mutantSourceFingerprint())) {
                throw new IllegalArgumentException(
                        "Mutation plan requires ordered unique mutant identities");
            }
        }
        if (status == Status.UNAVAILABLE && (!mutants.isEmpty() || gaps.isEmpty())) {
            throw new IllegalArgumentException(
                    "Unavailable mutation plan requires gaps and cannot contain mutants");
        }
        if (status != Status.UNAVAILABLE && mutants.isEmpty()) {
            throw new IllegalArgumentException("Available mutation plan requires mutants");
        }
        if (status == Status.GENERATED && !gaps.isEmpty()) {
            throw new IllegalArgumentException("Generated mutation plan cannot contain gaps");
        }
        if (status == Status.PARTIAL && gaps.isEmpty()) {
            throw new IllegalArgumentException("Partial mutation plan requires a disclosed gap");
        }
    }

    /** Honest aggregate planning outcomes. */
    public enum Status {
        /** Every discovered supported site was planned within the declared bound. */
        GENERATED,
        /** Usable mutants exist, but a bound or unsupported site left a disclosed gap. */
        PARTIAL,
        /** No independently compiling mutant could be produced safely. */
        UNAVAILABLE
    }

    /** Supported pure-DSL semantic mutation operators in protocol generation one. */
    public enum MutationKind {
        /** Toggle exclusive and inclusive branch fan-out semantics. */
        BRANCH_MODE_TOGGLED,
        /** Redirect one branch case to a sibling branch target. */
        BRANCH_CASE_TARGET_REPLACED,
        /** Redirect the default branch to an explicit case target. */
        BRANCH_OTHERWISE_TARGET_REPLACED,
        /** Negate one decision-table predicate without modifying its operator binding. */
        DECISION_CONDITION_NEGATED,
        /** Swap adjacent FIRST-hit decision rules. */
        DECISION_FIRST_RULE_ORDER_SWAPPED,
        /** Relax UNIQUE/ANY decision semantics to FIRST. */
        DECISION_HIT_POLICY_RELAXED,
        /** Swap adjacent transform output expressions. */
        TRANSFORM_BINDINGS_SWAPPED,
        /** Remove a node fallback while preserving the bound operator. */
        FALLBACK_REMOVED,
        /** Decrement a positive node retry count. */
        RETRY_ATTEMPTS_DECREMENTED
    }

    /** Equivalence classification carried before any mutant is executed. */
    public enum EquivalenceClassification {
        /** Observable equivalence has not been proven or disproven. */
        UNKNOWN
    }

    /** Stable planner gaps suitable for gates and low-cardinality telemetry. */
    public enum GapCode {
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
     * Exact bounded planner policy included in the plan fingerprint.
     *
     * @param plannerVersion deterministic planner generation
     * @param maxMutants maximum independently compiling mutants returned
     * @param sourceFormat required recoverable BLOGE DSL format
     * @param verificationMode baseline and mutant compiler proof boundary
     * @param externalOperatorMutation fixed false for this protocol generation
     * @param equivalentMutantDetection fixed false until execution evidence exists
     */
    public record MutationPolicy(
            String plannerVersion,
            int maxMutants,
            String sourceFormat,
            String verificationMode,
            boolean externalOperatorMutation,
            boolean equivalentMutantDetection
    ) {
        /** Validates fail-closed generation-one policy invariants. */
        public MutationPolicy {
            plannerVersion = normalized(plannerVersion);
            sourceFormat = normalized(sourceFormat);
            verificationMode = normalized(verificationMode);
            if (!PLANNER_VERSION.equals(plannerVersion)
                    || !SOURCE_FORMAT.equals(sourceFormat)
                    || !VERIFICATION_MODE.equals(verificationMode)
                    || maxMutants < 1 || maxMutants > 128
                    || externalOperatorMutation || equivalentMutantDetection) {
                throw new IllegalArgumentException("Mutation planning policy is inconsistent");
            }
        }
    }

    /**
     * One deterministic pure-DSL rewrite that independently recompiles.
     *
     * @param mutantId stable plan-local coordinate
     * @param kind mutation operator
     * @param astPath stable location in the recoverable graph AST
     * @param sourceLine one-based source line when preserved by the AST
     * @param sourceColumn one-based source column when preserved by the AST
     * @param mutantSourceFingerprint fingerprint of the complete mutated AST payload
     * @param mutantGraphArtifactFingerprint fingerprint of the independently compiled graph
     * @param mutantTargetFingerprint complete graph plus frozen dependency fingerprint
     * @param equivalenceClassification fixed unknown before execution
     */
    public record PlannedMutant(
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
        /** Normalizes structural fields and validates all content identities. */
        public PlannedMutant {
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
                throw new IllegalArgumentException("Planned mutant identity is inconsistent");
            }
        }
    }

    /**
     * Payload-free planning limitation.
     *
     * @param code machine-readable gap category
     * @param astPath bounded structural location
     * @param mutationKind affected mutation operator, or empty for source-level gaps
     */
    public record PlanningGap(GapCode code, String astPath, String mutationKind) {
        /** Normalizes safe structural fields. */
        public PlanningGap {
            code = Objects.requireNonNull(code, "code");
            astPath = normalized(astPath);
            mutationKind = normalized(mutationKind);
            if (astPath.length() > 512 || mutationKind.length() > 128) {
                throw new IllegalArgumentException("Mutation planning gap is unbounded");
            }
        }
    }

    private static List<PlanningGap> sortedGaps(List<PlanningGap> values) {
        List<PlanningGap> sorted = new ArrayList<>(values == null
                ? List.of() : new LinkedHashSet<>(values));
        if (sorted.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Mutation planning gaps cannot contain null");
        }
        sorted.sort(Comparator.comparing((PlanningGap gap) -> gap.code().name())
                .thenComparing(PlanningGap::astPath)
                .thenComparing(PlanningGap::mutationKind));
        return List.copyOf(sorted);
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(value).matches();
    }

    private static String defaulted(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
