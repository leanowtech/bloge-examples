package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Atomic dual-quorum publication of recovery-fleet inventory and witness runtime keys.
 *
 * <p>Deployment-root and witness-root authorities sign the same canonical material. A rotation,
 * threshold change, emergency revocation, or runtime trust-domain change therefore updates both
 * independent verifier sets as one indivisible generation. The protocol contains public
 * verification material only; signer private keys and recovery payloads are forbidden.</p>
 *
 * @param schemaVersion envelope protocol generation
 * @param material exact atomic dual key-set statement
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param deploymentRootSignatures deployment bootstrap-root M-of-N signatures
 * @param witnessRootSignatures independent witness bootstrap-root M-of-N signatures
 */
public record ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature> deploymentRootSignatures,
        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature> witnessRootSignatures) {

    /** Current atomic recovery-fleet dual trust-root publication generation. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects partial, unbounded, non-canonical, or authority-duplicated envelopes. */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication {
        schemaVersion = normalized(schemaVersion);
        materialFingerprint = normalized(materialFingerprint);
        deploymentRootSignatures = immutableSignatures(deploymentRootSignatures);
        witnessRootSignatures = immutableSignatures(witnessRootSignatures);
        if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                || !FINGERPRINT.matcher(materialFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root publication envelope is invalid");
        }
    }

    /**
     * Checks the supplied material fingerprint against canonical protocol serialization.
     *
     * @param objectMapper canonical protocol mapper
     * @return true only when the material fingerprint is exact
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /**
     * Canonical dual runtime-key statement approved by independent bootstrap-root quorums.
     *
     * @param schemaVersion material protocol generation
     * @param trustRootSetId stable managed dual key-set identity
     * @param sequence monotonic publication sequence
     * @param previousMaterialFingerprint exact predecessor, blank at sequence one
     * @param deploymentScopeId stable tenant and environment deployment scope
     * @param fleetId stable recovery-fleet identity
     * @param protocolVersion exact recovery-fleet inventory-publication protocol
     * @param deploymentRootTrustDomain deployment bootstrap-root trust domain
     * @param witnessRootTrustDomain independent witness bootstrap-root trust domain
     * @param deploymentTrustDomain runtime inventory/publication signer domain
     * @param witnessTrustDomain runtime publication-witness signer domain
     * @param deploymentSignatureThreshold runtime deployment signature threshold
     * @param witnessSignatureThreshold runtime witness signature threshold
     * @param deploymentKeys canonical deployment runtime verification keys
     * @param witnessKeys canonical witness runtime verification keys
     * @param policyFingerprint externally governed key-rotation policy
     * @param issuedAt issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive validity deadline
     */
    public record Material(
            String schemaVersion,
            String trustRootSetId,
            long sequence,
            String previousMaterialFingerprint,
            String deploymentScopeId,
            String fleetId,
            String protocolVersion,
            String deploymentRootTrustDomain,
            String witnessRootTrustDomain,
            String deploymentTrustDomain,
            String witnessTrustDomain,
            int deploymentSignatureThreshold,
            int witnessSignatureThreshold,
            List<AuthorityKeyMaterial> deploymentKeys,
            List<AuthorityKeyMaterial> witnessKeys,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current signed recovery-fleet dual trust-root material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootMaterial.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces a complete, canonical, independently governed dual key-set generation. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustRootSetId = normalized(trustRootSetId);
            previousMaterialFingerprint = normalized(previousMaterialFingerprint);
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
            protocolVersion = normalized(protocolVersion);
            deploymentRootTrustDomain = normalized(deploymentRootTrustDomain);
            witnessRootTrustDomain = normalized(witnessRootTrustDomain);
            deploymentTrustDomain = normalized(deploymentTrustDomain);
            witnessTrustDomain = normalized(witnessTrustDomain);
            policyFingerprint = normalized(policyFingerprint);
            deploymentKeys = immutableKeys(deploymentKeys);
            witnessKeys = immutableKeys(witnessKeys);
            boolean predecessorShape = sequence == 1 && previousMaterialFingerprint.isEmpty()
                    || sequence > 1
                    && FINGERPRINT.matcher(previousMaterialFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustRootSetId).matches()
                    || sequence < 1 || !predecessorShape
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || !ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                    .SCHEMA_VERSION.equals(protocolVersion)
                    || !IDENTIFIER.matcher(deploymentRootTrustDomain).matches()
                    || !IDENTIFIER.matcher(witnessRootTrustDomain).matches()
                    || !IDENTIFIER.matcher(deploymentTrustDomain).matches()
                    || !IDENTIFIER.matcher(witnessTrustDomain).matches()
                    || !independentTrustDomains(deploymentRootTrustDomain,
                    witnessRootTrustDomain, deploymentTrustDomain, witnessTrustDomain)
                    || deploymentSignatureThreshold < 1
                    || deploymentSignatureThreshold > distinctAuthorities(deploymentKeys)
                    || witnessSignatureThreshold < 1
                    || witnessSignatureThreshold > distinctAuthorities(witnessKeys)
                    || !independent(deploymentKeys, witnessKeys)
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory trust-root publication material is invalid");
            }
        }
    }

    /**
     * One runtime Ed25519 verification key governed by the atomic dual-root publication.
     *
     * @param authorityId stable independent signer authority
     * @param keyId rotation-aware key identity
     * @param publicKeyBase64 X.509-encoded public Ed25519 key
     * @param notBefore inclusive signing activation time
     * @param expiresAt exclusive signing expiry time
     * @param enabled administrative enablement
     * @param revoked compromise or withdrawal state
     */
    public record AuthorityKeyMaterial(
            String authorityId,
            String keyId,
            String publicKeyBase64,
            Instant notBefore,
            Instant expiresAt,
            boolean enabled,
            boolean revoked) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects malformed identity, lifecycle, or non-Ed25519 public material. */
        public AuthorityKeyMaterial {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            publicKeyBase64 = normalized(publicKeyBase64);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches()
                    || !wholeSecond(notBefore) || !wholeSecond(expiresAt)
                    || !expiresAt.isAfter(notBefore)
                    || !validPublicKeyShape(publicKeyBase64)) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory trust-root authority key is invalid");
            }
        }

        private String canonicalIdentity() {
            return authorityId + '\u0000' + keyId;
        }
    }

    private static List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
            .AuthoritySignature> immutableSignatures(
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                    .AuthoritySignature> values) {
        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature> result = values == null ? List.of() : List.copyOf(values);
        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature> sorted = result.stream()
                .sorted(Comparator.comparing(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                .AuthoritySignature::authorityId)
                        .thenComparing(
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                        .AuthoritySignature::keyId))
                .toList();
        Set<String> authorities = new HashSet<>();
        if (result.isEmpty() || result.size() > 32 || !result.equals(sorted)
                || result.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root signatures are invalid");
        }
        return result;
    }

    private static List<AuthorityKeyMaterial> immutableKeys(List<AuthorityKeyMaterial> values) {
        List<AuthorityKeyMaterial> result = values == null ? List.of() : List.copyOf(values);
        List<AuthorityKeyMaterial> sorted = result.stream()
                .sorted(Comparator.comparing(AuthorityKeyMaterial::authorityId)
                        .thenComparing(AuthorityKeyMaterial::keyId))
                .toList();
        Set<String> identities = new HashSet<>();
        if (result.isEmpty() || result.size() > 64 || !result.equals(sorted)
                || result.stream().anyMatch(key -> !identities.add(key.canonicalIdentity()))) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root key set is invalid");
        }
        return result;
    }

    private static int distinctAuthorities(List<AuthorityKeyMaterial> keys) {
        return (int) keys.stream().map(AuthorityKeyMaterial::authorityId).distinct().count();
    }

    private static boolean independent(
            List<AuthorityKeyMaterial> deployment,
            List<AuthorityKeyMaterial> witness) {
        Set<String> authorityIds = new HashSet<>();
        Set<String> publicKeys = new HashSet<>();
        deployment.forEach(key -> {
            authorityIds.add(key.authorityId());
            publicKeys.add(key.publicKeyBase64());
        });
        return witness.stream().noneMatch(key -> authorityIds.contains(key.authorityId())
                || publicKeys.contains(key.publicKeyBase64()));
    }

    private static boolean independentTrustDomains(String... values) {
        return new HashSet<>(List.of(values)).size() == values.length;
    }

    private static boolean validPublicKeyShape(String encoded) {
        try {
            return encoded.length() <= 512 && Base64.getDecoder().decode(encoded).length == 44;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
