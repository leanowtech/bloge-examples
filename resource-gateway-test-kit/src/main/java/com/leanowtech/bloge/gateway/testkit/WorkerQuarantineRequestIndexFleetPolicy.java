package com.leanowtech.bloge.gateway.testkit;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Caller-owned exact fleet inventory and immutable rollout expectations.
 *
 * <p>Resource Gateway does not create this policy. A deployment authority must derive it from an
 * independently trusted serving inventory, artifact registry, workload scope, and evidence key-set
 * pin. The policy is therefore the trust boundary that prevents a reachable subset of replicas from
 * presenting itself as the whole fleet.</p>
 *
 * @param challenge one deployment-gate nonce shared by every directly addressed replica
 * @param deploymentScopeFingerprint expected tenant/project/environment/region scope fingerprint
 * @param targetMode immediate request-index mode to enter
 * @param artifactFingerprint expected immutable image or application digest
 * @param protocolVersion exact Resource Gateway integration protocol version
 * @param expectedInstanceIds complete serving-instance inventory
 * @param trustedKeySetFingerprint evidence key-set pin obtained outside the proof endpoint
 * @param maximumObservationSpread maximum DB observation-time spread across the proof cohort
 */
public record WorkerQuarantineRequestIndexFleetPolicy(
        String challenge,
        String deploymentScopeFingerprint,
        WorkerQuarantineRequestIndexReplicaProof.Mode targetMode,
        String artifactFingerprint,
        String protocolVersion,
        Set<String> expectedInstanceIds,
        String trustedKeySetFingerprint,
        Duration maximumObservationSpread) {

    /** Validates and deterministically orders the complete caller-owned policy. */
    public WorkerQuarantineRequestIndexFleetPolicy {
        challenge = normalized(challenge);
        deploymentScopeFingerprint = normalized(deploymentScopeFingerprint);
        artifactFingerprint = normalized(artifactFingerprint);
        protocolVersion = normalized(protocolVersion);
        trustedKeySetFingerprint = normalized(trustedKeySetFingerprint);
        TreeSet<String> instances = new TreeSet<>();
        if (expectedInstanceIds != null) {
            instances.addAll(expectedInstanceIds);
        }
        expectedInstanceIds = Collections.unmodifiableSet(instances);
        if (!challenge.matches("[A-Za-z0-9_-]{32,128}")
                || !fingerprint(deploymentScopeFingerprint)
                || targetMode == null
                || targetMode == WorkerQuarantineRequestIndexReplicaProof.Mode.LEGACY_READ_WRITE
                || !fingerprint(artifactFingerprint)
                || protocolVersion.isBlank() || protocolVersion.length() > 64
                || expectedInstanceIds.isEmpty() || expectedInstanceIds.size() > 10_000
                || expectedInstanceIds.stream().anyMatch(instance ->
                !instance.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}"))
                || !fingerprint(trustedKeySetFingerprint)
                || maximumObservationSpread == null
                || maximumObservationSpread.compareTo(Duration.ofSeconds(1)) < 0
                || maximumObservationSpread.compareTo(Duration.ofMinutes(5)) > 0
                || maximumObservationSpread.getNano() != 0) {
            throw new IllegalArgumentException("Request-index fleet policy is invalid");
        }
    }

    /**
     * Creates the default strict policy with a 30-second proof-cohort observation window.
     *
     * @param challenge deployment challenge
     * @param deploymentScopeFingerprint expected scope fingerprint
     * @param targetMode immediate rollout target
     * @param artifactFingerprint expected artifact digest
     * @param protocolVersion expected protocol version
     * @param expectedInstanceIds exact serving inventory
     * @param trustedKeySetFingerprint independently distributed key-set pin
     * @return strict immutable fleet policy
     */
    public static WorkerQuarantineRequestIndexFleetPolicy strict(
            String challenge,
            String deploymentScopeFingerprint,
            WorkerQuarantineRequestIndexReplicaProof.Mode targetMode,
            String artifactFingerprint,
            String protocolVersion,
            Set<String> expectedInstanceIds,
            String trustedKeySetFingerprint) {
        return new WorkerQuarantineRequestIndexFleetPolicy(
                challenge, deploymentScopeFingerprint, targetMode, artifactFingerprint,
                protocolVersion, expectedInstanceIds, trustedKeySetFingerprint,
                Duration.ofSeconds(30));
    }

    private static boolean fingerprint(String value) {
        return value.matches("sha256:[a-f0-9]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
