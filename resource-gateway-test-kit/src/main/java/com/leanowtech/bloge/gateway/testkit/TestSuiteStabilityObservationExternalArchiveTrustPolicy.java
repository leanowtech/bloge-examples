package com.leanowtech.bloge.gateway.testkit;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Caller-owned trust policy for independent external observation-archive verification.
 *
 * <p>This policy must be pinned by CI or a governance authority outside the Resource Gateway
 * lifecycle response. Allowing the producer to supply both evidence and this policy would collapse
 * external proof into self-attestation.</p>
 *
 * @param schemaVersion local policy model generation
 * @param trustDomain exact accepted external archive trust domain
 * @param archiveSetId exact accepted archive-set identity
 * @param acceptedRetentionPolicyFingerprints approved historical retention-policy revisions
 * @param minimumCopies minimum independently trusted copies required for every retirement
 * @param requiredRetainUntil absolute retention horizon required by the current decision
 * @param authorities complete accepted authority, failure-domain, and key pins by authority id
 */
public record TestSuiteStabilityObservationExternalArchiveTrustPolicy(
        String schemaVersion,
        String trustDomain,
        String archiveSetId,
        Set<String> acceptedRetentionPolicyFingerprints,
        int minimumCopies,
        Instant requiredRetainUntil,
        Map<String, TrustedAuthority> authorities
) {
    /** Current caller-side archive trust-policy model generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveTrustPolicy.v1";

    /** Normalizes and freezes an independently supplied trust policy. */
    public TestSuiteStabilityObservationExternalArchiveTrustPolicy {
        schemaVersion = normalized(schemaVersion);
        trustDomain = normalized(trustDomain);
        archiveSetId = normalized(archiveSetId);
        acceptedRetentionPolicyFingerprints = acceptedRetentionPolicyFingerprints == null
                ? Set.of() : Set.copyOf(acceptedRetentionPolicyFingerprints);
        Map<String, TrustedAuthority> exactAuthorities = new LinkedHashMap<>();
        if (authorities != null) {
            authorities.forEach((key, value) -> exactAuthorities.put(normalized(key), value));
        }
        authorities = Map.copyOf(exactAuthorities);
        HashSet<String> failureDomains = new HashSet<>();
        boolean authorityClosure = authorities.entrySet().stream().allMatch(entry ->
                entry.getValue() != null
                        && entry.getKey().equals(entry.getValue().authorityId())
                        && failureDomains.add(entry.getValue().failureDomain()));
        if (!SCHEMA_VERSION.equals(schemaVersion) || !identifier(trustDomain)
                || !identifier(archiveSetId) || acceptedRetentionPolicyFingerprints.isEmpty()
                || acceptedRetentionPolicyFingerprints.stream().anyMatch(
                value -> !fingerprint(value))
                || minimumCopies < 1 || minimumCopies > 16
                || requiredRetainUntil == null || Instant.EPOCH.equals(requiredRetainUntil)
                || authorities.size() < minimumCopies || authorities.size() > 16
                || !authorityClosure) {
            throw new IllegalArgumentException(
                    "External observation-archive trust policy is incomplete");
        }
    }

    /**
     * Resolves one accepted authority pin without falling back to response-provided identity.
     *
     * @param authorityId exact receipt authority identity
     * @return caller-pinned authority when present
     */
    public Optional<TrustedAuthority> authority(String authorityId) {
        return Optional.ofNullable(authorities.get(normalized(authorityId)));
    }

    /**
     * Exact caller pin for one authority, failure domain, and historical verification keys.
     *
     * @param authorityId exact external authority identity
     * @param failureDomain independently certified failure domain
     * @param verificationKeys accepted Ed25519 keys by exact key id
     */
    public record TrustedAuthority(
            String authorityId,
            String failureDomain,
            Map<String, EvidenceVerificationKey> verificationKeys
    ) {
        /** Normalizes and freezes one authority pin. */
        public TrustedAuthority {
            authorityId = normalized(authorityId);
            failureDomain = normalized(failureDomain);
            Map<String, EvidenceVerificationKey> exactKeys = new LinkedHashMap<>();
            if (verificationKeys != null) {
                verificationKeys.forEach((key, value) ->
                        exactKeys.put(normalized(key), Objects.requireNonNull(value, "key")));
            }
            verificationKeys = Map.copyOf(exactKeys);
            boolean keyClosure = verificationKeys.entrySet().stream().allMatch(entry ->
                    entry.getKey().equals(entry.getValue().keyId())
                            && "Ed25519".equals(entry.getValue().algorithm()));
            if (!identifier(authorityId) || !identifier(failureDomain)
                    || verificationKeys.isEmpty() || !keyClosure) {
                throw new IllegalArgumentException("External archive authority pin is incomplete");
            }
        }

        /**
         * Resolves one authority-bound public key.
         *
         * @param keyId exact receipt signing key identity
         * @return pinned key when present
         */
        public Optional<EvidenceVerificationKey> key(String keyId) {
            return Optional.ofNullable(verificationKeys.get(normalized(keyId)));
        }
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static boolean identifier(String value) {
        return normalized(value).matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
