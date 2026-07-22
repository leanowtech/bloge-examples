package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.time.Duration;
import java.util.List;

/**
 * Formal request, response, failure, effect, security, and service-level contract of a capability.
 *
 * <p>The contract intentionally keeps behavior beyond input/output schema. A graph that returns the
 * right shape but has an unknown write effect, secret policy, idempotency rule, or runtime budget is
 * not safe to project as an executable business capability.</p>
 *
 * @param schemaVersion capability-contract protocol version
 * @param inputSchema accepted request/context shape
 * @param outputSchema externally observable successful result shape
 * @param errorModel normalized error alternatives
 * @param effect conservative transitive effect contract
 * @param determinism runtime determinism class
 * @param idempotency idempotency contract
 * @param stateModelRef exact state model used by virtual mutations, when applicable
 * @param compatibility compatibility policy for future revisions
 * @param security data and secret handling contract
 * @param slo service-level and execution-budget contract
 */
public record CapabilityContract(
        String schemaVersion,
        SchemaEnvelope inputSchema,
        SchemaEnvelope outputSchema,
        List<ErrorContract> errorModel,
        EffectContract effect,
        Determinism determinism,
        IdempotencyContract idempotency,
        MirrorArtifactRef stateModelRef,
        CompatibilityPolicy compatibility,
        SecurityContract security,
        SloContract slo
) {
    /** Current capability-contract protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityContract.v1";

    /** Runtime sources of non-determinism visible to mirror planning. */
    public enum Determinism {
        DETERMINISTIC,
        CONTROLLED_NONDETERMINISTIC,
        NONDETERMINISTIC
    }

    /**
     * Normalizes optional contract components and validates state/effect consistency.
     */
    public CapabilityContract {
        schemaVersion = version(schemaVersion);
        inputSchema = inputSchema == null ? SchemaEnvelope.opaque() : inputSchema;
        outputSchema = outputSchema == null ? SchemaEnvelope.opaque() : outputSchema;
        errorModel = errorModel == null ? List.of() : errorModel.stream()
                .sorted(java.util.Comparator.comparing(ErrorContract::code)).toList();
        effect = effect == null ? EffectContract.unknown("effect contract is absent") : effect;
        determinism = determinism == null ? Determinism.NONDETERMINISTIC : determinism;
        idempotency = idempotency == null ? IdempotencyContract.unknown() : idempotency;
        compatibility = compatibility == null ? CompatibilityPolicy.conservative() : compatibility;
        security = security == null ? SecurityContract.restricted() : security;
        slo = slo == null ? SloContract.unspecified() : slo;
        if (effect.mode() == EffectContract.Mode.VIRTUAL_MUTATION && stateModelRef == null) {
            throw new IllegalArgumentException("VIRTUAL_MUTATION requires stateModelRef");
        }
        if (effect.mode() != EffectContract.Mode.VIRTUAL_MUTATION
                && effect.mode() != EffectContract.Mode.MIXED && stateModelRef != null) {
            throw new IllegalArgumentException("stateModelRef is only valid for virtual or mixed mutation");
        }
    }

    /**
     * One stable error alternative exposed to graph authors and assertion tooling.
     *
     * @param code stable business/platform error code
     * @param category normalized category
     * @param retryable whether retry may succeed without changing input
     * @param schema structured error payload shape
     */
    public record ErrorContract(String code, ErrorCategory category, boolean retryable, SchemaEnvelope schema) {
        /** Normalizes an error contract. */
        public ErrorContract {
            code = required(code, "error code");
            category = category == null ? ErrorCategory.UNKNOWN : category;
            schema = schema == null ? SchemaEnvelope.opaque() : schema;
        }
    }

    /** Stable failure categories used by scenario and retry policy. */
    public enum ErrorCategory {
        VALIDATION,
        NOT_FOUND,
        CONFLICT,
        THROTTLED,
        TIMEOUT,
        DEPENDENCY,
        AUTHORIZATION,
        UNKNOWN
    }

    /**
     * Idempotency semantics for a capability invocation.
     *
     * @param mode known idempotency mode
     * @param keyPath JSON Pointer or named input source for the idempotency key
     * @param replayReturnsOriginal whether an exact replay returns the original receipt/result
     */
    public record IdempotencyContract(IdempotencyMode mode, String keyPath, boolean replayReturnsOriginal) {
        /** Validates key requirements for keyed idempotency. */
        public IdempotencyContract {
            mode = mode == null ? IdempotencyMode.UNKNOWN : mode;
            keyPath = normalized(keyPath);
            if (mode == IdempotencyMode.KEYED && keyPath.isBlank()) {
                throw new IllegalArgumentException("KEYED idempotency requires keyPath");
            }
            if (mode != IdempotencyMode.KEYED && !keyPath.isBlank()) {
                throw new IllegalArgumentException("keyPath is only valid for KEYED idempotency");
            }
        }

        /** @return conservative unknown idempotency */
        public static IdempotencyContract unknown() {
            return new IdempotencyContract(IdempotencyMode.UNKNOWN, "", false);
        }
    }

    /** Capability idempotency modes. */
    public enum IdempotencyMode {
        DETERMINISTIC,
        IDEMPOTENT,
        KEYED,
        NON_IDEMPOTENT,
        UNKNOWN
    }

    /**
     * Compatibility promise used when comparing capability revisions.
     *
     * @param input backward-compatibility policy for callers
     * @param output forward-compatibility policy for consumers
     * @param errorModel whether adding a documented error is considered breaking
     */
    public record CompatibilityPolicy(
            CompatibilityMode input,
            CompatibilityMode output,
            CompatibilityMode errorModel
    ) {
        /** Defaults missing dimensions to explicit review. */
        public CompatibilityPolicy {
            input = input == null ? CompatibilityMode.REVIEW_REQUIRED : input;
            output = output == null ? CompatibilityMode.REVIEW_REQUIRED : output;
            errorModel = errorModel == null ? CompatibilityMode.REVIEW_REQUIRED : errorModel;
        }

        /** @return conservative policy for a newly projected capability */
        public static CompatibilityPolicy conservative() {
            return new CompatibilityPolicy(CompatibilityMode.REVIEW_REQUIRED,
                    CompatibilityMode.REVIEW_REQUIRED, CompatibilityMode.REVIEW_REQUIRED);
        }
    }

    /** Compatibility decision for one contract dimension. */
    public enum CompatibilityMode {
        BACKWARD_COMPATIBLE,
        FORWARD_COMPATIBLE,
        EXACT,
        REVIEW_REQUIRED
    }

    /**
     * Data and secret handling restrictions inherited by mirror plans.
     *
     * @param classification maximum data classification handled by the capability
     * @param requiresSecrets whether the real runtime requires secret resolution
     * @param allowedRegions region allowlist; empty means unresolved, not globally allowed
     * @param payloadRetentionAllowed whether governed payload retention may be requested
     */
    public record SecurityContract(
            DataClassification classification,
            boolean requiresSecrets,
            List<String> allowedRegions,
            boolean payloadRetentionAllowed
    ) {
        /** Normalizes regions into deterministic order. */
        public SecurityContract {
            classification = classification == null ? DataClassification.RESTRICTED : classification;
            allowedRegions = normalizedList(allowedRegions);
        }

        /** @return a fail-closed security contract for an unresolved projection */
        public static SecurityContract restricted() {
            return new SecurityContract(DataClassification.RESTRICTED, true, List.of(), false);
        }
    }

    /** Supported business-data classifications. */
    public enum DataClassification {
        PUBLIC,
        INTERNAL,
        CONFIDENTIAL,
        RESTRICTED
    }

    /**
     * Runtime budget and ownership contract.
     *
     * @param timeout positive invocation timeout, or {@code null} when not declared
     * @param availabilityTarget monthly availability target in [0,1], or {@code null}
     * @param latencyP95Ms positive p95 latency target, or {@code null}
     * @param owner escalation owner for SLO violations
     */
    public record SloContract(
            @JsonFormat(shape = JsonFormat.Shape.STRING) Duration timeout,
            Double availabilityTarget,
            Long latencyP95Ms,
            String owner
    ) {
        /** Validates bounded SLO values. */
        public SloContract {
            if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (availabilityTarget != null && (!Double.isFinite(availabilityTarget)
                    || availabilityTarget < 0.0d || availabilityTarget > 1.0d)) {
                throw new IllegalArgumentException("availabilityTarget must be in [0,1]");
            }
            if (latencyP95Ms != null && latencyP95Ms < 1) {
                throw new IllegalArgumentException("latencyP95Ms must be positive");
            }
            owner = normalized(owner);
        }

        /** @return a contract that honestly declares no measured SLO */
        public static SloContract unspecified() {
            return new SloContract(null, null, null, "");
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
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalizedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(CapabilityContract::normalized).filter(value -> !value.isEmpty())
                .distinct().sorted().toList();
    }
}
