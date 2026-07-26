package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Online sampling authority backed by signed current-head publications.
 *
 * <p>Every call resolves and verifies both the exact current grant and its exact current shared
 * guard policy. The adapter never caches a positive publication or key across calls, so a
 * successor publication, key revocation, or trust-source outage becomes visible at the mandatory
 * post-execution observation.</p>
 */
public final class SignedReadOnlyShadowSamplingGrantAuthority
        implements ReadOnlyShadowSamplingGrantAuthority {
    private final ReadOnlyShadowAuthorityPublicationSource source;
    private final ReadOnlyShadowAuthorityTrustStore trustStore;
    private final ReadOnlyShadowAuthorityIntegrity integrity;
    private final Clock clock;

    /**
     * Creates a fail-closed signed sampling authority.
     *
     * @param source online current-head publication source
     * @param trustStore dynamic authority-key and revocation source
     * @param integrity independent protocol verifier
     * @param clock trusted runtime clock
     */
    public SignedReadOnlyShadowSamplingGrantAuthority(
            ReadOnlyShadowAuthorityPublicationSource source,
            ReadOnlyShadowAuthorityTrustStore trustStore,
            ReadOnlyShadowAuthorityIntegrity integrity,
            Clock clock) {
        this.source = Objects.requireNonNull(
                source, "source");
        this.trustStore = Objects.requireNonNull(
                trustStore, "trustStore");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.clock = Objects.requireNonNull(
                clock, "clock");
    }

    @Override
    public Grant resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef grantRef) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        MirrorArtifactRef exactRef =
                Objects.requireNonNull(
                        grantRef, "grantRef");
        if (!available()) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        if (!ReadOnlyShadowSamplingGrantPublication
                .ARTIFACT_KIND.equals(exactRef.kind())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .GRANT_REVOKED);
        }
        Instant now = clock.instant();
        ReadOnlyShadowSamplingGrantPublication grant =
                currentGrant(
                        exactScope, exactRef.id());
        if (!grant.artifactRef().equals(exactRef)
                || !grant.material().scope()
                .equals(exactScope)
                || !grant.material().active()
                || !verifiedGrant(grant, now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .GRANT_REVOKED);
        }
        MirrorArtifactRef policyRef =
                grant.material().guardPolicyRef();
        ReadOnlyShadowGuardPolicyPublication policy =
                currentPolicy(
                        grant.material().guardScope(),
                        policyRef.id());
        if (!policy.artifactRef().equals(policyRef)
                || !policy.material().guardScope()
                .equals(grant.material().guardScope())
                || !verifiedPolicy(policy, now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .GRANT_REVOKED);
        }
        Instant validFrom = maximum(
                grant.material().validFrom(),
                grant.seal().signedAt(),
                policy.material().validFrom(),
                policy.seal().signedAt());
        Instant expiresAt = minimum(
                grant.material().expiresAt(),
                policy.material().expiresAt());
        if (now.isBefore(validFrom)
                || !expiresAt.isAfter(now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .GRANT_REVOKED);
        }
        return new Grant(
                exactScope,
                policy.material().guardScope(),
                grant.artifactRef(),
                grant.material().maximumSamples(),
                validFrom,
                expiresAt,
                policy.artifactRef(),
                policy.material().limits(),
                grant.attestationRef(),
                policy.attestationRef(),
                now);
    }

    @Override
    public boolean available() {
        return safeAvailable(source::available)
                && safeAvailable(trustStore::available);
    }

    private ReadOnlyShadowSamplingGrantPublication
    currentGrant(
            CapabilitySnapshot.Scope scope,
            String grantId) {
        try {
            return source.currentSamplingGrant(
                            scope, grantId)
                    .orElseThrow(() -> failure(
                            ReadOnlyShadowDataPlane
                                    .FailureReason
                                    .GRANT_REVOKED));
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }

    private ReadOnlyShadowGuardPolicyPublication
    currentPolicy(
            CapabilitySnapshot.Scope guardScope,
            String policyId) {
        try {
            return source.currentGuardPolicy(
                            guardScope, policyId)
                    .orElseThrow(() -> failure(
                            ReadOnlyShadowDataPlane
                                    .FailureReason
                                    .GRANT_REVOKED));
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }

    private boolean verifiedGrant(
            ReadOnlyShadowSamplingGrantPublication publication,
            Instant now) {
        Optional<ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
                key = resolveKey(
                publication.material().scope(),
                ReadOnlyShadowAuthorityIntegrity
                        .PublicationKind.SAMPLING_GRANT,
                publication.material().issuer(),
                publication.seal().keyId());
        return key.isPresent()
                && integrity.verifySamplingGrant(
                publication,
                key.get(),
                now).verified();
    }

    private boolean verifiedPolicy(
            ReadOnlyShadowGuardPolicyPublication publication,
            Instant now) {
        Optional<ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
                key = resolveKey(
                publication.material().guardScope(),
                ReadOnlyShadowAuthorityIntegrity
                        .PublicationKind.GUARD_POLICY,
                publication.material().issuer(),
                publication.seal().keyId());
        return key.isPresent()
                && integrity.verifyGuardPolicy(
                publication,
                key.get(),
                now).verified();
    }

    private Optional<
            ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
    resolveKey(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind
                    publicationKind,
            String issuer,
            String keyId) {
        try {
            return trustStore.resolve(
                    scope,
                    publicationKind,
                    issuer,
                    keyId);
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }

    private static boolean safeAvailable(
            Availability probe) {
        try {
            return probe.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static Instant maximum(
            Instant first,
            Instant second,
            Instant third,
            Instant fourth) {
        Instant result = first.isAfter(second)
                ? first : second;
        result = result.isAfter(third)
                ? result : third;
        return result.isAfter(fourth)
                ? result : fourth;
    }

    private static Instant minimum(
            Instant first,
            Instant second) {
        return first.isBefore(second)
                ? first : second;
    }

    private static ReadOnlyShadowDataPlane.Failure
    failure(
            ReadOnlyShadowDataPlane.FailureReason reason) {
        return new ReadOnlyShadowDataPlane.Failure(reason);
    }

    @FunctionalInterface
    private interface Availability {
        boolean available();
    }
}
