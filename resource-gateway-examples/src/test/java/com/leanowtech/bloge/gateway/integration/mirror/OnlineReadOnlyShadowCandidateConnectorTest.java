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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineReadOnlyShadowCandidateConnectorTest {
    private static final Instant NOW =
            OnlineReadOnlyShadowBaselineTestFixtures.NOW;
    private static final Clock CONNECTOR_CLOCK =
            Clock.fixed(
                    NOW.plusSeconds(8),
                    ZoneOffset.UTC);

    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures.mapper();
    private final VisualEvidenceSigner evidenceSigner =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(
                            NOW.plusSeconds(7),
                            ZoneOffset.UTC));
    private final MirrorEvidenceIntegrityService evidenceIntegrity =
            new MirrorEvidenceIntegrityService(
                    mapper,
                    evidenceSigner,
                    CONNECTOR_CLOCK);
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy =
            new PayloadFreeEqualityReadOnlyShadowPolicy(
                    mapper);
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            baselineIntegrity =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .integrity(mapper);
    private final OnlineReadOnlyShadowBaselineAuthority
            baselineAuthority =
            mock(OnlineReadOnlyShadowBaselineAuthority.class);
    private final OnlineReadOnlyShadowCandidateAuthority
            candidateAuthority =
            mock(OnlineReadOnlyShadowCandidateAuthority.class);

    private MirrorPlan plan;
    private ReadOnlyShadowConnectorInvocation invocation;
    private OnlineReadOnlyShadowBaselineObservation
            baselineObservation;
    private ReadOnlyShadowConnectorObservation
            baselineResult;

    @BeforeEach
    void setUp() {
        plan = MirrorPersistenceTestFixtures.plan(
                mapper,
                MirrorPersistenceTestFixtures
                        .scope("support"),
                "refund-shadow-plan",
                '4');
        ReadOnlyShadowJobRequest request =
                request(plan);
        ReadOnlyShadowAccessAuthority.Admission admission =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .admission(request);
        invocation =
                new ReadOnlyShadowConnectorInvocation(
                        "execution-online-candidate",
                        request,
                        admission,
                        NOW.plusSeconds(1),
                        request.deadlineAt());
        OnlineReadOnlyShadowBaselineCommand baselineCommand =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .command(invocation);
        baselineObservation =
                baselineIntegrity.sign(
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .unsigned(
                                        mapper,
                                        baselineCommand));
        baselineResult = baselineResult(
                baselineObservation);
        when(baselineAuthority.ready())
                .thenReturn(true);
        when(baselineAuthority.resolve(
                request.scope(),
                baselineObservation.artifactRef()))
                .thenReturn(baselineObservation);
        when(candidateAuthority.ready())
                .thenReturn(true);
    }

    @Test
    void executesSealedCandidateAgainstExactBaselineVaultReceipt() {
        when(candidateAuthority.execute(any()))
                .thenAnswer(answer ->
                        candidateBundle(
                                answer.getArgument(0),
                                "candidate-run",
                                'a'));
        OnlineReadOnlyShadowCandidateConnector connector =
                connector();

        ReadOnlyShadowConnectorObservation observed =
                connector.observePaired(
                        invocation,
                        baselineResult);

        ArgumentCaptor<OnlineReadOnlyShadowCandidateCommand>
                command =
                ArgumentCaptor.forClass(
                        OnlineReadOnlyShadowCandidateCommand
                                .class);
        org.mockito.Mockito.verify(candidateAuthority)
                .execute(command.capture());
        OnlineReadOnlyShadowCandidateCommand exact =
                command.getValue();
        assertThat(exact.baselineObservationRef())
                .isEqualTo(
                        baselineObservation.artifactRef());
        assertThat(exact.payloadVaultReceiptRef())
                .isEqualTo(
                        baselineObservation
                                .payloadVaultReceiptRef());
        assertThat(exact.requestContextFingerprint())
                .isEqualTo(
                        baselineObservation
                                .requestContextFingerprint());
        assertThat(observed.source().role())
                .isEqualTo(
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE);
        assertThat(observed.source()
                .requestContextFingerprint())
                .isEqualTo(
                        baselineResult.source()
                                .requestContextFingerprint());
        assertThat(observed.source().artifactRef().kind())
                .isEqualTo("MIRROR_EVIDENCE_BUNDLE");
        assertThat(observed.writeCredentialExposed())
                .isFalse();
        assertThat(observed.writeAttemptCount()).isZero();
    }

    @Test
    void rejectsSignedCandidateThatDoesNotBindTheExactCommand() {
        when(candidateAuthority.execute(any()))
                .thenAnswer(answer -> {
                    OnlineReadOnlyShadowCandidateCommand command =
                            answer.getArgument(0);
                    return candidateBundle(
                            command,
                            "candidate-run",
                            'a',
                            "sha256:"
                                    + "f".repeat(64));
                });

        assertThatThrownBy(() ->
                connector().observePaired(
                        invocation,
                        baselineResult))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsBaselineConnectorProjectionThatDiffersFromResolvedEvidence() {
        ReadOnlyShadowConnectorObservation altered =
                new ReadOnlyShadowConnectorObservation(
                        new ReadOnlyShadowComparison
                                .SourceObservation(
                                baselineResult.source().role(),
                                baselineResult.source()
                                        .artifactRef(),
                                baselineResult.source().scope(),
                                baselineResult.source()
                                        .targetCapabilityRef(),
                                fingerprint('f'),
                                baselineResult.source()
                                        .semanticResultFingerprint(),
                                baselineResult.source()
                                        .completedAt(),
                                baselineResult.source()
                                        .evidenceClass(),
                                baselineResult.source()
                                        .evidenceComplete()),
                        baselineResult.comparisonPolicyRef(),
                        baselineResult
                                .normalizedFactFingerprints(),
                        false,
                        0);

        assertThatThrownBy(() ->
                connector().observePaired(
                        invocation,
                        altered))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
    }

    @Test
    void preservesCandidateAvailabilityAsRetryableRuntimeFailure() {
        when(candidateAuthority.execute(any()))
                .thenThrow(
                        new OnlineReadOnlyShadowCandidateAuthority
                                .AuthorityException(
                                OnlineReadOnlyShadowCandidateAuthority
                                        .Failure.UNAVAILABLE,
                                "ONLINE_CANDIDATE_OVERLOADED"));

        assertThatThrownBy(() ->
                connector().observePaired(
                        invocation,
                        baselineResult))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .CANDIDATE_RUNTIME_UNAVAILABLE);
    }

    @Test
    void readinessFailsClosedAndUnpairedCallsCannotExecute() {
        when(candidateAuthority.ready())
                .thenThrow(new IllegalStateException("offline"));
        OnlineReadOnlyShadowCandidateConnector connector =
                connector();

        assertThat(connector.ready()).isFalse();
        assertThatThrownBy(() ->
                connector.observe(invocation))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
    }

    @Test
    void unavailableAuthorityIsClosedAndPayloadFree() {
        OnlineReadOnlyShadowCandidateAuthority unavailable =
                OnlineReadOnlyShadowCandidateAuthority
                        .unavailable();

        assertThat(unavailable.ready()).isFalse();
        assertThatThrownBy(() ->
                unavailable.execute(
                        candidateCommand()))
                .isInstanceOf(
                        OnlineReadOnlyShadowCandidateAuthority
                                .AuthorityException.class)
                .hasMessage(
                        "ONLINE_CANDIDATE_AUTHORITY_UNAVAILABLE")
                .extracting("failure")
                .isEqualTo(
                        OnlineReadOnlyShadowCandidateAuthority
                                .Failure.UNAVAILABLE);
    }

    private OnlineReadOnlyShadowCandidateConnector connector() {
        return new OnlineReadOnlyShadowCandidateConnector(
                baselineAuthority,
                baselineIntegrity,
                candidateAuthority,
                evidenceIntegrity,
                policy,
                mapper,
                CONNECTOR_CLOCK);
    }

    private OnlineReadOnlyShadowCandidateCommand
    candidateCommand() {
        return new OnlineReadOnlyShadowCandidateCommand(
                OnlineReadOnlyShadowCandidateCommand
                        .SCHEMA_VERSION,
                invocation.executionId(),
                invocation.request().requestId(),
                invocation.request().scope(),
                invocation.request().inventoryRef(),
                invocation.request().unitId(),
                invocation.request().scenarioCaseRef(),
                invocation.request().targetCapabilityRef(),
                invocation.request().candidatePlanRef(),
                invocation.request().comparisonPolicyRef(),
                baselineObservation.artifactRef(),
                baselineObservation.payloadVaultReceiptRef(),
                baselineObservation
                        .requestContextFingerprint(),
                invocation.request().accessGrant(),
                invocation.accessAdmission()
                        .admissionFingerprint(),
                invocation.accessAdmission().admittedAt(),
                invocation.deadlineAt());
    }

    private ReadOnlyShadowJobRequest request(
            MirrorPlan exactPlan) {
        ReadOnlyShadowJobRequest source =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .request("online-candidate");
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

    private MirrorEvidenceBundle candidateBundle(
            OnlineReadOnlyShadowCandidateCommand command,
            String runId,
            char semanticMaterial) {
        return candidateBundle(
                command,
                runId,
                semanticMaterial,
                command.commandFingerprint(mapper));
    }

    private MirrorEvidenceBundle candidateBundle(
            OnlineReadOnlyShadowCandidateCommand command,
            String runId,
            char semanticMaterial,
            String evidenceRequestId) {
        Instant startedAt = NOW.plusSeconds(5);
        MirrorRunEvidence evidence =
                new MirrorRunEvidence(
                        MirrorRunEvidence.SCHEMA_VERSION_V1,
                        runId,
                        evidenceRequestId,
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
                        fingerprint(semanticMaterial),
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
    baselineResult(
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
                value.writeCredentialExposed(),
                value.writeAttemptCount());
    }

    private static String fingerprint(
            char material) {
        return "sha256:"
                + String.valueOf(material).repeat(64);
    }
}
