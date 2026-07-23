package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Operator-owned observation admission policy source.
 *
 * <p>The API deliberately accepts only lookup coordinates. Authority keys, classification rules,
 * residency, retention, age, and size limits come from this provider rather than request fields or
 * query parameters. Implementations should return one atomic policy generation and must never
 * assemble a decision from independently refreshed mutable fragments.</p>
 */
public interface CapabilityObservationAdmissionPolicyProvider {
    /**
     * Reports whether governed policy can currently be resolved.
     *
     * @return true only when policy reads are usable
     */
    boolean available();

    /**
     * Resolves one exact policy generation.
     *
     * @param scope complete authenticated enterprise scope
     * @param capabilityRef exact submitted capability
     * @param grantRef exact submitted data-use grant
     * @param keyId submitted producer key id
     * @return exact operator-owned policy, or empty when no policy governs the coordinates
     */
    Optional<AdmissionPolicy> resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef grantRef,
            String keyId);

    /**
     * Returns a fail-closed provider for compositions without governed observation policy.
     *
     * @return provider that is unavailable and resolves nothing
     */
    static CapabilityObservationAdmissionPolicyProvider unavailable() {
        return new CapabilityObservationAdmissionPolicyProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<AdmissionPolicy> resolve(
                    CapabilitySnapshot.Scope scope,
                    MirrorArtifactRef capabilityRef,
                    MirrorArtifactRef grantRef,
                    String keyId) {
                return Optional.empty();
            }
        };
    }

    /**
     * Atomic policy generation used for one admission decision.
     *
     * @param scope complete governed scope
     * @param capabilityRef exact governed capability
     * @param policyRef exact immutable policy generation
     * @param grantRef exact accepted data-use grant
     * @param authorityKey exact producer verification key
     * @param allowedClassifications accepted post-sanitization classifications
     * @param allowedVaultRegions accepted payload residency regions
     * @param requiredUses uses that the grant must authorize
     * @param maximumObservationAge maximum age at admission time
     * @param maximumFutureSkew maximum tolerated future occurrence skew
     * @param maximumPayloadBytes maximum size of each sanitized payload
     * @param minimumRemainingRetention minimum payload availability after admission
     */
    record AdmissionPolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef policyRef,
            MirrorArtifactRef grantRef,
            CapabilityObservationIntegrity.AuthorityKey authorityKey,
            Set<CapabilityObservationEnvelope.Classification> allowedClassifications,
            Set<String> allowedVaultRegions,
            Set<CapabilityObservationEnvelope.AllowedUse> requiredUses,
            Duration maximumObservationAge,
            Duration maximumFutureSkew,
            long maximumPayloadBytes,
            Duration minimumRemainingRetention
    ) {
        /** Validates a complete fail-closed policy generation. */
        public AdmissionPolicy {
            scope = Objects.requireNonNull(scope, "scope");
            capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
            policyRef = ref(
                    policyRef, "OBSERVATION_ADMISSION_POLICY", "policyRef");
            grantRef = ref(grantRef, "DATA_USE_GRANT", "grantRef");
            authorityKey = Objects.requireNonNull(authorityKey, "authorityKey");
            allowedClassifications = immutableNonEmpty(
                    allowedClassifications, "allowedClassifications");
            allowedVaultRegions = immutableNonEmpty(
                    allowedVaultRegions, "allowedVaultRegions");
            if (allowedVaultRegions.stream().anyMatch(value ->
                    value == null
                            || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}"))) {
                throw new IllegalArgumentException("allowedVaultRegions is invalid");
            }
            requiredUses = immutableNonEmpty(requiredUses, "requiredUses");
            maximumObservationAge = positiveBounded(
                    maximumObservationAge, Duration.ofDays(365),
                    "maximumObservationAge");
            maximumFutureSkew = nonNegativeBounded(
                    maximumFutureSkew, Duration.ofHours(1),
                    "maximumFutureSkew");
            if (maximumPayloadBytes < 0
                    || maximumPayloadBytes > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("maximumPayloadBytes is invalid");
            }
            minimumRemainingRetention = positiveBounded(
                    minimumRemainingRetention, Duration.ofDays(3650),
                    "minimumRemainingRetention");
        }
    }

    private static MirrorArtifactRef ref(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static <T> Set<T> immutableNonEmpty(Set<T> values, String field) {
        if (values == null || values.isEmpty() || values.size() > 64
                || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return Set.copyOf(values);
    }

    private static Duration positiveBounded(
            Duration value, Duration maximum, String field) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.isZero() || exact.isNegative() || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static Duration nonNegativeBounded(
            Duration value, Duration maximum, String field) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.isNegative() || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
