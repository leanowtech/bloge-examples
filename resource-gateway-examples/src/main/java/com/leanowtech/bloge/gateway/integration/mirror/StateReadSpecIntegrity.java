package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Canonical sealing and semantic verification for {@link StateReadSpec}. */
public final class StateReadSpecIntegrity {
    /** Maximum canonical size of one state-read specification. */
    public static final int MAXIMUM_CANONICAL_BYTES = 2 * 1024 * 1024;

    private StateReadSpecIntegrity() {
    }

    /**
     * Validates and content-addresses one state-read specification.
     *
     * @param mapper canonical protocol mapper
     * @param spec unsealed or resealed specification
     * @return sealed immutable specification
     */
    public static StateReadSpec seal(ObjectMapper mapper, StateReadSpec spec) {
        Objects.requireNonNull(mapper, "mapper");
        validateExpressions(Objects.requireNonNull(spec, "spec"));
        validateCommon(spec);
        StateReadSpec material = spec.withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Verifies the artifact seal and exact state-model business-key dependency.
     *
     * @param mapper canonical protocol mapper
     * @param spec sealed state-read specification
     * @param stateModel exact referenced state model
     */
    public static void verify(
            ObjectMapper mapper, StateReadSpec spec, StateModel stateModel) {
        Objects.requireNonNull(spec, "spec");
        if (spec.fingerprint().isBlank()
                || !spec.fingerprint().equals(seal(mapper, spec).fingerprint())) {
            throw new IllegalArgumentException(
                    "state read spec fingerprint mismatch");
        }
        StateModelIntegrity.verify(mapper, stateModel);
        if (!spec.stateModelRef().equals(
                StateModelIntegrity.reference(stateModel))) {
            throw new IllegalArgumentException(
                    "state read spec does not reference the supplied state model");
        }
        if (!spec.scope().equals(stateModel.scope())) {
            throw new IllegalArgumentException(
                    "state read spec scope must match the state model scope");
        }
        StateModel.EntityType entity = stateModel.entityTypes().stream()
                .filter(value -> value.entityType().equals(spec.entityType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "state read spec references an unknown entity type"));
        StateModel.BusinessKeyDefinition key = entity.businessKeys().stream()
                .filter(value -> value.name().equals(spec.businessKeyName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "state read spec references an unknown business key"));
        if (key.fieldPaths().size() != spec.keyComponents().size()) {
            throw new IllegalArgumentException(
                    "state read spec business key component count does not match the model");
        }
    }

    /**
     * Returns the exact artifact reference carried by runtime provenance.
     *
     * @param spec sealed state-read specification
     * @return exact {@code STATE_READ_SPEC} reference
     */
    public static MirrorArtifactRef reference(StateReadSpec spec) {
        if (spec == null || spec.fingerprint().isBlank()) {
            throw new IllegalArgumentException(
                    "state read spec must be sealed before reference");
        }
        return new MirrorArtifactRef(
                "STATE_READ_SPEC", spec.specId(), spec.revision(),
                spec.fingerprint());
    }

    private static void validateCommon(StateReadSpec spec) {
        if (!spec.scope().tenantId().equals(spec.provenance().tenantId())) {
            throw new IllegalArgumentException(
                    "state read spec scope tenant must match provenance tenant");
        }
        if (spec.createdAt().isAfter(
                spec.provenance().approvedAt() == null
                        ? spec.createdAt() : spec.provenance().approvedAt())) {
            throw new IllegalArgumentException(
                    "state read spec approval cannot predate artifact creation");
        }
    }

    private static void validateExpressions(StateReadSpec spec) {
        for (BoundedStateExpression component : spec.keyComponents()) {
            BoundedStateExpression.validate(component);
            walk(component, expression -> {
                if (expression.operator()
                        != BoundedStateExpression.Operator.LITERAL
                        && expression.operator()
                        != BoundedStateExpression.Operator.INPUT_POINTER
                        && expression.operator()
                        != BoundedStateExpression.Operator.CONCAT) {
                    throw new IllegalArgumentException(
                            "state read lookup expressions may use only input, literal, and concat");
                }
            });
        }
        BoundedStateExpression.validate(spec.responseProjection());
        walk(spec.responseProjection(), expression -> {
            if (expression.operator()
                    == BoundedStateExpression.Operator.DETERMINISTIC_ID
                    || expression.operator()
                    == BoundedStateExpression.Operator.SEQUENCE) {
                throw new IllegalArgumentException(
                        "state read response projection cannot allocate identity or sequence");
            }
            if (expression.operator()
                    == BoundedStateExpression.Operator.ENTITY_POINTER
                    && !StateReadSpec.RESULT_ALIAS.equals(expression.reference())) {
                throw new IllegalArgumentException(
                        "state read response projection may reference only the result entity");
            }
        });
    }

    private static void walk(
            BoundedStateExpression root,
            java.util.function.Consumer<BoundedStateExpression> visitor) {
        ArrayDeque<BoundedStateExpression> remaining = new ArrayDeque<>();
        remaining.push(root);
        while (!remaining.isEmpty()) {
            BoundedStateExpression current = remaining.pop();
            visitor.accept(current);
            List<BoundedStateExpression> arguments = current.arguments();
            for (int index = arguments.size() - 1; index >= 0; index--) {
                remaining.push(arguments.get(index));
            }
            current.fields().values().forEach(remaining::push);
        }
    }
}
