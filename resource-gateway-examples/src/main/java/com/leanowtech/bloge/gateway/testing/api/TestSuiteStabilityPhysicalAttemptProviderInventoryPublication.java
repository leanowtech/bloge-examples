package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed lifecycle publication for one exact physical-attempt provider inventory.
 *
 * <p>The nested inventory proves which provider deployments may be resolved. Publication material
 * independently authorizes or revokes that exact inventory and signs the complete expected
 * Resource Gateway replica set. An independent witness checkpoint binds every publication into a
 * second monotonic predecessor chain.</p>
 *
 * @param schemaVersion publication envelope generation
 * @param inventory independently signed complete provider inventory
 * @param material signed lifecycle, cohort, and predecessor material
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param signatures sorted distinct deployment-authority signatures
 * @param witness independently signed publication checkpoint
 */
public record TestSuiteStabilityPhysicalAttemptProviderInventoryPublication(
        String schemaVersion,
        TestSuiteStabilityPhysicalAttemptProviderInventory inventory,
        Material material,
        String materialFingerprint,
        List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures,
        WitnessCheckpoint witness) {

    /** Current physical provider-inventory publication envelope generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryPublication.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects ambiguous envelopes and cross-linked nested material before trust evaluation. */
    public TestSuiteStabilityPhysicalAttemptProviderInventoryPublication {
        schemaVersion = normalized(schemaVersion);
        materialFingerprint = normalized(materialFingerprint);
        signatures = canonicalSignatures(signatures, "publication");
        if (!SCHEMA_VERSION.equals(schemaVersion) || inventory == null || material == null
                || witness == null || !FINGERPRINT.matcher(materialFingerprint).matches()
                || !inventory.materialFingerprint().equals(
                material.inventoryMaterialFingerprint())
                || !inventory.material().scopeId().equals(material.scopeId())
                || !inventory.material().cohortId().equals(material.cohortId())
                || material.sequence() != witness.material().sequence()
                || !materialFingerprint.equals(
                witness.material().publicationMaterialFingerprint())) {
            throw new IllegalArgumentException(
                    "Physical-attempt provider inventory publication is invalid");
        }
    }

    /**
     * Recomputes the canonical publication material identity.
     *
     * @param objectMapper canonical protocol mapper
     * @return true only when the envelope fingerprint matches the material
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /** Signed operational state of the nested inventory. */
    public enum State {
        /** The inventory and exact replica set may serve physical attempts. */
        ACTIVE,
        /** The inventory is explicitly withdrawn and all resolution must close. */
        REVOKED
    }

    /**
     * Canonical lifecycle and exact-cohort material signed by deployment authorities.
     *
     * @param schemaVersion material generation
     * @param trustDomain deployment publication trust domain
     * @param publicationId unique publication statement identity
     * @param sequence monotonic sequence within {@code scopeId}
     * @param scopeId stable physical provider-fleet scope
     * @param cohortId exact Resource Gateway rollout cohort
     * @param inventoryMaterialFingerprint exact nested inventory identity
     * @param expectedReplicaIds sorted complete Resource Gateway replica set
     * @param state active or explicitly revoked lifecycle state
     * @param policyFingerprint accepted external publication policy
     * @param previousPublicationFingerprint predecessor identity, blank only at sequence one
     * @param issuedAt issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive publication deadline
     * @param reasonCode blank for active state; stable reason for revocation
     */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String publicationId,
            long sequence,
            String scopeId,
            String cohortId,
            String inventoryMaterialFingerprint,
            List<String> expectedReplicaIds,
            State state,
            String policyFingerprint,
            String previousPublicationFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            String reasonCode) {

        /** Current physical provider-inventory publication material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryPublicationMaterial.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Enforces exact replica ordering, lifecycle shape, chain shape, and time bounds. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            publicationId = normalized(publicationId);
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            inventoryMaterialFingerprint = normalized(inventoryMaterialFingerprint);
            expectedReplicaIds = expectedReplicaIds == null
                    ? List.of() : expectedReplicaIds.stream().map(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryPublication::normalized)
                    .toList();
            policyFingerprint = normalized(policyFingerprint);
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            reasonCode = normalized(reasonCode);
            List<String> ordered = expectedReplicaIds.stream().sorted().toList();
            Set<String> unique = new HashSet<>(expectedReplicaIds);
            boolean predecessorValid = sequence == 1
                    ? previousPublicationFingerprint.isEmpty()
                    : FINGERPRINT.matcher(previousPublicationFingerprint).matches();
            boolean stateValid = state == State.ACTIVE && reasonCode.isEmpty()
                    || state == State.REVOKED && REASON.matcher(reasonCode).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(publicationId).matches()
                    || sequence < 1 || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(cohortId).matches()
                    || !FINGERPRINT.matcher(inventoryMaterialFingerprint).matches()
                    || expectedReplicaIds.isEmpty() || expectedReplicaIds.size() > 256
                    || unique.size() != expectedReplicaIds.size()
                    || !ordered.equals(expectedReplicaIds)
                    || expectedReplicaIds.stream().anyMatch(replica ->
                    !IDENTIFIER.matcher(replica).matches())
                    || state == null || !stateValid
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !predecessorValid || !wholeSecond(issuedAt)
                    || !wholeSecond(notBefore) || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory publication material is invalid");
            }
        }
    }

    /**
     * Independently signed witness checkpoint for one publication material identity.
     *
     * @param schemaVersion witness envelope generation
     * @param material canonical witness statement
     * @param materialFingerprint canonical SHA-256 identity of witness material
     * @param signatures sorted distinct witness-authority signatures
     */
    public record WitnessCheckpoint(
            String schemaVersion,
            WitnessMaterial material,
            String materialFingerprint,
            List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures) {

        /** Current physical provider-inventory witness envelope generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryWitness.v1";

        /** Rejects incomplete witness envelopes before cryptographic verification. */
        public WitnessCheckpoint {
            schemaVersion = normalized(schemaVersion);
            materialFingerprint = normalized(materialFingerprint);
            signatures = canonicalSignatures(signatures, "witness");
            if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                    || !FINGERPRINT.matcher(materialFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory witness is invalid");
            }
        }

        /**
         * Recomputes the canonical witness material identity.
         *
         * @param objectMapper canonical protocol mapper
         * @return true only when the envelope fingerprint matches the material
         */
        public boolean fingerprintVerified(ObjectMapper objectMapper) {
            return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
        }
    }

    /**
     * Canonical independent witness statement.
     *
     * @param schemaVersion witness material generation
     * @param witnessDomain independent witness trust domain
     * @param checkpointId unique checkpoint identity
     * @param sequence exact publication sequence being witnessed
     * @param publicationMaterialFingerprint exact publication material identity
     * @param previousWitnessFingerprint predecessor checkpoint, blank only at sequence one
     * @param issuedAt witness issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive witness deadline
     */
    public record WitnessMaterial(
            String schemaVersion,
            String witnessDomain,
            String checkpointId,
            long sequence,
            String publicationMaterialFingerprint,
            String previousWitnessFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current physical provider-inventory witness material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryWitnessMaterial.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces exact publication binding, predecessor shape, and validity bounds. */
        public WitnessMaterial {
            schemaVersion = normalized(schemaVersion);
            witnessDomain = normalized(witnessDomain);
            checkpointId = normalized(checkpointId);
            publicationMaterialFingerprint = normalized(publicationMaterialFingerprint);
            previousWitnessFingerprint = normalized(previousWitnessFingerprint);
            boolean predecessorValid = sequence == 1
                    ? previousWitnessFingerprint.isEmpty()
                    : FINGERPRINT.matcher(previousWitnessFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(witnessDomain).matches()
                    || !IDENTIFIER.matcher(checkpointId).matches() || sequence < 1
                    || !FINGERPRINT.matcher(publicationMaterialFingerprint).matches()
                    || !predecessorValid || !wholeSecond(issuedAt)
                    || !wholeSecond(notBefore) || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory witness material is invalid");
            }
        }
    }

    private static List<TestSuiteStabilityServingInventory.AuthoritySignature>
            canonicalSignatures(
            List<TestSuiteStabilityServingInventory.AuthoritySignature> values,
            String label) {
        List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures =
                values == null ? List.of() : List.copyOf(values);
        List<TestSuiteStabilityServingInventory.AuthoritySignature> ordered = signatures.stream()
                .sorted(Comparator.comparing(
                        TestSuiteStabilityServingInventory.AuthoritySignature::authorityId)
                        .thenComparing(
                                TestSuiteStabilityServingInventory.AuthoritySignature::keyId))
                .toList();
        Set<String> authorities = new HashSet<>();
        if (signatures.isEmpty() || signatures.size() > 32
                || !ordered.equals(signatures)
                || signatures.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "Physical-attempt provider inventory " + label
                            + " signatures are invalid");
        }
        return signatures;
    }

    private static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
