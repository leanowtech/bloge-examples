package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Database-clock authority for exact certificate-rotation replica acknowledgements. */
public interface ControlPlaneCertificateRotationConvergenceRepository {

    /** Persists or renews one exact process-start acknowledgement. */
    Snapshot acknowledge(Acknowledgement acknowledgement);

    /** Reads one aggregate snapshot without mutating replica state. */
    Snapshot snapshot(ExpectedRotation expectedRotation);

    /** Withdraws all current acknowledgements owned by this exact local process start. */
    void withdraw(String instanceId, String startupId);

    /** Closed local replica state; provider diagnostics never enter this protocol. */
    enum ReplicaState {
        /** Candidate material is locally verified and staged for activation. */
        STAGED,
        /** Candidate generation is the locally active transport generation. */
        ACTIVE,
        /** Local processing failed with a bounded machine-readable reason. */
        FAILED
    }

    /**
     * Exact expected signed rotation identity used for aggregate evaluation.
     *
     * @param targetId stable control-plane transport target
     * @param generation positive candidate generation
     * @param eventId signed event identity
     * @param eventFingerprint canonical signed event fingerprint
     * @param settingsFingerprint candidate settings fingerprint
     * @param activateAt signed activation instant
     */
    record ExpectedRotation(
            String targetId,
            long generation,
            String eventId,
            String eventFingerprint,
            String settingsFingerprint,
            Instant activateAt) {

        /** Validates the exact payload-free rotation identity. */
        public ExpectedRotation {
            targetId = normalized(targetId);
            eventId = normalized(eventId);
            eventFingerprint = normalized(eventFingerprint);
            settingsFingerprint = normalized(settingsFingerprint);
            if (!IDENTIFIER.matcher(targetId).matches()
                    || generation < 1 || !IDENTIFIER.matcher(eventId).matches()
                    || !FINGERPRINT.matcher(eventFingerprint).matches()
                    || !FINGERPRINT.matcher(settingsFingerprint).matches()
                    || activateAt == null) {
                throw invalid("Expected certificate rotation is invalid");
            }
        }
    }

    /**
     * One private, payload-free process-start acknowledgement.
     *
     * @param schemaVersion acknowledgement protocol generation
     * @param deploymentScopeId exact signed-event deployment scope
     * @param fleetId exact rollout fleet generation
     * @param instanceId stable serving slot
     * @param startupId unique process-start UUID
     * @param artifactFingerprint immutable application/image identity
     * @param policyFingerprint exact shared fleet policy identity
     * @param protocolVersion exact integration protocol generation
     * @param sequence strict per-process-target successor sequence
     * @param expectedRotation exact signed rotation identity
     * @param state local stage/active/failure state
     * @param failureCode bounded reason required only for failed state
     */
    record Acknowledgement(
            String schemaVersion,
            String deploymentScopeId,
            String fleetId,
            String instanceId,
            String startupId,
            String artifactFingerprint,
            String policyFingerprint,
            String protocolVersion,
            long sequence,
            ExpectedRotation expectedRotation,
            ReplicaState state,
            String failureCode) {

        /** Current private acknowledgement protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationReplicaAcknowledgement.v1";

        /** Rejects incomplete identity, unsafe diagnostics and ambiguous state. */
        public Acknowledgement {
            schemaVersion = normalized(schemaVersion);
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
            instanceId = normalized(instanceId);
            startupId = normalized(startupId);
            artifactFingerprint = normalized(artifactFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            protocolVersion = normalized(protocolVersion);
            expectedRotation = Objects.requireNonNull(expectedRotation, "expectedRotation");
            state = Objects.requireNonNull(state, "state");
            failureCode = normalized(failureCode);
            boolean failureValid = state == ReplicaState.FAILED
                    ? FAILURE_CODE.matcher(failureCode).matches() : failureCode.isEmpty();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || !IDENTIFIER.matcher(instanceId).matches()
                    || !validUuid(startupId)
                    || !FINGERPRINT.matcher(artifactFingerprint).matches()
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !IDENTIFIER.matcher(protocolVersion).matches()
                    || sequence < 1 || !failureValid) {
                throw invalid("Certificate rotation acknowledgement is invalid");
            }
        }
    }

    /**
     * Aggregate-only fleet state for one exact rotation identity.
     *
     * @param schemaVersion aggregate protocol generation
     * @param activationPermitted configured stage threshold is satisfied without active conflicts
     * @param converged every expected slot has one exact active acknowledgement
     * @param status primary bounded status
     * @param expectedReplicaCount exact inventory cardinality
     * @param requiredStagedReplicaCount activation threshold
     * @param liveReplicaCount unexpired process-start rows
     * @param stagedReplicaCount exact live staged rows
     * @param activeReplicaCount exact live active rows
     * @param failedReplicaCount exact live failed rows
     * @param missingReplicaCount expected slots without a live process
     * @param unexpectedReplicaCount live slots outside the inventory
     * @param duplicateReplicaCount expected slots with multiple live process starts
     * @param divergentArtifactCount live rows from another immutable artifact
     * @param divergentPolicyCount live rows from another fleet policy
     * @param divergentProtocolCount live rows using another protocol generation
     * @param divergentRotationCount live rows acknowledging another rotation identity
     * @param corruptReplicaCount live-looking rows that fail whole-record verification
     * @param observedAt database observation time
     * @param nextLeaseExpiryAt earliest live membership expiry, possibly null
     * @param activationBlockers bounded reasons preventing activation
     * @param convergenceBlockers bounded reasons preventing all-replica convergence
     */
    record Snapshot(
            String schemaVersion,
            boolean activationPermitted,
            boolean converged,
            String status,
            int expectedReplicaCount,
            int requiredStagedReplicaCount,
            int liveReplicaCount,
            int stagedReplicaCount,
            int activeReplicaCount,
            int failedReplicaCount,
            int missingReplicaCount,
            int unexpectedReplicaCount,
            int duplicateReplicaCount,
            int divergentArtifactCount,
            int divergentPolicyCount,
            int divergentProtocolCount,
            int divergentRotationCount,
            int corruptReplicaCount,
            Instant observedAt,
            Instant nextLeaseExpiryAt,
            List<String> activationBlockers,
            List<String> convergenceBlockers) {

        /** Current aggregate convergence protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationConvergenceSnapshot.v1";

        /** Validates bounded counts and truth relationships. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            activationBlockers = activationBlockers == null
                    ? List.of() : List.copyOf(activationBlockers);
            convergenceBlockers = convergenceBlockers == null
                    ? List.of() : List.copyOf(convergenceBlockers);
            int maximum = ControlPlaneCertificateRotationFleetPolicy.maximumReplicas() * 2;
            boolean countsValid = expectedReplicaCount > 0
                    && expectedReplicaCount
                    <= ControlPlaneCertificateRotationFleetPolicy.maximumReplicas()
                    && requiredStagedReplicaCount > 0
                    && requiredStagedReplicaCount <= expectedReplicaCount
                    && bounded(liveReplicaCount, maximum)
                    && bounded(stagedReplicaCount, liveReplicaCount)
                    && bounded(activeReplicaCount, liveReplicaCount)
                    && bounded(failedReplicaCount, liveReplicaCount)
                    && stagedReplicaCount + activeReplicaCount + failedReplicaCount
                    <= liveReplicaCount
                    && bounded(missingReplicaCount, expectedReplicaCount)
                    && bounded(unexpectedReplicaCount, liveReplicaCount)
                    && bounded(duplicateReplicaCount, expectedReplicaCount)
                    && bounded(divergentArtifactCount, liveReplicaCount)
                    && bounded(divergentPolicyCount, liveReplicaCount)
                    && bounded(divergentProtocolCount, liveReplicaCount)
                    && bounded(divergentRotationCount, liveReplicaCount)
                    && bounded(corruptReplicaCount, maximum);
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank() || !countsValid
                    || observedAt == null
                    || liveReplicaCount == 0 && nextLeaseExpiryAt != null
                    || liveReplicaCount > 0 && (nextLeaseExpiryAt == null
                    || !nextLeaseExpiryAt.isAfter(observedAt))
                    || !blockersValid(activationBlockers)
                    || !blockersValid(convergenceBlockers)
                    || activationPermitted != activationBlockers.isEmpty()
                    || converged != convergenceBlockers.isEmpty()
                    || activationPermitted
                    && stagedReplicaCount + activeReplicaCount < requiredStagedReplicaCount
                    || activationPermitted && failedReplicaCount > 0
                    || converged && !"CONVERGED".equals(status)
                    || !converged && activationPermitted
                    && !"ACTIVATION_PERMITTED".equals(status)
                    || !activationPermitted
                    && !status.equals(activationBlockers.getFirst())
                    || converged && (!activationPermitted || !"CONVERGED".equals(status)
                    || liveReplicaCount != expectedReplicaCount
                    || activeReplicaCount != expectedReplicaCount
                    || stagedReplicaCount != 0 || failedReplicaCount != 0
                    || missingReplicaCount != 0 || unexpectedReplicaCount != 0
                    || duplicateReplicaCount != 0 || divergentArtifactCount != 0
                    || divergentPolicyCount != 0 || divergentProtocolCount != 0
                    || divergentRotationCount != 0 || corruptReplicaCount != 0)) {
                throw invalid("Certificate rotation convergence snapshot is invalid");
            }
        }
    }

    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static boolean blockersValid(List<String> blockers) {
        return blockers.size() <= 20 && blockers.stream().allMatch(
                value -> value != null && FAILURE_CODE.matcher(value).matches());
    }

    private static boolean bounded(int value, int maximum) {
        return value >= 0 && value <= maximum;
    }

    private static boolean validUuid(String value) {
        try {
            return java.util.UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
