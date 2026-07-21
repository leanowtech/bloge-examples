package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationConvergenceMonitorTest {

    private static final String TARGET =
            ControlPlaneCertificateRotationTargets.TEST_SECRET_NOTARY;
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String EVENT_FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final String SETTINGS_FINGERPRINT = "sha256:" + "c".repeat(64);
    private static final char[] PASSWORD = "test-transport-password".toCharArray();

    @TempDir
    Path temporaryDirectory;

    @Test
    void databaseDueAllReplicaAdmissionAndFinalActiveProofGateTheNewGeneration()
            throws Exception {
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "convergence-current");
        var successor = current.rotateClient(temporaryDirectory, "convergence-next");
        var policy = policy(ControlPlaneCertificateRotationFleetPolicy.ActivationMode
                .ALL_REPLICAS, true);
        var repository = new FleetRepository(policy, clock);
        try (var monitor = new ControlPlaneCertificateRotationConvergenceMonitor(
                repository, policy, new ObjectMapper(), clock, false)) {
            var gate = monitor.activationGate(TARGET);
            var transport = transport(1, current, clock, gate);
            monitor.registerTarget(TARGET, transport);
            ControlPlaneCertificateRotationEvent event = event(now.plusSeconds(2));

            monitor.prepare(event);
            transport.stage(2, event.material().activateAt(), settings(successor));
            monitor.applied(event);
            repository.fleetReady = true;

            monitor.refreshNow();
            assertThat(transport.localActiveGeneration()).isEqualTo(1);
            assertThat(gate.activationPermitted(2, event.material().activateAt())).isFalse();

            clock.advance(Duration.ofSeconds(2));
            monitor.refreshNow();

            assertThat(transport.localActiveGeneration()).isEqualTo(2);
            assertThat(transport.pendingGeneration()).isEmpty();
            assertThat(gate.servingPermitted(2)).isTrue();
            assertThat(repository.acknowledgements).extracting(
                            ControlPlaneCertificateRotationConvergenceRepository
                                    .Acknowledgement::state)
                    .contains(ControlPlaneCertificateRotationConvergenceRepository
                                    .ReplicaState.STAGED,
                            ControlPlaneCertificateRotationConvergenceRepository
                                    .ReplicaState.ACTIVE);
            assertThat(repository.acknowledgements.getLast().sequence()).isEqualTo(2);
            assertThat(monitor.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.replicaConvergenceProven()).isTrue();
                assertThat(descriptor.servingReady()).isTrue();
                assertThat(descriptor.status()).isEqualTo("CONVERGED");
            });
        }
    }

    @Test
    void stoppedHeartbeatExpiresCachedServingAndActivationWithoutRequestPathIo()
            throws Exception {
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "convergence-expiry-current");
        var successor = current.rotateClient(temporaryDirectory, "convergence-expiry-next");
        var policy = policy(ControlPlaneCertificateRotationFleetPolicy.ActivationMode
                .ALL_REPLICAS, true);
        var repository = new FleetRepository(policy, clock);
        try (var monitor = new ControlPlaneCertificateRotationConvergenceMonitor(
                repository, policy, new ObjectMapper(), clock, false)) {
            var gate = monitor.activationGate(TARGET);
            var transport = transport(1, current, clock, gate);
            monitor.registerTarget(TARGET, transport);
            ControlPlaneCertificateRotationEvent event = event(now.plusSeconds(10));
            monitor.prepare(event);
            transport.stage(2, event.material().activateAt(), settings(successor));
            monitor.applied(event);

            assertThat(gate.servingPermitted(1)).isTrue();
            repository.unavailable = true;
            clock.advance(Duration.ofSeconds(2));
            monitor.refreshNow();

            assertThat(gate.servingPermitted(1)).isFalse();
            assertThat(gate.activationPermitted(2, event.material().activateAt())).isFalse();
            assertThat(monitor.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isFalse();
                assertThat(descriptor.status()).isEqualTo(
                        "CONVERGENCE_LEASE_UNAVAILABLE");
            });
        }
    }

    @Test
    void durableActivationWithLocalPromotionFailurePublishesFailedState()
            throws Exception {
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "convergence-failed-current");
        var successor = current.rotateClient(temporaryDirectory, "convergence-failed-next");
        var policy = policy(ControlPlaneCertificateRotationFleetPolicy.ActivationMode
                .ALL_REPLICAS, true);
        var repository = new FleetRepository(policy, clock);
        try (var monitor = new ControlPlaneCertificateRotationConvergenceMonitor(
                repository, policy, new ObjectMapper(), clock, false)) {
            var gate = monitor.activationGate(TARGET);
            var transport = transport(1, current, clock, gate);
            monitor.registerTarget(TARGET, transport);
            ControlPlaneCertificateRotationEvent event = event(now.plusSeconds(2));

            monitor.prepare(event);
            transport.stage(2, event.material().activateAt(), settings(successor));
            monitor.applied(event);
            repository.fleetReady = true;
            clock.advance(Duration.ofSeconds(2));
            clock.failOnceOnCall(4);

            monitor.refreshNow();

            assertThat(transport.localActiveGeneration()).isEqualTo(1);
            assertThat(repository.acknowledgements.getLast()).satisfies(acknowledgement -> {
                assertThat(acknowledgement.state()).isEqualTo(
                        ControlPlaneCertificateRotationConvergenceRepository
                                .ReplicaState.FAILED);
                assertThat(acknowledgement.failureCode()).isEqualTo(
                        "LOCAL_ACTIVATION_FAILED");
            });
            assertThat(gate.servingPermitted(2)).isFalse();
            assertThat(monitor.descriptor().blockedTargetCount()).isEqualTo(1);
        }
    }

    @Test
    void restoredSignedActiveGenerationCannotServeBeforeFreshFleetConvergence()
            throws Exception {
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "convergence-restored");
        var policy = policy(ControlPlaneCertificateRotationFleetPolicy.ActivationMode
                .ALL_REPLICAS, true);
        var repository = new FleetRepository(policy, clock);
        try (var monitor = new ControlPlaneCertificateRotationConvergenceMonitor(
                repository, policy, new ObjectMapper(), clock, false)) {
            var gate = monitor.activationGate(TARGET);
            var transport = transport(2, current, clock, gate);
            monitor.registerTarget(TARGET, transport);
            var expected = expected(now.minusSeconds(10));

            monitor.restoreActive(expected);
            assertThat(gate.servingPermitted(2)).isFalse();

            repository.fleetReady = true;
            monitor.refreshNow();

            assertThat(gate.servingPermitted(2)).isTrue();
            assertThat(monitor.descriptor().replicaConvergenceProven()).isTrue();
            monitor.close();
            assertThat(gate.servingPermitted(2)).isFalse();
            assertThat(monitor.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isFalse();
                assertThat(descriptor.servingReady()).isFalse();
                assertThat(descriptor.status()).isEqualTo("CLOSED");
            });
        }
    }

    @Test
    void quorumAndUnsignedMultiReplicaPoliciesFailBeforeAHeartbeatStarts() {
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var quorum = policy(ControlPlaneCertificateRotationFleetPolicy.ActivationMode
                .FENCED_QUORUM, true);
        var unsigned = policy(ControlPlaneCertificateRotationFleetPolicy.ActivationMode
                .ALL_REPLICAS, false);

        assertThatThrownBy(() -> new ControlPlaneCertificateRotationConvergenceMonitor(
                new FleetRepository(quorum, clock), quorum, new ObjectMapper(), clock, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lacks a serving fence");
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationConvergenceMonitor(
                new FleetRepository(unsigned, clock), unsigned, new ObjectMapper(), clock, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory is not attested");
    }

    private ControlPlaneCertificateRotationFleetPolicy policy(
            ControlPlaneCertificateRotationFleetPolicy.ActivationMode mode,
            boolean attested) {
        Set<String> replicas = mode
                == ControlPlaneCertificateRotationFleetPolicy.ActivationMode.FENCED_QUORUM
                ? Set.of("replica-a", "replica-b", "replica-c")
                : Set.of("replica-a", "replica-b");
        int required = mode
                == ControlPlaneCertificateRotationFleetPolicy.ActivationMode.FENCED_QUORUM
                ? 2 : replicas.size();
        var inventory = attested
                ? new ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation(
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation.SCHEMA_VERSION,
                true, "DEPLOYMENT_SIGNED", 7, "sha256:" + "d".repeat(64),
                "sha256:" + "e".repeat(64), Instant.now().plusSeconds(3_600))
                : ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                .localConfigured();
        return new ControlPlaneCertificateRotationFleetPolicy(
                "deployment-a", "fleet-2026-07", "replica-a",
                UUID.randomUUID().toString(), FINGERPRINT, replicas,
                "tool-studio-v1", mode, required, Duration.ofSeconds(1),
                Duration.ofSeconds(3), Duration.ofHours(1), inventory);
    }

    private RotatingControlPlaneHttpTransport transport(
            long generation,
            RecoveryFleetPublicationTlsFixture.Material material,
            Clock clock,
            RotatingControlPlaneHttpTransport.ActivationGate gate) {
        return new RotatingControlPlaneHttpTransport(
                generation, settings(material), reference -> PASSWORD.clone(), clock,
                Duration.ofMinutes(1), Duration.ofHours(1), gate);
    }

    private static ControlPlaneCertificateRotationEvent event(Instant activateAt) {
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "rotation-trust", "rotation-event-2", "deployment-a", TARGET, 2,
                FINGERPRINT, "material-2", SETTINGS_FINGERPRINT, FINGERPRINT,
                activateAt.minusSeconds(2), activateAt.minusSeconds(2), activateAt,
                activateAt.plusSeconds(30));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                EVENT_FINGERPRINT, List.of(new ControlPlaneCertificateRotationEvent
                .AuthoritySignature("authority-a", "key-a", "Ed25519",
                activateAt.minusSeconds(2), Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation expected(
            Instant activateAt) {
        return new ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation(
                TARGET, 2, "rotation-event-2", EVENT_FINGERPRINT,
                SETTINGS_FINGERPRINT, activateAt);
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings(
            RecoveryFleetPublicationTlsFixture.Material material) {
        String issuer = PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                material.certificateAuthority());
        var policy = new ControlPlaneCertificateIdentityPolicy(
                material.clientCertificate().getSubjectX500Principal().getName(),
                material.clientUriSan(), Set.of(issuer), material.serverUriSan(),
                Set.of(issuer));
        return new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                material.trustStore(), "test:trust", material.clientKeyStore(), "test:client",
                Set.of(PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate())), policy);
    }

    private static final class FleetRepository
            implements ControlPlaneCertificateRotationConvergenceRepository {
        private final ControlPlaneCertificateRotationFleetPolicy policy;
        private final Clock clock;
        private final List<Acknowledgement> acknowledgements = new ArrayList<>();
        private volatile boolean fleetReady;
        private volatile boolean unavailable;

        private FleetRepository(
                ControlPlaneCertificateRotationFleetPolicy policy,
                Clock clock) {
            this.policy = policy;
            this.clock = clock;
        }

        @Override
        public synchronized Snapshot acknowledge(Acknowledgement acknowledgement) {
            if (unavailable) {
                throw new IllegalStateException("database unavailable");
            }
            acknowledgements.add(acknowledgement);
            return snapshotFor(acknowledgement.state());
        }

        @Override
        public synchronized Snapshot snapshot(ExpectedRotation expectedRotation) {
            if (unavailable) {
                throw new IllegalStateException("database unavailable");
            }
            ReplicaState state = acknowledgements.isEmpty()
                    ? ReplicaState.STAGED : acknowledgements.getLast().state();
            return snapshotFor(state);
        }

        @Override
        public void withdraw(String instanceId, String startupId) {
        }

        private Snapshot snapshotFor(ReplicaState state) {
            int expected = policy.expectedInstanceIds().size();
            int required = policy.requiredStagedReplicas();
            Instant observed = clock.instant();
            if (fleetReady && state == ReplicaState.ACTIVE) {
                return new Snapshot(Snapshot.SCHEMA_VERSION, true, true, "CONVERGED",
                        expected, required, expected, 0, expected, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, observed, observed.plusSeconds(3),
                        List.of(), List.of());
            }
            if (fleetReady && state == ReplicaState.STAGED) {
                return new Snapshot(Snapshot.SCHEMA_VERSION, true, false,
                        "ACTIVATION_PERMITTED", expected, required, expected, required,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, observed,
                        observed.plusSeconds(3), List.of(),
                        List.of("REPLICA_STILL_STAGED", "REPLICA_NOT_ACTIVE"));
            }
            int failed = state == ReplicaState.FAILED ? 1 : 0;
            List<String> activation = failed > 0
                    ? List.of("REPLICA_FAILED", "REPLICA_MISSING",
                    "STAGING_THRESHOLD_UNMET")
                    : List.of("REPLICA_MISSING", "STAGING_THRESHOLD_UNMET");
            return new Snapshot(Snapshot.SCHEMA_VERSION, false, false,
                    activation.getFirst(), expected, required, 1,
                    state == ReplicaState.STAGED ? 1 : 0,
                    state == ReplicaState.ACTIVE ? 1 : 0, failed,
                    expected - 1, 0, 0, 0, 0, 0, 0, 0, observed,
                    observed.plusSeconds(3), activation,
                    concat(activation, "REPLICA_NOT_ACTIVE"));
        }

        private static List<String> concat(List<String> values, String extra) {
            ArrayList<String> result = new ArrayList<>(values);
            result.add(extra);
            return List.copyOf(result);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final AtomicInteger callsUntilFailure = new AtomicInteger(-1);

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        private void failOnceOnCall(int call) {
            callsUntilFailure.set(call);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            int remaining = callsUntilFailure.get();
            if (remaining > 0 && callsUntilFailure.decrementAndGet() == 0) {
                callsUntilFailure.set(-1);
                throw new IllegalStateException("local clock unavailable");
            }
            return instant.get();
        }
    }
}
