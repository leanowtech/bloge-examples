package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlEvidenceBinding;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlMode;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlRunEvidence;
import com.leanowtech.bloge.gateway.testing.function.FunctionInvocationSite;
import com.leanowtech.bloge.gateway.testing.function.FunctionEvidenceCeiling;
import com.leanowtech.bloge.gateway.testing.world.WorldInvocationCoordinate;
import com.leanowtech.bloge.gateway.testing.world.WorldStateSession;
import com.leanowtech.bloge.gateway.testing.world.WorldStateSnapshot;
import com.leanowtech.bloge.gateway.testing.world.WorldStateTransactionObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRunEvidenceProtocolCodecTest {
    private static final String FP = "sha256:" + "a".repeat(64);
    private static final String RUN_ID = "test-run-evidence-1";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TestRunEvidenceProtocolCodec codec = new TestRunEvidenceProtocolCodec(mapper);

    @Test
    void stateAndFunctionProjectionRoundTripsThroughOrdinaryJsonWithoutPayloads() {
        TestRunControlEvidenceProjection projection = projection();
        TestRunEvidence evidence = codec.withControlProjection(baseEvidence(), projection);

        String json = codec.write(evidence);
        TestRunEvidence restored = codec.read(json);

        assertThat(codec.controlProjection(restored)).isEqualTo(projection);
        assertThat(restored.semanticResultFingerprint()).isEqualTo(evidence.semanticResultFingerprint());
        assertThat(json).doesNotContain("snapshotFingerprint", "stateValue", "statePayload");
        assertThat(json).doesNotContain("secret-state", "secret-argument", "secret-result",
                "secret-error", "expectedArguments", "returnValue", "errorMessage");
    }

    @Test
    void tamperedProjectionFingerprintAndMissingProjectionFieldsFailClosed() {
        TestRunControlEvidenceProjection projection = projection();
        String json = codec.write(codec.withControlProjection(baseEvidence(), projection));

        assertThatThrownBy(() -> codec.read(json.replace(projection.projectionFingerprint(), FP)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.TEST.EVIDENCE_PROTOCOL_INVALID");
        assertThatThrownBy(() -> codec.read(json.replace("\"schemaVersion\":\""
                        + TestRunControlEvidenceProjection.SCHEMA_VERSION + "\"", "\"schemaVersion\":\"\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.TEST.EVIDENCE_PROTOCOL_INVALID");
    }

    @Test
    void semanticFingerprintIsStableAcrossRunIdsWhileFullProjectionRemainsRunBound() {
        TestRunControlEvidenceProjection firstProjection =
                TestRunControlEvidenceProjection.from("run-a", FP, FP, FP, FP, FP,
                        snapshot("run-a"), functionEvidence());
        TestRunControlEvidenceProjection secondProjection =
                TestRunControlEvidenceProjection.from("run-b", FP, FP, FP, FP, FP,
                        snapshot("run-b"), functionEvidence());
        TestRunEvidence first = new TestRunEvidenceProtocolCodec(mapper).withControlProjection(
                baseEvidence("run-a"), firstProjection);
        TestRunEvidence second = new TestRunEvidenceProtocolCodec(mapper).withControlProjection(
                baseEvidence("run-b"), secondProjection);

        assertThat(firstProjection.projectionFingerprint())
                .isNotEqualTo(secondProjection.projectionFingerprint());
        for (int i = 0; i < 20; i++) {
            assertThat(TestSemanticResultFingerprint.compute(mapper, first))
                    .isEqualTo(TestSemanticResultFingerprint.compute(mapper, second));
        }
        assertThat(firstProjection.stableSemanticMaterial().toString())
                .doesNotContain("run-a", "run-b", "projectionFingerprint");
    }

    @Test
    void outerBindingAndReservedMetadataAreFailClosed() {
        assertThatThrownBy(() -> TestRunControlEvidenceProjection.from(
                RUN_ID, FP, FP, FP, FP, FP, snapshot("other-run"), functionEvidence()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestRunControlEvidenceProjection.from(
                RUN_ID, FP, FP, "sha256:" + "b".repeat(64), FP, FP,
                snapshot(RUN_ID), functionEvidence()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestRunControlEvidenceProjection.from(
                RUN_ID, FP, FP, FP, FP, "sha256:" + "b".repeat(64),
                snapshot(RUN_ID), functionEvidence()))
                .isInstanceOf(IllegalArgumentException.class);

        TestRunEvidence occupied = new TestRunEvidence(TestRunEvidence.SCHEMA_VERSION, RUN_ID,
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                "GRAPH_CONTRACT_TEST", FP, FP, FP, "", Instant.EPOCH, Instant.EPOCH,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(TestRunEvidenceProtocolCodec.CONTROL_PROJECTION_METADATA_KEY, "guest"));
        assertThatThrownBy(() -> codec.withControlProjection(occupied, projection()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.TEST.EVIDENCE_PROTOCOL_INVALID");
    }

    @Test
    void strictCodecRejectsUnknownDuplicateAndMalformedProtocolFacts() {
        String json = codec.write(codec.withControlProjection(baseEvidence(), projection()));
        String marker = "\"controlEvidenceProjection\":{\"schemaVersion\"";
        assertThatThrownBy(() -> codec.read(json.replace(marker,
                "\"controlEvidenceProjection\":{\"unknown\":1,\"schemaVersion\"")))
                .isInstanceOf(IllegalArgumentException.class);
        String duplicate = json.replace("\"runId\":\"" + RUN_ID + "\"",
                "\"runId\":\"" + RUN_ID + "\",\"runId\":\"" + RUN_ID + "\"");
        assertThatThrownBy(() -> codec.read(duplicate))
                .isInstanceOf(IllegalArgumentException.class);

        TestRunControlEvidenceProjection.TransactionProjection transaction =
                TestRunControlEvidenceProjection.TransactionProjection.from(snapshot().observations().getFirst());
        assertThatThrownBy(() -> new TestRunControlEvidenceProjection.TransactionProjection(
                transaction.coordinate(), List.of("/balance", "/balance"), List.of(), FP, FP, FP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRunControlEvidenceProjection.ConsumptionProjection(
                "rule", 0, 1, 0, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readWriteAndProjectionValidationRejectCrossEnvelopeBindings() throws Exception {
        TestRunControlEvidenceProjection wrongRun = TestRunControlEvidenceProjection.from(
                "foreign-run", FP, FP, FP, FP, FP, snapshot("foreign-run"), functionEvidence());
        TestRunControlEvidenceProjection wrongTarget = TestRunControlEvidenceProjection.from(
                RUN_ID, "", "", "sha256:" + "b".repeat(64), FP, FP, null, functionEvidence());
        TestRunControlEvidenceProjection wrongPlan = TestRunControlEvidenceProjection.from(
                RUN_ID, "", "", FP, "sha256:" + "b".repeat(64), FP, null, functionEvidence());

        for (TestRunControlEvidenceProjection invalid : List.of(wrongRun, wrongTarget, wrongPlan)) {
            TestRunEvidence forged = rawProjectionEvidence(invalid);
            assertThatThrownBy(() -> codec.controlProjection(forged))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("RG.TEST.EVIDENCE_PROTOCOL_INVALID");
            assertThatThrownBy(() -> codec.write(forged))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("RG.TEST.EVIDENCE_PROTOCOL_INVALID");
            assertThatThrownBy(() -> codec.read(mapper.writeValueAsString(forged)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("RG.TEST.EVIDENCE_PROTOCOL_INVALID");
        }
    }

    @Test
    void allEvidenceCeilingsRoundTripThroughExternalProjection() {
        for (FunctionEvidenceCeiling ceiling : FunctionEvidenceCeiling.values()) {
            TestRunControlEvidenceProjection value = TestRunControlEvidenceProjection.from(
                    RUN_ID, FP, FP, FP, FP, FP, snapshot(), functionEvidence(ceiling));
            TestRunEvidence restored = codec.read(codec.write(
                    codec.withControlProjection(baseEvidence(), value)));
            assertThat(codec.controlProjection(restored).function().evidenceCeiling())
                    .isEqualTo(ceiling.name());
        }
    }

    @Test
    void projectionFingerprintIsStableAcrossTwentyComputationsAndBindsBothDomains() {
        TestRunControlEvidenceProjection first = projection();
        for (int i = 0; i < 20; i++) {
            assertThat(TestRunControlEvidenceProjection.from(RUN_ID, FP, FP, FP, FP, FP,
                    snapshot(), functionEvidence())
                    .projectionFingerprint()).isEqualTo(first.projectionFingerprint());
        }
        TestRunControlEvidenceProjection changed = TestRunControlEvidenceProjection.from(
                RUN_ID, FP, FP, FP, FP, FP, snapshot(), new FunctionControlRunEvidence(FP,
                        FunctionEvidenceCeiling.EXPLORATORY, List.of(), List.of(), List.of(), FP));
        assertThat(changed.projectionFingerprint()).isNotEqualTo(first.projectionFingerprint());
    }

    @Test
    void duplicateAndOversizedProjectionFactsFailClosed() {
        WorldStateSnapshot snapshot = snapshot();
        TestRunControlEvidenceProjection.TransactionProjection transaction =
                TestRunControlEvidenceProjection.TransactionProjection.from(
                        snapshot.observations().getFirst());
        assertThatThrownBy(() -> new TestRunControlEvidenceProjection.StateProjection(
                FP, FP, FP, RUN_ID, FP, 2, FP, List.of(transaction, transaction)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRunControlEvidenceProjection.StateProjection(
                FP, FP, FP, RUN_ID, FP, 0, FP,
                java.util.stream.IntStream.range(0, 4_097).mapToObj(i -> transaction).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TestRunControlEvidenceProjection projection() {
        return TestRunControlEvidenceProjection.from(RUN_ID, FP, FP, FP, FP, FP,
                snapshot(RUN_ID), functionEvidence());
    }

    private FunctionControlRunEvidence functionEvidence() {
        return functionEvidence(FunctionEvidenceCeiling.CERTIFIABLE);
    }

    private FunctionControlRunEvidence functionEvidence(FunctionEvidenceCeiling ceiling) {
        FunctionInvocationSite site = new FunctionInvocationSite("/root", "node", "f", 1, 1);
        return new FunctionControlRunEvidence(FP, ceiling,
                List.of(new FunctionControlEvidenceBinding(site, FP, FP,
                        FunctionControlMode.CONTROLLED, ceiling, "")),
                List.of(), List.of(), FP);
    }

    private WorldStateSnapshot snapshot() {
        return snapshot(RUN_ID);
    }

    private WorldStateSnapshot snapshot(String runId) {
        WorldStateSession.Binding binding = new WorldStateSession.Binding(FP, FP, FP, runId);
        WorldInvocationCoordinate coordinate = new WorldInvocationCoordinate(
                "/root", "node", 1, 1, 1, "/root/node#WORLD");
        WorldStateTransactionObservation observation = new WorldStateTransactionObservation(
                coordinate, List.of("/balance"), List.of("/balance"), FP, FP, FP);
        List<WorldStateTransactionObservation> observations = List.of(observation);
        String fingerprint = WorldStateSnapshot.fingerprint(binding, FP, 1,
                Map.of("/balance", 1), observations);
        return new WorldStateSnapshot(binding, FP, 1, Map.of("/balance", 1),
                observations, fingerprint);
    }

    private TestRunEvidence baseEvidence() {
        return baseEvidence(RUN_ID);
    }

    private TestRunEvidence baseEvidence(String runId) {
        return new TestRunEvidence(TestRunEvidence.SCHEMA_VERSION, runId,
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                "GRAPH_CONTRACT_TEST", FP, FP, FP, "", Instant.EPOCH, Instant.EPOCH,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private TestRunEvidence rawProjectionEvidence(TestRunControlEvidenceProjection projection) {
        TestRunEvidence base = baseEvidence();
        TestRunEvidence raw = new TestRunEvidence(TestRunEvidence.SCHEMA_VERSION, base.runId(),
                base.status(), base.evidenceClass(), base.executionPurpose(), base.targetFingerprint(),
                base.fixtureBundleFingerprint(), base.planFingerprint(), base.startedAt(),
                base.completedAt(), base.nodeTrace(), base.edgeTrace(), base.fixtureConsumptions(),
                base.assertionResults(), base.diagnostics(),
                Map.of(TestRunEvidenceProtocolCodec.CONTROL_PROJECTION_METADATA_KEY, projection));
        return TestSemanticResultFingerprint.attach(mapper, raw);
    }
}
