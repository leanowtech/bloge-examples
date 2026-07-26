package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyntheticRegionalReadOnlyShadowProviderTest {
    private static final Instant NOW =
            OnlineReadOnlyShadowBaselineTestFixtures.NOW;
    private static final Clock BASELINE_CLOCK =
            Clock.fixed(
                    NOW.plusSeconds(1),
                    ZoneOffset.UTC);
    private static final Clock CANDIDATE_CLOCK =
            Clock.fixed(
                    NOW.plusSeconds(3),
                    ZoneOffset.UTC);
    private static final Clock RESOLUTION_CLOCK =
            Clock.fixed(
                    NOW.plusSeconds(4),
                    ZoneOffset.UTC);

    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures.mapper();
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy =
            new PayloadFreeEqualityReadOnlyShadowPolicy(
                    mapper);
    private final VisualEvidenceSigner baselineSigner =
            InMemoryVisualEvidenceSigner.usingClock(
                    BASELINE_CLOCK);
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            baselineIntegrity =
            new OnlineReadOnlyShadowBaselineObservationIntegrity(
                    mapper,
                    OnlineReadOnlyShadowBaselineEvidenceAuthority
                            .from(baselineSigner),
                    RESOLUTION_CLOCK);
    private final VisualEvidenceSigner candidateSigner =
            InMemoryVisualEvidenceSigner.usingClock(
                    CANDIDATE_CLOCK);
    private final MirrorEvidenceIntegrityService candidateIntegrity =
            new MirrorEvidenceIntegrityService(
                    mapper,
                    candidateSigner,
                    CANDIDATE_CLOCK);
    private final VisualEvidenceSigner resolutionSigner =
            InMemoryVisualEvidenceSigner.usingClock(
                    RESOLUTION_CLOCK);
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            resolutionIntegrity =
            new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                    mapper,
                    resolutionSigner,
                    RESOLUTION_CLOCK);

    private MirrorPlan plan;
    private ReadOnlyShadowJobRequest request;
    private ReadOnlyShadowAccessAuthority.Admission admission;
    private SyntheticRegionalReadOnlyShadowProvider provider;
    private AtomicInteger candidateFactoryInvocations;

    @BeforeEach
    void setUp() {
        plan = MirrorPersistenceTestFixtures.plan(
                mapper,
                MirrorPersistenceTestFixtures
                        .scope("support"),
                "refund-shadow-plan",
                '4');
        request = request(plan);
        admission =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .admission(request);
        candidateFactoryInvocations =
                new AtomicInteger();
        provider =
                new SyntheticRegionalReadOnlyShadowProvider(
                        List.of(fixture()),
                        command -> {
                            candidateFactoryInvocations
                                    .incrementAndGet();
                            return candidateBundle(command);
                        },
                        baselineIntegrity,
                        candidateIntegrity,
                        mapper,
                        BASELINE_CLOCK,
                        8);
    }

    @Test
    void enforcesIdempotentAppendOnlyBaselineAndCandidateArtifacts() {
        OnlineReadOnlyShadowBaselineCommand baselineCommand =
                baselineCommand();

        OnlineReadOnlyShadowBaselineObservation first =
                provider.baselineAuthority()
                        .observe(baselineCommand);
        OnlineReadOnlyShadowBaselineObservation retry =
                provider.baselineAuthority()
                        .observe(baselineCommand);

        assertThat(retry).isEqualTo(first);
        assertThat(provider.baselineAuthority()
                .resolve(
                        request.scope(),
                        first.artifactRef()))
                .isEqualTo(first);

        OnlineReadOnlyShadowCandidateCommand candidateCommand =
                candidateCommand(first);
        MirrorEvidenceBundle candidate =
                provider.candidateAuthority()
                        .execute(candidateCommand);
        assertThat(provider.candidateAuthority()
                .execute(candidateCommand))
                .isEqualTo(candidate);
        MirrorArtifactRef candidateRef =
                candidateRef(candidate);
        assertThat(provider.candidateAuthority()
                .resolve(
                        request.scope(),
                        candidateRef))
                .isEqualTo(candidate);
    }

    @Test
    void sameExecutionIdentityCannotBeReusedForAlteredCommands() {
        OnlineReadOnlyShadowBaselineCommand baselineCommand =
                baselineCommand();
        OnlineReadOnlyShadowBaselineObservation baseline =
                provider.baselineAuthority()
                        .observe(baselineCommand);
        OnlineReadOnlyShadowBaselineCommand alteredBaseline =
                new OnlineReadOnlyShadowBaselineCommand(
                        baselineCommand.schemaVersion(),
                        baselineCommand.executionId(),
                        baselineCommand.requestId(),
                        baselineCommand.scope(),
                        baselineCommand.inventoryRef(),
                        baselineCommand.unitId(),
                        baselineCommand.scenarioCaseRef(),
                        baselineCommand.targetCapabilityRef(),
                        baselineCommand.baselineBindingRef(),
                        baselineCommand.comparisonPolicyRef(),
                        baselineCommand.accessGrant(),
                        fingerprint('f'),
                        baselineCommand.admittedAt(),
                        baselineCommand.deadlineAt());

        assertThatThrownBy(() ->
                provider.baselineAuthority()
                        .observe(alteredBaseline))
                .isInstanceOf(
                        OnlineReadOnlyShadowBaselineAuthority
                                .AuthorityException.class)
                .extracting("reasonCode")
                .isEqualTo(
                        "SYNTHETIC_BASELINE_EXECUTION_ID_CONFLICT");

        OnlineReadOnlyShadowCandidateCommand candidateCommand =
                candidateCommand(baseline);
        provider.candidateAuthority()
                .execute(candidateCommand);
        OnlineReadOnlyShadowCandidateCommand alteredCandidate =
                new OnlineReadOnlyShadowCandidateCommand(
                        candidateCommand.schemaVersion(),
                        candidateCommand.executionId(),
                        candidateCommand.requestId(),
                        candidateCommand.scope(),
                        candidateCommand.inventoryRef(),
                        candidateCommand.unitId(),
                        candidateCommand.scenarioCaseRef(),
                        candidateCommand.targetCapabilityRef(),
                        candidateCommand.candidatePlanRef(),
                        candidateCommand.comparisonPolicyRef(),
                        candidateCommand.baselineObservationRef(),
                        candidateCommand.payloadVaultReceiptRef(),
                        candidateCommand
                                .requestContextFingerprint(),
                        candidateCommand.accessGrant(),
                        fingerprint('e'),
                        candidateCommand.admittedAt(),
                        candidateCommand.deadlineAt());

        assertThatThrownBy(() ->
                provider.candidateAuthority()
                        .execute(alteredCandidate))
                .isInstanceOf(
                        OnlineReadOnlyShadowCandidateAuthority
                                .AuthorityException.class)
                .extracting("reasonCode")
                .isEqualTo(
                        "SYNTHETIC_CANDIDATE_EXECUTION_ID_CONFLICT");
    }

    @Test
    void candidateMustMatchEveryStoredBaselineCommandCoordinate() {
        OnlineReadOnlyShadowBaselineObservation baseline =
                provider.baselineAuthority()
                        .observe(baselineCommand());
        OnlineReadOnlyShadowCandidateCommand source =
                candidateCommand(baseline);
        OnlineReadOnlyShadowCandidateCommand unpaired =
                new OnlineReadOnlyShadowCandidateCommand(
                        source.schemaVersion(),
                        "execution-other-pair",
                        source.requestId(),
                        source.scope(),
                        source.inventoryRef(),
                        source.unitId(),
                        source.scenarioCaseRef(),
                        source.targetCapabilityRef(),
                        source.candidatePlanRef(),
                        source.comparisonPolicyRef(),
                        source.baselineObservationRef(),
                        source.payloadVaultReceiptRef(),
                        source.requestContextFingerprint(),
                        source.accessGrant(),
                        source.admissionFingerprint(),
                        source.admittedAt(),
                        source.deadlineAt());

        assertThatThrownBy(() ->
                provider.candidateAuthority()
                        .execute(unpaired))
                .isInstanceOf(
                        OnlineReadOnlyShadowCandidateAuthority
                                .AuthorityException.class)
                .extracting("reasonCode")
                .isEqualTo(
                        "SYNTHETIC_CANDIDATE_BASELINE_MISMATCH");
    }

    @Test
    void concurrentRetriesShareOneAtomicBaselineAndCandidateArtifact()
            throws Exception {
        OnlineReadOnlyShadowBaselineCommand baselineCommand =
                baselineCommand();
        try (var executor =
                     Executors
                             .newVirtualThreadPerTaskExecutor()) {
            List<Callable<OnlineReadOnlyShadowBaselineObservation>>
                    baselineCalls =
                    java.util.stream.IntStream
                            .range(0, 32)
                            .mapToObj(ignored ->
                                    (Callable<OnlineReadOnlyShadowBaselineObservation>)
                                            () -> provider
                                                    .baselineAuthority()
                                                    .observe(
                                                            baselineCommand))
                            .toList();
            var baselineResults =
                    executor.invokeAll(
                            baselineCalls);
            OnlineReadOnlyShadowBaselineObservation baseline =
                    baselineResults.getFirst()
                            .get();
            assertThat(baselineResults)
                    .allSatisfy(result ->
                            assertThat(result.get())
                                    .isEqualTo(
                                            baseline));

            OnlineReadOnlyShadowCandidateCommand candidateCommand =
                    candidateCommand(baseline);
            List<Callable<MirrorEvidenceBundle>>
                    candidateCalls =
                    java.util.stream.IntStream
                            .range(0, 32)
                            .mapToObj(ignored ->
                                    (Callable<MirrorEvidenceBundle>)
                                            () -> provider
                                                    .candidateAuthority()
                                                    .execute(
                                                            candidateCommand))
                            .toList();
            var candidateResults =
                    executor.invokeAll(
                            candidateCalls);
            MirrorEvidenceBundle candidate =
                    candidateResults.getFirst()
                            .get();
            assertThat(candidateResults)
                    .allSatisfy(result ->
                            assertThat(result.get())
                                    .isEqualTo(
                                            candidate));
        }
    }

    @Test
    void governedOnlineDataPlaneProducesComparisonAndV2SourceProof() {
        ReadOnlyShadowSourceResolutionAttestationRepository
                attestations =
                mock(
                        ReadOnlyShadowSourceResolutionAttestationRepository
                                .class);
        when(attestations.create(any()))
                .thenAnswer(answer ->
                        answer.getArgument(0));
        GovernedReadOnlyShadowDataPlane dataPlane =
                onlineDataPlane(attestations);

        ReadOnlyShadowDataPlane.ExecutionResult result =
                dataPlane.execute(
                        new ReadOnlyShadowDataPlane.Permit(
                                "execution-synthetic-pair",
                                request,
                                1,
                                request.deadlineAt(),
                                new ReadOnlyShadowDataPlane
                                        .ExecutionControl() {
                                    @Override
                                    public Instant
                                    leaseExpiresAt() {
                                        return request.deadlineAt();
                                    }

                                    @Override
                                    public Instant heartbeat() {
                                        return request.deadlineAt();
                                    }
                                }));

        assertThat(result.baseline().role())
                .isEqualTo(
                        ReadOnlyShadowComparison.SourceRole
                                .BASELINE);
        assertThat(result.candidate().role())
                .isEqualTo(
                        ReadOnlyShadowComparison.SourceRole
                                .CANDIDATE);
        assertThat(result.baseline()
                .requestContextFingerprint())
                .isEqualTo(
                        result.candidate()
                                .requestContextFingerprint());
        assertThat(result.sourceResolutionAttestationRef()
                .kind())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestation
                                .ARTIFACT_KIND);
        assertThat(result.results()).isNotEmpty();
        assertThat(result.accessProof()
                .writeCredentialExposed()).isFalse();
        assertThat(result.accessProof()
                .writeAttemptCount()).isZero();
    }

    @Test
    void durableWorkerRecoversOneOnlineExecutionAfterProcessCrash() {
        AtomicReference<Instant> databaseNow =
                new AtomicReference<>(NOW);
        EmbeddedDatabase database =
                new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true)
                        .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            DataSourceTransactionManager transactions =
                    new DataSourceTransactionManager(database);
            ReadOnlyShadowComparisonIntegrity
                    comparisonIntegrity =
                    ReadOnlyShadowJobTestFixtures
                            .integrity(mapper);
            DatabaseReadOnlyShadowJobRepository jobs =
                    new DatabaseReadOnlyShadowJobRepository(
                            jdbc,
                            mapper,
                            comparisonIntegrity,
                            transactions,
                            databaseNow::get);
            jobs.init();
            DatabaseReadOnlyShadowSourceResolutionAttestationRepository
                    attestations =
                    new DatabaseReadOnlyShadowSourceResolutionAttestationRepository(
                            jdbc,
                            mapper,
                            resolutionIntegrity);
            attestations.init();
            GovernedReadOnlyShadowDataPlane governed =
                    onlineDataPlane(attestations);
            AtomicBoolean firstAttempt =
                    new AtomicBoolean(true);
            AtomicReference<ReadOnlyShadowDataPlane.ExecutionResult>
                    abandonedResult =
                    new AtomicReference<>();
            ReadOnlyShadowDataPlane crashOnce =
                    crashOnceAfterExecution(
                            governed,
                            firstAttempt,
                            abandonedResult);
            ReadOnlyShadowJobWorker worker =
                    new ReadOnlyShadowJobWorker(
                            jobs,
                            crashOnce,
                            comparisonIntegrity,
                            ReadOnlyShadowJobTestFixtures
                                    .POLICY);
            ReadOnlyShadowJobRepository.Submission submission =
                    jobs.submit(
                            request,
                            ReadOnlyShadowJobTestFixtures
                                    .POLICY);
            String jobId =
                    submission.job().jobId();

            assertThatThrownBy(() -> worker.runOne(
                    request.scope().region(),
                    request.scope().environmentId(),
                    "synthetic-worker-before-crash"))
                    .isInstanceOf(
                            SimulatedProcessCrash.class);
            ReadOnlyShadowJob abandoned =
                    jobs.find(
                            request.scope(),
                            jobId)
                            .orElseThrow();
            assertThat(abandoned.status())
                    .isEqualTo(
                            ReadOnlyShadowJob.Status.RUNNING);
            assertThat(abandoned.attemptCount())
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM read_only_shadow_source_resolution_attestation
                    """, Integer.class))
                    .isEqualTo(1);

            databaseNow.set(
                    abandoned.leaseExpiresAt()
                            .plusSeconds(1));
            ReadOnlyShadowJobRepository.Claim recovered =
                    worker.runOne(
                            request.scope().region(),
                            request.scope().environmentId(),
                            "synthetic-worker-after-crash");

            assertThat(recovered.outcome())
                    .isEqualTo(
                            ReadOnlyShadowJobRepository
                                    .ClaimOutcome.ACQUIRED);
            assertThat(recovered.lease().epoch())
                    .isGreaterThan(
                            abandoned.leaseEpoch());
            ReadOnlyShadowJob completed =
                    jobs.find(
                            request.scope(),
                            jobId)
                            .orElseThrow();
            assertThat(completed.status())
                    .isEqualTo(
                            ReadOnlyShadowJob.Status.SUCCEEDED);
            assertThat(completed.attemptCount())
                    .isEqualTo(2);
            ReadOnlyShadowComparison comparison =
                    jobs.findComparison(
                            request.scope(),
                            jobId)
                            .orElseThrow();
            ReadOnlyShadowDataPlane.ExecutionResult first =
                    abandonedResult.get();
            assertThat(comparison.baseline())
                    .isEqualTo(first.baseline());
            assertThat(comparison.candidate())
                    .isEqualTo(first.candidate());
            assertThat(comparison.sourceResolutionAttestationRef())
                    .isEqualTo(
                            first.sourceResolutionAttestationRef());
            assertThat(candidateFactoryInvocations)
                    .hasValue(1);
            ReadOnlyShadowSourceResolutionAttestation proof =
                    attestations.find(
                            request.scope(),
                            comparison
                                    .sourceResolutionAttestationRef()
                                    .id(),
                            comparison
                                    .sourceResolutionAttestationRef()
                                    .revision())
                            .orElseThrow();
            assertThat(proof.schemaVersion())
                    .isEqualTo(
                            ReadOnlyShadowSourceResolutionAttestation
                                    .ONLINE_SCHEMA_VERSION);
            assertThat(proof.executionId())
                    .isEqualTo(jobId);
            assertThat(proof.sourceMode())
                    .isEqualTo(
                            ReadOnlyShadowJobRequest.SourceMode
                                    .ONLINE_EXECUTION);
            assertThat(resolutionIntegrity.verify(proof))
                    .isEqualTo(proof);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM read_only_shadow_source_resolution_attestation
                    """, Integer.class))
                    .isEqualTo(1);

            List<ReadOnlyShadowJobLifecycleEvent> lifecycle =
                    jobs.lifecycle(
                            request.scope(),
                            jobId,
                            0,
                            128);
            assertThat(lifecycle)
                    .extracting(
                            ReadOnlyShadowJobLifecycleEvent
                                    ::transition)
                    .startsWith(
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.ADMITTED,
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.CLAIMED)
                    .contains(
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.LEASE_RENEWED,
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.TAKEN_OVER)
                    .endsWith(
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.SUCCEEDED);
            assertThat(lifecycle)
                    .extracting(
                            ReadOnlyShadowJobLifecycleEvent
                                    ::sequence)
                    .isSorted()
                    .doesNotHaveDuplicates();
            assertThat(lifecycle.getLast()
                    .comparisonFingerprint())
                    .isEqualTo(
                            comparison.comparisonFingerprint());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void durableWorkerRetriesTransientOnlineResolutionWithoutReexecution() {
        AtomicReference<Instant> databaseNow =
                new AtomicReference<>(NOW);
        EmbeddedDatabase database =
                new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true)
                        .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            ReadOnlyShadowComparisonIntegrity
                    comparisonIntegrity =
                    ReadOnlyShadowJobTestFixtures
                            .integrity(mapper);
            DatabaseReadOnlyShadowJobRepository jobs =
                    new DatabaseReadOnlyShadowJobRepository(
                            jdbc,
                            mapper,
                            comparisonIntegrity,
                            new DataSourceTransactionManager(
                                    database),
                            databaseNow::get);
            jobs.init();
            DatabaseReadOnlyShadowSourceResolutionAttestationRepository
                    attestations =
                    new DatabaseReadOnlyShadowSourceResolutionAttestationRepository(
                            jdbc,
                            mapper,
                            resolutionIntegrity);
            attestations.init();
            AtomicBoolean firstResolution =
                    new AtomicBoolean(true);
            OnlineReadOnlyShadowCandidateAuthority
                    transientCandidate =
                    unavailableOnFirstResolution(
                            firstResolution);
            ReadOnlyShadowJobWorker worker =
                    new ReadOnlyShadowJobWorker(
                            jobs,
                            onlineDataPlane(
                                    attestations,
                                    transientCandidate),
                            comparisonIntegrity,
                            ReadOnlyShadowJobTestFixtures
                                    .POLICY);
            String jobId =
                    jobs.submit(
                            request,
                            ReadOnlyShadowJobTestFixtures
                                    .POLICY)
                            .job()
                            .jobId();

            worker.runOne(
                    request.scope().region(),
                    request.scope().environmentId(),
                    "synthetic-worker-retry-1");

            ReadOnlyShadowJob queued =
                    jobs.find(
                            request.scope(),
                            jobId)
                            .orElseThrow();
            assertThat(queued.status())
                    .isEqualTo(
                            ReadOnlyShadowJob.Status.QUEUED);
            assertThat(queued.attemptCount())
                    .isEqualTo(1);
            assertThat(queued.failureCode())
                    .isEqualTo(
                            "RG.MIRROR.SHADOW."
                                    + ReadOnlyShadowDataPlane
                                    .FailureReason
                                    .SOURCE_RESOLUTION_UNAVAILABLE
                                    .name());
            assertThat(candidateFactoryInvocations)
                    .hasValue(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM read_only_shadow_source_resolution_attestation
                    """, Integer.class))
                    .isZero();

            databaseNow.set(
                    queued.nextEligibleAt()
                            .plusMillis(1));
            worker.runOne(
                    request.scope().region(),
                    request.scope().environmentId(),
                    "synthetic-worker-retry-2");

            ReadOnlyShadowJob completed =
                    jobs.find(
                            request.scope(),
                            jobId)
                            .orElseThrow();
            assertThat(completed.status())
                    .isEqualTo(
                            ReadOnlyShadowJob.Status.SUCCEEDED);
            assertThat(completed.attemptCount())
                    .isEqualTo(2);
            assertThat(candidateFactoryInvocations)
                    .hasValue(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM read_only_shadow_source_resolution_attestation
                    """, Integer.class))
                    .isEqualTo(1);
            assertThat(jobs.lifecycle(
                    request.scope(),
                    jobId,
                    0,
                    128))
                    .extracting(
                            ReadOnlyShadowJobLifecycleEvent
                                    ::transition)
                    .containsSubsequence(
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.RETRY_SCHEDULED,
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.CLAIMED,
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.SUCCEEDED)
                    .doesNotContain(
                            ReadOnlyShadowJobLifecycleEvent
                                    .Transition.TAKEN_OVER);
        } finally {
            database.shutdown();
        }
    }

    private GovernedReadOnlyShadowDataPlane onlineDataPlane(
            ReadOnlyShadowSourceResolutionAttestationRepository
                    attestations) {
        return onlineDataPlane(
                attestations,
                provider.candidateAuthority());
    }

    private GovernedReadOnlyShadowDataPlane onlineDataPlane(
            ReadOnlyShadowSourceResolutionAttestationRepository
                    attestations,
            OnlineReadOnlyShadowCandidateAuthority
                    candidateAuthority) {
        OnlineReadOnlyShadowBaselineConnector baseline =
                new OnlineReadOnlyShadowBaselineConnector(
                        provider.baselineAuthority(),
                        baselineIntegrity,
                        mapper,
                        RESOLUTION_CLOCK);
        OnlineReadOnlyShadowCandidateConnector candidate =
                new OnlineReadOnlyShadowCandidateConnector(
                        provider.baselineAuthority(),
                        baselineIntegrity,
                        candidateAuthority,
                        candidateIntegrity,
                        policy,
                        mapper,
                        RESOLUTION_CLOCK);
        OnlineReadOnlyShadowSourceResolutionVerifier resolver =
                new OnlineReadOnlyShadowSourceResolutionVerifier(
                        provider.baselineAuthority(),
                        baselineIntegrity,
                        candidateAuthority,
                        candidateIntegrity,
                        policy,
                        attestations,
                        resolutionIntegrity,
                        mapper,
                        RESOLUTION_CLOCK);
        return new GovernedReadOnlyShadowDataPlane(
                authority(),
                guard(),
                baseline,
                candidate,
                resolver,
                policy,
                RESOLUTION_CLOCK);
    }

    private OnlineReadOnlyShadowCandidateAuthority
    unavailableOnFirstResolution(
            AtomicBoolean firstResolution) {
        OnlineReadOnlyShadowCandidateAuthority delegate =
                provider.candidateAuthority();
        return new OnlineReadOnlyShadowCandidateAuthority() {
            @Override
            public boolean ready() {
                return delegate.ready();
            }

            @Override
            public MirrorEvidenceBundle execute(
                    OnlineReadOnlyShadowCandidateCommand command) {
                return delegate.execute(command);
            }

            @Override
            public MirrorEvidenceBundle resolve(
                    CapabilitySnapshot.Scope scope,
                    MirrorArtifactRef evidenceRef) {
                if (firstResolution.compareAndSet(
                        true, false)) {
                    throw new AuthorityException(
                            Failure.UNAVAILABLE,
                            "SYNTHETIC_TRANSIENT_RESOLUTION_OUTAGE");
                }
                return delegate.resolve(
                        scope, evidenceRef);
            }
        };
    }

    private static ReadOnlyShadowDataPlane
    crashOnceAfterExecution(
            ReadOnlyShadowDataPlane delegate,
            AtomicBoolean firstAttempt,
            AtomicReference<ReadOnlyShadowDataPlane.ExecutionResult>
                    abandonedResult) {
        return new ReadOnlyShadowDataPlane() {
            @Override
            public boolean ready() {
                return delegate.ready();
            }

            @Override
            public ExecutionResult execute(
                    Permit permit) {
                ExecutionResult result =
                        delegate.execute(permit);
                if (firstAttempt.compareAndSet(
                        true, false)) {
                    abandonedResult.set(result);
                    throw new SimulatedProcessCrash();
                }
                return result;
            }
        };
    }

    private ReadOnlyShadowAccessAuthority authority() {
        return new ReadOnlyShadowAccessAuthority() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public Admission admit(
                    ReadOnlyShadowDataPlane.Permit permit) {
                return admission;
            }

            @Override
            public Confirmation confirm(
                    Admission admitted,
                    Instant startedAt,
                    Instant completedAt) {
                return ReadOnlyShadowSourceResolutionTestFixtures
                        .confirmation(admission);
            }
        };
    }

    private static ReadOnlyShadowExecutionGuard guard() {
        ReadOnlyShadowExecutionGuard guard =
                mock(ReadOnlyShadowExecutionGuard.class);
        ReadOnlyShadowExecutionGuard.Lease lease =
                mock(ReadOnlyShadowExecutionGuard.Lease.class);
        when(guard.ready()).thenReturn(true);
        when(guard.acquire(any(), any()))
                .thenReturn(lease);
        return guard;
    }

    private ReadOnlyShadowJobRequest request(
            MirrorPlan exactPlan) {
        ReadOnlyShadowJobRequest source =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .request("synthetic-pair");
        return new ReadOnlyShadowJobRequest(
                source.schemaVersion(),
                source.requestId(),
                exactPlan.scope(),
                source.inventoryRef(),
                source.unitId(),
                source.scenarioCaseRef(),
                exactPlan.rootCapability(),
                new MirrorArtifactRef(
                        "MIRROR_PLAN",
                        exactPlan.planId(),
                        1,
                        exactPlan.planFingerprint()),
                source.baselineBindingRef(),
                policy.reference(),
                source.accessGrant(),
                NOW.plus(Duration.ofMinutes(5)));
    }

    private SyntheticRegionalReadOnlyShadowProvider
            .BaselineFixture fixture() {
        return new SyntheticRegionalReadOnlyShadowProvider
                .BaselineFixture(
                request.baselineBindingRef(),
                ref(
                        "WORKLOAD_IDENTITY",
                        "synthetic-read-identity",
                        'a'),
                ref(
                        "WORKLOAD_IDENTITY_ATTESTATION",
                        "synthetic-read-identity",
                        'b'),
                ref(
                        "PAYLOAD_VAULT_RECEIPT",
                        "synthetic-vault-receipt",
                        'c'),
                ref(
                        "READ_ONLY_TRANSPORT_ATTESTATION",
                        "synthetic-read-transport",
                        'd'),
                fingerprint('e'),
                fingerprint('f'),
                fingerprint('1'),
                fingerprint('2'),
                ref(
                        "JSON_SCHEMA",
                        "synthetic-response",
                        '3'),
                Map.of(
                        DomainFidelityProfile.Dimension
                                .BEHAVIOR,
                        fingerprint('4'),
                        DomainFidelityProfile.Dimension
                                .CONTRACT,
                        fingerprint('5')),
                MirrorRunEvidence.EvidenceClass
                        .CERTIFIABLE,
                true);
    }

    private OnlineReadOnlyShadowBaselineCommand
    baselineCommand() {
        return new OnlineReadOnlyShadowBaselineCommand(
                OnlineReadOnlyShadowBaselineCommand
                        .SCHEMA_VERSION,
                "execution-synthetic-pair",
                request.requestId(),
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.baselineBindingRef(),
                request.comparisonPolicyRef(),
                request.accessGrant(),
                admission.admissionFingerprint(),
                admission.admittedAt(),
                admission.validUntil());
    }

    private OnlineReadOnlyShadowCandidateCommand
    candidateCommand(
            OnlineReadOnlyShadowBaselineObservation baseline) {
        return new OnlineReadOnlyShadowCandidateCommand(
                OnlineReadOnlyShadowCandidateCommand
                        .SCHEMA_VERSION,
                "execution-synthetic-pair",
                request.requestId(),
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.candidatePlanRef(),
                request.comparisonPolicyRef(),
                baseline.artifactRef(),
                baseline.payloadVaultReceiptRef(),
                baseline.requestContextFingerprint(),
                request.accessGrant(),
                admission.admissionFingerprint(),
                admission.admittedAt(),
                admission.validUntil());
    }

    private MirrorEvidenceBundle candidateBundle(
            OnlineReadOnlyShadowCandidateCommand command) {
        Instant startedAt = NOW.plusSeconds(1);
        MirrorRunEvidence evidence =
                new MirrorRunEvidence(
                        MirrorRunEvidence.SCHEMA_VERSION_V1,
                        "candidate-synthetic-pair",
                        command.commandFingerprint(mapper),
                        command.requestContextFingerprint(),
                        plan.planId(),
                        plan.planFingerprint(),
                        plan.capabilityClosureFingerprint(),
                        plan.executionControlFingerprint(),
                        plan.rootCapability(),
                        plan.fixtureBundleRef(),
                        List.of(
                                new MirrorRunEvidence
                                        .ExternalBinding(
                                        plan.rootCapability(),
                                        "loadCustomer",
                                        plan.externalBindings()
                                                .getFirst()
                                                .capabilityRef(),
                                        "/root/loadCustomer#RESOURCE",
                                        "/root")),
                        plan.scope(),
                        MirrorPersistenceTestFixtures.PURPOSE,
                        MirrorRunEvidence.Status.PASSED,
                        MirrorRunEvidence.EvidenceClass
                                .EXPLORATORY,
                        fingerprint('6'),
                        startedAt,
                        startedAt.plusSeconds(1),
                        List.of(),
                        List.of(),
                        List.of(),
                        new MirrorRunEvidence.IsolationFacts(
                                MirrorRunEvidence.IsolationFacts
                                        .EngineMode
                                        .INDEPENDENT_TEST_ENGINE,
                                List.of(),
                                List.of("InvocationRecorder"),
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                null,
                                List.of(
                                        "DEPLOYMENT_EGRESS_NOT_ATTESTED")),
                        List.of(
                                "DEPLOYMENT_EGRESS_NOT_ATTESTED"));
        return candidateIntegrity.seal(evidence)
                .bundle();
    }

    private static MirrorArtifactRef candidateRef(
            MirrorEvidenceBundle bundle) {
        return new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                bundle.evidence().runId(),
                1,
                bundle.bundleFingerprint());
    }

    private static MirrorArtifactRef ref(
            String kind,
            String id,
            char material) {
        return new MirrorArtifactRef(
                kind,
                id,
                1,
                fingerprint(material));
    }

    private static String fingerprint(
            char material) {
        return "sha256:"
                + String.valueOf(material).repeat(64);
    }

    private static final class SimulatedProcessCrash
            extends Error {
    }
}
