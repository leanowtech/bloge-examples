package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** Shared exact protocol fixtures for the online baseline sidecar boundary. */
final class OnlineReadOnlyShadowBaselineTestFixtures {
    static final Instant NOW =
            Instant.parse("2026-07-26T00:00:00Z");
    static final Clock CLOCK =
            Clock.fixed(
                    NOW.plusSeconds(4),
                    ZoneOffset.UTC);

    private OnlineReadOnlyShadowBaselineTestFixtures() {
    }

    static ObjectMapper mapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(
                        SerializationFeature
                                .WRITE_DATES_AS_TIMESTAMPS);
    }

    static OnlineReadOnlyShadowBaselineObservationIntegrity
    integrity(
            ObjectMapper mapper) {
        return new OnlineReadOnlyShadowBaselineObservationIntegrity(
                mapper,
                authority(),
                CLOCK);
    }

    static OnlineReadOnlyShadowBaselineEvidenceAuthority
    authority() {
        return OnlineReadOnlyShadowBaselineEvidenceAuthority
                .from(
                        InMemoryVisualEvidenceSigner
                                .usingClock(CLOCK));
    }

    static OnlineReadOnlyShadowBaselineCommand command(
            ObjectMapper mapper) {
        ReadOnlyShadowJobRequest request =
                request("online-command");
        ReadOnlyShadowAccessAuthority.Admission admission =
                admission(request);
        return new OnlineReadOnlyShadowBaselineCommand(
                OnlineReadOnlyShadowBaselineCommand
                        .SCHEMA_VERSION,
                "execution-online-command",
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
                request.deadlineAt());
    }

    static OnlineReadOnlyShadowBaselineObservation unsigned(
            ObjectMapper mapper,
            OnlineReadOnlyShadowBaselineCommand command) {
        return unsigned(
                mapper, command, false, 0);
    }

    static OnlineReadOnlyShadowBaselineObservation unsigned(
            ObjectMapper mapper,
            OnlineReadOnlyShadowBaselineCommand command,
            boolean writeCredentialExposed,
            long writeAttemptCount) {
        return new OnlineReadOnlyShadowBaselineObservation(
                OnlineReadOnlyShadowBaselineObservation
                        .SCHEMA_VERSION,
                "",
                OnlineReadOnlyShadowBaselineObservation
                        .deterministicObservationId(
                                mapper,
                                command.scope(),
                                command.executionId(),
                                command.commandFingerprint(
                                        mapper),
                                command.baselineBindingRef()),
                1,
                command.scope(),
                command.executionId(),
                command.requestId(),
                command.commandFingerprint(mapper),
                command.scenarioCaseRef(),
                command.targetCapabilityRef(),
                command.baselineBindingRef(),
                command.comparisonPolicyRef(),
                command.accessGrant()
                        .samplingGrantRef(),
                command.accessGrant()
                        .egressAuthorityRef(),
                command.accessGrant()
                        .killSwitchRef(),
                ref(
                        "WORKLOAD_IDENTITY",
                        "baseline-read-identity",
                        'a'),
                ref(
                        "WORKLOAD_IDENTITY_ATTESTATION",
                        "baseline-read-identity",
                        'b'),
                ref(
                        "PAYLOAD_VAULT_RECEIPT",
                        "vault-receipt",
                        'c'),
                ref(
                        "READ_ONLY_TRANSPORT_ATTESTATION",
                        "baseline-read-transport",
                        'd'),
                fingerprint('e'),
                fingerprint('f'),
                fingerprint('1'),
                fingerprint('2'),
                command.idempotencyKeyFingerprint(
                        mapper),
                ref(
                        "JSON_SCHEMA",
                        "refund-response",
                        '3'),
                Map.of(
                        DomainFidelityProfile.Dimension
                                .BEHAVIOR,
                        fingerprint('4'),
                        DomainFidelityProfile.Dimension
                                .CONTRACT,
                        fingerprint('5')),
                OnlineReadOnlyShadowBaselineObservation
                        .AccessMode.READ_ONLY,
                NOW.plusSeconds(1),
                NOW.plusSeconds(2),
                command.deadlineAt(),
                NOW.plusSeconds(60),
                MirrorRunEvidence.EvidenceClass
                        .CERTIFIABLE,
                true,
                writeCredentialExposed,
                writeAttemptCount,
                NOW.plusSeconds(3),
                VisualRunEvidenceSeal.unsigned());
    }

    static ReadOnlyShadowConnectorInvocation invocation(
            String requestId) {
        ReadOnlyShadowJobRequest request =
                request(requestId);
        ReadOnlyShadowAccessAuthority.Admission admission =
                admission(request);
        return new ReadOnlyShadowConnectorInvocation(
                "execution-" + requestId,
                request,
                admission,
                NOW.plusSeconds(1),
                request.deadlineAt());
    }

    static ReadOnlyShadowJobRequest request(
            String requestId) {
        ReadOnlyShadowJobRequest source =
                ReadOnlyShadowJobTestFixtures.request(
                        requestId, 1);
        return new ReadOnlyShadowJobRequest(
                source.schemaVersion(),
                source.requestId(),
                source.scope(),
                source.inventoryRef(),
                source.unitId(),
                source.scenarioCaseRef(),
                source.targetCapabilityRef(),
                source.candidatePlanRef(),
                source.baselineBindingRef(),
                source.comparisonPolicyRef(),
                source.accessGrant(),
                NOW.plus(Duration.ofMinutes(5)));
    }

    static OnlineReadOnlyShadowBaselineCommand command(
            ReadOnlyShadowConnectorInvocation invocation) {
        ReadOnlyShadowJobRequest request =
                invocation.request();
        return new OnlineReadOnlyShadowBaselineCommand(
                OnlineReadOnlyShadowBaselineCommand
                        .SCHEMA_VERSION,
                invocation.executionId(),
                request.requestId(),
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.baselineBindingRef(),
                request.comparisonPolicyRef(),
                request.accessGrant(),
                invocation.accessAdmission()
                        .admissionFingerprint(),
                invocation.accessAdmission()
                        .admittedAt(),
                request.deadlineAt());
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
                        NOW.plusSeconds(120),
                        ref(
                                "SHADOW_EXECUTION_GUARD_POLICY",
                                "baseline-pressure",
                                '6'),
                        limits,
                        ref(
                                "SHADOW_SAMPLING_GRANT_ATTESTATION",
                                request.accessGrant()
                                        .samplingGrantRef()
                                        .id(),
                                '7'),
                        ref(
                                "SHADOW_EXECUTION_GUARD_POLICY_ATTESTATION",
                                "baseline-pressure",
                                '8'),
                        NOW);
        ReadOnlyShadowKillSwitchAuthority.State killSwitch =
                new ReadOnlyShadowKillSwitchAuthority.State(
                        request.scope(),
                        request.accessGrant()
                                .killSwitchRef(),
                        true,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(120),
                        ref(
                                "SHADOW_KILL_SWITCH_ATTESTATION",
                                request.accessGrant()
                                        .killSwitchRef()
                                        .id(),
                                '9'),
                        NOW);
        MirrorDeploymentIsolationRunTrust.Admission egress =
                new MirrorDeploymentIsolationRunTrust.Admission(
                        request.scope(),
                        ref(
                                MirrorDeploymentIsolationAttestationBundle
                                        .ARTIFACT_KIND,
                                "egress-decision",
                                'a'),
                        ref(
                                MirrorDeploymentIsolationAuthorityKeySetPublication
                                        .ARTIFACT_KIND,
                                "egress-authority",
                                'b'),
                        request.accessGrant()
                                .egressAuthorityRef(),
                        ref(
                                MirrorDeploymentIsolationAttestationStatusPublication
                                        .ARTIFACT_KIND,
                                "egress-status",
                                'c'),
                        ref(
                                MirrorDeploymentIsolationAgentSnapshot
                                        .ARTIFACT_KIND,
                                "egress-snapshot",
                                'd'),
                        NOW,
                        NOW.plusSeconds(120));
        return new ReadOnlyShadowAccessAuthority.Admission(
                fingerprint('0'),
                request.accessGrant().zeroWriteProof(),
                limits,
                grant,
                killSwitch,
                egress,
                NOW,
                NOW.plusSeconds(120));
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
        return "sha256:"
                + String.valueOf(material).repeat(64);
    }
}
