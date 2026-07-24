package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateTransitionWorkbookSeedTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void projectsOnePayloadFreeCommittedWriteAndItsEventClosure() throws Exception {
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                mapper, MirrorPersistenceTestFixtures.scope("org-a"),
                "transition-workbook-plan", '1');
        MirrorEvidenceBundle bundle =
                MirrorPersistenceTestFixtures.readWriteEvidence(
                        mapper, new InMemoryVisualEvidenceSigner(),
                        plan, "transition-workbook-run", '2');

        MirrorStateTransitionWorkbookSeed seed =
                MirrorStateTransitionWorkbookSeed.project(
                        mapper, bundle);

        seed.verify(mapper);
        assertThat(seed.runId())
                .isEqualTo("transition-workbook-run");
        assertThat(seed.evidenceBundleFingerprint())
                .isEqualTo(bundle.bundleFingerprint());
        assertThat(seed.initialStateRevision()).isZero();
        assertThat(seed.finalStateRevision()).isEqualTo(1);
        assertThat(seed.transitionCount()).isEqualTo(1);
        assertThat(seed.committedTransitionCount()).isEqualTo(1);
        assertThat(seed.replayedTransitionCount()).isZero();
        assertThat(seed.eventCount()).isEqualTo(1);
        assertThat(seed.stateAdvanced()).isTrue();
        assertThat(seed.gateReady()).isFalse();
        assertThat(seed.blockers()).containsExactly(
                "EVIDENCE_NOT_CERTIFIABLE",
                "RUN_EVIDENCE_LIMITED");
        assertThat(seed.writeAssertions()).singleElement()
                .satisfies(write -> {
                    assertThat(write.revisionBefore()).isZero();
                    assertThat(write.revisionAfter()).isEqualTo(1);
                    assertThat(write.replayed()).isFalse();
                    assertThat(write.events()).singleElement()
                            .satisfies(event -> {
                                assertThat(event.stateRevision())
                                        .isEqualTo(1);
                                assertThat(event.mutationId())
                                        .isEqualTo("update-customer");
                            });
                });
        assertThat(mapper.writeValueAsString(seed))
                .doesNotContain("customer-raw-id")
                .doesNotContain("raw-idempotency-key")
                .doesNotContain("customer-secret")
                .doesNotContain("\"entityId\"")
                .contains("idempotencyKeyFingerprint")
                .contains("entityIdentityFingerprint");
    }

    @Test
    void detectsSeedTamperingAndRefusesReadOnlyEvidence() {
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                mapper, MirrorPersistenceTestFixtures.scope("org-a"),
                "transition-workbook-plan", '3');
        InMemoryVisualEvidenceSigner signer =
                new InMemoryVisualEvidenceSigner();
        MirrorStateTransitionWorkbookSeed seed =
                MirrorStateTransitionWorkbookSeed.project(
                        mapper,
                        MirrorPersistenceTestFixtures.readWriteEvidence(
                                mapper, signer, plan,
                                "transition-workbook-run", '4'));

        assertThatThrownBy(() -> seed.withFingerprint(
                MirrorPersistenceTestFixtures.fingerprint('f'))
                .verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
        assertThatThrownBy(() ->
                MirrorStateTransitionWorkbookSeed.project(
                        mapper,
                        MirrorPersistenceTestFixtures.statefulEvidence(
                                mapper, signer, plan,
                                "read-only-run", '5')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verified v4 bundle");
    }
}
