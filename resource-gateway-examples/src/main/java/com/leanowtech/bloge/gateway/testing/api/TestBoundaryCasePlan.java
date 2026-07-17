package com.leanowtech.bloge.gateway.testing.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Content-addressed, validator-proven boundary input plan for one exact test target.
 *
 * <p>The plan is an authoring asset, not execution evidence. Every emitted case has already been
 * checked against the same supported schema validator used by graph input admission. Unsupported
 * constraints and generation bounds are explicit gaps, so consumers cannot mistake a partial
 * input-space sample for exhaustive property coverage.</p>
 *
 * @param schemaVersion boundary-plan protocol version
 * @param target exact graph or operator target used to obtain the input schema
 * @param inputSchemaFingerprint canonical projected input-schema fingerprint
 * @param planFingerprint canonical fingerprint of the complete plan except this field
 * @param status whether useful cases were generated and whether coverage gaps remain
 * @param policy deterministic generation and verification bounds
 * @param cases ordered validator-proven boundary inputs
 * @param gaps stable reasons why the supported input space was not exhaustively expanded
 */
public record TestBoundaryCasePlan(
        String schemaVersion,
        TestExecutionApiRequest.Target target,
        String inputSchemaFingerprint,
        String planFingerprint,
        Status status,
        GenerationPolicy policy,
        List<BoundaryCase> cases,
        List<CoverageGap> gaps
) {
    /** Current public boundary-plan protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testBoundaryCasePlan.v1";

    /** Normalizes protocol values and freezes all generated JSON input trees. */
    public TestBoundaryCasePlan {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION
                : normalized(schemaVersion);
        target = Objects.requireNonNull(target, "target");
        inputSchemaFingerprint = normalized(inputSchemaFingerprint);
        planFingerprint = normalized(planFingerprint);
        status = Objects.requireNonNull(status, "status");
        policy = Objects.requireNonNull(policy, "policy");
        cases = cases == null ? List.of() : List.copyOf(cases);
        gaps = sortedGaps(gaps);
        if (!inputSchemaFingerprint.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("inputSchemaFingerprint must be canonical SHA-256");
        }
        if (!planFingerprint.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("planFingerprint must be canonical SHA-256");
        }
        if (cases.size() > policy.maxCases()) {
            throw new IllegalArgumentException("Boundary plan exceeds maxCases");
        }
        if (status == Status.UNAVAILABLE && !cases.isEmpty()) {
            throw new IllegalArgumentException("Unavailable boundary plan cannot contain cases");
        }
        if (status != Status.UNAVAILABLE && cases.isEmpty()) {
            throw new IllegalArgumentException("Generated boundary plan requires at least one case");
        }
        if (status == Status.GENERATED && !gaps.isEmpty()) {
            throw new IllegalArgumentException("Generated boundary plan cannot contain coverage gaps");
        }
        if (status == Status.PARTIAL && gaps.isEmpty()) {
            throw new IllegalArgumentException("Partial boundary plan requires coverage gaps");
        }
    }

    /** Honest aggregate generation outcomes. */
    public enum Status {
        /** Cases were generated without a known unsupported constraint or truncation. */
        GENERATED,
        /** Cases are useful, but one or more constraints or bounds were not expanded. */
        PARTIAL,
        /** No baseline input could be proven valid, so no cases are published. */
        UNAVAILABLE
    }

    /** Validator-proven expected result when the generated input reaches schema admission. */
    public enum ExpectedOutcome {
        /** The supported schema validator accepted the complete input. */
        ACCEPTED,
        /** The supported schema validator rejected the complete input. */
        SCHEMA_REJECTED
    }

    /** Stable boundary transformations understood by the v1 planner. */
    public enum BoundaryKind {
        BASELINE,
        REQUIRED_PROPERTY_MISSING,
        UNKNOWN_PROPERTY,
        TYPE_MISMATCH,
        MINIMUM,
        BELOW_MINIMUM,
        EXCLUSIVE_MINIMUM,
        AT_EXCLUSIVE_MINIMUM,
        MAXIMUM,
        ABOVE_MAXIMUM,
        EXCLUSIVE_MAXIMUM,
        AT_EXCLUSIVE_MAXIMUM,
        MIN_LENGTH,
        BELOW_MIN_LENGTH,
        MAX_LENGTH,
        ABOVE_MAX_LENGTH,
        MIN_ITEMS,
        BELOW_MIN_ITEMS,
        MAX_ITEMS,
        ABOVE_MAX_ITEMS,
        ENUM_MEMBER,
        OUTSIDE_ENUM,
        CONST_VALUE,
        OUTSIDE_CONST
    }

    /** Stable non-exhaustiveness reasons suitable for authoring gates and metrics. */
    public enum GapCode {
        OPAQUE_INPUT_SCHEMA,
        INVALID_INPUT_SCHEMA,
        BASELINE_NOT_PROVEN,
        BLOGE_SCHEMA_PROJECTION_WARNING,
        CONSTRAINT_NOT_BOUNDARY_EXPANDED,
        CANDIDATE_NOT_PROVEN,
        CASE_LIMIT_REACHED,
        DEPTH_LIMIT_REACHED,
        COLLECTION_LIMIT_REACHED
    }

    /**
     * Immutable generation policy carried with every plan.
     *
     * @param generatorVersion deterministic planner algorithm generation
     * @param maxCases maximum published cases including the baseline
     * @param maxDepth maximum recursive schema/property depth
     * @param maxCollectionItems maximum generated string length or collection size
     * @param verificationMode proof boundary applied to every candidate
     */
    public record GenerationPolicy(
            String generatorVersion,
            int maxCases,
            int maxDepth,
            int maxCollectionItems,
            String verificationMode
    ) {
        /** Validates stable, bounded generation policy values. */
        public GenerationPolicy {
            generatorVersion = normalized(generatorVersion);
            verificationMode = normalized(verificationMode);
            if (generatorVersion.isBlank() || verificationMode.isBlank()) {
                throw new IllegalArgumentException(
                        "Boundary generation policy requires version and verification mode");
            }
            if (maxCases < 1 || maxCases > 256 || maxDepth < 1 || maxDepth > 32
                    || maxCollectionItems < 1 || maxCollectionItems > 1_024) {
                throw new IllegalArgumentException("Boundary generation policy exceeds safe bounds");
            }
        }
    }

    /**
     * One whole-input candidate proven by schema admission.
     *
     * @param caseId deterministic plan-local identifier
     * @param kind boundary transformation applied to the baseline
     * @param instancePath JSON Pointer to the transformed input location
     * @param schemaPath JSON Pointer to the governing schema location
     * @param expectedOutcome validator-proven expected admission result
     * @param input complete graph context or operator input value
     * @param validationCodes stable rejection diagnostics; empty for accepted cases
     */
    public record BoundaryCase(
            String caseId,
            BoundaryKind kind,
            String instancePath,
            String schemaPath,
            ExpectedOutcome expectedOutcome,
            Object input,
            List<String> validationCodes
    ) {
        /** Normalizes coordinates and recursively freezes the generated input. */
        public BoundaryCase {
            caseId = normalized(caseId);
            kind = Objects.requireNonNull(kind, "kind");
            instancePath = normalized(instancePath);
            schemaPath = normalized(schemaPath);
            expectedOutcome = Objects.requireNonNull(expectedOutcome, "expectedOutcome");
            input = freeze(input);
            validationCodes = sortedStrings(validationCodes);
            if (caseId.isBlank() || caseId.length() > 128) {
                throw new IllegalArgumentException("Boundary caseId must be 1..128 characters");
            }
            if (expectedOutcome == ExpectedOutcome.ACCEPTED && !validationCodes.isEmpty()) {
                throw new IllegalArgumentException("Accepted boundary case cannot have errors");
            }
            if (expectedOutcome == ExpectedOutcome.SCHEMA_REJECTED
                    && validationCodes.isEmpty()) {
                throw new IllegalArgumentException("Rejected boundary case requires diagnostics");
            }
        }
    }

    /**
     * One stable disclosure of a generation limitation.
     *
     * @param code stable gap category
     * @param schemaPath affected schema location
     * @param keyword affected keyword or projection diagnostic code
     */
    public record CoverageGap(GapCode code, String schemaPath, String keyword) {
        /** Normalizes gap coordinates for deterministic ordering and fingerprinting. */
        public CoverageGap {
            code = Objects.requireNonNull(code, "code");
            schemaPath = normalized(schemaPath);
            keyword = normalized(keyword);
        }
    }

    private static List<CoverageGap> sortedGaps(List<CoverageGap> values) {
        if (values == null) {
            return List.of();
        }
        List<CoverageGap> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        sorted.sort(Comparator.comparing((CoverageGap gap) -> gap.code().name())
                .thenComparing(CoverageGap::schemaPath)
                .thenComparing(CoverageGap::keyword));
        return List.copyOf(sorted);
    }

    private static List<String> sortedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        sorted.replaceAll(TestBoundaryCasePlan::normalized);
        sorted.removeIf(String::isBlank);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> frozen = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> frozen.put(String.valueOf(key), freeze(item)));
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(TestBoundaryCasePlan::freeze).toList();
        }
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
