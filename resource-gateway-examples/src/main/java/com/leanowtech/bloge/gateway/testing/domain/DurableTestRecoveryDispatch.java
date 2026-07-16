package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable worker handoff issued with a durable recovery owner claim or lease heartbeat.
 *
 * <p>The dispatch carries no signal data, fixture value, replay payload, credential, or provider
 * seed. It proves only which authorized closure may execute under one exact owner/epoch/revision
 * fence. A worker must compare the dispatch with the live checkpoint before opening a recovery
 * session; a historical dispatch never grants authority over a newer fence.</p>
 *
 * @param schemaVersion worker dispatch protocol version
 * @param authorization exact payload-free authorization decision
 * @param scope immutable tenant, organization, project, environment, and initiating actor scope
 * @param runId governed durable run identity
 * @param engineExecutionId exact BLOGE execution identity
 * @param ownerId server-selected recovery process owner
 * @param leaseEpoch exact positive owner generation
 * @param revision exact non-negative control revision
 * @param leaseExpiresAt database-authority lease expiry
 * @param checkpointFingerprint exact claimed control checkpoint identity
 * @param dispatchFingerprint canonical fingerprint of every preceding field
 */
public record DurableTestRecoveryDispatch(
        String schemaVersion,
        DurableTestRecoveryAuthorization authorization,
        DurableTestExecutionCheckpoint.Scope scope,
        String runId,
        String engineExecutionId,
        String ownerId,
        long leaseEpoch,
        long revision,
        Instant leaseExpiresAt,
        String checkpointFingerprint,
        String dispatchFingerprint
) {
    /** Current payload-free worker handoff protocol. */
    public static final String SCHEMA_VERSION = "bloge.durableTestRecoveryDispatch.v1";
    private static final int MAX_CANONICAL_BYTES = 128 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects incomplete dispatch identities and impossible fences. */
    public DurableTestRecoveryDispatch {
        schemaVersion = required(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported durable recovery dispatch version");
        }
        authorization = Objects.requireNonNull(authorization, "authorization");
        scope = Objects.requireNonNull(scope, "scope");
        runId = identifier(runId, "runId");
        engineExecutionId = identifier(engineExecutionId, "engineExecutionId");
        ownerId = identifier(ownerId, "ownerId");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        checkpointFingerprint = fingerprint(checkpointFingerprint, "checkpointFingerprint");
        dispatchFingerprint = dispatchFingerprint == null ? "" : dispatchFingerprint.trim();
        if (leaseEpoch <= 0 || revision < 0) {
            throw new IllegalArgumentException("A positive lease epoch and revision are required");
        }
        if (!dispatchFingerprint.isEmpty()
                && !FINGERPRINT.matcher(dispatchFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "dispatchFingerprint must be empty or a canonical SHA-256 fingerprint");
        }
    }

    /**
     * Issues a dispatch for one newly claimed or heartbeat-renewed {@code RESUMING} checkpoint.
     *
     * @param objectMapper canonical protocol mapper
     * @param authorization authorization receipt bound to the pre-claim checkpoint
     * @param checkpoint newly claimed or renewed checkpoint
     * @return sealed payload-free worker handoff
     */
    public static DurableTestRecoveryDispatch issue(
            ObjectMapper objectMapper,
            DurableTestRecoveryAuthorization authorization,
            DurableTestExecutionCheckpoint checkpoint) {
        Objects.requireNonNull(authorization, "authorization").requireValid(objectMapper);
        DurableTestExecutionCheckpoint claimed = Objects.requireNonNull(checkpoint, "checkpoint");
        requireAuthorizationAgreement(authorization, claimed);
        String replayClosureFingerprint = ProtocolFingerprint.of(
                objectMapper, claimed.dependencies().plan().replayDependencies());
        if (!authorization.replayClosureFingerprint().equals(replayClosureFingerprint)) {
            throw new IllegalArgumentException(
                    "Recovery authorization does not match the replay dependency closure");
        }
        if (claimed.lifecycle().status() != DurableTestExecutionCheckpoint.Status.RESUMING) {
            throw new IllegalArgumentException("A recovery dispatch requires a RESUMING checkpoint");
        }
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = claimed.lifecycle();
        DurableTestRecoveryDispatch material = new DurableTestRecoveryDispatch(
                SCHEMA_VERSION, authorization, claimed.scope(), claimed.runId(),
                claimed.engineExecutionId(), lifecycle.ownerId(), lifecycle.leaseEpoch(),
                lifecycle.revision(), lifecycle.leaseExpiresAt(),
                claimed.checkpointFingerprint(), "");
        String sealed = ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(objectMapper, "objectMapper"),
                material.fingerprintMaterial(), MAX_CANONICAL_BYTES);
        return material.withDispatchFingerprint(sealed);
    }

    /**
     * Verifies nested authorization, dispatch content identity, and checkpoint agreement.
     *
     * @param objectMapper canonical protocol mapper
     * @param checkpoint checkpoint expected to be controlled by this handoff
     */
    public void requireValid(ObjectMapper objectMapper, DurableTestExecutionCheckpoint checkpoint) {
        requireValid(objectMapper);
        if (!agreesWith(checkpoint)) {
            throw new IllegalArgumentException(
                    "Durable recovery dispatch does not match its claimed checkpoint");
        }
    }

    /**
     * Verifies nested and aggregate content identities without consulting mutable state.
     *
     * @param objectMapper canonical protocol mapper
     */
    public void requireValid(ObjectMapper objectMapper) {
        authorization.requireValid(objectMapper);
        String actual = ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(objectMapper, "objectMapper"),
                fingerprintMaterial(), MAX_CANONICAL_BYTES);
        if (!actual.equals(dispatchFingerprint)) {
            throw new IllegalArgumentException("Invalid durable recovery dispatch fingerprint");
        }
    }

    /**
     * Tests exact scope, execution, owner fence, expiry, and content identity agreement.
     *
     * @param checkpoint candidate live or historical claimed checkpoint
     * @return whether the candidate is exactly controlled by this dispatch
     */
    public boolean agreesWith(DurableTestExecutionCheckpoint checkpoint) {
        if (checkpoint == null) {
            return false;
        }
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
        return scope.equals(checkpoint.scope())
                && runId.equals(checkpoint.runId())
                && engineExecutionId.equals(checkpoint.engineExecutionId())
                && ownerId.equals(lifecycle.ownerId())
                && leaseEpoch == lifecycle.leaseEpoch()
                && revision == lifecycle.revision()
                && leaseExpiresAt.equals(lifecycle.leaseExpiresAt())
                && checkpointFingerprint.equals(checkpoint.checkpointFingerprint())
                && lifecycle.status() == DurableTestExecutionCheckpoint.Status.RESUMING;
    }

    /**
     * Projects canonical material covered by {@link #dispatchFingerprint()}.
     *
     * @return payload-free worker handoff material
     */
    public Map<String, Object> fingerprintMaterial() {
        return Map.ofEntries(
                Map.entry("schemaVersion", schemaVersion),
                Map.entry("authorization", authorization),
                Map.entry("scope", scope),
                Map.entry("runId", runId),
                Map.entry("engineExecutionId", engineExecutionId),
                Map.entry("ownerId", ownerId),
                Map.entry("leaseEpoch", leaseEpoch),
                Map.entry("revision", revision),
                Map.entry("leaseExpiresAt", leaseExpiresAt),
                Map.entry("checkpointFingerprint", checkpointFingerprint));
    }

    private DurableTestRecoveryDispatch withDispatchFingerprint(String value) {
        return new DurableTestRecoveryDispatch(schemaVersion, authorization, scope, runId,
                engineExecutionId, ownerId, leaseEpoch, revision, leaseExpiresAt,
                checkpointFingerprint, value);
    }

    private static void requireAuthorizationAgreement(
            DurableTestRecoveryAuthorization authorization,
            DurableTestExecutionCheckpoint checkpoint) {
        DurableTestExecutionCheckpoint.ControlDependencies dependencies = checkpoint.dependencies();
        DurableTestExecutionCheckpoint.ExecutionTargetRef target = dependencies.target();
        if (target == null
                || !authorization.targetFingerprint().equals(target.fingerprint())
                || !authorization.planFingerprint().equals(
                dependencies.plan().planFingerprint())
                || !authorization.fixtureFingerprint().equals(
                dependencies.fixture().fingerprint())
                || !authorization.providerStateFingerprint().equals(
                checkpoint.executionServiceState().snapshotFingerprint())
                || !authorization.authorityFingerprint().equals(
                dependencies.identitySnapshot().fingerprint())
                || !authorization.authorizedPurpose().equals(
                dependencies.plan().authorizedPurpose())
                || !authorization.sideEffectPolicy().equals(dependencies.sideEffectPolicy())) {
            throw new IllegalArgumentException(
                    "Recovery authorization does not match the claimed dependency closure");
        }
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String identifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded stable identifier");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
