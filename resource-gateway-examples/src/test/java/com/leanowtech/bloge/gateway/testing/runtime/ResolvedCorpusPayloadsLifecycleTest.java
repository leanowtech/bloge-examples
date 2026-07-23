package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterValidation;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolvedCorpusPayloadsLifecycleTest {
    private static final Instant MATERIALIZED_AT =
            Instant.parse("2026-07-23T01:00:00Z");
    private static final String REQUEST_FINGERPRINT =
            "sha256:" + "1".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void closeDrainsExistingLeaseThenZeroizesPayloadAndRejectsEscapedSample() {
        byte[] response = "{\"customerId\":\"C-sensitive\"}"
                .getBytes(StandardCharsets.UTF_8);
        ResolvedCorpusPayloads.Sample escaped = ResolvedCorpusPayloads.Sample.response(
                REQUEST_FINGERPRINT, response, List.of(observationRef()),
                List.of("observation-1"), 1, List.of());
        ResolvedCorpusPayloads payloads = payloads(escaped);
        ResolvedCorpusPayloads.GenerationLease lease = payloads.acquireLease();

        payloads.close();

        assertThat(payloads.lifecycle()).isEqualTo(
                new ResolvedCorpusPayloads.GenerationLifecycle(
                        ResolvedCorpusPayloads.GenerationState.DRAINING,
                        1, response.length, 0));
        assertThat(escaped.toRule(mapper)).isNotNull();
        assertThatThrownBy(payloads::acquireLease)
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MIRROR_GENERATION_CLOSED"));

        lease.close();

        assertThat(payloads.lifecycle()).isEqualTo(
                new ResolvedCorpusPayloads.GenerationLifecycle(
                        ResolvedCorpusPayloads.GenerationState.CLOSED,
                        0, 0, response.length));
        assertThatThrownBy(() -> escaped.toRule(mapper))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MIRROR_GENERATION_CLOSED"))
                .hasMessageNotContaining("C-sensitive");
        payloads.close();
        lease.close();
        assertThat(payloads.lifecycle().zeroizedPayloadBytes())
                .isEqualTo(response.length);
    }

    @Test
    void boundAndUnboundViewsShareOneGenerationOwner() {
        ResolvedCorpusPayloads payloads = payloads(
                ResolvedCorpusPayloads.Sample.error(
                        REQUEST_FINGERPRINT, "CUSTOMER_MISSING", "BUSINESS",
                        false, "sha256:" + "2".repeat(64),
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of()));
        ResolvedCorpusPayloads bound = payloads.bindSites(
                Map.of("/root/customer#PRIMARY", capabilityRef()));

        bound.close();

        assertThat(payloads.lifecycle().state())
                .isEqualTo(ResolvedCorpusPayloads.GenerationState.CLOSED);
        assertThat(bound.lifecycle()).isEqualTo(payloads.lifecycle());
        assertThatThrownBy(() -> payloads.bindSites(Map.of()))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MIRROR_GENERATION_CLOSED"));
        assertThatThrownBy(() -> bound.forSite("/root/customer#PRIMARY"))
                .isInstanceOf(TestControlException.class);
    }

    @Test
    void closeDestroysClusterMatchValuesAndRepresentativeResponse() throws Exception {
        byte[] response = mapper.writeValueAsBytes(
                Map.of("customerId", "recorded", "tier", "gold"));
        ResolvedCorpusPayloads.Cluster cluster = new ResolvedCorpusPayloads.Cluster(
                new MirrorArtifactRef("CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
                        "customer-cluster", 1, "sha256:" + "3".repeat(64)),
                List.of(new ResolvedCorpusPayloads.MatchCriterion(
                        "/tier", mapper.valueToTree("gold"))),
                CapabilityCorpusClusterValidation.IdentityMode.IDENTITY_FREE_RESPONSE,
                List.of(), response, List.of(observationRef()),
                List.of("cluster-1"), new ArtifactProvenance.Confidence(
                        0.95, 0.9, 0.99, "HOLDOUT_WILSON_V1"),
                1, List.of());
        ResolvedCorpusPayloads.CapabilityCorpus escaped =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(), List.of(), List.of(cluster));
        ResolvedCorpusPayloads payloads = ResolvedCorpusPayloads.of(List.of(escaped));

        ResolvedCorpusPayloads.ClusterResolution resolution =
                escaped.findCluster(REQUEST_FINGERPRINT,
                        Map.of("tier", "gold"), mapper).orElseThrow();
        ResolvedCorpusPayloads.Sample projected = resolution.sample();
        assertThat(projected.toRule(mapper)).isNotNull();
        resolution.close();
        assertThatThrownBy(() -> projected.toRule(mapper))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MIRROR_GENERATION_CLOSED"));

        payloads.close();

        assertThat(payloads.lifecycle().zeroizedPayloadBytes())
                .isEqualTo(response.length + "\"gold\"".length());
        assertThatThrownBy(() -> escaped.findCluster(REQUEST_FINGERPRINT,
                Map.of("tier", "gold"), mapper))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MIRROR_GENERATION_CLOSED"));
    }

    @Test
    void finalLeaseReleasePerformsZeroizationExactlyOnce() {
        byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
        ResolvedCorpusPayloads payloads = payloads(
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT, response, List.of(observationRef()),
                        List.of("observation-1"), 1, List.of()));
        ResolvedCorpusPayloads.GenerationLease first = payloads.acquireLease();
        ResolvedCorpusPayloads.GenerationLease second = payloads.acquireLease();

        payloads.close();
        first.close();

        assertThat(payloads.lifecycle().state())
                .isEqualTo(ResolvedCorpusPayloads.GenerationState.DRAINING);
        assertThat(payloads.lifecycle().activeLeases()).isOne();

        second.close();

        assertThat(payloads.lifecycle()).isEqualTo(
                new ResolvedCorpusPayloads.GenerationLifecycle(
                        ResolvedCorpusPayloads.GenerationState.CLOSED,
                        0, 0, response.length));
    }

    @Test
    void ownerCloseAndFinalLeaseReleaseAreRaceSafe() throws Exception {
        byte[] response = "{\"race\":\"safe\"}"
                .getBytes(StandardCharsets.UTF_8);
        ResolvedCorpusPayloads payloads = payloads(
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT, response,
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of()));
        ResolvedCorpusPayloads.GenerationLease lease =
                payloads.acquireLease();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> closeOwner = executor.submit(
                    () -> race(ready, start, payloads::close));
            Future<?> releaseLease = executor.submit(
                    () -> race(ready, start, lease::close));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            closeOwner.get(5, TimeUnit.SECONDS);
            releaseLease.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(payloads.lifecycle()).isEqualTo(
                new ResolvedCorpusPayloads.GenerationLifecycle(
                        ResolvedCorpusPayloads.GenerationState.CLOSED,
                        0, 0, response.length));
    }

    @Test
    void nestedOwnersCannotDestroyAnOpenGeneration() {
        ResolvedCorpusPayloads.Sample sample =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT,
                        "{\"owner\":\"generation\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of());
        ResolvedCorpusPayloads.CapabilityCorpus corpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(sample));
        ResolvedCorpusPayloads payloads =
                ResolvedCorpusPayloads.of(List.of(corpus));

        assertThatThrownBy(sample::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("live generation");
        assertThatThrownBy(corpus::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("live generation");
        assertThat(sample.toRule(mapper)).isNotNull();

        payloads.close();

        assertThatThrownBy(() -> sample.toRule(mapper))
                .isInstanceOf(TestControlException.class);
    }

    @Test
    void aSecondGenerationCannotDestroyAnotherOwnersCorpus() {
        byte[] response = "{\"owner\":\"first\"}"
                .getBytes(StandardCharsets.UTF_8);
        ResolvedCorpusPayloads.Sample sample =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT, response,
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of());
        ResolvedCorpusPayloads.CapabilityCorpus corpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(sample));
        ResolvedCorpusPayloads first =
                ResolvedCorpusPayloads.of(List.of(corpus));

        assertThatThrownBy(() ->
                ResolvedCorpusPayloads.of(List.of(corpus)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already belongs");

        assertThat(first.lifecycle()).isEqualTo(
                new ResolvedCorpusPayloads.GenerationLifecycle(
                        ResolvedCorpusPayloads.GenerationState.OPEN,
                        0, response.length, 0));
        assertThat(sample.toRule(mapper)).isNotNull();

        first.close();

        assertThat(first.lifecycle().zeroizedPayloadBytes())
                .isEqualTo(response.length);
    }

    @Test
    void anAttachedSampleCannotTransferThroughAnotherParent() {
        ResolvedCorpusPayloads.Sample sample =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT,
                        "{\"owner\":\"first\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of());
        ResolvedCorpusPayloads first = payloads(sample);

        assertThatThrownBy(() ->
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(sample)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("live generation");

        assertThat(first.lifecycle().state())
                .isEqualTo(ResolvedCorpusPayloads.GenerationState.OPEN);
        assertThat(sample.toRule(mapper)).isNotNull();
        first.close();
    }

    @Test
    void partialParentCleanupSkipsForeignAliasAndClosesItsOtherSamples() {
        ResolvedCorpusPayloads.Sample shared =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT,
                        "{\"owner\":\"first\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of());
        ResolvedCorpusPayloads.Sample local =
                ResolvedCorpusPayloads.Sample.response(
                        "sha256:" + "2".repeat(64),
                        "{\"owner\":\"second\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-2"),
                        1, List.of());
        ResolvedCorpusPayloads.CapabilityCorpus firstCorpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(shared));
        ResolvedCorpusPayloads.CapabilityCorpus partialSecond =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        new MirrorArtifactRef(
                                "CAPABILITY", "customer.lookup.secondary", 1,
                                "sha256:" + "f".repeat(64)),
                        new MirrorArtifactRef(
                                "CAPABILITY_CORPUS_PUBLICATION",
                                "customer-corpus-secondary", 1,
                                "sha256:" + "8".repeat(64)),
                        new MirrorArtifactRef(
                                "CAPABILITY_CORPUS_REVISION",
                                "customer-corpus-secondary", 1,
                                "sha256:" + "9".repeat(64)),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(shared, local));
        ResolvedCorpusPayloads first =
                ResolvedCorpusPayloads.of(List.of(firstCorpus));

        partialSecond.close();

        assertThat(shared.toRule(mapper)).isNotNull();
        assertThatThrownBy(() -> local.toRule(mapper))
                .isInstanceOf(TestControlException.class);
        first.close();
    }

    @Test
    void failedAttachRollsBackLocalChildrenAroundForeignAlias() {
        ResolvedCorpusPayloads.Sample localBefore =
                ResolvedCorpusPayloads.Sample.response(
                        "sha256:" + "0".repeat(64),
                        "{\"owner\":\"second-before\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-0"),
                        1, List.of());
        ResolvedCorpusPayloads.Sample foreign =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT,
                        "{\"owner\":\"first\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of());
        ResolvedCorpusPayloads.Sample localAfter =
                ResolvedCorpusPayloads.Sample.response(
                        "sha256:" + "2".repeat(64),
                        "{\"owner\":\"second-after\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-2"),
                        1, List.of());
        ResolvedCorpusPayloads.CapabilityCorpus firstCorpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(foreign));
        ResolvedCorpusPayloads.CapabilityCorpus secondCorpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        new MirrorArtifactRef(
                                "CAPABILITY", "customer.lookup.secondary", 1,
                                "sha256:" + "f".repeat(64)),
                        new MirrorArtifactRef(
                                "CAPABILITY_CORPUS_PUBLICATION",
                                "customer-corpus-secondary", 1,
                                "sha256:" + "8".repeat(64)),
                        new MirrorArtifactRef(
                                "CAPABILITY_CORPUS_REVISION",
                                "customer-corpus-secondary", 1,
                                "sha256:" + "9".repeat(64)),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(localBefore, foreign, localAfter));
        ResolvedCorpusPayloads first =
                ResolvedCorpusPayloads.of(List.of(firstCorpus));

        assertThatThrownBy(() ->
                ResolvedCorpusPayloads.of(List.of(secondCorpus)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already belongs");

        assertThat(foreign.toRule(mapper)).isNotNull();
        assertThatThrownBy(() -> localBefore.toRule(mapper))
                .isInstanceOf(TestControlException.class);
        assertThatThrownBy(() -> localAfter.toRule(mapper))
                .isInstanceOf(TestControlException.class);
        assertThat(first.lifecycle().state())
                .isEqualTo(ResolvedCorpusPayloads.GenerationState.OPEN);
        first.close();
    }

    @Test
    void failedGenerationAssemblyClosesEveryTransferredCapability() {
        ResolvedCorpusPayloads.Sample first = ResolvedCorpusPayloads.Sample.response(
                REQUEST_FINGERPRINT, "{\"source\":1}".getBytes(StandardCharsets.UTF_8),
                List.of(observationRef()), List.of("observation-1"), 1, List.of());
        ResolvedCorpusPayloads.Sample second = ResolvedCorpusPayloads.Sample.response(
                "sha256:" + "2".repeat(64),
                "{\"source\":2}".getBytes(StandardCharsets.UTF_8),
                List.of(observationRef()), List.of("observation-2"), 1, List.of());
        ResolvedCorpusPayloads.CapabilityCorpus firstCorpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(first));
        ResolvedCorpusPayloads.CapabilityCorpus duplicateCorpus =
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(second));

        assertThatThrownBy(() -> ResolvedCorpusPayloads.of(
                List.of(firstCorpus, duplicateCorpus)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        assertThatThrownBy(() -> first.toRule(mapper))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MIRROR_GENERATION_CLOSED"));
        assertThatThrownBy(() -> second.toRule(mapper))
                .isInstanceOf(TestControlException.class);
    }

    @Test
    void failedCapabilityAssemblyClosesEveryTransferredOutcome() {
        ResolvedCorpusPayloads.Sample first =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT,
                        "{\"source\":1}".getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of());
        ResolvedCorpusPayloads.Sample duplicate =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT,
                        "{\"source\":2}".getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-2"),
                        1, List.of());

        assertThatThrownBy(() ->
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        assertThatThrownBy(() -> first.toRule(mapper))
                .isInstanceOf(TestControlException.class);
        assertThatThrownBy(() -> duplicate.toRule(mapper))
                .isInstanceOf(TestControlException.class);
    }

    @Test
    void failedTrajectoryAssemblyClosesTransferredAttempt() {
        ResolvedCorpusPayloads.Sample attempt =
                ResolvedCorpusPayloads.Sample.response(
                        REQUEST_FINGERPRINT,
                        "{\"status\":\"only\"}".getBytes(StandardCharsets.UTF_8),
                        List.of(observationRef()), List.of("observation-1"),
                        1, List.of());

        assertThatThrownBy(() -> new ResolvedCorpusPayloads.Trajectory(
                REQUEST_FINGERPRINT,
                new MirrorArtifactRef(
                        "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION",
                        "trajectory-1", 1, "sha256:" + "e".repeat(64)),
                List.of(attempt)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 2 and 32");

        assertThatThrownBy(() -> attempt.toRule(mapper))
                .isInstanceOf(TestControlException.class);
    }

    @Test
    void failedClusterAssemblyClosesTransferredMatchCriteria() {
        ResolvedCorpusPayloads.MatchCriterion criterion =
                new ResolvedCorpusPayloads.MatchCriterion(
                        "/tier", mapper.valueToTree("gold"));

        assertThatThrownBy(() -> new ResolvedCorpusPayloads.Cluster(
                new MirrorArtifactRef(
                        "CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
                        "customer-cluster", 1,
                        "sha256:" + "3".repeat(64)),
                List.of(criterion),
                CapabilityCorpusClusterValidation.IdentityMode
                        .IDENTITY_FREE_RESPONSE,
                List.of(),
                "{\"tier\":\"gold\"}".getBytes(StandardCharsets.UTF_8),
                List.of(observationRef()), List.of("cluster-1"),
                new ArtifactProvenance.Confidence(
                        0.95, 0.9, 0.99, "HOLDOUT_WILSON_V1"),
                2, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness");

        assertThatThrownBy(criterion::expectedValue)
                .isInstanceOf(TestControlException.class);
    }

    private static ResolvedCorpusPayloads payloads(
            ResolvedCorpusPayloads.Sample sample) {
        return ResolvedCorpusPayloads.of(List.of(
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef(), publicationRef(), revisionRef(),
                        MATERIALIZED_AT, MATERIALIZED_AT.plusSeconds(60),
                        List.of(sample))));
    }

    private static MirrorArtifactRef capabilityRef() {
        return new MirrorArtifactRef(
                "CAPABILITY", "customer.lookup", 1,
                "sha256:" + "a".repeat(64));
    }

    private static MirrorArtifactRef publicationRef() {
        return new MirrorArtifactRef(
                "CAPABILITY_CORPUS_PUBLICATION", "customer-corpus", 1,
                "sha256:" + "b".repeat(64));
    }

    private static MirrorArtifactRef revisionRef() {
        return new MirrorArtifactRef(
                "CAPABILITY_CORPUS_REVISION", "customer-corpus", 1,
                "sha256:" + "c".repeat(64));
    }

    private static MirrorArtifactRef observationRef() {
        return new MirrorArtifactRef(
                "CAPABILITY_OBSERVATION", "observation-1", 1,
                "sha256:" + "d".repeat(64));
    }

    private static void race(
            CountDownLatch ready,
            CountDownLatch start,
            Runnable action) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("race start timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "race was interrupted", interrupted);
        }
        action.run();
    }
}
