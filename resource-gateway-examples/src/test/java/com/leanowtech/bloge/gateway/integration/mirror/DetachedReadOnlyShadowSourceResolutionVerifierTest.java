package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetachedReadOnlyShadowSourceResolutionVerifierTest {
    private static final Instant NOW =
            ReadOnlyShadowSourceResolutionTestFixtures.NOW;

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW.plusSeconds(4), ZoneOffset.UTC));
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy =
            new PayloadFreeEqualityReadOnlyShadowPolicy(mapper);
    private final ReadOnlyShadowSourceBindingRepository bindingRepository =
            mock(ReadOnlyShadowSourceBindingRepository.class);
    private final MirrorEvidenceRepository evidenceRepository =
            mock(MirrorEvidenceRepository.class);
    private final ReadOnlyShadowSourceResolutionAttestationRepository
            attestationRepository =
            mock(ReadOnlyShadowSourceResolutionAttestationRepository.class);
    private final MirrorEvidenceIntegrityService evidenceIntegrity =
            new MirrorEvidenceIntegrityService(
                    mapper,
                    signer,
                    Clock.fixed(
                            NOW.plusSeconds(4),
                            ZoneOffset.UTC));
    private final ReadOnlyShadowSourceBindingIntegrity bindingIntegrity =
            new ReadOnlyShadowSourceBindingIntegrity(
                    mapper,
                    signer,
                    Clock.fixed(
                            NOW.plusSeconds(4),
                            ZoneOffset.UTC));
    private final ReadOnlyShadowSourceBindingService bindingService =
            new ReadOnlyShadowSourceBindingService(
                    bindingRepository,
                    evidenceRepository,
                    bindingIntegrity,
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            attestationIntegrity =
            new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                    mapper,
                    signer,
                    Clock.fixed(
                            NOW.plusSeconds(4),
                            ZoneOffset.UTC));

    private MirrorEvidenceBundle candidateBundle;
    private ReadOnlyShadowSourceBinding binding;
    private ReadOnlyShadowJobRequest request;
    private ReadOnlyShadowAccessAuthority.Admission admission;
    private ReadOnlyShadowAccessAuthority.Confirmation confirmation;
    private ReadOnlyShadowConnectorObservation baselineObservation;
    private ReadOnlyShadowConnectorObservation candidateObservation;
    private DetachedReadOnlyShadowSourceResolutionVerifier verifier;

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
        binding = bindingService.publish(
                unsignedBinding(plan, candidateBundle));
        when(bindingRepository.find(
                binding.scope(),
                binding.bindingId(),
                binding.revision()))
                .thenReturn(Optional.of(binding));
        when(attestationRepository.create(any()))
                .thenAnswer(invocation ->
                        invocation.getArgument(0));

        request = request(binding);
        admission =
                ReadOnlyShadowSourceResolutionTestFixtures
                        .admission(request);
        confirmation =
                ReadOnlyShadowSourceResolutionTestFixtures
                        .confirmation(admission);
        ReadOnlyShadowConnectorInvocation invocation =
                new ReadOnlyShadowConnectorInvocation(
                        "detached-execution",
                        request,
                        admission,
                        NOW,
                        request.deadlineAt());
        Clock connectorClock =
                Clock.fixed(
                        NOW.plusSeconds(2),
                        ZoneOffset.UTC);
        baselineObservation =
                new DetachedReadOnlyShadowBaselineConnector(
                        bindingService,
                        policy,
                        connectorClock)
                        .observe(invocation);
        candidateObservation =
                new DetachedReadOnlyShadowCandidateConnector(
                        bindingService,
                        evidenceRepository,
                        evidenceIntegrity,
                        policy,
                        connectorClock)
                        .observe(invocation);
        verifier =
                new DetachedReadOnlyShadowSourceResolutionVerifier(
                        bindingService,
                        evidenceRepository,
                        evidenceIntegrity,
                        policy,
                        attestationRepository,
                        attestationIntegrity,
                        mapper,
                        Clock.fixed(
                                NOW.plusSeconds(4),
                                ZoneOffset.UTC));
    }

    @Test
    void reResolvesBothSourcesAndPublishesDeterministicSignedAttestation() {
        var command = command(
                baselineObservation,
                candidateObservation);

        MirrorArtifactRef first = verifier.verify(command);
        MirrorArtifactRef second = verifier.verify(command);

        assertThat(verifier.ready()).isTrue();
        assertThat(first).isEqualTo(second);
        assertThat(first.kind())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestation
                                .ARTIFACT_KIND);
        ArgumentCaptor<ReadOnlyShadowSourceResolutionAttestation>
                captor = ArgumentCaptor.forClass(
                ReadOnlyShadowSourceResolutionAttestation.class);
        verify(attestationRepository, atLeastOnce())
                .create(captor.capture());
        ReadOnlyShadowSourceResolutionAttestation published =
                captor.getValue();
        assertThat(published.attestationSeal().signed())
                .isTrue();
        assertThat(published.sourceBindingRef())
                .isEqualTo(binding.artifactRef());
        assertThat(published.baseline().sourceCompletedAt())
                .isEqualTo(binding.baseline().observedAt());
        assertThat(published.baseline().resolvedAt())
                .isEqualTo(
                        baselineObservation.source()
                                .completedAt());
        assertThat(published.candidate().sourceCompletedAt())
                .isEqualTo(
                        candidateBundle.evidence()
                                .completedAt());
        assertThat(published.candidate().resolvedAt())
                .isEqualTo(
                        candidateObservation.source()
                                .completedAt());
        assertThat(attestationIntegrity.verify(published))
                .isEqualTo(published);
    }

    @Test
    void rejectsConnectorFactDriftBeforeSigning() {
        LinkedHashMap<DomainFidelityProfile.Dimension, String> drifted =
                new LinkedHashMap<>(
                        candidateObservation
                                .normalizedFactFingerprints());
        drifted.put(
                DomainFidelityProfile.Dimension.BEHAVIOR,
                fingerprint('f'));
        ReadOnlyShadowConnectorObservation altered =
                new ReadOnlyShadowConnectorObservation(
                        candidateObservation.source(),
                        candidateObservation
                                .comparisonPolicyRef(),
                        drifted,
                        false,
                        0);

        assertThatThrownBy(() ->
                verifier.verify(
                        command(
                                baselineObservation,
                                altered)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "differs from independently resolved source");
    }

    @Test
    void rejectsRepositoryCandidateTamperDuringIndependentReResolution()
            throws Exception {
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

        assertThatThrownBy(() ->
                verifier.verify(
                        command(
                                baselineObservation,
                                candidateObservation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
    }

    private ReadOnlyShadowSourceResolutionVerifier.Verification
    command(
            ReadOnlyShadowConnectorObservation baseline,
            ReadOnlyShadowConnectorObservation candidate) {
        return new ReadOnlyShadowSourceResolutionVerifier
                .Verification(
                "detached-execution",
                request,
                admission,
                confirmation,
                baseline,
                candidate);
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
                ReadOnlyShadowJobTestFixtures
                        .request("coordinates", 1)
                        .accessGrant(),
                NOW.plus(Duration.ofMinutes(30)));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
