package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchWorkbookSeedTest {
    private static final Instant AT =
            ScenarioRehearsalBatchEvidenceTestFixtures.COMPLETED
                    .plusSeconds(2);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void projectsOneGateReadyBatchDeterministically() {
        Fixture fixture = fixture(true);

        ScenarioRehearsalBatchWorkbookSeed first =
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()),
                        Map.of(
                                fixture.child().runId(),
                                fixture.child()));
        ScenarioRehearsalBatchWorkbookSeed second =
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()),
                        Map.of(
                                fixture.child().runId(),
                                fixture.child()));

        assertThat(first).isEqualTo(second);
        assertThat(first.gateReady()).isTrue();
        assertThat(first.blockers()).isEmpty();
        assertThat(first.entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.entryId())
                            .isEqualTo("refund-happy-path");
                    assertThat(entry.evidenceBacked()).isTrue();
                    assertThat(entry.childWorkbook().gateReady())
                            .isTrue();
                    assertThat(entry.childWorkbook()
                            .seedFingerprint())
                            .isEqualTo(
                                    fixture.child()
                                            .seedFingerprint());
                });
        first.verify(mapper);
    }

    @Test
    void blocksAPassingBatchWhenTheChildWorkbookIsExploratory() {
        Fixture fixture = fixture(false);

        ScenarioRehearsalBatchWorkbookSeed seed =
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()),
                        Map.of(
                                fixture.child().runId(),
                                fixture.child()));

        assertThat(seed.status())
                .isEqualTo(
                        ScenarioRehearsalBatchJob.Status.SUCCEEDED);
        assertThat(seed.summary().passedItems()).isOne();
        assertThat(seed.gateReady()).isFalse();
        assertThat(seed.blockers())
                .containsExactly("CHILD_WORKBOOK_BLOCKED");
        assertThat(seed.entries().getFirst()
                .childWorkbook().blockers())
                .containsExactly(
                        "CHILD_EVIDENCE_NOT_CERTIFIABLE");
    }

    @Test
    void detachedSealAuthenticatesButDoesNotChangeSeedIdentity() {
        Fixture fixture = fixture(true);
        ScenarioRehearsalBatchWorkbookSeed material =
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()),
                        Map.of(
                                fixture.child().runId(),
                                fixture.child()));
        InMemoryVisualEvidenceSigner signer =
                new InMemoryVisualEvidenceSigner();
        String attestation =
                material.attestationMaterialFingerprint(
                        mapper);
        VisualRunEvidenceSeal seal =
                signer.seal(attestation);

        ScenarioRehearsalBatchWorkbookSeed signed =
                material.withWorkbookSeal(seal);

        assertThat(signed.seedFingerprint())
                .isEqualTo(material.seedFingerprint());
        assertThat(signed.workbookSeal())
                .isEqualTo(seal);
        assertThat(signer.verify(
                signed.workbookSeal(),
                signed.attestationMaterialFingerprint(
                        mapper)).valid()).isTrue();
        signed.verify(mapper);
    }

    @Test
    void rejectsMissingExtraAndSubstitutedChildWorkbooks() {
        Fixture fixture = fixture(true);
        ScenarioRehearsalWorkbookSeed extra =
                child(
                        fixture.material(),
                        true,
                        "scenario-" + "f".repeat(64));
        Map<String, ScenarioRehearsalWorkbookSeed> children =
                new LinkedHashMap<>();
        children.put(
                fixture.child().runId(),
                fixture.child());
        children.put(extra.runId(), extra);

        assertThatThrownBy(() ->
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() ->
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()),
                        children))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreferenced");
        assertThatThrownBy(() ->
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()),
                        Map.of(
                                fixture.child().runId(),
                                fixture.child()
                                        .withFingerprint(
                                                fingerprint('f')))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    @Test
    void rejectsCrossEvidenceRetentionAndTamperedSeedIdentity() {
        Fixture fixture = fixture(true);
        ScenarioRehearsalBatchRetentionState drifted =
                new ScenarioRehearsalBatchRetentionState(
                        "",
                        fixture.retentionState().scope(),
                        fixture.retentionState().requestId(),
                        fixture.retentionState().jobId(),
                        fixture.retentionState()
                                .manifestFingerprint(),
                        fingerprint('f'),
                        fixture.retentionState().status(),
                        fixture.retentionState().revision(),
                        fixture.retentionState().retainUntil(),
                        fixture.retentionState().activeHoldIds(),
                        fixture.retentionState().updatedAt(),
                        batchRegistration(
                                fixture.material(),
                                fingerprint('f')));

        assertThatThrownBy(() ->
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        drifted,
                        List.of(drifted.latestEvent()),
                        Map.of(
                                fixture.child().runId(),
                                fixture.child())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active closure");

        ScenarioRehearsalBatchWorkbookSeed seed =
                ScenarioRehearsalBatchWorkbookSeed.project(
                        mapper,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()),
                        Map.of(
                                fixture.child().runId(),
                                fixture.child()));
        assertThatThrownBy(() ->
                seed.withFingerprint(fingerprint('f'))
                        .verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    private Fixture fixture(boolean childGateReady) {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures
                        .material(mapper);
        ScenarioRehearsalWorkbookSeed child =
                child(
                        material,
                        childGateReady,
                        material.manifest().entries()
                                .getFirst().aggregateRunId());
        ScenarioRehearsalBatchItemPage.Item original =
                material.items().getFirst();
        ScenarioRehearsalBatchItemPage.Item item =
                new ScenarioRehearsalBatchItemPage.Item(
                        original.itemIndex(),
                        original.compiledPlanRef(),
                        original.childRequestId(),
                        original.status(),
                        original.attemptCount(),
                        original.runId(),
                        original.evidenceBundleFingerprint(),
                        child.seedFingerprint(),
                        original.failureCode(),
                        original.startedAt(),
                        original.completedAt());
        ScenarioRehearsalBatchEvidenceIndex index =
                new ScenarioRehearsalBatchEvidenceIndex(
                        "",
                        fingerprint('b'),
                        material.request(),
                        material.manifest(),
                        material.job(),
                        List.of(item));
        ScenarioRehearsalBatchEvidenceAttestation
                attestation =
                new ScenarioRehearsalBatchEvidenceAttestation(
                        "",
                        ScenarioRehearsalBatchEvidenceAttestation
                                .SignatureStatus.VERIFIED,
                        material.job().jobId(),
                        material.job().requestFingerprint(),
                        material.manifest()
                                .manifestFingerprint(),
                        material.job().recordFingerprint(),
                        index.indexFingerprint(),
                        AT,
                        "batch-evidence-key",
                        "Ed25519",
                        "c2lnbmF0dXJl",
                        true);
        ScenarioRehearsalBatchEvidenceBundle bundle =
                new ScenarioRehearsalBatchEvidenceBundle(
                        "",
                        fingerprint('c'),
                        ScenarioRehearsalBatchEvidenceBundle
                                .PayloadPolicy.HASH_ONLY,
                        attestation,
                        index);
        ScenarioRehearsalBatchRetentionEvent registration =
                batchRegistration(
                        material,
                        bundle.bundleFingerprint());
        ScenarioRehearsalBatchRetentionState state =
                new ScenarioRehearsalBatchRetentionState(
                        "",
                        material.job().scope(),
                        material.job().requestId(),
                        material.job().jobId(),
                        material.manifest()
                                .manifestFingerprint(),
                        bundle.bundleFingerprint(),
                        ScenarioRehearsalBatchRetentionState
                                .Status.RETAINED,
                        1,
                        registration.retainUntil(),
                        List.of(),
                        registration.occurredAt(),
                        registration);
        return new Fixture(
                material,
                child,
                bundle,
                registration,
                state);
    }

    private ScenarioRehearsalWorkbookSeed child(
            ScenarioRehearsalBatchEvidenceTestFixtures.Material
                    material,
            boolean gateReady,
            String runId) {
        MirrorArtifactRef scenarioCase =
                ref("SCENARIO_CASE", "refund-happy-path", '1');
        MirrorArtifactRef testSuite =
                ref("TEST_SUITE", "refund-suite", '2');
        MirrorArtifactRef mirrorPlan =
                ref("MIRROR_PLAN", "refund-mirror", '3');
        MirrorArtifactRef fixture =
                ref("FIXTURE_BUNDLE", "refund-fixture", '4');
        MirrorArtifactRef assertionRef =
                ref(
                        "CASE_HANDLING_ASSERTION",
                        "refund-approved",
                        '5');
        String evidenceFingerprint =
                material.items().getFirst()
                        .evidenceBundleFingerprint();
        ScenarioHandlingAssertionResult assertion =
                new ScenarioHandlingAssertionResult(
                        "",
                        "",
                        "mirror-refund-run",
                        fingerprint('6'),
                        mirrorPlan.fingerprint(),
                        assertionRef,
                        CaseHandlingAssertion.Observation
                                .NODE_STATUS,
                        ScenarioHandlingAssertionResult.Outcome
                                .PASS,
                        CaseHandlingAssertion.Severity.BLOCKER,
                        "RG.MIRROR.SCENARIO.REFUND_APPROVED",
                        ScenarioHandlingAssertionResult.ReasonCode
                                .ASSERTION_MATCHED,
                        ScenarioHandlingAssertionResult
                                .ObservedFacts.empty());
        ScenarioRehearsalWorkbookSeed.CaseResult result =
                new ScenarioRehearsalWorkbookSeed.CaseResult(
                        0,
                        scenarioCase,
                        ScenarioCase.CaseType.GOLDEN,
                        testSuite,
                        "refund-happy-path",
                        mirrorPlan,
                        fixture,
                        null,
                        assertion.runId(),
                        assertion.evidenceBundleFingerprint(),
                        MirrorRunEvidence.Status.PASSED.name(),
                        gateReady
                                ? MirrorRunEvidence.EvidenceClass
                                .CERTIFIABLE.name()
                                : MirrorRunEvidence.EvidenceClass
                                .EXPLORATORY.name(),
                        ScenarioCaseRehearsalResult.Outcome.PASS,
                        "",
                        List.of(assertion));
        ScenarioRehearsalResult.Summary summary =
                new ScenarioRehearsalResult.Summary(
                        1, 1, 0, 0, 1,
                        0, 0, 0, 0);
        ScenarioRehearsalRetentionEvent registration =
                childRegistration(
                        runId,
                        material.manifest().entries()
                                .getFirst().aggregateRequestId(),
                        evidenceFingerprint);
        List<String> blockers = gateReady
                ? List.of()
                : List.of(
                        "CHILD_EVIDENCE_NOT_CERTIFIABLE");
        ScenarioRehearsalWorkbookSeed seed =
                new ScenarioRehearsalWorkbookSeed(
                        "",
                        "",
                        material.job().scope(),
                        runId,
                        material.manifest().entries()
                                .getFirst().aggregateRequestId(),
                        ref(
                                "SCENARIO_PACK",
                                "refund-scenarios",
                                '7'),
                        material.manifest().entries()
                                .getFirst().compiledPlanRef(),
                        ref(
                                "CAPABILITY",
                                "refund-capability",
                                '8'),
                        evidenceFingerprint,
                        fingerprint('9'),
                        "child-evidence-key",
                        registration,
                        ScenarioCaseRehearsalResult.Outcome.PASS,
                        summary,
                        List.of(result),
                        gateReady,
                        blockers);
        return seed.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper,
                        seed,
                        ScenarioRehearsalWorkbookSeed
                                .MAXIMUM_CANONICAL_BYTES));
    }

    private ScenarioRehearsalRetentionEvent childRegistration(
            String runId,
            String requestId,
            String evidenceFingerprint) {
        ScenarioRehearsalRetentionEvent material =
                new ScenarioRehearsalRetentionEvent(
                        "",
                        "child-retention-event",
                        "child-retention-register",
                        ScenarioRehearsalBatchEvidenceTestFixtures
                                .SCOPE,
                        requestId,
                        runId,
                        1,
                        ScenarioRehearsalRetentionEvent.Type
                                .RETENTION_REGISTERED,
                        AT.plus(Duration.ofDays(30)),
                        AT,
                        "scenario-runtime",
                        "RG.MIRROR.RETENTION_REGISTERED",
                        "",
                        evidenceFingerprint,
                        "",
                        0,
                        ScenarioRehearsalRetentionEvent
                                .ChildEvidenceDisposition
                                .NOT_APPLICABLE,
                        VisualRunEvidenceSeal.unsigned());
        return material.withEvidenceSeal(
                seal(
                        material.eventFingerprint(),
                        "child-retention-key"));
    }

    private ScenarioRehearsalBatchRetentionEvent
    batchRegistration(
            ScenarioRehearsalBatchEvidenceTestFixtures.Material
                    material,
            String evidenceFingerprint) {
        ScenarioRehearsalBatchRetentionEvent event =
                new ScenarioRehearsalBatchRetentionEvent(
                        "",
                        "batch-retention-event",
                        "batch-retention-register",
                        material.job().scope(),
                        material.job().requestId(),
                        material.job().jobId(),
                        material.manifest()
                                .manifestFingerprint(),
                        1,
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .RETENTION_REGISTERED,
                        AT.plus(Duration.ofDays(30)),
                        AT,
                        "scenario-batch-finalizer",
                        "RG.MIRROR.REHEARSAL_BATCH.RETENTION_REGISTERED",
                        "",
                        evidenceFingerprint,
                        "",
                        0,
                        0,
                        0,
                        ScenarioRehearsalBatchRetentionEvent
                                .PreservedDisposition
                                .NOT_APPLICABLE,
                        ScenarioRehearsalBatchRetentionEvent
                                .PreservedDisposition
                                .NOT_APPLICABLE,
                        VisualRunEvidenceSeal.unsigned());
        return event.withEvidenceSeal(
                seal(
                        event.eventFingerprint(),
                        "batch-retention-key"));
    }

    private static VisualRunEvidenceSeal seal(
            String fingerprint,
            String keyId) {
        return new VisualRunEvidenceSeal(
                "",
                fingerprint,
                "Ed25519",
                keyId,
                AT,
                "c2lnbmF0dXJl");
    }

    private static MirrorArtifactRef ref(
            String kind,
            String id,
            char fingerprint) {
        return new MirrorArtifactRef(
                kind,
                id,
                1,
                fingerprint(fingerprint));
    }

    private static String fingerprint(char value) {
        return ScenarioRehearsalBatchEvidenceTestFixtures
                .fingerprint(value);
    }

    private record Fixture(
            ScenarioRehearsalBatchEvidenceTestFixtures.Material
                    material,
            ScenarioRehearsalWorkbookSeed child,
            ScenarioRehearsalBatchEvidenceBundle bundle,
            ScenarioRehearsalBatchRetentionEvent registration,
            ScenarioRehearsalBatchRetentionState retentionState
    ) {
    }
}
