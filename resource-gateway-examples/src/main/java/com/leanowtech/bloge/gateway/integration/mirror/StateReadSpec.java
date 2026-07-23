package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable query-to-session-state lowering contract for one external read capability.
 *
 * <p>The specification prevents the runtime from guessing entity types or request fields from
 * operator names. It binds one exact capability and state model to one declared business key,
 * evaluates bounded request-only key components, and projects a live entity through the same
 * closed expression vocabulary used by virtual writes. The sole entity alias available to the
 * response projection is {@value #RESULT_ALIAS}.</p>
 *
 * @param schemaVersion state-read-spec protocol version
 * @param specId stable specification identity inside its enterprise scope
 * @param revision positive immutable revision
 * @param fingerprint canonical artifact fingerprint with this field blanked
 * @param scope exact enterprise namespace
 * @param targetCapabilityRef exact external read capability
 * @param stateModelRef exact state model
 * @param entityType state-model entity type returned by the capability
 * @param businessKeyName exact state-model business key
 * @param keyComponents ordered bounded expressions evaluated only from invocation input
 * @param responseProjection bounded projection over invocation input and the {@code result} entity
 * @param provenance owner approval and source lineage
 * @param lifecycle governed artifact lifecycle
 * @param createdAt immutable creation time
 */
public record StateReadSpec(
        String schemaVersion,
        String specId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef stateModelRef,
        String entityType,
        String businessKeyName,
        List<BoundedStateExpression> keyComponents,
        BoundedStateExpression responseProjection,
        ArtifactProvenance provenance,
        CapabilitySnapshot.Lifecycle lifecycle,
        Instant createdAt
) {
    /** Current state-read-spec wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.stateReadSpec.v1";
    /** The only entity alias visible to a read response projection. */
    public static final String RESULT_ALIAS = "result";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Normalizes immutable collections and validates exact artifact coordinates. */
    public StateReadSpec {
        schemaVersion = version(schemaVersion);
        specId = identifier(specId, "specId");
        if (revision < 1) {
            throw new IllegalArgumentException("state read spec revision must be positive");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "state read spec fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        targetCapabilityRef = exactKind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        stateModelRef = exactKind(
                stateModelRef, "STATE_MODEL", "stateModelRef");
        entityType = identifier(entityType, "entityType");
        businessKeyName = identifier(businessKeyName, "businessKeyName");
        keyComponents = keyComponents == null ? List.of() : keyComponents.stream()
                .map(value -> Objects.requireNonNull(value, "keyComponent"))
                .toList();
        if (keyComponents.isEmpty() || keyComponents.size() > 16) {
            throw new IllegalArgumentException(
                    "state read spec requires between 1 and 16 key components");
        }
        responseProjection = Objects.requireNonNull(
                responseProjection, "responseProjection");
        provenance = Objects.requireNonNull(provenance, "provenance");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** @return a copy carrying a replacement canonical fingerprint */
    public StateReadSpec withFingerprint(String value) {
        return new StateReadSpec(
                schemaVersion, specId, revision, value, scope,
                targetCapabilityRef, stateModelRef, entityType,
                businessKeyName, keyComponents, responseProjection,
                provenance, lifecycle, createdAt);
    }

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef ref, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(ref, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String normalized = MirrorStateProtocolSupport.required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported state read spec schemaVersion");
        }
        return normalized;
    }
}
