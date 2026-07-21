package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ControlPlaneCertificateRotationControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private static final String EVENT_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String MATERIAL_FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final String POLICY_FINGERPRINT = "sha256:" + "c".repeat(64);
    private static final String INITIAL_FINGERPRINT = "sha256:" + "d".repeat(64);

    private FakeTarget target;
    private AtomicInteger resolutions;
    private ControlPlaneCertificateRotationMaterialSource materialSource;

    @BeforeEach
    void setUp() {
        target = new FakeTarget(7);
        resolutions = new AtomicInteger();
        materialSource = (targetId, generation, materialRef) -> {
            resolutions.incrementAndGet();
            return new ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial(
                    MATERIAL_FINGERPRINT,
                    mock(PinnedMutualTlsRecoveryFleetPublicationTransport.Settings.class));
        };
    }

    @Test
    void appliesAuthorizedContiguousGenerationAndMakesExactReplayIdempotent() {
        var controller = controller(verifiedTrust(), materialSource);
        var event = event(7, 8, EVENT_FINGERPRINT, MATERIAL_FINGERPRINT);

        var applied = controller.apply(event);
        var replayed = controller.apply(event);

        assertThat(applied.status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);
        assertThat(applied.eventId()).isEqualTo("rotation-008");
        assertThat(applied.eventFingerprint()).isEqualTo(EVENT_FINGERPRINT);
        assertThat(applied.activeGeneration()).isEqualTo(7);
        assertThat(applied.pendingGeneration()).isEqualTo(8);
        assertThat(replayed.status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.REPLAYED);
        assertThat(resolutions).hasValue(1);
        assertThat(target.stageCalls).hasValue(1);
    }

    @Test
    void rejectsUnauthorizedUnknownSkippedRollbackConflictAndMaterialMismatchWithoutStaging() {
        var unauthorized = controller(rejectedTrust(), materialSource);
        assertThat(unauthorized.apply(event(7, 8, EVENT_FINGERPRINT,
                MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.AUTHORIZATION_REJECTED);

        var controller = controller(verifiedTrust(), materialSource);
        assertThat(controller.apply(event(6, 7, "sha256:" + "d".repeat(64),
                MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.GENERATION_CONFLICT);
        assertThat(controller.apply(event(7, 9, "sha256:" + "e".repeat(64),
                MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.GENERATION_CONFLICT);
        assertThat(controller.apply(event(7, 8, "sha256:" + "f".repeat(64),
                "sha256:" + "1".repeat(64))).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.MATERIAL_MISMATCH);
        assertThat(controller.apply(eventForTarget("missing-target", 7, 8,
                "sha256:" + "2".repeat(64), MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.TARGET_UNKNOWN);
        assertThat(target.stageCalls).hasValue(0);
    }

    @Test
    void rejectsDifferentEventForAnAlreadyAcceptedGeneration() {
        var controller = controller(verifiedTrust(), materialSource);

        assertThat(controller.apply(event(7, 8, EVENT_FINGERPRINT,
                MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);
        assertThat(controller.apply(event(7, 8, "sha256:" + "9".repeat(64),
                MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.GENERATION_CONFLICT);
        assertThat(resolutions).hasValue(1);
        assertThat(target.stageCalls).hasValue(1);
    }

    @Test
    void rejectsAClaimedVerificationWhoseEventOrMaterialBindingDrifts() {
        ControlPlaneCertificateRotationTrustStore driftedTrust =
                new ControlPlaneCertificateRotationTrustStore() {
                    @Override
                    public Verification verify(
                            ControlPlaneCertificateRotationEvent event,
                            ExpectedBinding expected,
                            Instant observedAt) {
                        return new Verification(VerificationStatus.VERIFIED, "VERIFIED",
                                event.material().eventId(),
                                "sha256:" + "9".repeat(64),
                                event.material().settingsFingerprint(), 2, 2);
                    }

                    @Override
                    public Descriptor descriptor() {
                        return new Descriptor("", true, "enterprise-pki-governance",
                                2, 2, 2, 1, Map.of());
                    }
                };

        var result = controller(driftedTrust, materialSource).apply(
                event(7, 8, EVENT_FINGERPRINT, MATERIAL_FINGERPRINT));

        assertThat(result.status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.AUTHORIZATION_REJECTED);
        assertThat(result.reasonCode())
                .isEqualTo("CERTIFICATE_ROTATION_AUTHORIZATION_BINDING_MISMATCH");
        assertThat(resolutions).hasValue(0);
        assertThat(target.stageCalls).hasValue(0);
    }

    @Test
    void concurrentExactReplayResolvesAndStagesOnlyOnce() throws Exception {
        CountDownLatch resolutionEntered = new CountDownLatch(1);
        CountDownLatch releaseResolution = new CountDownLatch(1);
        materialSource = (targetId, generation, materialRef) -> {
            resolutions.incrementAndGet();
            resolutionEntered.countDown();
            try {
                if (!releaseResolution.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial(
                    MATERIAL_FINGERPRINT,
                    mock(PinnedMutualTlsRecoveryFleetPublicationTransport.Settings.class));
        };
        var controller = controller(verifiedTrust(), materialSource);
        var event = event(7, 8, EVENT_FINGERPRINT, MATERIAL_FINGERPRINT);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> controller.apply(event));
            assertThat(resolutionEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> controller.apply(event));
            releaseResolution.countDown();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS).status(),
                    second.get(5, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder(
                            ControlPlaneCertificateRotationController.ApplyStatus.APPLIED,
                            ControlPlaneCertificateRotationController.ApplyStatus.REPLAYED);
        }
        assertThat(resolutions).hasValue(1);
        assertThat(target.stageCalls).hasValue(1);
    }

    @Test
    void resolutionAndStagingFailuresLeaveCurrentGenerationRetryable() {
        var unavailableSource = new ControlPlaneCertificateRotationMaterialSource() {
            private int attempt;

            @Override
            public ResolvedMaterial resolve(String targetId, long generation, String materialRef) {
                if (attempt++ == 0) {
                    throw new IllegalStateException("secret manager unavailable: do-not-leak");
                }
                return new ResolvedMaterial(MATERIAL_FINGERPRINT,
                        mock(PinnedMutualTlsRecoveryFleetPublicationTransport.Settings.class));
            }
        };
        var controller = controller(verifiedTrust(), unavailableSource);
        var event = event(7, 8, EVENT_FINGERPRINT, MATERIAL_FINGERPRINT);

        var failed = controller.apply(event);
        var retried = controller.apply(event);

        assertThat(failed.status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.MATERIAL_UNAVAILABLE);
        assertThat(failed.reasonCode()).isEqualTo("ROTATION_MATERIAL_UNAVAILABLE");
        assertThat(failed.toString()).doesNotContain("do-not-leak");
        assertThat(retried.status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);

        var stagingTarget = new FakeTarget(7);
        stagingTarget.failStage = true;
        var stagingController = new ControlPlaneCertificateRotationController(
                verifiedTrust(), materialSource, Clock.fixed(NOW, ZoneOffset.UTC),
                "resource-gateway-prod", Map.of("external-notary",
                new ControlPlaneCertificateRotationController.TargetRegistration(
                        stagingTarget, INITIAL_FINGERPRINT)));
        assertThat(stagingController.apply(event).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.STAGING_REJECTED);
        assertThat(stagingTarget.activeGeneration()).isEqualTo(7);
        stagingTarget.failStage = false;
        assertThat(stagingController.apply(event).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);
    }

    @Test
    void targetObservationFailureCompletesSingleFlightAndMarksStateOutOfSync() {
        var unstableTarget = new FakeTarget(7);
        unstableTarget.failPendingObservationAfterStage = true;
        var controller = new ControlPlaneCertificateRotationController(
                verifiedTrust(), materialSource, Clock.fixed(NOW, ZoneOffset.UTC),
                "resource-gateway-prod", Map.of("external-notary",
                new ControlPlaneCertificateRotationController.TargetRegistration(
                        unstableTarget, INITIAL_FINGERPRINT)));
        var event = event(7, 8, EVENT_FINGERPRINT, MATERIAL_FINGERPRINT);

        var failed = controller.apply(event);
        var replay = controller.apply(event);

        assertThat(failed.status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.STATE_OUT_OF_SYNC);
        assertThat(failed.reasonCode()).isEqualTo("CERTIFICATE_ROTATION_STATE_OUT_OF_SYNC");
        assertThat(replay.status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.STATE_OUT_OF_SYNC);
        assertThat(unstableTarget.stageCalls).hasValue(1);
    }

    @Test
    void advancesMaterialPredecessorAfterActivationAndAcceptsOnlyTheNextGeneration() {
        String nextFingerprint = "sha256:" + "6".repeat(64);
        materialSource = (targetId, generation, materialId) ->
                new ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial(
                        generation == 8 ? MATERIAL_FINGERPRINT : nextFingerprint,
                        mock(PinnedMutualTlsRecoveryFleetPublicationTransport.Settings.class));
        var controller = controller(verifiedTrust(), materialSource);

        assertThat(controller.apply(event(7, 8, EVENT_FINGERPRINT,
                MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);
        target.promotePending();

        assertThat(controller.targetStates().get("external-notary")).satisfies(state -> {
            assertThat(state.activeGeneration()).isEqualTo(8);
            assertThat(state.pendingGeneration()).isZero();
            assertThat(state.synchronizedState()).isTrue();
        });
        assertThat(controller.apply(event(8, 9, "sha256:" + "7".repeat(64),
                nextFingerprint)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);
        assertThat(target.pendingGeneration()).hasValue(9);
    }

    @Test
    void failsClosedWhenLiveTargetDriftsOrVerificationIdentityIsSubstituted() {
        var controller = controller(verifiedTrust(), materialSource);
        target.forceActive(9);

        assertThat(controller.apply(event(7, 8, EVENT_FINGERPRINT,
                MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.STATE_OUT_OF_SYNC);
        assertThat(controller.targetStates().get("external-notary").synchronizedState())
                .isFalse();
        assertThat(resolutions).hasValue(0);

        target = new FakeTarget(7);
        ControlPlaneCertificateRotationTrustStore substituted = new FakeTrust(true) {
            @Override
            public Verification verify(
                    ControlPlaneCertificateRotationEvent event,
                    ExpectedBinding expected,
                    Instant observedAt) {
                return new Verification(VerificationStatus.VERIFIED, "VERIFIED",
                        "different-event", event.materialFingerprint(),
                        event.material().settingsFingerprint(), 2, 2);
            }
        };
        var substitutedController = controller(substituted, materialSource);
        assertThat(substitutedController.apply(event(7, 8, EVENT_FINGERPRINT,
                MATERIAL_FINGERPRINT)).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.AUTHORIZATION_REJECTED);
        assertThat(resolutions).hasValue(0);
    }

    private ControlPlaneCertificateRotationController controller(
            ControlPlaneCertificateRotationTrustStore trustStore,
            ControlPlaneCertificateRotationMaterialSource source) {
        return new ControlPlaneCertificateRotationController(
                trustStore, source, Clock.fixed(NOW, ZoneOffset.UTC),
                "resource-gateway-prod", Map.of("external-notary",
                new ControlPlaneCertificateRotationController.TargetRegistration(
                        target, INITIAL_FINGERPRINT)));
    }

    private static ControlPlaneCertificateRotationTrustStore verifiedTrust() {
        return new FakeTrust(true);
    }

    private static ControlPlaneCertificateRotationTrustStore rejectedTrust() {
        return new FakeTrust(false);
    }

    private static ControlPlaneCertificateRotationEvent event(
            long previousGeneration,
            long generation,
            String eventFingerprint,
            String materialFingerprint) {
        return eventForTarget("external-notary", previousGeneration, generation,
                eventFingerprint, materialFingerprint);
    }

    private static ControlPlaneCertificateRotationEvent eventForTarget(
            String targetId,
            long previousGeneration,
            long generation,
            String eventFingerprint,
            String materialFingerprint) {
        String previousFingerprint = previousGeneration == 7
                ? INITIAL_FINGERPRINT : previousGeneration == 8
                ? MATERIAL_FINGERPRINT : "sha256:" + "e".repeat(64);
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-pki-governance", "rotation-%03d".formatted(generation),
                "resource-gateway-prod",
                targetId, generation, previousFingerprint, "candidate-a",
                materialFingerprint, POLICY_FINGERPRINT, NOW.minusSeconds(5),
                NOW.minusSeconds(5), NOW.plusSeconds(60), NOW.plusSeconds(300));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                eventFingerprint, List.of(new ControlPlaneCertificateRotationEvent
                .AuthoritySignature("pki-a", "key-a", "Ed25519", NOW,
                java.util.Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static final class FakeTarget implements ControlPlaneCertificateRotationTarget {
        private final AtomicInteger stageCalls = new AtomicInteger();
        private long activeGeneration;
        private long pendingGeneration;
        private boolean failStage;
        private boolean failPendingObservationAfterStage;

        private FakeTarget(long activeGeneration) {
            this.activeGeneration = activeGeneration;
        }

        @Override
        public synchronized long activeGeneration() {
            return activeGeneration;
        }

        @Override
        public synchronized OptionalLong pendingGeneration() {
            if (failPendingObservationAfterStage && pendingGeneration > 0) {
                throw new IllegalStateException("observation failed: do-not-leak");
            }
            return pendingGeneration == 0 ? OptionalLong.empty()
                    : OptionalLong.of(pendingGeneration);
        }

        @Override
        public synchronized void stage(
                long generation,
                Instant activateAt,
                PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings) {
            stageCalls.incrementAndGet();
            if (failStage) {
                throw new IllegalArgumentException("invalid material: do-not-leak");
            }
            pendingGeneration = generation;
        }

        private synchronized void promotePending() {
            activeGeneration = pendingGeneration;
            pendingGeneration = 0;
        }

        private synchronized void forceActive(long generation) {
            activeGeneration = generation;
            pendingGeneration = 0;
        }
    }

    private static class FakeTrust
            implements ControlPlaneCertificateRotationTrustStore {

        private final boolean verified;

        private FakeTrust(boolean verified) {
            this.verified = verified;
        }

        @Override
        public Verification verify(
                ControlPlaneCertificateRotationEvent event,
                ExpectedBinding expected,
                Instant observedAt) {
            if (!verified) {
                return new Verification(VerificationStatus.QUORUM_NOT_MET,
                        "ROTATION_AUTHORIZATION_QUORUM_NOT_MET", "", "", "", 0, 2);
            }
            return new Verification(VerificationStatus.VERIFIED, "VERIFIED",
                    event.material().eventId(), event.materialFingerprint(),
                    event.material().settingsFingerprint(), 2, 2);
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor("", verified, "enterprise-pki-governance",
                    verified ? 2 : 0, verified ? 2 : 0, verified ? 2 : 0,
                    verified ? 1 : 0, Map.of());
        }
    }
}
