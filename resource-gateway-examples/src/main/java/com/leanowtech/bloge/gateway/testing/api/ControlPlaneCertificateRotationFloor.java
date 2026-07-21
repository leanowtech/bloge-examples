package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Durable monotonic authority for accepted control-plane certificate generations.
 *
 * <p>The floor is downstream of signature verification and upstream of live TLS staging. It
 * prevents a restart or competing replica from accepting an older initial generation, a different
 * event for an existing generation, a reused event identity, or a successor whose settings
 * predecessor differs from the durable active head.</p>
 */
public interface ControlPlaneCertificateRotationFloor {

    /** Closed acceptance outcome for one exact signed event. */
    enum AcceptanceStatus {
        /** A future successor was durably staged. */
        STAGED,
        /** A due successor was durably accepted as active. */
        ACTIVATED,
        /** The exact durable event was submitted again without mutation. */
        REPLAYED
    }

    /**
     * Out-of-band initial target inventory used only to establish or verify a floor.
     *
     * @param generation positive active generation
     * @param materialId safe deployment-owned material lookup identity
     * @param settingsFingerprint exact active settings fingerprint
     */
    record InitialTarget(
            long generation,
            String materialId,
            String settingsFingerprint) {

        /** Rejects non-canonical or incomplete initial target state. */
        public InitialTarget {
            materialId = normalized(materialId);
            settingsFingerprint = normalized(settingsFingerprint);
            if (generation < 1 || !ControlPlaneCertificateRotationFloor.materialId(materialId)
                    || !fingerprint(settingsFingerprint)) {
                throw invalid("Control-plane certificate rotation initial target is invalid");
            }
        }
    }

    /**
     * Whole durable target head, including at most one pending successor.
     *
     * @param schemaVersion snapshot protocol version
     * @param deploymentScopeId exact deployment scope
     * @param targetId exact transport target
     * @param activeGeneration durable active generation
     * @param activeMaterialId safe active material lookup identity
     * @param activeSettingsFingerprint exact active settings fingerprint
     * @param activeEventId signed event identity, empty only for initial bootstrap
     * @param activeEventFingerprint signed event fingerprint, empty only for initial bootstrap
     * @param activatedAt database activation or initial bootstrap time
     * @param pendingGeneration pending successor generation, or zero
     * @param pendingMaterialId pending material lookup identity, or empty
     * @param pendingSettingsFingerprint pending settings fingerprint, or empty
     * @param pendingEventId pending signed event identity, or empty
     * @param pendingEventFingerprint pending signed event fingerprint, or empty
     * @param pendingActivateAt pending activation instant, or null
     * @param updatedAt database time of the last durable mutation
     */
    record Snapshot(
            String schemaVersion,
            String deploymentScopeId,
            String targetId,
            long activeGeneration,
            String activeMaterialId,
            String activeSettingsFingerprint,
            String activeEventId,
            String activeEventFingerprint,
            Instant activatedAt,
            long pendingGeneration,
            String pendingMaterialId,
            String pendingSettingsFingerprint,
            String pendingEventId,
            String pendingEventFingerprint,
            Instant pendingActivateAt,
            Instant updatedAt) {

        /** Current durable snapshot protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationFloorSnapshot.v1";

        /** Validates complete active state and all-or-none pending state. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion).isBlank()
                    ? SCHEMA_VERSION : normalized(schemaVersion);
            deploymentScopeId = normalized(deploymentScopeId);
            targetId = normalized(targetId);
            activeMaterialId = normalized(activeMaterialId);
            activeSettingsFingerprint = normalized(activeSettingsFingerprint);
            activeEventId = normalized(activeEventId);
            activeEventFingerprint = normalized(activeEventFingerprint);
            pendingMaterialId = normalized(pendingMaterialId);
            pendingSettingsFingerprint = normalized(pendingSettingsFingerprint);
            pendingEventId = normalized(pendingEventId);
            pendingEventFingerprint = normalized(pendingEventFingerprint);
            activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            boolean initial = activeEventId.isBlank() && activeEventFingerprint.isBlank();
            boolean signed = identifier(activeEventId) && fingerprint(activeEventFingerprint);
            boolean pending = pendingGeneration > 0;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !identifier(deploymentScopeId) || !identifier(targetId)
                    || activeGeneration < 1 || !materialId(activeMaterialId)
                    || !fingerprint(activeSettingsFingerprint) || !(initial || signed)
                    || updatedAt.isBefore(activatedAt)
                    || pending && (pendingGeneration != activeGeneration + 1
                    || !materialId(pendingMaterialId)
                    || !fingerprint(pendingSettingsFingerprint)
                    || !identifier(pendingEventId) || !fingerprint(pendingEventFingerprint)
                    || pendingActivateAt == null)
                    || !pending && (pendingGeneration != 0 || !pendingMaterialId.isBlank()
                    || !pendingSettingsFingerprint.isBlank() || !pendingEventId.isBlank()
                    || !pendingEventFingerprint.isBlank() || pendingActivateAt != null)) {
                throw invalid("Control-plane certificate rotation floor snapshot is invalid");
            }
        }

        /** @return true when a successor is durably pending */
        public boolean hasPending() {
            return pendingGeneration > 0;
        }
    }

    /**
     * Result of atomically accepting or replaying one event.
     *
     * @param status closed mutation outcome
     * @param snapshot exact durable target head after the operation
     */
    record Acceptance(AcceptanceStatus status, Snapshot snapshot) {
        /** Rejects absent status or state. */
        public Acceptance {
            status = Objects.requireNonNull(status, "status");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Atomically accepts one already signature-verified event against the durable floor.
     *
     * @param event exact signed event
     * @return staged, activated, or replayed durable state
     */
    Acceptance accept(ControlPlaneCertificateRotationEvent event);

    /**
     * Reads one target and atomically advances a due pending successor using database time.
     *
     * @param targetId configured target identity
     * @return exact durable target head
     */
    Snapshot snapshot(String targetId);

    /** @return exact durable heads for the configured target inventory */
    Map<String, Snapshot> snapshots();

    /** @return true only when state survives process restart */
    boolean durable();

    private static boolean identifier(String value) {
        return value != null
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    }

    private static boolean materialId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    }

    private static boolean fingerprint(String value) {
        return value != null && value.matches("sha256:[a-f0-9]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
