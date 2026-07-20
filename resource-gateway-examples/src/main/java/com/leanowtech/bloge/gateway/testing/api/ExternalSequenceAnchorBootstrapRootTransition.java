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
 * Cross-signed transition to one complete external-anchor bootstrap-root generation.
 *
 * <p>The current-root quorum authorizes the successor while the incoming-root quorum proves
 * possession of the replacement private keys. Both groups sign the same canonical material.
 * This prevents partial key-set publication and makes normal rotation replayable from genesis;
 * it cannot recover a deployment after the entire current quorum is irretrievably lost.</p>
 *
 * @param schemaVersion envelope protocol generation
 * @param material exact successor root statement
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param authorizingRootSignatures signatures from the preceding root generation
 * @param incomingRootSignatures proof-of-possession signatures from the successor generation
 */
public record ExternalSequenceAnchorBootstrapRootTransition(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<TestSuiteStabilityServingInventory.AuthoritySignature>
                authorizingRootSignatures,
        List<TestSuiteStabilityServingInventory.AuthoritySignature> incomingRootSignatures) {

    /** Current cross-signed root transition generation. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorBootstrapRootTransition.v1";
    /** Maximum distinct-authority signatures carried by either ceremony role. */
    public static final int MAXIMUM_SIGNATURES_PER_ROLE =
            ExternalSequenceAnchorBootstrapRootGenesis.MAXIMUM_SIGNATURE_THRESHOLD;

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects partial, duplicated, non-canonical, or unbounded transition envelopes. */
    public ExternalSequenceAnchorBootstrapRootTransition {
        schemaVersion = ExternalSequenceAnchorBootstrapRootGenesis.normalized(schemaVersion);
        materialFingerprint = ExternalSequenceAnchorBootstrapRootGenesis.normalized(
                materialFingerprint);
        authorizingRootSignatures = immutableSignatures(authorizingRootSignatures);
        incomingRootSignatures = immutableSignatures(incomingRootSignatures);
        if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                || !FINGERPRINT.matcher(materialFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "External sequence-anchor bootstrap-root transition is invalid");
        }
    }

    /** @return true only when the material fingerprint is canonical and exact */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /**
     * Canonical successor root statement.
     *
     * @param schemaVersion material protocol generation
     * @param rootSetId stable managed bootstrap-root set identity
     * @param sequence contiguous one-based transition generation
     * @param previousMaterialFingerprint genesis fingerprint at sequence one, prior transition later
     * @param scopeId stable Resource Gateway fleet scope
     * @param trustDomain independent bootstrap-root trust domain
     * @param signatureThreshold required distinct root-authority quorum
     * @param maximumFaults declared Byzantine fault bound
     * @param rootKeys complete canonical successor key set
     * @param policyFingerprint exact accepted ceremony policy
     * @param issuedAt ceremony issuance time
     * @param notBefore inclusive successor activation time
     * @param expiresAt exclusive hard successor deadline
     */
    public record Material(
            String schemaVersion,
            String rootSetId,
            long sequence,
            String previousMaterialFingerprint,
            String scopeId,
            String trustDomain,
            int signatureThreshold,
            int maximumFaults,
            List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> rootKeys,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current canonical successor material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootMaterial.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces canonical identity, quorum math, lifecycle, and predecessor shape. */
        public Material {
            schemaVersion = ExternalSequenceAnchorBootstrapRootGenesis.normalized(schemaVersion);
            rootSetId = ExternalSequenceAnchorBootstrapRootGenesis.normalized(rootSetId);
            previousMaterialFingerprint =
                    ExternalSequenceAnchorBootstrapRootGenesis.normalized(
                            previousMaterialFingerprint);
            scopeId = ExternalSequenceAnchorBootstrapRootGenesis.normalized(scopeId);
            trustDomain = ExternalSequenceAnchorBootstrapRootGenesis.normalized(trustDomain);
            policyFingerprint = ExternalSequenceAnchorBootstrapRootGenesis.normalized(
                    policyFingerprint);
            rootKeys = ExternalSequenceAnchorBootstrapRootGenesis.immutableKeys(rootKeys);
            int authorityCount =
                    ExternalSequenceAnchorBootstrapRootGenesis.distinctAuthorities(rootKeys);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(rootSetId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(previousMaterialFingerprint).matches()
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || maximumFaults < 0 || maximumFaults > 10
                    || authorityCount < 3 * maximumFaults + 1
                    || signatureThreshold < 2 * maximumFaults + 1
                    || signatureThreshold
                    > ExternalSequenceAnchorBootstrapRootGenesis.MAXIMUM_SIGNATURE_THRESHOLD
                    || signatureThreshold > authorityCount
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !ExternalSequenceAnchorBootstrapRootGenesis.wholeSecond(issuedAt)
                    || !ExternalSequenceAnchorBootstrapRootGenesis.wholeSecond(notBefore)
                    || !ExternalSequenceAnchorBootstrapRootGenesis.wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "External sequence-anchor bootstrap-root material is invalid");
            }
        }
    }

    private static List<TestSuiteStabilityServingInventory.AuthoritySignature>
            immutableSignatures(
            List<TestSuiteStabilityServingInventory.AuthoritySignature> values) {
        List<TestSuiteStabilityServingInventory.AuthoritySignature> result =
                values == null ? List.of() : List.copyOf(values);
        List<TestSuiteStabilityServingInventory.AuthoritySignature> sorted = result.stream()
                .sorted(Comparator.comparing(
                        TestSuiteStabilityServingInventory.AuthoritySignature::authorityId)
                        .thenComparing(
                                TestSuiteStabilityServingInventory.AuthoritySignature::keyId))
                .toList();
        Set<String> authorities = new HashSet<>();
        if (result.isEmpty() || result.size() > MAXIMUM_SIGNATURES_PER_ROLE
                || !result.equals(sorted)
                || result.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "External sequence-anchor bootstrap-root signatures are invalid");
        }
        return result;
    }
}
