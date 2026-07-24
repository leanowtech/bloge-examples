package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateWriteOutcomeRunEvidenceIntegrityTest {
    private static final Instant STARTED =
            Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsCommittedAndRejectedWriteAttemptsWithoutPayloads()
            throws Exception {
        MirrorStateWriteOutcomeRunEvidence sealed =
                MirrorStateWriteOutcomeRunEvidenceIntegrity
                        .seal(mapper, committedThenRejected());

        MirrorStateWriteOutcomeRunEvidenceIntegrity.verify(
                mapper, sealed);
        assertThat(sealed.stateEvidenceFingerprint())
                .startsWith("sha256:");
        assertThat(MirrorStateWriteOutcomeRunEvidenceIntegrity
                .reference(sealed))
                .isEqualTo(new MirrorArtifactRef(
                        "MIRROR_STATE_RUN_EVIDENCE",
                        sealed.runId(), 3,
                        sealed.stateEvidenceFingerprint()));
        assertThat(sealed.writeAttempts())
                .extracting(
                        MirrorStateWriteOutcomeRunEvidence
                                .StateWriteAttempt::outcome)
                .containsExactly(
                        MirrorStateWriteOutcomeRunEvidence
                                .WriteOutcome.COMMITTED,
                        MirrorStateWriteOutcomeRunEvidence
                                .WriteOutcome.REJECTED);
        assertThat(sealed.finalStateRevision())
                .isEqualTo(1);
        String json = mapper.writeValueAsString(sealed);
        assertThat(json)
                .doesNotContain("customer-raw-id")
                .doesNotContain("raw-idempotency-key")
                .doesNotContain("customer-secret")
                .contains("failureFingerprint")
                .contains("receiptFingerprint");
    }

    @Test
    void requiresUnknownCommitOutcomeToBlockCertification() {
        MirrorStateWriteOutcomeRunEvidence unknown =
                unknownCommitOutcome();

        MirrorStateWriteOutcomeRunEvidence sealed =
                MirrorStateWriteOutcomeRunEvidenceIntegrity
                        .seal(mapper, unknown);

        assertThat(sealed.limitations()).containsExactly(
                MirrorStateWriteOutcomeRunEvidence
                        .UNKNOWN_OUTCOME_LIMITATION);
        assertThat(sealed.writeAttempts())
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(
                            MirrorStateWriteOutcomeRunEvidence
                                    .WriteOutcome
                                    .COMMIT_OUTCOME_UNKNOWN);
                    assertThat(attempt.stateDisposition())
                            .isEqualTo(
                                    MirrorStateWriteOutcomeRunEvidence
                                            .StateDisposition.UNKNOWN);
                    assertThat(attempt.transition()).isNull();
                });
    }

    @Test
    void rejectsUnknownCommitOutcomeWithoutItsMandatoryLimitation() {
        MirrorStateWriteOutcomeRunEvidence source =
                unknownCommitOutcome();

        assertThatThrownBy(() ->
                new MirrorStateWriteOutcomeRunEvidence(
                        source.schemaVersion(), "",
                        source.runId(),
                        source.planFingerprint(),
                        source.sessionStateRef(),
                        source.finalSessionStateRef(),
                        source.stateModelRef(),
                        source.stateRevision(),
                        source.finalStateRevision(),
                        source.worldFingerprint(),
                        source.finalWorldFingerprint(),
                        source.logicalClock(),
                        source.finalLogicalClock(),
                        source.mode(),
                        source.statefulBindings(),
                        source.accesses(),
                        source.writeAttempts(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "unknown write outcomes");
    }

    @Test
    void rejectsTamperedFailureFingerprint() {
        MirrorStateWriteOutcomeRunEvidence source =
                unknownCommitOutcome();
        MirrorStateWriteOutcomeRunEvidence.StateWriteAttempt
                attempt = source.writeAttempts().getFirst();
        MirrorStateWriteOutcomeRunEvidence tampered =
                new MirrorStateWriteOutcomeRunEvidence(
                        source.schemaVersion(), "",
                        source.runId(),
                        source.planFingerprint(),
                        source.sessionStateRef(),
                        source.finalSessionStateRef(),
                        source.stateModelRef(),
                        source.stateRevision(),
                        source.finalStateRevision(),
                        source.worldFingerprint(),
                        source.finalWorldFingerprint(),
                        source.logicalClock(),
                        source.finalLogicalClock(),
                        source.mode(),
                        source.statefulBindings(),
                        source.accesses(),
                        List.of(new MirrorStateWriteOutcomeRunEvidence
                                .StateWriteAttempt(
                                attempt.invocationSiteId(),
                                attempt.graphPath(),
                                attempt.correlationKey(),
                                attempt.occurrence(),
                                attempt.attempt(),
                                attempt.capabilityRef(),
                                attempt.writeEffectRef(),
                                attempt.observedStateRef(),
                                attempt.observedStateRevision(),
                                attempt.observedWorldFingerprint(),
                                attempt.observedLogicalClock(),
                                attempt.requestFingerprint(),
                                attempt.outcome(),
                                attempt.stage(),
                                attempt.stateDisposition(),
                                attempt.retryable(),
                                attempt.errorCode(),
                                attempt.errorType(),
                                fingerprint('0'), null)),
                        source.limitations());

        assertThatThrownBy(() ->
                MirrorStateWriteOutcomeRunEvidenceIntegrity
                        .seal(mapper, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "failure fingerprint mismatch");
    }

    private MirrorStateWriteOutcomeRunEvidence
            committedThenRejected() {
        MirrorArtifactRef capability =
                capability();
        MirrorArtifactRef effect = effect();
        MirrorArtifactRef initial = state(1, '3');
        MirrorArtifactRef terminal = state(2, '4');
        String initialWorld = fingerprint('5');
        String finalWorld = fingerprint('6');
        String committedRequest = fingerprint('7');
        MirrorStateTransitionRunEvidence.StateTransition
                transition = transition(
                capability, effect, initial, terminal,
                initialWorld, finalWorld,
                committedRequest);
        String rejectedRequest = fingerprint('8');
        String failure = failureFingerprint(
                effect, terminal, 1, finalWorld,
                STARTED.plusSeconds(1),
                rejectedRequest,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REJECTED,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMAND_EVALUATION,
                MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.UNCHANGED,
                false,
                "RG.MIRROR.STATE.PRECONDITION_FAILED",
                "MIRROR_STATE_WRITE");
        return evidence(
                initial, terminal, 0, 1,
                initialWorld, finalWorld,
                List.of(
                        new MirrorStateWriteOutcomeRunEvidence
                                .StateWriteAttempt(
                                "/root/updateCustomer#PRIMARY",
                                "/root", "", 1, 1,
                                capability, effect, initial, 0,
                                initialWorld, STARTED,
                                committedRequest,
                                MirrorStateWriteOutcomeRunEvidence
                                        .WriteOutcome.COMMITTED,
                                MirrorStateWriteOutcomeRunEvidence
                                        .WriteStage.COMPLETED,
                                MirrorStateWriteOutcomeRunEvidence
                                        .StateDisposition.ADVANCED,
                                false, "", "", "",
                                transition),
                        new MirrorStateWriteOutcomeRunEvidence
                                .StateWriteAttempt(
                                "/root/updateCustomer#PRIMARY",
                                "/root", "", 2, 1,
                                capability, effect, terminal, 1,
                                finalWorld,
                                STARTED.plusSeconds(1),
                                rejectedRequest,
                                MirrorStateWriteOutcomeRunEvidence
                                        .WriteOutcome.REJECTED,
                                MirrorStateWriteOutcomeRunEvidence
                                        .WriteStage.COMMAND_EVALUATION,
                                MirrorStateWriteOutcomeRunEvidence
                                        .StateDisposition.UNCHANGED,
                                false,
                                "RG.MIRROR.STATE.PRECONDITION_FAILED",
                                "MIRROR_STATE_WRITE",
                                failure, null)),
                List.of());
    }

    private MirrorStateWriteOutcomeRunEvidence
            unknownCommitOutcome() {
        MirrorArtifactRef initial = state(1, '3');
        String world = fingerprint('5');
        String request = fingerprint('8');
        String failure = failureFingerprint(
                effect(), initial, 0, world, STARTED,
                request,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMIT_OUTCOME_UNKNOWN,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMIT,
                MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.UNKNOWN,
                true,
                "RG.MIRROR.SESSION.STORE_UNAVAILABLE",
                "MIRROR_STATE_WRITE");
        return evidence(
                initial, initial, 0, 0,
                world, world,
                List.of(new MirrorStateWriteOutcomeRunEvidence
                        .StateWriteAttempt(
                        "/root/updateCustomer#PRIMARY",
                        "/root", "", 1, 1,
                        capability(), effect(), initial, 0,
                        world, STARTED, request,
                        MirrorStateWriteOutcomeRunEvidence
                                .WriteOutcome
                                .COMMIT_OUTCOME_UNKNOWN,
                        MirrorStateWriteOutcomeRunEvidence
                                .WriteStage.COMMIT,
                        MirrorStateWriteOutcomeRunEvidence
                                .StateDisposition.UNKNOWN,
                        true,
                        "RG.MIRROR.SESSION.STORE_UNAVAILABLE",
                        "MIRROR_STATE_WRITE",
                        failure, null)),
                List.of(
                        MirrorStateWriteOutcomeRunEvidence
                                .UNKNOWN_OUTCOME_LIMITATION));
    }

    private MirrorStateWriteOutcomeRunEvidence evidence(
            MirrorArtifactRef initial,
            MirrorArtifactRef terminal,
            long initialRevision,
            long finalRevision,
            String initialWorld,
            String finalWorld,
            List<MirrorStateWriteOutcomeRunEvidence
                    .StateWriteAttempt> attempts,
            List<String> limitations) {
        return new MirrorStateWriteOutcomeRunEvidence(
                MirrorStateWriteOutcomeRunEvidence
                        .SCHEMA_VERSION,
                "", "run-state-write-outcome-1",
                fingerprint('9'), initial, terminal,
                new MirrorArtifactRef(
                        "STATE_MODEL", "customer-world",
                        1, fingerprint('a')),
                initialRevision, finalRevision,
                initialWorld, finalWorld,
                STARTED,
                STARTED.plusSeconds(finalRevision),
                MirrorStateWriteOutcomeRunEvidence.Mode
                        .SERIALIZABLE_READ_WRITE_OUTCOMES,
                List.of(new MirrorStateTransitionRunEvidence
                        .StatefulBinding(
                        "/root/updateCustomer#PRIMARY",
                        "/root", capability(),
                        MirrorStateTransitionRunEvidence
                                .Interaction.WRITE,
                        null, effect())),
                List.of(), attempts, limitations);
    }

    private MirrorStateTransitionRunEvidence.StateTransition
            transition(
            MirrorArtifactRef capability,
            MirrorArtifactRef effect,
            MirrorArtifactRef initial,
            MirrorArtifactRef terminal,
            String initialWorld,
            String finalWorld,
            String requestFingerprint) {
        return new MirrorStateTransitionRunEvidence
                .StateTransition(
                "/root/updateCustomer#PRIMARY",
                "/root", "", 1, 1,
                capability, effect,
                initial, terminal, 0, 1,
                initialWorld, finalWorld,
                STARTED,
                STARTED.plusSeconds(1),
                requestFingerprint,
                fingerprint('b'),
                fingerprint('c'),
                fingerprint('d'),
                fingerprint('e'),
                finalWorld,
                STARTED.plusSeconds(1),
                false,
                List.of(new MirrorStateTransitionRunEvidence
                        .TransitionEvent(
                        fingerprint('f'), 1,
                        "update-customer",
                        SessionStateSpace
                                .TransitionOperation.UPDATE,
                        "customer",
                        fingerprint('1'),
                        fingerprint('2'),
                        fingerprint('3'),
                        STARTED.plusSeconds(1),
                        fingerprint('4'))));
    }

    private String failureFingerprint(
            MirrorArtifactRef effectRef,
            MirrorArtifactRef stateRef,
            long revision,
            String world,
            Instant logicalClock,
            String request,
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome,
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            MirrorStateWriteOutcomeRunEvidence.StateDisposition disposition,
            boolean retryable,
            String errorCode,
            String errorType) {
        return MirrorStateWriteOutcomeRunEvidenceIntegrity
                .failureFingerprint(
                        mapper, effectRef, stateRef,
                        revision, world, logicalClock,
                        request, outcome, stage,
                        disposition, retryable,
                        errorCode, errorType);
    }

    private static MirrorArtifactRef capability() {
        return new MirrorArtifactRef(
                "CAPABILITY", "customer.update",
                1, fingerprint('1'));
    }

    private static MirrorArtifactRef effect() {
        return new MirrorArtifactRef(
                "WRITE_EFFECT", "update-customer",
                1, fingerprint('2'));
    }

    private static MirrorArtifactRef state(
            long revision, char fingerprint) {
        return new MirrorArtifactRef(
                "SESSION_STATE", "session-a",
                revision, fingerprint(fingerprint));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value)
                .repeat(64);
    }
}
