package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposedReadOnlyShadowAccessAuthorityTest {
    private static final Instant NOW =
            ReadOnlyShadowJobTestFixtures.NOW;
    private static final ReadOnlyShadowExecutionGuard.Limits LIMITS =
            new ReadOnlyShadowExecutionGuard.Limits(
                    8,
                    120,
                    Duration.ofMinutes(1),
                    5,
                    Duration.ofSeconds(30));

    @Test
    void doubleObservesTheExactGrantKillSwitchAndDeploymentDecision() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "authority-success", 7);
        AtomicReference<ReadOnlyShadowSamplingGrantAuthority.Grant>
                grant = new AtomicReference<>(grant(request));
        AtomicReference<ReadOnlyShadowKillSwitchAuthority.State>
                killSwitch = new AtomicReference<>(
                killSwitch(request, true));
        RecordingRunTrust runTrust =
                new RecordingRunTrust(
                        request.accessGrant()
                                .egressAuthorityRef());
        AtomicReference<Instant> now =
                new AtomicReference<>(NOW);
        ComposedReadOnlyShadowAccessAuthority authority =
                authority(
                        grant, killSwitch, runTrust, now);

        ReadOnlyShadowAccessAuthority.Admission admission =
                authority.admit(permit(request));
        now.set(NOW.plusSeconds(4));
        ReadOnlyShadowAccessAuthority.Confirmation confirmation =
                authority.confirm(
                        admission,
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(3));

        assertThat(admission.accessProof())
                .isEqualTo(
                        request.accessGrant()
                                .zeroWriteProof());
        assertThat(admission.guardLimits())
                .isEqualTo(LIMITS);
        assertThat(admission.validUntil())
                .isEqualTo(NOW.plusSeconds(60));
        assertThat(confirmation.admissionFingerprint())
                .isEqualTo(admission.admissionFingerprint());
        assertThat(confirmation.confirmedAt())
                .isEqualTo(NOW.plusSeconds(4));
        assertThat(runTrust.confirmations).isEqualTo(1);
        assertThat(authority.ready()).isTrue();
    }

    @Test
    void rejectsGrantDriftAtTerminalConfirmation() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "authority-grant-drift", 8);
        AtomicReference<ReadOnlyShadowSamplingGrantAuthority.Grant>
                grant = new AtomicReference<>(grant(request));
        AtomicReference<ReadOnlyShadowKillSwitchAuthority.State>
                killSwitch = new AtomicReference<>(
                killSwitch(request, true));
        AtomicReference<Instant> now =
                new AtomicReference<>(NOW);
        ComposedReadOnlyShadowAccessAuthority authority =
                authority(
                        grant,
                        killSwitch,
                        new RecordingRunTrust(
                                request.accessGrant()
                                        .egressAuthorityRef()),
                        now);
        ReadOnlyShadowAccessAuthority.Admission admission =
                authority.admit(permit(request));
        now.set(NOW.plusSeconds(4));
        ReadOnlyShadowSamplingGrantAuthority.Grant original =
                grant.get();
        grant.set(new ReadOnlyShadowSamplingGrantAuthority.Grant(
                original.scope(),
                original.guardScope(),
                original.grantRef(),
                original.maximumSamples(),
                original.validFrom(),
                original.expiresAt(),
                original.guardPolicyRef(),
                new ReadOnlyShadowExecutionGuard.Limits(
                        4,
                        120,
                        Duration.ofMinutes(1),
                        5,
                        Duration.ofSeconds(30)),
                original.authorityAttestationRef(),
                NOW.plusSeconds(2)));

        assertThatThrownBy(() -> authority.confirm(
                admission,
                NOW.plusSeconds(1),
                NOW.plusSeconds(3)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane
                                .FailureReason.GRANT_REVOKED);
    }

    @Test
    void rejectsKillSwitchDriftBeforeTerminalEgressConfirmation() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "authority-kill-drift", 9);
        AtomicReference<ReadOnlyShadowSamplingGrantAuthority.Grant>
                grant = new AtomicReference<>(grant(request));
        AtomicReference<ReadOnlyShadowKillSwitchAuthority.State>
                killSwitch = new AtomicReference<>(
                killSwitch(request, true));
        AtomicReference<Instant> now =
                new AtomicReference<>(NOW);
        RecordingRunTrust runTrust =
                new RecordingRunTrust(
                        request.accessGrant()
                                .egressAuthorityRef());
        ComposedReadOnlyShadowAccessAuthority authority =
                authority(
                        grant,
                        killSwitch,
                        runTrust,
                        now);
        ReadOnlyShadowAccessAuthority.Admission admission =
                authority.admit(permit(request));
        now.set(NOW.plusSeconds(4));
        ReadOnlyShadowKillSwitchAuthority.State original =
                killSwitch.get();
        killSwitch.set(
                new ReadOnlyShadowKillSwitchAuthority.State(
                        original.scope(),
                        original.killSwitchRef(),
                        false,
                        original.effectiveAt(),
                        original.expiresAt(),
                        original.authorityAttestationRef(),
                        NOW.plusSeconds(2)));

        assertThatThrownBy(() -> authority.confirm(
                admission,
                NOW.plusSeconds(1),
                NOW.plusSeconds(3)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane
                                .FailureReason.KILL_SWITCH_OPEN);
        assertThat(runTrust.confirmations).isZero();
    }

    @Test
    void rejectsAnOpenKillSwitchBeforeEgressAdmission() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "authority-kill-open", 10);
        AtomicReference<ReadOnlyShadowSamplingGrantAuthority.Grant>
                grant = new AtomicReference<>(grant(request));
        AtomicReference<ReadOnlyShadowKillSwitchAuthority.State>
                killSwitch = new AtomicReference<>(
                killSwitch(request, false));
        RecordingRunTrust runTrust =
                new RecordingRunTrust(
                        request.accessGrant()
                                .egressAuthorityRef());

        assertThatThrownBy(() -> authority(
                grant,
                killSwitch,
                runTrust,
                new AtomicReference<>(NOW))
                .admit(permit(request)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane
                                .FailureReason.KILL_SWITCH_OPEN);
        assertThat(runTrust.admissions).isZero();
    }

    @Test
    void rejectsDeploymentTrustForADifferentEgressAttestation() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "authority-egress-drift", 11);
        MirrorArtifactRef different =
                ReadOnlyShadowJobTestFixtures.ref(
                        MirrorDeploymentIsolationAttestation
                                .ARTIFACT_KIND,
                        "different-egress",
                        'f');

        assertThatThrownBy(() -> authority(
                new AtomicReference<>(grant(request)),
                new AtomicReference<>(
                        killSwitch(request, true)),
                new RecordingRunTrust(different),
                new AtomicReference<>(NOW))
                .admit(permit(request)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane
                                .FailureReason.EGRESS_DENIED);
    }

    @Test
    void readinessFailsClosedWhenAnyOnlineAuthorityIsUnavailable() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "authority-unavailable", 12);
        ReadOnlyShadowSamplingGrantAuthority sampling =
                new FixedSamplingAuthority(
                        new AtomicReference<>(
                                grant(request)),
                        false);
        ReadOnlyShadowKillSwitchAuthority killSwitch =
                new FixedKillSwitchAuthority(
                        new AtomicReference<>(
                                killSwitch(request, true)),
                        true);
        RecordingRunTrust runTrust =
                new RecordingRunTrust(
                        request.accessGrant()
                                .egressAuthorityRef());

        ComposedReadOnlyShadowAccessAuthority authority =
                new ComposedReadOnlyShadowAccessAuthority(
                        sampling,
                        killSwitch,
                        runTrust,
                        Clock.fixed(
                                NOW.plusSeconds(4),
                                ZoneOffset.UTC));

        assertThat(authority.ready()).isFalse();
        assertThatThrownBy(() -> authority.admit(
                permit(request)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .ADMISSION_AUTHORITY_UNAVAILABLE);
    }

    private static ComposedReadOnlyShadowAccessAuthority authority(
            AtomicReference<ReadOnlyShadowSamplingGrantAuthority.Grant>
                    grant,
            AtomicReference<ReadOnlyShadowKillSwitchAuthority.State>
                    killSwitch,
            RecordingRunTrust runTrust,
            AtomicReference<Instant> now) {
        return new ComposedReadOnlyShadowAccessAuthority(
                new FixedSamplingAuthority(grant, true),
                new FixedKillSwitchAuthority(
                        killSwitch, true),
                runTrust,
                clock(now));
    }

    private static Clock clock(
            AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(
                    ZoneId zone) {
                if (!ZoneOffset.UTC.equals(zone)) {
                    throw new IllegalArgumentException(
                            "test clock uses UTC");
                }
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    private static ReadOnlyShadowSamplingGrantAuthority.Grant
    grant(ReadOnlyShadowJobRequest request) {
        return new ReadOnlyShadowSamplingGrantAuthority.Grant(
                request.scope(),
                request.scope(),
                request.accessGrant()
                        .samplingGrantRef(),
                request.accessGrant()
                        .maximumSamples(),
                NOW.minusSeconds(30),
                NOW.plusSeconds(60),
                ReadOnlyShadowJobTestFixtures.ref(
                        "SHADOW_EXECUTION_GUARD_POLICY",
                        "baseline-pressure",
                        '0'),
                LIMITS,
                ReadOnlyShadowJobTestFixtures.ref(
                        "SHADOW_SAMPLING_GRANT_ATTESTATION",
                        "grant-attestation",
                        '1'),
                NOW);
    }

    private static ReadOnlyShadowKillSwitchAuthority.State
    killSwitch(
            ReadOnlyShadowJobRequest request,
            boolean enabled) {
        return new ReadOnlyShadowKillSwitchAuthority.State(
                request.scope(),
                request.accessGrant()
                        .killSwitchRef(),
                enabled,
                NOW.minusSeconds(30),
                NOW.plusSeconds(60),
                ReadOnlyShadowJobTestFixtures.ref(
                        "SHADOW_KILL_SWITCH_ATTESTATION",
                        "kill-switch-attestation",
                        '2'),
                NOW);
    }

    private static ReadOnlyShadowDataPlane.Permit permit(
            ReadOnlyShadowJobRequest request) {
        return new ReadOnlyShadowDataPlane.Permit(
                "shadow-authority-test",
                request,
                1,
                request.deadlineAt(),
                new ReadOnlyShadowDataPlane.ExecutionControl() {
                    @Override
                    public Instant leaseExpiresAt() {
                        return NOW.plusSeconds(30);
                    }

                    @Override
                    public Instant heartbeat() {
                        return NOW.plusSeconds(30);
                    }
                });
    }

    private record FixedSamplingAuthority(
            AtomicReference<ReadOnlyShadowSamplingGrantAuthority.Grant>
                    grant,
            boolean available)
            implements ReadOnlyShadowSamplingGrantAuthority {
        @Override
        public Grant resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef grantRef) {
            return grant.get();
        }
    }

    private record FixedKillSwitchAuthority(
            AtomicReference<ReadOnlyShadowKillSwitchAuthority.State>
                    state,
            boolean available)
            implements ReadOnlyShadowKillSwitchAuthority {
        @Override
        public State resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef killSwitchRef) {
            return state.get();
        }
    }

    private static final class RecordingRunTrust
            implements MirrorDeploymentIsolationRunTrustAuthority {
        private final MirrorArtifactRef egressRef;
        private int admissions;
        private int confirmations;

        private RecordingRunTrust(
                MirrorArtifactRef egressRef) {
            this.egressRef = egressRef;
        }

        @Override
        public MirrorDeploymentIsolationRunTrust.Admission admit(
                CapabilitySnapshot.Scope scope) {
            admissions++;
            return new MirrorDeploymentIsolationRunTrust.Admission(
                    scope,
                    ReadOnlyShadowJobTestFixtures.ref(
                            MirrorDeploymentIsolationAttestationBundle
                                    .ARTIFACT_KIND,
                            "egress-decision",
                            '3'),
                    ReadOnlyShadowJobTestFixtures.ref(
                            MirrorDeploymentIsolationAuthorityKeySetPublication
                                    .ARTIFACT_KIND,
                            "egress-authority",
                            '4'),
                    egressRef,
                    ReadOnlyShadowJobTestFixtures.ref(
                            MirrorDeploymentIsolationAttestationStatusPublication
                                    .ARTIFACT_KIND,
                            "egress-status",
                            '5'),
                    ReadOnlyShadowJobTestFixtures.ref(
                            MirrorDeploymentIsolationAgentSnapshot
                                    .ARTIFACT_KIND,
                            "egress-snapshot",
                            '6'),
                    NOW,
                    NOW.plusSeconds(60));
        }

        @Override
        public MirrorDeploymentIsolationRunTrust.Binding confirm(
                MirrorDeploymentIsolationRunTrust.Admission admission,
                Instant startedAt,
                Instant completedAt) {
            confirmations++;
            return new MirrorDeploymentIsolationRunTrust.Binding(
                    "",
                    admission.decisionRef(),
                    admission.authorityKeySetRef(),
                    admission.attestationRef(),
                    admission.statusRef(),
                    admission.admittedSnapshotRef(),
                    new MirrorArtifactRef(
                            MirrorDeploymentIsolationAgentSnapshot
                                    .ARTIFACT_KIND,
                            admission.admittedSnapshotRef().id(),
                            2,
                            ReadOnlyShadowJobTestFixtures
                                    .fingerprint('7')),
                    admission.admittedAt(),
                    NOW.plusSeconds(4));
        }

        @Override
        public CommitPermit acquireCommitPermit(
                CapabilitySnapshot.Scope scope,
                MirrorDeploymentIsolationRunTrust.Binding binding) {
            return () -> {
            };
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
