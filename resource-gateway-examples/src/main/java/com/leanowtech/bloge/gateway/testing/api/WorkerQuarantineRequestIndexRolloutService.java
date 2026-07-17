package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexInventory;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticated signing boundary for one replica's request-index rollout readiness facts.
 *
 * <p>The service does not discover or count the fleet. It binds an external deployment challenge
 * to this process identity, configured immutable artifact fingerprint, exact operating mode, and a
 * database-clock live-row inventory. A deployment authority must independently supply the exact
 * serving-instance inventory and reject any missing or unexpected replica.</p>
 */
public final class WorkerQuarantineRequestIndexRolloutService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> CLEARANCES =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final DatabaseDurableWorkerQuarantineControlPlane controlPlane;
    private final TestSecurityEventRepository securityEvents;
    private final VisualEvidenceSigner signer;
    private final ObjectMapper objectMapper;
    private final Settings settings;

    /**
     * Creates the fail-closed per-replica rollout proof authority.
     *
     * @param controlPlane database request-index inventory authority
     * @param securityEvents append-only test-runtime security audit
     * @param signer evidence signer, normally backed by managed KMS/HSM in staging
     * @param objectMapper canonical protocol fingerprint mapper
     * @param settings deployment-owned instance, artifact, TTL, and authorization settings
     */
    public WorkerQuarantineRequestIndexRolloutService(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            TestSecurityEventRepository securityEvents,
            VisualEvidenceSigner signer,
            ObjectMapper objectMapper,
            Settings settings) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        if (!signer.available()) {
            throw new IllegalArgumentException(
                    "Request-index rollout proof requires an available evidence signer");
        }
    }

    /**
     * Signs one short-lived local transition proof after authorization and exact inventory read.
     *
     * @param request challenge and immediate target mode
     * @param identity verified deployment workload identity
     * @return signed proof, including blockers when local transition invariants are not satisfied
     */
    public WorkerQuarantineRequestIndexReplicaProof prove(
            WorkerQuarantineRequestIndexReplicaProofRequest request,
            IntegrationRequestContext identity) {
        authorize(identity);
        validateRequest(request, identity);
        try {
            WorkerQuarantineRequestIndexInventory inventory = controlPlane.requestIndexInventory();
            WorkerQuarantineRequestIndexMode current = controlPlane.requestIndexMode();
            List<String> blockers = blockers(current, request.targetMode(), inventory);
            String scopeFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                    "schemaVersion", "bloge.workerQuarantineRequestIndexDeploymentScope.v1",
                    "tenantId", identity.tenantId(),
                    "organizationId", identity.organizationId(),
                    "projectId", identity.projectId(),
                    "environmentId", identity.environmentId(),
                    "region", identity.region()));
            WorkerQuarantineRequestIndexReplicaProof.Material material =
                    new WorkerQuarantineRequestIndexReplicaProof.Material(
                            WorkerQuarantineRequestIndexReplicaProof.MATERIAL_SCHEMA_VERSION,
                            request.challenge(), scopeFingerprint, settings.instanceId(),
                            settings.startupId(), settings.artifactFingerprint(),
                            ToolStudioResourceGatewayProtocol.VERSION, current,
                            request.targetMode(), inventory, blockers.isEmpty(), blockers,
                            inventory.observedAt().plus(settings.proofTtl()));
            String fingerprint = ProtocolFingerprint.of(objectMapper, material);
            VisualRunEvidenceSeal seal = signer.seal(fingerprint);
            requireSealTime(seal, inventory.observedAt(), material.expiresAt());
            WorkerQuarantineRequestIndexReplicaProof proof =
                    new WorkerQuarantineRequestIndexReplicaProof(
                            WorkerQuarantineRequestIndexReplicaProof.SCHEMA_VERSION,
                            material, fingerprint, seal);
            append(identity, "ALLOWED", "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_ISSUED", Map.of(
                    "instanceId", settings.instanceId(),
                    "startupId", settings.startupId(),
                    "artifactFingerprint", settings.artifactFingerprint(),
                    "materialFingerprint", fingerprint,
                    "currentMode", current.name(),
                    "targetMode", request.targetMode().name(),
                    "transitionAllowed", blockers.isEmpty(),
                    "blockers", blockers,
                    "liveLegacyRows", inventory.liveLegacyRows(),
                    "liveKeyedRows", inventory.liveKeyedRows()));
            return proof;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            appendUnavailable(identity);
            throw unavailable(identity,
                    "Request-index rollout proof could not be produced.");
        }
    }

    private void authorize(IntegrationRequestContext identity) {
        if (identity == null) {
            throw new IntegrationProblemException(IntegrationProblem.unauthorized(
                    "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_IDENTITY_REQUIRED",
                    "Verified deployment identity is required.", "", Map.of()));
        }
        try {
            identity.requireComplete();
        } catch (IntegrationProblemException invalid) {
            appendRejected(identity, "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_IDENTITY_INCOMPLETE");
            throw invalid;
        }
        boolean scopeComplete = !identity.projectId().isBlank() && !identity.region().isBlank();
        boolean allowed = ENABLED_ENVIRONMENTS.contains(identity.environmentId())
                && "TEST_RUNTIME_MAINTENANCE".equals(identity.purpose())
                && identity.groups().contains(settings.requiredGroup())
                && identity.hasClearanceAtLeast(settings.requiredClearance())
                && scopeComplete;
        if (!allowed) {
            appendRejected(identity, "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_FORBIDDEN");
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_FORBIDDEN",
                    "Request-index rollout proof is not allowed for this identity.",
                    identity.correlationId(), Map.of()));
        }
    }

    private void validateRequest(
            WorkerQuarantineRequestIndexReplicaProofRequest request,
            IntegrationRequestContext identity) {
        boolean valid = request != null
                && WorkerQuarantineRequestIndexReplicaProofRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                && request.challenge().matches("[A-Za-z0-9_-]{32,128}")
                && (request.targetMode() == WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE
                || request.targetMode() == WorkerQuarantineRequestIndexMode.KEYED_ONLY);
        if (!valid) {
            appendRejected(identity, "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_REQUEST_INVALID");
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_REQUEST_INVALID",
                    "A canonical challenge and immediate keyed rollout target are required.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static List<String> blockers(
            WorkerQuarantineRequestIndexMode current,
            WorkerQuarantineRequestIndexMode target,
            WorkerQuarantineRequestIndexInventory inventory) {
        List<String> blockers = new ArrayList<>();
        WorkerQuarantineRequestIndexMode expected = switch (target) {
            case DUAL_READ_KEYED_WRITE -> WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE;
            case KEYED_ONLY -> WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE;
            case LEGACY_READ_WRITE -> null;
        };
        if (current != expected) {
            blockers.add("CURRENT_MODE_NOT_PREDECESSOR");
        }
        if (target == WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE
                && inventory.liveKeyedRows() > 0) {
            blockers.add("LIVE_KEYED_ROWS_PRESENT");
        }
        if (target == WorkerQuarantineRequestIndexMode.KEYED_ONLY
                && inventory.liveLegacyRows() > 0) {
            blockers.add("LIVE_LEGACY_ROWS_PRESENT");
        }
        return List.copyOf(blockers);
    }

    private static void requireSealTime(
            VisualRunEvidenceSeal seal, Instant observedAt, Instant expiresAt) {
        Duration skew = Duration.ofMinutes(5);
        if (seal == null || seal.signedAt().isBefore(observedAt.minus(skew))
                || seal.signedAt().isAfter(expiresAt)) {
            throw new IllegalStateException(
                    "Request-index rollout proof signing time is outside its validity window");
        }
    }

    private void appendRejected(IntegrationRequestContext identity, String reasonCode) {
        append(identity, "REJECTED", reasonCode, Map.of());
    }

    private void appendUnavailable(IntegrationRequestContext identity) {
        try {
            append(identity, "REJECTED", "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_UNAVAILABLE",
                    Map.of("instanceId", settings.instanceId()));
        } catch (IntegrationProblemException ignored) {
            // The returned response remains unavailable; no unsigned proof escapes this boundary.
        }
    }

    private void append(
            IntegrationRequestContext identity,
            String outcome,
            String reasonCode,
            Map<String, Object> facts) {
        try {
            securityEvents.append(new TestSecurityEvent(0, Instant.now(),
                    identity == null ? "" : identity.correlationId(),
                    identity == null ? "" : identity.tenantId(),
                    identity == null ? "" : identity.environmentId(),
                    identity == null ? "" : identity.actorId(),
                    "DURABLE_WORKER_REQUEST_INDEX_REPLICA_PROOF", outcome,
                    reasonCode, facts));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity,
                    "Request-index rollout proof audit could not be committed.");
        }
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.TEST.REQUEST_INDEX_REPLICA_PROOF_UNAVAILABLE", title,
                identity == null ? "" : identity.correlationId(), Map.of()));
    }

    /**
     * Deployment-owned proof identity, freshness, and authorization configuration.
     *
     * @param instanceId stable serving-replica id from the deployment inventory
     * @param startupId unique id generated for this process start
     * @param artifactFingerprint immutable image or application artifact SHA-256
     * @param proofTtl short proof validity, from 5 through 300 whole seconds
     * @param requiredGroup deployment-owned rollout operator group
     * @param requiredClearance minimum caller clearance
     */
    public record Settings(
            String instanceId,
            String startupId,
            String artifactFingerprint,
            Duration proofTtl,
            String requiredGroup,
            String requiredClearance) {

        /** @return normalized and fail-closed settings */
        public Settings validated() {
            String instance = normalized(instanceId);
            String startup = normalized(startupId);
            String artifact = normalized(artifactFingerprint);
            String group = normalized(requiredGroup);
            String clearance = normalized(requiredClearance).toUpperCase(Locale.ROOT);
            Duration ttl = Objects.requireNonNull(proofTtl, "proofTtl");
            if (!instance.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                    || !validUuid(startup)
                    || !artifact.matches("sha256:[a-f0-9]{64}")
                    || ttl.compareTo(Duration.ofSeconds(5)) < 0
                    || ttl.compareTo(Duration.ofMinutes(5)) > 0
                    || ttl.getNano() != 0
                    || !group.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                    || !CLEARANCES.contains(clearance)) {
                throw new IllegalArgumentException(
                        "Request-index rollout proof settings are invalid");
            }
            return new Settings(instance, startup, artifact, ttl, group, clearance);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean validUuid(String value) {
        try {
            return java.util.UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
