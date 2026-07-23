package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, content-addressed schema of the entity world owned by a mirror session.
 *
 * <p>A state model names entity types and exact unique business-key projections. It does not
 * contain executable storage code. State mutation is admitted only through separately sealed
 * {@link WriteEffectSpec} artifacts that reference this exact revision.</p>
 *
 * @param schemaVersion state-model protocol version
 * @param stateModelId stable model identity inside its enterprise scope
 * @param revision positive immutable revision
 * @param fingerprint canonical fingerprint with this field blanked
 * @param scope exact enterprise namespace
 * @param entityTypes bounded entity definitions
 * @param invariants cross-entity predicates evaluated after every candidate mutation
 * @param provenance owner and lineage facts
 * @param lifecycle governed artifact lifecycle
 * @param createdAt immutable creation time
 */
public record StateModel(
        String schemaVersion,
        String stateModelId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        List<EntityType> entityTypes,
        List<Invariant> invariants,
        ArtifactProvenance provenance,
        CapabilitySnapshot.Lifecycle lifecycle,
        Instant createdAt
) {
    /** Current state-model wire version. */
    public static final String SCHEMA_VERSION = "resourceGateway.stateModel.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");

    /** Normalizes deterministic collections and rejects ambiguous model names. */
    public StateModel {
        schemaVersion = version(schemaVersion);
        stateModelId = identifier(stateModelId, "stateModelId");
        if (revision < 1) {
            throw new IllegalArgumentException("state model revision must be positive");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        entityTypes = entityTypes == null ? List.of() : entityTypes.stream()
                .sorted(Comparator.comparing(EntityType::entityType)).toList();
        if (entityTypes.isEmpty() || entityTypes.size() > 256) {
            throw new IllegalArgumentException(
                    "state model requires between 1 and 256 entity types");
        }
        unique(entityTypes.stream().map(EntityType::entityType).toList(), "entity type");
        Set<String> businessKeys = new HashSet<>();
        for (EntityType entityType : entityTypes) {
            for (BusinessKeyDefinition key : entityType.businessKeys()) {
                if (!businessKeys.add(key.name())) {
                    throw new IllegalArgumentException(
                            "business key names must be unique across a state model");
                }
            }
        }
        invariants = invariants == null ? List.of() : invariants.stream()
                .sorted(Comparator.comparing(Invariant::invariantId)).toList();
        if (invariants.size() > 256) {
            throw new IllegalArgumentException("state model exceeds maximum invariant count");
        }
        unique(invariants.stream().map(Invariant::invariantId).toList(), "invariant");
        provenance = Objects.requireNonNull(provenance, "provenance");
        lifecycle = lifecycle == null ? CapabilitySnapshot.Lifecycle.DRAFT : lifecycle;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * One JSON entity type and its exact unique business-key definitions.
     *
     * @param entityType stable type name
     * @param schema entity payload JSON Schema
     * @param businessKeys business keys that must be indexed for every live entity
     */
    public record EntityType(
            String entityType,
            SchemaEnvelope schema,
            List<BusinessKeyDefinition> businessKeys
    ) {
        /** Normalizes one entity definition. */
        public EntityType {
            entityType = identifier(entityType, "entityType");
            schema = Objects.requireNonNull(schema, "schema");
            businessKeys = businessKeys == null ? List.of() : businessKeys.stream()
                    .sorted(Comparator.comparing(BusinessKeyDefinition::name)).toList();
            if (businessKeys.isEmpty() || businessKeys.size() > 32) {
                throw new IllegalArgumentException(
                        "entity type requires between 1 and 32 business keys");
            }
            unique(businessKeys.stream().map(BusinessKeyDefinition::name).toList(),
                    "business key");
        }
    }

    /**
     * Unique, ordered composite business key projected from entity JSON fields.
     *
     * @param name model-wide unique key name
     * @param fieldPaths ordered non-root JSON Pointers
     */
    public record BusinessKeyDefinition(String name, List<String> fieldPaths) {
        /** Validates bounded unique field paths. */
        public BusinessKeyDefinition {
            name = identifier(name, "business key name");
            fieldPaths = fieldPaths == null ? List.of() : fieldPaths.stream()
                    .map(path -> MirrorStateProtocolSupport.nonRootPointer(
                            path, "business key field path"))
                    .toList();
            if (fieldPaths.isEmpty() || fieldPaths.size() > 16) {
                throw new IllegalArgumentException(
                        "business key requires between 1 and 16 field paths");
            }
            unique(fieldPaths, "business key field path");
        }
    }

    /**
     * Owner-defined predicate that must hold for every committed candidate world.
     *
     * @param invariantId stable invariant identity
     * @param predicate bounded boolean expression
     * @param errorCode stable business failure emitted when false
     */
    public record Invariant(
            String invariantId,
            BoundedStateExpression predicate,
            String errorCode
    ) {
        /** Validates one state invariant. */
        public Invariant {
            invariantId = identifier(invariantId, "invariantId");
            predicate = Objects.requireNonNull(predicate, "predicate");
            errorCode = MirrorStateProtocolSupport.errorCode(errorCode);
        }
    }

    /** @return a copy carrying a replacement fingerprint */
    public StateModel withFingerprint(String value) {
        return new StateModel(schemaVersion, stateModelId, revision, value, scope, entityTypes,
                invariants, provenance, lifecycle, createdAt);
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
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " values must be unique");
        }
    }
}
