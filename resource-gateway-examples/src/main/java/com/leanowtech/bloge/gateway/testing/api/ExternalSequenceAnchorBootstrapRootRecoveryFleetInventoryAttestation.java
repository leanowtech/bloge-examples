package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deployment-signed authorization for one exact bootstrap-root recovery fleet inventory.
 *
 * <p>The attestation contains public governance material only. It binds an immutable fleet
 * topology and the complete canonical lane descriptor set to one deployment artifact and policy.
 * It never carries a service object, authority resolver, endpoint, credential, private key, root
 * key, or provider payload. Runtime objects are resolved independently from a reviewed local
 * catalog after signature verification and must reproduce every signed descriptor exactly.</p>
 *
 * @param schemaVersion signed envelope protocol generation
 * @param material exact deployment-owned fleet inventory statement
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param signatures canonical signatures from distinct inventory authorities
 */
public record ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<AuthoritySignature> signatures) {

    /** Current signed recovery-fleet inventory envelope generation. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects ambiguous, unbounded, or non-canonical signed envelopes. */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation {
        schemaVersion = normalized(schemaVersion);
        materialFingerprint = normalized(materialFingerprint);
        List<AuthoritySignature> supplied = signatures == null
                ? List.of() : new ArrayList<>(signatures);
        boolean signaturesPresent = supplied.stream().noneMatch(Objects::isNull);
        List<AuthoritySignature> ordered = signaturesPresent ? supplied.stream()
                .sorted(Comparator.comparing(AuthoritySignature::authorityId)
                        .thenComparing(AuthoritySignature::keyId))
                .toList() : List.of();
        Set<String> authorities = new HashSet<>();
        if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                || !FINGERPRINT.matcher(materialFingerprint).matches()
                || supplied.isEmpty() || supplied.size() > 32
                || !signaturesPresent || !ordered.equals(supplied)
                || supplied.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet inventory attestation is invalid");
        }
        signatures = List.copyOf(supplied);
    }

    /**
     * Recomputes the signed material identity without trusting the envelope fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @return whether the supplied fingerprint identifies the exact material
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /**
     * Canonical deployment statement covered by every detached signature.
     *
     * @param schemaVersion material protocol generation
     * @param trustDomain independent fleet-inventory trust domain
     * @param inventoryId unique attestation identity for audit correlation
     * @param generation strictly monotonic inventory generation
     * @param deploymentScopeId stable tenant/environment deployment scope
     * @param fleetId exact durable scheduler fleet identity
     * @param artifactFingerprint exact application image or artifact SHA-256
     * @param partitionCount immutable durable fleet partition count
     * @param laneDescriptors sorted complete public recovery lane inventory
     * @param policyFingerprint accepted external inventory policy revision
     * @param issuedAt inventory issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive hard validity deadline
     */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String inventoryId,
            long generation,
            String deploymentScopeId,
            String fleetId,
            String artifactFingerprint,
            int partitionCount,
            List<LaneDescriptor> laneDescriptors,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current signed recovery-fleet inventory material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryMaterial.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects non-canonical topology, descriptor, policy, and time material. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            inventoryId = normalized(inventoryId);
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
            artifactFingerprint = normalized(artifactFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            List<LaneDescriptor> supplied = laneDescriptors == null
                    ? List.of() : new ArrayList<>(laneDescriptors);
            boolean lanesPresent = supplied.stream().noneMatch(Objects::isNull);
            List<LaneDescriptor> canonical = lanesPresent
                    ? supplied.stream()
                    .sorted(Comparator.comparing(LaneDescriptor::key)).toList()
                    : List.of();
            Set<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey> keys =
                    new HashSet<>();
            boolean unique = lanesPresent && supplied.stream()
                    .allMatch(descriptor -> keys.add(descriptor.key()));
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(inventoryId).matches()
                    || generation < 1L
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || !FINGERPRINT.matcher(artifactFingerprint).matches()
                    || partitionCount < 1
                    || partitionCount
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                    .MAXIMUM_PARTITIONS
                    || supplied.size()
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.MAXIMUM_LANES
                    || !lanesPresent || !unique || !canonical.equals(supplied)
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory material is invalid");
            }
            laneDescriptors = List.copyOf(supplied);
        }
    }

    /**
     * One detached Ed25519 signature from a distinct deployment authority.
     *
     * @param authorityId stable inventory authority identity
     * @param keyId rotation-aware public-key identity
     * @param algorithm fixed {@code Ed25519} algorithm
     * @param signedAt signature creation time
     * @param signature base64-encoded 64-byte Ed25519 signature
     */
    public record AuthoritySignature(
            String authorityId,
            String keyId,
            String algorithm,
            Instant signedAt,
            String signature) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Validates bounded detached-signature shape before cryptographic verification. */
        public AuthoritySignature {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches()
                    || !"Ed25519".equals(algorithm) || !wholeSecond(signedAt)
                    || !validSignature(signature)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory signature is invalid");
            }
        }

        private static boolean validSignature(String encoded) {
            try {
                return Base64.getDecoder().decode(encoded).length == 64;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
    }

    private static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
