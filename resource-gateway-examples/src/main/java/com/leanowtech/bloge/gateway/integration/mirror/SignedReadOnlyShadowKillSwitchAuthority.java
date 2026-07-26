package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Online kill-switch authority backed by signed current-head publications.
 *
 * <p>The adapter returns a verified disabled state when the caller references that exact current
 * generation, allowing the composed authority to classify an emergency stop as
 * {@code KILL_SWITCH_OPEN}. A stale reference, invalid signature, revoked key, or cross-scope
 * lookup is classified the same way without leaking verification detail.</p>
 */
public final class SignedReadOnlyShadowKillSwitchAuthority
        implements ReadOnlyShadowKillSwitchAuthority {
    private final ReadOnlyShadowAuthorityPublicationSource source;
    private final ReadOnlyShadowAuthorityTrustStore trustStore;
    private final ReadOnlyShadowAuthorityIntegrity integrity;
    private final Clock clock;

    /**
     * Creates a fail-closed signed kill-switch authority.
     *
     * @param source online current-head publication source
     * @param trustStore dynamic authority-key and revocation source
     * @param integrity independent protocol verifier
     * @param clock trusted runtime clock
     */
    public SignedReadOnlyShadowKillSwitchAuthority(
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
    public State resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef killSwitchRef) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        MirrorArtifactRef exactRef =
                Objects.requireNonNull(
                        killSwitchRef, "killSwitchRef");
        if (!available()) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        if (!ReadOnlyShadowKillSwitchPublication
                .ARTIFACT_KIND.equals(exactRef.kind())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .KILL_SWITCH_OPEN);
        }
        Instant now = clock.instant();
        ReadOnlyShadowKillSwitchPublication publication =
                current(exactScope, exactRef.id());
        if (!publication.artifactRef().equals(exactRef)
                || !publication.material().scope()
                .equals(exactScope)
                || !verified(publication, now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .KILL_SWITCH_OPEN);
        }
        Instant effectiveAt =
                publication.material().effectiveAt()
                        .isAfter(
                                publication.seal()
                                        .signedAt())
                        ? publication.material()
                        .effectiveAt()
                        : publication.seal().signedAt();
        return new State(
                exactScope,
                publication.artifactRef(),
                publication.material().enabled(),
                effectiveAt,
                publication.material().expiresAt(),
                publication.attestationRef(),
                now);
    }

    @Override
    public boolean available() {
        return safeAvailable(source::available)
                && safeAvailable(trustStore::available);
    }

    private ReadOnlyShadowKillSwitchPublication current(
            CapabilitySnapshot.Scope scope,
            String switchId) {
        try {
            return source.currentKillSwitch(
                            scope, switchId)
                    .orElseThrow(() -> failure(
                            ReadOnlyShadowDataPlane
                                    .FailureReason
                                    .KILL_SWITCH_OPEN));
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }

    private boolean verified(
            ReadOnlyShadowKillSwitchPublication publication,
            Instant now) {
        Optional<ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
                key;
        try {
            key = trustStore.resolve(
                    publication.material().scope(),
                    ReadOnlyShadowAuthorityIntegrity
                            .PublicationKind.KILL_SWITCH,
                    publication.material().issuer(),
                    publication.seal().keyId());
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        return key.isPresent()
                && integrity.verifyKillSwitch(
                publication,
                key.get(),
                now).verified();
    }

    private static boolean safeAvailable(
            Availability probe) {
        try {
            return probe.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
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
