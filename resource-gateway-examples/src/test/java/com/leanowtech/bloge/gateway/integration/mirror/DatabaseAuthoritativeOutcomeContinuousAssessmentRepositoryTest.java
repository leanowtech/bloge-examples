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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseAuthoritativeOutcomeContinuousAssessmentRepositoryTest {
    private static final AuthoritativeOutcomeContinuousAssessmentPolicy
            POLICY =
            new AuthoritativeOutcomeContinuousAssessmentPolicy(
                    Duration.ofSeconds(10),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(8),
                    3);

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(
                    DomainFidelityTestFixtures.NOW);

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private AuthoritativeOutcomeSelectedPopulationCompletenessProjector
            projector;
    private DatabaseAuthoritativeOutcomeSelectedPopulationRepository
            populations;
    private DatabaseAuthoritativeOutcomeContinuousAssessmentRepository
            repository;
    private AuthoritativeOutcomeSelectedPopulationTestFixtures.Population
            population;
    private AuthoritativeOutcomeContinuousAssessmentRequest request;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions =
                new DataSourceTransactionManager(database);
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK);
        populationIntegrity =
                new AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeObservationIntegrity observationIntegrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .outcomeAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        dispositionIntegrity =
                new AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .dispositionAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        projector =
                new AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        signer,
                        DomainFidelityTestFixtures.CLOCK);
        DatabaseAuthoritativeOutcomeInboxRepository outcomes =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        jdbc,
                        mapper,
                        observationIntegrity,
                        transactions,
                        now::get);
        outcomes.init();
        populations =
                new DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
                        jdbc,
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        projector,
                        transactions,
                        now::get);
        populations.init();
        population =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(populationIntegrity);
        populations.register(
                population.manifest(),
                population.chunks(),
                "");
        repository =
                new DatabaseAuthoritativeOutcomeContinuousAssessmentRepository(
                        jdbc,
                        mapper,
                        transactions,
                        now::get);
        repository.init();
        request = new AuthoritativeOutcomeContinuousAssessmentRequest(
                "",
                "refund-completeness",
                population.manifest().artifactRef());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void serializesConcurrentRegistrationAndRecoversAfterRestart()
            throws Exception {
        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Callable<AuthoritativeOutcomeContinuousAssessmentRepository
                    .Admission> register =
                    () -> repository.register(
                            population.manifest().scope(),
                            request);
            Future<AuthoritativeOutcomeContinuousAssessmentRepository
                    .Admission> first =
                    executor.submit(register);
            Future<AuthoritativeOutcomeContinuousAssessmentRepository
                    .Admission> second =
                    executor.submit(register);

            assertThat(List.of(
                    first.get().idempotentReplay(),
                    second.get().idempotentReplay()))
                    .containsExactlyInAnyOrder(false, true);
        }
        DatabaseAuthoritativeOutcomeContinuousAssessmentRepository
                restarted = repository();
        restarted.init();
        AuthoritativeOutcomeContinuousAssessmentRepository
                .ObservedProjection observed =
                restarted.find(
                        population.manifest().scope(),
                        request.projectionId())
                        .orElseThrow();

        assertThat(observed.freshness()).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.UNINITIALIZED);
        assertThat(observed.projection().populationRef())
                .isEqualTo(population.manifest().artifactRef());
        assertThat(observed.projection().recordFingerprint())
                .startsWith("sha256:");
    }

    @Test
    void publishesEvidenceAndExpiresFreshnessAtExactHalfOpenBoundary() {
        repository.register(
                population.manifest().scope(),
                request);
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                claim = claim("owner-a");
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment = assessment(1, "");

        AuthoritativeOutcomeContinuousAssessmentProjection published =
                repository.publish(
                        claim.lease(),
                        assessment.artifactRef(),
                        assessment.observationSetFingerprint(),
                        assessment.dispositionSetFingerprint(),
                        POLICY);

        assertThat(published.freshnessAt(now.get())).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.CURRENT);
        now.set(published.freshUntil());
        assertThat(repository.find(
                population.manifest().scope(),
                request.projectionId())
                .orElseThrow()
                .freshness()).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.STALE);

        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                refresh = claim("owner-b");
        advance(Duration.ofSeconds(1));
        AuthoritativeOutcomeContinuousAssessmentProjection unchanged =
                repository.unchanged(
                        refresh.lease(), POLICY);
        assertThat(unchanged.currentThrough())
                .isEqualTo(now.get());
        assertThat(unchanged.freshnessAt(now.get()))
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                .Freshness.CURRENT);
    }

    @Test
    void rejectsPhantomAssessmentAndWriteAtExactLeaseExpiry() {
        repository.register(
                population.manifest().scope(),
                request);
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                claim = claim("owner-a");
        MirrorArtifactRef phantom = new MirrorArtifactRef(
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        .ARTIFACT_KIND,
                request.assessmentId(),
                1,
                "sha256:" + "a".repeat(64));

        assertReason(
                () -> repository.publish(
                        claim.lease(),
                        phantom,
                        "sha256:" + "b".repeat(64),
                        "sha256:" + "c".repeat(64),
                        POLICY),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.ASSESSMENT_INVALID);

        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment = assessment(1, "");
        now.set(claim.lease().expiresAt());
        assertReason(
                () -> repository.publish(
                        claim.lease(),
                        assessment.artifactRef(),
                        assessment.observationSetFingerprint(),
                        assessment.dispositionSetFingerprint(),
                        POLICY),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.LEASE_LOST);
    }

    @Test
    void adoptsAssessmentHeadThatAdvancedBeyondAnUnpersistedCursor() {
        repository.register(
                population.manifest().scope(),
                request);
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                first = assessment(1, "");
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                second = assessment(
                2, first.assessmentFingerprint());
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                claim = claim("replacement-owner");

        AuthoritativeOutcomeContinuousAssessmentProjection adopted =
                repository.publish(
                        claim.lease(),
                        second.artifactRef(),
                        second.observationSetFingerprint(),
                        second.dispositionSetFingerprint(),
                        POLICY);

        assertThat(adopted.lastAssessmentRef())
                .isEqualTo(second.artifactRef());
        assertThat(adopted.lastAssessmentRef().revision())
                .isEqualTo(2);
    }

    @Test
    void backsOffExpiredLeasesAndEventuallyQuarantinesCrashLoop() {
        repository.register(
                population.manifest().scope(),
                request);
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                first = claim("owner-1");
        now.set(first.lease().expiresAt());

        assertThat(claimOrNoWork("owner-2").outcome())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentRepository
                                .Claim.Outcome.NO_WORK);
        advance(Duration.ofSeconds(2));
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                second = claim("owner-2");
        now.set(second.lease().expiresAt());
        assertThat(claimOrNoWork("owner-3").outcome())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentRepository
                                .Claim.Outcome.NO_WORK);
        advance(Duration.ofSeconds(4));
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                third = claim("owner-3");
        now.set(third.lease().expiresAt());

        assertThat(claimOrNoWork("owner-4").outcome())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentRepository
                                .Claim.Outcome.NO_WORK);
        AuthoritativeOutcomeContinuousAssessmentProjection quarantined =
                repository.find(
                        population.manifest().scope(),
                        request.projectionId())
                        .orElseThrow()
                        .projection();
        assertThat(quarantined.status()).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Status.QUARANTINED);
        assertThat(quarantined.freshnessAt(now.get())).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.QUARANTINED);
        assertThat(quarantined.consecutiveFailures())
                .isEqualTo(3);
    }

    @Test
    void detectsOutOfBandProjectionMutation() {
        repository.register(
                population.manifest().scope(),
                request);
        jdbc.update("""
                UPDATE mirror_outcome_continuous_assessments
                SET status = 'QUARANTINED'
                WHERE projection_id = ?
                """,
                request.projectionId());

        assertReason(
                () -> repository.find(
                        population.manifest().scope(),
                        request.projectionId()),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void rejectsProjectionRebindingAndPreexistingReservedStream() {
        repository.register(
                population.manifest().scope(),
                request);
        assertReason(
                () -> repository.register(
                        population.manifest().scope(),
                        new AuthoritativeOutcomeContinuousAssessmentRequest(
                                "",
                                request.projectionId(),
                                new MirrorArtifactRef(
                                        population.manifest()
                                                .artifactRef().kind(),
                                        population.manifest()
                                                .artifactRef().id(),
                                        2,
                                        "sha256:" + "d".repeat(64)))),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.CONTENT_CONFLICT);

        AuthoritativeOutcomeContinuousAssessmentRequest second =
                new AuthoritativeOutcomeContinuousAssessmentRequest(
                        "",
                        "already-owned",
                        population.manifest().artifactRef());
        assessment(
                second.assessmentId(),
                1,
                "");
        assertReason(
                () -> repository.register(
                        population.manifest().scope(),
                        second),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.CONTENT_CONFLICT);
    }

    @Test
    void writesBoundedHashChainedLifecycleAndRejectsDeletedHead() {
        repository.register(
                population.manifest().scope(),
                request);
        AuthoritativeOutcomeContinuousAssessmentLifecyclePage
                registered = repository.lifecycle(
                population.manifest().scope(),
                request.projectionId(),
                0,
                1);
        assertThat(registered.events())
                .extracting(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                .Transition.REGISTERED);
        assertThat(registered.predecessorFingerprint())
                .isBlank();
        assertThat(registered.hasMore()).isFalse();

        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                claim = claim("owner-a");
        AuthoritativeOutcomeContinuousAssessmentLifecyclePage
                first = repository.lifecycle(
                population.manifest().scope(),
                request.projectionId(),
                0,
                1);
        AuthoritativeOutcomeContinuousAssessmentLifecyclePage
                second = repository.lifecycle(
                population.manifest().scope(),
                request.projectionId(),
                first.nextOrdinal(),
                10);

        assertThat(first.hasMore()).isTrue();
        assertThat(second.predecessorFingerprint())
                .isEqualTo(first.events()
                        .getLast()
                        .eventFingerprint());
        assertThat(second.events())
                .extracting(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                .Transition.CLAIMED);
        assertThat(second.events().getFirst()
                .actorFingerprint())
                .isEqualTo(claim.projection()
                        .leaseOwnerFingerprint());
        assertThat(second.events().getFirst()
                .previousEventFingerprint())
                .isEqualTo(first.events().getLast()
                        .eventFingerprint());

        jdbc.update("""
                DELETE FROM mirror_outcome_continuous_lifecycle
                WHERE projection_id = ? AND event_ordinal = ?
                """,
                request.projectionId(),
                second.nextOrdinal());
        assertReason(
                () -> repository.find(
                        population.manifest().scope(),
                        request.projectionId()),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void adoptsExplicitMigratedBaselineOnlyForLegacyHeadZero() {
        repository.register(
                population.manifest().scope(),
                request);
        jdbc.update("""
                DELETE FROM mirror_outcome_continuous_lifecycle
                WHERE projection_id = ?
                """,
                request.projectionId());
        jdbc.update("""
                UPDATE mirror_outcome_continuous_assessments
                SET lifecycle_head_ordinal = 0,
                    lifecycle_head_fingerprint = ''
                WHERE projection_id = ?
                """,
                request.projectionId());

        claim("owner-after-upgrade");
        AuthoritativeOutcomeContinuousAssessmentLifecyclePage page =
                repository.lifecycle(
                        population.manifest().scope(),
                        request.projectionId(),
                        0,
                        10);

        assertThat(page.events())
                .extracting(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                .Transition.MIGRATED,
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                .Transition.CLAIMED);
        assertThat(page.events().getFirst()
                .projection().status())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                .Status.QUEUED);
        assertThat(page.events().getLast()
                .projection().status())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                .Status.RUNNING);
    }

    @Test
    void remediatesExactQuarantineAndExactlyReplaysAfterLaterClaim() {
        AuthoritativeOutcomeContinuousAssessmentProjection
                quarantined = quarantine();
        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                head = lifecycleHead();
        AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                command = remediationCommand(
                "remediation-1",
                quarantined,
                head,
                "DEPENDENCY_REPAIRED");

        AuthoritativeOutcomeContinuousAssessmentRepository
                .Remediation accepted = repository.remediate(
                population.manifest().scope(),
                request.projectionId(),
                command,
                "SERVICE:outcome-operator");

        assertThat(accepted.idempotentReplay())
                .isFalse();
        AuthoritativeOutcomeContinuousAssessmentRemediationReceipt
                receipt = accepted.receipt();
        receipt.verify(mapper);
        assertThat(receipt.previousProjection())
                .isEqualTo(quarantined);
        assertThat(receipt.lifecycleEvent().transition())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                .Transition.REMEDIATION_ACCEPTED);
        assertThat(receipt.lifecycleEvent()
                .projection().status())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                .Status.QUEUED);
        assertThat(receipt.lifecycleEvent()
                .projection().consecutiveFailures())
                .isZero();
        assertThat(receipt.lifecycleEvent()
                .projection().attemptCount())
                .isEqualTo(quarantined.attemptCount());
        assertThat(receipt.lifecycleEvent()
                .projection().leaseEpoch())
                .isEqualTo(quarantined.leaseEpoch());
        assertThat(receipt.lifecycleEvent()
                .previousEventFingerprint())
                .isEqualTo(head.eventFingerprint());

        claim("owner-after-remediation");
        AuthoritativeOutcomeContinuousAssessmentRepository
                .Remediation replay = repository.remediate(
                population.manifest().scope(),
                request.projectionId(),
                command,
                "SERVICE:outcome-operator");

        assertThat(replay.idempotentReplay())
                .isTrue();
        assertThat(replay.receipt())
                .isEqualTo(receipt);
    }

    @Test
    void rejectsBlindWrongStateAndReusedRemediationCommands() {
        repository.register(
                population.manifest().scope(),
                request);
        AuthoritativeOutcomeContinuousAssessmentProjection
                queued = repository.find(
                population.manifest().scope(),
                request.projectionId())
                .orElseThrow()
                .projection();
        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                registered = lifecycleHead();
        assertReason(
                () -> repository.remediate(
                        population.manifest().scope(),
                        request.projectionId(),
                        remediationCommand(
                                "not-quarantined",
                                queued,
                                registered,
                                "REVIEWED"),
                        "SERVICE:outcome-operator"),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.REMEDIATION_NOT_QUARANTINED);

        AuthoritativeOutcomeContinuousAssessmentProjection
                quarantined = repository.fail(
                claim("owner-a").lease(),
                "DEPENDENCY_FAILED",
                false,
                POLICY);
        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                head = lifecycleHead();
        AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                wrongFence = new
                AuthoritativeOutcomeContinuousAssessmentRemediationRequest(
                "",
                "wrong-fence",
                "sha256:" + "a".repeat(64),
                head.eventOrdinal(),
                head.eventFingerprint(),
                "REVIEWED");
        assertReason(
                () -> repository.remediate(
                        population.manifest().scope(),
                        request.projectionId(),
                        wrongFence,
                        "SERVICE:outcome-operator"),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.REMEDIATION_FENCE_MISMATCH);

        AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                accepted = remediationCommand(
                "stable-command",
                quarantined,
                head,
                "DEPENDENCY_REPAIRED");
        repository.remediate(
                population.manifest().scope(),
                request.projectionId(),
                accepted,
                "SERVICE:outcome-operator");
        AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                changed = remediationCommand(
                "stable-command",
                quarantined,
                head,
                "OPERATOR_OVERRIDE");
        assertReason(
                () -> repository.remediate(
                        population.manifest().scope(),
                        request.projectionId(),
                        changed,
                        "SERVICE:outcome-operator"),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.REMEDIATION_COMMAND_CONFLICT);
        assertReason(
                () -> repository.remediate(
                        population.manifest().scope(),
                        request.projectionId(),
                        accepted,
                        "SERVICE:different-operator"),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.REMEDIATION_COMMAND_CONFLICT);
    }

    @Test
    void failsClosedWhenRetainedRemediationReceiptIsTampered() {
        AuthoritativeOutcomeContinuousAssessmentProjection
                quarantined = quarantine();
        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                head = lifecycleHead();
        AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                command = remediationCommand(
                "tamper-check",
                quarantined,
                head,
                "DEPENDENCY_REPAIRED");
        repository.remediate(
                population.manifest().scope(),
                request.projectionId(),
                command,
                "SERVICE:outcome-operator");
        jdbc.update("""
                UPDATE mirror_outcome_continuous_remediations
                SET reason_code = 'TAMPERED'
                WHERE projection_id = ? AND command_id = ?
                """,
                request.projectionId(),
                command.commandId());

        assertReason(
                () -> repository.remediate(
                        population.manifest().scope(),
                        request.projectionId(),
                        command,
                        "SERVICE:outcome-operator"),
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    private AuthoritativeOutcomeContinuousAssessmentRepository.Claim
    claim(String owner) {
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                claim = claimOrNoWork(owner);
        assertThat(claim.outcome()).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentRepository
                        .Claim.Outcome.ACQUIRED);
        return claim;
    }

    private AuthoritativeOutcomeContinuousAssessmentProjection
    quarantine() {
        repository.register(
                population.manifest().scope(),
                request);
        return repository.fail(
                claim("quarantine-owner").lease(),
                "DEPENDENCY_FAILED",
                false,
                POLICY);
    }

    private AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
    lifecycleHead() {
        return repository.lifecycle(
                population.manifest().scope(),
                request.projectionId(),
                0,
                100)
                .events()
                .getLast();
    }

    private static
    AuthoritativeOutcomeContinuousAssessmentRemediationRequest
    remediationCommand(
            String commandId,
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection,
            AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                    head,
            String reasonCode) {
        return new
                AuthoritativeOutcomeContinuousAssessmentRemediationRequest(
                "",
                commandId,
                projection.recordFingerprint(),
                head.eventOrdinal(),
                head.eventFingerprint(),
                reasonCode);
    }

    private AuthoritativeOutcomeContinuousAssessmentRepository.Claim
    claimOrNoWork(String owner) {
        return repository.claimNext(
                population.manifest().scope().region(),
                population.manifest().scope()
                        .environmentId(),
                owner,
                POLICY);
    }

    private AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    assessment(
            long revision,
            String predecessorFingerprint) {
        return assessment(
                request.assessmentId(),
                revision,
                predecessorFingerprint);
    }

    private AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    assessment(
            String assessmentId,
            long revision,
            String predecessorFingerprint) {
        return new AuthoritativeOutcomeSelectedPopulationService(
                populations,
                populationIntegrity,
                dispositionIntegrity,
                projector)
                .assess(
                        population.manifest().scope(),
                        population.manifest().populationId(),
                        population.manifest().revision(),
                        assessmentId,
                        revision,
                        predecessorFingerprint)
                .assessment();
    }

    private DatabaseAuthoritativeOutcomeContinuousAssessmentRepository
    repository() {
        return new DatabaseAuthoritativeOutcomeContinuousAssessmentRepository(
                jdbc,
                mapper,
                transactions,
                now::get);
    }

    private void advance(Duration duration) {
        now.set(now.get().plus(duration));
    }

    private static void assertReason(
            Runnable action,
            AuthoritativeOutcomeContinuousAssessmentRepository.Reason
                    reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        AuthoritativeOutcomeContinuousAssessmentRepository
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeContinuousAssessmentRepository
                                .Violation) failure).reason())
                .isEqualTo(reason);
    }
}
