package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationEventPageTest {

    private static final String SCOPE = "resource-gateway-prod";
    private static final String HEAD = fingerprint('0');
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void verifiesCanonicalPageMaterialWithoutTreatingItAsEventAuthorization() {
        ControlPlaneCertificateRotationEventPage page = page(1, HEAD,
                List.of(event("rotation-002", "target-a")));

        assertThat(page.fingerprintVerified(objectMapper)).isTrue();
        assertThat(page.material().events()).hasSize(1);
        assertThat(page.material().events().getFirst().signatures()).hasSize(1);
    }

    @Test
    void detectsAChangedPageBodyBehindTheSameEnvelopeFingerprint() {
        ControlPlaneCertificateRotationEventPage original = page(1, HEAD,
                List.of(event("rotation-002", "target-a")));
        var changedMaterial = new ControlPlaneCertificateRotationEventPage.Material(
                ControlPlaneCertificateRotationEventPage.Material.SCHEMA_VERSION,
                SCOPE, 1, HEAD, now(), now().plusSeconds(60),
                List.of(event("rotation-003", "target-a")));
        var changed = new ControlPlaneCertificateRotationEventPage(
                ControlPlaneCertificateRotationEventPage.SCHEMA_VERSION,
                changedMaterial, original.pageFingerprint());

        assertThat(changed.fingerprintVerified(objectMapper)).isFalse();
    }

    @Test
    void rejectsDuplicateTargetsBecauseOnePageCannotEncodeIntraTargetOrdering() {
        assertThatThrownBy(() -> page(1, HEAD, List.of(
                event("rotation-002", "target-a"),
                event("rotation-003", "target-a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event page");
    }

    @Test
    void rejectsAnEventFromAnotherDeploymentScope() {
        ControlPlaneCertificateRotationEvent foreign = event(
                "rotation-002", "target-a", "another-scope");

        assertThatThrownBy(() -> page(1, HEAD, List.of(foreign)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event page");
    }

    @Test
    void rejectsEmptyOversizedAndSubMicrosecondPages() {
        assertThatThrownBy(() -> material(1, HEAD, List.of(), now(), now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> material(1, HEAD,
                java.util.stream.IntStream.range(0, 13)
                        .mapToObj(index -> event("rotation-" + (index + 100),
                                "target-" + index)).toList(),
                now(), now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> material(1, HEAD,
                List.of(event("rotation-002", "target-a")),
                now().plusNanos(1), now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ControlPlaneCertificateRotationEventPage page(
            long sequence,
            String predecessor,
            List<ControlPlaneCertificateRotationEvent> events) {
        var material = material(sequence, predecessor, events, now(), now().plusSeconds(60));
        return new ControlPlaneCertificateRotationEventPage(
                ControlPlaneCertificateRotationEventPage.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(objectMapper, material));
    }

    private ControlPlaneCertificateRotationEventPage.Material material(
            long sequence,
            String predecessor,
            List<ControlPlaneCertificateRotationEvent> events,
            Instant issuedAt,
            Instant expiresAt) {
        return new ControlPlaneCertificateRotationEventPage.Material(
                ControlPlaneCertificateRotationEventPage.Material.SCHEMA_VERSION,
                SCOPE, sequence, predecessor, issuedAt, expiresAt, events);
    }

    private ControlPlaneCertificateRotationEvent event(String eventId, String targetId) {
        return event(eventId, targetId, SCOPE);
    }

    private ControlPlaneCertificateRotationEvent event(
            String eventId,
            String targetId,
            String scope) {
        Instant now = now();
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "certificate-authority", eventId, scope, targetId, 2,
                fingerprint('a'), "candidate-b", fingerprint('b'), fingerprint('f'),
                now, now, now.plusSeconds(10), now.plusSeconds(120));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(objectMapper, material),
                List.of(new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", now,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static Instant now() {
        return Instant.parse("2026-07-21T12:00:00Z");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
