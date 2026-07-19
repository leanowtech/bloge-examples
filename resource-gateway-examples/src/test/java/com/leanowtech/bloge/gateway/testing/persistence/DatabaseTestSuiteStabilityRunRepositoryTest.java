package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationExternalArchiveProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures.CaseMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionProgress;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStopRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceiptSet;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirement;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirementService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePageRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecyclePageIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerRangeIntegrity;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityRunRepositoryTest {
    private static final String REQUEST_FINGERPRINT =
            TestSuiteStabilityProtocolFixtures.fingerprint('9');

    private ObjectMapper mapper;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DatabaseTestSuiteStabilityRunRepository repository;
    private TestSuiteStabilityAttestationService attestations;
    private TestSuiteStabilityObservationAttestationService observationAttestations;
    private TestSuiteStabilityObservationFloorRetirementAttestationService
            retirementAttestations;
    private TestSuiteStabilityObservationFloorRetirementService retirementService;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-stability-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseTestSuiteStabilityRunRepository(jdbc, mapper);
        repository.init();
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        attestations = new TestSuiteStabilityAttestationService(mapper, signer);
        observationAttestations = new TestSuiteStabilityObservationAttestationService(
                mapper, signer, attestations);
        retirementAttestations =
                new TestSuiteStabilityObservationFloorRetirementAttestationService(
                        mapper, signer);
        retirementService = new TestSuiteStabilityObservationFloorRetirementService(
                mapper, repository, retirementAttestations,
                TestSuiteStabilityObservationExternalArchiveProtocolFixtures.authority(mapper));
    }

    @Test
    void signedTerminalAnalysisRoundTripsOnlyInsideItsScope() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease lease = checkpointed(
                record, acquired(record, "owner-a"));

        assertThat(complete(record, lease)).isEqualTo(record);
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .contains(record);
        assertThat(repository.findByClientRequestId(
                "tenant-a", "test", "stability-request")).contains(record);
        assertThat(repository.find("tenant-b", "test", record.stabilityRunId())).isEmpty();
        assertThat(repository.find("tenant-a", "staging", record.stabilityRunId())).isEmpty();
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .isEmpty();
    }

    @Test
    void claimCreatesAnEmptyScopedProgressJournalWithALiveOwner() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));

        TestSuiteStabilityLeaseClaim claim = repository.claim(
                leaseRequest(record, "owner-a"));

        assertThat(claim.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
        assertThat(claim.progress().stabilityRunId()).isEqualTo(record.stabilityRunId());
        assertThat(claim.progress().suiteRef()).isEqualTo(record.evidence().suiteRef());
        assertThat(claim.progress().plannedAttempts())
                .isEqualTo(record.evidence().requestedAttempts());
        assertThat(claim.progress().attempts()).isEmpty();
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.liveOwner()).isTrue();
                    assertThat(snapshot.progress()).isEqualTo(claim.progress());
                });
        assertThat(repository.findProgress("tenant-b", "test", record.stabilityRunId()))
                .isEmpty();
    }

    @Test
    void checkpointAppendsOnlyTheNextAttemptAndRenewsTheExactFenceAtomically() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease lease = acquired(record, "owner-a");
        TestSuiteStabilityExecutionProgress.AttemptReference first = reference(record, 0);

        var checkpoint = repository.checkpoint(lease, first,
                Duration.ofSeconds(60), Duration.ofDays(30));

        assertThat(checkpoint.lease().expiresAt()).isAfter(lease.expiresAt());
        assertThat(checkpoint.progress().attempts()).containsExactly(first);
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .get().extracting(value -> value.progress().attempts())
                .isEqualTo(List.of(first));

        assertThatThrownBy(() -> repository.checkpoint(checkpoint.lease(), reference(record, 2),
                Duration.ofSeconds(30), Duration.ofDays(30)))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT));
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .get().extracting(value -> value.progress().attempts())
                .isEqualTo(List.of(first));
        assertThat(repository.renew(checkpoint.lease(), Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void takeoverResumesTheDurablePrefixAndFencesTheExpiredOwner() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease old = acquired(record, "owner-a");
        TestSuiteStabilityExecutionLease afterFirst = repository.checkpoint(
                old, reference(record, 0), Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        jdbc.update("""
                UPDATE rg_test_suite_stability_execution_leases
                SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE stability_run_id = ?
                """, old.stabilityRunId());

        TestSuiteStabilityLeaseClaim takeover = repository.claim(
                leaseRequest(record, "owner-b"));

        assertThat(takeover.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
        assertThat(takeover.lease().epoch()).isEqualTo(1);
        assertThat(takeover.progress().attempts()).containsExactly(reference(record, 0));
        assertThatThrownBy(() -> repository.checkpoint(
                afterFirst, reference(record, 1), Duration.ofSeconds(30), Duration.ofDays(30)))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.LEASE_LOST));

        TestSuiteStabilityExecutionLease completeLease = checkpointRemaining(
                record, takeover.lease(), 1);
        assertThat(complete(record, completeLease)).isEqualTo(record);
    }

    @Test
    void incompleteProgressCannotPublishAndTheTransactionLeavesItRecoverable() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease lease = acquired(record, "owner-a");
        lease = repository.checkpoint(lease, reference(record, 0),
                Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        TestSuiteStabilityExecutionLease incomplete = lease;

        assertThatThrownBy(() -> complete(record, incomplete))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT));
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId())).isEmpty();
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .get().satisfies(snapshot -> {
                    assertThat(snapshot.liveOwner()).isTrue();
                    assertThat(snapshot.progress().completedAttempts()).isEqualTo(1);
                });
        assertThat(repository.renew(incomplete, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void statisticalV3AnalysisRoundTripsWithoutLosingItsSignedAssessment() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.statisticalStableEvidence();
        TestSuiteStabilityRunRecord record = record(evidence, "tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));

        complete(record, checkpointed(record, acquired(record, "owner-a")));

        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .get().extracting(value -> value.evidence().statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStatus.SATISFIED);
    }

    @Test
    void baselineConditionalV4RoundTripsWithoutLosingItsExactRateBound() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.rateStableEvidence();
        TestSuiteStabilityRunRecord record = record(evidence, "tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));

        complete(record, checkpointed(record, acquired(record, "owner-a")));

        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .get().satisfies(value -> {
                    assertThat(value.evidence().schemaVersion())
                            .isEqualTo(TestSuiteStabilityEvidence.SCHEMA_VERSION_V4);
                    assertThat(value.evidence().statisticalAssessment()
                            .comparisonAttempts()).isEqualTo(29);
                    assertThat(value.evidence().statisticalAssessment()
                            .upperInstabilityRateBps()).isEqualTo(982);
                });
    }

    @Test
    void anytimeValidV5MayConsumeOnlyItsExactFirstBoundaryPrefix() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.sequentialStableEvidence();
        TestSuiteStabilityRunRecord record = record(evidence, "tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease lease = acquired(record, "owner-a");
        TestSuiteStabilityExecutionLease after56 = checkpointRemaining(record, lease, 0, 56);

        assertThatThrownBy(() -> complete(record, after56))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT));

        TestSuiteStabilityExecutionLease after57 = checkpointRemaining(record, after56, 56, 57);
        assertThat(complete(record, after57)).isEqualTo(record);
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .get().satisfies(value -> {
                    assertThat(value.evidence().requestedAttempts()).isEqualTo(100);
                    assertThat(value.evidence().attempts()).hasSize(57);
                    assertThat(value.evidence().statisticalAssessment().stopReason())
                            .isEqualTo(TestSuiteStabilityEvidence.StatisticalStopReason
                                    .E_VALUE_THRESHOLD_REACHED);
                });
    }

    @Test
    void exactSuiteHistoryIsChronologicalScopeBoundAndFingerprintLocked() {
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord first = trendRecord(
                '1', now.minusSeconds(300), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord second = trendRecord(
                '2', now.minusSeconds(200), now.plusSeconds(3_600));
        completeTrend(first);
        completeTrend(second);

        var history = repository.history("tenant-a", "test",
                TestSuiteStabilityProtocolFixtures.SUITE_REF,
                now.minusSeconds(600), now, 10);

        assertThat(history.complete()).isTrue();
        assertThat(history.records()).extracting(TestSuiteStabilityRunRecord::stabilityRunId)
                .containsExactly(first.stabilityRunId(), second.stabilityRunId());
        assertThat(repository.history("tenant-b", "test",
                TestSuiteStabilityProtocolFixtures.SUITE_REF,
                now.minusSeconds(600), now, 10).records()).isEmpty();
        TestSuiteExecutionRequest.SuiteRef forgedRef = new TestSuiteExecutionRequest.SuiteRef(
                TestSuiteStabilityProtocolFixtures.SUITE_REF.suiteId(),
                TestSuiteStabilityProtocolFixtures.SUITE_REF.revision(),
                TestSuiteStabilityProtocolFixtures.fingerprint('9'));
        assertThatThrownBy(() -> repository.history("tenant-a", "test", forgedRef,
                now.minusSeconds(600), now, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indexed projection");
    }

    @Test
    void exactSuiteHistoryMakesRetentionLossAndBudgetTruncationExplicit() throws Exception {
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord initiallyLive = trendRecord(
                '1', now.minusSeconds(300), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord retained = trendRecord(
                '2', now.minusSeconds(200), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord overflow = trendRecord(
                '3', now.minusSeconds(100), now.plusSeconds(3_600));
        completeTrend(initiallyLive);
        completeTrend(retained);
        completeTrend(overflow);
        TestSuiteStabilityRunRecord expired = new TestSuiteStabilityRunRecord(
                initiallyLive.stabilityRunId(), initiallyLive.clientRequestId(),
                initiallyLive.requestFingerprint(), initiallyLive.tenantId(),
                initiallyLive.organizationId(), initiallyLive.projectId(),
                initiallyLive.environmentId(), initiallyLive.actorId(),
                initiallyLive.classification(), initiallyLive.evidenceFingerprint(),
                initiallyLive.evidence(), initiallyLive.attestation(),
                initiallyLive.createdAt(), now.minusSeconds(100));
        jdbc.update("""
                UPDATE rg_test_suite_stability_records
                SET expires_at = ?, record_json = ?
                WHERE stability_run_id = ?
                """, java.sql.Timestamp.from(expired.expiresAt()),
                mapper.writeValueAsString(expired), expired.stabilityRunId());

        var history = repository.history("tenant-a", "test",
                TestSuiteStabilityProtocolFixtures.SUITE_REF,
                now.minusSeconds(600), now, 2);

        assertThat(history.complete()).isFalse();
        assertThat(history.expiredMatchingRuns()).isEqualTo(1);
        assertThat(history.truncated()).isTrue();
        assertThat(history.records()).extracting(TestSuiteStabilityRunRecord::stabilityRunId)
                .containsExactly(retained.stabilityRunId(), overflow.stabilityRunId());
    }

    @Test
    void terminalPublicationAtomicallyAppendsAContiguousSignedObservationLedger() {
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord first = trendRecord(
                'a', now.minusSeconds(20), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord second = trendRecord(
                'b', now.minusSeconds(10), now.plusSeconds(3_600));

        completeTrend(first);
        completeTrend(second);

        var head = repository.observationLedgerHead(
                "tenant-a", "test", first.evidence().suiteRef()).orElseThrow();
        var entries = repository.observations(
                "tenant-a", "test", first.evidence().suiteRef(), 0, 10);
        assertThat(head.latestSequence()).isEqualTo(2);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(value -> value.sequence())
                .containsExactly(1L, 2L);
        assertThat(entries.get(0).previousObservationId()).isBlank();
        assertThat(entries.get(1).previousObservationId()).isEqualTo(
                entries.get(0).observation().evidence().observationId());
        assertThat(head.latestObservationId()).isEqualTo(
                entries.get(1).observation().evidence().observationId());
        assertThat(head.latestEntryFingerprint()).isEqualTo(
                entries.get(1).entryFingerprint());
        assertThat(repository.observations(
                "tenant-a", "test", first.evidence().suiteRef(), 1, 10))
                .containsExactly(entries.get(1));
        assertThat(repository.observationLedgerHead(
                "tenant-b", "test", first.evidence().suiteRef())).isEmpty();
        assertThatThrownBy(() -> repository.observations(
                "tenant-a", "test", first.evidence().suiteRef(), 3, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beyond the committed head");
    }

    @Test
    void lockedObservationRangeBindsFloorHeadPredecessorAndExactPages() {
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord first = trendRecord(
                '1', now.minusSeconds(30), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord second = trendRecord(
                '2', now.minusSeconds(20), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord third = trendRecord(
                '3', now.minusSeconds(10), now.plusSeconds(3_600));
        completeTrend(first);
        completeTrend(second);
        completeTrend(third);

        var firstPage = repository.observationRange(
                "tenant-a", "test", first.evidence().suiteRef(), 0, 2).orElseThrow();
        assertThat(TestSuiteStabilityObservationLedgerRangeIntegrity.valid(
                mapper, firstPage)).isTrue();
        assertThat(firstPage.floorSequence()).isEqualTo(1);
        assertThat(firstPage.floorPreviousObservationId()).isBlank();
        assertThat(firstPage.floorPreviousEntryFingerprint()).isBlank();
        assertThat(firstPage.entries()).extracting(value -> value.sequence())
                .containsExactly(1L, 2L);
        assertThat(firstPage.floorObservationId()).isEqualTo(
                firstPage.entries().getFirst().observation().evidence().observationId());
        assertThat(firstPage.floorEntryFingerprint()).isEqualTo(
                firstPage.entries().getFirst().entryFingerprint());
        assertThat(firstPage.head().latestSequence()).isEqualTo(3);
        assertThat(firstPage.hasMore()).isTrue();

        var finalPage = repository.observationRange(
                "tenant-a", "test", first.evidence().suiteRef(), 2, 2).orElseThrow();
        assertThat(finalPage.entries()).hasSize(1);
        assertThat(finalPage.entries().getFirst().sequence()).isEqualTo(3);
        assertThat(finalPage.previousObservationId()).isEqualTo(
                firstPage.entries().getLast().observation().evidence().observationId());
        assertThat(finalPage.previousEntryFingerprint()).isEqualTo(
                firstPage.entries().getLast().entryFingerprint());
        assertThat(finalPage.head()).isEqualTo(firstPage.head());
        assertThat(finalPage.hasMore()).isFalse();
        assertThat(TestSuiteStabilityObservationLedgerRangeIntegrity.valid(
                mapper, finalPage)).isTrue();

        var exhausted = repository.observationRange(
                "tenant-a", "test", first.evidence().suiteRef(), 3, 2).orElseThrow();
        assertThat(exhausted.entries()).isEmpty();
        assertThat(exhausted.previousObservationId()).isEqualTo(
                finalPage.entries().getFirst().observation().evidence().observationId());
        assertThat(exhausted.previousEntryFingerprint()).isEqualTo(
                finalPage.entries().getFirst().entryFingerprint());
        assertThat(exhausted.hasMore()).isFalse();
    }

    @Test
    void ledgerReadRejectsIndexedProjectionTamperingAndSequenceGaps() {
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord first = trendRecord(
                '7', now.minusSeconds(20), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord second = trendRecord(
                '8', now.minusSeconds(10), now.plusSeconds(3_600));
        completeTrend(first);
        completeTrend(second);
        var original = repository.observations(
                "tenant-a", "test", first.evidence().suiteRef(), 0, 10);

        jdbc.update("""
                UPDATE rg_test_suite_stability_observations
                SET observation_fingerprint = ?
                WHERE ledger_sequence = 2
                """, TestSuiteStabilityProtocolFixtures.fingerprint('f'));
        assertThatThrownBy(() -> repository.observations(
                "tenant-a", "test", first.evidence().suiteRef(), 0, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("columns contradict");

        jdbc.update("""
                UPDATE rg_test_suite_stability_observations
                SET observation_fingerprint = ?
                WHERE ledger_sequence = 2
                """, original.get(1).observation().evidenceFingerprint());
        jdbc.update("""
                DELETE FROM rg_test_suite_stability_observations
                WHERE ledger_sequence = 2
                """);
        assertThatThrownBy(() -> repository.observations(
                "tenant-a", "test", first.evidence().suiteRef(), 0, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no latest ledger row");
    }

    @Test
    void ledgerHeadReadRejectsIndexedScopeTamperingAndOrphanRows() {
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord record = trendRecord(
                '9', now.minusSeconds(10), now.plusSeconds(3_600));
        completeTrend(record);
        var originalHead = repository.observationLedgerHead(
                "tenant-a", "test", record.evidence().suiteRef()).orElseThrow();

        jdbc.update("""
                UPDATE rg_test_suite_stability_observations
                SET entry_fingerprint = ?
                """, TestSuiteStabilityProtocolFixtures.fingerprint('f'));
        assertThatThrownBy(() -> repository.observationLedgerHead(
                "tenant-a", "test", record.evidence().suiteRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latest ledger row");
        jdbc.update("""
                UPDATE rg_test_suite_stability_observations
                SET entry_fingerprint = ?
                """, originalHead.latestEntryFingerprint());

        jdbc.update("""
                UPDATE rg_test_suite_stability_observation_heads
                SET tenant_id = 'tenant-b'
                """);
        assertThatThrownBy(() -> repository.observationLedgerHead(
                "tenant-a", "test", record.evidence().suiteRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indexed scope");

        jdbc.update("""
                UPDATE rg_test_suite_stability_observation_heads
                SET tenant_id = 'tenant-a'
                """);
        jdbc.update("DELETE FROM rg_test_suite_stability_observation_heads");
        assertThatThrownBy(() -> repository.observations(
                "tenant-a", "test", record.evidence().suiteRef(), 0, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a committed head");
    }

    @Test
    void corruptedLedgerHeadRollsBackTerminalProgressAndLeaseMutation() {
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord first = trendRecord(
                'c', now.minusSeconds(20), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord second = trendRecord(
                'd', now.minusSeconds(10), now.plusSeconds(3_600));
        completeTrend(first);
        TestSuiteStabilityExecutionLease secondLease = checkpointed(
                second, acquired(second, "owner-d"));
        jdbc.update("""
                UPDATE rg_test_suite_stability_observation_heads
                SET head_fingerprint = ?
                """, TestSuiteStabilityProtocolFixtures.fingerprint('f'));

        assertThatThrownBy(() -> complete(second, secondLease))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("head fingerprint");

        assertThat(repository.find("tenant-a", "test", second.stabilityRunId())).isEmpty();
        assertThat(repository.findProgress(
                "tenant-a", "test", second.stabilityRunId())).isPresent();
        assertThat(repository.claim(leaseRequest(second, "owner-e")).state())
                .isEqualTo(TestSuiteStabilityLeaseClaim.State.IN_PROGRESS);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observations",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void observationFromAnotherSourceCannotBeAttachedToATerminalRecord() {
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord first = trendRecord(
                'e', now.minusSeconds(20), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord second = trendRecord(
                'f', now.minusSeconds(10), now.plusSeconds(3_600));
        TestSuiteStabilityExecutionLease lease = checkpointed(
                second, acquired(second, "owner-f"));

        assertThatThrownBy(() -> repository.complete(second, observation(first), lease))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source-consistent");
        assertThat(repository.find("tenant-a", "test", second.stabilityRunId())).isEmpty();
    }

    @Test
    void scopedIdempotencyKeyCannotBeReboundAfterTerminalPublication() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        complete(record, checkpointed(record, acquired(record, "owner-a")));
        TestSuiteStabilityLeaseRequest changed = new TestSuiteStabilityLeaseRequest(
                record.stabilityRunId(), record.tenantId(), record.environmentId(),
                record.clientRequestId(), TestSuiteStabilityProtocolFixtures.fingerprint('8'),
                record.evidence().suiteRef(), record.classification(),
                record.evidence().requestedAttempts(), "owner-b", Duration.ofSeconds(30),
                Duration.ofDays(30));

        assertThatThrownBy(() -> repository.claim(changed))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void rejectsTamperedFingerprintOrUnsignedMaterialBeforePersistence() {
        TestSuiteStabilityRunRecord valid = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityRunRecord tampered = new TestSuiteStabilityRunRecord(
                valid.stabilityRunId(), valid.clientRequestId(), valid.requestFingerprint(),
                valid.tenantId(), valid.organizationId(), valid.projectId(),
                valid.environmentId(), valid.actorId(), valid.classification(),
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), valid.evidence(),
                valid.attestation(), valid.createdAt(), valid.expiresAt());
        TestSuiteStabilityExecutionLease lease = acquired(valid, "owner-a");

        assertThatThrownBy(() -> repository.complete(tampered, observation(valid), lease))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed terminal stability record");
    }

    @Test
    void activeOwnerIsObservedAndExactRenewalAndReleaseAreFenced() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease first = acquired(record, "owner-a");

        TestSuiteStabilityLeaseClaim duplicate = repository.claim(
                leaseRequest(record, "owner-b"));
        assertThat(duplicate.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.IN_PROGRESS);
        assertThat(duplicate.retryAfterSeconds()).isBetween(1L, 30L);

        TestSuiteStabilityExecutionLease renewed = repository.renew(
                first, Duration.ofSeconds(60)).orElseThrow();
        assertThat(renewed.expiresAt()).isAfter(first.expiresAt());
        TestSuiteStabilityExecutionLease staleEpoch = new TestSuiteStabilityExecutionLease(
                renewed.stabilityRunId(), renewed.tenantId(), renewed.environmentId(),
                renewed.clientRequestId(), renewed.requestFingerprint(), renewed.ownerId(),
                renewed.epoch() + 1, renewed.expiresAt());
        assertThat(repository.release(staleEpoch)).isFalse();
        assertThat(repository.release(renewed)).isTrue();
        assertThat(repository.claim(leaseRequest(record, "owner-c")).state())
                .isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
    }

    @Test
    void expiredLeaseTakeoverIncrementsEpochAndOldOwnerCannotPublish() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease old = acquired(record, "owner-a");
        jdbc.update("""
                UPDATE rg_test_suite_stability_execution_leases
                SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE stability_run_id = ?
                """, old.stabilityRunId());

        TestSuiteStabilityLeaseClaim takeover = repository.claim(
                leaseRequest(record, "owner-b"));
        assertThat(takeover.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
        assertThat(takeover.lease().epoch()).isEqualTo(1);
        assertThatThrownBy(() -> complete(record, old))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.LEASE_LOST));
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId())).isEmpty();
        assertThat(complete(record, checkpointed(record, takeover.lease())))
                .isEqualTo(record);
    }

    @Test
    void orphanCleanupIsBoundedAndCannotDeleteAReacquiredLiveLease() {
        TestSuiteStabilityRunRecord first = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease expired = acquired(first, "owner-a");
        jdbc.update("""
                UPDATE rg_test_suite_stability_execution_leases
                SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE stability_run_id = ?
                """, expired.stabilityRunId());

        assertThat(repository.purgeExpiredLeases(1)).isEqualTo(1);
        TestSuiteStabilityExecutionLease live = acquired(first, "owner-b");
        assertThat(repository.purgeExpiredLeases(1)).isZero();
        assertThat(repository.renew(live, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void stopAtomicallyConsumesResumableStateAndPermanentlyFencesTheOldOwner() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease owner = acquired(record, "owner-a");
        owner = repository.checkpoint(owner, reference(record, 0),
                Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        TestSuiteStabilityExecutionLease stoppedOwner = owner;

        TestSuiteStabilityExecutionStop stop = repository.stop(stopRequest(
                record, TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_CANCELLED"));

        assertThat(repository.findStop("tenant-a", "test", record.stabilityRunId()))
                .contains(stop);
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .isEmpty();
        assertThat(repository.renew(stoppedOwner, Duration.ofSeconds(30))).isEmpty();
        assertThat(repository.claim(leaseRequest(record, "owner-b")))
                .satisfies(claim -> {
                    assertThat(claim.state())
                            .isEqualTo(TestSuiteStabilityLeaseClaim.State.STOPPED);
                    assertThat(claim.stop()).isEqualTo(stop);
                });
        assertThatThrownBy(() -> repository.checkpoint(stoppedOwner, reference(record, 1),
                Duration.ofSeconds(30), Duration.ofDays(30)))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.LEASE_LOST));
        assertThatThrownBy(() -> complete(record, stoppedOwner))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT));
    }

    @Test
    void stopBeforeClaimIsStrictlyIdempotentAndCannotBeRebound() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionStopRequest request = stopRequest(
                record, TestSuiteStabilityExecutionStop.Reason.DEADLINE_EXCEEDED,
                "RG.TEST.STABILITY_DEADLINE_EXCEEDED");

        TestSuiteStabilityExecutionStop first = repository.stop(request);

        assertThat(repository.stop(request)).isEqualTo(first);
        assertThat(repository.claim(leaseRequest(record, "owner-a")).stop())
                .isEqualTo(first);
        TestSuiteStabilityExecutionStopRequest changed =
                stopRequest(record, TestSuiteStabilityExecutionStop.Reason.WORKER_FAILED,
                        "RG.TEST.STABILITY_WORKER_FAILED");
        assertThatThrownBy(() -> repository.stop(changed))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void signedTerminalEvidenceCannotBeReplacedByALateStop() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        complete(record, checkpointed(record, acquired(record, "owner-a")));

        assertThatThrownBy(() -> repository.stop(stopRequest(
                record, TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_CANCELLED")))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT));
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .contains(record);
        assertThat(repository.findStop("tenant-a", "test", record.stabilityRunId()))
                .isEmpty();
    }

    @Test
    void corruptedStopMaterialFailsClosedBeforeItCanFenceAClaim() throws Exception {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        repository.stop(stopRequest(record,
                TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_CANCELLED"));
        String stored = jdbc.queryForObject("""
                SELECT stop_json FROM rg_test_suite_stability_execution_stops
                WHERE stability_run_id = ?
                """, String.class, record.stabilityRunId());
        var tampered = mapper.readTree(stored);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered)
                .put("failureCode", "RG.TEST.STABILITY_WORKER_FAILED");
        jdbc.update("""
                UPDATE rg_test_suite_stability_execution_stops SET stop_json = ?
                WHERE stability_run_id = ?
                """, mapper.writeValueAsString(tampered), record.stabilityRunId());

        assertThatThrownBy(() -> repository.findStop(
                "tenant-a", "test", record.stabilityRunId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint is invalid");
        assertThatThrownBy(() -> repository.claim(leaseRequest(record, "owner-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint is invalid");
    }

    @Test
    void twoRepositoryInstancesProduceOneCrossReplicaOwner() throws Exception {
        DatabaseTestSuiteStabilityRunRepository replica =
                new DatabaseTestSuiteStabilityRunRepository(new JdbcTemplate(dataSource), mapper);
        replica.init();
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<TestSuiteStabilityLeaseClaim> left = pool.submit(() -> {
                start.await();
                return repository.claim(leaseRequest(record, "owner-a"));
            });
            Future<TestSuiteStabilityLeaseClaim> right = pool.submit(() -> {
                start.await();
                return replica.claim(leaseRequest(record, "owner-b"));
            });
            start.countDown();

            assertThat(List.of(left.get().state(), right.get().state()))
                    .containsExactlyInAnyOrder(TestSuiteStabilityLeaseClaim.State.ACQUIRED,
                            TestSuiteStabilityLeaseClaim.State.IN_PROGRESS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCrossReplicaTerminalsSerializeOneExactSuiteObservationChain()
            throws Exception {
        DatabaseTestSuiteStabilityRunRepository replica =
                new DatabaseTestSuiteStabilityRunRepository(new JdbcTemplate(dataSource), mapper);
        replica.init();
        Instant now = Instant.now();
        TestSuiteStabilityRunRecord first = trendRecord(
                '5', now.minusSeconds(20), now.plusSeconds(3_600));
        TestSuiteStabilityRunRecord second = trendRecord(
                '6', now.minusSeconds(10), now.plusSeconds(3_600));
        TestSuiteStabilityExecutionLease firstLease = checkpointed(
                first, acquired(first, "owner-5"));
        TestSuiteStabilityExecutionLease secondLease = checkpointed(
                second, acquired(second, "owner-6"));
        TestSuiteStabilityObservation firstObservation = observation(first);
        TestSuiteStabilityObservation secondObservation = observation(second);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<TestSuiteStabilityRunRecord> left = pool.submit(() -> {
                start.await();
                return repository.complete(first, firstObservation, firstLease);
            });
            Future<TestSuiteStabilityRunRecord> right = pool.submit(() -> {
                start.await();
                return replica.complete(second, secondObservation, secondLease);
            });
            start.countDown();

            assertThat(List.of(left.get(), right.get()))
                    .containsExactlyInAnyOrder(first, second);
            var entries = repository.observations(
                    "tenant-a", "test", first.evidence().suiteRef(), 0, 10);
            assertThat(entries).hasSize(2);
            assertThat(entries).extracting(value -> value.sequence())
                    .containsExactly(1L, 2L);
            assertThat(entries.get(1).previousObservationId()).isEqualTo(
                    entries.get(0).observation().evidence().observationId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void signedFloorRetirementAtomicallyArchivesPrefixAndPreservesRangeClosure() {
        Instant now = Instant.now();
        for (char identity : new char[] {'1', '2', '3', '4', '5'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(60 - identity),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        var rolloutFloor = repository.observationLedgerFloor(
                "tenant-a", "test", suite).orElseThrow();
        assertThat(rolloutFloor.floorSequence()).isEqualTo(1);
        assertThat(rolloutFloor.retirementGeneration()).isZero();

        var result = retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 2, 2,
                TestSuiteStabilityProtocolFixtures.fingerprint('a'), retainUntil());

        assertThat(result.status())
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementService.Status.RETIRED);
        assertThat(result.successorFloor().floorSequence()).isEqualTo(3);
        assertThat(result.successorFloor().retirementGeneration()).isEqualTo(1);
        assertThat(result.retirement().evidence().archiveSegment().retiredEntries())
                .extracting(value -> value.sequence()).containsExactly(1L, 2L);
        assertThat(result.retirement().evidence().archiveSegment().successorEntry().sequence())
                .isEqualTo(3);
        assertThat(retirementAttestations.verify(
                result.retirement().evidence(), result.retirement().attestation()))
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementAttestationService
                        .Verification.VERIFIED);
        assertThat(repository.findObservationFloorRetirement(
                result.retirement().evidence().retirementId()))
                .contains(result.retirement());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observations", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archives", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_retirements",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archive_receipts",
                Integer.class)).isEqualTo(1);
        assertThat(repository.findObservationExternalArchiveReceiptSet(
                result.retirement().evidence().retirementId()))
                .contains(result.archiveReceiptSet());

        assertThatThrownBy(() -> repository.observations(
                "tenant-a", "test", suite, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before the retained floor");
        assertThat(repository.observations("tenant-a", "test", suite, 2, 10))
                .extracting(value -> value.sequence()).containsExactly(3L, 4L, 5L);
        var range = repository.observationRange(
                "tenant-a", "test", suite, 2, 10).orElseThrow();
        assertThat(TestSuiteStabilityObservationLedgerRangeIntegrity.valid(mapper, range)).isTrue();
        assertThat(range.floorSequence()).isEqualTo(3);
        assertThat(range.floorPreviousObservationId()).isEqualTo(
                result.successorFloor().previousObservationId());
        assertThat(range.floorPreviousEntryFingerprint()).isEqualTo(
                result.successorFloor().previousEntryFingerprint());
        assertThat(range.head().coverageFrom()).isEqualTo(
                result.successorFloor().coverageFrom());
    }

    @Test
    void rolloutLifecyclePageIsEmptyAndClosesAtGenerationZero() {
        Instant now = Instant.now();
        completeTrend(trendRecord('a', now.minusSeconds(10), now.plusSeconds(3_600)));
        var request = new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 2, "", "");

        var page = repository.observationLedgerLifecyclePage(
                "tenant-a", "test", request).orElseThrow();

        assertThat(TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.valid(
                mapper, page)).isTrue();
        assertThat(page.retirements()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.startingFloor()).isEqualTo(page.currentFloor());
        assertThat(page.terminalFloor()).isEqualTo(page.currentFloor());
        assertThat(page.currentFloor().retirementGeneration()).isZero();
    }

    @Test
    void lifecyclePagesPreservePinnedContinuityAcrossMultipleRetirements() {
        Instant now = Instant.now();
        for (char identity : new char[] {'1', '2', '3', '4'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(60 - identity),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        var rollout = repository.observationLedgerFloor(
                "tenant-a", "test", suite).orElseThrow();
        String policy = TestSuiteStabilityProtocolFixtures.fingerprint('e');
        var firstRetirement = retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1, policy,
                retainUntil());
        var secondRetirement = retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1, policy,
                retainUntil());
        var firstRequest = new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                suite, 0, 1, "", "");

        var firstPage = repository.observationLedgerLifecyclePage(
                "tenant-a", "test", firstRequest).orElseThrow();

        assertThat(TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.valid(
                mapper, firstPage)).isTrue();
        assertThat(firstPage.startingFloor()).isEqualTo(rollout);
        assertThat(firstPage.retirements()).containsExactly(firstRetirement.retirement());
        assertThat(firstPage.terminalFloor()).isEqualTo(firstRetirement.successorFloor());
        assertThat(firstPage.currentFloor()).isEqualTo(secondRetirement.successorFloor());
        assertThat(firstPage.hasMore()).isTrue();

        var continuation = new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                suite, firstPage.terminalFloor().retirementGeneration(), 1,
                firstPage.currentFloor().floorFingerprint(),
                firstPage.head().headFingerprint());
        var finalPage = repository.observationLedgerLifecyclePage(
                "tenant-a", "test", continuation).orElseThrow();

        assertThat(TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.valid(
                mapper, finalPage)).isTrue();
        assertThat(finalPage.startingFloor()).isEqualTo(firstPage.terminalFloor());
        assertThat(finalPage.retirements()).containsExactly(secondRetirement.retirement());
        assertThat(finalPage.terminalFloor()).isEqualTo(firstPage.currentFloor());
        assertThat(finalPage.currentFloor()).isEqualTo(firstPage.currentFloor());
        assertThat(finalPage.head()).isEqualTo(firstPage.head());
        assertThat(finalPage.hasMore()).isFalse();
    }

    @Test
    void lifecycleReadFailsClosedWhenARetirementGenerationIsMissing() {
        Instant now = Instant.now();
        for (char identity : new char[] {'1', '2', '3', '4'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(60 - identity),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        String policy = TestSuiteStabilityProtocolFixtures.fingerprint('f');
        retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1, policy,
                retainUntil());
        retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1, policy,
                retainUntil());
        jdbc.update("""
                DELETE FROM rg_test_suite_stability_observation_retirements
                WHERE retirement_generation = 1
                """);
        var request = new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                suite, 0, 1, "", "");

        assertThatThrownBy(() -> repository.observationLedgerLifecyclePage(
                "tenant-a", "test", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generations are incomplete");
    }

    @Test
    void lifecycleContinuationRejectsAValidButDisconnectedPredecessor() throws Exception {
        Instant now = Instant.now();
        for (char identity : new char[] {'1', '2', '3', '4'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(60 - identity),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        String policy = TestSuiteStabilityProtocolFixtures.fingerprint('a');
        var first = retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1, policy,
                retainUntil());
        retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1, policy,
                retainUntil());
        var firstPage = repository.observationLedgerLifecyclePage(
                "tenant-a", "test",
                new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                        TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                        suite, 0, 1, "", "")).orElseThrow();
        TestSuiteStabilityObservationFloorRetirement disconnected = retirementWithPolicy(
                first.retirement(), TestSuiteStabilityProtocolFixtures.fingerprint('b'));
        assertThat(TestSuiteStabilityObservationFloorRetirementIntegrity.successorFloor(
                mapper, disconnected)).isNotEqualTo(first.successorFloor());
        jdbc.update("""
                UPDATE rg_test_suite_stability_observation_retirements
                SET retirement_id = ?, evidence_fingerprint = ?,
                    attestation_fingerprint = ?, retirement_fingerprint = ?,
                    retired_at = ?, retirement_json = ?
                WHERE scope_fingerprint = ? AND retirement_generation = 1
                """, disconnected.evidence().retirementId(),
                disconnected.evidenceFingerprint(), disconnected.attestationFingerprint(),
                disconnected.retirementFingerprint(),
                Timestamp.from(disconnected.evidence().retiredAt()),
                mapper.writeValueAsString(disconnected),
                disconnected.evidence().scopeFingerprint());
        var continuation = new TestSuiteStabilityObservationLedgerLifecyclePageRequest(
                TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION,
                suite, 1, 1, firstPage.currentFloor().floorFingerprint(),
                firstPage.head().headFingerprint());

        assertThatThrownBy(() -> repository.observationLedgerLifecyclePage(
                "tenant-a", "test", continuation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predecessor does not close on its floor");
    }

    @Test
    void exactRetirementReplayIsStableAcrossALaterFloorGeneration() {
        Instant now = Instant.now();
        for (char identity : new char[] {'6', '7', '8', '9'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(60 - identity),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        String policy = TestSuiteStabilityProtocolFixtures.fingerprint('b');
        var first = retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1, policy,
                retainUntil());

        assertThat(repository.commitObservationFloorRetirement(
                first.retirement(), first.archiveReceiptSet()))
                .isEqualTo(first.successorFloor());
        assertThatThrownBy(() -> repository.commitObservationFloorRetirement(
                first.retirement(), externalReceiptSet(first.retirement())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different archive receipts");
        var second = retirementService.retire(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1, policy,
                retainUntil());

        assertThat(second.successorFloor().retirementGeneration()).isEqualTo(2);
        assertThat(second.successorFloor().floorSequence()).isEqualTo(3);
        assertThat(second.retirement().evidence().previousFloor())
                .isEqualTo(first.successorFloor());
        assertThat(second.retirement().evidence().archiveSegment().previousObservationId())
                .isEqualTo(first.successorFloor().previousObservationId());
        assertThat(repository.commitObservationFloorRetirement(
                first.retirement(), first.archiveReceiptSet()))
                .isEqualTo(first.successorFloor());
        assertThat(repository.observationLedgerFloor("tenant-a", "test", suite))
                .contains(second.successorFloor());
    }

    @Test
    void concurrentAppendInvalidatesPinnedRetirementWithoutPartialArchive() {
        Instant now = Instant.now();
        for (char identity : new char[] {'a', 'b', 'c'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        var evidence = repository.planObservationFloorRetirement(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('c')).orElseThrow();
        TestSuiteStabilityObservationFloorRetirement signed = signedRetirement(evidence);
        completeTrend(trendRecord('d', now.minusSeconds(5), now.plusSeconds(3_600)));

        var receipts = externalReceiptSet(signed);
        assertThatThrownBy(() -> repository.commitObservationFloorRetirement(signed, receipts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("floor or head pin changed");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archive_receipts",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archives", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_retirements",
                Integer.class)).isZero();
        assertThat(repository.observationLedgerFloor("tenant-a", "test", suite))
                .get().extracting(value -> value.retirementGeneration()).isEqualTo(0L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observations", Integer.class))
                .isEqualTo(4);
    }

    @Test
    void missingActivePrefixRowRollsBackArchiveRetirementAndFloorMutation() {
        Instant now = Instant.now();
        for (char identity : new char[] {'1', '2', '3'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        var evidence = repository.planObservationFloorRetirement(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('d')).orElseThrow();
        TestSuiteStabilityObservationFloorRetirement signed = signedRetirement(evidence);
        jdbc.update("""
                DELETE FROM rg_test_suite_stability_observations
                WHERE ledger_sequence = 1
                """);

        var receipts = externalReceiptSet(signed);
        assertThatThrownBy(() -> repository.commitObservationFloorRetirement(signed, receipts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active ledger row");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archive_receipts",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archives", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_retirements",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT retirement_generation
                FROM rg_test_suite_stability_observation_floors
                """, Long.class)).isZero();
    }

    @Test
    void archiveProjectionTamperingMakesSignedRetirementUnreadable() {
        Instant now = Instant.now();
        for (char identity : new char[] {'4', '5', '6'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
        var result = retirementService.retire(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('e'), retainUntil());
        jdbc.update("""
                UPDATE rg_test_suite_stability_observation_archives
                SET segment_fingerprint = ?
                """, TestSuiteStabilityProtocolFixtures.fingerprint('f'));

        assertThatThrownBy(() -> repository.findObservationFloorRetirement(
                result.retirement().evidence().retirementId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archive columns contradict");
    }

    @Test
    void receiptProjectionTamperingMakesExternalAcknowledgementUnreadable() {
        Instant now = Instant.now();
        for (char identity : new char[] {'4', '5', '6'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
        var result = retirementService.retire(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('e'), retainUntil());
        jdbc.update("""
                UPDATE rg_test_suite_stability_observation_archive_receipts
                SET required_copies = 2
                """);

        assertThatThrownBy(() -> repository.findObservationExternalArchiveReceiptSet(
                result.retirement().evidence().retirementId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt columns contradict");
    }

    @Test
    void unavailableRetirementSignerLeavesLedgerAndFloorUntouched() {
        Instant now = Instant.now();
        for (char identity : new char[] {'7', '8', '9'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
        var unavailable = new TestSuiteStabilityObservationFloorRetirementService(
                mapper, repository,
                new TestSuiteStabilityObservationFloorRetirementAttestationService(
                        mapper, VisualEvidenceSigner.unavailable()),
                TestSuiteStabilityObservationExternalArchiveProtocolFixtures.authority(mapper));

        var result = unavailable.retire(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('1'), retainUntil());

        assertThat(result.status())
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementService.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityObservationFloorRetirementAttestationService
                        .SIGNER_UNAVAILABLE);
        assertThat(repository.observationLedgerFloor(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF))
                .get().extracting(value -> value.retirementGeneration()).isEqualTo(0L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archives", Integer.class))
                .isZero();
    }

    @Test
    void unavailableExternalArchiveLeavesEveryLocalRetirementSurfaceUntouched() {
        prepareRetirementPrefix();
        var service = new TestSuiteStabilityObservationFloorRetirementService(
                mapper, repository, retirementAttestations,
                TestSuiteStabilityObservationExternalArchiveAuthority.unavailable());

        var result = service.retire(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('2'), retainUntil());

        assertThat(result.status())
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementService.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveAuthority.ARCHIVE_UNAVAILABLE);
        assertNoLocalRetirementMutation(3);
    }

    @Test
    void invalidExternalReceiptVerificationLeavesEveryLocalSurfaceUntouched() {
        prepareRetirementPrefix();
        TestSuiteStabilityObservationExternalArchiveAuthority delegate =
                TestSuiteStabilityObservationExternalArchiveProtocolFixtures.authority(mapper);
        TestSuiteStabilityObservationExternalArchiveAuthority invalid =
                verifyingAs(delegate,
                        TestSuiteStabilityObservationExternalArchiveAuthority.Verification.INVALID);
        var service = new TestSuiteStabilityObservationFloorRetirementService(
                mapper, repository, retirementAttestations, invalid);

        var result = service.retire(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('3'), retainUntil());

        assertThat(result.status())
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementService.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveAuthority.ARCHIVE_RECEIPT_INVALID);
        assertNoLocalRetirementMutation(3);
    }

    @Test
    void authenticatedExternalArchiveConflictLeavesEveryLocalSurfaceUntouched() {
        prepareRetirementPrefix();
        TestSuiteStabilityObservationExternalArchiveAuthority delegate =
                TestSuiteStabilityObservationExternalArchiveProtocolFixtures.authority(mapper);
        TestSuiteStabilityObservationExternalArchiveAuthority conflict =
                new TestSuiteStabilityObservationExternalArchiveAuthority() {
                    @Override
                    public TestSuiteStabilityObservationExternalArchiveReceiptSet archive(
                            TestSuiteStabilityObservationFloorRetirement retirement,
                            Instant retainUntil) {
                        throw new ExternalArchiveException(
                                ExternalArchiveException.Reason.AUTHENTICATED_CONFLICT);
                    }

                    @Override
                    public Verification verify(
                            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
                        return Verification.INVALID;
                    }

                    @Override
                    public Descriptor descriptor() {
                        return delegate.descriptor();
                    }

                    @Override
                    public Snapshot snapshot() {
                        return delegate.snapshot();
                    }
                };
        var service = new TestSuiteStabilityObservationFloorRetirementService(
                mapper, repository, retirementAttestations, conflict);

        var result = service.retire(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('4'), retainUntil());

        assertThat(result.status())
                .isEqualTo(TestSuiteStabilityObservationFloorRetirementService.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveAuthority.ARCHIVE_CONFLICT);
        assertNoLocalRetirementMutation(3);
    }

    @Test
    void restartBackfillsAnExactGenerationZeroFloorForLegacyLedger() {
        Instant now = Instant.now();
        completeTrend(trendRecord('f', now.minusSeconds(10), now.plusSeconds(3_600)));
        jdbc.update("DELETE FROM rg_test_suite_stability_observation_floors");

        DatabaseTestSuiteStabilityRunRepository restarted =
                new DatabaseTestSuiteStabilityRunRepository(
                        new JdbcTemplate(dataSource), mapper);
        restarted.init();

        assertThat(restarted.observationLedgerFloor(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF))
                .get().satisfies(floor -> {
                    assertThat(floor.floorSequence()).isEqualTo(1);
                    assertThat(floor.retirementGeneration()).isZero();
                    assertThat(floor.previousObservationId()).isBlank();
                    assertThat(floor.latestRetirementId()).isBlank();
                });
    }

    @Test
    void concurrentRestartsSerializeLegacyFloorBackfill() throws Exception {
        Instant now = Instant.now();
        completeTrend(trendRecord('0', now.minusSeconds(10), now.plusSeconds(3_600)));
        jdbc.update("DELETE FROM rg_test_suite_stability_observation_floors");
        DatabaseTestSuiteStabilityRunRepository leftRepository =
                new DatabaseTestSuiteStabilityRunRepository(
                        new JdbcTemplate(dataSource), mapper);
        DatabaseTestSuiteStabilityRunRepository rightRepository =
                new DatabaseTestSuiteStabilityRunRepository(
                        new JdbcTemplate(dataSource), mapper);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> left = pool.submit(() -> {
                start.await();
                leftRepository.init();
                return null;
            });
            Future<?> right = pool.submit(() -> {
                start.await();
                rightRepository.init();
                return null;
            });
            start.countDown();

            left.get();
            right.get();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_floors
                    """, Integer.class)).isEqualTo(1);
            assertThat(leftRepository.observationLedgerFloor(
                    "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF))
                    .isEqualTo(rightRepository.observationLedgerFloor(
                            "tenant-a", "test",
                            TestSuiteStabilityProtocolFixtures.SUITE_REF));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void orphanFloorWithActiveRowsButNoHeadFailsClosedFromBothReadDirections() {
        Instant now = Instant.now();
        completeTrend(trendRecord('0', now.minusSeconds(10), now.plusSeconds(3_600)));
        jdbc.update("DELETE FROM rg_test_suite_stability_observation_heads");

        assertThatThrownBy(() -> repository.observationLedgerHead(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rows or lifecycle state exist without a committed head");
        assertThatThrownBy(() -> repository.observationLedgerFloor(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("floor has no committed head");
    }

    @Test
    void orphanRetirementMaterialCannotMasqueradeAsAnEmptyLedger() {
        Instant now = Instant.now();
        for (char identity : new char[] {'1', '2', '3'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
        retirementService.retire(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('4'), retainUntil());
        jdbc.update("DELETE FROM rg_test_suite_stability_observations");
        jdbc.update("DELETE FROM rg_test_suite_stability_observation_floors");
        jdbc.update("DELETE FROM rg_test_suite_stability_observation_heads");

        assertThatThrownBy(() -> repository.observationLedgerHead(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lifecycle state exist without a committed head");
    }

    @Test
    void futureCutoffIsRejectedEvenWhenNoPrefixCouldBeRetired() {
        Instant now = Instant.now();
        completeTrend(trendRecord('0', now.minusSeconds(10), now.plusSeconds(3_600)));

        assertThatThrownBy(() -> repository.planObservationFloorRetirement(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                repository.currentTime().plusSeconds(60), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('5')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cutoff is in the future");
    }

    @Test
    void twoRepositoryInstancesConvergeOnOneSignedRetirementCommit() throws Exception {
        Instant now = Instant.now();
        for (char identity : new char[] {'a', 'b', 'c'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        var evidence = repository.planObservationFloorRetirement(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('2')).orElseThrow();
        TestSuiteStabilityObservationFloorRetirement retirement = signedRetirement(evidence);
        var receipts = externalReceiptSet(retirement);
        DatabaseTestSuiteStabilityRunRepository replica =
                new DatabaseTestSuiteStabilityRunRepository(new JdbcTemplate(dataSource), mapper);
        replica.init();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> left = pool.submit(() -> {
                start.await();
                return repository.commitObservationFloorRetirement(retirement, receipts);
            });
            Future<?> right = pool.submit(() -> {
                start.await();
                return replica.commitObservationFloorRetirement(retirement, receipts);
            });
            start.countDown();

            assertThat(left.get()).isEqualTo(right.get());
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_retirements
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_archives
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_archive_receipts
                    """, Integer.class)).isEqualTo(1);
            assertThat(repository.observationLedgerFloor("tenant-a", "test", suite))
                    .get().extracting(value -> value.retirementGeneration()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retirementGenerationConflictRollsBackNewArchiveAndActiveDeletion() {
        Instant now = Instant.now();
        for (char identity : new char[] {'d', 'e', 'f'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
        var suite = TestSuiteStabilityProtocolFixtures.SUITE_REF;
        var evidence = repository.planObservationFloorRetirement(
                "tenant-a", "test", suite, repository.currentTime(), 1, 1,
                TestSuiteStabilityProtocolFixtures.fingerprint('3')).orElseThrow();
        TestSuiteStabilityObservationFloorRetirement retirement = signedRetirement(evidence);
        var receipts = externalReceiptSet(retirement);
        jdbc.update("""
                INSERT INTO rg_test_suite_stability_observation_retirements (
                    retirement_id, scope_fingerprint, retirement_generation,
                    evidence_fingerprint, attestation_fingerprint,
                    retirement_fingerprint, retired_at, retirement_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, "stability-observation-retirement-" + "f".repeat(64),
                evidence.scopeFingerprint(), evidence.retirementGeneration(),
                TestSuiteStabilityProtocolFixtures.fingerprint('a'),
                TestSuiteStabilityProtocolFixtures.fingerprint('b'),
                TestSuiteStabilityProtocolFixtures.fingerprint('c'),
                java.sql.Timestamp.from(evidence.retiredAt()), "{}");

        assertThatThrownBy(() -> repository.commitObservationFloorRetirement(
                retirement, receipts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation already exists");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archive_receipts",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archives", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observations", Integer.class))
                .isEqualTo(3);
        assertThat(repository.observationLedgerFloor("tenant-a", "test", suite))
                .get().extracting(value -> value.retirementGeneration()).isEqualTo(0L);
    }

    private TestSuiteStabilityExecutionLease acquired(
            TestSuiteStabilityRunRecord record,
            String owner) {
        TestSuiteStabilityLeaseClaim claim = repository.claim(leaseRequest(record, owner));
        assertThat(claim.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
        return claim.lease();
    }

    private static TestSuiteStabilityLeaseRequest leaseRequest(
            TestSuiteStabilityRunRecord record,
            String owner) {
        return new TestSuiteStabilityLeaseRequest(record.stabilityRunId(), record.tenantId(),
                record.environmentId(), record.clientRequestId(), record.requestFingerprint(),
                record.evidence().suiteRef(), record.classification(),
                record.evidence().requestedAttempts(), owner, Duration.ofSeconds(30),
                Duration.ofDays(30));
    }

    private static TestSuiteStabilityExecutionStopRequest stopRequest(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionStop.Reason reason,
            String failureCode) {
        return new TestSuiteStabilityExecutionStopRequest(
                record.stabilityRunId(), record.tenantId(), record.environmentId(),
                record.clientRequestId(), record.requestFingerprint(), record.classification(),
                reason, failureCode, "test-worker", Duration.ofDays(30));
    }

    private TestSuiteStabilityExecutionLease checkpointed(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionLease lease) {
        return checkpointRemaining(record, lease, 0);
    }

    private TestSuiteStabilityExecutionLease checkpointRemaining(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionLease lease,
            int completedAttempts) {
        return checkpointRemaining(record, lease, completedAttempts,
                record.evidence().attempts().size());
    }

    private TestSuiteStabilityExecutionLease checkpointRemaining(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionLease lease,
            int completedAttempts,
            int targetAttempts) {
        TestSuiteStabilityExecutionLease current = lease;
        for (TestSuiteStabilityEvidence.AttemptResult attempt
                : record.evidence().attempts().subList(
                completedAttempts, targetAttempts)) {
            current = repository.checkpoint(current,
                    new TestSuiteStabilityExecutionProgress.AttemptReference(
                            attempt.attempt(), attempt.suiteRunId(),
                            attempt.aggregateEvidenceFingerprint()),
                    Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        }
        return current;
    }

    private static TestSuiteStabilityExecutionProgress.AttemptReference reference(
            TestSuiteStabilityRunRecord record,
            int zeroBasedIndex) {
        TestSuiteStabilityEvidence.AttemptResult attempt =
                record.evidence().attempts().get(zeroBasedIndex);
        return new TestSuiteStabilityExecutionProgress.AttemptReference(
                attempt.attempt(), attempt.suiteRunId(),
                attempt.aggregateEvidenceFingerprint());
    }

    private TestSuiteStabilityRunRecord complete(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionLease lease) {
        return repository.complete(record, observation(record), lease);
    }

    private TestSuiteStabilityObservation observation(TestSuiteStabilityRunRecord record) {
        var sealed = observationAttestations.seal(record);
        if (!sealed.verified()) {
            throw new IllegalStateException(
                    "Repository fixture observation signature failed: " + sealed.failureCode());
        }
        return sealed.observation();
    }

    private TestSuiteStabilityRunRecord record(
            String tenantId,
            String environmentId,
            String clientRequestId,
            Instant expiresAt) {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.stableEvidence();
        return record(evidence, tenantId, environmentId, clientRequestId, expiresAt);
    }

    private TestSuiteStabilityRunRecord record(
            TestSuiteStabilityEvidence evidence,
            String tenantId,
            String environmentId,
            String clientRequestId,
            Instant expiresAt) {
        var seal = attestations.seal(evidence, REQUEST_FINGERPRINT);
        Instant createdAt = Instant.now();
        return new TestSuiteStabilityRunRecord(evidence.stabilityRunId(), clientRequestId,
                REQUEST_FINGERPRINT, tenantId, "org-a", "project-a", environmentId,
                "runner", "INTERNAL", seal.attestation().evidenceFingerprint(), evidence,
                seal.attestation(), createdAt, expiresAt);
    }

    private TestSuiteStabilityRunRecord trendRecord(
            char identity,
            Instant createdAt,
            Instant expiresAt) {
        return TestSuiteStabilityTrendProtocolFixtures.record(
                mapper, attestations, identity, createdAt, expiresAt,
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
    }

    private void completeTrend(TestSuiteStabilityRunRecord record) {
        complete(record, checkpointed(record, acquired(record,
                "owner-" + record.stabilityRunId().substring(record.stabilityRunId().length() - 1))));
    }

    private TestSuiteStabilityObservationFloorRetirement signedRetirement(
            TestSuiteStabilityObservationFloorRetirementEvidence evidence) {
        var seal = retirementAttestations.seal(evidence);
        assertThat(seal.verified()).isTrue();
        String evidenceFingerprint = ProtocolFingerprint.of(mapper, evidence);
        String attestationFingerprint = ProtocolFingerprint.of(mapper, seal.attestation());
        TestSuiteStabilityObservationFloorRetirement unsigned =
                new TestSuiteStabilityObservationFloorRetirement(
                        evidenceFingerprint, evidence, attestationFingerprint,
                        seal.attestation(), TestSuiteStabilityProtocolFixtures.fingerprint('0'));
        return new TestSuiteStabilityObservationFloorRetirement(
                unsigned.evidenceFingerprint(), unsigned.evidence(),
                unsigned.attestationFingerprint(), unsigned.attestation(),
                TestSuiteStabilityObservationFloorRetirementIntegrity.retirementFingerprint(
                        mapper, unsigned));
    }

    private TestSuiteStabilityObservationFloorRetirement retirementWithPolicy(
            TestSuiteStabilityObservationFloorRetirement retirement,
            String retentionPolicyFingerprint) {
        TestSuiteStabilityObservationFloorRetirementEvidence source = retirement.evidence();
        TestSuiteStabilityObservationFloorRetirementEvidence placeholder =
                new TestSuiteStabilityObservationFloorRetirementEvidence(
                        source.schemaVersion(), source.retirementId(), source.scopeFingerprint(),
                        source.suiteRef(), source.retirementGeneration(), source.previousFloor(),
                        source.pinnedHead(), source.archiveSegment(), source.cutoffExclusive(),
                        source.minimumRetainedEntries(), source.maximumRetiredEntries(),
                        retentionPolicyFingerprint, source.reason(), source.retiredAt());
        String retirementId = TestSuiteStabilityObservationFloorRetirementIntegrity.retirementId(
                mapper, placeholder);
        return signedRetirement(new TestSuiteStabilityObservationFloorRetirementEvidence(
                source.schemaVersion(), retirementId, source.scopeFingerprint(), source.suiteRef(),
                source.retirementGeneration(), source.previousFloor(), source.pinnedHead(),
                source.archiveSegment(), source.cutoffExclusive(), source.minimumRetainedEntries(),
                source.maximumRetiredEntries(), retentionPolicyFingerprint, source.reason(),
                source.retiredAt()));
    }

    private TestSuiteStabilityObservationExternalArchiveReceiptSet externalReceiptSet(
            TestSuiteStabilityObservationFloorRetirement retirement) {
        return TestSuiteStabilityObservationExternalArchiveProtocolFixtures.receiptSet(
                mapper, retirement, retainUntil());
    }

    private void prepareRetirementPrefix() {
        Instant now = Instant.now();
        for (char identity : new char[] {'a', 'b', 'c'}) {
            completeTrend(trendRecord(identity, now.minusSeconds(30),
                    now.plusSeconds(3_600)));
        }
    }

    private void assertNoLocalRetirementMutation(int expectedActiveRows) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archive_receipts",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_archives",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observation_retirements",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_observations",
                Integer.class)).isEqualTo(expectedActiveRows);
        assertThat(repository.observationLedgerFloor(
                "tenant-a", "test", TestSuiteStabilityProtocolFixtures.SUITE_REF))
                .get().extracting(value -> value.retirementGeneration()).isEqualTo(0L);
    }

    private static TestSuiteStabilityObservationExternalArchiveAuthority verifyingAs(
            TestSuiteStabilityObservationExternalArchiveAuthority delegate,
            TestSuiteStabilityObservationExternalArchiveAuthority.Verification verification) {
        return new TestSuiteStabilityObservationExternalArchiveAuthority() {
            @Override
            public TestSuiteStabilityObservationExternalArchiveReceiptSet archive(
                    TestSuiteStabilityObservationFloorRetirement retirement,
                    Instant retainUntil) {
                return delegate.archive(retirement, retainUntil);
            }

            @Override
            public Verification verify(
                    TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
                return verification;
            }

            @Override
            public Descriptor descriptor() {
                return delegate.descriptor();
            }

            @Override
            public Snapshot snapshot() {
                return delegate.snapshot();
            }
        };
    }

    private static Instant retainUntil() {
        return Instant.now().plus(Duration.ofDays(365));
    }
}
