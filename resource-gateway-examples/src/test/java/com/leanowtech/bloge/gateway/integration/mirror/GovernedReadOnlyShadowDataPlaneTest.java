package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedReadOnlyShadowDataPlaneTest {
    private static final Instant NOW =
            ReadOnlyShadowJobTestFixtures.NOW;

    @Test
    void executesThePairedObservationBehindAuthorityLeaseAndGuardFences() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "governed-success", 20);
        List<String> events = new ArrayList<>();
        AtomicInteger heartbeats =
                new AtomicInteger();
        FixtureAuthority authority =
                new FixtureAuthority(request, events);
        FixtureGuard guard =
                new FixtureGuard(events);
        GovernedReadOnlyShadowDataPlane dataPlane =
                dataPlane(
                        authority,
                        guard,
                        connector(
                                request,
                                ReadOnlyShadowComparison
                                        .SourceRole.BASELINE,
                                "SHADOW_BASELINE_OBSERVATION",
                                'a',
                                events),
                        connector(
                                request,
                                ReadOnlyShadowComparison
                                        .SourceRole.CANDIDATE,
                                "MIRROR_EVIDENCE_BUNDLE",
                                'b',
                                events),
                        verifier(events),
                        comparisonEngine(events));

        ReadOnlyShadowDataPlane.ExecutionResult result =
                dataPlane.execute(
                        permit(
                                request,
                                heartbeats,
                                events));

        assertThat(result.accessProof())
                .isEqualTo(
                        request.accessGrant()
                                .zeroWriteProof());
        assertThat(result.authorityProof()
                .guardPolicyAttestationRef()
                .kind()).isEqualTo(
                "SHADOW_EXECUTION_GUARD_POLICY_ATTESTATION");
        assertThat(result.authorityProof()
                .samplingGrantAttestationRef()
                .kind()).isEqualTo(
                "SHADOW_SAMPLING_GRANT_ATTESTATION");
        assertThat(result.authorityProof()
                .killSwitchAttestationRef()
                .kind()).isEqualTo(
                "SHADOW_KILL_SWITCH_ATTESTATION");
        assertThat(result.baseline().role())
                .isEqualTo(
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE);
        assertThat(result.candidate().role())
                .isEqualTo(
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE);
        assertThat(result.results())
                .singleElement()
                .extracting(
                        ReadOnlyShadowComparison
                                .DimensionComparison::outcome)
                .isEqualTo(
                        ReadOnlyShadowComparison
                                .DiffOutcome.MATCH);
        assertThat(heartbeats).hasValue(5);
        assertThat(events).containsExactly(
                "heartbeat",
                "authority.admit",
                "guard.acquire",
                "heartbeat",
                "guard.renew",
                "baseline.observe",
                "heartbeat",
                "guard.renew",
                "candidate.observe",
                "heartbeat",
                "guard.renew",
                "authority.confirm",
                "heartbeat",
                "guard.renew",
                "sources.verify",
                "comparison.compare",
                "guard.succeeded",
                "guard.close");
        assertThat(dataPlane.ready()).isTrue();
    }

    @Test
    void rejectsWriteCredentialsBeforeTheCandidateCanRun() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "governed-write-credential", 21);
        List<String> events = new ArrayList<>();
        FixtureConnector baseline =
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE,
                        "SHADOW_BASELINE_OBSERVATION",
                        'a',
                        events);
        baseline.writeCredentialExposed = true;

        assertThatThrownBy(() -> dataPlane(
                new FixtureAuthority(request, events),
                new FixtureGuard(events),
                baseline,
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        'b',
                        events),
                verifier(events),
                comparisonEngine(events))
                .execute(permit(
                        request,
                        new AtomicInteger(),
                        events)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .WRITE_CAPABILITY_DETECTED);
        assertThat(events)
                .contains("guard.failed:WRITE_CAPABILITY_DETECTED")
                .doesNotContain("candidate.observe")
                .endsWith("guard.close");
    }

    @Test
    void rejectsMeasuredWriteAttemptsBeforeTheCandidateCanRun() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "governed-write-attempt", 22);
        List<String> events = new ArrayList<>();
        FixtureConnector baseline =
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE,
                        "SHADOW_BASELINE_OBSERVATION",
                        'a',
                        events);
        baseline.writeAttemptCount = 1;

        assertThatThrownBy(() -> dataPlane(
                new FixtureAuthority(request, events),
                new FixtureGuard(events),
                baseline,
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        'b',
                        events),
                verifier(events),
                comparisonEngine(events))
                .execute(permit(
                        request,
                        new AtomicInteger(),
                        events)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .WRITE_ATTEMPT_DETECTED);
        assertThat(events)
                .contains("guard.failed:WRITE_ATTEMPT_DETECTED")
                .doesNotContain("candidate.observe")
                .endsWith("guard.close");
    }

    @Test
    void rejectsDifferentRequestContextsBeforeSourceResolution() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "governed-context-drift", 23);
        List<String> events = new ArrayList<>();
        FixtureConnector candidate =
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        'b',
                        events);
        candidate.requestContextFingerprint =
                ReadOnlyShadowJobTestFixtures
                        .fingerprint('9');

        assertThatThrownBy(() -> dataPlane(
                new FixtureAuthority(request, events),
                new FixtureGuard(events),
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE,
                        "SHADOW_BASELINE_OBSERVATION",
                        'a',
                        events),
                candidate,
                verifier(events),
                comparisonEngine(events))
                .execute(permit(
                        request,
                        new AtomicInteger(),
                        events)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
        assertThat(events)
                .doesNotContain("sources.verify")
                .doesNotContain("comparison.compare");
    }

    @Test
    void independentlyRejectsTerminalAuthorityDrift() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "governed-authority-drift", 24);
        List<String> events = new ArrayList<>();
        FixtureAuthority authority =
                new FixtureAuthority(request, events);
        authority.terminalGrantDrift = true;

        assertThatThrownBy(() -> dataPlane(
                authority,
                new FixtureGuard(events),
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE,
                        "SHADOW_BASELINE_OBSERVATION",
                        'a',
                        events),
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        'b',
                        events),
                verifier(events),
                comparisonEngine(events))
                .execute(permit(
                        request,
                        new AtomicInteger(),
                        events)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
        assertThat(events)
                .contains("authority.confirm")
                .doesNotContain("sources.verify")
                .doesNotContain("comparison.compare");
    }

    @Test
    void stopsBeforeConnectorsWhenTheSharedGuardDeniesBudget() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "governed-budget-denied", 25);
        List<String> events = new ArrayList<>();
        FixtureGuard guard =
                new FixtureGuard(events);
        guard.acquireFailure =
                ReadOnlyShadowDataPlane.FailureReason
                        .BUDGET_EXHAUSTED;

        assertThatThrownBy(() -> dataPlane(
                new FixtureAuthority(request, events),
                guard,
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE,
                        "SHADOW_BASELINE_OBSERVATION",
                        'a',
                        events),
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        'b',
                        events),
                verifier(events),
                comparisonEngine(events))
                .execute(permit(
                        request,
                        new AtomicInteger(),
                        events)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .BUDGET_EXHAUSTED);
        assertThat(events).containsExactly(
                "heartbeat",
                "authority.admit",
                "guard.acquire");
    }

    @Test
    void failsClosedWithoutConsumingWorkWhenAnyDeepDependencyIsUnready() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "governed-unready", 26);
        List<String> events = new ArrayList<>();
        FixtureConnector candidate =
                connector(
                        request,
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        "MIRROR_EVIDENCE_BUNDLE",
                        'b',
                        events);
        candidate.ready = false;
        GovernedReadOnlyShadowDataPlane dataPlane =
                dataPlane(
                        new FixtureAuthority(
                                request, events),
                        new FixtureGuard(events),
                        connector(
                                request,
                                ReadOnlyShadowComparison
                                        .SourceRole.BASELINE,
                                "SHADOW_BASELINE_OBSERVATION",
                                'a',
                                events),
                        candidate,
                        verifier(events),
                        comparisonEngine(events));

        assertThat(dataPlane.ready()).isFalse();
        assertThatThrownBy(() -> dataPlane.execute(
                permit(
                        request,
                        new AtomicInteger(),
                        events)))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .CANDIDATE_RUNTIME_UNAVAILABLE);
        assertThat(events).isEmpty();
    }

    private static GovernedReadOnlyShadowDataPlane dataPlane(
            ReadOnlyShadowAccessAuthority authority,
            ReadOnlyShadowExecutionGuard guard,
            ReadOnlyShadowBaselineConnector baseline,
            ReadOnlyShadowCandidateConnector candidate,
            ReadOnlyShadowSourceResolutionVerifier verifier,
            ReadOnlyShadowComparisonEngine comparisonEngine) {
        return new GovernedReadOnlyShadowDataPlane(
                authority,
                guard,
                baseline,
                candidate,
                verifier,
                comparisonEngine,
                Clock.fixed(
                        NOW.plusSeconds(10),
                        ZoneOffset.UTC));
    }

    private static ReadOnlyShadowDataPlane.Permit permit(
            ReadOnlyShadowJobRequest request,
            AtomicInteger heartbeats,
            List<String> events) {
        return new ReadOnlyShadowDataPlane.Permit(
                "shadow-governed-test",
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
                        heartbeats.incrementAndGet();
                        events.add("heartbeat");
                        return NOW.plusSeconds(45);
                    }
                });
    }

    private static FixtureConnector connector(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowComparison.SourceRole role,
            String kind,
            char fingerprint,
            List<String> events) {
        return new FixtureConnector(
                role,
                observation(
                        request,
                        role,
                        kind,
                        fingerprint),
                events);
    }

    private static ReadOnlyShadowSourceResolutionVerifier verifier(
            List<String> events) {
        return new ReadOnlyShadowSourceResolutionVerifier() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public MirrorArtifactRef verify(
                    Verification verification) {
                events.add("sources.verify");
                return ReadOnlyShadowJobTestFixtures.ref(
                        "SHADOW_SOURCE_RESOLUTION_ATTESTATION",
                        "resolved-sources",
                        'c');
            }
        };
    }

    private static ReadOnlyShadowComparisonEngine comparisonEngine(
            List<String> events) {
        return new ReadOnlyShadowComparisonEngine() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public List<ReadOnlyShadowComparison.DimensionComparison>
            compare(
                    MirrorArtifactRef comparisonPolicyRef,
                    ReadOnlyShadowConnectorObservation baseline,
                    ReadOnlyShadowConnectorObservation candidate) {
                events.add("comparison.compare");
                String fact = baseline.normalizedFactFingerprints()
                        .get(DomainFidelityProfile
                                .Dimension.BEHAVIOR);
                return List.of(
                        new ReadOnlyShadowComparison
                                .DimensionComparison(
                                DomainFidelityProfile
                                        .Dimension.BEHAVIOR,
                                fact,
                                candidate
                                        .normalizedFactFingerprints()
                                        .get(DomainFidelityProfile
                                                .Dimension.BEHAVIOR),
                                ReadOnlyShadowComparison
                                        .DiffOutcome.MATCH,
                                List.of()));
            }
        };
    }

    private static ReadOnlyShadowComparison.SourceObservation
    observation(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowComparison.SourceRole role,
            String kind,
            char material) {
        return new ReadOnlyShadowComparison.SourceObservation(
                role,
                ReadOnlyShadowJobTestFixtures.ref(
                        kind,
                        role.name().toLowerCase(),
                        material),
                request.scope(),
                request.targetCapabilityRef(),
                ReadOnlyShadowJobTestFixtures
                        .fingerprint('e'),
                ReadOnlyShadowJobTestFixtures
                        .fingerprint(material),
                NOW.plusSeconds(5),
                MirrorRunEvidence
                        .EvidenceClass.CERTIFIABLE,
                true);
    }

    private static final class FixtureAuthority
            implements ReadOnlyShadowAccessAuthority {
        private final ReadOnlyShadowJobRequest request;
        private final List<String> events;
        private boolean terminalGrantDrift;

        private FixtureAuthority(
                ReadOnlyShadowJobRequest request,
                List<String> events) {
            this.request = request;
            this.events = events;
        }

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public Admission admit(
                ReadOnlyShadowDataPlane.Permit permit) {
            events.add("authority.admit");
            return admission(request);
        }

        @Override
        public Confirmation confirm(
                Admission admission,
                Instant startedAt,
                Instant completedAt) {
            events.add("authority.confirm");
            Confirmation exact =
                    confirmation(admission);
            if (!terminalGrantDrift) {
                return exact;
            }
            ReadOnlyShadowSamplingGrantAuthority.Grant grant =
                    exact.samplingGrant();
            return new Confirmation(
                    exact.admissionFingerprint(),
                    new ReadOnlyShadowSamplingGrantAuthority.Grant(
                            grant.scope(),
                            grant.guardScope(),
                            grant.grantRef(),
                            grant.maximumSamples(),
                            grant.validFrom(),
                            grant.expiresAt(),
                            grant.guardPolicyRef(),
                            new ReadOnlyShadowExecutionGuard.Limits(
                                    2,
                                    60,
                                    Duration.ofMinutes(1),
                                    3,
                                    Duration.ofSeconds(30)),
                            grant.authorityAttestationRef(),
                            grant.guardPolicyAttestationRef(),
                            NOW.plusSeconds(8)),
                    exact.killSwitch(),
                    exact.egressBinding(),
                    exact.confirmedAt());
        }
    }

    private static final class FixtureGuard
            implements ReadOnlyShadowExecutionGuard {
        private final List<String> events;
        private ReadOnlyShadowDataPlane.FailureReason
                acquireFailure;

        private FixtureGuard(
                List<String> events) {
            this.events = events;
        }

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public Lease acquire(
                ReadOnlyShadowDataPlane.Permit permit,
                ReadOnlyShadowAccessAuthority.Admission admission) {
            events.add("guard.acquire");
            if (acquireFailure != null) {
                throw new ReadOnlyShadowDataPlane
                        .Failure(acquireFailure);
            }
            return new Lease() {
                @Override
                public void renew(
                        Instant leaseExpiresAt) {
                    events.add("guard.renew");
                }

                @Override
                public void succeeded() {
                    events.add("guard.succeeded");
                }

                @Override
                public void failed(
                        ReadOnlyShadowDataPlane
                                .FailureReason reason) {
                    events.add(
                            "guard.failed:" + reason.name());
                }

                @Override
                public void close() {
                    events.add("guard.close");
                }
            };
        }
    }

    private static final class FixtureConnector
            implements ReadOnlyShadowBaselineConnector,
            ReadOnlyShadowCandidateConnector {
        private final ReadOnlyShadowComparison.SourceRole role;
        private final ReadOnlyShadowComparison.SourceObservation
                observation;
        private final List<String> events;
        private boolean ready = true;
        private boolean writeCredentialExposed;
        private long writeAttemptCount;
        private String requestContextFingerprint;

        private FixtureConnector(
                ReadOnlyShadowComparison.SourceRole role,
                ReadOnlyShadowComparison.SourceObservation
                        observation,
                List<String> events) {
            this.role = role;
            this.observation = observation;
            this.events = events;
            this.requestContextFingerprint =
                    observation.requestContextFingerprint();
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public ReadOnlyShadowConnectorObservation observe(
                ReadOnlyShadowConnectorInvocation invocation) {
            events.add(
                    role == ReadOnlyShadowComparison
                            .SourceRole.BASELINE
                            ? "baseline.observe"
                            : "candidate.observe");
            ReadOnlyShadowComparison.SourceObservation exact =
                    new ReadOnlyShadowComparison.SourceObservation(
                            observation.role(),
                            observation.artifactRef(),
                            observation.scope(),
                            observation.targetCapabilityRef(),
                            requestContextFingerprint,
                            observation.semanticResultFingerprint(),
                            observation.completedAt(),
                            observation.evidenceClass(),
                            observation.evidenceComplete());
            return new ReadOnlyShadowConnectorObservation(
                    exact,
                    invocation.request()
                            .comparisonPolicyRef(),
                    Map.of(
                            DomainFidelityProfile
                                    .Dimension.BEHAVIOR,
                            ReadOnlyShadowJobTestFixtures
                                    .fingerprint('d')),
                    writeCredentialExposed,
                    writeAttemptCount);
        }
    }

    private static ReadOnlyShadowAccessAuthority.Admission
    admission(ReadOnlyShadowJobRequest request) {
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
                                "baseline-pressure",
                                '0'),
                        limits,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_SAMPLING_GRANT_ATTESTATION",
                                request.accessGrant()
                                        .samplingGrantRef()
                                        .id(),
                                '1'),
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_EXECUTION_GUARD_POLICY_ATTESTATION",
                                "baseline-pressure",
                                '2'),
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
                                '2'),
                        NOW);
        MirrorDeploymentIsolationRunTrust.Admission egress =
                new MirrorDeploymentIsolationRunTrust.Admission(
                        request.scope(),
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
                        request.accessGrant()
                                .egressAuthorityRef(),
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
        return new ReadOnlyShadowAccessAuthority.Admission(
                ReadOnlyShadowJobTestFixtures
                        .fingerprint('f'),
                request.accessGrant().zeroWriteProof(),
                limits,
                grant,
                killSwitch,
                egress,
                NOW,
                NOW.plusSeconds(60));
    }

    private static ReadOnlyShadowAccessAuthority.Confirmation
    confirmation(
            ReadOnlyShadowAccessAuthority.Admission admission) {
        MirrorArtifactRef admittedSnapshot =
                admission.egressAdmission()
                        .admittedSnapshotRef();
        MirrorDeploymentIsolationRunTrust.Binding binding =
                new MirrorDeploymentIsolationRunTrust.Binding(
                        "",
                        admission.egressAdmission()
                                .decisionRef(),
                        admission.egressAdmission()
                                .authorityKeySetRef(),
                        admission.egressAdmission()
                                .attestationRef(),
                        admission.egressAdmission()
                                .statusRef(),
                        admittedSnapshot,
                        new MirrorArtifactRef(
                                admittedSnapshot.kind(),
                                admittedSnapshot.id(),
                                2,
                                ReadOnlyShadowJobTestFixtures
                                        .fingerprint('7')),
                        admission.admittedAt(),
                        NOW.plusSeconds(9));
        return new ReadOnlyShadowAccessAuthority.Confirmation(
                admission.admissionFingerprint(),
                admission.samplingGrant(),
                admission.killSwitch(),
                binding,
                NOW.plusSeconds(9));
    }
}
