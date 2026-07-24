package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free exact recovery fence for one immutable mirror Session state head.
 *
 * <p>The checkpoint pins the durable data-plane generation, complete executable dependency
 * closure, state revision, logical time, and all aggregate fingerprints. It intentionally carries
 * no entity, command, request, response, credential, lease, or encryption-key value. Recovery
 * therefore requires the original encrypted data plane to remain available.</p>
 *
 * @param schemaVersion checkpoint protocol version
 * @param checkpointId stable checkpoint identity
 * @param scope exact authenticated enterprise namespace
 * @param sessionId exact Session identity
 * @param storeGeneration immutable durable data-plane generation
 * @param planFingerprint exact mirror plan generation
 * @param stateModelRef exact state model
 * @param stateReadRefs exact admitted state-read specifications
 * @param writeEffectRefs exact admitted virtual write effects
 * @param stateRevision exact committed Session revision
 * @param logicalClock exact deterministic business time
 * @param worldFingerprint exact current business-world fingerprint
 * @param stateFingerprint exact state-and-journal fingerprint
 * @param payloadFingerprint exact encrypted aggregate plaintext fingerprint
 * @param descriptorFingerprint exact payload-free descriptor fingerprint
 * @param sessionCreatedAt durable Session creation time
 * @param sessionUpdatedAt durable state-head update time
 * @param sessionExpiresAt hard Session expiry
 * @param checkpointedAt server checkpoint creation time
 * @param fingerprint canonical checkpoint fingerprint
 */
public record MirrorSessionCheckpoint(
        String schemaVersion,
        String checkpointId,
        CapabilitySnapshot.Scope scope,
        String sessionId,
        MirrorSessionStoreGeneration storeGeneration,
        String planFingerprint,
        MirrorArtifactRef stateModelRef,
        List<MirrorArtifactRef> stateReadRefs,
        List<MirrorArtifactRef> writeEffectRefs,
        long stateRevision,
        Instant logicalClock,
        String worldFingerprint,
        String stateFingerprint,
        String payloadFingerprint,
        String descriptorFingerprint,
        Instant sessionCreatedAt,
        Instant sessionUpdatedAt,
        Instant sessionExpiresAt,
        Instant checkpointedAt,
        String fingerprint
) {
    /** Current signed Session checkpoint protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionCheckpoint.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:-]{0,511}");

    /** Normalizes deterministic dependency ordering and validates temporal closure. */
    public MirrorSessionCheckpoint {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror Session checkpoint schemaVersion");
        }
        checkpointId = identifier(checkpointId, "checkpointId");
        scope = Objects.requireNonNull(scope, "scope");
        sessionId = identifier(sessionId, "sessionId");
        storeGeneration = Objects.requireNonNull(
                storeGeneration, "storeGeneration");
        planFingerprint = MirrorStateProtocolSupport.fingerprint(
                planFingerprint, "planFingerprint");
        stateModelRef = exactKind(
                stateModelRef, "STATE_MODEL", "stateModelRef");
        stateReadRefs = refs(
                stateReadRefs, "STATE_READ_SPEC", "stateReadRefs", true);
        writeEffectRefs = refs(
                writeEffectRefs, "WRITE_EFFECT", "writeEffectRefs", false);
        if (stateRevision < 0) {
            throw new IllegalArgumentException(
                    "checkpoint stateRevision must not be negative");
        }
        logicalClock = Objects.requireNonNull(logicalClock, "logicalClock");
        worldFingerprint = MirrorStateProtocolSupport.fingerprint(
                worldFingerprint, "worldFingerprint");
        stateFingerprint = MirrorStateProtocolSupport.fingerprint(
                stateFingerprint, "stateFingerprint");
        payloadFingerprint = MirrorStateProtocolSupport.fingerprint(
                payloadFingerprint, "payloadFingerprint");
        descriptorFingerprint = MirrorStateProtocolSupport.fingerprint(
                descriptorFingerprint, "descriptorFingerprint");
        sessionCreatedAt = Objects.requireNonNull(
                sessionCreatedAt, "sessionCreatedAt");
        sessionUpdatedAt = Objects.requireNonNull(
                sessionUpdatedAt, "sessionUpdatedAt");
        sessionExpiresAt = Objects.requireNonNull(
                sessionExpiresAt, "sessionExpiresAt");
        checkpointedAt = Objects.requireNonNull(
                checkpointedAt, "checkpointedAt");
        if (sessionUpdatedAt.isBefore(sessionCreatedAt)
                || checkpointedAt.isBefore(sessionUpdatedAt)
                || !sessionExpiresAt.isAfter(checkpointedAt)) {
            throw new IllegalArgumentException(
                    "checkpoint times do not describe one active Session head");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "checkpoint fingerprint");
    }

    /**
     * Creates a copy carrying a replacement canonical fingerprint.
     *
     * @return checkpoint copy with the supplied fingerprint
     */
    public MirrorSessionCheckpoint withFingerprint(String value) {
        return new MirrorSessionCheckpoint(
                schemaVersion, checkpointId, scope, sessionId,
                storeGeneration, planFingerprint, stateModelRef,
                stateReadRefs, writeEffectRefs, stateRevision, logicalClock,
                worldFingerprint, stateFingerprint, payloadFingerprint,
                descriptorFingerprint, sessionCreatedAt, sessionUpdatedAt,
                sessionExpiresAt, checkpointedAt, value);
    }

    /** Keeps dependency and state fingerprint closures out of generic logs. */
    @Override
    public String toString() {
        return "MirrorSessionCheckpoint[checkpointId=" + checkpointId
                + ", sessionId=" + sessionId
                + ", stateRevision=" + stateRevision
                + ", checkpointedAt=" + checkpointedAt + "]";
    }

    private static List<MirrorArtifactRef> refs(
            List<MirrorArtifactRef> values,
            String kind,
            String field,
            boolean emptyAllowed) {
        List<MirrorArtifactRef> provided = values == null
                ? List.of() : values.stream()
                .map(ref -> exactKind(ref, kind, field))
                .toList();
        Comparator<MirrorArtifactRef> order =
                Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision);
        List<MirrorArtifactRef> canonical = provided.stream()
                .sorted(order).toList();
        boolean duplicateCoordinate = false;
        for (int index = 1; index < canonical.size(); index++) {
            if (order.compare(
                    canonical.get(index - 1), canonical.get(index)) == 0) {
                duplicateCoordinate = true;
                break;
            }
        }
        if ((!emptyAllowed && provided.isEmpty())
                || provided.size() > 256
                || duplicateCoordinate
                || !provided.equals(canonical)) {
            throw new IllegalArgumentException(
                    field + " has an invalid dependency closure");
        }
        return List.copyOf(provided);
    }

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef ref = Objects.requireNonNull(value, field);
        if (!kind.equals(ref.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return ref;
    }

    private static String identifier(String value, String field) {
        String normalized = MirrorStateProtocolSupport.required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " contains unsupported characters");
        }
        return normalized;
    }
}
