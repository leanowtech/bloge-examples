package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Binding;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Material;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.ProviderDeployment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static M-of-N Ed25519 provider-inventory authority with generation-fenced runtime adapters.
 *
 * <p>Construction verifies canonical material, trust-domain/policy/cohort binding, signature
 * threshold, hard lifetime, and exact adapter coverage. Resolution returns a wrapper that
 * rechecks the inventory generation before both descriptor and observation calls, verifies the
 * adapter descriptor against signed material, and rejects commands for another deployment.</p>
 *
 * <p>The authority never calls a provider while constructing or observing capability state.
 * Inventory expiration closes future resolution immediately without a process restart.</p>
 */
public final class ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
        implements TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority {

    /** Aggregate source type for immutable signed deployment configuration. */
    public static final String SOURCE_TYPE = "STATIC_SIGNED_ED25519_M_OF_N";
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_LIFETIME = Duration.ofDays(30);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final Clock clock;
    private final Material material;
    private final String materialFingerprint;
    private final int validSignatureCount;
    private final int signatureThreshold;
    private final Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
            authorities;

    /**
     * Local expectations that signed material must bind exactly.
     *
     * @param trustDomain independent provider-inventory trust domain
     * @param scopeId stable provider-fleet scope
     * @param cohortId exact local deployment cohort
     * @param protocolVersion exact physical-attempt integration protocol
     * @param acceptedPolicyFingerprints accepted signed policy revisions
     */
    public record ExpectedBinding(
            String trustDomain,
            String scopeId,
            String cohortId,
            String protocolVersion,
            Set<String> acceptedPolicyFingerprints) {

        /** Rejects incomplete or unbounded local expectations. */
        public ExpectedBinding {
            trustDomain = normalized(trustDomain);
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            protocolVersion = normalized(protocolVersion);
            acceptedPolicyFingerprints = acceptedPolicyFingerprints == null
                    ? Set.of() : Set.copyOf(acceptedPolicyFingerprints);
            if (!IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(cohortId).matches()
                    || !IDENTIFIER.matcher(protocolVersion).matches()
                    || acceptedPolicyFingerprints.isEmpty()
                    || acceptedPolicyFingerprints.size() > 32
                    || acceptedPolicyFingerprints.stream().anyMatch(value ->
                    !value.matches("sha256:[a-f0-9]{64}"))) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory expected binding is invalid");
            }
        }
    }

    /**
     * Verifies and freezes one static provider inventory.
     *
     * @param objectMapper canonical protocol mapper
     * @param clock current freshness authority
     * @param expected exact local inventory expectations
     * @param signatureThreshold required distinct inventory authorities
     * @param authorityKeys public Ed25519 verification keys
     * @param inventory untrusted signed inventory envelope
     * @param runtimeAuthorities exact provider/deployment runtime adapter map
     */
    public ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding expected,
            int signatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> authorityKeys,
            TestSuiteStabilityPhysicalAttemptProviderInventory inventory,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    runtimeAuthorities) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        ExpectedBinding binding = Objects.requireNonNull(expected, "expected");
        TestSuiteStabilityPhysicalAttemptProviderInventory envelope =
                Objects.requireNonNull(inventory, "inventory");
        this.material = envelope.material();
        this.materialFingerprint = envelope.materialFingerprint();
        this.signatureThreshold = signatureThreshold;
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        authorityKeys, signatureThreshold);
        Instant now = clock.instant();
        verifyMaterial(mapper, binding, envelope, now);
        this.validSignatureCount =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                        keys, signatureThreshold, envelope.signatures(), materialFingerprint,
                        material.issuedAt(), material.expiresAt(), now,
                        "Physical-attempt provider inventory");
        this.authorities = exactAuthorities(material.bindings(), runtimeAuthorities);
    }

    /** Returns current hard-expiry state without provider, network, or database I/O. */
    @Override
    public Observation observation() {
        Instant now = clock.instant();
        String status;
        boolean available;
        if (now.isBefore(material.notBefore())) {
            status = "NOT_YET_VALID";
            available = false;
        } else if (!now.isBefore(material.expiresAt())) {
            status = "EXPIRED";
            available = false;
        } else {
            status = "VERIFIED";
            available = true;
        }
        return new Observation(Observation.SCHEMA_VERSION, true, available, status, SOURCE_TYPE,
                material.revision(), materialFingerprint, material.revision(),
                materialFingerprint, material.policyFingerprint(), material.cohortId(),
                material.bindings(), material.expiresAt(), validSignatureCount,
                signatureThreshold);
    }

    /**
     * Resolves only an exact signed provider/deployment and returns a generation-fenced wrapper.
     *
     * @param providerId retained provider identity
     * @param deploymentId retained provider generation
     * @return exact signed observation adapter
     */
    @Override
    public TestSuiteStabilityPhysicalAttemptObservationAuthority resolve(
            String providerId, String deploymentId) {
        Observation observed = requireCurrent(materialFingerprint);
        ProviderDeployment identity = new ProviderDeployment(providerId, deploymentId);
        TestSuiteStabilityPhysicalAttemptObservationAuthority delegate =
                authorities.get(identity);
        if (delegate == null) {
            throw new IllegalArgumentException(
                    "Physical-attempt provider deployment is not in the signed inventory");
        }
        Binding binding = observed.bindings().stream()
                .filter(candidate -> candidate.identity().equals(identity))
                .findFirst().orElseThrow();
        return new FencedAuthority(delegate, binding, observed.materialFingerprint());
    }

    private Observation requireCurrent(String expectedFingerprint) {
        Observation observed = observation();
        if (!observed.available()
                || !observed.materialFingerprint().equals(expectedFingerprint)) {
            throw new IllegalStateException(
                    "Physical-attempt provider inventory generation is unavailable");
        }
        return observed;
    }

    private void verifyMaterial(
            ObjectMapper objectMapper,
            ExpectedBinding expected,
            TestSuiteStabilityPhysicalAttemptProviderInventory inventory,
            Instant now) {
        if (!expected.trustDomain().equals(material.trustDomain())
                || !expected.scopeId().equals(material.scopeId())
                || !expected.cohortId().equals(material.cohortId())
                || !expected.protocolVersion().equals(material.protocolVersion())
                || !expected.acceptedPolicyFingerprints().contains(
                material.policyFingerprint())
                || !inventory.fingerprintVerified(objectMapper)) {
            throw new IllegalArgumentException(
                    "Physical-attempt provider inventory binding is invalid");
        }
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_LIFETIME) > 0
                || material.issuedAt().isAfter(now.plus(CLOCK_SKEW))
                || now.isBefore(material.notBefore())
                || !now.isBefore(material.expiresAt())) {
            throw new IllegalArgumentException(
                    "Physical-attempt provider inventory freshness is invalid");
        }
    }

    private static Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
            exactAuthorities(
            List<Binding> bindings,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    candidates) {
        Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority> supplied =
                candidates == null ? Map.of() : Map.copyOf(candidates);
        Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority> exact =
                new HashMap<>();
        for (Binding binding : bindings) {
            TestSuiteStabilityPhysicalAttemptObservationAuthority authority =
                    supplied.get(binding.identity());
            if (authority == null) {
                throw new IllegalArgumentException(
                        "Signed provider inventory has no exact runtime adapter");
            }
            exact.put(binding.identity(), authority);
        }
        if (exact.size() != supplied.size()) {
            throw new IllegalArgumentException(
                    "Runtime adapter is absent from the signed provider inventory");
        }
        return Map.copyOf(exact);
    }

    private final class FencedAuthority
            implements TestSuiteStabilityPhysicalAttemptObservationAuthority {
        private final TestSuiteStabilityPhysicalAttemptObservationAuthority delegate;
        private final Binding binding;
        private final String generationFingerprint;

        private FencedAuthority(
                TestSuiteStabilityPhysicalAttemptObservationAuthority delegate,
                Binding binding,
                String generationFingerprint) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.binding = Objects.requireNonNull(binding, "binding");
            this.generationFingerprint = generationFingerprint;
        }

        @Override
        public Descriptor descriptor() {
            requireCurrent(generationFingerprint);
            Descriptor actual = Objects.requireNonNull(
                    delegate.descriptor(), "physical-attempt provider descriptor");
            if (!binding.descriptor().equals(actual)) {
                throw new IllegalStateException(
                        "Physical-attempt provider descriptor does not match signed inventory");
            }
            return actual;
        }

        @Override
        public TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observe(
                TestSuiteStabilityPhysicalAttemptObservationCommand command) {
            requireCurrent(generationFingerprint);
            TestSuiteStabilityPhysicalAttemptIdentity identity = Objects.requireNonNull(
                    command, "command").identity();
            if (!binding.providerId().equals(identity.providerId())
                    || !binding.deploymentId().equals(identity.deploymentId())) {
                throw new IllegalArgumentException(
                        "Observation command does not match signed provider binding");
            }
            return delegate.observe(command);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
