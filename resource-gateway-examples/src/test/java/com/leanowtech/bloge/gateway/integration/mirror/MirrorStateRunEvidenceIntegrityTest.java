package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateRunEvidenceIntegrityTest {
    private static final String RUN_ID = "mirror-run-state-1";
    private static final String PLAN = fingerprint('1');
    private static final MirrorArtifactRef SESSION = new MirrorArtifactRef(
            "SESSION_STATE", "refund-session-1", 4, fingerprint('2'));
    private static final MirrorArtifactRef MODEL = new MirrorArtifactRef(
            "STATE_MODEL", "refund-model", 2, fingerprint('3'));
    private static final MirrorArtifactRef CAPABILITY = new MirrorArtifactRef(
            "CAPABILITY", "refund.query", 5, fingerprint('4'));
    private static final MirrorArtifactRef READ_SPEC = new MirrorArtifactRef(
            "STATE_READ_SPEC", "query-refund", 3, fingerprint('5'));
    private static final String SITE = "/root/queryRefund#PRIMARY";
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsAndVerifiesPayloadFreeLiveAbsentAndTombstoneAccesses() {
        MirrorStateRunEvidence sealed =
                MirrorStateRunEvidenceIntegrity.seal(
                        mapper, evidence(List.of(
                                access(1,
                                        MirrorStateRunEvidence.AccessOutcome
                                                .LIVE_ENTITY,
                                        fingerprint('6'),
                                        fingerprint('7'), ""),
                                access(2,
                                        MirrorStateRunEvidence.AccessOutcome
                                                .ABSENT,
                                        "", "", ""),
                                access(3,
                                        MirrorStateRunEvidence.AccessOutcome
                                                .TOMBSTONED,
                                        fingerprint('8'), "",
                                        MirrorStateRunEvidence
                                                .MirrorSessionStateError
                                                .ENTITY_TOMBSTONED))));

        MirrorStateRunEvidenceIntegrity.verify(mapper, sealed);

        assertThat(sealed.stateEvidenceFingerprint())
                .startsWith("sha256:");
        assertThat(MirrorStateRunEvidenceIntegrity.reference(sealed))
                .isEqualTo(new MirrorArtifactRef(
                        "MIRROR_STATE_RUN_EVIDENCE", RUN_ID, 1,
                        sealed.stateEvidenceFingerprint()));
        assertThat(sealed.accesses())
                .extracting(MirrorStateRunEvidence.StateAccess::outcome)
                .containsExactly(
                        MirrorStateRunEvidence.AccessOutcome.LIVE_ENTITY,
                        MirrorStateRunEvidence.AccessOutcome.ABSENT,
                        MirrorStateRunEvidence.AccessOutcome.TOMBSTONED);
        assertThat(mapper.valueToTree(sealed).toString())
                .doesNotContain("refund-payload")
                .doesNotContain("customer-id");
        assertThat(sealed.toString())
                .doesNotContain(sealed.worldFingerprint())
                .doesNotContain(sealed.sessionStateRef().fingerprint());
    }

    @Test
    void rejectsAccessOutsideTheDeclaredStatefulBindingClosure() {
        MirrorStateRunEvidence.StateAccess access = new MirrorStateRunEvidence.StateAccess(
                "/root/other#PRIMARY", "/root", "", 1, 1,
                CAPABILITY, READ_SPEC, fingerprint('9'), fingerprint('a'),
                MirrorStateRunEvidence.AccessOutcome.ABSENT,
                "", "", "");

        assertThatThrownBy(() -> evidence(List.of(access)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact stateful binding");
    }

    @Test
    void rejectsPayloadClaimsThatContradictTheAccessOutcome() {
        assertThatThrownBy(() -> access(
                1, MirrorStateRunEvidence.AccessOutcome.ABSENT,
                fingerprint('6'), "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot claim");
        assertThatThrownBy(() -> access(
                1, MirrorStateRunEvidence.AccessOutcome.LIVE_ENTITY,
                fingerprint('6'), "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires record and output");
        assertThatThrownBy(() -> access(
                1, MirrorStateRunEvidence.AccessOutcome.TOMBSTONED,
                fingerprint('6'), "", "WRONG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal error");
    }

    @Test
    void detectsTamperingAndStateRevisionReferenceMismatch() {
        MirrorStateRunEvidence sealed =
                MirrorStateRunEvidenceIntegrity.seal(
                        mapper, evidence(List.of(access(
                                1,
                                MirrorStateRunEvidence.AccessOutcome.ABSENT,
                                "", "", ""))));
        MirrorStateRunEvidence altered = new MirrorStateRunEvidence(
                sealed.schemaVersion(), sealed.stateEvidenceFingerprint(),
                sealed.runId(), sealed.planFingerprint(),
                sealed.sessionStateRef(), sealed.stateModelRef(),
                sealed.stateRevision(), fingerprint('f'),
                sealed.logicalClock(), sealed.mode(),
                sealed.statefulBindings(), sealed.accesses(),
                sealed.limitations());

        assertThatThrownBy(() ->
                MirrorStateRunEvidenceIntegrity.verify(mapper, altered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
        assertThatThrownBy(() -> new MirrorStateRunEvidence(
                "", "", RUN_ID, PLAN,
                new MirrorArtifactRef(
                        "SESSION_STATE", "refund-session-1",
                        99, fingerprint('2')),
                MODEL, 3, fingerprint('b'),
                Instant.parse("2026-07-24T00:00:00Z"),
                MirrorStateRunEvidence.Mode.READ_ONLY_SNAPSHOT,
                List.of(binding()), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateRevision + 1");
    }

    private static MirrorStateRunEvidence evidence(
            List<MirrorStateRunEvidence.StateAccess> accesses) {
        return new MirrorStateRunEvidence(
                MirrorStateRunEvidence.SCHEMA_VERSION, "",
                RUN_ID, PLAN, SESSION, MODEL, 3,
                fingerprint('b'),
                Instant.parse("2026-07-24T00:00:00Z"),
                MirrorStateRunEvidence.Mode.READ_ONLY_SNAPSHOT,
                List.of(binding()), accesses, List.of());
    }

    private static MirrorStateRunEvidence.StatefulBinding binding() {
        return new MirrorStateRunEvidence.StatefulBinding(
                SITE, "/root", CAPABILITY, READ_SPEC);
    }

    private static MirrorStateRunEvidence.StateAccess access(
            int occurrence,
            MirrorStateRunEvidence.AccessOutcome outcome,
            String recordFingerprint,
            String outputFingerprint,
            String errorCode) {
        return new MirrorStateRunEvidence.StateAccess(
                SITE, "/root", "", occurrence, 1,
                CAPABILITY, READ_SPEC, fingerprint('9'),
                fingerprint((char) ('a' + occurrence)),
                outcome, recordFingerprint, outputFingerprint, errorCode);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
