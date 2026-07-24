package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateWriteAttemptTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private static final String FINGERPRINT =
            "sha256:" + "1".repeat(64);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void sealsDeterministicallyWithoutBusinessPayload() throws Exception {
        MirrorStateWriteAttempt first =
                MirrorStateWriteAttemptIntegrity.seal(
                        mapper, inProgress());
        MirrorStateWriteAttempt second =
                MirrorStateWriteAttemptIntegrity.seal(
                        mapper, inProgress());
        String json = mapper.writeValueAsString(first);

        assertThat(second).isEqualTo(first);
        assertThat(first.fingerprint())
                .startsWith("sha256:");
        assertThat(json)
                .doesNotContain("idempotencyKey")
                .doesNotContain("\"input\"")
                .doesNotContain("\"response\"")
                .doesNotContain("customer");
        assertThat(first.toString())
                .doesNotContain(first.commandFingerprint())
                .doesNotContain(first.requestFingerprint())
                .doesNotContain(first.scope().tenantId());
    }

    @Test
    void fingerprintTamperingAndNestedGenerationDriftFailClosed()
            throws Exception {
        MirrorStateWriteAttempt sealed =
                MirrorStateWriteAttemptIntegrity.seal(
                        mapper, inProgress());
        ObjectNode tree = mapper.valueToTree(sealed);
        tree.put("requestFingerprint",
                "sha256:" + "2".repeat(64));
        MirrorStateWriteAttempt tampered =
                mapper.treeToValue(
                        tree, MirrorStateWriteAttempt.class);

        assertThatThrownBy(() ->
                MirrorStateWriteAttemptIntegrity.verify(
                        mapper, tampered))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    @Test
    void lifecycleRejectsTerminalClaimsWithoutExactClosure() {
        MirrorStateWriteAttempt initial = inProgress();

        assertThatThrownBy(() -> new MirrorStateWriteAttempt(
                initial.schemaVersion(), initial.scope(),
                initial.sessionId(), initial.attemptId(),
                initial.coordinate(), initial.storeGeneration(),
                initial.planFingerprint(),
                initial.writeEffectRef(),
                initial.requestFingerprint(),
                initial.commandFingerprint(),
                initial.initialStateRevision(),
                initial.initialWorldFingerprint(),
                initial.initialStateFingerprint(),
                MirrorStateWriteAttempt.Status.TERMINAL,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMITTED,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMPLETED,
                MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.ADVANCED,
                1, FINGERPRINT, FINGERPRINT,
                "", false, "", "", "",
                MirrorStateWriteAttempt
                        .ResolutionSource.EXECUTION,
                NOW, NOW, null, ""))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "successful write attempt");
    }

    private MirrorStateWriteAttempt inProgress() {
        CapabilitySnapshot.Scope scope =
                new CapabilitySnapshot.Scope(
                        "tenant-a", "org-a",
                        "project-a", "test", "sg");
        MirrorSessionStoreGeneration generation =
                MirrorSessionStoreGenerationIntegrity.seal(
                        mapper,
                        new MirrorSessionStoreGeneration(
                                "", "store-generation-a",
                                MirrorSessionStoreGeneration
                                        .CURRENT_SCHEMA_REVISION,
                                NOW, ""));
        return new MirrorStateWriteAttempt(
                MirrorStateWriteAttempt.SCHEMA_VERSION,
                scope, "session-a",
                "attempt-00000000-0000-0000-0000-000000000001",
                new MirrorStateWriteAttempt.Coordinate(
                        MirrorStateWriteAttempt.ExecutionKind
                                .GRAPH_RUN,
                        "run-request-a", 1,
                        "/root/refund#PRIMARY",
                        "/root", "", 1, 1),
                generation, FINGERPRINT,
                new MirrorArtifactRef(
                        "WRITE_EFFECT", "refund-effect",
                        1, FINGERPRINT),
                FINGERPRINT, FINGERPRINT,
                0, FINGERPRINT, FINGERPRINT,
                MirrorStateWriteAttempt.Status.IN_PROGRESS,
                null,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMAND_ADMISSION,
                MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.UNKNOWN,
                -1, "", "", "", false,
                "", "", "",
                MirrorStateWriteAttempt
                        .ResolutionSource.EXECUTION,
                NOW, null, null, "");
    }
}
