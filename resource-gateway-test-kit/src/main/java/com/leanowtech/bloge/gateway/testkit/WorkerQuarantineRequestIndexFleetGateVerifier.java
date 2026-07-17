package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline fail-closed gate for a complete cohort of signed request-index rollout proofs.
 *
 * <p>The verifier never discovers the fleet and never trusts the proof endpoint to describe fleet
 * membership, artifact identity, deployment scope, protocol generation, or signing keys. Those
 * facts come from {@link WorkerQuarantineRequestIndexFleetPolicy} and an externally pinned complete
 * evidence-key lifecycle snapshot. Verification is independent of Resource Gateway server classes
 * and emits only bounded operational metadata.</p>
 */
public final class WorkerQuarantineRequestIndexFleetGateVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MIN_PROOF_TTL = Duration.ofSeconds(5);
    private static final Duration MAX_PROOF_TTL = Duration.ofMinutes(5);
    private final Clock clock;

    /** Creates a verifier using the system UTC clock. */
    public WorkerQuarantineRequestIndexFleetGateVerifier() {
        this(Clock.systemUTC());
    }

    WorkerQuarantineRequestIndexFleetGateVerifier(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Bounded fleet-gate outcome classes. */
    public enum Outcome {
        /** Exact inventory, policy, key trust, fingerprints, freshness, and signatures passed. */
        VERIFIED,
        /** A proof collection, signed material, fingerprint, or signature is invalid. */
        INVALID,
        /** Required pinned verification material is unavailable. */
        KEY_UNAVAILABLE,
        /** Caller policy, fleet identity, time, transition, or key policy rejects the cohort. */
        POLICY_REJECTED
    }

    /**
     * Payload-free fleet decision suitable for deployment logs and machine gates.
     *
     * @param outcome bounded decision class
     * @param reasonCode stable machine-readable reason
     * @param expectedInstances number of independently expected serving instances
     * @param observedInstances number of distinct instance proofs received
     * @param verifiedInstances number whose complete proof verification passed
     * @param instanceId implicated deployment instance, when safely known
     * @param keyId implicated signing key, when safely known
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            int expectedInstances,
            int observedInstances,
            int verifiedInstances,
            String instanceId,
            String keyId) {

        /** Normalizes and bounds all log-safe result fields. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            instanceId = normalized(instanceId);
            keyId = normalized(keyId);
            if (outcome == null || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,127}")
                    || expectedInstances < 0 || expectedInstances > 10_000
                    || observedInstances < 0 || observedInstances > 10_000
                    || verifiedInstances < 0 || verifiedInstances > observedInstances
                    || (!instanceId.isEmpty()
                    && !instanceId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}"))
                    || (!keyId.isEmpty()
                    && !keyId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}"))) {
                throw new IllegalArgumentException("Request-index fleet result is invalid");
            }
        }

        /**
         * Indicates whether every exact-inventory and cryptographic check passed.
         *
         * @return true only for a complete verified fleet cohort
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one exact proof cohort against caller-owned inventory and signing trust.
     *
     * @param proofs independently collected per-instance proofs
     * @param policy deployment-authoritative exact fleet policy
     * @param keySet complete signed evidence-key lifecycle snapshot
     * @return bounded fail-closed fleet decision
     */
    public VerificationResult verify(
            List<WorkerQuarantineRequestIndexReplicaProof> proofs,
            WorkerQuarantineRequestIndexFleetPolicy policy,
            EvidenceVerificationKeySet keySet) {
        if (policy == null) {
            return result(Outcome.POLICY_REJECTED, "FLEET_POLICY_MISSING", 0, 0, 0, "", "");
        }
        int expected = policy.expectedInstanceIds().size();
        if (proofs == null) {
            return result(Outcome.INVALID, "REPLICA_PROOF_COLLECTION_MISSING",
                    expected, 0, 0, "", "");
        }
        if (proofs.size() > 10_000) {
            return result(Outcome.INVALID, "REPLICA_PROOF_LIMIT_EXCEEDED",
                    expected, 10_000, 0, "", "");
        }

        Map<String, WorkerQuarantineRequestIndexReplicaProof> byInstance = new HashMap<>();
        Set<String> startupIds = new HashSet<>();
        Instant earliestObservation = null;
        Instant latestObservation = null;
        for (WorkerQuarantineRequestIndexReplicaProof proof : proofs) {
            if (proof == null) {
                return result(Outcome.INVALID, "REPLICA_PROOF_MISSING",
                        expected, byInstance.size(), 0, "", "");
            }
            WorkerQuarantineRequestIndexReplicaProof.Material material = proof.material();
            String instanceId = material.instanceId();
            if (byInstance.putIfAbsent(instanceId, proof) != null) {
                return result(Outcome.POLICY_REJECTED, "DUPLICATE_INSTANCE_PROOF",
                        expected, byInstance.size(), 0, instanceId, proof.seal().keyId());
            }
            if (!policy.expectedInstanceIds().contains(instanceId)) {
                return result(Outcome.POLICY_REJECTED, "UNEXPECTED_INSTANCE_PROOF",
                        expected, byInstance.size(), 0, instanceId, proof.seal().keyId());
            }
            if (!startupIds.add(material.startupId())) {
                return result(Outcome.POLICY_REJECTED, "DUPLICATE_PROCESS_START_PROOF",
                        expected, byInstance.size(), 0, instanceId, proof.seal().keyId());
            }
            Instant observedAt = material.inventory().observedAt();
            earliestObservation = earliestObservation == null || observedAt.isBefore(earliestObservation)
                    ? observedAt : earliestObservation;
            latestObservation = latestObservation == null || observedAt.isAfter(latestObservation)
                    ? observedAt : latestObservation;
        }
        if (!byInstance.keySet().equals(policy.expectedInstanceIds())) {
            return result(Outcome.POLICY_REJECTED, "EXPECTED_INSTANCE_PROOF_MISSING",
                    expected, byInstance.size(), 0, firstMissing(policy, byInstance), "");
        }
        if (earliestObservation != null && Duration.between(
                earliestObservation, latestObservation).compareTo(
                policy.maximumObservationSpread()) > 0) {
            return result(Outcome.POLICY_REJECTED, "PROOF_COHORT_OBSERVATION_SPREAD_EXCEEDED",
                    expected, byInstance.size(), 0, "", "");
        }

        TestSuiteEvidenceVerifier.KeySetVerificationResult keySetResult =
                new TestSuiteEvidenceVerifier(clock).verifyKeySet(
                        keySet, policy.trustedKeySetFingerprint());
        if (!keySetResult.verified()) {
            return result(map(keySetResult.outcome()), keySetResult.reasonCode(),
                    expected, byInstance.size(), 0, "", keySetResult.attestationKeyId());
        }

        Map<String, EvidenceVerificationKeySet.KeyPolicy> keys = new HashMap<>();
        keySet.keys().forEach(key -> keys.put(key.keyId(), key));
        List<WorkerQuarantineRequestIndexReplicaProof> ordered = byInstance.values().stream()
                .sorted(Comparator.comparing(proof -> proof.material().instanceId())).toList();
        int verified = 0;
        for (WorkerQuarantineRequestIndexReplicaProof proof : ordered) {
            VerificationResult failure = verifyProof(proof, policy, keySet, keys, expected,
                    byInstance.size(), verified);
            if (failure != null) {
                return failure;
            }
            verified++;
        }
        return result(Outcome.VERIFIED, "VERIFIED", expected, byInstance.size(), verified, "",
                keySet.activeKeyId());
    }

    private VerificationResult verifyProof(
            WorkerQuarantineRequestIndexReplicaProof proof,
            WorkerQuarantineRequestIndexFleetPolicy policy,
            EvidenceVerificationKeySet keySet,
            Map<String, EvidenceVerificationKeySet.KeyPolicy> keys,
            int expected,
            int observed,
            int verified) {
        WorkerQuarantineRequestIndexReplicaProof.Material material = proof.material();
        String instanceId = material.instanceId();
        String keyId = proof.seal().keyId();
        if (!policy.challenge().equals(material.challenge())) {
            return rejected("PROOF_CHALLENGE_MISMATCH", expected, observed, verified, instanceId, keyId);
        }
        if (!policy.deploymentScopeFingerprint().equals(material.deploymentScopeFingerprint())) {
            return rejected("PROOF_DEPLOYMENT_SCOPE_MISMATCH",
                    expected, observed, verified, instanceId, keyId);
        }
        if (!policy.targetMode().equals(material.targetMode())) {
            return rejected("PROOF_TARGET_MODE_MISMATCH", expected, observed, verified, instanceId, keyId);
        }
        if (!policy.artifactFingerprint().equals(material.artifactFingerprint())) {
            return rejected("PROOF_ARTIFACT_MISMATCH", expected, observed, verified, instanceId, keyId);
        }
        if (!policy.protocolVersion().equals(material.protocolVersion())) {
            return rejected("PROOF_PROTOCOL_VERSION_MISMATCH",
                    expected, observed, verified, instanceId, keyId);
        }
        WorkerQuarantineRequestIndexReplicaProof.Mode predecessor =
                policy.targetMode() == WorkerQuarantineRequestIndexReplicaProof.Mode.KEYED_ONLY
                        ? WorkerQuarantineRequestIndexReplicaProof.Mode.DUAL_READ_KEYED_WRITE
                        : WorkerQuarantineRequestIndexReplicaProof.Mode.LEGACY_READ_WRITE;
        if (material.currentMode() != predecessor) {
            return rejected("PROOF_CURRENT_MODE_MISMATCH", expected, observed, verified, instanceId, keyId);
        }
        if (!material.transitionAllowed() || !material.blockers().isEmpty()) {
            return rejected("PROOF_TRANSITION_BLOCKED", expected, observed, verified, instanceId, keyId);
        }
        WorkerQuarantineRequestIndexReplicaProof.Inventory inventory = material.inventory();
        boolean compatible = policy.targetMode()
                == WorkerQuarantineRequestIndexReplicaProof.Mode.DUAL_READ_KEYED_WRITE
                ? inventory.liveKeyedRows() == 0 : inventory.liveLegacyRows() == 0;
        if (!compatible) {
            return rejected("PROOF_INVENTORY_TARGET_INCOMPATIBLE",
                    expected, observed, verified, instanceId, keyId);
        }

        Instant now = clock.instant();
        Duration ttl = Duration.between(inventory.observedAt(), material.expiresAt());
        if (inventory.observedAt().isAfter(now.plus(CLOCK_SKEW))) {
            return rejected("PROOF_OBSERVATION_NOT_YET_VALID",
                    expected, observed, verified, instanceId, keyId);
        }
        if (!material.expiresAt().isAfter(now)) {
            return rejected("PROOF_STALE", expected, observed, verified, instanceId, keyId);
        }
        if (ttl.compareTo(MIN_PROOF_TTL) < 0 || ttl.compareTo(MAX_PROOF_TTL) > 0
                || ttl.getNano() != 0) {
            return rejected("PROOF_TTL_POLICY_REJECTED",
                    expected, observed, verified, instanceId, keyId);
        }
        Instant signedAt = proof.seal().signedAt();
        if (signedAt.isBefore(inventory.observedAt().minus(CLOCK_SKEW))
                || !signedAt.isBefore(material.expiresAt())
                || signedAt.isAfter(now.plus(CLOCK_SKEW))) {
            return rejected("PROOF_SIGNING_TIME_REJECTED",
                    expected, observed, verified, instanceId, keyId);
        }

        String recomputed;
        try {
            recomputed = sha256(proof.exactMaterial());
        } catch (RuntimeException failure) {
            return invalid("PROOF_MATERIAL_INVALID", expected, observed, verified, instanceId, keyId);
        }
        if (!recomputed.equals(proof.materialFingerprint())) {
            return invalid("PROOF_MATERIAL_FINGERPRINT_INVALID",
                    expected, observed, verified, instanceId, keyId);
        }

        EvidenceVerificationKeySet.KeyPolicy key = keys.get(keyId);
        if (key == null) {
            return result(Outcome.KEY_UNAVAILABLE, "PROOF_VERIFICATION_KEY_UNAVAILABLE",
                    expected, observed, verified, instanceId, keyId);
        }
        if (!keySet.activeKeyId().equals(keyId)
                || key.state() != EvidenceVerificationKeySet.KeyState.ACTIVE
                || !"Ed25519".equals(key.algorithm())
                || !proof.seal().algorithm().equals(key.algorithm())) {
            return rejected("PROOF_VERIFICATION_KEY_POLICY_REJECTED",
                    expected, observed, verified, instanceId, keyId);
        }
        if (signedAt.isBefore(key.notBefore().minus(CLOCK_SKEW))
                || (key.notAfter() != null && !signedAt.isBefore(key.notAfter()))) {
            return rejected("PROOF_KEY_NOT_VALID_AT_SIGNING_TIME",
                    expected, observed, verified, instanceId, keyId);
        }
        try {
            if (!verifyEd25519(proof.materialFingerprint(), proof.seal().signature(),
                    key.encodedPublicKey())) {
                return invalid("PROOF_SIGNATURE_INVALID",
                        expected, observed, verified, instanceId, keyId);
            }
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return invalid("PROOF_SIGNATURE_INVALID",
                    expected, observed, verified, instanceId, keyId);
        }
        return null;
    }

    private static String firstMissing(
            WorkerQuarantineRequestIndexFleetPolicy policy,
            Map<String, WorkerQuarantineRequestIndexReplicaProof> observed) {
        return policy.expectedInstanceIds().stream()
                .filter(instance -> !observed.containsKey(instance)).findFirst().orElse("");
    }

    private static Outcome map(TestSuiteEvidenceVerifier.Outcome outcome) {
        return switch (outcome) {
            case VERIFIED -> Outcome.VERIFIED;
            case INVALID -> Outcome.INVALID;
            case KEY_UNAVAILABLE -> Outcome.KEY_UNAVAILABLE;
            case POLICY_REJECTED -> Outcome.POLICY_REJECTED;
        };
    }

    private static boolean verifyEd25519(
            String materialFingerprint,
            String encodedSignature,
            String encodedPublicKey) throws GeneralSecurityException {
        byte[] publicKey = Base64.getDecoder().decode(encodedPublicKey);
        byte[] signature = Base64.getDecoder().decode(encodedSignature);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signature);
    }

    private static String sha256(JsonNode value) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical(value));
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | GeneralSecurityException failure) {
            throw new IllegalArgumentException("Canonical rollout proof cannot be fingerprinted");
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static VerificationResult rejected(
            String reason, int expected, int observed, int verified, String instanceId, String keyId) {
        return result(Outcome.POLICY_REJECTED, reason,
                expected, observed, verified, instanceId, keyId);
    }

    private static VerificationResult invalid(
            String reason, int expected, int observed, int verified, String instanceId, String keyId) {
        return result(Outcome.INVALID, reason, expected, observed, verified, instanceId, keyId);
    }

    private static VerificationResult result(
            Outcome outcome, String reason, int expected, int observed, int verified,
            String instanceId, String keyId) {
        return new VerificationResult(
                outcome, reason, expected, observed, verified, instanceId, keyId);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
