package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateStatusMonitorTest {

    private static final String TARGET = "recovery-fleet.inventory";
    private static final String BASELINE = fingerprint('0');
    private static final String SETTINGS = fingerprint('a');
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void restoresDurableAdmissionBeforeAStatusSourceFailure() throws Exception {
        MutableFloor floor = new MutableFloor();
        ControlPlaneCertificateStatusPublication publication = publication(
                1, "", 1, SETTINGS);
        floor.accept(publication);
        var admission = admission();
        var monitor = new ControlPlaneCertificateStatusMonitor(floor,
                cursor -> {
                    throw new IllegalStateException("source unavailable");
                }, admission, Clock.fixed(NOW, ZoneOffset.UTC), 4);

        var descriptor = monitor.refresh();

        assertThat(descriptor.status()).isEqualTo(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.SOURCE_UNAVAILABLE);
        assertThat(descriptor.admissionFresh()).isTrue();
        assertThat(admission.servingPermitted(TARGET, 1, SETTINGS)).isTrue();
    }

    @Test
    void appliesAContiguousBoundedSequenceAndStopsAtUnchanged() throws Exception {
        MutableFloor floor = new MutableFloor();
        ControlPlaneCertificateStatusPublication first = publication(1, "", 1, SETTINGS);
        ControlPlaneCertificateStatusPublication second = publication(
                2, first.materialFingerprint(), 2, fingerprint('b'));
        Queue<ControlPlaneCertificateStatusPublication> publications =
                new ArrayDeque<>(List.of(first, second));
        var admission = admission();
        var monitor = new ControlPlaneCertificateStatusMonitor(floor,
                cursor -> publications.isEmpty()
                        ? ControlPlaneCertificateStatusSource.FetchResult.unchanged()
                        : ControlPlaneCertificateStatusSource.FetchResult.publication(
                        publications.remove()), admission,
                Clock.fixed(NOW, ZoneOffset.UTC), 4);

        var descriptor = monitor.refresh();

        assertThat(descriptor.status()).isEqualTo(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.APPLIED);
        assertThat(descriptor.appliedCount()).isEqualTo(2);
        assertThat(descriptor.sequence()).isEqualTo(2);
        assertThat(admission.servingPermitted(TARGET, 2, fingerprint('b'))).isTrue();
    }

    @Test
    void enforcesCatchUpBoundWithoutDroppingTheLatestAppliedCache() throws Exception {
        MutableFloor floor = new MutableFloor();
        ControlPlaneCertificateStatusPublication first = publication(1, "", 1, SETTINGS);
        ControlPlaneCertificateStatusPublication second = publication(
                2, first.materialFingerprint(), 2, fingerprint('b'));
        ControlPlaneCertificateStatusPublication third = publication(
                3, second.materialFingerprint(), 3, fingerprint('c'));
        Queue<ControlPlaneCertificateStatusPublication> publications =
                new ArrayDeque<>(List.of(first, second, third));
        var admission = admission();
        var monitor = new ControlPlaneCertificateStatusMonitor(floor,
                cursor -> publications.isEmpty()
                        ? ControlPlaneCertificateStatusSource.FetchResult.unchanged()
                        : ControlPlaneCertificateStatusSource.FetchResult.publication(
                        publications.remove()), admission,
                Clock.fixed(NOW, ZoneOffset.UTC), 2);

        var firstCycle = monitor.refresh();
        var secondCycle = monitor.refresh();

        assertThat(firstCycle.status()).isEqualTo(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.BATCH_LIMIT);
        assertThat(firstCycle.sequence()).isEqualTo(2);
        assertThat(firstCycle.appliedCount()).isEqualTo(2);
        assertThat(secondCycle.status()).isEqualTo(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.APPLIED);
        assertThat(secondCycle.sequence()).isEqualTo(3);
        assertThat(secondCycle.appliedCount()).isEqualTo(1);
        assertThat(admission.servingPermitted(TARGET, 3, fingerprint('c'))).isTrue();
    }

    @Test
    void rejectedForkKeepsThePreviousFreshAdmission() throws Exception {
        MutableFloor floor = new MutableFloor();
        ControlPlaneCertificateStatusPublication first = publication(1, "", 1, SETTINGS);
        floor.accept(first);
        ControlPlaneCertificateStatusPublication fork = publication(
                2, fingerprint('9'), 2, fingerprint('b'));
        var admission = admission();
        var monitor = new ControlPlaneCertificateStatusMonitor(floor,
                cursor -> ControlPlaneCertificateStatusSource.FetchResult.publication(fork),
                admission, Clock.fixed(NOW, ZoneOffset.UTC), 2);

        var descriptor = monitor.refresh();

        assertThat(descriptor.status()).isEqualTo(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.PUBLICATION_REJECTED);
        assertThat(descriptor.sequence()).isEqualTo(1);
        assertThat(descriptor.admissionFresh()).isTrue();
        assertThat(admission.servingPermitted(TARGET, 1, SETTINGS)).isTrue();
    }

    @Test
    void exactReplayCannotSpinTheBoundedWatcher() throws Exception {
        MutableFloor floor = new MutableFloor();
        ControlPlaneCertificateStatusPublication first = publication(1, "", 1, SETTINGS);
        floor.accept(first);
        AtomicLong fetches = new AtomicLong();
        var monitor = new ControlPlaneCertificateStatusMonitor(floor,
                cursor -> {
                    fetches.incrementAndGet();
                    return ControlPlaneCertificateStatusSource.FetchResult.publication(first);
                }, admission(), Clock.fixed(NOW, ZoneOffset.UTC), 32);

        var descriptor = monitor.refresh();

        assertThat(descriptor.status()).isEqualTo(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.CURRENT);
        assertThat(fetches).hasValue(1);
    }

    private static ControlPlaneCertificateStatusAdmission admission() {
        return new ControlPlaneCertificateStatusAdmission(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> 1);
    }

    private ControlPlaneCertificateStatusPublication publication(
            long sequence,
            String predecessor,
            long generation,
            String settingsFingerprint) throws Exception {
        var target = new ControlPlaneCertificateStatusPublication.TargetStatus(
                TARGET, generation, settingsFingerprint, List.of(
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT),
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER)));
        var material = new ControlPlaneCertificateStatusPublication.Material(
                ControlPlaneCertificateStatusPublication.Material.SCHEMA_VERSION,
                "enterprise-pki", "status-" + sequence, "rg-staging", sequence,
                predecessor, fingerprint('f'), NOW.minusSeconds(1), NOW.plusSeconds(3600),
                List.of(target));
        String materialFingerprint = ProtocolFingerprint.of(objectMapper, material);
        var signature = new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                "authority-a", "key-a", "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[64]));
        return new ControlPlaneCertificateStatusPublication(
                ControlPlaneCertificateStatusPublication.SCHEMA_VERSION,
                material, materialFingerprint, List.of(signature));
    }

    private static ControlPlaneCertificateStatusPublication.CertificateEvidence evidence(
            ControlPlaneCertificateStatusPublication.CertificateRole role) {
        return new ControlPlaneCertificateStatusPublication.CertificateEvidence(
                role, ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                ControlPlaneCertificateStatusPublication.EvidenceType.OCSP,
                role == ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT
                        ? fingerprint('c') : fingerprint('d'),
                fingerprint('e'), fingerprint('6'), "CERTIFICATE_GOOD",
                NOW, NOW, NOW.plusSeconds(7200));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class MutableFloor implements ControlPlaneCertificateStatusFloor {
        private Snapshot current = new Snapshot(Snapshot.SCHEMA_VERSION, "rg-staging",
                0, BASELINE, 0, "", BASELINE, null, null, null, List.of());

        @Override
        public synchronized Acceptance accept(
                ControlPlaneCertificateStatusPublication publication) {
            if (publication.material().sequence() == current.sequence()
                    && publication.materialFingerprint().equals(
                    current.publicationFingerprint())) {
                return new Acceptance(AcceptanceStatus.REPLAYED, current);
            }
            String expectedPredecessor = current.sequence() == 0
                    ? "" : current.publicationFingerprint();
            if (publication.material().sequence() != current.sequence() + 1
                    || !publication.material().previousPublicationFingerprint().equals(
                    expectedPredecessor)) {
                throw new IllegalArgumentException("test floor cursor conflict");
            }
            current = new Snapshot(Snapshot.SCHEMA_VERSION, "rg-staging", 0, BASELINE,
                    publication.material().sequence(), publication.material().publicationId(),
                    publication.materialFingerprint(), publication.material().issuedAt(),
                    publication.material().expiresAt(), NOW,
                    publication.material().targets());
            return new Acceptance(AcceptanceStatus.APPLIED, current);
        }

        @Override
        public synchronized Snapshot snapshot() {
            return current;
        }

        @Override
        public boolean durable() {
            return true;
        }
    }
}
