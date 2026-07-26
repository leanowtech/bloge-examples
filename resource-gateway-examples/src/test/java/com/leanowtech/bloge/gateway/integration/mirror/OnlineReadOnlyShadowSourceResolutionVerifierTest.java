package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineReadOnlyShadowSourceResolutionVerifierTest {
    private static final Instant NOW =
            OnlineReadOnlyShadowBaselineTestFixtures.NOW;
    private static final Clock EVIDENCE_CLOCK =
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
    private final OnlineReadOnlyShadowBaselineAuthority
            baselineAuthority =
            mock(OnlineReadOnlyShadowBaselineAuthority.class);
    private final OnlineReadOnlyShadowCandidateAuthority
            candidateAuthority =
            mock(OnlineReadOnlyShadowCandidateAuthority.class);
    private final ReadOnlyShadowSourceResolutionAttestationRepository
            attestations =
            mock(ReadOnlyShadowSourceResolutionAttestationRepository
                    .class);
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            baselineIntegrity =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .integrity(mapper);
    private final VisualEvidenceSigner candidateSigner =
            InMemoryVisualEvidenceSigner.usingClock(
                    EVIDENCE_CLOCK);
    private final MirrorEvidenceIntegrityService evidenceIntegrity =
            new MirrorEvidenceIntegrityService(
                    mapper,
                    candidateSigner,
                    EVIDENCE_CLOCK);
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            attestationIntegrity =
            new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                    mapper,
                    InMemoryVisualEvidenceSigner.usingClock(
                            RESOLUTION_CLOCK),
                    RESOLUTION_CLOCK);

    private MirrorPlan plan;
    private ReadOnlyShadowJobRequest request;
    private ReadOnlyShadowAccessAuthority.Admission admission;
    private ReadOnlyShadowAccessAuthority.Confirmation confirmation;
    private OnlineReadOnlyShadowBaselineCommand baselineCommand;
    private OnlineReadOnlyShadowBaselineObservation baseline;
    private OnlineReadOnlyShadowCandidateCommand candidateCommand;
    private MirrorEvidenceBundle candidate;
    private ReadOnlyShadowConnectorObservation baselineProjection;
    private ReadOnlyShadowConnectorObservation candidateProjection;

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
        confirmation =
                ReadOnlyShadowSourceResolutionTestFixtures
                        .confirmation(admission);
        baselineCommand = baselineCommand();
        baseline = baselineIntegrity.sign(
                OnlineReadOnlyShadowBaselineTestFixtures
                        .unsigned(
                                mapper,
                                baselineCommand));
        candidateCommand = candidateCommand();
        candidate = candidateBundle();
        baselineProjection =
                baselineProjection(baseline);
        candidateProjection =
                candidateProjection(candidate);

        when(baselineAuthority.ready())
                .thenReturn(true);
        when(baselineAuthority.resolve(
                request.scope(),
                baseline.artifactRef()))
                .thenReturn(baseline);
        when(candidateAuthority.ready())
                .thenReturn(true);
        when(candidateAuthority.resolve(
                request.scope(),
                candidateProjection.source()
                        .artifactRef()))
                .thenReturn(candidate);
        when(attestations.create(any()))
                .thenAnswer(answer ->
                        answer.getArgument(0));
    }

    @Test
    void independentlyResolvesBothOnlineSourcesAndSignsV2Proof() {
        OnlineReadOnlyShadowSourceResolutionVerifier verifier =
                verifier();

        MirrorArtifactRef result =
                verifier.verify(verification(
                        baselineProjection,
                        candidateProjection));

        ArgumentCaptor<ReadOnlyShadowSourceResolutionAttestation>
                captured =
                ArgumentCaptor.forClass(
                        ReadOnlyShadowSourceResolutionAttestation
                                .class);
        org.mockito.Mockito.verify(attestations)
                .create(captured.capture());
        ReadOnlyShadowSourceResolutionAttestation proof =
                captured.getValue();
        assertThat(result)
                .isEqualTo(proof.artifactRef());
        assertThat(proof.schemaVersion())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestation
                                .ONLINE_SCHEMA_VERSION);
        assertThat(proof.sourceMode())
                .isEqualTo(
                        ReadOnlyShadowJobRequest.SourceMode
                                .ONLINE_EXECUTION);
        assertThat(proof.sourceBindingRef()).isNull();
        assertThat(proof.baselineCommandFingerprint())
                .isEqualTo(
                        baselineCommand.commandFingerprint(
                                mapper));
        assertThat(proof.candidateCommandFingerprint())
                .isEqualTo(
                        candidateCommand.commandFingerprint(
                                mapper));
        assertThat(proof.requestContextFingerprint())
                .isEqualTo(
                        baseline.requestContextFingerprint());
        assertThat(proof.baseline().sourceCompletedAt())
                .isEqualTo(baseline.completedAt());
        assertThat(proof.candidate().sourceCompletedAt())
                .isEqualTo(candidate.evidence().completedAt());
        assertThat(proof.baseline().resolvedAt())
                .isEqualTo(RESOLUTION_CLOCK.instant());
        assertThat(proof.candidate().resolvedAt())
                .isEqualTo(RESOLUTION_CLOCK.instant());
        assertThat(attestationIntegrity.verify(proof))
                .isEqualTo(proof);
    }

    @Test
    void transientExactReadOutageRemainsRetryable() {
        when(candidateAuthority.resolve(
                request.scope(),
                candidateProjection.source()
                        .artifactRef()))
                .thenThrow(
                        new OnlineReadOnlyShadowCandidateAuthority
                                .AuthorityException(
                                OnlineReadOnlyShadowCandidateAuthority
                                        .Failure.UNAVAILABLE,
                                "ONLINE_CANDIDATE_STORE_UNAVAILABLE"));

        assertThatThrownBy(() ->
                verifier().verify(verification(
                        baselineProjection,
                        candidateProjection)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void validlySignedCandidateForAnotherCommandIsRejected() {
        OnlineReadOnlyShadowCandidateCommand altered =
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
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .ref(
                                        "PAYLOAD_VAULT_RECEIPT",
                                        "different-receipt",
                                        'f'),
                        candidateCommand
                                .requestContextFingerprint(),
                        candidateCommand.accessGrant(),
                        candidateCommand
                                .admissionFingerprint(),
                        candidateCommand.admittedAt(),
                        candidateCommand.deadlineAt());
        candidate = candidateBundle(altered);
        when(candidateAuthority.resolve(
                request.scope(),
                candidateProjection.source()
                        .artifactRef()))
                .thenReturn(candidate);

        assertThatThrownBy(() ->
                verifier().verify(verification(
                        baselineProjection,
                        candidateProjection)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
    }

    @Test
    void connectorProjectionCannotOverrideResolvedBaselineFacts() {
        ReadOnlyShadowConnectorObservation altered =
                new ReadOnlyShadowConnectorObservation(
                        new ReadOnlyShadowComparison
                                .SourceObservation(
                                baselineProjection.source()
                                        .role(),
                                baselineProjection.source()
                                        .artifactRef(),
                                baselineProjection.source()
                                        .scope(),
                                baselineProjection.source()
                                        .targetCapabilityRef(),
                                baselineProjection.source()
                                        .requestContextFingerprint(),
                                fingerprint('7'),
                                baselineProjection.source()
                                        .completedAt(),
                                baselineProjection.source()
                                        .evidenceClass(),
                                baselineProjection.source()
                                        .evidenceComplete()),
                        baselineProjection
                                .comparisonPolicyRef(),
                        baselineProjection
                                .normalizedFactFingerprints(),
                        false,
                        0);

        assertThatThrownBy(() ->
                verifier().verify(verification(
                        altered,
                        candidateProjection)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
    }

    @Test
    void readinessFailsClosedOnDependencyException() {
        when(baselineAuthority.ready())
                .thenThrow(new IllegalStateException("offline"));

        assertThat(verifier().ready()).isFalse();
    }

    private OnlineReadOnlyShadowSourceResolutionVerifier
    verifier() {
        return new OnlineReadOnlyShadowSourceResolutionVerifier(
                baselineAuthority,
                baselineIntegrity,
                candidateAuthority,
                evidenceIntegrity,
                policy,
                attestations,
                attestationIntegrity,
                mapper,
                RESOLUTION_CLOCK);
    }

    private ReadOnlyShadowSourceResolutionVerifier.Verification
    verification(
            ReadOnlyShadowConnectorObservation baselineValue,
            ReadOnlyShadowConnectorObservation candidateValue) {
        return new ReadOnlyShadowSourceResolutionVerifier
                .Verification(
                "execution-online-pair",
                request,
                admission,
                confirmation,
                baselineValue,
                candidateValue);
    }

    private ReadOnlyShadowJobRequest request(
            MirrorPlan exactPlan) {
        ReadOnlyShadowJobRequest source =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .request("online-pair");
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

    private OnlineReadOnlyShadowBaselineCommand
    baselineCommand() {
        return new OnlineReadOnlyShadowBaselineCommand(
                OnlineReadOnlyShadowBaselineCommand
                        .SCHEMA_VERSION,
                "execution-online-pair",
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
    candidateCommand() {
        return new OnlineReadOnlyShadowCandidateCommand(
                OnlineReadOnlyShadowCandidateCommand
                        .SCHEMA_VERSION,
                "execution-online-pair",
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

    private MirrorEvidenceBundle candidateBundle() {
        return candidateBundle(candidateCommand);
    }

    private MirrorEvidenceBundle candidateBundle(
            OnlineReadOnlyShadowCandidateCommand command) {
        Instant startedAt = NOW.plusSeconds(2);
        MirrorRunEvidence evidence =
                new MirrorRunEvidence(
                        MirrorRunEvidence.SCHEMA_VERSION_V1,
                        "candidate-online-pair",
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
                        fingerprint('a'),
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
        return evidenceIntegrity.seal(evidence).bundle();
    }

    private static ReadOnlyShadowConnectorObservation
    baselineProjection(
            OnlineReadOnlyShadowBaselineObservation value) {
        return new ReadOnlyShadowConnectorObservation(
                new ReadOnlyShadowComparison.SourceObservation(
                        ReadOnlyShadowComparison.SourceRole
                                .BASELINE,
                        value.artifactRef(),
                        value.scope(),
                        value.targetCapabilityRef(),
                        value.requestContextFingerprint(),
                        value.semanticResultFingerprint(),
                        value.completedAt(),
                        value.evidenceClass(),
                        value.evidenceComplete()),
                value.comparisonPolicyRef(),
                value.normalizedFactFingerprints(),
                false,
                0);
    }

    private ReadOnlyShadowConnectorObservation
    candidateProjection(
            MirrorEvidenceBundle value) {
        MirrorRunEvidence evidence =
                value.evidence();
        return new ReadOnlyShadowConnectorObservation(
                new ReadOnlyShadowComparison.SourceObservation(
                        ReadOnlyShadowComparison.SourceRole
                                .CANDIDATE,
                        new MirrorArtifactRef(
                                "MIRROR_EVIDENCE_BUNDLE",
                                evidence.runId(),
                                1,
                                value.bundleFingerprint()),
                        evidence.scope(),
                        evidence.rootCapability(),
                        evidence.requestContextFingerprint(),
                        evidence.semanticResultFingerprint(),
                        evidence.completedAt(),
                        evidence.evidenceClass(),
                        true),
                policy.reference(),
                policy.normalize(evidence),
                false,
                0);
    }

    private static String fingerprint(
            char material) {
        return "sha256:"
                + String.valueOf(material).repeat(64);
    }
}
