package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationToken;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTrustProvider;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorServingGenerationFenceTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "support", "staging", "sg");
    private static final String PURPOSE = "MIRROR_REHEARSAL";
    private static final String DEPENDENCY = fingerprint('a');

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final VisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    private final MirrorServingGenerationIntegrity integrity =
            new MirrorServingGenerationIntegrity(mapper);

    @Test
    void twoReplicasRejectAnOldGenerationForEveryNewRun() {
        MutableClock clock = new MutableClock(NOW);
        MirrorServingGenerationToken first = token(1, "", 11);
        SharedAuthority authority = new SharedAuthority(first);
        MirrorServingGenerationTrustProvider trust = trust(first);
        MirrorServingGenerationFence replicaA = fence(first, authority, trust, clock);
        MirrorServingGenerationFence replicaB = fence(first, authority, trust, clock);

        replicaA.admitRun();
        replicaB.admitRun();
        authority.current = token(2, first.tokenFingerprint(), 12);

        assertStale(replicaA::admitRun);
        assertStale(replicaB::admitRun);
    }

    @Test
    void cachedFloorAllowsOnlyTheSignedMaximumStalenessWindow() {
        MutableClock clock = new MutableClock(NOW);
        MirrorServingGenerationToken first = token(1, "", 11);
        SharedAuthority authority = new SharedAuthority(first);
        MirrorServingGenerationFence fence = fence(first, authority, trust(first), clock);
        fence.admitRun();

        authority.current = token(2, first.tokenFingerprint(), 12);
        clock.advance(Duration.ofSeconds(4));
        fence.admitOccurrence();

        clock.advance(Duration.ofSeconds(1));
        assertStale(fence::admitOccurrence);
    }

    @Test
    void authorityOutageFailsClosedAfterCacheAgeButNotInsideIt() {
        MutableClock clock = new MutableClock(NOW);
        MirrorServingGenerationToken first = token(1, "", 11);
        SharedAuthority authority = new SharedAuthority(first);
        MirrorServingGenerationFence fence = fence(first, authority, trust(first), clock);
        fence.admitRun();

        authority.available = false;
        clock.advance(Duration.ofSeconds(4));
        fence.admitOccurrence();

        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(fence::admitOccurrence)
                .isInstanceOf(TestControlException.class)
                .extracting(failure -> ((TestControlException) failure).code())
                .isEqualTo("MIRROR_SERVING_GENERATION_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void trustDistributionOutageFailsAsAuthorityUnavailable() {
        MutableClock clock = new MutableClock(NOW);
        MirrorServingGenerationToken first = token(1, "", 11);
        SharedAuthority authority = new SharedAuthority(first);
        MirrorServingGenerationFence fence = fence(
                first, authority,
                MirrorServingGenerationTrustProvider.unavailable(), clock);

        assertThatThrownBy(fence::admitRun)
                .isInstanceOf(TestControlException.class)
                .extracting(failure -> ((TestControlException) failure).code())
                .isEqualTo(
                        "MIRROR_SERVING_GENERATION_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void expiredTokenAndRollbackFloorFailClosed() {
        MutableClock clock = new MutableClock(NOW);
        MirrorServingGenerationToken first = token(1, "", 11);
        SharedAuthority authority = new SharedAuthority(first);
        MirrorServingGenerationFence fence = fence(first, authority, trust(first), clock);
        fence.admitRun();

        clock.advance(Duration.ofHours(1));
        assertThatThrownBy(fence::admitOccurrence)
                .isInstanceOf(TestControlException.class)
                .extracting(failure -> ((TestControlException) failure).code())
                .isEqualTo("MIRROR_SERVING_GENERATION_EXPIRED");

        MirrorServingGenerationToken second =
                token(2, first.tokenFingerprint(), 12);
        SharedAuthority rollback = new SharedAuthority(first);
        MirrorServingGenerationFence secondFence =
                fence(second, rollback, trust(second), new MutableClock(NOW));
        assertThatThrownBy(secondFence::admitRun)
                .isInstanceOf(TestControlException.class)
                .extracting(failure -> ((TestControlException) failure).code())
                .isEqualTo("MIRROR_SERVING_GENERATION_ROLLBACK");
    }

    @Test
    void emitsBoundedRunRefreshOccurrenceCacheAndStaleOutcomes() {
        MutableClock clock = new MutableClock(NOW);
        MirrorServingGenerationToken first = token(1, "", 11);
        SharedAuthority authority = new SharedAuthority(first);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorServingGenerationTelemetry telemetry =
                new MirrorServingGenerationTelemetry(meters);
        MirrorServingGenerationFence fence =
                new MirrorServingGenerationFence(
                        first, authority, trust(first), integrity, clock,
                        telemetry);

        fence.admitRun();
        clock.advance(Duration.ofSeconds(4));
        fence.admitOccurrence();
        authority.current = token(2, first.tokenFingerprint(), 12);
        clock.advance(Duration.ofSeconds(1));
        assertStale(fence::admitOccurrence);

        assertThat(count(
                meters, "run", "current")).isEqualTo(1);
        assertThat(count(
                meters, "occurrence", "cached")).isEqualTo(1);
        assertThat(count(
                meters, "occurrence", "stale")).isEqualTo(1);
    }

    private MirrorServingGenerationFence fence(
            MirrorServingGenerationToken expected,
            SharedAuthority authority,
            MirrorServingGenerationTrustProvider trust,
            Clock clock) {
        return new MirrorServingGenerationFence(
                expected, authority, trust, integrity, clock);
    }

    private MirrorServingGenerationToken token(
            long generation,
            String previous,
            long revocationCursor) {
        return integrity.seal(new MirrorServingGenerationToken.Material(
                        "support-corpus", generation, previous, SCOPE, PURPOSE,
                        DEPENDENCY, revocationCursor, NOW,
                        NOW.plus(Duration.ofHours(1)), Duration.ofSeconds(5)),
                "corpus-authority-a", signer);
    }

    private MirrorServingGenerationTrustProvider trust(
            MirrorServingGenerationToken token) {
        VisualEvidenceSigner.VerificationKey key = signer.key(token.seal().keyId())
                .orElseThrow();
        return MirrorServingGenerationTrustProvider.fixed(
                new MirrorServingGenerationTrustProvider.AuthorityKey(
                        token.seal().authorityId(), key.keyId(), key.algorithm(),
                        key.encodedPublicKey(), NOW.minus(Duration.ofHours(1)),
                        NOW.plus(Duration.ofHours(2)),
                        MirrorServingGenerationTrustProvider.KeyState.ACTIVE));
    }

    private static void assertStale(Runnable admission) {
        assertThatThrownBy(admission::run)
                .isInstanceOf(TestControlException.class)
                .extracting(failure -> ((TestControlException) failure).code())
                .isEqualTo("MIRROR_SERVING_GENERATION_STALE");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static double count(
            SimpleMeterRegistry meters,
            String check,
            String outcome) {
        return meters.find(
                        "resource.gateway.mirror.serving_generation.checks")
                .tags("check", check, "outcome", outcome)
                .counter().count();
    }

    private static final class SharedAuthority
            implements MirrorServingGenerationAuthority {
        private MirrorServingGenerationToken current;
        private boolean available = true;

        private SharedAuthority(MirrorServingGenerationToken current) {
            this.current = current;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Resolution admit(AdmissionRequest request) {
            return available
                    ? Resolution.current(current)
                    : Resolution.unavailable("AUTHORITY_UNAVAILABLE");
        }

        @Override
        public Resolution currentFloor(FloorRequest request) {
            return available
                    ? Resolution.current(current)
                    : Resolution.unavailable("AUTHORITY_UNAVAILABLE");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
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
            return current;
        }
    }
}
