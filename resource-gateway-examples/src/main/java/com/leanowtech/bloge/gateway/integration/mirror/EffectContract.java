package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;

/**
 * Conservative summary of every read and mutation reachable through a capability.
 *
 * <p>An effect contract is transitive for composed capabilities. Unknown effects never collapse to
 * read-only. A compiler must either resolve them or preserve an {@link Mode#UNKNOWN} contract that
 * cannot be admitted to a write-capable mirror plan.</p>
 *
 * @param schemaVersion effect protocol version
 * @param mode conservative top-level effect mode
 * @param readSet resource or entity patterns that may be read
 * @param writeSet resource or entity patterns that may be changed
 * @param conditionalEffects condition-labelled effect summaries
 * @param compensationRef exact compensation capability, when one exists
 * @param requiresApproval whether use requires an explicit governance approval
 * @param riskLevel highest transitive risk level
 * @param derivation how the summary was produced
 * @param unresolvedReasons bounded reasons that prevent a complete summary
 */
public record EffectContract(
        String schemaVersion,
        Mode mode,
        List<String> readSet,
        List<String> writeSet,
        List<ConditionalEffect> conditionalEffects,
        MirrorArtifactRef compensationRef,
        boolean requiresApproval,
        RiskLevel riskLevel,
        Derivation derivation,
        List<String> unresolvedReasons
) {
    /** Current effect protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.effectContract.v1";

    /** Conservative behavior visible to a runtime or release gate. */
    public enum Mode {
        READ_ONLY,
        VIRTUAL_MUTATION,
        EXTERNAL_MUTATION,
        MIXED,
        UNKNOWN
    }

    /** Ordered risk levels used by transitive aggregation. */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL;

        /**
         * Returns the more restrictive of two risk levels.
         *
         * @param left first risk
         * @param right second risk
         * @return highest risk, defaulting missing inputs to {@link #CRITICAL}
         */
        public static RiskLevel max(RiskLevel left, RiskLevel right) {
            RiskLevel effectiveLeft = left == null ? CRITICAL : left;
            RiskLevel effectiveRight = right == null ? CRITICAL : right;
            return effectiveLeft.ordinal() >= effectiveRight.ordinal() ? effectiveLeft : effectiveRight;
        }
    }

    /** Source of an effect summary. */
    public enum Derivation {
        DECLARED,
        STATIC_ANALYSIS,
        TRANSITIVE_SUMMARY
    }

    /**
     * One effect that is only reachable under a named, reviewable condition.
     *
     * @param condition stable condition or route identifier, not executable source code
     * @param mode effect mode under the condition
     * @param readSet conditional read set
     * @param writeSet conditional write set
     */
    public record ConditionalEffect(
            String condition,
            Mode mode,
            List<String> readSet,
            List<String> writeSet
    ) {
        /** Normalizes patterns and rejects an unspecified conditional effect. */
        public ConditionalEffect {
            condition = required(condition, "condition");
            mode = mode == null ? Mode.UNKNOWN : mode;
            readSet = normalizedPatterns(readSet);
            writeSet = normalizedPatterns(writeSet);
            validateSets(mode, readSet, writeSet);
        }
    }

    /**
     * Normalizes deterministic collection order and enforces conservative mode/set consistency.
     */
    public EffectContract {
        schemaVersion = version(schemaVersion);
        mode = mode == null ? Mode.UNKNOWN : mode;
        readSet = normalizedPatterns(readSet);
        writeSet = normalizedPatterns(writeSet);
        conditionalEffects = conditionalEffects == null ? List.of() : conditionalEffects.stream()
                .sorted(java.util.Comparator.comparing(ConditionalEffect::condition)).toList();
        riskLevel = riskLevel == null ? RiskLevel.CRITICAL : riskLevel;
        derivation = derivation == null ? Derivation.DECLARED : derivation;
        unresolvedReasons = normalizedPatterns(unresolvedReasons);
        validateSets(mode, readSet, writeSet);
        if (mode == Mode.UNKNOWN && unresolvedReasons.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN effect requires an unresolved reason");
        }
        if (mode != Mode.UNKNOWN && !unresolvedReasons.isEmpty()) {
            throw new IllegalArgumentException("resolved effect must not retain unresolved reasons");
        }
        if ((mode == Mode.EXTERNAL_MUTATION || mode == Mode.MIXED || mode == Mode.UNKNOWN)
                && riskLevel == RiskLevel.LOW) {
            throw new IllegalArgumentException("external, mixed, and unknown effects cannot be LOW risk");
        }
    }

    /** @return a declared, low-risk read-only contract */
    public static EffectContract readOnly(List<String> readSet) {
        return new EffectContract("", Mode.READ_ONLY, readSet, List.of(), List.of(), null,
                false, RiskLevel.LOW, Derivation.DECLARED, List.of());
    }

    /** @return a fail-closed effect that records why analysis was incomplete */
    public static EffectContract unknown(String reason) {
        return new EffectContract("", Mode.UNKNOWN, List.of(), List.of(), List.of(), null,
                true, RiskLevel.CRITICAL, Derivation.STATIC_ANALYSIS, List.of(required(reason, "reason")));
    }

    /** @return whether the capability may mutate either a virtual or external world */
    public boolean mutation() {
        return mode == Mode.VIRTUAL_MUTATION || mode == Mode.EXTERNAL_MUTATION || mode == Mode.MIXED;
    }

    private static void validateSets(Mode mode, List<String> reads, List<String> writes) {
        if (mode == Mode.READ_ONLY && !writes.isEmpty()) {
            throw new IllegalArgumentException("READ_ONLY effect must not declare writeSet");
        }
        if ((mode == Mode.VIRTUAL_MUTATION || mode == Mode.EXTERNAL_MUTATION) && writes.isEmpty()) {
            throw new IllegalArgumentException(mode + " effect requires writeSet");
        }
        if (mode == Mode.MIXED && (reads.isEmpty() || writes.isEmpty())) {
            throw new IllegalArgumentException("MIXED effect requires both readSet and writeSet");
        }
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + normalized);
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static List<String> normalizedPatterns(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
    }
}
