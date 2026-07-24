package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateTransitionRunEvidenceIntegrityTest {
    private static final Instant STARTED =
            Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsOnePayloadFreeReadWriteTransitionClosure() throws Exception {
        MirrorStateTransitionRunEvidence sealed =
                MirrorStateTransitionRunEvidenceIntegrity.seal(
                        mapper, evidence());

        MirrorStateTransitionRunEvidenceIntegrity.verify(
                mapper, sealed);
        assertThat(sealed.stateEvidenceFingerprint())
                .startsWith("sha256:");
        assertThat(MirrorStateTransitionRunEvidenceIntegrity
                .reference(sealed))
                .isEqualTo(new MirrorArtifactRef(
                        "MIRROR_STATE_RUN_EVIDENCE",
                        sealed.runId(), 2,
                        sealed.stateEvidenceFingerprint()));
        assertThat(sealed.transitions()).singleElement()
                .satisfies(transition -> {
                    assertThat(transition.revisionBefore())
                            .isZero();
                    assertThat(transition.revisionAfter())
                            .isEqualTo(1);
                    assertThat(transition.events()).singleElement();
                });
        String json = mapper.writeValueAsString(sealed);
        assertThat(json)
                .doesNotContain("customer-raw-id")
                .doesNotContain("raw-idempotency-key")
                .doesNotContain("customer-secret")
                .contains("idempotencyKeyFingerprint")
                .contains("entityIdentityFingerprint");
    }

    @Test
    void rejectsACommittedTransitionThatDoesNotReachTheFinalHead() {
        MirrorStateTransitionRunEvidence source =
                evidence();
        MirrorArtifactRef wrongFinal = new MirrorArtifactRef(
                "SESSION_STATE", "session-a", 3,
                fingerprint('f'));

        assertThatThrownBy(() ->
                new MirrorStateTransitionRunEvidence(
                        source.schemaVersion(), "",
                        source.runId(),
                        source.planFingerprint(),
                        source.sessionStateRef(),
                        wrongFinal,
                        source.stateModelRef(),
                        source.stateRevision(), 2,
                        source.worldFingerprint(),
                        fingerprint('e'),
                        source.logicalClock(),
                        source.finalLogicalClock(),
                        source.mode(),
                        source.statefulBindings(),
                        source.accesses(),
                        source.transitions(),
                        source.limitations()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "does not reach the final Session head");
    }

    @Test
    void detectsStateEvidenceFingerprintTampering() {
        MirrorStateTransitionRunEvidence sealed =
                MirrorStateTransitionRunEvidenceIntegrity.seal(
                        mapper, evidence());

        assertThatThrownBy(() ->
                MirrorStateTransitionRunEvidenceIntegrity.verify(
                        mapper,
                        sealed.withFingerprint(
                                fingerprint('0'))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    static MirrorStateTransitionRunEvidence evidence() {
        MirrorArtifactRef capability = new MirrorArtifactRef(
                "CAPABILITY", "customer.update", 1,
                fingerprint('1'));
        MirrorArtifactRef effect = new MirrorArtifactRef(
                "WRITE_EFFECT", "update-customer", 1,
                fingerprint('2'));
        MirrorArtifactRef initial = new MirrorArtifactRef(
                "SESSION_STATE", "session-a", 1,
                fingerprint('3'));
        MirrorArtifactRef terminal = new MirrorArtifactRef(
                "SESSION_STATE", "session-a", 2,
                fingerprint('4'));
        MirrorArtifactRef model = new MirrorArtifactRef(
                "STATE_MODEL", "customer-world", 1,
                fingerprint('5'));
        String initialWorld = fingerprint('6');
        String finalWorld = fingerprint('7');
        String receipt = fingerprint('8');
        return new MirrorStateTransitionRunEvidence(
                MirrorStateTransitionRunEvidence
                        .SCHEMA_VERSION,
                "", "run-state-rw-1",
                fingerprint('9'), initial, terminal,
                model, 0, 1, initialWorld,
                finalWorld, STARTED,
                STARTED.plusSeconds(1),
                MirrorStateTransitionRunEvidence.Mode
                        .SERIALIZABLE_READ_WRITE,
                List.of(
                        new MirrorStateTransitionRunEvidence
                                .StatefulBinding(
                                "/root/updateCustomer#PRIMARY",
                                "/root", capability,
                                MirrorStateTransitionRunEvidence
                                        .Interaction.WRITE,
                                null, effect)),
                List.of(),
                List.of(
                        new MirrorStateTransitionRunEvidence
                                .StateTransition(
                                "/root/updateCustomer#PRIMARY",
                                "/root", "", 1, 1,
                                capability, effect,
                                initial, terminal, 0, 1,
                                initialWorld, finalWorld,
                                STARTED,
                                STARTED.plusSeconds(1),
                                fingerprint('a'),
                                fingerprint('b'),
                                fingerprint('c'),
                                receipt, fingerprint('d'),
                                finalWorld,
                                STARTED.plusSeconds(1),
                                false,
                                List.of(
                                        new MirrorStateTransitionRunEvidence
                                                .TransitionEvent(
                                                fingerprint('e'),
                                                1,
                                                "update-customer",
                                                SessionStateSpace
                                                        .TransitionOperation
                                                        .UPDATE,
                                                "customer",
                                                fingerprint('f'),
                                                fingerprint('1'),
                                                fingerprint('2'),
                                                STARTED.plusSeconds(1),
                                                fingerprint('3'))))),
                List.of());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
