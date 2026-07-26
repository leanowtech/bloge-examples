package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadOnlyShadowSourceBindingServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-26T12:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private final ReadOnlyShadowSourceBindingIntegrity integrity =
            new ReadOnlyShadowSourceBindingIntegrity(
                    mapper, signer,
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private final ReadOnlyShadowSourceBindingRepository bindings =
            mock(ReadOnlyShadowSourceBindingRepository.class);
    private final MirrorEvidenceRepository evidence =
            mock(MirrorEvidenceRepository.class);
    private final ReadOnlyShadowSourceBindingService service =
            new ReadOnlyShadowSourceBindingService(
                    bindings, evidence, integrity,
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private MirrorEvidenceBundle candidate;
    private ReadOnlyShadowSourceBinding unsigned;

    @BeforeEach
    void setUp() {
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                mapper,
                MirrorPersistenceTestFixtures.scope("support"),
                "detached-candidate-plan",
                '4');
        candidate = MirrorPersistenceTestFixtures.evidence(
                mapper,
                signer,
                plan,
                "candidate-run",
                'b',
                "candidate-request",
                MirrorPersistenceTestFixtures.fingerprint('a'));
        unsigned = unsigned(plan, candidate);
        when(evidence.find(
                unsigned.scope(),
                candidate.evidence().runId()))
                .thenReturn(Optional.of(candidate));
        when(bindings.create(any()))
                .thenAnswer(invocation ->
                        invocation.getArgument(0));
    }

    @Test
    void publishesOnlyAfterClosingTheExactCandidateAndResolvesByExactRef() {
        ReadOnlyShadowSourceBinding signed =
                service.publish(unsigned);
        when(bindings.find(
                signed.scope(),
                signed.bindingId(),
                signed.revision()))
                .thenReturn(Optional.of(signed));

        assertThat(signed.bindingSeal().signed()).isTrue();
        assertThat(service.resolve(
                signed.scope(),
                signed.artifactRef(),
                NOW.plusSeconds(1)))
                .isEqualTo(signed);
        assertThat(service.ready()).isTrue();
    }

    @Test
    void rejectsCandidateDriftExpiryAndReferenceFingerprintDrift() {
        when(evidence.find(
                unsigned.scope(),
                candidate.evidence().runId()))
                .thenReturn(Optional.empty());
        assertFailure(
                () -> service.publish(unsigned),
                ReadOnlyShadowSourceBindingService.Reason
                        .CANDIDATE_NOT_FOUND);

        when(evidence.find(
                unsigned.scope(),
                candidate.evidence().runId()))
                .thenReturn(Optional.of(candidate));
        ReadOnlyShadowSourceBinding signed =
                service.publish(unsigned);
        when(bindings.find(
                signed.scope(),
                signed.bindingId(),
                signed.revision()))
                .thenReturn(Optional.of(signed));
        assertFailure(
                () -> service.resolve(
                        signed.scope(),
                        new MirrorArtifactRef(
                                ReadOnlyShadowSourceBinding.ARTIFACT_KIND,
                                signed.bindingId(),
                                signed.revision(),
                                ReadOnlyShadowJobTestFixtures
                                        .fingerprint('f')),
                        NOW.plusSeconds(1)),
                ReadOnlyShadowSourceBindingService.Reason
                        .REFERENCE_MISMATCH);
        assertFailure(
                () -> service.resolve(
                        signed.scope(),
                        signed.artifactRef(),
                        signed.expiresAt()),
                ReadOnlyShadowSourceBindingService.Reason
                        .WINDOW_REJECTED);
    }

    private static void assertFailure(
            Runnable action,
            ReadOnlyShadowSourceBindingService.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ReadOnlyShadowSourceBindingService.Failure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(reason));
    }

    private static ReadOnlyShadowSourceBinding unsigned(
            MirrorPlan plan,
            MirrorEvidenceBundle bundle) {
        return new ReadOnlyShadowSourceBinding(
                ReadOnlyShadowSourceBinding.SCHEMA_VERSION,
                "",
                "detached-refund-pair",
                1,
                plan.scope(),
                new MirrorArtifactRef(
                        "SCENARIO_CASE",
                        "refund-golden",
                        1,
                        MirrorPersistenceTestFixtures.fingerprint('1')),
                plan.rootCapability(),
                new MirrorArtifactRef(
                        "MIRROR_PLAN",
                        plan.planId(),
                        1,
                        plan.planFingerprint()),
                new MirrorArtifactRef(
                        "SHADOW_BASELINE_BINDING",
                        "refund-production-read",
                        1,
                        MirrorPersistenceTestFixtures.fingerprint('2')),
                new MirrorArtifactRef(
                        "SHADOW_COMPARISON_POLICY",
                        "behavior-fingerprint-v1",
                        1,
                        MirrorPersistenceTestFixtures.fingerprint('3')),
                bundle.evidence().requestContextFingerprint(),
                "",
                new ReadOnlyShadowSourceBinding.BaselineObservation(
                        MirrorPersistenceTestFixtures.fingerprint('b'),
                        Map.of(
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                MirrorPersistenceTestFixtures
                                        .fingerprint('b')),
                        NOW.minusSeconds(20),
                        MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                        true,
                        false,
                        0),
                new MirrorArtifactRef(
                        "MIRROR_EVIDENCE_BUNDLE",
                        bundle.evidence().runId(),
                        1,
                        bundle.bundleFingerprint()),
                NOW,
                NOW.plusSeconds(3600),
                NOW.minusSeconds(10),
                VisualRunEvidenceSeal.unsigned());
    }
}
