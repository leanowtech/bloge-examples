package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.integration.FailingIntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualRunPayloadGovernanceTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void detachedDatabasePayloadCanExpireWithoutInvalidatingImmutableEvidence() {
        Fixture fixture = databaseFixture(policy("CONFIDENTIAL", 7, Set.of()));
        VisualGraphRunRecord created = fixture.runs().create(record("run-1"));
        VisualGraphRunRecord canonical = fixture.runs().find("run-1").orElseThrow();

        assertThat(created.contextPayload()).containsEntry("customerId", "customer-42");
        assertThat(canonical.contextPayload()).isEmpty();
        assertThat(canonical.outputPayload()).isNull();
        assertThat(canonical.payloadRetention().classification()).isEqualTo("CONFIDENTIAL");
        assertThat(canonical.payloadRetention().payloadFingerprint()).startsWith("sha256:");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT run_json FROM visual_graph_run_records WHERE run_id = 'run-1'", String.class))
                .doesNotContain("customer-42", "approved");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT payload_json FROM visual_run_payload_blobs WHERE run_id = 'run-1'", String.class))
                .contains("customer-42", "approved");

        VisualRunPayloadStatus purged = fixture.payloads().purge("run-1", "purge-request-1",
                "retention-admin", "ticket-42", Instant.parse("2026-07-02T00:00:00Z"));

        assertThat(purged.state()).isEqualTo(VisualRunPayloadStatus.PURGED);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM visual_run_payload_blobs WHERE run_id = 'run-1'", Long.class)).isZero();
        VisualGraphRunRecord evidence = fixture.runs().find("run-1").orElseThrow();
        assertThat(fixture.signer().verify(evidence.evidenceSeal(), evidence.evidenceMaterialFingerprint()).valid())
                .isTrue();
        assertThat(fixture.payloads().events("run-1")).extracting(VisualPayloadLifecycleEvent::type)
                .containsExactly(VisualPayloadLifecycleEvent.CAPTURED, VisualPayloadLifecycleEvent.PURGED);
    }

    @Test
    void legalHoldFreezesExpiryAndReleaseAfterDeadlinePurgesWithSignedChain() {
        Clock clock = Clock.fixed(CAPTURED_AT, ZoneOffset.UTC);
        VisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        InMemoryVisualRunPayloadRepository payloads = new InMemoryVisualRunPayloadRepository(
                policy("CONFIDENTIAL", 1, Set.of()), signer, clock);
        VisualGraphRunRecord run = record("run-held").withIdentity("run-held", CAPTURED_AT);
        payloads.capture(run);

        payloads.placeHold("run-held", "hold-request-1", "legal-case-7", "records-officer", "litigation",
                CAPTURED_AT.plus(Duration.ofHours(12)));
        VisualRunPayloadRepository.Access held = payloads.access("run-held", CAPTURED_AT.plus(Duration.ofDays(3)));

        assertThat(held.status().state()).isEqualTo(VisualRunPayloadStatus.LEGAL_HOLD);
        assertThat(held.readable()).isTrue();
        assertThatThrownBy(() -> payloads.purge("run-held", "purge-request-1", "retention-admin", "expired",
                CAPTURED_AT.plus(Duration.ofDays(3))))
                .isInstanceOf(VisualPayloadGovernanceException.class)
                .satisfies(failure -> assertThat(((VisualPayloadGovernanceException) failure).reason())
                        .isEqualTo(VisualPayloadGovernanceException.Reason.LEGAL_HOLD_ACTIVE));

        VisualRunPayloadStatus released = payloads.releaseHold("run-held", "release-request-1", "legal-case-7",
                "records-officer", "case-closed", CAPTURED_AT.plus(Duration.ofDays(3)));

        assertThat(released.state()).isEqualTo(VisualRunPayloadStatus.PURGED);
        List<VisualPayloadLifecycleEvent> events = payloads.events("run-held");
        assertThat(events).extracting(VisualPayloadLifecycleEvent::type).containsExactly(
                VisualPayloadLifecycleEvent.CAPTURED,
                VisualPayloadLifecycleEvent.HOLD_PLACED,
                VisualPayloadLifecycleEvent.HOLD_RELEASED,
                VisualPayloadLifecycleEvent.PURGED);
        for (int index = 0; index < events.size(); index++) {
            VisualPayloadLifecycleEvent event = events.get(index);
            assertThat(signer.verify(event.evidenceSeal(), event.eventFingerprint()).valid()).isTrue();
            if (index > 0) {
                assertThat(event.previousEventFingerprint()).isEqualTo(events.get(index - 1).eventFingerprint());
            }
        }
    }

    @Test
    void restrictedPolicyDoesNotPersistPayloadAndUnavailableSignerFailsClosed() {
        VisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        InMemoryVisualRunPayloadRepository restricted = new InMemoryVisualRunPayloadRepository(
                policy("RESTRICTED", 0, Set.of()), signer,
                Clock.fixed(CAPTURED_AT, ZoneOffset.UTC));

        VisualRunPayloadRepository.Capture capture = restricted.capture(
                record("run-restricted").withIdentity("run-restricted", CAPTURED_AT));

        assertThat(capture.status().state()).isEqualTo(VisualRunPayloadStatus.NOT_RETAINED);
        assertThat(capture.descriptor().payloadFingerprint()).isEmpty();
        assertThat(restricted.access("run-restricted", CAPTURED_AT).readable()).isFalse();
        assertThat(restricted.events("run-restricted")).singleElement()
                .extracting(VisualPayloadLifecycleEvent::type)
                .isEqualTo(VisualPayloadLifecycleEvent.NOT_RETAINED);

        InMemoryVisualRunPayloadRepository unavailable = new InMemoryVisualRunPayloadRepository(
                policy("PUBLIC", 1, Set.of()), VisualEvidenceSigner.unavailable(),
                Clock.fixed(CAPTURED_AT, ZoneOffset.UTC));
        assertThatThrownBy(() -> unavailable.capture(
                record("run-no-signer").withIdentity("run-no-signer", CAPTURED_AT)))
                .isInstanceOf(VisualPayloadGovernanceException.class)
                .satisfies(failure -> assertThat(((VisualPayloadGovernanceException) failure).reason())
                        .isEqualTo(VisualPayloadGovernanceException.Reason.SIGNING_UNAVAILABLE));
    }

    @Test
    void databaseRestartPreservesPayloadAndTamperingIsDetected() {
        Fixture fixture = databaseFixture(policy("INTERNAL", 14, Set.of()));
        fixture.runs().create(record("run-restart"));

        DatabaseVisualRunPayloadRepository reloadedPayloads = new DatabaseVisualRunPayloadRepository(
                fixture.jdbc(), fixture.mapper(), policy("INTERNAL", 14, Set.of()), fixture.signer());
        reloadedPayloads.init();
        DatabaseVisualGraphRunRepository reloadedRuns = new DatabaseVisualGraphRunRepository(
                fixture.jdbc(), fixture.mapper(), fixture.signer(), null, reloadedPayloads);
        reloadedRuns.init();

        VisualRunPayloadRepository.Access restored = reloadedPayloads.access("run-restart", Instant.now());
        assertThat(restored.payload().context()).containsEntry("customerId", "customer-42");
        assertThat(reloadedRuns.find("run-restart").orElseThrow().contextPayload()).isEmpty();

        fixture.jdbc().update("UPDATE visual_run_payload_blobs SET payload_json = REPLACE(payload_json, ?, ?)",
                "customer-42", "customer-99");
        assertThatThrownBy(() -> reloadedPayloads.access("run-restart", Instant.now()))
                .isInstanceOf(VisualPayloadGovernanceException.class)
                .satisfies(failure -> assertThat(((VisualPayloadGovernanceException) failure).reason())
                        .isEqualTo(VisualPayloadGovernanceException.Reason.CORRUPT));
    }

    @Test
    void runPayloadAndOutboxRollBackWithRunEvidence() {
        Fixture fixture = databaseFixture(policy("PUBLIC", 30, Set.of()));
        DatabaseVisualGraphRunRepository failing = new DatabaseVisualGraphRunRepository(
                fixture.jdbc(), fixture.mapper(), fixture.signer(), new FailingIntegrationChangeEventOutbox(),
                fixture.payloads());
        failing.init();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> failing.create(record("run-atomic"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("simulated outbox failure");

        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM visual_graph_run_records WHERE run_id = 'run-atomic'", Long.class)).isZero();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM visual_run_payload_states WHERE run_id = 'run-atomic'", Long.class)).isZero();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM visual_run_payload_blobs WHERE run_id = 'run-atomic'", Long.class)).isZero();
    }

    @Test
    void lifecycleOutboxFailureRollsBackStateEventAndPayloadDeletion() {
        Fixture fixture = databaseFixture(policy("PUBLIC", 30, Set.of()));
        fixture.runs().create(record("run-transition-atomic"));
        DatabaseVisualRunPayloadRepository failing = new DatabaseVisualRunPayloadRepository(
                fixture.jdbc(), fixture.mapper(), policy("PUBLIC", 30, Set.of()), fixture.signer(),
                new FailingIntegrationChangeEventOutbox());
        failing.init();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> failing.purge(
                "run-transition-atomic", "purge-atomic-1", "retention-admin", "expiry", CAPTURED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("simulated outbox failure");

        assertThat(fixture.payloads().status("run-transition-atomic").orElseThrow().state())
                .isEqualTo(VisualRunPayloadStatus.AVAILABLE);
        assertThat(fixture.payloads().events("run-transition-atomic")).hasSize(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM visual_run_payload_blobs WHERE run_id = 'run-transition-atomic'", Long.class))
                .isOne();
    }

    @Test
    void lifecycleRequestIdIsDurablyIdempotentAndRejectsDifferentContent() {
        Fixture fixture = databaseFixture(policy("PUBLIC", 30, Set.of()));
        fixture.runs().create(record("run-idempotent"));

        VisualRunPayloadStatus first = fixture.payloads().placeHold("run-idempotent", "hold-command-1", "case-1",
                "records-officer", "litigation", CAPTURED_AT.plusSeconds(1));
        DatabaseVisualRunPayloadRepository restarted = new DatabaseVisualRunPayloadRepository(
                fixture.jdbc(), fixture.mapper(), policy("PUBLIC", 30, Set.of()), fixture.signer());
        restarted.init();
        VisualRunPayloadStatus replayed = restarted.placeHold("run-idempotent", "hold-command-1", "case-1",
                "records-officer", "litigation", CAPTURED_AT.plusSeconds(20));

        assertThat(first.revision()).isEqualTo(2);
        assertThat(replayed.revision()).isEqualTo(2);
        assertThat(restarted.events("run-idempotent")).hasSize(2);
        assertThatThrownBy(() -> restarted.placeHold("run-idempotent", "hold-command-1", "case-1",
                "records-officer", "different-reason", CAPTURED_AT.plusSeconds(30)))
                .isInstanceOf(VisualPayloadGovernanceException.class)
                .satisfies(failure -> assertThat(((VisualPayloadGovernanceException) failure).reason())
                        .isEqualTo(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT));

        VisualRunPayloadStatus released = restarted.releaseHold("run-idempotent", "release-command-1", "case-1",
                "records-officer", "case-closed", CAPTURED_AT.plusSeconds(40));
        VisualRunPayloadStatus releaseReplay = restarted.releaseHold("run-idempotent", "release-command-1", "case-1",
                "records-officer", "case-closed", CAPTURED_AT.plusSeconds(50));
        assertThat(released.revision()).isEqualTo(3);
        assertThat(releaseReplay.revision()).isEqualTo(3);
        assertThat(restarted.events("run-idempotent").get(2).holdId()).isEqualTo("case-1");
    }

    @Test
    void twoRepositoryInstancesFenceConcurrentHoldAgainstPurgeWithoutSplitBrain() throws Exception {
        Fixture fixture = databaseFixture(policy("PUBLIC", 30, Set.of()));
        fixture.runs().create(record("run-race"));
        DatabaseVisualRunPayloadRepository second = new DatabaseVisualRunPayloadRepository(
                fixture.jdbc(), fixture.mapper(), policy("PUBLIC", 30, Set.of()), fixture.signer());
        second.init();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(fixture.dataSource());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> hold = executor.submit(() -> transition(manager, ready, start, () ->
                    fixture.payloads().placeHold("run-race", "hold-race-1", "case-race", "records-officer", "case",
                            CAPTURED_AT.plusSeconds(1))));
            Future<Boolean> purge = executor.submit(() -> transition(manager, ready, start, () ->
                    second.purge("run-race", "purge-race-1", "retention-admin", "expiry",
                            CAPTURED_AT.plusSeconds(1))));
            ready.await();
            start.countDown();

            assertThat(List.of(hold.get(), purge.get())).containsExactlyInAnyOrder(true, false);
        }

        VisualRunPayloadStatus status = fixture.payloads().status("run-race").orElseThrow();
        assertThat(status.state()).isIn(VisualRunPayloadStatus.LEGAL_HOLD, VisualRunPayloadStatus.PURGED);
        assertThat(status.revision()).isEqualTo(2);
        assertThat(fixture.payloads().events("run-race")).hasSize(2);
        long blobs = fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM visual_run_payload_blobs WHERE run_id = 'run-race'", Long.class);
        assertThat(blobs).isEqualTo(VisualRunPayloadStatus.LEGAL_HOLD.equals(status.state()) ? 1 : 0);
    }

    private static boolean transition(DataSourceTransactionManager manager,
                                      CountDownLatch ready,
                                      CountDownLatch start,
                                      Runnable action) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            new TransactionTemplate(manager).executeWithoutResult(ignored -> action.run());
            return true;
        } catch (VisualPayloadGovernanceException failure) {
            return false;
        }
    }

    private static Fixture databaseFixture(VisualPayloadGovernancePolicy policy) {
        DataSource dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        VisualEvidenceSigner signer = new DatabaseVisualEvidenceSigner(jdbc);
        DatabaseVisualRunPayloadRepository payloads = new DatabaseVisualRunPayloadRepository(
                jdbc, mapper, policy, signer);
        payloads.init();
        DatabaseVisualGraphRunRepository runs = new DatabaseVisualGraphRunRepository(
                jdbc, mapper, signer, null, payloads);
        runs.init();
        return new Fixture(dataSource, jdbc, mapper, signer, payloads, runs);
    }

    private static VisualPayloadGovernancePolicy policy(String classification, long days, Set<String> groups) {
        return new ConfiguredVisualPayloadGovernancePolicy("test-policy", "7", classification, groups,
                Map.of(classification, Duration.ofDays(days)));
    }

    private static VisualGraphRunRecord record(String runId) {
        GraphDraft draft = new GraphDraft("", "draft-1", 1, "visualPolicy", "tenant-a", "local", "prod",
                "", SchemaEnvelope.opaque(), List.of(), List.of(), Map.of(),
                new GraphDraft.OutputSelection("response", ""));
        VisualGraphRunResponse response = new VisualGraphRunResponse(true, true, true, "visualPolicy", "response",
                Map.of("decision", "approved"), Map.of("response", Map.of("decision", "approved")),
                Map.of("response", "COMPLETED"), 10, Map.of("response", 5L), List.of(), List.of(), null, null,
                "graph visualPolicy {}");
        return VisualGraphRunRecord.storedDraft(draft,
                Map.of("customerId", "customer-42", "apiToken", "secret-token"), response)
                .withIdentity(runId, CAPTURED_AT);
    }

    private record Fixture(DataSource dataSource, JdbcTemplate jdbc, ObjectMapper mapper,
                           VisualEvidenceSigner signer, DatabaseVisualRunPayloadRepository payloads,
                           DatabaseVisualGraphRunRepository runs) {
    }
}
