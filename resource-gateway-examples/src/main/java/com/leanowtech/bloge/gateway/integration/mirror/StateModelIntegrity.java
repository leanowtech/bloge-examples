package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaIntrospection;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical sealing and semantic verification for {@link StateModel}. */
public final class StateModelIntegrity {
    /** Maximum canonical size of one state-model artifact. */
    public static final int MAXIMUM_CANONICAL_BYTES = 4 * 1024 * 1024;

    private StateModelIntegrity() {
    }

    /**
     * Validates and content-addresses a state model.
     *
     * @param mapper canonical protocol mapper
     * @param model unsealed or resealed model
     * @return sealed immutable model
     */
    public static StateModel seal(ObjectMapper mapper, StateModel model) {
        Objects.requireNonNull(mapper, "mapper");
        validate(Objects.requireNonNull(model, "model"));
        StateModel material = model.withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Recomputes and verifies an exact state model.
     *
     * @param mapper canonical protocol mapper
     * @param model sealed model
     */
    public static void verify(ObjectMapper mapper, StateModel model) {
        Objects.requireNonNull(model, "model");
        if (model.fingerprint().isBlank()) {
            throw new IllegalArgumentException("state model is not sealed");
        }
        if (!model.fingerprint().equals(seal(mapper, model).fingerprint())) {
            throw new IllegalArgumentException("state model fingerprint mismatch");
        }
    }

    /**
     * Returns the exact artifact reference consumed by capabilities and write effects.
     *
     * @param model sealed model
     * @return exact {@code STATE_MODEL} reference
     */
    public static MirrorArtifactRef reference(StateModel model) {
        if (model == null || model.fingerprint().isBlank()) {
            throw new IllegalArgumentException("state model must be sealed before reference");
        }
        return new MirrorArtifactRef("STATE_MODEL", model.stateModelId(), model.revision(),
                model.fingerprint());
    }

    private static void validate(StateModel model) {
        if (!model.scope().tenantId().equals(model.provenance().tenantId())) {
            throw new IllegalArgumentException(
                    "state model scope tenant must match provenance tenant");
        }
        if (model.createdAt().isAfter(model.provenance().approvedAt() == null
                ? model.createdAt() : model.provenance().approvedAt())) {
            throw new IllegalArgumentException(
                    "state model approval cannot predate artifact creation");
        }
        for (StateModel.EntityType entityType : model.entityTypes()) {
            if (!VisualSchemaValidator.validateEnvelope(
                    entityType.schema(), "/entityTypes/" + entityType.entityType()
                            + "/schema").isEmpty()) {
                throw new IllegalArgumentException(
                        "state model contains an unsupported entity schema");
            }
            validateEntitySchema(entityType);
        }
        for (StateModel.Invariant invariant : model.invariants()) {
            BoundedStateExpression.validate(invariant.predicate());
        }
    }

    private static void validateEntitySchema(StateModel.EntityType entityType) {
        Map<String, Object> schema = entityType.schema().schema();
        if (!"object".equals(VisualSchemaIntrospection.schemaType(schema))
                || !Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            throw new IllegalArgumentException(
                    "state model entity schema must be a closed object");
        }
        for (StateModel.BusinessKeyDefinition key : entityType.businessKeys()) {
            for (String path : key.fieldPaths()) {
                Map<String, Object> valueSchema = schemaAtRequiredPath(schema, path);
                String type = VisualSchemaIntrospection.schemaType(valueSchema);
                if (type.isBlank() || "object".equals(type) || "array".equals(type)
                        || "any".equals(type) || "opaque".equals(type)
                        || "null".equals(type)) {
                    throw new IllegalArgumentException(
                            "state model business-key path must resolve to a required scalar");
                }
            }
        }
    }

    private static Map<String, Object> schemaAtRequiredPath(
            Map<String, Object> root, String path) {
        JsonPointer pointer = JsonPointer.compile(path);
        Map<String, Object> current = root;
        while (!pointer.matches()) {
            String property = pointer.getMatchingProperty();
            if (property == null || !requiredProperties(current).contains(property)) {
                throw new IllegalArgumentException(
                        "state model business-key path must resolve to a required scalar");
            }
            Object nested = VisualSchemaIntrospection.propertiesOf(current).get(property);
            current = VisualSchemaIntrospection.objectSchema(nested);
            if (current == null) {
                throw new IllegalArgumentException(
                        "state model business-key path must resolve to a required scalar");
            }
            pointer = pointer.tail();
        }
        return current;
    }

    private static List<String> requiredProperties(Map<String, Object> schema) {
        Object value = schema.get("required");
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }
}
