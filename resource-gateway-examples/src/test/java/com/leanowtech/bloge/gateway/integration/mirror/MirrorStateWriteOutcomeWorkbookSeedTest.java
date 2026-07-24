package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateWriteOutcomeWorkbookSeedTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final MirrorPlan plan =
            MirrorPersistenceTestFixtures.plan(
                    mapper,
                    MirrorPersistenceTestFixtures
                            .scope("org-a"),
                    "write-outcome-seed-plan", '1');

    @Test
    void projectsCommittedAttemptWithExactReceiptClosure() {
        MirrorEvidenceBundle bundle =
                MirrorPersistenceTestFixtures
                        .writeOutcomeEvidence(
                                mapper,
                                new InMemoryVisualEvidenceSigner(),
                                plan,
                                "write-outcome-seed-run", '2');

        MirrorStateWriteOutcomeWorkbookSeed seed =
                MirrorStateWriteOutcomeWorkbookSeed.project(
                        mapper, bundle);

        seed.verify(mapper);
        assertThat(seed.runId())
                .isEqualTo("write-outcome-seed-run");
        assertThat(seed.stateEvidenceRef().revision())
                .isEqualTo(3);
        assertThat(seed.writeAttemptCount()).isEqualTo(1);
        assertThat(seed.committedCount()).isEqualTo(1);
        assertThat(seed.replayedCount()).isZero();
        assertThat(seed.rejectedCount()).isZero();
        assertThat(seed.preCommitFailedCount()).isZero();
        assertThat(seed.commitOutcomeUnknownCount())
                .isZero();
        assertThat(seed.eventCount()).isEqualTo(1);
        assertThat(seed.stateAdvanced()).isTrue();
        assertThat(seed.writeAttemptAssertions())
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.outcome())
                            .isEqualTo(
                                    MirrorStateWriteOutcomeRunEvidence
                                            .WriteOutcome.COMMITTED);
                    assertThat(attempt.transition())
                            .isNotNull();
                    assertThat(attempt.failureFingerprint())
                            .isBlank();
                });
        assertThat(mapper.valueToTree(seed).toString())
                .doesNotContain("idempotencyKey\"")
                .doesNotContain("entityId\"")
                .doesNotContain("response\"");
    }

    @Test
    void projectsRejectedAttemptAsExpectedOutcomeBlocker() {
        MirrorEvidenceBundle bundle =
                MirrorPersistenceTestFixtures
                        .rejectedWriteOutcomeEvidence(
                                mapper,
                                new InMemoryVisualEvidenceSigner(),
                                plan,
                                "write-rejected-seed-run", '3');

        MirrorStateWriteOutcomeWorkbookSeed seed =
                MirrorStateWriteOutcomeWorkbookSeed.project(
                        mapper, bundle);

        seed.verify(mapper);
        assertThat(seed.rejectedCount()).isEqualTo(1);
        assertThat(seed.committedCount()).isZero();
        assertThat(seed.stateAdvanced()).isFalse();
        assertThat(seed.initialSessionStateRef())
                .isEqualTo(seed.finalSessionStateRef());
        assertThat(seed.gateReady()).isFalse();
        assertThat(seed.blockers()).contains(
                "RUN_NOT_PASSED",
                "STATE_WRITE_REJECTION_REQUIRES_EXPECTATION");
        assertThat(seed.writeAttemptAssertions())
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.outcome())
                            .isEqualTo(
                                    MirrorStateWriteOutcomeRunEvidence
                                            .WriteOutcome.REJECTED);
                    assertThat(attempt.transition()).isNull();
                    assertThat(attempt.errorCode())
                            .isEqualTo(
                                    "RG.MIRROR.STATE.PRECONDITION_FAILED");
                    assertThat(attempt.failureFingerprint())
                            .startsWith("sha256:");
                });
    }

    @Test
    void rejectsLegacyReadWriteBundleAndTamperedSeed() {
        MirrorEvidenceBundle legacy =
                MirrorPersistenceTestFixtures
                        .readWriteEvidence(
                                mapper,
                                new InMemoryVisualEvidenceSigner(),
                                plan, "legacy-run", '4');

        assertThatThrownBy(() ->
                MirrorStateWriteOutcomeWorkbookSeed.project(
                        mapper, legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verified v5 bundle");

        MirrorStateWriteOutcomeWorkbookSeed seed =
                MirrorStateWriteOutcomeWorkbookSeed.project(
                        mapper,
                        MirrorPersistenceTestFixtures
                                .writeOutcomeEvidence(
                                        mapper,
                                        new InMemoryVisualEvidenceSigner(),
                                        plan,
                                        "tamper-run", '5'));
        MirrorStateWriteOutcomeWorkbookSeed tampered =
                seed.withFingerprint(
                        MirrorPersistenceTestFixtures
                                .fingerprint('f'));
        assertThatThrownBy(() -> tampered.verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }
}
