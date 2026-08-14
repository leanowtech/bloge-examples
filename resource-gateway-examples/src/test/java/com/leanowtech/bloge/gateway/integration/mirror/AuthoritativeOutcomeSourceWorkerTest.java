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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeSourceWorkerTest {
    private static final AuthoritativeOutcomeSourceCheckpointRepository.Policy POLICY =
            new AuthoritativeOutcomeSourceCheckpointRepository.Policy(
                    Duration.ofSeconds(30), Duration.ofSeconds(2),
                    Duration.ofMinutes(1), Duration.ofSeconds(10), 3);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> checkpointNow =
            new AtomicReference<>(Instant.parse("2026-08-03T00:00:00Z"));
    private EmbeddedDatabase database;
    private DatabaseAuthoritativeOutcomeSourceCheckpointRepository checkpoints;
    private DatabaseAuthoritativeOutcomeInboxRepository inbox;
    private AuthoritativeOutcomeObservationIntegrity integrity;

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
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK),
                trustedObservationAuthority(),
                DomainFidelityTestFixtures.CLOCK);
        inbox = new DatabaseAuthoritativeOutcomeInboxRepository(
                jdbc, mapper, integrity, transactions,
                DomainFidelityTestFixtures.CLOCK::instant);
        inbox.init();
        checkpoints = new DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
                jdbc, mapper, transactions, checkpointNow::get);
        checkpoints.init();
        checkpoints.registerLive(AuthoritativeOutcomeSourceTestFixtures.liveRegistration());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void stagesSignsAppendsAndCommitsOneProductionPage() {
        AtomicInteger fetches = new AtomicInteger();
        AuthoritativeOutcomeSourcePage page =
                AuthoritativeOutcomeSourceTestFixtures.livePage(mapper);
        AuthoritativeOutcomeSourceWorker worker = worker(position -> {
            fetches.incrementAndGet();
            assertThat(position.committedSequence()).isZero();
            return AuthoritativeOutcomeSource.FetchResult.page(page);
        });

        var claim = worker.runOne(scope().region(), scope().environmentId(), "worker-a");

        assertThat(claim.outcome())
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Claim.Outcome.ACQUIRED);
        assertThat(fetches).hasValue(1);
        assertThat(checkpoints.find(AuthoritativeOutcomeSourceTestFixtures.liveKey()))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.committedSequence()).isEqualTo(1);
                    assertThat(snapshot.status())
                            .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.ACTIVE);
                    assertThat(snapshot.hasStagedPage()).isFalse();
                });
        assertThat(inbox.findLatestObservation(
                scope(), page.entries().getFirst().observation().observationId()))
                .get()
                .satisfies(observation -> {
                    assertThat(observation.observationSeal().signed()).isTrue();
                    assertThat(observation.reconciliation())
                            .isEqualTo(AuthoritativeOutcomeObservation.Reconciliation.MATCH);
                });
    }

    @Test
    void crashAfterInboxAppendReplaysWithoutDuplicatingTheObservation() {
        AuthoritativeOutcomeSourcePage page =
                AuthoritativeOutcomeSourceTestFixtures.livePage(mapper);
        var abandoned = checkpoints.claimNext(
                scope().region(), scope().environmentId(), "dead-worker", POLICY);
        checkpoints.stage(abandoned.lease(), page);
        AuthoritativeOutcomeObservation signed =
                integrity.sign(page.entries().getFirst().observation());
        inbox.append(signed, "");
        checkpointNow.set(abandoned.lease().expiresAt().plusSeconds(1));
        AtomicInteger fetches = new AtomicInteger();

        worker(position -> {
            fetches.incrementAndGet();
            throw new AssertionError("a staged page must replay without refetch");
        }).runOne(scope().region(), scope().environmentId(), "replacement-worker");

        assertThat(fetches).hasValue(0);
        assertThat(checkpoints.find(AuthoritativeOutcomeSourceTestFixtures.liveKey()))
                .get()
                .extracting(AuthoritativeOutcomeSourceCheckpointRepository.Snapshot::committedSequence)
                .isEqualTo(1L);
        assertThat(inbox.findObservation(scope(), signed.observationId(), 1)).contains(signed);
        assertThat(inbox.findObservation(scope(), signed.observationId(), 2)).isEmpty();
    }

    @Test
    void transientSourceFailureUsesBoundedRetryWithoutLeakingTheProviderException() {
        worker(position -> AuthoritativeOutcomeSource.FetchResult.withoutPage(
                AuthoritativeOutcomeSource.FetchStatus.SOURCE_UNAVAILABLE,
                "CUSTOMER_LEDGER_UNAVAILABLE"))
                .runOne(scope().region(), scope().environmentId(), "worker-a");

        assertThat(checkpoints.find(AuthoritativeOutcomeSourceTestFixtures.liveKey()))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.status())
                            .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.ACTIVE);
                    assertThat(snapshot.consecutiveFailures()).isEqualTo(1);
                    assertThat(snapshot.failureCode())
                            .isEqualTo("RG.MIRROR.OUTCOME_SOURCE.SOURCE_UNAVAILABLE");
                    assertThat(snapshot.nextEligibleAt()).isAfter(checkpointNow.get());
                });
    }

    @Test
    void discontinuousOrWrongScopePageIsQuarantinedBeforeInboxMutation() {
        AuthoritativeOutcomeSourcePage valid =
                AuthoritativeOutcomeSourceTestFixtures.livePage(mapper);
        AuthoritativeOutcomeSourcePage discontinuous =
                new AuthoritativeOutcomeSourcePage(
                        valid.schemaVersion(), "", valid.scope(), valid.connectorId(),
                        valid.connectorGeneration(), valid.streamKind(), valid.streamId(),
                        valid.controlCommandRef(), 2, valid.previousPageFingerprint(),
                        valid.previousCursorRef(), valid.nextCursorRef(), valid.watermark(),
                        valid.producedAt(), valid.entries(), valid.sourceSeal()).seal(mapper);

        worker(position -> AuthoritativeOutcomeSource.FetchResult.page(discontinuous))
                .runOne(scope().region(), scope().environmentId(), "worker-a");

        assertThat(checkpoints.find(AuthoritativeOutcomeSourceTestFixtures.liveKey()))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.status())
                            .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.QUARANTINED);
                    assertThat(snapshot.failureCode())
                            .isEqualTo("RG.MIRROR.OUTCOME_SOURCE.PROTOCOL_REJECTED");
                });
        assertThat(inbox.findLatestObservation(
                scope(), valid.entries().getFirst().observation().observationId())).isEmpty();
    }

    @Test
    void noChangeReleasesLiveButStreamCompleteTerminatesOnlyBackfill() {
        worker(position -> AuthoritativeOutcomeSource.FetchResult.withoutPage(
                AuthoritativeOutcomeSource.FetchStatus.NO_CHANGE, "NO_NEW_FACTS"))
                .runOne(scope().region(), scope().environmentId(), "worker-a");
        assertThat(checkpoints.find(AuthoritativeOutcomeSourceTestFixtures.liveKey()))
                .get()
                .extracting(AuthoritativeOutcomeSourceCheckpointRepository.Snapshot::status)
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.ACTIVE);

        checkpointNow.updateAndGet(value -> value.plusSeconds(10));
        worker(position -> AuthoritativeOutcomeSource.FetchResult.withoutPage(
                AuthoritativeOutcomeSource.FetchStatus.STREAM_COMPLETE, "END_OF_BACKFILL"))
                .runOne(scope().region(), scope().environmentId(), "worker-a");
        assertThat(checkpoints.find(AuthoritativeOutcomeSourceTestFixtures.liveKey()))
                .get()
                .extracting(AuthoritativeOutcomeSourceCheckpointRepository.Snapshot::status)
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.QUARANTINED);
    }

    @Test
    void workerDoesNotClaimWhenAnyTrustBoundaryIsUnavailable() {
        AuthoritativeOutcomeSource unavailable = new Source(position -> {
            throw new AssertionError("unready source must not fetch");
        }) {
            @Override
            public Descriptor descriptor() {
                throw new IllegalStateException("vault unavailable");
            }
        };
        AuthoritativeOutcomeSourceWorker worker = new AuthoritativeOutcomeSourceWorker(
                checkpoints, unavailable, trustedSourceAuthority(), integrity,
                inbox, POLICY, mapper);

        var result = worker.runOne(scope().region(), scope().environmentId(), "worker-a");

        assertThat(result.outcome())
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Claim.Outcome.NO_WORK);
        assertThat(checkpoints.find(AuthoritativeOutcomeSourceTestFixtures.liveKey()))
                .get()
                .extracting(AuthoritativeOutcomeSourceCheckpointRepository.Snapshot::attemptCount)
                .isEqualTo(0L);
    }

    private AuthoritativeOutcomeSourceWorker worker(Fetch fetch) {
        return new AuthoritativeOutcomeSourceWorker(
                checkpoints, new Source(fetch), trustedSourceAuthority(),
                integrity, inbox, POLICY, mapper);
    }

    private static AuthoritativeOutcomeSourceAuthorityVerifier trustedSourceAuthority() {
        return new AuthoritativeOutcomeSourceAuthorityVerifier() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void verifyPage(AuthoritativeOutcomeSourcePage page) {
            }

            @Override
            public void verifyCommand(AuthoritativeOutcomeConnectorControlCommand command) {
            }
        };
    }

    private static AuthoritativeOutcomeAuthorityVerifier trustedObservationAuthority() {
        return new AuthoritativeOutcomeAuthorityVerifier() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void verify(AuthoritativeOutcomeObservation observation) {
            }
        };
    }

    private static CapabilitySnapshot.Scope scope() {
        return AuthoritativeOutcomeSourceTestFixtures.scope();
    }

    @FunctionalInterface
    private interface Fetch {
        AuthoritativeOutcomeSource.FetchResult fetch(
                AuthoritativeOutcomeSource.Position position);
    }

    private static class Source implements AuthoritativeOutcomeSource {
        private final Fetch fetch;

        private Source(Fetch fetch) {
            this.fetch = fetch;
        }

        @Override
        public FetchResult fetch(Position position) {
            return fetch.fetch(position);
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(
                    Descriptor.SCHEMA_VERSION,
                    true, true, true, true, true, true);
        }
    }
}
