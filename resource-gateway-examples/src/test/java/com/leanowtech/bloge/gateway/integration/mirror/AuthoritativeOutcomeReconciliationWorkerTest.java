package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeReconciliationWorkerTest {
    private static final AuthoritativeOutcomeInboxPolicy POLICY =
            new AuthoritativeOutcomeInboxPolicy(
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(10),
                    3,
                    Duration.ofDays(30));

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(
                    DomainFidelityTestFixtures.NOW);
    private final MutableClock clock =
            new MutableClock(now);

    private EmbeddedDatabase database;
    private AuthoritativeOutcomeObservationIntegrity integrity;
    private DatabaseAuthoritativeOutcomeInboxRepository repository;
    private AuthoritativeOutcomeObservation pending;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(database);
        integrity = new AuthoritativeOutcomeObservationIntegrity(
                mapper,
                InMemoryVisualEvidenceSigner.usingClock(clock),
                alwaysTrusted(),
                clock);
        repository =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        jdbc,
                        mapper,
                        integrity,
                        transactions,
                        now::get);
        repository.init();
        pending = integrity.sign(
                AuthoritativeOutcomeTestFixtures.pending());
        repository.append(pending, "");
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void verifiesSignsAndAtomicallySettlesOneConnectorSuccessor() {
        now.updateAndGet(value ->
                value.plusSeconds(1));
        AtomicInteger calls = new AtomicInteger();
        AuthoritativeOutcomeConnector connector =
                connector((current, observedAt, control) -> {
                    calls.incrementAndGet();
                    assertThat(current).isEqualTo(pending);
                    return AuthoritativeOutcomeConnector
                            .Result.successor(
                                    matchedSuccessor(
                                            current,
                                            observedAt));
                });
        AuthoritativeOutcomeReconciliationWorker worker =
                worker(connector);

        AuthoritativeOutcomeInboxRepository.Claim claim =
                worker.runOne(
                        pending.scope().region(),
                        pending.scope().environmentId(),
                        "worker-a");

        assertThat(claim.outcome()).isEqualTo(
                AuthoritativeOutcomeInboxRepository
                        .Claim.Outcome.ACQUIRED);
        assertThat(calls).hasValue(1);
        AuthoritativeOutcomeInboxEntry entry =
                repository.findEntry(
                        pending.scope(),
                        pending.observationId())
                        .orElseThrow();
        assertThat(entry.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.SETTLED);
        assertThat(entry.currentRevision()).isEqualTo(2);
        assertThat(repository.findLatestObservation(
                pending.scope(),
                pending.observationId()))
                .get()
                .extracting(
                        AuthoritativeOutcomeObservation
                                ::reconciliation)
                .isEqualTo(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
    }

    @Test
    void requeuesNoChangeWithoutCallingTheSignerForASuccessor() {
        AuthoritativeOutcomeReconciliationWorker worker =
                worker(connector((current, observedAt, control) ->
                        AuthoritativeOutcomeConnector
                                .Result.noChange()));

        worker.runOne(
                pending.scope().region(),
                pending.scope().environmentId(),
                "worker-a");

        AuthoritativeOutcomeInboxEntry entry =
                repository.findEntry(
                        pending.scope(),
                        pending.observationId())
                        .orElseThrow();
        assertThat(entry.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.QUEUED);
        assertThat(entry.currentRevision()).isEqualTo(1);
        assertThat(entry.consecutiveFailures()).isZero();
    }

    @Test
    void persistsOnlyStableRetryableConnectorFailure() {
        AuthoritativeOutcomeReconciliationWorker worker =
                worker(connector((current, observedAt, control) -> {
                    throw new AuthoritativeOutcomeConnector
                            .Failure(
                            AuthoritativeOutcomeConnector
                                    .FailureReason
                                    .AUTHORITY_UNAVAILABLE);
                }));

        worker.runOne(
                pending.scope().region(),
                pending.scope().environmentId(),
                "worker-a");

        AuthoritativeOutcomeInboxEntry entry =
                repository.findEntry(
                        pending.scope(),
                        pending.observationId())
                        .orElseThrow();
        assertThat(entry.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.QUEUED);
        assertThat(entry.consecutiveFailures()).isEqualTo(1);
        assertThat(entry.failureCode()).isEqualTo(
                "RG.MIRROR.OUTCOME.AUTHORITY_UNAVAILABLE");
    }

    @Test
    void quarantinesAnInvalidSignedOrDiscontinuousConnectorResult() {
        AuthoritativeOutcomeReconciliationWorker worker =
                worker(connector((current, observedAt, control) ->
                        AuthoritativeOutcomeConnector
                                .Result.successor(current)));

        worker.runOne(
                pending.scope().region(),
                pending.scope().environmentId(),
                "worker-a");

        AuthoritativeOutcomeInboxEntry entry =
                repository.findEntry(
                        pending.scope(),
                        pending.observationId())
                        .orElseThrow();
        assertThat(entry.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry
                        .Status.QUARANTINED);
        assertThat(entry.failureCode()).isEqualTo(
                "RG.MIRROR.OUTCOME.RESULT_INVALID");
        assertThat(entry.reconciliation()).isEqualTo(
                AuthoritativeOutcomeObservation
                        .Reconciliation.PENDING);
    }

    @Test
    void carriesRenewedFenceAcrossLongConnectorWork() {
        now.updateAndGet(value ->
                value.plusSeconds(1));
        AtomicReference<Instant> renewed =
                new AtomicReference<>();
        AuthoritativeOutcomeReconciliationWorker worker =
                worker(connector((current, observedAt, control) -> {
                    now.updateAndGet(value ->
                            value.plusSeconds(10));
                    renewed.set(control.heartbeat());
                    return AuthoritativeOutcomeConnector
                            .Result.successor(
                                    matchedSuccessor(
                                            current,
                                            now.get()));
                }));

        worker.runOne(
                pending.scope().region(),
                pending.scope().environmentId(),
                "worker-a");

        assertThat(renewed.get())
                .isAfter(now.get());
        assertThat(repository.findEntry(
                pending.scope(),
                pending.observationId()))
                .get()
                .extracting(
                        AuthoritativeOutcomeInboxEntry::status)
                .isEqualTo(
                        AuthoritativeOutcomeInboxEntry
                                .Status.SETTLED);
        assertThat(repository.lifecycle(
                pending.scope(),
                pending.observationId(),
                0,
                20))
                .extracting(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.OBSERVATION_APPENDED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.CLAIMED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.HEARTBEAT,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.SUCCESSOR_APPENDED);
    }

    @Test
    void doesNotClaimWhenConnectorReadinessIsFalse() {
        AuthoritativeOutcomeConnector unavailable =
                new AuthoritativeOutcomeConnector() {
                    @Override
                    public boolean ready() {
                        return false;
                    }

                    @Override
                    public Result reconcile(
                            AuthoritativeOutcomeObservation current,
                            Instant observedAt,
                            ExecutionControl control) {
                        throw new AssertionError(
                                "unready connector must not execute");
                    }
                };
        AuthoritativeOutcomeReconciliationWorker worker =
                worker(unavailable);

        AuthoritativeOutcomeInboxRepository.Claim claim =
                worker.runOne(
                        pending.scope().region(),
                        pending.scope().environmentId(),
                        "worker-a");

        assertThat(claim.outcome()).isEqualTo(
                AuthoritativeOutcomeInboxRepository
                        .Claim.Outcome.NO_WORK);
        assertThat(repository.findEntry(
                pending.scope(),
                pending.observationId()))
                .get()
                .extracting(
                        AuthoritativeOutcomeInboxEntry
                                ::attemptCount)
                .isEqualTo(0L);
    }

    @Test
    void reducesRawConnectorExceptionToGenericQuarantineCode() {
        AuthoritativeOutcomeReconciliationWorker worker =
                worker(connector((current, observedAt, control) -> {
                    throw new IllegalStateException(
                            "customer-account=secret");
                }));

        worker.runOne(
                pending.scope().region(),
                pending.scope().environmentId(),
                "worker-a");

        AuthoritativeOutcomeInboxEntry entry =
                repository.findEntry(
                        pending.scope(),
                        pending.observationId())
                        .orElseThrow();
        assertThat(entry.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry
                        .Status.QUARANTINED);
        assertThat(entry.failureCode()).isEqualTo(
                "RG.MIRROR.OUTCOME.UNEXPECTED_FAILURE");
        assertThat(entry.toString())
                .doesNotContain("customer-account", "secret");
    }

    private AuthoritativeOutcomeReconciliationWorker worker(
            AuthoritativeOutcomeConnector connector) {
        return new AuthoritativeOutcomeReconciliationWorker(
                repository,
                connector,
                integrity,
                POLICY);
    }

    private static AuthoritativeOutcomeConnector connector(
            Reconcile reconcile) {
        return new AuthoritativeOutcomeConnector() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public Result reconcile(
                    AuthoritativeOutcomeObservation current,
                    Instant observedAt,
                    ExecutionControl control) {
                return reconcile.apply(
                        current, observedAt, control);
            }
        };
    }

    private static AuthoritativeOutcomeObservation
    matchedSuccessor(
            AuthoritativeOutcomeObservation current,
            Instant reconciledAt) {
        return AuthoritativeOutcomeTestFixtures.successor(
                current,
                AuthoritativeOutcomeObservation
                        .Reconciliation.MATCH,
                List.of(
                        AuthoritativeOutcomeTestFixtures.fact(
                                "settlement-ledger",
                                "settlement-worker-success",
                                'a',
                                'e',
                                true)),
                List.of(
                        AuthoritativeOutcomeTestFixtures.watermark(
                                "settlement-ledger",
                                AuthoritativeOutcomeTestFixtures
                                        .WINDOW_CLOSES_AT)),
                reconciledAt);
    }

    private static AuthoritativeOutcomeAuthorityVerifier
    alwaysTrusted() {
        return new AuthoritativeOutcomeAuthorityVerifier() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void verify(
                    AuthoritativeOutcomeObservation observation) {
                assertThat(observation.authorityWatermarks())
                        .isNotEmpty();
            }
        };
    }

    @FunctionalInterface
    private interface Reconcile {
        AuthoritativeOutcomeConnector.Result apply(
                AuthoritativeOutcomeObservation current,
                Instant observedAt,
                AuthoritativeOutcomeConnector.ExecutionControl
                        control);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(
                AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException(
                        "test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
