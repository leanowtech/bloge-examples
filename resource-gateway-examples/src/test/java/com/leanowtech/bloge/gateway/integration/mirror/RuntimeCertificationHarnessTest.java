package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCertificationHarnessTest {
    private final RuntimeCertificationTestFixtures fixtures =
            new RuntimeCertificationTestFixtures();

    @Test
    void planIsNonExecutingAndShowsCompleteDenominator() {
        FakeAdapter adapter = new FakeAdapter(fixtures, null);
        RuntimeCertificationHarness.Plan plan = harness(
                adapter, new FakeJournal(), regional(true)).plan(fixtures.manifest, adapter);

        assertThat(plan.status()).isEqualTo(RuntimeCertificationHarness.PlanStatus.READY);
        assertThat(plan.scenarios()).hasSize(
                RuntimeCertificationManifest.Scenario.values().length);
        assertThat(plan.findings()).isEmpty();
        assertThat(adapter.calls).hasValue(0);
    }

    @Test
    void runsAllScenariosAndExactReplayDoesNotReapplyFaults() {
        FakeAdapter adapter = new FakeAdapter(fixtures, null);
        FakeJournal journal = new FakeJournal();
        AtomicInteger regionalReads = new AtomicInteger();
        RuntimeCertificationHarness harness = harness(
                adapter, journal, regional(regionalReads, true));

        RuntimeCertificationReport first = harness.execute(command(adapter));
        RuntimeCertificationReport replay = harness.execute(command(adapter));

        assertThat(first.verdict()).isEqualTo(RuntimeCertificationReport.Verdict.CERTIFIED);
        assertThat(first.scenarioResults()).allMatch(value -> value.status()
                == RuntimeCertificationReport.ScenarioStatus.PASSED);
        assertThat(replay).isEqualTo(first);
        assertThat(adapter.calls).hasValue(
                RuntimeCertificationManifest.Scenario.values().length);
        assertThat(journal.appended).hasSize(
                RuntimeCertificationManifest.Scenario.values().length);
        // Exact replay still rechecks current regional trust before serving a stored report.
        assertThat(regionalReads).hasValue(3);
    }

    @Test
    void adapterFailureRemainsBlockedAndAbortsRemainingFaults() {
        FakeAdapter adapter = new FakeAdapter(fixtures,
                RuntimeCertificationManifest.Scenario.POSTGRES_PRIMARY_KILL_AFTER_STAGE);
        FakeJournal journal = new FakeJournal();

        RuntimeCertificationReport report = harness(
                adapter, journal, regional(true)).execute(command(adapter));

        assertThat(report.verdict()).isEqualTo(RuntimeCertificationReport.Verdict.BLOCKED);
        assertThat(report.scenarioResults()).extracting(
                RuntimeCertificationReport.ScenarioResult::status)
                .contains(RuntimeCertificationReport.ScenarioStatus.BLOCKED,
                        RuntimeCertificationReport.ScenarioStatus.ABORTED);
        assertThat(adapter.calls).hasValue(2);
        assertThat(journal.appended).hasSize(
                RuntimeCertificationManifest.Scenario.values().length);
    }

    @Test
    void blocksUnsafeAdapterBeforeClaimOrFault() {
        FakeAdapter adapter = new FakeAdapter(fixtures, null);
        adapter.descriptor = new RuntimeCertificationReport.AdapterDescriptor(
                "", "adapter:unsafe", RuntimeCertificationTestFixtures.fingerprint('1'),
                "customer-platform:sandbox", fixtures.manifest.environmentClass(),
                fixtures.manifest.environmentFingerprint(),
                RuntimeCertificationTestFixtures.scenarios(), false, true, true, true, true);
        FakeJournal journal = new FakeJournal();
        RuntimeCertificationHarness harness = harness(adapter, journal, regional(true));

        assertThat(harness.plan(fixtures.manifest, adapter).findings())
                .contains("ADAPTER_SAFETY_CONTROLS_REJECTED");
        assertThatThrownBy(() -> harness.execute(command(adapter)))
                .isInstanceOf(RuntimeCertificationHarness
                        .RuntimeCertificationRejectedException.class)
                .hasMessageContaining("ADAPTER_SAFETY_CONTROLS_REJECTED");
        assertThat(journal.claims).hasValue(0);
        assertThat(adapter.calls).hasValue(0);
    }

    @Test
    void blocksWhenRegionalCertificationDriftsBeforeAnyFault() {
        FakeAdapter adapter = new FakeAdapter(fixtures, null);
        FakeJournal journal = new FakeJournal();
        RuntimeCertificationHarness harness = harness(adapter, journal, regional(false));

        assertThatThrownBy(() -> harness.execute(command(adapter)))
                .isInstanceOf(RuntimeCertificationHarness
                        .RuntimeCertificationRejectedException.class)
                .hasMessageContaining("REGIONAL_CERTIFICATION_REJECTED");
        assertThat(journal.claims).hasValue(0);
        assertThat(adapter.calls).hasValue(0);
    }

    @Test
    void staleEpochStopsBeforeNextFault() {
        FakeAdapter adapter = new FakeAdapter(fixtures, null);
        FakeJournal journal = new FakeJournal();
        journal.loseLeaseAfterAppend = true;

        assertThatThrownBy(() -> harness(adapter, journal, regional(true))
                .execute(command(adapter)))
                .isInstanceOf(RuntimeCertificationHarness
                        .RuntimeCertificationRejectedException.class)
                .hasMessageContaining("JOURNAL_LEASE_LOST");
        assertThat(adapter.calls).hasValue(1);
    }

    private RuntimeCertificationHarness harness(
            FakeAdapter adapter,
            FakeJournal journal,
            RegionalDataPlaneCertificationAuthority regional) {
        return new RuntimeCertificationHarness(fixtures.integrity,
                fixtures.authorizationSigner, fixtures.reportSigner, regional, journal,
                Clock.fixed(fixtures.now, ZoneOffset.UTC), "harness:replica-1");
    }

    private RuntimeCertificationHarness.ExecutionCommand command(FakeAdapter adapter) {
        return new RuntimeCertificationHarness.ExecutionCommand(
                "runtime-report:sg:3", 5, fixtures.manifest, fixtures.authorization,
                fixtures.regional.certification.artifactRef(),
                fixtures.regional.isolationV2.artifactRef(),
                fixtures.regional.isolationV2.attestation().artifactRef(), adapter,
                "runtime-certification-authority:sg",
                List.of(RuntimeCertificationTestFixtures.ref(
                        "RUNTIME_CERTIFICATION_EVIDENCE_INDEX", "index:sg", 5, 'f')));
    }

    private RegionalDataPlaneCertificationAuthority regional(boolean allow) {
        return regional(new AtomicInteger(), allow);
    }

    private RegionalDataPlaneCertificationAuthority regional(
            AtomicInteger reads, boolean allow) {
        return new RegionalDataPlaneCertificationAuthority() {
            @Override
            public void require(
                    CapabilitySnapshot.Scope scope,
                    MirrorArtifactRef isolationDecisionRef,
                    MirrorArtifactRef isolationAttestationRef,
                    Instant executionStartedAt,
                    Instant executionCompletedAt) {
                reads.incrementAndGet();
                if (!allow) {
                    throw new TrustException("TEST_REJECTED");
                }
            }

            @Override
            public boolean available() {
                return allow;
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("", allow, allow ? "READY" : "UNAVAILABLE",
                        RegionalDataPlaneDeploymentContract.SCHEMA_VERSION,
                        RegionalDataPlaneCertification.SCHEMA_VERSION);
            }
        };
    }

    private static final class FakeAdapter
            implements RuntimeCertificationEnvironmentAdapter {
        private final RuntimeCertificationTestFixtures fixtures;
        private final RuntimeCertificationManifest.Scenario failure;
        private final AtomicInteger calls = new AtomicInteger();
        private RuntimeCertificationReport.AdapterDescriptor descriptor;

        private FakeAdapter(
                RuntimeCertificationTestFixtures fixtures,
                RuntimeCertificationManifest.Scenario failure) {
            this.fixtures = fixtures;
            this.failure = failure;
            this.descriptor = fixtures.adapter;
        }

        @Override
        public RuntimeCertificationReport.AdapterDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public RuntimeCertificationReport.ScenarioResult execute(
                ScenarioExecution request) {
            calls.incrementAndGet();
            if (request.requirement().scenario() == failure) {
                throw new IllegalStateException("injected adapter failure");
            }
            List<RuntimeCertificationReport.InvariantObservation> observations =
                    request.requirement().requiredInvariantCodes().stream()
                            .map(code -> new RuntimeCertificationReport.InvariantObservation(
                                    code, RuntimeCertificationReport.InvariantStatus.PASSED,
                                    List.of(RuntimeCertificationTestFixtures.ref(
                                            "RUNTIME_INVARIANT_PROOF",
                                            request.requirement().scenario().name().toLowerCase()
                                                    + ":" + code.toLowerCase(), 1, '1'))))
                            .toList();
            return new RuntimeCertificationReport.ScenarioResult(
                    request.requirement().scenario(),
                    "attempt:" + request.requirement().scenario().name().toLowerCase(),
                    RuntimeCertificationReport.ScenarioStatus.PASSED,
                    request.requestedAt(), request.requestedAt().plusSeconds(1), true, true,
                    0, 0, RuntimeCertificationTestFixtures.fingerprint('2'),
                    RuntimeCertificationTestFixtures.fingerprint('3'), observations,
                    List.of(RuntimeCertificationTestFixtures.ref(
                            "RUNTIME_SCENARIO_PROOF",
                            request.requirement().scenario().name().toLowerCase(), 1, '4')),
                    "PASSED");
        }
    }

    private static final class FakeJournal
            implements RuntimeCertificationExecutionJournal {
        private final AtomicInteger claims = new AtomicInteger();
        private final List<RuntimeCertificationReport.ScenarioResult> appended =
                new ArrayList<>();
        private RuntimeCertificationReport completed;
        private boolean loseLeaseAfterAppend;

        @Override
        public Claim claimOrResume(
                RunIdentity identity,
                String ownerId,
                Duration leaseDuration,
                Instant now) {
            claims.incrementAndGet();
            if (completed != null) {
                return new Claim(ClaimStatus.COMPLETED, null,
                        completed.authorizationConsumptionRef(), appended, completed,
                        "EXACT_REPLAY");
            }
            return new Claim(appended.isEmpty() ? ClaimStatus.ACQUIRED : ClaimStatus.RESUMED,
                    new Lease(identity.runId(), ownerId, 1, now.plus(leaseDuration)),
                    RuntimeCertificationTestFixtures.ref(
                            "RUNTIME_CERTIFICATION_AUTHORIZATION_CONSUMPTION",
                            identity.authorizationRef().id(), 1, 'e'),
                    appended, null, appended.isEmpty() ? "ACQUIRED" : "RESUMED");
        }

        @Override
        public Lease heartbeat(Lease lease, Duration leaseDuration, Instant now) {
            if (loseLeaseAfterAppend && !appended.isEmpty()) {
                throw new LeaseLostException("test lease lost");
            }
            return new Lease(lease.runId(), lease.ownerId(), lease.epoch(),
                    now.plus(leaseDuration));
        }

        @Override
        public void appendScenario(
                Lease lease, RuntimeCertificationReport.ScenarioResult result) {
            appended.add(result);
        }

        @Override
        public void complete(Lease lease, RuntimeCertificationReport report) {
            completed = report;
        }
    }
}
