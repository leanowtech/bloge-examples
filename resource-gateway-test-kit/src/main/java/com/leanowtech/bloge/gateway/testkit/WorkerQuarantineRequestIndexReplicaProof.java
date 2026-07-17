package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Schema-validated, signed rollout facts emitted by one exact Resource Gateway process start.
 *
 * <p>The projection contains deployment identity and payload-free request-index generation counts.
 * It deliberately retains the exact wire material internally so an offline verifier can recompute
 * the producer's canonical fingerprint without normalizing timestamps or other JSON scalars.</p>
 */
public final class WorkerQuarantineRequestIndexReplicaProof {

    /** Closed request-index format modes understood by the rollout protocol. */
    public enum Mode {
        /** Previous-binary-compatible reads and writes. */
        LEGACY_READ_WRITE,
        /** Legacy/keyed reads with keyed writes. */
        DUAL_READ_KEYED_WRITE,
        /** Keyed reads and writes only. */
        KEYED_ONLY
    }

    /** Closed signed reasons preventing one immediate mode transition. */
    public enum Blocker {
        /** The process is not running the target mode's immediate predecessor. */
        CURRENT_MODE_NOT_PREDECESSOR,
        /** Keyed rows make the first keyed-write cutover unsafe for an N-1 rollback. */
        LIVE_KEYED_ROWS_PRESENT,
        /** Legacy rows still require dual-read compatibility. */
        LIVE_LEGACY_ROWS_PRESENT
    }

    /**
     * Live payload-free rows for one HMAC key generation.
     *
     * @param keyId non-secret configured key generation id
     * @param liveRows positive live-row count
     * @param latestExpiry latest expiry among those live rows
     */
    public record KeyGeneration(String keyId, long liveRows, Instant latestExpiry) {
        /** Validates one bounded generation aggregate. */
        public KeyGeneration {
            keyId = normalized(keyId);
            if (!keyId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,127}")
                    || liveRows < 1 || latestExpiry == null) {
                throw invalid();
            }
        }
    }

    /**
     * Database-clock inventory of every live request tombstone format and keyed generation.
     *
     * @param observedAt authoritative database observation time
     * @param liveLegacyRows live v1 row count
     * @param liveKeyedRows live v2 row count
     * @param latestLegacyExpiry latest live legacy expiry, or epoch when none exist
     * @param latestKeyedExpiry latest live keyed expiry, or epoch when none exist
     * @param keyedGenerations ordered keyed-generation aggregates
     */
    public record Inventory(
            Instant observedAt,
            long liveLegacyRows,
            long liveKeyedRows,
            Instant latestLegacyExpiry,
            Instant latestKeyedExpiry,
            List<KeyGeneration> keyedGenerations) {

        /** Enforces count, expiry, ordering, and protocol cardinality invariants. */
        public Inventory {
            keyedGenerations = keyedGenerations == null
                    ? List.of() : List.copyOf(keyedGenerations);
            if (observedAt == null || liveLegacyRows < 0 || liveKeyedRows < 0
                    || latestLegacyExpiry == null || latestKeyedExpiry == null
                    || keyedGenerations.size() > 16
                    || !validExpiry(liveLegacyRows, latestLegacyExpiry, observedAt)
                    || !validExpiry(liveKeyedRows, latestKeyedExpiry, observedAt)) {
                throw invalid();
            }
            long keyedTotal = 0;
            String previousKeyId = "";
            Instant latestGenerationExpiry = Instant.EPOCH;
            for (KeyGeneration generation : keyedGenerations) {
                if (generation == null || !generation.latestExpiry().isAfter(observedAt)
                        || (!previousKeyId.isEmpty()
                        && previousKeyId.compareTo(generation.keyId()) >= 0)) {
                    throw invalid();
                }
                keyedTotal = Math.addExact(keyedTotal, generation.liveRows());
                if (generation.latestExpiry().isAfter(latestGenerationExpiry)) {
                    latestGenerationExpiry = generation.latestExpiry();
                }
                previousKeyId = generation.keyId();
            }
            if (keyedTotal != liveKeyedRows
                    || (liveKeyedRows == 0) != keyedGenerations.isEmpty()
                    || !latestGenerationExpiry.equals(latestKeyedExpiry)) {
                throw invalid();
            }
        }

        private static boolean validExpiry(long rows, Instant expiry, Instant observedAt) {
            return rows == 0 ? Instant.EPOCH.equals(expiry) : expiry.isAfter(observedAt);
        }
    }

    /**
     * Immutable facts covered by the proof fingerprint.
     *
     * @param schemaVersion signed material generation
     * @param challenge deployment-gate nonce
     * @param deploymentScopeFingerprint verified deployment scope fingerprint
     * @param instanceId stable deployment inventory id
     * @param startupId process-start UUID
     * @param artifactFingerprint immutable image or application digest
     * @param protocolVersion Resource Gateway integration protocol version
     * @param currentMode mode enforced by the signing process
     * @param targetMode requested immediate target mode
     * @param inventory database-clock live-row inventory
     * @param transitionAllowed producer's local transition decision
     * @param blockers closed signed blockers
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
            Mode currentMode,
            Mode targetMode,
            Inventory inventory,
            boolean transitionAllowed,
            List<Blocker> blockers,
            Instant expiresAt) {

        /** Enforces strict signed-material invariants before policy verification. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            challenge = normalized(challenge);
            deploymentScopeFingerprint = normalized(deploymentScopeFingerprint);
            instanceId = normalized(instanceId);
            startupId = normalized(startupId);
            artifactFingerprint = normalized(artifactFingerprint);
            protocolVersion = normalized(protocolVersion);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            if (!TestingProtocol.WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_MATERIAL_V1
                    .equals(schemaVersion)
                    || !challenge.matches("[A-Za-z0-9_-]{32,128}")
                    || !fingerprint(deploymentScopeFingerprint)
                    || !instanceId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                    || !uuid(startupId) || !fingerprint(artifactFingerprint)
                    || protocolVersion.isBlank() || protocolVersion.length() > 64
                    || currentMode == null || targetMode == null
                    || targetMode == Mode.LEGACY_READ_WRITE || inventory == null
                    || expiresAt == null || !expiresAt.isAfter(inventory.observedAt())
                    || !canonicalBlockers(blockers)
                    || transitionAllowed != blockers.isEmpty()) {
                throw invalid();
            }
        }
    }

    /**
     * Detached Ed25519 seal over the material fingerprint.
     *
     * @param schemaVersion evidence seal version
     * @param materialFingerprint exact signed fingerprint
     * @param algorithm signature algorithm
     * @param keyId signing key id
     * @param signedAt signature creation time
     * @param signature base64 detached signature
     */
    public record Seal(
            String schemaVersion,
            String materialFingerprint,
            String algorithm,
            String keyId,
            Instant signedAt,
            String signature) {

        /** Validates the bounded detached-signature envelope. */
        public Seal {
            schemaVersion = normalized(schemaVersion);
            materialFingerprint = normalized(materialFingerprint);
            algorithm = normalized(algorithm);
            keyId = normalized(keyId);
            signature = normalized(signature);
            if (!"bloge.visualRunEvidenceSeal.v1".equals(schemaVersion)
                    || !fingerprint(materialFingerprint) || !"Ed25519".equals(algorithm)
                    || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                    || signedAt == null
                    || signature.isBlank() || signature.length() > 4096) {
                throw invalid();
            }
        }
    }

    private final String schemaVersion;
    private final Material material;
    private final String materialFingerprint;
    private final Seal seal;
    private final JsonNode exactMaterial;

    private WorkerQuarantineRequestIndexReplicaProof(
            String schemaVersion,
            Material material,
            String materialFingerprint,
            Seal seal,
            JsonNode exactMaterial) {
        this.schemaVersion = normalized(schemaVersion);
        this.material = material;
        this.materialFingerprint = normalized(materialFingerprint);
        this.seal = seal;
        this.exactMaterial = exactMaterial.deepCopy();
        if (!TestingProtocol.WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_V1
                .equals(this.schemaVersion) || material == null
                || !fingerprint(this.materialFingerprint) || seal == null
                || !this.materialFingerprint.equals(seal.materialFingerprint())) {
            throw invalid();
        }
    }

    /**
     * Decodes and cross-validates one strict proof response.
     *
     * @param value untrusted JSON response
     * @return typed proof retaining exact canonical material
     */
    public static WorkerQuarantineRequestIndexReplicaProof from(JsonNode value) {
        try {
            TestingProtocolSchemaValidator.require(
                    value, "workerQuarantineRequestIndexReplicaProof");
            JsonNode rawMaterial = value.path("material");
            JsonNode rawInventory = rawMaterial.path("inventory");
            List<KeyGeneration> generations = new ArrayList<>();
            rawInventory.path("keyedGenerations").forEach(generation -> generations.add(
                    new KeyGeneration(generation.path("keyId").asText(),
                            generation.path("liveRows").asLong(),
                            instant(generation.path("latestExpiry")))));
            Inventory inventory = new Inventory(instant(rawInventory.path("observedAt")),
                    rawInventory.path("liveLegacyRows").asLong(),
                    rawInventory.path("liveKeyedRows").asLong(),
                    instant(rawInventory.path("latestLegacyExpiry")),
                    instant(rawInventory.path("latestKeyedExpiry")), generations);
            List<Blocker> blockers = new ArrayList<>();
            rawMaterial.path("blockers").forEach(blocker -> blockers.add(
                    enumValue(Blocker.class, blocker.asText())));
            Material material = new Material(rawMaterial.path("schemaVersion").asText(),
                    rawMaterial.path("challenge").asText(),
                    rawMaterial.path("deploymentScopeFingerprint").asText(),
                    rawMaterial.path("instanceId").asText(),
                    rawMaterial.path("startupId").asText(),
                    rawMaterial.path("artifactFingerprint").asText(),
                    rawMaterial.path("protocolVersion").asText(),
                    enumValue(Mode.class, rawMaterial.path("currentMode").asText()),
                    enumValue(Mode.class, rawMaterial.path("targetMode").asText()),
                    inventory, rawMaterial.path("transitionAllowed").asBoolean(), blockers,
                    instant(rawMaterial.path("expiresAt")));
            JsonNode rawSeal = value.path("seal");
            Seal seal = new Seal(rawSeal.path("schemaVersion").asText(),
                    rawSeal.path("materialFingerprint").asText(),
                    rawSeal.path("algorithm").asText(), rawSeal.path("keyId").asText(),
                    instant(rawSeal.path("signedAt")), rawSeal.path("signature").asText());
            return new WorkerQuarantineRequestIndexReplicaProof(
                    value.path("schemaVersion").asText(), material,
                    value.path("materialFingerprint").asText(), seal, rawMaterial);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * Returns the proof envelope schema version.
     *
     * @return proof envelope schema version
     */
    public String schemaVersion() {
        return schemaVersion;
    }

    /**
     * Returns the immutable signed material projection.
     *
     * @return immutable signed material projection
     */
    public Material material() {
        return material;
    }

    /**
     * Returns the producer's claimed canonical material fingerprint.
     *
     * @return claimed canonical material fingerprint
     */
    public String materialFingerprint() {
        return materialFingerprint;
    }

    /**
     * Returns the detached signature seal.
     *
     * @return detached signature seal
     */
    public Seal seal() {
        return seal;
    }

    JsonNode exactMaterial() {
        return exactMaterial.deepCopy();
    }

    private static boolean canonicalBlockers(List<Blocker> blockers) {
        int previous = -1;
        for (Blocker blocker : blockers) {
            if (blocker == null || blocker.ordinal() <= previous) {
                return false;
            }
            previous = blocker.ordinal();
        }
        return true;
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException failure) {
            throw invalid();
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static boolean fingerprint(String value) {
        return value.matches("sha256:[a-f0-9]{64}");
    }

    private static boolean uuid(String value) {
        try {
            return java.util.UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Request-index replica proof is invalid");
    }
}
