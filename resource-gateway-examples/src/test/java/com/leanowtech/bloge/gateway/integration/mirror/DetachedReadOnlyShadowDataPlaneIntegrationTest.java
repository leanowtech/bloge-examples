package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetachedReadOnlyShadowDataPlaneIntegrationTest {
    private static final Instant NOW =
            ReadOnlyShadowSourceResolutionTestFixtures.NOW;

    @Test
    void executesExactDetachedPairAndPersistsIndependentlyVerifiedSourceProof() {
        ObjectMapper mapper =
                new ObjectMapper().findAndRegisterModules();
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        Clock.fixed(
                                NOW.plusSeconds(4),
                                ZoneOffset.UTC));
        PayloadFreeEqualityReadOnlyShadowPolicy policy =
                new PayloadFreeEqualityReadOnlyShadowPolicy(
                        mapper);
        MirrorPlan plan =
                MirrorPersistenceTestFixtures.plan(
                        mapper,
                        MirrorPersistenceTestFixtures
                                .scope("support"),
                        "refund-shadow-plan",
                        '4');
        MirrorEvidenceBundle candidateEvidence =
                MirrorPersistenceTestFixtures.evidence(
                        mapper,
                        signer,
                        plan,
                        "candidate-run",
                        'a',
                        "candidate-request",
                        fingerprint('9'));
        MirrorEvidenceRepository evidenceRepository =
                mock(MirrorEvidenceRepository.class);
        when(evidenceRepository.find(
                plan.scope(),
                candidateEvidence.evidence().runId()))
                .thenReturn(Optional.of(candidateEvidence));
        ReadOnlyShadowSourceBindingRepository bindingRepository =
                mock(ReadOnlyShadowSourceBindingRepository.class);
        when(bindingRepository.create(any()))
                .thenAnswer(invocation ->
                        invocation.getArgument(0));
        ReadOnlyShadowSourceBindingIntegrity bindingIntegrity =
                new ReadOnlyShadowSourceBindingIntegrity(
                        mapper,
                        signer,
                        Clock.fixed(
                                NOW.plusSeconds(4),
                                ZoneOffset.UTC));
        ReadOnlyShadowSourceBindingService bindingService =
                new ReadOnlyShadowSourceBindingService(
                        bindingRepository,
                        evidenceRepository,
                        bindingIntegrity,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        ReadOnlyShadowSourceBinding binding =
                bindingService.publish(
                        unsignedBinding(
                                plan,
                                candidateEvidence,
                                policy));
        when(bindingRepository.find(
                binding.scope(),
                binding.bindingId(),
                binding.revision()))
                .thenReturn(Optional.of(binding));
        ReadOnlyShadowJobRequest request =
                request(binding);
        MirrorEvidenceIntegrityService evidenceIntegrity =
                new MirrorEvidenceIntegrityService(
                        mapper,
                        signer,
                        Clock.fixed(
                                NOW.plusSeconds(4),
                                ZoneOffset.UTC));
        ReadOnlyShadowSourceResolutionAttestationIntegrity
                attestationIntegrity =
                new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                        mapper,
                        signer,
                        Clock.fixed(
                                NOW.plusSeconds(4),
                                ZoneOffset.UTC));

        var database =
                new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true)
                        .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            DatabaseReadOnlyShadowSourceResolutionAttestationRepository
                    attestations =
                    new DatabaseReadOnlyShadowSourceResolutionAttestationRepository(
                            jdbc,
                            mapper,
                            attestationIntegrity);
            attestations.init();
            Clock connectorClock =
                    Clock.fixed(
                            NOW.plusSeconds(2),
                            ZoneOffset.UTC);
            DetachedReadOnlyShadowSourceResolutionVerifier
                    sourceVerifier =
                    new DetachedReadOnlyShadowSourceResolutionVerifier(
                            bindingService,
                            evidenceRepository,
                            evidenceIntegrity,
                            policy,
                            attestations,
                            attestationIntegrity,
                            mapper,
                            Clock.fixed(
                                    NOW.plusSeconds(4),
                                    ZoneOffset.UTC));
            GovernedReadOnlyShadowDataPlane dataPlane =
                    new GovernedReadOnlyShadowDataPlane(
                            authority(),
                            guard(),
                            new DetachedReadOnlyShadowBaselineConnector(
                                    bindingService,
                                    policy,
                                    connectorClock),
                            new DetachedReadOnlyShadowCandidateConnector(
                                    bindingService,
                                    evidenceRepository,
                                    evidenceIntegrity,
                                    policy,
                                    connectorClock),
                            sourceVerifier,
                            policy,
                            Clock.fixed(
                                    NOW.plusSeconds(4),
                                    ZoneOffset.UTC));

            ReadOnlyShadowDataPlane.ExecutionResult result =
                    dataPlane.execute(
                            permit(request));

            assertThat(dataPlane.ready()).isTrue();
            assertThat(result.baseline().artifactRef())
                    .isEqualTo(binding.baselineArtifactRef());
            assertThat(result.candidate().artifactRef())
                    .isEqualTo(
                            binding.candidateEvidenceRef());
            assertThat(result.results())
                    .extracting(
                            ReadOnlyShadowComparison
                                    .DimensionComparison::dimension)
                    .containsExactly(
                            DomainFidelityProfile.Dimension.BEHAVIOR,
                            DomainFidelityProfile.Dimension.CONTRACT,
                            DomainFidelityProfile.Dimension.EFFECT);
            assertThat(result.results())
                    .allSatisfy(comparison ->
                            assertThat(comparison.outcome())
                                    .isEqualTo(
                                            ReadOnlyShadowComparison
                                                    .DiffOutcome.MATCH));
            ReadOnlyShadowSourceResolutionAttestation stored =
                    attestations.find(
                            request.scope(),
                            result.sourceResolutionAttestationRef()
                                    .id(),
                            result.sourceResolutionAttestationRef()
                                    .revision())
                            .orElseThrow();
            assertThat(stored.artifactRef())
                    .isEqualTo(
                            result.sourceResolutionAttestationRef());
            assertThat(stored.executionId())
                    .isEqualTo("detached-e2e-execution");
            assertThat(stored.sourceBindingRef())
                    .isEqualTo(binding.artifactRef());
            assertThat(stored.baseline().writeCredentialExposed())
                    .isFalse();
            assertThat(stored.baseline().writeAttemptCount())
                    .isZero();
            assertThat(stored.candidate().writeCredentialExposed())
                    .isFalse();
            assertThat(stored.candidate().writeAttemptCount())
                    .isZero();
            assertThat(attestationIntegrity.verify(stored))
                    .isEqualTo(stored);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM "
                            + "read_only_shadow_source_resolution_attestation",
                    Long.class)).isEqualTo(1L);
        } finally {
            database.shutdown();
        }
    }

    private static ReadOnlyShadowAccessAuthority authority() {
        return new ReadOnlyShadowAccessAuthority() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public Admission admit(
                    ReadOnlyShadowDataPlane.Permit permit) {
                return ReadOnlyShadowSourceResolutionTestFixtures
                        .admission(permit.request());
            }

            @Override
            public Confirmation confirm(
                    Admission admission,
                    Instant startedAt,
                    Instant completedAt) {
                return ReadOnlyShadowSourceResolutionTestFixtures
                        .confirmation(admission);
            }
        };
    }

    private static ReadOnlyShadowExecutionGuard guard() {
        return new ReadOnlyShadowExecutionGuard() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public Lease acquire(
                    ReadOnlyShadowDataPlane.Permit permit,
                    ReadOnlyShadowAccessAuthority.Admission admission) {
                return new Lease() {
                    @Override
                    public void renew(
                            Instant leaseExpiresAt) {
                    }

                    @Override
                    public void succeeded() {
                    }

                    @Override
                    public void failed(
                            ReadOnlyShadowDataPlane.FailureReason reason) {
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

    private static ReadOnlyShadowDataPlane.Permit permit(
            ReadOnlyShadowJobRequest request) {
        return new ReadOnlyShadowDataPlane.Permit(
                "detached-e2e-execution",
                request,
                1,
                request.deadlineAt(),
                new ReadOnlyShadowDataPlane.ExecutionControl() {
                    @Override
                    public Instant leaseExpiresAt() {
                        return NOW.plusSeconds(45);
                    }

                    @Override
                    public Instant heartbeat() {
                        return NOW.plusSeconds(45);
                    }
                });
    }

    private static ReadOnlyShadowSourceBinding unsignedBinding(
            MirrorPlan plan,
            MirrorEvidenceBundle bundle,
            PayloadFreeEqualityReadOnlyShadowPolicy policy) {
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

    private static String fingerprint(
            char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
