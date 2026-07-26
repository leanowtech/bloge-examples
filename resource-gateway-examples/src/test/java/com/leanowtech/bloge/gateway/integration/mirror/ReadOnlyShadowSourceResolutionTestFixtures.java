package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Shared payload-free source-resolution attestation fixtures. */
final class ReadOnlyShadowSourceResolutionTestFixtures {
    static final Instant NOW =
            ReadOnlyShadowJobTestFixtures.NOW;

    private ReadOnlyShadowSourceResolutionTestFixtures() {
    }

    static ReadOnlyShadowSourceResolutionAttestation unsigned(
            MirrorArtifactRef policyRef) {
        return new ReadOnlyShadowSourceResolutionAttestation(
                ReadOnlyShadowSourceResolutionAttestation
                        .SCHEMA_VERSION,
                "",
                "source-resolution-test",
                1,
                ReadOnlyShadowJobTestFixtures.scope("support"),
                "detached-job",
                "detached-execution",
                ReadOnlyShadowJobTestFixtures.ref(
                        ReadOnlyShadowSourceBinding.ARTIFACT_KIND,
                        "detached-pair",
                        '1'),
                policyRef,
                fingerprint('2'),
                fingerprint('3'),
                NOW,
                NOW.plusSeconds(3),
                resolution(
                        ReadOnlyShadowComparison.SourceRole.BASELINE,
                        "SHADOW_BASELINE_OBSERVATION",
                        "detached-pair:baseline",
                        '4',
                        NOW.minusSeconds(20),
                        NOW.plusSeconds(2)),
                resolution(
                        ReadOnlyShadowComparison.SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        "candidate-run",
                        '5',
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(2)),
                NOW.plusSeconds(4),
                VisualRunEvidenceSeal.unsigned());
    }

    static ReadOnlyShadowSourceResolutionAttestation unsignedOnline(
            MirrorArtifactRef policyRef) {
        return new ReadOnlyShadowSourceResolutionAttestation(
                ReadOnlyShadowSourceResolutionAttestation
                        .ONLINE_SCHEMA_VERSION,
                "",
                "source-resolution-online-test",
                1,
                ReadOnlyShadowJobTestFixtures.scope("support"),
                "online-job",
                "online-execution",
                ReadOnlyShadowJobRequest.SourceMode
                        .ONLINE_EXECUTION,
                null,
                fingerprint('a'),
                fingerprint('b'),
                policyRef,
                fingerprint('2'),
                fingerprint('3'),
                NOW,
                NOW.plusSeconds(3),
                resolution(
                        ReadOnlyShadowComparison.SourceRole.BASELINE,
                        "SHADOW_BASELINE_OBSERVATION",
                        "online-pair:baseline",
                        '4',
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(4)),
                resolution(
                        ReadOnlyShadowComparison.SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        "online-candidate-run",
                        '5',
                        NOW.plusSeconds(2),
                        NOW.plusSeconds(4)),
                NOW.plusSeconds(4),
                VisualRunEvidenceSeal.unsigned());
    }

    static ReadOnlyShadowSourceResolutionAttestation.SourceResolution
    resolution(
            ReadOnlyShadowComparison.SourceRole role,
            String kind,
            String id,
            char material,
            Instant sourceCompletedAt,
            Instant resolvedAt) {
        return new ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution(
                role,
                ReadOnlyShadowJobTestFixtures.ref(
                        kind, id, material),
                fingerprint(material),
                Map.of(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        fingerprint(material)),
                sourceCompletedAt,
                resolvedAt,
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                true,
                false,
                0);
    }

    static ReadOnlyShadowAccessAuthority.Admission admission(
            ReadOnlyShadowJobRequest request) {
        ReadOnlyShadowExecutionGuard.Limits limits =
                new ReadOnlyShadowExecutionGuard.Limits(
                        4,
                        60,
                        Duration.ofMinutes(1),
                        3,
                        Duration.ofSeconds(30));
        ReadOnlyShadowSamplingGrantAuthority.Grant grant =
                new ReadOnlyShadowSamplingGrantAuthority.Grant(
                        request.scope(),
                        request.scope(),
                        request.accessGrant()
                                .samplingGrantRef(),
                        request.accessGrant()
                                .maximumSamples(),
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(60),
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_EXECUTION_GUARD_POLICY",
                                "detached-pressure",
                                '4'),
                        limits,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_SAMPLING_GRANT_ATTESTATION",
                                request.accessGrant()
                                        .samplingGrantRef()
                                        .id(),
                                '5'),
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_EXECUTION_GUARD_POLICY_ATTESTATION",
                                "detached-pressure",
                                '6'),
                        NOW);
        ReadOnlyShadowKillSwitchAuthority.State killSwitch =
                new ReadOnlyShadowKillSwitchAuthority.State(
                        request.scope(),
                        request.accessGrant()
                                .killSwitchRef(),
                        true,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(60),
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_KILL_SWITCH_ATTESTATION",
                                request.accessGrant()
                                        .killSwitchRef()
                                        .id(),
                                '7'),
                        NOW);
        MirrorDeploymentIsolationRunTrust.Admission egress =
                new MirrorDeploymentIsolationRunTrust.Admission(
                        request.scope(),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAttestationBundle
                                        .ARTIFACT_KIND,
                                "detached-egress-decision",
                                '8'),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAuthorityKeySetPublication
                                        .ARTIFACT_KIND,
                                "detached-egress-authority",
                                '9'),
                        request.accessGrant()
                                .egressAuthorityRef(),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAttestationStatusPublication
                                        .ARTIFACT_KIND,
                                "detached-egress-status",
                                'a'),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAgentSnapshot
                                        .ARTIFACT_KIND,
                                "detached-egress-snapshot",
                                'b'),
                        NOW,
                        NOW.plusSeconds(60));
        return new ReadOnlyShadowAccessAuthority.Admission(
                fingerprint('c'),
                request.accessGrant().zeroWriteProof(),
                limits,
                grant,
                killSwitch,
                egress,
                NOW,
                NOW.plusSeconds(60));
    }

    static ReadOnlyShadowAccessAuthority.Confirmation confirmation(
            ReadOnlyShadowAccessAuthority.Admission admission) {
        MirrorArtifactRef admitted =
                admission.egressAdmission()
                        .admittedSnapshotRef();
        MirrorDeploymentIsolationRunTrust.Binding egress =
                new MirrorDeploymentIsolationRunTrust.Binding(
                        "",
                        admission.egressAdmission().decisionRef(),
                        admission.egressAdmission()
                                .authorityKeySetRef(),
                        admission.egressAdmission().attestationRef(),
                        admission.egressAdmission().statusRef(),
                        admitted,
                        new MirrorArtifactRef(
                                admitted.kind(),
                                admitted.id(),
                                2,
                                fingerprint('d')),
                        admission.admittedAt(),
                        NOW.plusSeconds(3));
        return new ReadOnlyShadowAccessAuthority.Confirmation(
                admission.admissionFingerprint(),
                admission.samplingGrant(),
                admission.killSwitch(),
                egress,
                NOW.plusSeconds(3));
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
