package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed operational publication for one exact bootstrap-root recovery fleet inventory.
 *
 * <p>The nested attestation proves which lanes belong to the fleet. Publication material proves
 * whether that exact attestation is currently active or revoked, while an independently signed
 * witness checkpoint binds the publication into a second predecessor chain. Both chains name the
 * stable deployment scope and fleet so a valid document cannot be replayed into another recovery
 * domain.</p>
 *
 * @param schemaVersion publication envelope generation
 * @param inventory independently signed exact fleet inventory
 * @param material signed operational state and publication predecessor
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param signatures distinct deployment-authority signatures
 * @param witness independent witness checkpoint for the same publication
 */
public record ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
        String schemaVersion,
        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation inventory,
        Material material,
        String materialFingerprint,
        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature> signatures,
        WitnessCheckpoint witness) {

    /** Current remote publication document generation. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects partial, non-canonical, or cross-linked publication envelopes. */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication {
        schemaVersion = normalized(schemaVersion);
        materialFingerprint = normalized(materialFingerprint);
        signatures = canonicalSignatures(signatures, "publication");
        if (!SCHEMA_VERSION.equals(schemaVersion) || inventory == null || material == null
                || witness == null || !FINGERPRINT.matcher(materialFingerprint).matches()
                || !inventory.materialFingerprint().equals(
                material.inventoryMaterialFingerprint())
                || material.sequence() != witness.material().sequence()
                || !material.deploymentScopeId().equals(
                witness.material().deploymentScopeId())
                || !material.fleetId().equals(witness.material().fleetId())
                || !materialFingerprint.equals(
                witness.material().publicationMaterialFingerprint())) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet inventory publication is invalid");
        }
    }

    /**
     * Recomputes the canonical publication material identity.
     *
     * @param objectMapper canonical protocol mapper
     * @return true when canonical material bytes reproduce the declared fingerprint
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material));
    }

    /** Operational authorization carried by a signed publication. */
    public enum State {
        /** The exact nested inventory remains authorized for recovery. */
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
     * @param deploymentScopeId exact stable deployment scope
     * @param fleetId exact durable recovery fleet identity
     * @param sequence monotonic sequence within the stable fleet scope
     * @param inventoryMaterialFingerprint exact nested inventory identity
     * @param state active or explicitly revoked state
     * @param policyFingerprint accepted publication policy
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
            String deploymentScopeId,
            String fleetId,
            long sequence,
            String inventoryMaterialFingerprint,
            State state,
            String policyFingerprint,
            String previousPublicationFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            String reasonCode) {

        /** Current publication material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationMaterial.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Enforces canonical identity, chain, state, and time shape before verification. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            publicationId = normalized(publicationId);
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
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
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(inventoryMaterialFingerprint).matches()
                    || state == null
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !chainValid || !stateValid
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory publication material is invalid");
            }
        }
    }

    /**
     * Independently signed checkpoint for one publication material fingerprint.
     *
     * @param schemaVersion witness envelope generation
     * @param material canonical witness material
     * @param materialFingerprint canonical SHA-256 identity of witness material
     * @param signatures distinct witness-authority signatures
     */
    public record WitnessCheckpoint(
            String schemaVersion,
            WitnessMaterial material,
            String materialFingerprint,
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                    .AuthoritySignature> signatures) {

        /** Current witness checkpoint generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryWitness.v1";

        /** Rejects ambiguous checkpoint envelopes before trust evaluation. */
        public WitnessCheckpoint {
            schemaVersion = normalized(schemaVersion);
            materialFingerprint = normalized(materialFingerprint);
            signatures = canonicalSignatures(signatures, "witness");
            if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                    || !FINGERPRINT.matcher(materialFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory witness is invalid");
            }
        }

        /**
         * Recomputes the canonical witness material identity.
         *
         * @param objectMapper canonical protocol mapper
         * @return true when canonical witness bytes reproduce the declared fingerprint
         */
        public boolean fingerprintVerified(ObjectMapper objectMapper) {
            return materialFingerprint.equals(ProtocolFingerprint.of(
                    Objects.requireNonNull(objectMapper, "objectMapper"), material));
        }
    }

    /**
     * Canonical independent witness statement.
     *
     * @param schemaVersion witness material generation
     * @param witnessDomain independent witness trust domain
     * @param checkpointId unique checkpoint identity
     * @param deploymentScopeId exact stable deployment scope
     * @param fleetId exact durable recovery fleet identity
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
            String deploymentScopeId,
            String fleetId,
            long sequence,
            String publicationMaterialFingerprint,
            String previousWitnessFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current witness material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryWitnessMaterial.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces canonical witness identity, scope, predecessor, and validity shape. */
        public WitnessMaterial {
            schemaVersion = normalized(schemaVersion);
            witnessDomain = normalized(witnessDomain);
            checkpointId = normalized(checkpointId);
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
            publicationMaterialFingerprint = normalized(publicationMaterialFingerprint);
            previousWitnessFingerprint = normalized(previousWitnessFingerprint);
            boolean chainValid = sequence == 1
                    ? previousWitnessFingerprint.isEmpty()
                    : FINGERPRINT.matcher(previousWitnessFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(witnessDomain).matches()
                    || !IDENTIFIER.matcher(checkpointId).matches()
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(publicationMaterialFingerprint).matches()
                    || !chainValid
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory witness material is invalid");
            }
        }
    }

    private static List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
            .AuthoritySignature> canonicalSignatures(
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                    .AuthoritySignature> values,
            String label) {
        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature> signatures = values == null ? List.of()
                : List.copyOf(values);
        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature> ordered = signatures.stream()
                .sorted(Comparator.comparing(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                .AuthoritySignature::authorityId)
                        .thenComparing(
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                        .AuthoritySignature::keyId))
                .toList();
        Set<String> authorities = new HashSet<>();
        if (signatures.isEmpty() || signatures.size() > 32
                || !ordered.equals(signatures)
                || signatures.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet inventory " + label
                            + " signatures are invalid");
        }
        return signatures;
    }

    private static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
