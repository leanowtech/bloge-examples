package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Payload-free public projection of one isolated stateful mirror session.
 *
 * <p>The descriptor is safe for the control-plane HTTP surface: it exposes exact scope,
 * dependency, revision, lifecycle, and fingerprint facts without exposing entities, command
 * inputs, command responses, encryption material, or lease ownership.</p>
 *
 * @param schemaVersion descriptor wire version
 * @param sessionId stable session identity
 * @param scope exact authenticated enterprise namespace
 * @param planFingerprint exact mirror plan generation
 * @param stateModelRef exact state model
 * @param writeEffectRefs exact admitted write effects
 * @param stateRevision current committed state revision
 * @param status current lifecycle status
 * @param worldFingerprint current business-world fingerprint
 * @param stateFingerprint current complete state-and-journal fingerprint
 * @param createdAt durable creation time
 * @param updatedAt durable latest state transition time
 * @param expiresAt hard session expiry
 * @param destroyedAt explicit destruction or expiry cleanup time, otherwise {@code null}
 * @param fingerprint canonical descriptor fingerprint
 */
public record MirrorSessionDescriptor(
        String schemaVersion,
        String sessionId,
        CapabilitySnapshot.Scope scope,
        String planFingerprint,
        MirrorArtifactRef stateModelRef,
        List<MirrorArtifactRef> writeEffectRefs,
        long stateRevision,
        Status status,
        String worldFingerprint,
        String stateFingerprint,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant destroyedAt,
        String fingerprint
) {
    /** Current payload-free session descriptor version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionDescriptor.v1";

    /** Validates and deterministically orders one descriptor. */
    public MirrorSessionDescriptor {
        schemaVersion = version(schemaVersion);
        sessionId = MirrorStateProtocolSupport.required(sessionId, "sessionId");
        scope = Objects.requireNonNull(scope, "scope");
        planFingerprint = MirrorStateProtocolSupport.fingerprint(
                planFingerprint, "planFingerprint");
        stateModelRef = exactKind(stateModelRef, "STATE_MODEL", "stateModelRef");
        writeEffectRefs = writeEffectRefs == null ? List.of() : writeEffectRefs.stream()
                .map(ref -> exactKind(ref, "WRITE_EFFECT", "writeEffectRef"))
                .sorted(Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision))
                .toList();
        if (writeEffectRefs.isEmpty() || writeEffectRefs.size() > 256
                || writeEffectRefs.stream().distinct().count() != writeEffectRefs.size()) {
            throw new IllegalArgumentException(
                    "descriptor requires between 1 and 256 unique write-effect refs");
        }
        if (stateRevision < 0) {
            throw new IllegalArgumentException("stateRevision must not be negative");
        }
        status = Objects.requireNonNull(status, "status");
        worldFingerprint = MirrorStateProtocolSupport.fingerprint(
                worldFingerprint, "worldFingerprint");
        stateFingerprint = MirrorStateProtocolSupport.fingerprint(
                stateFingerprint, "stateFingerprint");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (updatedAt.isBefore(createdAt) || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                    "descriptor times must satisfy createdAt <= updatedAt and createdAt < expiresAt");
        }
        if (status == Status.ACTIVE && destroyedAt != null) {
            throw new IllegalArgumentException(
                    "active descriptor must not carry destroyedAt");
        }
        if (status != Status.ACTIVE && destroyedAt == null) {
            throw new IllegalArgumentException(
                    "terminal descriptor requires destroyedAt");
        }
        if (destroyedAt != null && destroyedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "destroyedAt must not precede createdAt");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "descriptor fingerprint");
    }

    /** @return a copy carrying a replacement canonical fingerprint */
    public MirrorSessionDescriptor withFingerprint(String value) {
        return new MirrorSessionDescriptor(schemaVersion, sessionId, scope,
                planFingerprint, stateModelRef, writeEffectRefs, stateRevision, status,
                worldFingerprint, stateFingerprint, createdAt, updatedAt, expiresAt,
                destroyedAt, value);
    }

    /** Session lifecycle projected without reading encrypted payloads. */
    public enum Status {
        ACTIVE,
        EXPIRED,
        DESTROYED
    }

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef ref, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(ref, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported mirror session descriptor schemaVersion");
        }
        return normalized;
    }
}
