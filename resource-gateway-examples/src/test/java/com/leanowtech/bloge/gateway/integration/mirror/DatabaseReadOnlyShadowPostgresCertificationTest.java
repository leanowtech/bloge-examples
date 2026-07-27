package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Certifies shared Shadow queue and guard fencing against a native PostgreSQL process.
 *
 * <p>Two independent data sources model separate Resource Gateway replicas. The test keeps
 * PostgreSQL durability controls enabled and proves unique ordinal admission, single worker
 * publication, lease takeover fencing, and guard-state initialization under concurrent access.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class DatabaseReadOnlyShadowPostgresCertificationTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger guardTokens =
            new AtomicInteger();

    private EmbeddedPostgres postgres;

    @BeforeAll
    void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder()
                .setServerConfig("fsync", "on")
                .setServerConfig(
                        "synchronous_commit", "on")
                .setServerConfig(
                        "full_page_writes", "on")
                .setServerConfig(
                        "lock_timeout", "5s")
                .setServerConfig(
                        "statement_timeout", "15s")
                .start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void runsWithDurablePostgresSettingsAndDatabaseClock() {
        Replica first = replica(
                postgres.getPostgresDatabase());
        JdbcTemplate jdbc = first.jdbc();
        assertThat(jdbc.queryForObject(
                "SHOW server_version_num",
                String.class))
                .startsWith("14");
        assertThat(jdbc.queryForObject(
                "SHOW fsync",
                String.class))
                .isEqualTo("on");
        assertThat(jdbc.queryForObject(
                "SHOW synchronous_commit",
                String.class))
                .isEqualTo("on");
        assertThat(jdbc.queryForObject(
                "SHOW full_page_writes",
                String.class))
                .isEqualTo("on");
        assertThat(jdbc.queryForObject(
                "SHOW lock_timeout",
                String.class))
                .isEqualTo("5s");
        assertThat(jdbc.queryForObject(
                "SHOW statement_timeout",
                String.class))
                .isEqualTo("15s");

        ReadOnlyShadowComparisonIntegrity integrity =
                ReadOnlyShadowJobTestFixtures
                        .integrity(mapper);
        DatabaseReadOnlyShadowJobRepository
                databaseClockRepository =
                new DatabaseReadOnlyShadowJobRepository(
                        first.jdbc(),
                        mapper,
                        integrity,
                        first.transactions());
        databaseClockRepository.init();
        Instant before = Instant.now()
                .minusSeconds(2);
        Instant databaseNow =
                databaseClockRepository
                        .observedAt();
        assertThat(databaseNow)
                .isBetween(
                        before,
                        Instant.now()
                                .plusSeconds(2));
    }

    @Test
    void reservesOneSamplingOrdinalAcrossConnections()
            throws Exception {
        CyclicBarrier initializationRace =
                new CyclicBarrier(2);
        JobFixture fixture = jobFixture(
                () -> awaitBarrier(
                        initializationRace));
        certifiesUniqueOrdinalAcrossReplicas(
                fixture.firstJobs(),
                fixture.secondJobs(),
                fixture.now().get());
    }

    @Test
    void publishesOneTerminalComparisonAcrossWorkers()
            throws Exception {
        JobFixture fixture = jobFixture();
        certifiesOneWorkerPublication(
                fixture.firstJobs(),
                fixture.secondJobs(),
                fixture.integrity(),
                fixture.now().get());
    }

    @Test
    void fencesAStaleOwnerAfterLeaseTakeover() {
        JobFixture fixture = jobFixture();
        certifiesExpiredLeaseTakeover(
                fixture.firstJobs(),
                fixture.secondJobs(),
                fixture.integrity(),
                fixture.now());
    }

    @Test
    void sharesGuardInitializationAndConcurrencyBudget()
            throws Exception {
        Replica first = replica(
                postgres.getPostgresDatabase());
        Replica second = replica(
                postgres.getPostgresDatabase());
        AtomicReference<Instant> now =
                new AtomicReference<>(
                        Instant.now().truncatedTo(
                                ChronoUnit.MILLIS));
        CyclicBarrier initializationRace =
                new CyclicBarrier(2);
        certifiesGuardStateAndBudgetAcrossReplicas(
                first,
                second,
                now,
                () -> awaitBarrier(
                        initializationRace));
    }

    @Test
    void serializesOutcomeAdmissionAndFencesExpiredOwnersAcrossReplicas()
            throws Exception {
        Replica first = replica(postgres.getPostgresDatabase());
        Replica second = replica(postgres.getPostgresDatabase());
        AtomicReference<Instant> now =
                new AtomicReference<>(
                        DomainFidelityTestFixtures.NOW);
        Clock clock = Clock.fixed(
                DomainFidelityTestFixtures.NOW,
                ZoneOffset.UTC);
        AuthoritativeOutcomeObservationIntegrity integrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner
                                .usingClock(clock),
                        new AuthoritativeOutcomeAuthorityVerifier() {
                            @Override
                            public boolean available() {
                                return true;
                            }

                            @Override
                            public void verify(
                                    AuthoritativeOutcomeObservation
                                            observation) {
                            }
                        },
                        clock);
        CyclicBarrier initializationRace =
                new CyclicBarrier(2);
        DatabaseAuthoritativeOutcomeInboxRepository firstInbox =
                outcomeInbox(
                        first,
                        integrity,
                        now,
                        () -> awaitBarrier(
                                initializationRace));
        DatabaseAuthoritativeOutcomeInboxRepository secondInbox =
                outcomeInbox(
                        second,
                        integrity,
                        now,
                        () -> awaitBarrier(
                                initializationRace));
        firstInbox.init();
        secondInbox.init();
        AuthoritativeOutcomeObservation pending =
                integrity.sign(
                        AuthoritativeOutcomeTestFixtures
                                .pending());

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<AuthoritativeOutcomeInboxRepository.Admission>
                    firstAdmission = executor.submit(() ->
                    firstInbox.append(pending, ""));
            Future<AuthoritativeOutcomeInboxRepository.Admission>
                    secondAdmission = executor.submit(() ->
                    secondInbox.append(pending, ""));
            assertThat(List.of(
                    awaitFuture(firstAdmission)
                            .idempotentReplay(),
                    awaitFuture(secondAdmission)
                            .idempotentReplay()))
                    .containsExactlyInAnyOrder(false, true);
        }

        AuthoritativeOutcomeInboxRepository.Claim stale =
                firstInbox.claimNext(
                        pending.scope().region(),
                        pending.scope().environmentId(),
                        "postgres-outcome-stale",
                        AuthoritativeOutcomeInboxPolicy.DEFAULT);
        assertThat(stale.outcome()).isEqualTo(
                AuthoritativeOutcomeInboxRepository
                        .Claim.Outcome.ACQUIRED);
        now.set(now.get().plusSeconds(31));
        AuthoritativeOutcomeInboxRepository.Claim deferredReplacement =
                secondInbox.claimNext(
                        pending.scope().region(),
                        pending.scope().environmentId(),
                        "postgres-outcome-replacement",
                        AuthoritativeOutcomeInboxPolicy.DEFAULT);
        assertThat(deferredReplacement.outcome()).isEqualTo(
                AuthoritativeOutcomeInboxRepository
                        .Claim.Outcome.NO_WORK);
        now.set(now.get().plusSeconds(5));
        AuthoritativeOutcomeInboxRepository.Claim replacement =
                secondInbox.claimNext(
                        pending.scope().region(),
                        pending.scope().environmentId(),
                        "postgres-outcome-replacement",
                        AuthoritativeOutcomeInboxPolicy.DEFAULT);

        assertThat(replacement.outcome()).isEqualTo(
                AuthoritativeOutcomeInboxRepository
                        .Claim.Outcome.ACQUIRED);
        assertThat(replacement.lease().epoch())
                .isGreaterThan(stale.lease().epoch());
        assertThatThrownBy(() ->
                firstInbox.noChange(
                        stale.lease(),
                        AuthoritativeOutcomeInboxPolicy.DEFAULT))
                .isInstanceOf(
                        AuthoritativeOutcomeInboxRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeInboxRepository
                                .Reason.LEASE_LOST);
        assertThat(secondInbox.noChange(
                replacement.lease(),
                AuthoritativeOutcomeInboxPolicy.DEFAULT)
                .status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.QUEUED);
        assertThat(firstInbox.lifecycle(
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
                                .Transition.LEASE_EXPIRED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.CLAIMED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.NO_CHANGE);
    }

    @Test
    void certifiesSelectedPopulationAdmissionAndAssessmentCutsAcrossReplicas()
            throws Exception {
        Replica first = replica(
                postgres.getPostgresDatabase());
        Replica second = replica(
                postgres.getPostgresDatabase());
        Clock clock = Clock.fixed(
                DomainFidelityTestFixtures.NOW,
                ZoneOffset.UTC);
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(clock);
        AuthoritativeOutcomeSelectedPopulationIntegrity
                populationIntegrity =
                new
                        AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        clock);
        AuthoritativeOutcomeObservationIntegrity
                observationIntegrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .outcomeAuthority(),
                        clock);
        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                dispositionIntegrity =
                new
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .dispositionAuthority(),
                        clock);
        AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                projector =
                new
                        AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        signer,
                        clock);
        DatabaseAuthoritativeOutcomeInboxRepository
                firstInbox =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        first.jdbc(),
                        mapper,
                        observationIntegrity,
                        first.transactions());
        DatabaseAuthoritativeOutcomeInboxRepository
                secondInbox =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        second.jdbc(),
                        mapper,
                        observationIntegrity,
                        second.transactions());
        firstInbox.init();
        secondInbox.init();
        DatabaseAuthoritativeOutcomeSelectedPopulationRepository
                firstPopulation =
                selectedPopulationRepository(
                        first,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        projector);
        DatabaseAuthoritativeOutcomeSelectedPopulationRepository
                secondPopulation =
                selectedPopulationRepository(
                        second,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        projector);
        firstPopulation.init();
        secondPopulation.init();
        AuthoritativeOutcomeSelectedPopulationTestFixtures
                .Population population =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(
                                populationIntegrity);

        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Future<AuthoritativeOutcomeSelectedPopulationRepository
                    .PopulationAdmission> firstAdmission =
                    executor.submit(() ->
                            firstPopulation.register(
                                    population.manifest(),
                                    population.chunks(),
                                    ""));
            Future<AuthoritativeOutcomeSelectedPopulationRepository
                    .PopulationAdmission> secondAdmission =
                    executor.submit(() ->
                            secondPopulation.register(
                                    population.manifest(),
                                    population.chunks(),
                                    ""));
            assertThat(List.of(
                    awaitFuture(firstAdmission)
                            .idempotentReplay(),
                    awaitFuture(secondAdmission)
                            .idempotentReplay()))
                    .containsExactlyInAnyOrder(
                            false, true);
        }

        AuthoritativeOutcomeSelectedPopulationDisposition
                disposition =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedDisposition(
                                dispositionIntegrity,
                                population.manifest(),
                                population.members().get(2),
                                "postgres-deletion-member-3");
        firstPopulation.appendDisposition(
                disposition, "");
        AuthoritativeOutcomeSelectedPopulationRepository
                .AssessmentCut stale =
                firstPopulation.prepareAssessment(
                        population.manifest().scope(),
                        population.manifest().populationId(),
                        1);
        AuthoritativeOutcomeObservation observation =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedObservation(
                                observationIntegrity,
                                population.manifest(),
                                population.members().getFirst(),
                                "postgres-observation-member-1",
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.MATCH);
        secondInbox.append(observation, "");
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                staleAssessment =
                projector.assess(
                        "postgres-assessment-stale",
                        1,
                        population.manifest(),
                        population.chunks(),
                        stale.observations(),
                        stale.dispositions());

        assertThatThrownBy(() ->
                firstPopulation.appendAssessment(
                        stale,
                        staleAssessment,
                        ""))
                .isInstanceOfSatisfying(
                        AuthoritativeOutcomeSelectedPopulationRepository
                                .Violation.class,
                        failure -> assertThat(
                                failure.reason())
                                .isEqualTo(
                                        AuthoritativeOutcomeSelectedPopulationRepository
                                                .Reason.CUT_STALE));

        AuthoritativeOutcomeSelectedPopulationService service =
                new AuthoritativeOutcomeSelectedPopulationService(
                        firstPopulation,
                        populationIntegrity,
                        dispositionIntegrity,
                        projector);
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                current =
                service.assess(
                        population.manifest().scope(),
                        population.manifest().populationId(),
                        1,
                        "postgres-assessment-current",
                        1,
                        "").assessment();
        assertThat(current.totals())
                .isEqualTo(
                        new
                                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment.Counts(
                                3,
                                1,
                                0,
                                0,
                                0,
                                0,
                                1,
                                1));
        assertThat(secondPopulation.findAssessment(
                population.manifest().scope(),
                "postgres-assessment-current",
                1)).contains(current);
        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                page =
                secondPopulation.assessmentSources(
                        population.manifest().scope(),
                        "postgres-assessment-current",
                        1,
                        0,
                        100);
        page.verify(mapper);
        assertThat(page.complete()).isTrue();
        assertThat(page.entries())
                .extracting(
                        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                                .Entry::sourceKind)
                .containsExactly(
                        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                                .SourceKind.OBSERVATION,
                        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                                .SourceKind.LEGAL_DISPOSITION);
    }

    @Test
    void certifiesContinuousAssessmentFencingAcrossReplicas() {
        Replica first = replica(
                postgres.getPostgresDatabase());
        Replica second = replica(
                postgres.getPostgresDatabase());
        Clock clock = Clock.fixed(
                DomainFidelityTestFixtures.NOW,
                ZoneOffset.UTC);
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(clock);
        AuthoritativeOutcomeSelectedPopulationIntegrity
                populationIntegrity =
                new AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        clock);
        AuthoritativeOutcomeObservationIntegrity
                observationIntegrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .outcomeAuthority(),
                        clock);
        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                dispositionIntegrity =
                new AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .dispositionAuthority(),
                        clock);
        AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                projector =
                new AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        signer,
                        clock);
        DatabaseAuthoritativeOutcomeInboxRepository inbox =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        first.jdbc(),
                        mapper,
                        observationIntegrity,
                        first.transactions());
        inbox.init();
        DatabaseAuthoritativeOutcomeSelectedPopulationRepository
                populations =
                selectedPopulationRepository(
                        first,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        projector);
        populations.init();
        AuthoritativeOutcomeSelectedPopulationTestFixtures.Population
                population =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(
                                populationIntegrity,
                                DomainFidelityTestFixtures.scope(
                                        "support-continuous"));
        populations.register(
                population.manifest(),
                population.chunks(),
                "");
        DatabaseAuthoritativeOutcomeContinuousAssessmentRepository
                firstProjection =
                new DatabaseAuthoritativeOutcomeContinuousAssessmentRepository(
                        first.jdbc(),
                        mapper,
                        first.transactions());
        DatabaseAuthoritativeOutcomeContinuousAssessmentRepository
                secondProjection =
                new DatabaseAuthoritativeOutcomeContinuousAssessmentRepository(
                        second.jdbc(),
                        mapper,
                        second.transactions());
        firstProjection.init();
        secondProjection.init();
        AuthoritativeOutcomeContinuousAssessmentRequest command =
                new AuthoritativeOutcomeContinuousAssessmentRequest(
                        "",
                        "postgres-continuous-assessment",
                        population.manifest().artifactRef());
        assertThat(firstProjection.register(
                population.manifest().scope(),
                command).idempotentReplay()).isFalse();
        assertThat(secondProjection.register(
                population.manifest().scope(),
                command).idempotentReplay()).isTrue();
        AuthoritativeOutcomeContinuousAssessmentPolicy policy =
                new AuthoritativeOutcomeContinuousAssessmentPolicy(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(8),
                        3);
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim claim =
                firstProjection.claimNext(
                        "sg",
                        "staging",
                        "postgres-projection-worker",
                        policy);
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment =
                new AuthoritativeOutcomeSelectedPopulationService(
                        populations,
                        populationIntegrity,
                        dispositionIntegrity,
                        projector)
                        .assess(
                                population.manifest().scope(),
                                population.manifest()
                                        .populationId(),
                                population.manifest().revision(),
                                command.assessmentId(),
                                1,
                                "")
                        .assessment();

        AuthoritativeOutcomeContinuousAssessmentProjection published =
                secondProjection.publish(
                        claim.lease(),
                        assessment.artifactRef(),
                        assessment.observationSetFingerprint(),
                        assessment.dispositionSetFingerprint(),
                        policy);

        assertThat(published.lastAssessmentRef())
                .isEqualTo(assessment.artifactRef());
        assertThat(firstProjection.find(
                population.manifest().scope(),
                command.projectionId())
                .orElseThrow().freshness())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                .Freshness.CURRENT);
        assertThat(secondProjection.claimNext(
                "sg",
                "staging",
                "postgres-projection-worker-2",
                policy).outcome())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentRepository
                                .Claim.Outcome.NO_WORK);

        AuthoritativeOutcomeContinuousAssessmentRequest
                remediationStream =
                new AuthoritativeOutcomeContinuousAssessmentRequest(
                        "",
                        "postgres-continuous-remediation",
                        population.manifest().artifactRef());
        firstProjection.register(
                population.manifest().scope(),
                remediationStream);
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                remediationClaim =
                secondProjection.claimNext(
                        "sg",
                        "staging",
                        "postgres-remediation-worker",
                        policy);
        AuthoritativeOutcomeContinuousAssessmentProjection
                quarantined = firstProjection.fail(
                remediationClaim.lease(),
                "DEPENDENCY_FAILED",
                false,
                policy);
        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                quarantineHead = secondProjection.lifecycle(
                population.manifest().scope(),
                remediationStream.projectionId(),
                0,
                100)
                .events()
                .getLast();
        AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                remediation =
                new AuthoritativeOutcomeContinuousAssessmentRemediationRequest(
                        "",
                        "postgres-remediation-1",
                        quarantined.recordFingerprint(),
                        quarantineHead.eventOrdinal(),
                        quarantineHead.eventFingerprint(),
                        "DEPENDENCY_REPAIRED");

        AuthoritativeOutcomeContinuousAssessmentRepository.Remediation
                remediated = secondProjection.remediate(
                population.manifest().scope(),
                remediationStream.projectionId(),
                remediation,
                "SERVICE:postgres-operator");

        remediated.receipt().verify(mapper);
        assertThat(remediated.idempotentReplay())
                .isFalse();
        assertThat(firstProjection.find(
                population.manifest().scope(),
                remediationStream.projectionId())
                .orElseThrow()
                .projection()
                .status())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                .Status.QUEUED);
        assertThat(firstProjection.remediate(
                population.manifest().scope(),
                remediationStream.projectionId(),
                remediation,
                "SERVICE:postgres-operator")
                .idempotentReplay())
                .isTrue();
    }

    @Test
    void certifiesResumablePopulationUploadAcrossReplicas()
            throws Exception {
        Replica first = replica(
                postgres.getPostgresDatabase());
        Replica second = replica(
                postgres.getPostgresDatabase());
        AtomicReference<Instant> now =
                new AtomicReference<>(
                        DomainFidelityTestFixtures.NOW);
        AuthoritativeOutcomeSelectedPopulationUploadPolicy policy =
                new
                        AuthoritativeOutcomeSelectedPopulationUploadPolicy(
                        1,
                        16 * 1024 * 1024,
                        32 * 1024 * 1024,
                        Duration.ofHours(1),
                        Duration.ofMinutes(2),
                        Duration.ofDays(1));
        CyclicBarrier initializationRace =
                new CyclicBarrier(2);
        Runnable beforeLockRowInsert = () ->
                awaitBarrier(initializationRace);
        DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
                firstUploads =
                new
                        DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository(
                        first.jdbc(),
                        mapper,
                        policy,
                        first.transactions(),
                        now::get,
                        beforeLockRowInsert);
        DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
                secondUploads =
                new
                        DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository(
                        second.jdbc(),
                        mapper,
                        policy,
                        second.transactions(),
                        now::get,
                        beforeLockRowInsert);
        firstUploads.init();
        secondUploads.init();
        Clock clock = Clock.fixed(
                DomainFidelityTestFixtures.NOW,
                ZoneOffset.UTC);
        AuthoritativeOutcomeSelectedPopulationIntegrity integrity =
                new
                        AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner
                                .usingClock(clock),
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        clock);
        AuthoritativeOutcomeSelectedPopulationTestFixtures.Population
                fixture =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(integrity);
        AuthoritativeOutcomeSelectedPopulationUploadRequest request =
                new
                        AuthoritativeOutcomeSelectedPopulationUploadRequest(
                        "",
                        "postgres-population-upload",
                        "",
                        fixture.manifest());

        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Future<AuthoritativeOutcomeSelectedPopulationUploadRepository
                    .Admission> firstAdmission =
                    executor.submit(() ->
                            firstUploads.begin(request));
            Future<AuthoritativeOutcomeSelectedPopulationUploadRepository
                    .Admission> secondAdmission =
                    executor.submit(() ->
                            secondUploads.begin(request));
            assertThat(List.of(
                    awaitFuture(firstAdmission)
                            .idempotentReplay(),
                    awaitFuture(secondAdmission)
                            .idempotentReplay()))
                    .containsExactlyInAnyOrder(
                            false, true);
        }

        assertThatThrownBy(() ->
                secondUploads.begin(
                        uploadRequestForPopulation(
                                fixture,
                                "postgres-quota-probe",
                                "postgres-quota-population")))
                .isInstanceOfSatisfying(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Violation.class,
                        failure -> assertThat(
                                failure.reason())
                                .isEqualTo(
                                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                                .Reason.ACTIVE_UPLOAD_QUOTA_EXCEEDED));

        secondUploads.stageChunk(
                fixture.manifest().scope(),
                request.uploadId(),
                1,
                fixture.chunks().get(1),
                2_048);
        firstUploads.stageChunk(
                fixture.manifest().scope(),
                request.uploadId(),
                0,
                fixture.chunks().getFirst(),
                1_024);
        AuthoritativeOutcomeSelectedPopulationUploadRepository
                .FinalizationClaim claim =
                secondUploads.claimFinalize(
                        fixture.manifest().scope(),
                        request.uploadId(),
                        "postgres-finalizer");
        AuthoritativeOutcomeSelectedPopulationAdmission admission =
                new
                        AuthoritativeOutcomeSelectedPopulationAdmission(
                        "",
                        new
                                AuthoritativeOutcomeSelectedPopulationBundle(
                                "",
                                fixture.manifest(),
                                fixture.chunks(),
                                ""),
                        false);
        firstUploads.completeFinalize(
                claim, admission);
        AuthoritativeOutcomeSelectedPopulationUploadRepository
                .FinalizationClaim replay =
                secondUploads.claimFinalize(
                        fixture.manifest().scope(),
                        request.uploadId(),
                        "postgres-replay");

        assertThat(replay.requiresExecution())
                .isFalse();
        assertThat(replay.upload().admission())
                .contains(admission);
    }

    private static
    AuthoritativeOutcomeSelectedPopulationUploadRequest
    uploadRequestForPopulation(
            AuthoritativeOutcomeSelectedPopulationTestFixtures.Population
                    fixture,
            String uploadId,
            String populationId) {
        AuthoritativeOutcomeSelectedPopulationManifest source =
                fixture.manifest();
        return new
                AuthoritativeOutcomeSelectedPopulationUploadRequest(
                "",
                uploadId,
                "",
                new
                        AuthoritativeOutcomeSelectedPopulationManifest(
                        "",
                        populationId,
                        source.revision(),
                        "",
                        source.scope(),
                        source.inventoryRef(),
                        source.cohortRef(),
                        source.samplingFrameRef(),
                        source.selectionPolicyRef(),
                        source.selectionAuthoritySetRef(),
                        source.selectionAttestationRef(),
                        source.selectedAt(),
                        source.strata(),
                        source.chunks(),
                        source.totalEligiblePopulation(),
                        source.totalSelectedPopulation(),
                        source.attestedAt(),
                        null));
    }

    private JobFixture jobFixture() {
        return jobFixture(
                () -> {
                });
    }

    private JobFixture jobFixture(
            Runnable beforeLockRowInsert) {
        Replica first = replica(
                postgres.getPostgresDatabase());
        Replica second = replica(
                postgres.getPostgresDatabase());
        ReadOnlyShadowComparisonIntegrity integrity =
                ReadOnlyShadowJobTestFixtures
                        .integrity(mapper);
        DatabaseReadOnlyShadowJobRepository
                databaseClockRepository =
                new DatabaseReadOnlyShadowJobRepository(
                        first.jdbc(),
                        mapper,
                        integrity,
                        first.transactions());
        databaseClockRepository.init();
        AtomicReference<Instant> now =
                new AtomicReference<>(
                        databaseClockRepository.observedAt()
                                .truncatedTo(
                                        ChronoUnit.MILLIS));
        DatabaseReadOnlyShadowJobRepository firstJobs =
                jobs(
                        first,
                        integrity,
                        now,
                        beforeLockRowInsert);
        DatabaseReadOnlyShadowJobRepository secondJobs =
                jobs(
                        second,
                        integrity,
                        now,
                        beforeLockRowInsert);
        firstJobs.init();
        secondJobs.init();
        return new JobFixture(
                firstJobs,
                secondJobs,
                integrity,
                now);
    }

    private void certifiesUniqueOrdinalAcrossReplicas(
            DatabaseReadOnlyShadowJobRepository first,
            DatabaseReadOnlyShadowJobRepository second,
            Instant now) throws Exception {
        ReadOnlyShadowJobRequest left =
                request(
                        "postgres-ordinal-left",
                        71,
                        now.plus(
                                Duration.ofMinutes(30)),
                        "postgres-ordinal");
        ReadOnlyShadowJobRequest right =
                request(
                        "postgres-ordinal-right",
                        71,
                        left.deadlineAt(),
                        "postgres-ordinal");
        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Future<Object> leftResult =
                    executor.submit(() ->
                            repositoryOutcome(() ->
                                    first.submit(
                                            left,
                                            ReadOnlyShadowJobTestFixtures
                                                    .POLICY)));
            Future<Object> rightResult =
                    executor.submit(() ->
                            repositoryOutcome(() ->
                                    second.submit(
                                            right,
                                            ReadOnlyShadowJobTestFixtures
                                                    .POLICY)));
            List<Object> outcomes =
                    List.of(
                            awaitFuture(leftResult),
                            awaitFuture(rightResult));
            assertThat(outcomes)
                    .filteredOn(
                            ReadOnlyShadowJobRepository
                                    .Submission.class
                                    ::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(value -> value
                            == ReadOnlyShadowJobRepository
                            .Reason.SAMPLE_ORDINAL_CONFLICT)
                    .hasSize(1);
        }
    }

    private void certifiesOneWorkerPublication(
            DatabaseReadOnlyShadowJobRepository first,
            DatabaseReadOnlyShadowJobRepository second,
            ReadOnlyShadowComparisonIntegrity integrity,
            Instant now) throws Exception {
        ReadOnlyShadowJobRequest request =
                request(
                        "postgres-worker-publication",
                        72,
                        now.plus(
                                Duration.ofMinutes(30)),
                        "postgres-worker");
        ReadOnlyShadowJob job =
                first.submit(
                        request,
                        ReadOnlyShadowJobTestFixtures
                                .POLICY)
                        .job();
        ReadOnlyShadowDataPlane dataPlane =
                mock(ReadOnlyShadowDataPlane.class);
        when(dataPlane.ready())
                .thenReturn(true);
        when(dataPlane.execute(any()))
                .thenReturn(
                        ReadOnlyShadowJobTestFixtures
                                .executionResult(request));
        ReadOnlyShadowJobWorker firstWorker =
                new ReadOnlyShadowJobWorker(
                        first,
                        dataPlane,
                        integrity,
                        ReadOnlyShadowJobTestFixtures
                                .POLICY);
        ReadOnlyShadowJobWorker secondWorker =
                new ReadOnlyShadowJobWorker(
                        second,
                        dataPlane,
                        integrity,
                        ReadOnlyShadowJobTestFixtures
                                .POLICY);

        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            CyclicBarrier workerStart =
                    new CyclicBarrier(2);
            Future<ReadOnlyShadowJobRepository.Claim>
                    firstResult =
                    executor.submit(() -> {
                        awaitBarrier(workerStart);
                        return
                            firstWorker.runOne(
                                    request.scope()
                                            .region(),
                                    request.scope()
                                            .environmentId(),
                                    "postgres-worker-a");
                    });
            Future<ReadOnlyShadowJobRepository.Claim>
                    secondResult =
                    executor.submit(() -> {
                        awaitBarrier(workerStart);
                        return
                            secondWorker.runOne(
                                    request.scope()
                                            .region(),
                                    request.scope()
                                            .environmentId(),
                                    "postgres-worker-b");
                    });
            assertThat(List.of(
                    awaitFuture(firstResult)
                            .outcome(),
                    awaitFuture(secondResult)
                            .outcome()))
                    .containsExactlyInAnyOrder(
                            ReadOnlyShadowJobRepository
                                    .ClaimOutcome.ACQUIRED,
                            ReadOnlyShadowJobRepository
                                    .ClaimOutcome.NO_WORK);
        }

        verify(dataPlane, times(1))
                .execute(any());
        assertThat(second.find(
                request.scope(),
                job.jobId()).orElseThrow()
                .status())
                .isEqualTo(
                        ReadOnlyShadowJob.Status
                                .SUCCEEDED);
        assertThat(second.findComparison(
                request.scope(),
                job.jobId()))
                .isPresent();
        assertThat(second.lifecycle(
                request.scope(),
                job.jobId(),
                0,
                10))
                .extracting(
                        ReadOnlyShadowJobLifecycleEvent
                                ::transition)
                .containsExactly(
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.ADMITTED,
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.CLAIMED,
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.SUCCEEDED);
    }

    private void certifiesExpiredLeaseTakeover(
            DatabaseReadOnlyShadowJobRepository first,
            DatabaseReadOnlyShadowJobRepository second,
            ReadOnlyShadowComparisonIntegrity integrity,
            AtomicReference<Instant> now) {
        Instant initialNow = now.get();
        ReadOnlyShadowJobRequest request =
                request(
                        "postgres-lease-takeover",
                        73,
                        initialNow.plus(
                                Duration.ofMinutes(30)),
                        "postgres-takeover");
        ReadOnlyShadowJob job =
                first.submit(
                        request,
                        ReadOnlyShadowJobTestFixtures
                                .POLICY)
                        .job();
        ReadOnlyShadowJobRepository.Claim stale =
                first.claimNext(
                        request.scope().region(),
                        request.scope()
                                .environmentId(),
                        "postgres-stale-worker",
                        ReadOnlyShadowJobTestFixtures
                                .POLICY);

        now.set(initialNow.plusSeconds(61));
        ReadOnlyShadowJobRepository.Claim replacement =
                second.claimNext(
                        request.scope().region(),
                        request.scope()
                                .environmentId(),
                        "postgres-replacement-worker",
                        ReadOnlyShadowJobTestFixtures
                                .POLICY);
        ReadOnlyShadowComparison comparison =
                integrity.sign(
                        ReadOnlyShadowJobTestFixtures
                                .unsignedComparison(
                                        job.jobId(),
                                        request));

        assertThat(replacement.job()
                .attemptCount())
                .isEqualTo(2);
        assertThat(replacement.lease()
                .epoch())
                .isGreaterThan(
                        stale.lease().epoch());
        assertThatThrownBy(() ->
                first.complete(
                        stale.lease(),
                        comparison))
                .isInstanceOf(
                        ReadOnlyShadowJobRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowJobRepository
                                .Reason.LEASE_LOST);

        assertThat(second.complete(
                replacement.lease(),
                comparison).status())
                .isEqualTo(
                        ReadOnlyShadowJob.Status
                                .SUCCEEDED);
        assertThat(first.lifecycle(
                request.scope(),
                job.jobId(),
                0,
                10).stream()
                .filter(event -> event.transition()
                        == ReadOnlyShadowJobLifecycleEvent
                        .Transition.SUCCEEDED)
                .count())
                .isEqualTo(1L);
        assertThat(first.findComparison(
                request.scope(),
                job.jobId()))
                .contains(comparison);
    }

    private void certifiesGuardStateAndBudgetAcrossReplicas(
            Replica first,
            Replica second,
            AtomicReference<Instant> now,
            Runnable beforeStateInsert)
            throws Exception {
        DatabaseReadOnlyShadowExecutionGuard firstGuard =
                guard(
                        first,
                        now,
                        beforeStateInsert);
        DatabaseReadOnlyShadowExecutionGuard secondGuard =
                guard(
                        second,
                        now,
                        beforeStateInsert);
        firstGuard.init();
        secondGuard.init();
        ReadOnlyShadowExecutionGuard.Limits limits =
                new ReadOnlyShadowExecutionGuard.Limits(
                        1,
                        10,
                        Duration.ofMinutes(1),
                        3,
                        Duration.ofSeconds(30));
        ReadOnlyShadowJobRequest left =
                request(
                        "postgres-guard-left",
                        74,
                        now.get().plus(
                                Duration.ofMinutes(20)),
                        "postgres-guard");
        ReadOnlyShadowJobRequest right =
                request(
                        "postgres-guard-right",
                        75,
                        left.deadlineAt(),
                        "postgres-guard");

        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Future<Object> leftResult =
                    executor.submit(() ->
                            guardOutcome(() ->
                                    firstGuard.acquire(
                                            permit(
                                                    "postgres-guard-left",
                                                    left,
                                                    now.get()),
                                            admission(
                                                    left,
                                                    limits,
                                                    now.get()))));
            Future<Object> rightResult =
                    executor.submit(() ->
                            guardOutcome(() ->
                                    secondGuard.acquire(
                                            permit(
                                                    "postgres-guard-right",
                                                    right,
                                                    now.get()),
                                            admission(
                                                    right,
                                                    limits,
                                                    now.get()))));
            List<Object> outcomes =
                    List.of(
                            awaitFuture(leftResult),
                            awaitFuture(rightResult));
            assertThat(outcomes)
                    .filteredOn(
                            ReadOnlyShadowExecutionGuard
                                    .Lease.class
                                    ::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(value -> value
                            == ReadOnlyShadowDataPlane
                            .FailureReason.BUDGET_EXHAUSTED)
                    .hasSize(1);
            outcomes.stream()
                    .filter(
                            ReadOnlyShadowExecutionGuard
                                    .Lease.class
                                    ::isInstance)
                    .map(
                            ReadOnlyShadowExecutionGuard
                                    .Lease.class
                                    ::cast)
                    .forEach(lease -> {
                        lease.succeeded();
                        lease.close();
                    });
        }

        assertThat(first.jdbc().queryForObject(
                """
                SELECT COUNT(*)
                FROM mirror_shadow_execution_guard_states
                WHERE guard_policy_id = 'postgres-pressure-policy'
                """,
                Long.class))
                .isEqualTo(1L);
    }

    private DatabaseReadOnlyShadowJobRepository jobs(
            Replica replica,
            ReadOnlyShadowComparisonIntegrity integrity,
            AtomicReference<Instant> now,
            Runnable beforeLockRowInsert) {
        return new DatabaseReadOnlyShadowJobRepository(
                replica.jdbc(),
                mapper,
                integrity,
                replica.transactions(),
                now::get,
                beforeLockRowInsert);
    }

    private DatabaseReadOnlyShadowExecutionGuard guard(
            Replica replica,
            AtomicReference<Instant> now,
            Runnable beforeStateInsert) {
        return new DatabaseReadOnlyShadowExecutionGuard(
                replica.jdbc(),
                mapper,
                replica.transactions(),
                now::get,
                () -> "postgres-guard-token-"
                        + String.format(
                        "%08d",
                        guardTokens.incrementAndGet()),
                beforeStateInsert);
    }

    private DatabaseAuthoritativeOutcomeInboxRepository
    outcomeInbox(
            Replica replica,
            AuthoritativeOutcomeObservationIntegrity integrity,
            AtomicReference<Instant> now,
            Runnable beforeLockRowInsert) {
        return new DatabaseAuthoritativeOutcomeInboxRepository(
                replica.jdbc(),
                mapper,
                integrity,
                replica.transactions(),
                now::get,
                beforeLockRowInsert);
    }

    private
    DatabaseAuthoritativeOutcomeSelectedPopulationRepository
    selectedPopulationRepository(
            Replica replica,
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    populationIntegrity,
            AuthoritativeOutcomeObservationIntegrity
                    observationIntegrity,
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositionIntegrity,
            AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                    projector) {
        return new
                DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
                replica.jdbc(),
                mapper,
                populationIntegrity,
                observationIntegrity,
                dispositionIntegrity,
                projector,
                replica.transactions());
    }

    private static Replica replica(
            DataSource dataSource) {
        return new Replica(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(
                        dataSource));
    }

    private static ReadOnlyShadowJobRequest request(
            String requestId,
            long ordinal,
            Instant deadlineAt,
            String environmentId) {
        ReadOnlyShadowJobRequest source =
                ReadOnlyShadowJobTestFixtures
                        .request(
                                requestId,
                                ordinal);
        CapabilitySnapshot.Scope scope =
                new CapabilitySnapshot.Scope(
                        source.scope().tenantId(),
                        source.scope()
                                .organizationId(),
                        source.scope().projectId(),
                        environmentId,
                        source.scope().region());
        return new ReadOnlyShadowJobRequest(
                source.schemaVersion(),
                source.requestId(),
                scope,
                source.inventoryRef(),
                source.unitId(),
                source.scenarioCaseRef(),
                source.targetCapabilityRef(),
                source.candidatePlanRef(),
                source.baselineBindingRef(),
                source.comparisonPolicyRef(),
                source.accessGrant(),
                deadlineAt);
    }

    private static ReadOnlyShadowDataPlane.Permit permit(
            String executionId,
            ReadOnlyShadowJobRequest request,
            Instant now) {
        Instant leaseExpiresAt =
                now.plusSeconds(20);
        return new ReadOnlyShadowDataPlane.Permit(
                executionId,
                request,
                1,
                request.deadlineAt(),
                new ReadOnlyShadowDataPlane
                        .ExecutionControl() {
                    @Override
                    public Instant leaseExpiresAt() {
                        return leaseExpiresAt;
                    }

                    @Override
                    public Instant heartbeat() {
                        return leaseExpiresAt;
                    }
                });
    }

    private static ReadOnlyShadowAccessAuthority.Admission
    admission(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowExecutionGuard.Limits limits,
            Instant now) {
        Instant validUntil =
                now.plusSeconds(600);
        MirrorArtifactRef policyRef =
                new MirrorArtifactRef(
                        "SHADOW_EXECUTION_GUARD_POLICY",
                        "postgres-pressure-policy",
                        1,
                        ReadOnlyShadowJobTestFixtures
                                .fingerprint('a'));
        ReadOnlyShadowSamplingGrantAuthority.Grant grant =
                new ReadOnlyShadowSamplingGrantAuthority
                        .Grant(
                        request.scope(),
                        request.scope(),
                        request.accessGrant()
                                .samplingGrantRef(),
                        request.accessGrant()
                                .maximumSamples(),
                        now.minusSeconds(30),
                        validUntil,
                        policyRef,
                        limits,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_SAMPLING_GRANT_ATTESTATION",
                                request.accessGrant()
                                        .samplingGrantRef()
                                        .id(),
                                '1'),
                        new MirrorArtifactRef(
                                "SHADOW_EXECUTION_GUARD_POLICY_ATTESTATION",
                                policyRef.id(),
                                policyRef.revision(),
                                ReadOnlyShadowJobTestFixtures
                                        .fingerprint('2')),
                        now);
        ReadOnlyShadowKillSwitchAuthority.State
                killSwitch =
                new ReadOnlyShadowKillSwitchAuthority
                        .State(
                        request.scope(),
                        request.accessGrant()
                                .killSwitchRef(),
                        true,
                        now.minusSeconds(30),
                        validUntil,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_KILL_SWITCH_ATTESTATION",
                                request.accessGrant()
                                        .killSwitchRef()
                                        .id(),
                                '2'),
                        now);
        MirrorDeploymentIsolationRunTrust.Admission
                egress =
                new MirrorDeploymentIsolationRunTrust
                        .Admission(
                        request.scope(),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAttestationBundle
                                        .ARTIFACT_KIND,
                                "postgres-egress-decision",
                                '3'),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAuthorityKeySetPublication
                                        .ARTIFACT_KIND,
                                "postgres-egress-authority",
                                '4'),
                        request.accessGrant()
                                .egressAuthorityRef(),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAttestationStatusPublication
                                        .ARTIFACT_KIND,
                                "postgres-egress-status",
                                '5'),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAgentSnapshot
                                        .ARTIFACT_KIND,
                                "postgres-egress-snapshot",
                                '6'),
                        now,
                        validUntil);
        return new ReadOnlyShadowAccessAuthority.Admission(
                ReadOnlyShadowJobTestFixtures
                        .fingerprint('f'),
                request.accessGrant()
                        .zeroWriteProof(),
                limits,
                grant,
                killSwitch,
                egress,
                now,
                validUntil);
    }

    private static Object repositoryOutcome(
            Callable<ReadOnlyShadowJobRepository
                    .Submission> action) {
        try {
            return action.call();
        } catch (ReadOnlyShadowJobRepository
                 .Violation rejected) {
            return rejected.reason();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "PostgreSQL admission task failed",
                    failure);
        }
    }

    private static Object guardOutcome(
            Callable<ReadOnlyShadowExecutionGuard
                    .Lease> action) {
        try {
            return action.call();
        } catch (ReadOnlyShadowDataPlane
                 .Failure rejected) {
            return rejected.reason();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "PostgreSQL guard task failed",
                    failure);
        }
    }

    private static void awaitBarrier(
            CyclicBarrier barrier) {
        try {
            barrier.await(
                    10,
                    TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "PostgreSQL concurrency barrier interrupted",
                    interrupted);
        } catch (BrokenBarrierException
                 | TimeoutException unavailable) {
            throw new IllegalStateException(
                    "PostgreSQL concurrency barrier unavailable",
                    unavailable);
        }
    }

    private static <T> T awaitFuture(
            Future<T> future) throws Exception {
        try {
            return future.get(
                    15,
                    TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw timeout;
        }
    }

    private record Replica(
            JdbcTemplate jdbc,
            DataSourceTransactionManager transactions) {
    }

    private record JobFixture(
            DatabaseReadOnlyShadowJobRepository firstJobs,
            DatabaseReadOnlyShadowJobRepository secondJobs,
            ReadOnlyShadowComparisonIntegrity integrity,
            AtomicReference<Instant> now) {
    }
}
