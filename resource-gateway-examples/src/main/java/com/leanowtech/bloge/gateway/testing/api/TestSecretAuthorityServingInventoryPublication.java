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
 * Signed operational publication for one exact test-secret authority serving inventory.
 *
 * <p>The nested inventory fixes the deployment topology and exact test-secret authority identity.
 * Publication material independently authorizes or revokes that inventory. A second trust domain
 * signs a witness checkpoint over the publication, so one compromised publication source cannot
 * silently present unrelated chain heads to different Resource Gateway replicas.</p>
 *
 * @param schemaVersion publication document generation
 * @param inventory independently signed exact test-secret serving inventory
 * @param material signed active-or-revoked publication state
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param signatures distinct deployment-authority signatures
 * @param witness independent witness checkpoint for this publication
 */
public record TestSecretAuthorityServingInventoryPublication(
        String schemaVersion,
        TestSecretAuthorityServingInventory inventory,
        Material material,
        String materialFingerprint,
        List<TestSecretAuthorityServingInventory.AuthoritySignature> signatures,
        WitnessCheckpoint witness) {

    /** Current remote test-secret serving-inventory publication generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSecretAuthorityServingInventoryPublication.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects structurally ambiguous or cross-linked publication documents. */
    public TestSecretAuthorityServingInventoryPublication {
        schemaVersion = normalized(schemaVersion);
        materialFingerprint = normalized(materialFingerprint);
        signatures = canonicalSignatures(signatures, "publication");
        if (!SCHEMA_VERSION.equals(schemaVersion) || inventory == null || material == null
                || witness == null || !FINGERPRINT.matcher(materialFingerprint).matches()
                || !inventory.materialFingerprint().equals(
                material.inventoryMaterialFingerprint())
                || material.sequence() != witness.material().sequence()
                || !materialFingerprint.equals(
                witness.material().publicationMaterialFingerprint())) {
            throw new IllegalArgumentException(
                    "Test-secret serving-inventory publication is invalid");
        }
    }

    /** @return true when the supplied publication fingerprint matches canonical material bytes */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /** Operational state carried by a signed publication. */
    public enum State {
        /** The exact nested inventory remains authorized for serving. */
        ACTIVE,
        /** The exact nested inventory is explicitly withdrawn. */
        REVOKED
    }

    /**
     * Canonical publication state signed by deployment authorities.
     *
     * @param schemaVersion material generation
     * @param trustDomain deployment publication trust domain
     * @param publicationId unique publication identity
     * @param sequence monotonic sequence within the stable fleet scope
     * @param inventoryMaterialFingerprint exact nested inventory identity
     * @param state active or explicitly revoked state
     * @param policyFingerprint accepted external publication policy
     * @param previousPublicationFingerprint predecessor identity, blank only at sequence one
     * @param issuedAt issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive publication validity deadline
     * @param reasonCode empty for active state; stable reason for revocation
     */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String publicationId,
            long sequence,
            String inventoryMaterialFingerprint,
            State state,
            String policyFingerprint,
            String previousPublicationFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            String reasonCode) {

        /** Current test-secret publication material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityServingInventoryPublicationMaterial.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Enforces canonical identity, chain, state, and time shape before verification. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            publicationId = normalized(publicationId);
            inventoryMaterialFingerprint = normalized(inventoryMaterialFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            reasonCode = normalized(reasonCode);
            boolean chainValid = sequence == 1
                    ? previousPublicationFingerprint.isEmpty()
                    : FINGERPRINT.matcher(previousPublicationFingerprint).matches();
            boolean stateValid = state == State.ACTIVE && reasonCode.isEmpty()
                    || state == State.REVOKED && REASON.matcher(reasonCode).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(publicationId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(inventoryMaterialFingerprint).matches()
                    || state == null
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !chainValid || !stateValid
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Test-secret serving-inventory publication material is invalid");
            }
        }
    }

    /**
     * Independently signed witness checkpoint for one publication material fingerprint.
     *
     * @param schemaVersion checkpoint envelope generation
     * @param material canonical witness material
     * @param materialFingerprint canonical SHA-256 identity of witness material
     * @param signatures distinct witness-authority signatures
     */
    public record WitnessCheckpoint(
            String schemaVersion,
            WitnessMaterial material,
            String materialFingerprint,
            List<TestSecretAuthorityServingInventory.AuthoritySignature> signatures) {

        /** Current test-secret serving-inventory witness generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityServingInventoryWitness.v1";

        /** Rejects ambiguous checkpoint envelopes before trust evaluation. */
        public WitnessCheckpoint {
            schemaVersion = normalized(schemaVersion);
            materialFingerprint = normalized(materialFingerprint);
            signatures = canonicalSignatures(signatures, "witness");
            if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                    || !FINGERPRINT.matcher(materialFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Test-secret serving-inventory witness is invalid");
            }
        }

        /** @return true when checkpoint fingerprint matches canonical witness material */
        public boolean fingerprintVerified(ObjectMapper objectMapper) {
            return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
        }
    }

    /**
     * Canonical independent witness statement.
     *
     * @param schemaVersion witness material generation
     * @param witnessDomain independent witness trust domain
     * @param checkpointId unique witness checkpoint identity
     * @param sequence exact publication sequence being witnessed
     * @param publicationMaterialFingerprint exact publication identity
     * @param previousWitnessFingerprint predecessor checkpoint, blank only at sequence one
     * @param issuedAt witness issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive witness validity deadline
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

        /** Current test-secret serving-inventory witness material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityServingInventoryWitnessMaterial.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces canonical witness identity, predecessor, and validity shape. */
        public WitnessMaterial {
            schemaVersion = normalized(schemaVersion);
            witnessDomain = normalized(witnessDomain);
            checkpointId = normalized(checkpointId);
            publicationMaterialFingerprint = normalized(publicationMaterialFingerprint);
            previousWitnessFingerprint = normalized(previousWitnessFingerprint);
            boolean chainValid = sequence == 1
                    ? previousWitnessFingerprint.isEmpty()
                    : FINGERPRINT.matcher(previousWitnessFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(witnessDomain).matches()
                    || !IDENTIFIER.matcher(checkpointId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(publicationMaterialFingerprint).matches()
                    || !chainValid
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Test-secret serving-inventory witness material is invalid");
            }
        }
    }

    private static List<TestSecretAuthorityServingInventory.AuthoritySignature>
            canonicalSignatures(
            List<TestSecretAuthorityServingInventory.AuthoritySignature> values,
            String label) {
        List<TestSecretAuthorityServingInventory.AuthoritySignature> signatures =
                values == null ? List.of() : List.copyOf(values);
        List<TestSecretAuthorityServingInventory.AuthoritySignature> ordered = signatures.stream()
                .sorted(Comparator.comparing(
                        TestSecretAuthorityServingInventory.AuthoritySignature::authorityId)
                        .thenComparing(
                                TestSecretAuthorityServingInventory.AuthoritySignature::keyId))
                .toList();
        Set<String> authorities = new HashSet<>();
        if (signatures.isEmpty() || signatures.size() > 32
                || !ordered.equals(signatures)
                || signatures.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "Test-secret serving-inventory " + label + " signatures are invalid");
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
