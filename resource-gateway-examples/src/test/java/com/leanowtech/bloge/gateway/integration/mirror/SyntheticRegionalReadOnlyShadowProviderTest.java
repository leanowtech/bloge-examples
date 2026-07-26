package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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
        provider =
                new SyntheticRegionalReadOnlyShadowProvider(
                        List.of(fixture()),
                        this::candidateBundle,
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
                        provider.candidateAuthority(),
                        candidateIntegrity,
                        policy,
                        mapper,
                        RESOLUTION_CLOCK);
        OnlineReadOnlyShadowSourceResolutionVerifier resolver =
                new OnlineReadOnlyShadowSourceResolutionVerifier(
                        provider.baselineAuthority(),
                        baselineIntegrity,
                        provider.candidateAuthority(),
                        candidateIntegrity,
                        policy,
                        attestations,
                        resolutionIntegrity,
                        mapper,
                        RESOLUTION_CLOCK);
        ReadOnlyShadowAccessAuthority authority =
                authority();
        ReadOnlyShadowExecutionGuard guard =
                guard();
        GovernedReadOnlyShadowDataPlane dataPlane =
                new GovernedReadOnlyShadowDataPlane(
                        authority,
                        guard,
                        baseline,
                        candidate,
                        resolver,
                        policy,
                        Clock.fixed(
                                NOW,
                                ZoneOffset.UTC));

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
}
