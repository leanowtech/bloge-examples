package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, owner-governed virtual transaction for one write capability.
 *
 * <p>One effect may mutate several entities, but the ordered mutation list commits as one
 * serializable transaction. Each value, precondition, business key, and response is expressed
 * through {@link BoundedStateExpression}; no effect can call a real resource or dynamically
 * resolve executable code.</p>
 *
 * @param schemaVersion write-effect protocol version
 * @param specId stable effect identity
 * @param revision positive immutable revision
 * @param fingerprint canonical fingerprint with this field blanked
 * @param scope exact enterprise namespace
 * @param targetCapabilityRef exact virtual write capability
 * @param stateModelRef exact state model
 * @param mutations ordered atomic mutation set
 * @param responseProjection deterministic command response
 * @param idempotency keyed command contract
 * @param provenance owner and lineage facts
 * @param lifecycle governed artifact lifecycle
 * @param createdAt immutable creation time
 */
public record WriteEffectSpec(
        String schemaVersion,
        String specId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef stateModelRef,
        List<Mutation> mutations,
        BoundedStateExpression responseProjection,
        Idempotency idempotency,
        ArtifactProvenance provenance,
        CapabilitySnapshot.Lifecycle lifecycle,
        Instant createdAt
) {
    /** Current write-effect wire version. */
    public static final String SCHEMA_VERSION = "resourceGateway.writeEffectSpec.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");

    /** Virtual entity operations supported by the v1 transaction engine. */
    public enum Operation {
        CREATE,
        UPDATE,
        DELETE,
        UPSERT
    }

    /** Normalizes immutable fields while preserving mutation order. */
    public WriteEffectSpec {
        schemaVersion = version(schemaVersion);
        specId = identifier(specId, "specId");
        if (revision < 1) {
            throw new IllegalArgumentException("write-effect revision must be positive");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        targetCapabilityRef = kind(targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        stateModelRef = kind(stateModelRef, "STATE_MODEL", "stateModelRef");
        mutations = mutations == null ? List.of() : List.copyOf(mutations);
        if (mutations.isEmpty() || mutations.size() > 64) {
            throw new IllegalArgumentException(
                    "write effect requires between 1 and 64 mutations");
        }
        unique(mutations.stream().map(Mutation::mutationId).toList(), "mutation");
        responseProjection = Objects.requireNonNull(responseProjection, "responseProjection");
        idempotency = Objects.requireNonNull(idempotency, "idempotency");
        provenance = Objects.requireNonNull(provenance, "provenance");
        lifecycle = lifecycle == null ? CapabilitySnapshot.Lifecycle.DRAFT : lifecycle;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * One ordered entity mutation inside the atomic transaction.
     *
     * @param mutationId stable alias used by later expressions
     * @param operation entity operation
     * @param entityType state-model entity type
     * @param identity expression producing a non-blank entity id
     * @param baselineReadCapabilityRef exact allowed baseline read, or {@code null}
     * @param preconditions predicates evaluated against the pre-mutation entity
     * @param fieldEffects non-overlapping JSON field assignments
     * @param businessKeys complete key projections for the resulting live entity
     */
    public record Mutation(
            String mutationId,
            Operation operation,
            String entityType,
            BoundedStateExpression identity,
            MirrorArtifactRef baselineReadCapabilityRef,
            List<Precondition> preconditions,
            List<FieldEffect> fieldEffects,
            List<BusinessKeyRule> businessKeys
    ) {
        /** Validates operation-specific mutation shape. */
        public Mutation {
            mutationId = identifier(mutationId, "mutationId");
            operation = Objects.requireNonNull(operation, "operation");
            entityType = identifier(entityType, "entityType");
            identity = Objects.requireNonNull(identity, "identity");
            if (baselineReadCapabilityRef != null
                    && !"CAPABILITY".equals(baselineReadCapabilityRef.kind())) {
                throw new IllegalArgumentException(
                        "baselineReadCapabilityRef must reference a CAPABILITY");
            }
            if (operation == Operation.CREATE && baselineReadCapabilityRef != null) {
                throw new IllegalArgumentException(
                        "CREATE must not declare a baseline read capability");
            }
            preconditions = preconditions == null ? List.of() : preconditions.stream()
                    .sorted(Comparator.comparing(Precondition::preconditionId)).toList();
            if (preconditions.size() > 64) {
                throw new IllegalArgumentException("mutation exceeds maximum precondition count");
            }
            unique(preconditions.stream().map(Precondition::preconditionId).toList(),
                    "precondition");
            fieldEffects = fieldEffects == null ? List.of() : fieldEffects.stream()
                    .sorted(Comparator.comparing(FieldEffect::path)).toList();
            if (fieldEffects.size() > 256) {
                throw new IllegalArgumentException("mutation exceeds maximum field-effect count");
            }
            validateFieldPaths(fieldEffects);
            if (operation == Operation.DELETE && !fieldEffects.isEmpty()) {
                throw new IllegalArgumentException("DELETE must not declare field effects");
            }
            if (operation != Operation.DELETE && fieldEffects.isEmpty()) {
                throw new IllegalArgumentException(operation + " requires field effects");
            }
            businessKeys = businessKeys == null ? List.of() : businessKeys.stream()
                    .sorted(Comparator.comparing(BusinessKeyRule::name)).toList();
            if (operation == Operation.DELETE && !businessKeys.isEmpty()) {
                throw new IllegalArgumentException("DELETE must not declare business-key rules");
            }
            if (operation != Operation.DELETE && businessKeys.isEmpty()) {
                throw new IllegalArgumentException(operation + " requires business-key rules");
            }
            unique(businessKeys.stream().map(BusinessKeyRule::name).toList(), "business key");
        }
    }

    /**
     * Boolean guard evaluated before any field assignment for one mutation.
     *
     * @param preconditionId stable guard identity
     * @param predicate bounded boolean expression
     * @param errorCode stable business failure code
     */
    public record Precondition(
            String preconditionId,
            BoundedStateExpression predicate,
            String errorCode
    ) {
        /** Validates one precondition. */
        public Precondition {
            preconditionId = identifier(preconditionId, "preconditionId");
            predicate = Objects.requireNonNull(predicate, "predicate");
            errorCode = MirrorStateProtocolSupport.errorCode(errorCode);
        }
    }

    /**
     * Deterministic assignment to one non-root object JSON Pointer.
     *
     * @param path destination JSON Pointer
     * @param value bounded value expression
     */
    public record FieldEffect(String path, BoundedStateExpression value) {
        /** Validates one assignment. */
        public FieldEffect {
            path = MirrorStateProtocolSupport.nonRootPointer(path, "field effect path");
            value = Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Complete composite business-key projection for the resulting entity.
     *
     * @param name exact key definition name in the state model
     * @param components ordered scalar expressions
     */
    public record BusinessKeyRule(
            String name,
            List<BoundedStateExpression> components
    ) {
        /** Validates one bounded key projection. */
        public BusinessKeyRule {
            name = identifier(name, "business key name");
            components = components == null ? List.of() : List.copyOf(components);
            if (components.isEmpty() || components.size() > 16) {
                throw new IllegalArgumentException(
                        "business key rule requires between 1 and 16 components");
            }
        }
    }

    /**
     * Mandatory exact-replay semantics for v1 state commands.
     *
     * @param keyPath request JSON Pointer producing the idempotency identity
     * @param replayReturnsOriginal must be true in v1
     */
    public record Idempotency(String keyPath, boolean replayReturnsOriginal) {
        /** Enforces keyed exact replay. */
        public Idempotency {
            keyPath = MirrorStateProtocolSupport.nonRootPointer(keyPath, "idempotency keyPath");
            if (!replayReturnsOriginal) {
                throw new IllegalArgumentException(
                        "stateful v1 idempotency must return the original receipt");
            }
        }
    }

    /** @return a copy carrying a replacement fingerprint */
    public WriteEffectSpec withFingerprint(String value) {
        return new WriteEffectSpec(schemaVersion, specId, revision, value, scope,
                targetCapabilityRef, stateModelRef, mutations, responseProjection,
                idempotency, provenance, lifecycle, createdAt);
    }

    /** @return a copy carrying a replacement enterprise scope */
    public WriteEffectSpec withScope(CapabilitySnapshot.Scope value) {
        return new WriteEffectSpec(schemaVersion, specId, revision, fingerprint, value,
                targetCapabilityRef, stateModelRef, mutations, responseProjection,
                idempotency, provenance, lifecycle, createdAt);
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value, String expected, String field) {
        MirrorArtifactRef ref = Objects.requireNonNull(value, field);
        if (!expected.equals(ref.kind())) {
            throw new IllegalArgumentException(field + " must reference " + expected);
        }
        return ref;
    }

    private static void validateFieldPaths(List<FieldEffect> fields) {
        for (int left = 0; left < fields.size(); left++) {
            String leftPath = fields.get(left).path();
            for (int right = left + 1; right < fields.size(); right++) {
                String rightPath = fields.get(right).path();
                if (rightPath.equals(leftPath) || rightPath.startsWith(leftPath + "/")) {
                    throw new IllegalArgumentException(
                            "field effect paths must be unique and non-overlapping");
                }
            }
        }
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + normalized);
        }
        return normalized;
    }

    private static String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static void unique(List<String> values, String field) {
        Set<String> unique = new HashSet<>(values);
        if (unique.size() != values.size()) {
            throw new IllegalArgumentException(field + " values must be unique");
        }
    }
}
