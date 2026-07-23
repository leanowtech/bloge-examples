package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical sealing and fail-closed semantic verification for {@link WriteEffectSpec}. */
public final class WriteEffectSpecIntegrity {
    /** Maximum canonical size of one write-effect artifact. */
    public static final int MAXIMUM_CANONICAL_BYTES = 4 * 1024 * 1024;

    private WriteEffectSpecIntegrity() {
    }

    /**
     * Validates and content-addresses a write effect.
     *
     * @param mapper canonical protocol mapper
     * @param effect unsealed or resealed effect
     * @return sealed immutable effect
     */
    public static WriteEffectSpec seal(ObjectMapper mapper, WriteEffectSpec effect) {
        Objects.requireNonNull(mapper, "mapper");
        validateCommon(Objects.requireNonNull(effect, "effect"));
        WriteEffectSpec material = effect.withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Verifies the effect seal and its exact state-model dependencies.
     *
     * @param mapper canonical protocol mapper
     * @param effect sealed write effect
     * @param stateModel sealed referenced model
     */
    public static void verify(
            ObjectMapper mapper, WriteEffectSpec effect, StateModel stateModel) {
        Objects.requireNonNull(effect, "effect");
        if (effect.fingerprint().isBlank()) {
            throw new IllegalArgumentException("write effect is not sealed");
        }
        if (!effect.fingerprint().equals(seal(mapper, effect).fingerprint())) {
            throw new IllegalArgumentException("write effect fingerprint mismatch");
        }
        StateModelIntegrity.verify(mapper, stateModel);
        if (!effect.stateModelRef().equals(StateModelIntegrity.reference(stateModel))) {
            throw new IllegalArgumentException(
                    "write effect does not reference the supplied state model");
        }
        if (!effect.scope().equals(stateModel.scope())) {
            throw new IllegalArgumentException(
                    "write effect scope must match the state model scope");
        }
        Map<String, StateModel.EntityType> entityTypes = new HashMap<>();
        stateModel.entityTypes().forEach(type -> entityTypes.put(type.entityType(), type));
        Set<String> availableAliases = new HashSet<>();
        for (WriteEffectSpec.Mutation mutation : effect.mutations()) {
            StateModel.EntityType entityType = entityTypes.get(mutation.entityType());
            if (entityType == null) {
                throw new IllegalArgumentException(
                        "write effect references an unknown entity type");
            }
            if (mutation.operation() != WriteEffectSpec.Operation.DELETE) {
                Set<String> expected = new HashSet<>();
                entityType.businessKeys().forEach(key -> expected.add(key.name()));
                Set<String> actual = new HashSet<>();
                mutation.businessKeys().forEach(key -> actual.add(key.name()));
                if (!expected.equals(actual)) {
                    throw new IllegalArgumentException(
                            "write effect business keys must exactly match the state model");
                }
                for (WriteEffectSpec.BusinessKeyRule rule : mutation.businessKeys()) {
                    int expectedComponents = entityType.businessKeys().stream()
                            .filter(key -> key.name().equals(rule.name()))
                            .findFirst().orElseThrow().fieldPaths().size();
                    if (expectedComponents != rule.components().size()) {
                        throw new IllegalArgumentException(
                            "write effect business-key component count does not match the model");
                    }
                }
            }
            validateEntityAliases(mutation.identity(), availableAliases);
            availableAliases.add(mutation.mutationId());
            mutation.preconditions().forEach(precondition ->
                    validateEntityAliases(precondition.predicate(), availableAliases));
            mutation.fieldEffects().forEach(field ->
                    validateEntityAliases(field.value(), availableAliases));
            mutation.businessKeys().forEach(key -> key.components().forEach(component ->
                    validateEntityAliases(component, availableAliases)));
        }
        validateEntityAliases(effect.responseProjection(), availableAliases);
        stateModel.invariants().forEach(invariant ->
                validateEntityAliases(invariant.predicate(), availableAliases));
    }

    /**
     * Returns the exact artifact reference stored by a session state space.
     *
     * @param effect sealed effect
     * @return exact {@code WRITE_EFFECT} reference
     */
    public static MirrorArtifactRef reference(WriteEffectSpec effect) {
        if (effect == null || effect.fingerprint().isBlank()) {
            throw new IllegalArgumentException("write effect must be sealed before reference");
        }
        return new MirrorArtifactRef("WRITE_EFFECT", effect.specId(), effect.revision(),
                effect.fingerprint());
    }

    private static void validateCommon(WriteEffectSpec effect) {
        if (!effect.scope().tenantId().equals(effect.provenance().tenantId())) {
            throw new IllegalArgumentException(
                    "write effect scope tenant must match provenance tenant");
        }
        if (effect.createdAt().isAfter(effect.provenance().approvedAt() == null
                ? effect.createdAt() : effect.provenance().approvedAt())) {
            throw new IllegalArgumentException(
                    "write effect approval cannot predate artifact creation");
        }
        for (WriteEffectSpec.Mutation mutation : effect.mutations()) {
            BoundedStateExpression.validate(mutation.identity());
            mutation.preconditions().forEach(precondition ->
                    BoundedStateExpression.validate(precondition.predicate()));
            mutation.fieldEffects().forEach(field ->
                    BoundedStateExpression.validate(field.value()));
            mutation.businessKeys().forEach(key -> key.components()
                    .forEach(BoundedStateExpression::validate));
        }
        BoundedStateExpression.validate(effect.responseProjection());
    }

    private static void validateEntityAliases(
            BoundedStateExpression root, Set<String> availableAliases) {
        ArrayDeque<BoundedStateExpression> remaining = new ArrayDeque<>();
        remaining.push(root);
        while (!remaining.isEmpty()) {
            BoundedStateExpression expression = remaining.pop();
            if (expression.operator() == BoundedStateExpression.Operator.ENTITY_POINTER
                    && !availableAliases.contains(expression.reference())) {
                throw new IllegalArgumentException(
                        "write effect expression references an unavailable mutation alias");
            }
            expression.arguments().forEach(remaining::push);
            expression.fields().values().forEach(remaining::push);
        }
    }
}
