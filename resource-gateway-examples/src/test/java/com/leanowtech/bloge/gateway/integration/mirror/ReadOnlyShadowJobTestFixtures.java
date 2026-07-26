package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/** Shared payload-free fixtures for durable Shadow queue and worker tests. */
final class ReadOnlyShadowJobTestFixtures {
    static final Instant NOW =
            Instant.parse("2026-07-26T00:00:00Z");
    static final ReadOnlyShadowJobPolicy POLICY =
            new ReadOnlyShadowJobPolicy(
                    3,
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(5),
                    Duration.ofHours(1));

    private ReadOnlyShadowJobTestFixtures() {
    }

    static CapabilitySnapshot.Scope scope(
            String tenant) {
        return new CapabilitySnapshot.Scope(
                tenant,
                "customer-operations",
                "refund-project",
                "staging",
                "ap-southeast-1");
    }

    static IntegrationRequestContext identity(
            String tenant,
            String purpose) {
        CapabilitySnapshot.Scope scope =
                scope(tenant);
        return new IntegrationRequestContext(
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                "SERVICE",
                "shadow-author",
                "",
                purpose,
                "shadow-correlation",
                Set.of("shadow-authors"),
                "CONFIDENTIAL",
                "");
    }

    static ReadOnlyShadowComparisonIntegrity integrity(
            ObjectMapper mapper) {
        Clock clock = Clock.fixed(
                NOW.plusSeconds(10),
                ZoneOffset.UTC);
        return new ReadOnlyShadowComparisonIntegrity(
                mapper,
                InMemoryVisualEvidenceSigner
                        .usingClock(clock),
                clock);
    }

    static ReadOnlyShadowJobRequest request(
            String requestId,
            long sampleOrdinal) {
        CapabilitySnapshot.Scope scope =
                scope("support");
        return new ReadOnlyShadowJobRequest(
                ReadOnlyShadowJobRequest.SCHEMA_VERSION,
                requestId,
                scope,
                ref(
                        DomainFidelityInventory.ARTIFACT_KIND,
                        "refund-fidelity",
                        '1'),
                "refund-golden",
                ref(
                        "SCENARIO_CASE",
                        "refund-golden",
                        '2'),
                ref(
                        "CAPABILITY",
                        "refund",
                        '3'),
                ref(
                        "MIRROR_PLAN",
                        "refund-shadow-plan",
                        '4'),
                ref(
                        "SHADOW_BASELINE_BINDING",
                        "refund-production-read",
                        '5'),
                ref(
                        "SHADOW_COMPARISON_POLICY",
                        "refund-semantic-v1",
                        '6'),
                new ReadOnlyShadowJobRequest.AccessGrant(
                        ReadOnlyShadowComparison
                                .AccessMode.READ_ONLY,
                        ref(
                                "SHADOW_SAMPLING_GRANT",
                                "grant-2026-07",
                                '7'),
                        ref(
                                MirrorDeploymentIsolationAttestation
                                        .ARTIFACT_KIND,
                                "shadow-deployment",
                                '8'),
                        ref(
                                "SHADOW_KILL_SWITCH_STATE",
                                "shadow-kill-switch",
                                '9'),
                        sampleOrdinal,
                        100),
                NOW.plus(Duration.ofMinutes(30)));
    }

    static ReadOnlyShadowComparison unsignedComparison(
            String jobId,
            ReadOnlyShadowJobRequest request) {
        ReadOnlyShadowComparison.SourceObservation baseline =
                observation(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE,
                        "SHADOW_BASELINE_OBSERVATION",
                        'a');
        ReadOnlyShadowComparison.SourceObservation candidate =
                observation(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        'b');
        return new ReadOnlyShadowComparison(
                ReadOnlyShadowComparison.SCHEMA_VERSION,
                jobId,
                1,
                "",
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.comparisonPolicyRef(),
                ref(
                        "SHADOW_SOURCE_RESOLUTION_ATTESTATION",
                        "sources-" + request.requestId(),
                        'c'),
                request.accessGrant().zeroWriteProof(),
                baseline,
                candidate,
                NOW.plusSeconds(6),
                List.of(
                        new ReadOnlyShadowComparison
                                .DimensionComparison(
                                DomainFidelityProfile
                                        .Dimension.BEHAVIOR,
                                fingerprint('d'),
                                fingerprint('d'),
                                ReadOnlyShadowComparison
                                        .DiffOutcome.MATCH,
                                List.of())),
                null);
    }

    static ReadOnlyShadowDataPlane.ExecutionResult
    executionResult(
            ReadOnlyShadowJobRequest request) {
        ReadOnlyShadowComparison comparison =
                unsignedComparison(
                        "shadow-placeholder",
                        request);
        return new ReadOnlyShadowDataPlane.ExecutionResult(
                comparison.accessProof(),
                comparison.sourceResolutionAttestationRef(),
                comparison.baseline(),
                comparison.candidate(),
                comparison.observedAt(),
                comparison.results());
    }

    static MirrorArtifactRef ref(
            String kind,
            String id,
            char material) {
        return new MirrorArtifactRef(
                kind,
                id,
                1,
                fingerprint(material));
    }

    static String fingerprint(char material) {
        return "sha256:" + String.valueOf(material)
                .repeat(64);
    }

    private static ReadOnlyShadowComparison.SourceObservation
    observation(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowComparison.SourceRole role,
            String kind,
            char material) {
        return new ReadOnlyShadowComparison.SourceObservation(
                role,
                ref(
                        kind,
                        role.name().toLowerCase(),
                        material),
                request.scope(),
                request.targetCapabilityRef(),
                fingerprint('e'),
                fingerprint(material),
                NOW.plusSeconds(5),
                MirrorRunEvidence
                        .EvidenceClass.CERTIFIABLE,
                true);
    }
}
