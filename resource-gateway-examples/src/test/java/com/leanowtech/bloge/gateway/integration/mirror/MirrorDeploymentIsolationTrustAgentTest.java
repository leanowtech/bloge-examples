package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationTrustAgentTest {
    private MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures;
    private MutableClock clock;
    private MirrorDeploymentIsolationAuthorityKeySetPublication authority;
    private MirrorDeploymentIsolationAttestationBundle active;
    private InMemoryCache cache;
    private FakeSource source;
    private MirrorDeploymentIsolationAgentSnapshotIntegrity snapshotIntegrity;

    @BeforeEach
    void setUp() {
        fixtures = new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();
        clock = new MutableClock(fixtures.activeClock.instant());
        authority = fixtures.authorityPublication();
        active = activeBundle(fixtures.BOOTSTRAP_REVISION, authority);
        cache = new InMemoryCache(fixtures.mapper, fixtures.bundleIntegrity,
                fixtures.authorityIntegrity);
        source = new FakeSource(active, authority);
        snapshotIntegrity = new MirrorDeploymentIsolationAgentSnapshotIntegrity(
                fixtures.mapper, fixtures.authorityIntegrity, fixtures.bundleIntegrity);
    }

    @Test
    void bootstrapsOnlyExactPinnedHeadAndRestoresDurableActiveGeneration() {
        try (var agent = agent(policy(active), cache, source)) {
            assertThat(agent.refreshNow()).isTrue();

            assertThat(agent.requireActive().cacheGeneration()).isEqualTo(1);
            assertThat(agent.observation().status()).isEqualTo("ACTIVE");
            assertThat(agent.observation().maximumSnapshotAgeSeconds()).isEqualTo(5);
        }

        try (var restored = agent(policy(active), cache, source)) {
            assertThat(restored.requireActive().snapshotFingerprint())
                    .isEqualTo(cache.current().orElseThrow().snapshotFingerprint());
            assertThat(restored.observation().status()).isEqualTo("ACTIVE");
        }
    }

    @Test
    void commitsRevocationWithoutPositiveAuthorityAndNeverReactivatesSameRevision() {
        try (var agent = agent(policy(active), cache, source)) {
            assertThat(agent.refreshNow()).isTrue();
            MirrorDeploymentIsolationAttestationStatusPublication revokedStatus =
                    fixtures.bundleIntegrity.revokedStatus(active.status(),
                            MirrorDeploymentIsolationAttestationStatusPublication.Reason
                                    .OPERATOR_REVOKED,
                            clock.instant());
            source.bundle = fixtures.bundleIntegrity.bundle(active.scope(),
                    active.authorityKeySetRef(), active.attestation(), revokedStatus);
            source.authorityUnavailable = true;

            assertThat(agent.refreshNow()).isTrue();
            assertThat(source.authorityReads).isEqualTo(1);
            assertThat(agent.observation().status()).isEqualTo("REVOKED");
            assertThatThrownBy(agent::requireActive)
                    .isInstanceOf(MirrorDeploymentIsolationTrustAgent
                            .TrustUnavailableException.class)
                    .extracting("reasonCode").isEqualTo("AGENT_CACHE_REVOKED");

            source.bundle = active;
            assertThat(agent.refreshNow()).isFalse();
            assertThat(agent.current().orElseThrow().revoked()).isTrue();
            assertThat(agent.observation().lastFailureCode())
                    .isEqualTo("AGENT_ATTESTATION_STATUS_DISCONTINUITY");
        }
    }

    @Test
    void acceptsOnlyContiguousSuccessorAfterRevocation() {
        try (var agent = agent(policy(active), cache, source)) {
            assertThat(agent.refreshNow()).isTrue();
            var revoked = fixtures.bundleIntegrity.bundle(active.scope(),
                    active.authorityKeySetRef(), active.attestation(),
                    fixtures.bundleIntegrity.revokedStatus(active.status(),
                            MirrorDeploymentIsolationAttestationStatusPublication.Reason
                                    .POLICY_DRIFT,
                            clock.instant()));
            source.bundle = revoked;
            assertThat(agent.refreshNow()).isTrue();

            source.bundle = activeBundle(fixtures.BOOTSTRAP_REVISION + 2, authority);
            assertThat(agent.refreshNow()).isFalse();
            assertThat(agent.current().orElseThrow().revoked()).isTrue();

            source.bundle = activeBundle(fixtures.BOOTSTRAP_REVISION + 1, authority);
            assertThat(agent.refreshNow()).isTrue();
            assertThat(agent.requireActive().attestationBundle().attestation()
                    .material().revision()).isEqualTo(fixtures.BOOTSTRAP_REVISION + 1);
            assertThat(agent.current().orElseThrow().cacheGeneration()).isEqualTo(3);
        }
    }

    @Test
    void preservesLastActiveGenerationOnlyUntilHardMissedRevocationBound() {
        try (var agent = agent(policy(active), cache, source)) {
            assertThat(agent.refreshNow()).isTrue();
            source.failure = new HttpMirrorDeploymentIsolationTrustSource.SourceException(
                    HttpMirrorDeploymentIsolationTrustSource.SourceFailure.UNAVAILABLE,
                    "MIRROR_TRUST_SOURCE_UNAVAILABLE");
            clock.advance(Duration.ofSeconds(2));

            assertThat(agent.refreshNow()).isFalse();
            assertThat(agent.requireActive()).isNotNull();
            assertThat(agent.observation().status()).isEqualTo("ACTIVE_REFRESH_DEGRADED");

            clock.advance(Duration.ofSeconds(4));
            assertThatThrownBy(agent::requireActive)
                    .isInstanceOf(MirrorDeploymentIsolationTrustAgent
                            .TrustUnavailableException.class)
                    .extracting("reasonCode").isEqualTo("AGENT_CACHE_EXPIRED");
            assertThat(agent.observation().available()).isFalse();
            assertThat(agent.observation().status()).isEqualTo("EXPIRED");
        }
    }

    @Test
    void rejectsBootstrapFloorMismatchWithoutWritingTrustOnFirstUse() {
        var exact = policy(active);
        var mismatched = new MirrorDeploymentIsolationTrustAgent.TrustPolicy(
                exact.attestationId(), exact.binding(), exact.bootstrapRoots(),
                new MirrorDeploymentIsolationTrustAgent.BootstrapFloor(
                        exact.bootstrapFloor().authorityGeneration(),
                        exact.bootstrapFloor().authorityPublicationFingerprint(),
                        exact.bootstrapFloor().attestationRevision(),
                        MirrorDeploymentIsolationAttestationRepositoryTestFixtures
                                .fingerprint('e'),
                        exact.bootstrapFloor().statusRevision(),
                        exact.bootstrapFloor().statusFingerprint()));

        try (var agent = agent(mismatched, cache, source)) {
            assertThat(agent.refreshNow()).isFalse();
            assertThat(cache.current()).isEmpty();
            assertThat(agent.observation().lastFailureCode())
                    .isEqualTo("AGENT_BOOTSTRAP_FLOOR_MISMATCH");
        }
    }

    @Test
    void rejectsNonDurableCacheAndSnapshotAgeBelowTwoRequestConvergence() {
        MirrorDeploymentIsolationAgentCache volatileCache = new InMemoryCache(
                fixtures.mapper, fixtures.bundleIntegrity, fixtures.authorityIntegrity) {
            @Override
            public boolean durable() {
                return false;
            }
        };
        assertThatThrownBy(() -> agent(policy(active), volatileCache, source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable cache");

        var settings = new MirrorDeploymentIsolationTrustAgent.Settings(
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(30));
        FakeSource slow = new FakeSource(active, authority,
                new MirrorDeploymentIsolationTrustSource.Descriptor(
                        MirrorDeploymentIsolationTrustSource.Descriptor.SCHEMA_VERSION,
                        true, true, true, true, true,
                        MirrorDeploymentIsolationTrustDistributionProtocol.VERSION, 1_000));
        assertThatThrownBy(() -> new MirrorDeploymentIsolationTrustAgent(
                clock, slow, cache, snapshotIntegrity, fixtures.authorityIntegrity,
                fixtures.bundleIntegrity, fixtures.attestationIntegrity,
                policy(active), settings, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two requests");
    }

    private MirrorDeploymentIsolationTrustAgent agent(
            MirrorDeploymentIsolationTrustAgent.TrustPolicy policy,
            MirrorDeploymentIsolationAgentCache targetCache,
            MirrorDeploymentIsolationTrustSource targetSource) {
        return new MirrorDeploymentIsolationTrustAgent(
                clock, targetSource, targetCache, snapshotIntegrity,
                fixtures.authorityIntegrity, fixtures.bundleIntegrity,
                fixtures.attestationIntegrity, policy,
                new MirrorDeploymentIsolationTrustAgent.Settings(
                        Duration.ofSeconds(1), Duration.ofSeconds(5),
                        Duration.ofSeconds(30)), false);
    }

    private MirrorDeploymentIsolationTrustAgent.TrustPolicy policy(
            MirrorDeploymentIsolationAttestationBundle bootstrap) {
        var resolved = fixtures.authorityPolicyProvider().resolve(
                bootstrap.scope(), bootstrap.attestation().material().deployment()
                        .deploymentScopeId(), fixtures.KEY_SET_ID).orElseThrow();
        return new MirrorDeploymentIsolationTrustAgent.TrustPolicy(
                fixtures.ATTESTATION_ID, resolved.binding(), resolved.roots(),
                new MirrorDeploymentIsolationTrustAgent.BootstrapFloor(
                        bootstrap.authorityKeySetRef().revision(),
                        bootstrap.authorityKeySetRef().fingerprint(),
                        bootstrap.attestation().material().revision(),
                        bootstrap.attestation().attestationFingerprint(),
                        bootstrap.status().material().statusRevision(),
                        bootstrap.status().statusFingerprint()));
    }

    private MirrorDeploymentIsolationAttestationBundle activeBundle(
            long revision,
            MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
        var attestation = fixtures.attestation(revision,
                fixtures.deployment("cluster-a"), fixtures.fingerprint('2'));
        var status = fixtures.bundleIntegrity.activeStatus(fixtures.scope("org-a"),
                publication.artifactRef(), attestation, clock.instant());
        return fixtures.bundleIntegrity.bundle(fixtures.scope("org-a"),
                publication.artifactRef(), attestation, status);
    }

    private static class InMemoryCache implements MirrorDeploymentIsolationAgentCache {
        private final MirrorDeploymentIsolationAgentSnapshotIntegrity integrity;
        private MirrorDeploymentIsolationAgentSnapshot current;

        private InMemoryCache(
                com.fasterxml.jackson.databind.ObjectMapper mapper,
                MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
                MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity) {
            this.integrity = new MirrorDeploymentIsolationAgentSnapshotIntegrity(
                    mapper, authorityIntegrity, bundleIntegrity);
        }

        @Override
        public Optional<MirrorDeploymentIsolationAgentSnapshot> current() {
            return Optional.ofNullable(current);
        }

        @Override
        public MirrorDeploymentIsolationAgentSnapshot replace(
                String expectedSnapshotFingerprint,
                MirrorDeploymentIsolationAgentSnapshot candidate) {
            String observed = current == null ? "" : current.snapshotFingerprint();
            long generation = current == null ? 1 : current.cacheGeneration() + 1;
            if (!observed.equals(expectedSnapshotFingerprint)
                    || generation != candidate.cacheGeneration()
                    || !integrity.canonicalSnapshotVerified(candidate)) {
                throw new AtomicFileMirrorDeploymentIsolationAgentCache
                        .ConcurrentCacheReplacementException();
            }
            current = candidate;
            return current;
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class FakeSource
            implements MirrorDeploymentIsolationTrustSource {
        private MirrorDeploymentIsolationAttestationBundle bundle;
        private MirrorDeploymentIsolationAuthorityKeySetPublication authority;
        private RuntimeException failure;
        private boolean authorityUnavailable;
        private int authorityReads;
        private final Descriptor descriptor;

        private FakeSource(
                MirrorDeploymentIsolationAttestationBundle bundle,
                MirrorDeploymentIsolationAuthorityKeySetPublication authority) {
            this(bundle, authority, new Descriptor(Descriptor.SCHEMA_VERSION,
                    true, true, true, true, true,
                    MirrorDeploymentIsolationTrustDistributionProtocol.VERSION, 100));
        }

        private FakeSource(
                MirrorDeploymentIsolationAttestationBundle bundle,
                MirrorDeploymentIsolationAuthorityKeySetPublication authority,
                Descriptor descriptor) {
            this.bundle = bundle;
            this.authority = authority;
            this.descriptor = descriptor;
        }

        @Override
        public MirrorDeploymentIsolationAttestationBundle latestAttestation() {
            if (failure != null) {
                throw failure;
            }
            return bundle;
        }

        @Override
        public MirrorDeploymentIsolationAuthorityKeySetPublication currentAuthority(
                MirrorArtifactRef authorityRef) {
            authorityReads++;
            if (authorityUnavailable) {
                throw new HttpMirrorDeploymentIsolationTrustSource.SourceException(
                        HttpMirrorDeploymentIsolationTrustSource.SourceFailure.UNAVAILABLE,
                        "MIRROR_TRUST_AUTHORITY_UNAVAILABLE");
            }
            return authority;
        }

        @Override
        public Descriptor descriptor() {
            return descriptor;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
