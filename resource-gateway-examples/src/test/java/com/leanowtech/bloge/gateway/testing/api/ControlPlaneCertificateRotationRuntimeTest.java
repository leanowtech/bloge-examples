package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateRotationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationRuntimeTest {

    private static final String TARGET =
            ControlPlaneCertificateRotationTargets.BOOTSTRAP_ROOT_PUBLISHER;
    private static final String POLICY = "sha256:" + "c".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void productRuntimeStagesAndActivatesCatalogMaterialOnAStableRealTlsClient()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-current");
        var successor = current.rotateClient(temporaryDirectory, "runtime-next");
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(objectMapper);
        var currentSettings = settings(current);
        var successorSettings = settings(successor);
        String currentFingerprint = fingerprinter.fingerprint(currentSettings);
        String successorFingerprint = fingerprinter.fingerprint(successorSettings);
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var runtime = new ControlPlaneCertificateRotationRuntime(
                enabledProperties(), Map.of(TARGET, initial(41)), verifiedTrust(),
                (targetId, generation, materialId) ->
                        new ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial(
                                successorFingerprint, successorSettings),
                reference -> RecoveryFleetPublicationTlsFixture.password(),
                fingerprinter, floorFactory(), clock);

        RecoveryFleetPublicationTransport transport = runtime.transport(
                TARGET, transportProperties(current));
        var client = transport.client(Duration.ofSeconds(2));
        AtomicReference<String> peer = new AtomicReference<>();

        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(current, peer)) {
            assertThat(send(client, server.uri())).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-runtime-current");

            var result = runtime.apply(event(41, currentFingerprint,
                    successorFingerprint, now.plusSeconds(10)));

            assertThat(result.status()).isEqualTo(
                    ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);
            assertThat(result.pendingGeneration()).isEqualTo(42);
            assertThat(runtime.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.enabled()).isTrue();
                assertThat(descriptor.ready()).isTrue();
                assertThat(descriptor.inventoriedTargetCount()).isEqualTo(1);
                assertThat(descriptor.registeredTargetCount()).isEqualTo(1);
                assertThat(descriptor.synchronizedState()).isTrue();
            });

            clock.advance(Duration.ofSeconds(10));
            assertThat(send(client, server.uri())).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-runtime-next");
        }
    }

    @Test
    void productRuntimeFencesRealRequestsUntilDurableExactStatusIsCached()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-status-gate");
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(objectMapper);
        String currentFingerprint = fingerprinter.fingerprint(settings(current));
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var admission = new ControlPlaneCertificateStatusAdmission(clock, System::nanoTime);
        var statusFloor = new FixedStatusFloor(statusSnapshot(
                now, currentFingerprint, goodTargetStatus(41, currentFingerprint)));
        var statusMonitor = new ControlPlaneCertificateStatusMonitor(statusFloor,
                cursor -> ControlPlaneCertificateStatusSource.FetchResult.unavailable(
                        "STATUS_AUTHORITY_UNAVAILABLE"), admission, clock, 1);
        var runtime = new ControlPlaneCertificateRotationRuntime(
                enabledProperties(), Map.of(TARGET, initial(41)), verifiedTrust(),
                (targetId, generation, materialId) -> {
                    throw new AssertionError("material resolution must not run");
                }, reference -> RecoveryFleetPublicationTlsFixture.password(),
                fingerprinter, floorFactory(), clock, null, statusMonitor, admission);

        RecoveryFleetPublicationTransport transport = runtime.transport(
                TARGET, transportProperties(current));
        var client = transport.client(Duration.ofSeconds(2));

        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(
                current, new AtomicReference<>())) {
            assertThatThrownBy(() -> send(client, server.uri()))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Control-plane client identity is unavailable")
                    .hasRootCauseMessage(
                            "Control-plane client identity has no fresh certificate status");

            ControlPlaneCertificateStatusMonitor.Descriptor refreshed =
                    statusMonitor.refresh();

            assertThat(refreshed.status()).isEqualTo(
                    ControlPlaneCertificateStatusMonitor.RefreshStatus.SOURCE_UNAVAILABLE);
            assertThat(send(client, server.uri())).isEqualTo("publication");
            assertThat(runtime.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.certificateStatusIntegrated()).isTrue();
                assertThat(descriptor.certificateStatusAvailable()).isFalse();
                assertThat(descriptor.certificateStatusFresh()).isTrue();
                assertThat(descriptor.certificateStatus()).isEqualTo("SOURCE_UNAVAILABLE");
                assertThat(descriptor.ready()).isTrue();
                assertThat(descriptor.productionReady()).isFalse();
            });
        }
    }

    @Test
    void enabledRuntimeRejectsUnknownUninventoriedDuplicateAndUnboundTargets() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-invalid");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(mapper);
        var runtime = new ControlPlaneCertificateRotationRuntime(
                enabledProperties(), Map.of(TARGET, initial(1)), verifiedTrust(),
                (targetId, generation, materialId) -> {
                    throw new AssertionError("material resolution must not run");
                }, reference -> RecoveryFleetPublicationTlsFixture.password(),
                fingerprinter, floorFactory(), Clock.systemUTC());

        assertThatThrownBy(() -> runtime.transport(
                ControlPlaneCertificateRotationTargets.RECOVERY_FLEET_INVENTORY,
                transportProperties(current))).isInstanceOf(IllegalArgumentException.class);
        runtime.transport(TARGET, transportProperties(current));
        assertThatThrownBy(() -> runtime.transport(TARGET, transportProperties(current)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runtime.apply(eventForTarget("unknown.target", 1,
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                Instant.now().plusSeconds(10))).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.TARGET_UNKNOWN);
    }

    @Test
    void restartMaterialResolutionFailureDoesNotLeakProviderDiagnostics() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-secret-safe");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(mapper);
        Instant now = Instant.now();
        var restored = new ControlPlaneCertificateRotationFloor.Snapshot(
                ControlPlaneCertificateRotationFloor.Snapshot.SCHEMA_VERSION,
                "rg-staging", TARGET, 42, "candidate-next",
                "sha256:" + "b".repeat(64), "rotation-042",
                "sha256:" + "e".repeat(64), now, 0, "", "", "", "",
                null, now);
        var runtime = new ControlPlaneCertificateRotationRuntime(
                enabledProperties(), Map.of(TARGET, initial(41)), verifiedTrust(),
                (targetId, generation, materialId) -> {
                    throw new IllegalStateException(
                            "vault://tenant/private-certificate-password");
                }, reference -> RecoveryFleetPublicationTlsFixture.password(), fingerprinter,
                fixedFloorFactory(restored), Clock.systemUTC());

        assertThatThrownBy(() -> runtime.transport(TARGET, transportProperties(current)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control-plane certificate rotation runtime is invalid")
                .hasNoCause()
                .hasMessageNotContaining("vault://")
                .hasMessageNotContaining("private-certificate-password");
    }

    @Test
    void disabledRuntimePreservesStaticCompatibilityTransport() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-disabled");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var runtime = new ControlPlaneCertificateRotationRuntime(
                ControlPlaneCertificateRotationRuntimeProperties.disabled(), Map.of(),
                ControlPlaneCertificateRotationTrustStore.unavailable(),
                (targetId, generation, materialId) -> {
                    throw new AssertionError("disabled runtime must not resolve material");
                }, reference -> RecoveryFleetPublicationTlsFixture.password(),
                new ControlPlaneCertificateSettingsFingerprint(mapper), floorFactory(),
                Clock.systemUTC());

        RecoveryFleetPublicationTransport transport = runtime.transport(
                TARGET, transportProperties(current));

        assertThat(transport).isInstanceOf(PinnedMutualTlsRecoveryFleetPublicationTransport.class);
        assertThat(runtime.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.enabled()).isFalse();
            assertThat(descriptor.ready()).isTrue();
            assertThat(descriptor.registeredTargetCount()).isZero();
        });
        assertThat(runtime.apply(event(1, "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), Instant.now().plusSeconds(10))).status())
                .isEqualTo(ControlPlaneCertificateRotationController.ApplyStatus
                .AUTHORIZATION_REJECTED);
    }

    @Test
    void restartRestoresACommittedActiveGenerationFromVerifiedAncestry() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-restart-current");
        var successor = current.rotateClient(temporaryDirectory, "runtime-restart-next");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(mapper);
        var successorSettings = settings(successor);
        String currentFingerprint = fingerprinter.fingerprint(settings(current));
        String successorFingerprint = fingerprinter.fingerprint(successorSettings);
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        ControlPlaneCertificateRotationMaterialSource source =
                (targetId, generation, materialId) ->
                        new ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial(
                                successorFingerprint, successorSettings);
        try (var database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:rotation-runtime-restart-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4))) {
            ControlPlaneCertificateRotationFloorFactory floors =
                    databaseFloorFactory(database, mapper);
            var first = new ControlPlaneCertificateRotationRuntime(
                    enabledProperties(), Map.of(TARGET, initial(41)), verifiedTrust(),
                    source, reference -> RecoveryFleetPublicationTlsFixture.password(),
                    fingerprinter, floors, clock);
            RecoveryFleetPublicationTransport firstTransport = first.transport(
                    TARGET, transportProperties(current));

            var applied = first.apply(event(41, currentFingerprint,
                    successorFingerprint, now.minusSeconds(1)));

            assertThat(applied.status()).isEqualTo(
                    ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);
            assertThat(applied.activeGeneration()).isEqualTo(42);
            assertThat(((ControlPlaneCertificateRotationTarget) firstTransport)
                    .activeGeneration()).isEqualTo(42);

            var restarted = new ControlPlaneCertificateRotationRuntime(
                    enabledProperties(), Map.of(TARGET, initial(41)), verifiedTrust(),
                    source, reference -> RecoveryFleetPublicationTlsFixture.password(),
                    fingerprinter, floors, clock);
            RecoveryFleetPublicationTransport restored = restarted.transport(
                    TARGET, transportProperties(current));

            assertThat(((ControlPlaneCertificateRotationTarget) restored)
                    .activeGeneration()).isEqualTo(42);
            assertThat(restarted.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.ready()).isTrue();
                assertThat(descriptor.durableState()).isTrue();
                assertThat(descriptor.synchronizedState()).isTrue();
            });
        }
    }

    private static String send(java.net.http.HttpClient client, java.net.URI uri)
            throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET()
                        .timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    private static ControlPlaneCertificateRotationEvent event(
            long activeGeneration,
            String currentFingerprint,
            String successorFingerprint,
            Instant activateAt) {
        return eventForTarget(TARGET, activeGeneration, currentFingerprint,
                successorFingerprint, activateAt);
    }

    private static ControlPlaneCertificateRotationEvent eventForTarget(
            String targetId,
            long activeGeneration,
            String currentFingerprint,
            String successorFingerprint,
            Instant activateAt) {
        Instant issuedAt = activateAt.minusSeconds(20);
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-pki", "rotation-%03d".formatted(activeGeneration + 1),
                "rg-staging", targetId, activeGeneration + 1, currentFingerprint,
                "candidate-next", successorFingerprint, POLICY, issuedAt, issuedAt,
                activateAt, activateAt.plusSeconds(300));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                "sha256:" + "e".repeat(64), List.of(
                new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", issuedAt,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static ControlPlaneCertificateRotationRuntimeProperties enabledProperties() {
        return new ControlPlaneCertificateRotationRuntimeProperties(
                true, true, "rg-staging", "enterprise-pki", POLICY, 1,
                "[{}]", 0L, 3_600L, "{\"" + TARGET + "\":41}", "[{}]");
    }

    private static ControlPlaneCertificateRotationRuntimeProperties.InitialTargetSpec initial(
            long generation) {
        return new ControlPlaneCertificateRotationRuntimeProperties.InitialTargetSpec(
                generation, "initial");
    }

    private static ControlPlaneCertificateStatusFloor.Snapshot statusSnapshot(
            Instant now,
            String publicationFingerprint,
            ControlPlaneCertificateStatusPublication.TargetStatus target) {
        return new ControlPlaneCertificateStatusFloor.Snapshot(
                ControlPlaneCertificateStatusFloor.Snapshot.SCHEMA_VERSION,
                "rg-staging", 0, "sha256:" + "0".repeat(64), 1, "status-001",
                publicationFingerprint, now.minusSeconds(1), now.plusSeconds(60), now,
                List.of(target));
    }

    private static ControlPlaneCertificateStatusPublication.TargetStatus goodTargetStatus(
            long generation, String settingsFingerprint) {
        return new ControlPlaneCertificateStatusPublication.TargetStatus(TARGET, generation,
                settingsFingerprint, List.of(
                goodEvidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                        'c'),
                goodEvidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                        'd')));
    }

    private static ControlPlaneCertificateStatusPublication.CertificateEvidence goodEvidence(
            ControlPlaneCertificateStatusPublication.CertificateRole role, char fingerprint) {
        Instant observedAt = Instant.now();
        return new ControlPlaneCertificateStatusPublication.CertificateEvidence(role,
                ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                ControlPlaneCertificateStatusPublication.EvidenceType.OCSP,
                "sha256:" + String.valueOf(fingerprint).repeat(64),
                "sha256:" + "e".repeat(64), "sha256:" + "f".repeat(64),
                "CERTIFICATE_GOOD", observedAt, observedAt,
                observedAt.plusSeconds(3_600));
    }

    private static ControlPlaneCertificateRotationFloorFactory floorFactory() {
        return (scope, targets) -> new TestFloor(scope, targets);
    }

    private static ControlPlaneCertificateRotationFloorFactory fixedFloorFactory(
            ControlPlaneCertificateRotationFloor.Snapshot snapshot) {
        return (scope, targets) -> new ControlPlaneCertificateRotationFloor() {
            @Override
            public Acceptance accept(ControlPlaneCertificateRotationEvent event) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Snapshot snapshot(String targetId) {
                return snapshot;
            }

            @Override
            public Map<String, Snapshot> snapshots() {
                return Map.of(snapshot.targetId(), snapshot);
            }

            @Override
            public boolean durable() {
                return true;
            }
        };
    }

    private static ControlPlaneCertificateRotationFloorFactory databaseFloorFactory(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper) {
        return (scope, targets) -> {
            var floor = new DatabaseControlPlaneCertificateRotationFloor(database.jdbc(),
                    objectMapper, scope, targets, database.transactionManager());
            floor.init();
            return floor;
        };
    }

    private static RecoveryFleetPublicationTransportProperties transportProperties(
            RecoveryFleetPublicationTlsFixture.Material material) {
        String issuer = PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                material.certificateAuthority());
        return new RecoveryFleetPublicationTransportProperties(
                true, true, material.trustStore().toString(), "test:trust",
                material.clientKeyStore().toString(), "test:client",
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate()), true,
                material.clientCertificate().getSubjectX500Principal().getName(),
                material.clientUriSan(), issuer, material.serverUriSan(), issuer);
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings(
            RecoveryFleetPublicationTlsFixture.Material material) {
        return transportProperties(material).pinnedSettings();
    }

    private static ControlPlaneCertificateRotationTrustStore verifiedTrust() {
        return new ControlPlaneCertificateRotationTrustStore() {
            @Override
            public Verification verify(
                    ControlPlaneCertificateRotationEvent event,
                    ExpectedBinding expected,
                    Instant observedAt) {
                return new Verification(VerificationStatus.VERIFIED, "VERIFIED",
                        event.material().eventId(), event.materialFingerprint(),
                        event.material().settingsFingerprint(), 1, 1);
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("", true, "enterprise-pki", 1, 1, 1, 1, Map.of());
            }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
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
            return instant;
        }
    }

    private record FixedStatusFloor(
            ControlPlaneCertificateStatusFloor.Snapshot snapshot)
            implements ControlPlaneCertificateStatusFloor {

        @Override
        public Acceptance accept(ControlPlaneCertificateStatusPublication publication) {
            throw new AssertionError("source returned no publication");
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class TestFloor implements ControlPlaneCertificateRotationFloor {
        private final String targetId;
        private Snapshot snapshot;

        private TestFloor(String scope, Map<String, InitialTarget> targets) {
            this.targetId = targets.keySet().iterator().next();
            InitialTarget initial = targets.get(targetId);
            Instant now = Instant.now();
            this.snapshot = new Snapshot(Snapshot.SCHEMA_VERSION, scope, targetId,
                    initial.generation(), initial.materialId(),
                    initial.settingsFingerprint(), "", "", now,
                    0, "", "", "", "", null, now);
        }

        @Override
        public synchronized Acceptance accept(ControlPlaneCertificateRotationEvent event) {
            var material = event.material();
            if (snapshot.pendingEventFingerprint().equals(event.materialFingerprint())) {
                return new Acceptance(AcceptanceStatus.REPLAYED, snapshot);
            }
            if (material.generation() != snapshot.activeGeneration() + 1
                    || !material.previousMaterialFingerprint().equals(
                    snapshot.activeSettingsFingerprint())) {
                throw new IllegalArgumentException("generation conflict");
            }
            Instant now = Instant.now();
            snapshot = new Snapshot(Snapshot.SCHEMA_VERSION,
                    snapshot.deploymentScopeId(), targetId, snapshot.activeGeneration(),
                    snapshot.activeMaterialId(), snapshot.activeSettingsFingerprint(),
                    snapshot.activeEventId(), snapshot.activeEventFingerprint(),
                    snapshot.activatedAt(), material.generation(), material.materialId(),
                    material.settingsFingerprint(), material.eventId(),
                    event.materialFingerprint(), material.activateAt(), now);
            return new Acceptance(AcceptanceStatus.STAGED, snapshot);
        }

        @Override
        public synchronized Snapshot snapshot(String targetId) {
            if (!this.targetId.equals(targetId)) {
                throw new IllegalArgumentException("unknown target");
            }
            return snapshot;
        }

        @Override
        public synchronized Map<String, Snapshot> snapshots() {
            return Map.of(targetId, snapshot);
        }

        @Override
        public boolean durable() {
            return true;
        }
    }
}
