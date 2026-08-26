package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorldDraftCandidateConcurrencyTest {
    @Test
    void concurrentRedactionAtSameRevisionHasOneCasWinner() throws Exception {
        WorldDraftTestSupport.Fixture fixture = fixture();
        AtomicInteger reads = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2);
        WorldDraftRedactor delegate = WorldDraftRedactor.schemaGuided();
        WorldDraftRedactor synchronizedRedactor = (payload, metadata, policy) -> {
            try {
                barrier.await();
                return delegate.redact(payload, metadata, policy);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        };
        WorldDraftCandidateService service = new WorldDraftCandidateService(
                WorldDraftTestSupport.authority(fixture, reads), new InMemoryWorldDraftCandidateRepository(),
                synchronizedRedactor, request -> {
                    throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
                }, Clock.fixed(WorldDraftTestSupport.NOW, ZoneOffset.UTC));
        WorldDraftCandidate captured = service.capture("cas", WorldDraftTestSupport.ACCESS, fixture.source());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<WorldDraftCandidate>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> service.redact("cas", captured.revision(),
                        WorldDraftTestSupport.ACCESS, fixture.policy())));
            }
            int successes = 0;
            int conflicts = 0;
            for (Future<WorldDraftCandidate> future : futures) {
                try {
                    assertThat(future.get().state()).isEqualTo(WorldDraftState.REDACTION_REQUIRED);
                    successes++;
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(WorldDraftCandidateException.class)
                            .extracting(error -> ((WorldDraftCandidateException) error).code())
                            .isEqualTo(WorldDraftCandidateException.Code.CAS_CONFLICT);
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(service.find("cas", WorldDraftTestSupport.ACCESS).orElseThrow().revision()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameInputRedactionAndMaterializationFingerprintsAreStableTwentyTimes() {
        Set<String> redactedPayloads = new HashSet<>();
        Set<String> redactionReports = new HashSet<>();
        Set<String> rules = new HashSet<>();
        Set<String> provenances = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            WorldDraftTestSupport.Fixture fixture = fixture();
            AtomicInteger reads = new AtomicInteger();
            ResourceWorldModel base = WorldDraftTestSupport.world("world", 1);
            ResourceWorldModel draftWorld = WorldDraftTestSupport.world("world", 2);
            WorldDraftMaterializer materializer = request -> new WorldDraftMaterializer.MaterializedDraft(
                    request.candidate(), draftWorld, new WorldDraftRule(request.candidate().schemaFingerprint(),
                    request.candidate().effectiveRequestFingerprint(), request.candidate().effectiveResponseFingerprint(),
                    null, request.candidate().redactedPayloadRef()), false);
            WorldDraftCandidateService service = WorldDraftTestSupport.service(fixture, reads, materializer);
            WorldDraftCandidate captured = service.capture("repeat", WorldDraftTestSupport.ACCESS, fixture.source());
            WorldDraftCandidate redacted = service.redact("repeat", captured.revision(),
                    WorldDraftTestSupport.ACCESS, fixture.policy());
            WorldDraftCandidate ready = service.markReviewReady("repeat", redacted.revision(),
                    WorldDraftTestSupport.ACCESS);
            WorldDraftCandidate approved = service.approve("repeat", ready.revision(),
                    WorldDraftTestSupport.ACCESS);
            WorldDraftMaterializer.MaterializedDraft draft = service.materialize("repeat", approved.revision(),
                    WorldDraftTestSupport.ACCESS, base);
            redactedPayloads.add(redacted.redactedPayloadFingerprint());
            redactionReports.add(redacted.redactionReportFingerprint());
            rules.add(draft.rule().fingerprint());
            provenances.add(draft.provenance().materializationFingerprint());
        }
        assertThat(redactedPayloads).hasSize(1);
        assertThat(redactionReports).hasSize(1);
        assertThat(rules).hasSize(1);
        assertThat(provenances).hasSize(1);
    }

    private static WorldDraftTestSupport.Fixture fixture() {
        return WorldDraftTestSupport.fixture(WorldDraftTestSupport.source(WorldDraftTestSupport.TENANT),
                WorldDraftTestSupport.policy(), WorldDraftTestSupport.NOW.plusSeconds(60));
    }
}
