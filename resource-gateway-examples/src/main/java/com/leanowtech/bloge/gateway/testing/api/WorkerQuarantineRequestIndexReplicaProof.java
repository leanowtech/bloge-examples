package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexInventory;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.List;

/**
 * Signed, short-lived statement of one Resource Gateway replica's request-index rollout state.
 *
 * <p>The signature covers {@link Material} through {@code materialFingerprint}. A challenge binds
 * the proof to one deployment-gate evaluation; instance and startup identities prevent a cached
 * proof from being counted as another process; the configured artifact fingerprint binds the
 * statement to the deployment authority's immutable image inventory.</p>
 *
 * @param schemaVersion proof envelope protocol version
 * @param material immutable signed rollout facts
 * @param materialFingerprint canonical SHA-256 fingerprint of material
 * @param seal persisted Ed25519 evidence seal over the material fingerprint
 */
public record WorkerQuarantineRequestIndexReplicaProof(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        VisualRunEvidenceSeal seal) {

    public static final String SCHEMA_VERSION =
            "bloge.workerQuarantineRequestIndexReplicaProof.v1";
    public static final String MATERIAL_SCHEMA_VERSION =
            "bloge.workerQuarantineRequestIndexReplicaProofMaterial.v1";
    private static final List<String> BLOCKER_ORDER = List.of(
            "CURRENT_MODE_NOT_PREDECESSOR",
            "LIVE_KEYED_ROWS_PRESENT",
            "LIVE_LEGACY_ROWS_PRESENT");

    /** Rejects an incomplete or internally inconsistent signed envelope. */
    public WorkerQuarantineRequestIndexReplicaProof {
        schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
        materialFingerprint = materialFingerprint == null ? "" : materialFingerprint.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || material == null
                || !materialFingerprint.matches("sha256:[a-f0-9]{64}")
                || seal == null
                || !seal.signed()
                || !materialFingerprint.equals(seal.materialFingerprint())) {
            throw new IllegalArgumentException("Request-index replica proof envelope is invalid");
        }
    }

    /**
     * Canonical material signed by one replica.
     *
     * @param schemaVersion material domain version
     * @param challenge caller challenge copied exactly from the request
     * @param deploymentScopeFingerprint identity-derived tenant/project/environment/region scope
     * @param instanceId stable deployment-platform replica identity
     * @param startupId unique process-start identity
     * @param artifactFingerprint immutable binary or image fingerprint asserted by deployment
     * @param protocolVersion Resource Gateway cross-system protocol version
     * @param currentMode mode enforced by this process
     * @param targetMode immediate rollout target requested by the caller
     * @param inventory database-clock live tombstone inventory
     * @param transitionAllowed whether all local transition invariants passed
     * @param blockers closed payload-free reasons preventing the transition
     * @param expiresAt exclusive proof freshness deadline
     */
    public record Material(
            String schemaVersion,
            String challenge,
            String deploymentScopeFingerprint,
            String instanceId,
            String startupId,
            String artifactFingerprint,
            String protocolVersion,
            WorkerQuarantineRequestIndexMode currentMode,
            WorkerQuarantineRequestIndexMode targetMode,
            WorkerQuarantineRequestIndexInventory inventory,
            boolean transitionAllowed,
            List<String> blockers,
            Instant expiresAt) {

        /** Enforces closed, deterministic proof material before fingerprinting. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            challenge = normalized(challenge);
            deploymentScopeFingerprint = normalized(deploymentScopeFingerprint);
            instanceId = normalized(instanceId);
            startupId = normalized(startupId);
            artifactFingerprint = normalized(artifactFingerprint);
            protocolVersion = normalized(protocolVersion);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            if (!MATERIAL_SCHEMA_VERSION.equals(schemaVersion)
                    || !challenge.matches("[A-Za-z0-9_-]{32,128}")
                    || !deploymentScopeFingerprint.matches("sha256:[a-f0-9]{64}")
                    || !instanceId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                    || !validUuid(startupId)
                    || !artifactFingerprint.matches("sha256:[a-f0-9]{64}")
                    || protocolVersion.isBlank()
                    || protocolVersion.length() > 64
                    || currentMode == null
                    || targetMode == null
                    || inventory == null
                    || expiresAt == null
                    || !expiresAt.isAfter(inventory.observedAt())
                    || blockers.size() > 8
                    || !canonicalBlockers(blockers)
                    || transitionAllowed != blockers.isEmpty()) {
                throw new IllegalArgumentException("Request-index replica proof material is invalid");
            }
        }
    }

    private static boolean canonicalBlockers(List<String> blockers) {
        int previous = -1;
        for (String blocker : blockers) {
            int current = BLOCKER_ORDER.indexOf(blocker);
            if (current <= previous) {
                return false;
            }
            previous = current;
        }
        return true;
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
