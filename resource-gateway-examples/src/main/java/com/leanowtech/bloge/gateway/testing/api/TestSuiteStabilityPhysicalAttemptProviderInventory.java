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
 * Externally signed complete provider inventory for physical suite-stability attempts.
 *
 * <p>The material binds every resolvable provider/deployment pair to its runtime artifact,
 * observation key, supported isolation modes, and bounded lifecycle guarantees. The inventory is
 * deliberately payload-free and carries no endpoint, credential, private key, tenant, or attempt
 * identity.</p>
 *
 * @param schemaVersion signed-envelope protocol generation
 * @param material exact deployment-owned provider statement
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param signatures sorted distinct-authority Ed25519 signatures
 */
public record TestSuiteStabilityPhysicalAttemptProviderInventory(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures) {

    /** Current signed physical-attempt provider-inventory envelope generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptProviderInventory.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects non-canonical, duplicated, empty, or unbounded signed envelopes. */
    public TestSuiteStabilityPhysicalAttemptProviderInventory {
        schemaVersion = normalized(schemaVersion);
        materialFingerprint = normalized(materialFingerprint);
        signatures = signatures == null ? List.of() : List.copyOf(signatures);
        List<TestSuiteStabilityServingInventory.AuthoritySignature> ordered = signatures.stream()
                .sorted(Comparator.comparing(
                                TestSuiteStabilityServingInventory.AuthoritySignature::authorityId)
                        .thenComparing(
                                TestSuiteStabilityServingInventory.AuthoritySignature::keyId))
                .toList();
        Set<String> authorities = new HashSet<>();
        if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                || !FINGERPRINT.matcher(materialFingerprint).matches()
                || signatures.isEmpty() || signatures.size() > 32
                || !ordered.equals(signatures)
                || signatures.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "Physical-attempt provider inventory envelope is invalid");
        }
    }

    /**
     * Recomputes the signed material identity without trusting the envelope fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @return true only when material and envelope identity are equal
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /**
     * Canonical complete provider statement covered by detached signatures.
     *
     * @param schemaVersion material protocol generation
     * @param trustDomain independent provider-inventory trust domain
     * @param inventoryId unique inventory statement identity
     * @param revision monotonic revision within the stable inventory scope
     * @param scopeId stable provider-fleet scope across revisions
     * @param cohortId immutable Resource Gateway deployment generation
     * @param protocolVersion exact physical-attempt integration protocol
     * @param policyFingerprint accepted provider-inventory policy revision
     * @param bindings sorted complete provider/deployment bindings
     * @param issuedAt inventory issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive hard validity deadline
     */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String inventoryId,
            long revision,
            String scopeId,
            String cohortId,
            String protocolVersion,
            String policyFingerprint,
            List<Binding> bindings,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current signed provider-inventory material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryMaterial.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects malformed identity, ordering, cardinality, and validity intervals. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            inventoryId = normalized(inventoryId);
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            protocolVersion = normalized(protocolVersion);
            policyFingerprint = normalized(policyFingerprint);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            List<Binding> ordered = bindings.stream()
                    .sorted(Comparator.comparing(Binding::providerId)
                            .thenComparing(Binding::deploymentId))
                    .toList();
            Set<ProviderDeployment> identities = new HashSet<>();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(inventoryId).matches() || revision < 1
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(cohortId).matches()
                    || !IDENTIFIER.matcher(protocolVersion).matches()
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || bindings.isEmpty() || bindings.size() > 128
                    || !ordered.equals(bindings)
                    || bindings.stream().anyMatch(binding ->
                    !identities.add(binding.identity()))
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt) || notBefore.isBefore(issuedAt)
                    || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory material is invalid");
            }
        }
    }

    /**
     * Exact runtime and observation contract for one provider deployment.
     *
     * @param schemaVersion binding protocol generation
     * @param providerId stable isolated-runtime provider
     * @param deploymentId exact provider workload generation
     * @param runtimeArtifactFingerprint exact provider image or executable SHA-256
     * @param observationKeyId pinned detached-signature key identity
     * @param isolationModes sorted supported physical isolation modes
     * @param maximumObservationLatencyMillis provider observation latency guarantee
     * @param minimumStateRetentionMillis signed lifecycle-fact retention guarantee
     */
    public record Binding(
            String schemaVersion,
            String providerId,
            String deploymentId,
            String runtimeArtifactFingerprint,
            String observationKeyId,
            List<TestSuiteStabilityAttemptCancellationReceipt.IsolationMode> isolationModes,
            long maximumObservationLatencyMillis,
            long minimumStateRetentionMillis) {

        /** Current provider-binding protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderBinding.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

        /** Enforces canonical mode ordering and the observation descriptor's exact bounds. */
        public Binding {
            schemaVersion = normalized(schemaVersion);
            providerId = normalized(providerId);
            deploymentId = normalized(deploymentId);
            runtimeArtifactFingerprint = normalized(runtimeArtifactFingerprint);
            observationKeyId = normalized(observationKeyId);
            isolationModes = isolationModes == null ? List.of() : List.copyOf(isolationModes);
            List<TestSuiteStabilityAttemptCancellationReceipt.IsolationMode> ordered =
                    isolationModes.stream().sorted(Comparator.comparing(Enum::name)).toList();
            Set<TestSuiteStabilityAttemptCancellationReceipt.IsolationMode> unique =
                    new HashSet<>(isolationModes);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(providerId).matches()
                    || !IDENTIFIER.matcher(deploymentId).matches()
                    || !FINGERPRINT.matcher(runtimeArtifactFingerprint).matches()
                    || !IDENTIFIER.matcher(observationKeyId).matches()
                    || isolationModes.isEmpty() || isolationModes.size() > 3
                    || unique.size() != isolationModes.size() || !ordered.equals(isolationModes)
                    || maximumObservationLatencyMillis < 100
                    || maximumObservationLatencyMillis > 300_000
                    || minimumStateRetentionMillis < 60_000
                    || minimumStateRetentionMillis > 2_592_000_000L) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory binding is invalid");
            }
        }

        /**
         * Returns the exact provider/deployment lookup identity.
         *
         * @return exact provider/deployment lookup identity
         */
        public ProviderDeployment identity() {
            return new ProviderDeployment(providerId, deploymentId);
        }

        /**
         * Reconstructs the signed observation descriptor expected from the runtime adapter.
         *
         * @return descriptor that the runtime adapter must reproduce exactly
         */
        public TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor() {
            return new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                    TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor.SCHEMA_VERSION,
                    providerId, deploymentId, observationKeyId, true,
                    Set.copyOf(isolationModes),
                    java.time.Duration.ofMillis(maximumObservationLatencyMillis),
                    java.time.Duration.ofMillis(minimumStateRetentionMillis));
        }
    }

    /**
     * Exact resolver key retained privately by the inventory authority.
     *
     * @param providerId stable provider identity
     * @param deploymentId exact provider generation
     */
    public record ProviderDeployment(String providerId, String deploymentId) {
        /** Rejects ambiguous or malformed resolver identities. */
        public ProviderDeployment {
            providerId = normalized(providerId);
            deploymentId = normalized(deploymentId);
            if (providerId.isEmpty() || deploymentId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider deployment identity is invalid");
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
