package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedReadOnlyShadowAuthorityTest {
    private static final Instant NOW =
            Instant.parse("2026-07-26T09:00:00Z");
    private static final String ISSUER =
            "data-governance:shadow";

    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);
    private final ReadOnlyShadowAuthorityIntegrity integrity =
            new ReadOnlyShadowAuthorityIntegrity(mapper);
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private final MutableSource source =
            new MutableSource();
    private final AtomicReference<
            ReadOnlyShadowAuthorityIntegrity.KeyState>
            keyState = new AtomicReference<>();
    private final ReadOnlyShadowAuthorityTrustStore trust =
            new ReadOnlyShadowAuthorityTrustStore() {
                @Override
                public Optional<
                        ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
                resolve(
                        CapabilitySnapshot.Scope scope,
                        ReadOnlyShadowAuthorityIntegrity.PublicationKind
                                publicationKind,
                        String issuer,
                        String keyId) {
                    CapabilitySnapshot.Scope delegated =
                            publicationKind
                                    == ReadOnlyShadowAuthorityIntegrity
                                    .PublicationKind.GUARD_POLICY
                                    ? guardScope()
                                    : executionScope();
                    if (keyState.get() == null
                            || !delegated.equals(scope)
                            || !ISSUER.equals(issuer)) {
                        return Optional.empty();
                    }
                    VisualEvidenceSigner.VerificationKey key =
                            signer.key(keyId).orElse(null);
                    return key == null
                            ? Optional.empty()
                            : Optional.of(
                            authorityKey(
                                    delegated,
                                    publicationKind,
                                    keyState.get(),
                                    null));
                }

                @Override
                public boolean available() {
                    return keyState.get() != null;
                }
            };

    private SignedReadOnlyShadowSamplingGrantAuthority
            sampling;
    private SignedReadOnlyShadowKillSwitchAuthority
            killSwitchAuthority;

    @BeforeEach
    void setUp() {
        ReadOnlyShadowGuardPolicyPublication policy =
                policy(1, "", guardScope(), limits(4));
        source.policy.set(policy);
        source.grant.set(
                grant(
                        1,
                        "",
                        true,
                        policy.artifactRef(),
                        100));
        source.killSwitch.set(
                killSwitch(1, "", true));
        keyState.set(
                ReadOnlyShadowAuthorityIntegrity
                        .KeyState.ACTIVE);
        sampling =
                new SignedReadOnlyShadowSamplingGrantAuthority(
                        source,
                        trust,
                        integrity,
                        Clock.fixed(
                                NOW, ZoneOffset.UTC));
        killSwitchAuthority =
                new SignedReadOnlyShadowKillSwitchAuthority(
                source,
                trust,
                integrity,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void resolvesExactCurrentGrantWithSignedSharedGuardPolicy() {
        var publication = source.grant.get();
        ReadOnlyShadowSamplingGrantAuthority.Grant resolved =
                sampling.resolve(
                        executionScope(),
                        publication.artifactRef());

        assertThat(sampling.available()).isTrue();
        assertThat(resolved.scope())
                .isEqualTo(executionScope());
        assertThat(resolved.guardScope())
                .isEqualTo(guardScope());
        assertThat(resolved.grantRef())
                .isEqualTo(publication.artifactRef());
        assertThat(resolved.guardPolicyRef())
                .isEqualTo(
                        source.policy.get().artifactRef());
        assertThat(resolved.limits())
                .isEqualTo(limits(4));
        assertThat(resolved.authorityAttestationRef())
                .isEqualTo(
                        publication.attestationRef());
        assertThat(resolved.guardPolicyAttestationRef())
                .isEqualTo(
                        source.policy.get().attestationRef());
        assertThat(resolved.observedAt())
                .isEqualTo(NOW);
    }

    @Test
    void rejectsAttestationsThatDoNotMatchTheirMaterialCoordinates() {
        ReadOnlyShadowSamplingGrantAuthority.Grant resolved =
                sampling.resolve(
                        executionScope(),
                        source.grant.get().artifactRef());
        MirrorArtifactRef unrelatedGrantAttestation =
                new MirrorArtifactRef(
                        "SHADOW_SAMPLING_GRANT_ATTESTATION",
                        "unrelated-grant",
                        resolved.grantRef().revision(),
                        resolved.authorityAttestationRef()
                                .fingerprint());
        MirrorArtifactRef unrelatedPolicyAttestation =
                new MirrorArtifactRef(
                        "SHADOW_EXECUTION_GUARD_POLICY_ATTESTATION",
                        "unrelated-policy",
                        resolved.guardPolicyRef().revision(),
                        resolved.guardPolicyAttestationRef()
                                .fingerprint());
        MirrorArtifactRef wrongGrantRevision =
                withNextRevision(
                        resolved.authorityAttestationRef());
        MirrorArtifactRef wrongPolicyRevision =
                withNextRevision(
                        resolved.guardPolicyAttestationRef());

        assertThatThrownBy(() ->
                copyGrant(
                        resolved,
                        unrelatedGrantAttestation,
                        resolved.guardPolicyAttestationRef()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                copyGrant(
                        resolved,
                        resolved.authorityAttestationRef(),
                        unrelatedPolicyAttestation))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                copyGrant(
                        resolved,
                        wrongGrantRevision,
                        resolved.guardPolicyAttestationRef()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                copyGrant(
                        resolved,
                        resolved.authorityAttestationRef(),
                        wrongPolicyRevision))
                .isInstanceOf(IllegalArgumentException.class);

        ReadOnlyShadowKillSwitchAuthority.State state =
                killSwitchAuthority.resolve(
                        executionScope(),
                        source.killSwitch.get().artifactRef());
        assertThatThrownBy(() ->
                new ReadOnlyShadowKillSwitchAuthority.State(
                        state.scope(),
                        state.killSwitchRef(),
                        state.enabled(),
                        state.effectiveAt(),
                        state.expiresAt(),
                        new MirrorArtifactRef(
                                "SHADOW_KILL_SWITCH_ATTESTATION",
                                "unrelated-switch",
                                state.killSwitchRef().revision(),
                                state.authorityAttestationRef()
                                        .fingerprint()),
                        state.observedAt()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ReadOnlyShadowKillSwitchAuthority.State(
                        state.scope(),
                        state.killSwitchRef(),
                        state.enabled(),
                        state.effectiveAt(),
                        state.expiresAt(),
                        withNextRevision(
                                state.authorityAttestationRef()),
                        state.observedAt()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAStaleGrantAfterAnInactiveSuccessorBecomesCurrent() {
        MirrorArtifactRef stale =
                source.grant.get().artifactRef();
        ReadOnlyShadowSamplingGrantPublication successor =
                grant(
                        2,
                        source.grant.get()
                                .publicationFingerprint(),
                        false,
                        source.policy.get()
                                .artifactRef(),
                        100);
        source.grant.set(successor);

        assertFailure(
                () -> sampling.resolve(
                        executionScope(), stale),
                ReadOnlyShadowDataPlane.FailureReason
                        .GRANT_REVOKED);
        assertFailure(
                () -> sampling.resolve(
                        executionScope(),
                        successor.artifactRef()),
                ReadOnlyShadowDataPlane.FailureReason
                        .GRANT_REVOKED);
    }

    @Test
    void rejectsGrantWhenTheReferencedGuardPolicyIsNoLongerCurrent() {
        MirrorArtifactRef grantRef =
                source.grant.get().artifactRef();
        ReadOnlyShadowGuardPolicyPublication successor =
                policy(
                        2,
                        source.policy.get()
                                .publicationFingerprint(),
                        guardScope(),
                        limits(2));
        source.policy.set(successor);

        assertFailure(
                () -> sampling.resolve(
                        executionScope(), grantRef),
                ReadOnlyShadowDataPlane.FailureReason
                        .GRANT_REVOKED);
    }

    @Test
    void exposesDisabledCurrentKillSwitchAndRejectsItsStalePredecessor() {
        MirrorArtifactRef stale =
                source.killSwitch.get().artifactRef();
        ReadOnlyShadowKillSwitchPublication disabled =
                killSwitch(
                        2,
                        source.killSwitch.get()
                                .publicationFingerprint(),
                        false);
        source.killSwitch.set(disabled);

        assertFailure(
                () -> killSwitchAuthority.resolve(
                        executionScope(), stale),
                ReadOnlyShadowDataPlane.FailureReason
                        .KILL_SWITCH_OPEN);
        ReadOnlyShadowKillSwitchAuthority.State state =
                killSwitchAuthority.resolve(
                        executionScope(),
                        disabled.artifactRef());
        assertThat(state.enabled()).isFalse();
        assertThat(state.authorityAttestationRef())
                .isEqualTo(
                        disabled.attestationRef());
    }

    @Test
    void observesDynamicKeyRevocationWithoutPositiveCaching() {
        sampling.resolve(
                executionScope(),
                source.grant.get().artifactRef());
        keyState.set(
                ReadOnlyShadowAuthorityIntegrity
                        .KeyState.REVOKED);

        assertFailure(
                () -> sampling.resolve(
                        executionScope(),
                        source.grant.get()
                                .artifactRef()),
                ReadOnlyShadowDataPlane.FailureReason
                        .GRANT_REVOKED);
    }

    @Test
    void failsClosedForUnavailableSourceTrustAndCrossScopeLookup() {
        source.available = false;
        assertThat(sampling.available()).isFalse();
        assertThat(killSwitchAuthority.available())
                .isFalse();
        assertFailure(
                () -> sampling.resolve(
                        executionScope(),
                        source.grant.get()
                                .artifactRef()),
                ReadOnlyShadowDataPlane.FailureReason
                        .ADMISSION_AUTHORITY_UNAVAILABLE);

        source.available = true;
        keyState.set(null);
        assertThat(sampling.available()).isFalse();
        assertFailure(
                () -> sampling.resolve(
                        executionScope(),
                        source.grant.get()
                                .artifactRef()),
                ReadOnlyShadowDataPlane.FailureReason
                        .ADMISSION_AUTHORITY_UNAVAILABLE);

        keyState.set(
                ReadOnlyShadowAuthorityIntegrity
                        .KeyState.ACTIVE);
        assertFailure(
                () -> sampling.resolve(
                        new CapabilitySnapshot.Scope(
                                "tenant-b",
                                "risk",
                                "loan",
                                "staging",
                                "sg"),
                        source.grant.get()
                                .artifactRef()),
                ReadOnlyShadowDataPlane.FailureReason
                        .GRANT_REVOKED);
    }

    private ReadOnlyShadowAuthorityIntegrity.AuthorityKey
    authorityKey(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind
                    publicationKind,
            ReadOnlyShadowAuthorityIntegrity.KeyState state,
            Instant retiredAt) {
        VisualEvidenceSigner.VerificationKey key =
                signer.key(
                        signer.descriptor()
                                .activeKeyId())
                        .orElseThrow();
        return new ReadOnlyShadowAuthorityIntegrity.AuthorityKey(
                key.keyId(),
                key.algorithm(),
                key.encodedPublicKey(),
                ISSUER,
                scope,
                publicationKind,
                NOW.minusSeconds(1),
                NOW.plusSeconds(3600),
                retiredAt,
                state);
    }

    private ReadOnlyShadowGuardPolicyPublication policy(
            long revision,
            String previous,
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowExecutionGuard.Limits limits) {
        return integrity.sealGuardPolicy(
                new ReadOnlyShadowGuardPolicyPublication
                        .Material(
                        "provider:credit-primary",
                        revision,
                        previous,
                        scope,
                        limits,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(600),
                        ISSUER),
                signer);
    }

    private ReadOnlyShadowSamplingGrantPublication grant(
            long revision,
            String previous,
            boolean active,
            MirrorArtifactRef policyRef,
            long maximumSamples) {
        return integrity.sealSamplingGrant(
                new ReadOnlyShadowSamplingGrantPublication
                        .Material(
                        "grant:loan-risk",
                        revision,
                        previous,
                        executionScope(),
                        active,
                        maximumSamples,
                        guardScope(),
                        policyRef,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(600),
                        ISSUER),
                signer);
    }

    private ReadOnlyShadowKillSwitchPublication
    killSwitch(
            long revision,
            String previous,
            boolean enabled) {
        return integrity.sealKillSwitch(
                new ReadOnlyShadowKillSwitchPublication
                        .Material(
                        "switch:loan-risk",
                        revision,
                        previous,
                        executionScope(),
                        enabled,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(600),
                        ISSUER),
                signer);
    }

    private static ReadOnlyShadowSamplingGrantAuthority.Grant
    copyGrant(
            ReadOnlyShadowSamplingGrantAuthority.Grant source,
            MirrorArtifactRef grantAttestation,
            MirrorArtifactRef policyAttestation) {
        return new ReadOnlyShadowSamplingGrantAuthority.Grant(
                source.scope(),
                source.guardScope(),
                source.grantRef(),
                source.maximumSamples(),
                source.validFrom(),
                source.expiresAt(),
                source.guardPolicyRef(),
                source.limits(),
                grantAttestation,
                policyAttestation,
                source.observedAt());
    }

    private static ReadOnlyShadowExecutionGuard.Limits
    limits(int maximumConcurrent) {
        return new ReadOnlyShadowExecutionGuard.Limits(
                maximumConcurrent,
                20,
                Duration.ofMinutes(1),
                3,
                Duration.ofMinutes(2));
    }

    private static CapabilitySnapshot.Scope executionScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                "risk",
                "loan",
                "staging",
                "sg");
    }

    private static MirrorArtifactRef withNextRevision(
            MirrorArtifactRef source) {
        return new MirrorArtifactRef(
                source.kind(),
                source.id(),
                source.revision() + 1,
                source.fingerprint());
    }

    private static CapabilitySnapshot.Scope guardScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                "shared-provider",
                "",
                "staging",
                "sg");
    }

    private static void assertFailure(
            org.assertj.core.api.ThrowableAssert
                    .ThrowingCallable action,
            ReadOnlyShadowDataPlane.FailureReason reason) {
        assertThatThrownBy(action)
                .isInstanceOf(
                        ReadOnlyShadowDataPlane
                                .Failure.class)
                .extracting(value ->
                        ((ReadOnlyShadowDataPlane.Failure)
                                value).reason())
                .isEqualTo(reason);
    }

    private static final class MutableSource
            implements ReadOnlyShadowAuthorityPublicationSource {
        private final AtomicReference<
                ReadOnlyShadowSamplingGrantPublication>
                grant = new AtomicReference<>();
        private final AtomicReference<
                ReadOnlyShadowKillSwitchPublication>
                killSwitch = new AtomicReference<>();
        private final AtomicReference<
                ReadOnlyShadowGuardPolicyPublication>
                policy = new AtomicReference<>();
        private volatile boolean available = true;

        @Override
        public Optional<ReadOnlyShadowSamplingGrantPublication>
        currentSamplingGrant(
                CapabilitySnapshot.Scope scope,
                String grantId) {
            var current = grant.get();
            return available
                    && current != null
                    && current.material().scope()
                    .equals(scope)
                    && current.material().grantId()
                    .equals(grantId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public Optional<ReadOnlyShadowKillSwitchPublication>
        currentKillSwitch(
                CapabilitySnapshot.Scope scope,
                String switchId) {
            var current = killSwitch.get();
            return available
                    && current != null
                    && current.material().scope()
                    .equals(scope)
                    && current.material().switchId()
                    .equals(switchId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public Optional<ReadOnlyShadowGuardPolicyPublication>
        currentGuardPolicy(
                CapabilitySnapshot.Scope guardScope,
                String policyId) {
            var current = policy.get();
            return available
                    && current != null
                    && current.material().guardScope()
                    .equals(guardScope)
                    && current.material().policyId()
                    .equals(policyId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public boolean available() {
            return available;
        }
    }
}
