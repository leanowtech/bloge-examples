package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DetachedReadOnlyShadowConnectorsTest {
    private static final Instant NOW =
            ReadOnlyShadowJobTestFixtures.NOW;
    private static final Clock CONNECTOR_CLOCK =
            Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC);

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy =
            new PayloadFreeEqualityReadOnlyShadowPolicy(mapper);
    private final ReadOnlyShadowSourceBindingRepository bindingRepository =
            mock(ReadOnlyShadowSourceBindingRepository.class);
    private final MirrorEvidenceRepository evidenceRepository =
            mock(MirrorEvidenceRepository.class);
    private final ReadOnlyShadowSourceBindingIntegrity bindingIntegrity =
            new ReadOnlyShadowSourceBindingIntegrity(
                    mapper,
                    signer,
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private final MirrorEvidenceIntegrityService evidenceIntegrity =
            new MirrorEvidenceIntegrityService(
                    mapper,
                    signer,
                    Clock.fixed(
                            NOW.plusSeconds(3),
                            ZoneOffset.UTC));
    private final ReadOnlyShadowSourceBindingService bindingService =
            new ReadOnlyShadowSourceBindingService(
                    bindingRepository,
                    evidenceRepository,
                    bindingIntegrity,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private MirrorEvidenceBundle candidateBundle;
    private ReadOnlyShadowSourceBinding binding;
    private ReadOnlyShadowJobRequest request;

    @BeforeEach
    void setUp() {
        MirrorPlan plan =
                MirrorPersistenceTestFixtures.plan(
                        mapper,
                        MirrorPersistenceTestFixtures
                                .scope("support"),
                        "refund-shadow-plan",
                        '4');
        candidateBundle =
                MirrorPersistenceTestFixtures.evidence(
                        mapper,
                        signer,
                        plan,
                        "candidate-run",
                        'a',
                        "candidate-request",
                        fingerprint('9'));
        when(evidenceRepository.find(
                plan.scope(),
                candidateBundle.evidence().runId()))
                .thenReturn(Optional.of(candidateBundle));
        when(bindingRepository.create(any()))
                .thenAnswer(invocation ->
                        invocation.getArgument(0));
        ReadOnlyShadowSourceBinding unsigned =
                unsignedBinding(plan, candidateBundle);
        binding = bindingService.publish(unsigned);
        when(bindingRepository.find(
                binding.scope(),
                binding.bindingId(),
                binding.revision()))
                .thenReturn(Optional.of(binding));
        request = request(binding);
    }

    @Test
    void resolvesOneBindingTwiceAndProducesPolicyComparableObservations() {
        ReadOnlyShadowConnectorInvocation invocation =
                invocation(request);
        DetachedReadOnlyShadowBaselineConnector baseline =
                new DetachedReadOnlyShadowBaselineConnector(
                        bindingService,
                        policy,
                        CONNECTOR_CLOCK);
        DetachedReadOnlyShadowCandidateConnector candidate =
                new DetachedReadOnlyShadowCandidateConnector(
                        bindingService,
                        evidenceRepository,
                        evidenceIntegrity,
                        policy,
                        CONNECTOR_CLOCK);

        ReadOnlyShadowConnectorObservation baselineResult =
                baseline.observe(invocation);
        ReadOnlyShadowConnectorObservation candidateResult =
                candidate.observe(invocation);
        var comparisons =
                policy.compare(
                        policy.reference(),
                        baselineResult,
                        candidateResult);

        assertThat(baseline.ready()).isTrue();
        assertThat(candidate.ready()).isTrue();
        assertThat(baselineResult.source().artifactRef())
                .isEqualTo(binding.baselineArtifactRef());
        assertThat(candidateResult.source().artifactRef())
                .isEqualTo(binding.candidateEvidenceRef());
        assertThat(baselineResult.source().completedAt())
                .isEqualTo(NOW.plusSeconds(2))
                .isAfter(binding.baseline().observedAt());
        assertThat(candidateResult.source().completedAt())
                .isEqualTo(NOW.plusSeconds(2))
                .isAfter(
                        candidateBundle.evidence().completedAt());
        assertThat(comparisons)
                .allMatch(comparison ->
                        comparison.outcome()
                        == ReadOnlyShadowComparison
                        .DiffOutcome.MATCH);
        assertThat(baselineResult.writeCredentialExposed())
                .isFalse();
        assertThat(candidateResult.writeAttemptCount())
                .isZero();
    }

    @Test
    void rejectsLegacyOnlineRequestAndUnknownPolicyBeforeSourceLookup() {
        ReadOnlyShadowSourceBindingService isolated =
                mock(ReadOnlyShadowSourceBindingService.class);
        DetachedReadOnlyShadowBaselineConnector connector =
                new DetachedReadOnlyShadowBaselineConnector(
                        isolated,
                        policy,
                        CONNECTOR_CLOCK);
        ReadOnlyShadowJobRequest online =
                ReadOnlyShadowJobTestFixtures.request(
                        "online-request",
                        1);

        assertThatThrownBy(() ->
                connector.observe(invocation(online)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "detached Shadow connector invocation");
        verifyNoInteractions(isolated);

        ReadOnlyShadowJobRequest drifted =
                copyWithPolicy(
                        request,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_COMPARISON_POLICY",
                                "unknown",
                                'f'));
        assertThatThrownBy(() ->
                connector.observe(invocation(drifted)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        verifyNoInteractions(isolated);
    }

    @Test
    void independentlyRejectsCandidateEvidenceTamper() throws Exception {
        ObjectNode tamperedJson =
                mapper.valueToTree(candidateBundle);
        ((ObjectNode) tamperedJson.path("evidence"))
                .put(
                        "semanticResultFingerprint",
                        fingerprint('f'));
        MirrorEvidenceBundle tampered =
                mapper.treeToValue(
                        tamperedJson,
                        MirrorEvidenceBundle.class);
        when(evidenceRepository.find(
                binding.scope(),
                binding.candidateEvidenceRef().id()))
                .thenReturn(Optional.of(tampered));
        DetachedReadOnlyShadowCandidateConnector connector =
                new DetachedReadOnlyShadowCandidateConnector(
                        bindingService,
                        evidenceRepository,
                        evidenceIntegrity,
                        policy,
                        CONNECTOR_CLOCK);

        assertThatThrownBy(() ->
                connector.observe(invocation(request)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
    }

    @Test
    void rejectsBindingCoordinateDriftAndExpiredResolution() {
        ReadOnlyShadowJobRequest wrongCase =
                copyWithCase(
                        request,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SCENARIO_CASE",
                                "different",
                                'd'));
        DetachedReadOnlyShadowBaselineConnector baseline =
                new DetachedReadOnlyShadowBaselineConnector(
                        bindingService,
                        policy,
                        CONNECTOR_CLOCK);

        assertThatThrownBy(() ->
                baseline.observe(invocation(wrongCase)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "differs from the durable request");

        DetachedReadOnlyShadowBaselineConnector expired =
                new DetachedReadOnlyShadowBaselineConnector(
                        bindingService,
                        policy,
                        Clock.fixed(
                                binding.expiresAt(),
                                ZoneOffset.UTC));
        ReadOnlyShadowJobRequest longDeadline =
                copyWithDeadline(
                        request,
                        binding.expiresAt().plusSeconds(10));
        assertThatThrownBy(() ->
                expired.observe(invocation(longDeadline)))
                .isInstanceOf(
                        ReadOnlyShadowSourceBindingService
                                .Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowSourceBindingService
                                .Reason.WINDOW_REJECTED);
    }

    private ReadOnlyShadowSourceBinding unsignedBinding(
            MirrorPlan plan,
            MirrorEvidenceBundle bundle) {
        Map<DomainFidelityProfile.Dimension, String> facts =
                policy.normalize(bundle.evidence());
        return new ReadOnlyShadowSourceBinding(
                ReadOnlyShadowSourceBinding.SCHEMA_VERSION,
                "",
                "detached-refund-pair",
                1,
                plan.scope(),
                ReadOnlyShadowJobTestFixtures.ref(
                        "SCENARIO_CASE",
                        "refund-golden",
                        '2'),
                plan.rootCapability(),
                new MirrorArtifactRef(
                        "MIRROR_PLAN",
                        plan.planId(),
                        1,
                        plan.planFingerprint()),
                ReadOnlyShadowJobTestFixtures.ref(
                        "SHADOW_BASELINE_BINDING",
                        "refund-production-read",
                        '5'),
                policy.reference(),
                bundle.evidence()
                        .requestContextFingerprint(),
                "",
                new ReadOnlyShadowSourceBinding
                        .BaselineObservation(
                        bundle.evidence()
                                .semanticResultFingerprint(),
                        facts,
                        NOW.minusSeconds(20),
                        MirrorRunEvidence
                                .EvidenceClass.CERTIFIABLE,
                        true,
                        false,
                        0),
                new MirrorArtifactRef(
                        "MIRROR_EVIDENCE_BUNDLE",
                        bundle.evidence().runId(),
                        1,
                        bundle.bundleFingerprint()),
                NOW,
                NOW.plus(Duration.ofHours(1)),
                NOW.minusSeconds(10),
                VisualRunEvidenceSeal.unsigned());
    }

    private static ReadOnlyShadowJobRequest request(
            ReadOnlyShadowSourceBinding binding) {
        return new ReadOnlyShadowJobRequest(
                ReadOnlyShadowJobRequest.V2_SCHEMA_VERSION,
                "detached-job",
                binding.scope(),
                ReadOnlyShadowJobTestFixtures.ref(
                        DomainFidelityInventory.ARTIFACT_KIND,
                        "refund-fidelity",
                        '1'),
                "refund-golden",
                binding.scenarioCaseRef(),
                binding.targetCapabilityRef(),
                binding.candidatePlanRef(),
                binding.baselineBindingRef(),
                binding.comparisonPolicyRef(),
                ReadOnlyShadowJobRequest.SourceMode
                        .DETACHED_EVIDENCE,
                binding.artifactRef(),
                accessGrant(),
                NOW.plus(Duration.ofMinutes(30)));
    }

    private static ReadOnlyShadowJobRequest.AccessGrant
    accessGrant() {
        return ReadOnlyShadowJobTestFixtures
                .request("coordinates", 1)
                .accessGrant();
    }

    private static ReadOnlyShadowConnectorInvocation invocation(
            ReadOnlyShadowJobRequest request) {
        ReadOnlyShadowAccessAuthority.Admission admission =
                mock(ReadOnlyShadowAccessAuthority
                        .Admission.class);
        when(admission.scope())
                .thenReturn(request.scope());
        when(admission.accessProof())
                .thenReturn(
                        request.accessGrant()
                                .zeroWriteProof());
        when(admission.admittedAt())
                .thenReturn(NOW);
        when(admission.validUntil())
                .thenReturn(NOW.plusSeconds(60));
        return new ReadOnlyShadowConnectorInvocation(
                "detached-execution",
                request,
                admission,
                NOW,
                request.deadlineAt());
    }

    private static ReadOnlyShadowJobRequest copyWithPolicy(
            ReadOnlyShadowJobRequest source,
            MirrorArtifactRef policy) {
        return copy(
                source,
                source.scenarioCaseRef(),
                policy,
                source.deadlineAt());
    }

    private static ReadOnlyShadowJobRequest copyWithCase(
            ReadOnlyShadowJobRequest source,
            MirrorArtifactRef scenarioCase) {
        return copy(
                source,
                scenarioCase,
                source.comparisonPolicyRef(),
                source.deadlineAt());
    }

    private static ReadOnlyShadowJobRequest copyWithDeadline(
            ReadOnlyShadowJobRequest source,
            Instant deadline) {
        return copy(
                source,
                source.scenarioCaseRef(),
                source.comparisonPolicyRef(),
                deadline);
    }

    private static ReadOnlyShadowJobRequest copy(
            ReadOnlyShadowJobRequest source,
            MirrorArtifactRef scenarioCase,
            MirrorArtifactRef policy,
            Instant deadline) {
        return new ReadOnlyShadowJobRequest(
                source.schemaVersion(),
                source.requestId(),
                source.scope(),
                source.inventoryRef(),
                source.unitId(),
                scenarioCase,
                source.targetCapabilityRef(),
                source.candidatePlanRef(),
                source.baselineBindingRef(),
                policy,
                source.sourceMode(),
                source.sourceBindingRef(),
                source.accessGrant(),
                deadline);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
