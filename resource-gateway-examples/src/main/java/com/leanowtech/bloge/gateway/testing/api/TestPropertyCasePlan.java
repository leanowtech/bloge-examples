package com.leanowtech.bloge.gateway.testing.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed, seed-reproducible property input plan for one exact target contract.
 *
 * <p>The plan freezes a bounded set of unique trials and a validator-proven linear shrink path for
 * each trial. It is an authoring asset, not execution evidence: {@link Quantification#BOUNDED_SAMPLED}
 * and {@code exhaustive=false} are first-class facts so trial count cannot be presented as proof of
 * exhaustive input-space coverage.</p>
 *
 * @param schemaVersion exact property-plan protocol version
 * @param target exact graph or operator target
 * @param inputSchemaFingerprint canonical projected input-schema fingerprint
 * @param planFingerprint canonical fingerprint of the complete plan except this field
 * @param status generation completeness
 * @param quantification fixed bounded-sampling meaning
 * @param exhaustive fixed false for this protocol generation
 * @param policy exact reproducibility and resource bounds
 * @param trials ordered unique root trials and their shrink paths
 * @param gaps stable limits or unsupported constraints encountered while generating
 */
public record TestPropertyCasePlan(
        String schemaVersion,
        TestExecutionApiRequest.Target target,
        String inputSchemaFingerprint,
        String planFingerprint,
        Status status,
        Quantification quantification,
        boolean exhaustive,
        GenerationPolicy policy,
        List<PropertyTrial> trials,
        List<CoverageGap> gaps
) {
    /** Current public seeded-property-plan protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testPropertyCasePlan.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CASE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** Freezes nested inputs and validates the complete trial/shrink closure. */
    public TestPropertyCasePlan {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        target = Objects.requireNonNull(target, "target");
        inputSchemaFingerprint = normalized(inputSchemaFingerprint);
        planFingerprint = normalized(planFingerprint);
        status = Objects.requireNonNull(status, "status");
        quantification = quantification == null
                ? Quantification.BOUNDED_SAMPLED : quantification;
        policy = Objects.requireNonNull(policy, "policy");
        trials = immutableTrials(trials);
        gaps = sortedGaps(gaps);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(inputSchemaFingerprint).matches()
                || !FINGERPRINT.matcher(planFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Property plan requires exact version and canonical fingerprints");
        }
        if (quantification != Quantification.BOUNDED_SAMPLED || exhaustive) {
            throw new IllegalArgumentException(
                    "Property plan v1 is bounded sampled and never exhaustive");
        }
        if (trials.size() > policy.requestedTrials()
                || allCases(trials).size() > policy.maxCases()) {
            throw new IllegalArgumentException("Property plan exceeds its generation policy");
        }
        if (status == Status.UNAVAILABLE && (!trials.isEmpty() || gaps.isEmpty())) {
            throw new IllegalArgumentException(
                    "Unavailable property plan requires gaps and cannot contain trials");
        }
        if (status != Status.UNAVAILABLE && trials.isEmpty()) {
            throw new IllegalArgumentException("Available property plan requires trials");
        }
        if (status == Status.GENERATED
                && (!gaps.isEmpty() || trials.size() != policy.requestedTrials())) {
            throw new IllegalArgumentException(
                    "Generated property plan requires every requested unique trial and no gap");
        }
        if (status == Status.PARTIAL && gaps.isEmpty()) {
            throw new IllegalArgumentException("Partial property plan requires a disclosed gap");
        }
        validateClosure(trials, policy);
    }

    /** Honest aggregate planning outcomes. */
    public enum Status {
        /** Every requested unique trial was generated within declared bounds. */
        GENERATED,
        /** Some trials are usable, but a generation or constraint gap remains. */
        PARTIAL,
        /** No validator-proven unique trial could be generated. */
        UNAVAILABLE
    }

    /** Quantifier attached to every v1 plan and later evidence. */
    public enum Quantification {
        /** A finite deterministic sample; no exhaustive-space claim is permitted. */
        BOUNDED_SAMPLED
    }

    /** Stable property-generation gaps suitable for gates and telemetry. */
    public enum GapCode {
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
     * Exact deterministic generation policy included in the plan fingerprint.
     *
     * @param generatorVersion algorithm generation
     * @param seed caller-selected reproducibility seed
     * @param requestedTrials requested unique root trials
     * @param maxShrinkSteps maximum shrink candidates per root trial
     * @param maxCases maximum root plus shrink cases
     * @param maxGenerationAttempts attempts allowed for each unique root trial
     * @param maxDepth maximum recursive schema depth
     * @param maxCollectionItems maximum generated string or collection size
     * @param verificationMode shared-validator proof boundary
     */
    public record GenerationPolicy(
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
        /** Validates positive bounded policy dimensions. */
        public GenerationPolicy {
            generatorVersion = normalized(generatorVersion);
            verificationMode = normalized(verificationMode);
            if (generatorVersion.isBlank() || verificationMode.isBlank()
                    || requestedTrials < 1 || maxShrinkSteps < 0 || maxCases < 1
                    || maxGenerationAttempts < 1 || maxDepth < 1
                    || maxCollectionItems < 1
                    || (long) requestedTrials * (maxShrinkSteps + 1L) > maxCases) {
                throw new IllegalArgumentException("Property generation policy is inconsistent");
            }
        }
    }

    /** Common read-only view over root and shrink cases. */
    public sealed interface PlannedCase permits PropertyTrial, ShrinkCandidate {
        /** @return suite-safe stable case id */
        String caseId();
        /** @return recursively immutable generated input */
        Object input();
        /** @return canonical input fingerprint */
        String inputFingerprint();
        /** @return deterministic non-negative simplification score */
        int complexity();
    }

    /**
     * One unique seeded root trial and its precomputed linear shrink path.
     *
     * @param trialId stable root case id
     * @param input validator-proven root input
     * @param inputFingerprint canonical input fingerprint
     * @param complexity deterministic root complexity
     * @param shrinkPath ordered strictly simpler candidates
     */
    public record PropertyTrial(
            String trialId,
            Object input,
            String inputFingerprint,
            int complexity,
            List<ShrinkCandidate> shrinkPath
    ) implements PlannedCase {
        /** Freezes input and shrink path. */
        public PropertyTrial {
            trialId = normalized(trialId);
            input = deepFreeze(input);
            inputFingerprint = normalized(inputFingerprint);
            shrinkPath = shrinkPath == null ? List.of() : List.copyOf(shrinkPath);
        }

        /** @return root trial id as its case id */
        @Override
        public String caseId() {
            return trialId;
        }
    }

    /**
     * One validator-proven candidate in a trial's linear shrink path.
     *
     * @param caseId stable shrink case id
     * @param parentCaseId immediately preceding root or shrink case
     * @param step one-based shrink step
     * @param input recursively immutable candidate input
     * @param inputFingerprint canonical candidate input fingerprint
     * @param complexity strictly smaller deterministic score
     */
    public record ShrinkCandidate(
            String caseId,
            String parentCaseId,
            int step,
            Object input,
            String inputFingerprint,
            int complexity
    ) implements PlannedCase {
        /** Freezes input and normalizes chain identifiers. */
        public ShrinkCandidate {
            caseId = normalized(caseId);
            parentCaseId = normalized(parentCaseId);
            input = deepFreeze(input);
            inputFingerprint = normalized(inputFingerprint);
        }
    }

    /**
     * Stable generation limitation without payload data.
     *
     * @param code machine-readable gap category
     * @param schemaPath bounded schema location
     * @param keyword affected constraint or bound
     */
    public record CoverageGap(GapCode code, String schemaPath, String keyword) {
        /** Normalizes location fields. */
        public CoverageGap {
            code = Objects.requireNonNull(code, "code");
            schemaPath = normalized(schemaPath);
            keyword = normalized(keyword);
        }
    }

    /** @return root trials followed immediately by each trial's shrink path */
    public List<PlannedCase> allCases() {
        return allCases(trials);
    }

    private static void validateClosure(List<PropertyTrial> values, GenerationPolicy policy) {
        Set<String> caseIds = new LinkedHashSet<>();
        Set<String> rootFingerprints = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            PropertyTrial trial = values.get(index);
            String expectedTrialId = "property-%03d".formatted(index + 1);
            requireCase(trial, expectedTrialId, caseIds);
            if (!rootFingerprints.add(trial.inputFingerprint())) {
                throw new IllegalArgumentException("Property root trials must be unique");
            }
            if (trial.shrinkPath().size() > policy.maxShrinkSteps()) {
                throw new IllegalArgumentException("Property shrink path exceeds policy");
            }
            String parent = trial.trialId();
            int complexity = trial.complexity();
            for (int step = 0; step < trial.shrinkPath().size(); step++) {
                ShrinkCandidate shrink = trial.shrinkPath().get(step);
                String expectedId = trial.trialId() + "-shrink-%03d".formatted(step + 1);
                requireCase(shrink, expectedId, caseIds);
                if (shrink.step() != step + 1 || !parent.equals(shrink.parentCaseId())
                        || shrink.complexity() >= complexity) {
                    throw new IllegalArgumentException(
                            "Property shrink path must be linear and strictly simpler");
                }
                parent = shrink.caseId();
                complexity = shrink.complexity();
            }
        }
    }

    private static void requireCase(PlannedCase value, String expectedId, Set<String> caseIds) {
        if (value == null || !expectedId.equals(value.caseId())
                || !CASE_ID.matcher(value.caseId()).matches()
                || !FINGERPRINT.matcher(value.inputFingerprint()).matches()
                || value.complexity() < 0 || !caseIds.add(value.caseId())) {
            throw new IllegalArgumentException("Property plan case closure is invalid");
        }
    }

    private static List<PropertyTrial> immutableTrials(List<PropertyTrial> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<CoverageGap> sortedGaps(List<CoverageGap> values) {
        if (values == null) {
            return List.of();
        }
        List<CoverageGap> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        sorted.sort(Comparator.comparing((CoverageGap value) -> value.code().name())
                .thenComparing(CoverageGap::schemaPath)
                .thenComparing(CoverageGap::keyword));
        return List.copyOf(sorted);
    }

    private static List<PlannedCase> allCases(List<PropertyTrial> values) {
        List<PlannedCase> result = new ArrayList<>();
        for (PropertyTrial trial : values) {
            result.add(trial);
            result.addAll(trial.shrinkPath());
        }
        return List.copyOf(result);
    }

    private static Object deepFreeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), deepFreeze(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(nested -> copy.add(deepFreeze(nested)));
            return Collections.unmodifiableList(copy);
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
