package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static com.leanowtech.bloge.gateway.capabilitystudio.CapabilityStudioDataLensProjection.PermissionMode;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioFeatureRehearsalBaselineServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(JSON);
    private final CapabilityStudioFeatureRehearsalService rehearsal =
            new CapabilityStudioFeatureRehearsalService(pack, JSON, new DefaultOperatorRegistry());
    private final CapabilityStudioFeatureRehearsalOracle oracle =
            new CapabilityStudioFeatureRehearsalOracle(JSON);
    private final CapabilityStudioFeatureRehearsalBaselineService baseline =
            new CapabilityStudioFeatureRehearsalBaselineService(pack, rehearsal, oracle);

    @Test
    void runsTheExactCanonicalNineCasesForThreeRoundsWithBusinessOracles() throws Exception {
        CapabilityStudioFeatureRehearsalBaselineProjection projection = baseline.run();

        assertThat(projection.status()).isEqualTo("PASSED");
        assertThat(projection.evidenceKind()).isEqualTo("DEVELOPMENT_TEST_OWNED");
        assertThat(projection.caseCount()).isEqualTo(9);
        assertThat(projection.roundCount()).isEqualTo(3);
        assertThat(projection.runCount()).isEqualTo(27);
        assertThat(projection.realExternalCallCount()).isZero();
        assertThat(projection.cases()).extracting(
                CapabilityStudioFeatureRehearsalBaselineProjection.CaseResult::caseId)
                .containsExactlyElementsOf(
                        CapabilityStudioFeatureRehearsalBaselineService.CANONICAL_CASE_IDS);
        assertThat(projection.cases()).allSatisfy(value -> {
            assertThat(value.rounds()).hasSize(3);
            assertThat(value.oracle().status()).isEqualTo(CapabilityStudioFeatureRehearsalOracle.PASS);
            assertThat(value.businessFingerprint()).startsWith("sha256:");
            assertThat(value.rounds()).extracting(
                    CapabilityStudioFeatureRehearsalBaselineProjection.RoundResult::realExternalCallCount)
                    .containsOnly(0);
        });
        assertThat(projection.cases().stream().flatMap(value -> value.rounds().stream())
                .map(CapabilityStudioFeatureRehearsalBaselineProjection.RoundResult::runId)
                .distinct()).hasSize(27);
        assertThat(projection.cases().stream().flatMap(value -> value.rounds().stream())
                .map(CapabilityStudioFeatureRehearsalBaselineProjection.RoundResult::semanticFingerprint)
                .distinct()).hasSizeGreaterThan(1);
        assertThat(projection.graphFingerprint()).startsWith("sha256:");
        assertThat(projection.cases().stream().map(
                value -> value.rounds().stream()
                        .map(CapabilityStudioFeatureRehearsalBaselineProjection.RoundResult::semanticFingerprint)
                        .distinct().count())).containsOnly(1L);
        assertThat(JSON.writeValueAsString(projection))
                .doesNotContain("\"input\"", "\"output\"", "payload", "fixture", "mock");
    }

    @Test
    void businessFixturesProduceDifferentExplainableDecisionOutcomes() {
        assertDecision("case-rider-not-responsible", "WAIVE_CANCELLATION_FEE",
                "RIDER_NOT_AT_FAULT");
        assertDecision("case-driver-responsible", "APPLY_DRIVER_RESPONSIBILITY_RULE",
                "DRIVER_LATE");
        assertDecision("case-city-policy-missing", "MANUAL_REVIEW", "CITY_POLICY_MISSING");
        assertDecision("case-compensation-history-empty", "AUTO_QUOTE", "CANCELLATION_CONTEXT_READY");
        assertDecision("case-policy-revision-regression", "AUTO_QUOTE", "CANCELLATION_CONTEXT_READY");

        CapabilityStudioFeatureRehearsalProjection empty = rehearsal.rehearseForOracle(
                "case-compensation-history-empty");
        assertThat(decision(empty).get("informationGap")).isEqualTo("COMPENSATION_HISTORY_EMPTY");
        assertThat(decision(empty).get("action")).isEqualTo("AUTO_QUOTE");
        assertThat(decision(rehearsal.rehearseForOracle("case-policy-revision-regression"))
                .get("policyVersion")).isEqualTo("SZ-CANCEL-2026.08-R2");
    }

    @Test
    void duplicateCaseOracleRequiresDistinctRunsWithTheSameBusinessOutcome() {
        List<CapabilityStudioFeatureRehearsalProjection> observations = List.of(
                rehearsal.rehearseForOracle("case-duplicate-cancellation"),
                rehearsal.rehearseForOracle("case-duplicate-cancellation"),
                rehearsal.rehearseForOracle("case-duplicate-cancellation"));

        assertThat(oracle.evaluate(observations).status())
                .isEqualTo(CapabilityStudioFeatureRehearsalOracle.PASS);
        assertThat(observations).extracting(value -> value.run().runId()).doesNotHaveDuplicates();
        assertThat(observations).extracting(value -> oracle.businessFingerprint(value))
                .containsOnly(oracle.businessFingerprint(observations.getFirst()));
    }

    @Test
    void timeoutCasePassesThreeRoundStabilityWithItsExpectedTimedOutTerminalStatus() {
        List<CapabilityStudioFeatureRehearsalProjection> observations = List.of(
                rehearsal.rehearseForOracle("case-compensation-history-timeout"),
                rehearsal.rehearseForOracle("case-compensation-history-timeout"),
                rehearsal.rehearseForOracle("case-compensation-history-timeout"));

        assertThat(oracle.evaluate(observations).status())
                .isEqualTo(CapabilityStudioFeatureRehearsalOracle.PASS);
        assertThat(observations).extracting(value -> value.run().status()).containsOnly("TIMED_OUT");
    }

    @Test
    void stableButWrongDuplicateBusinessResultFailsBeforeIdempotencyIsAccepted() {
        List<CapabilityStudioFeatureRehearsalProjection> observations = List.of(
                tamperDecision(rehearsal.rehearseForOracle("case-duplicate-cancellation")),
                tamperDecision(rehearsal.rehearseForOracle("case-duplicate-cancellation")),
                tamperDecision(rehearsal.rehearseForOracle("case-duplicate-cancellation")));

        assertThat(oracle.evaluate(observations).status())
                .isEqualTo(CapabilityStudioFeatureRehearsalOracle.FAIL);
    }

    @Test
    void concurrentBatchesKeepRunIdsAndEgressCountersIsolated() throws Exception {
        List<Callable<CapabilityStudioFeatureRehearsalBaselineProjection>> tasks = IntStream.range(0, 2)
                .mapToObj(ignored -> (Callable<CapabilityStudioFeatureRehearsalBaselineProjection>) baseline::run)
                .toList();
        List<CapabilityStudioFeatureRehearsalBaselineProjection> batches;
        try (var executor = Executors.newFixedThreadPool(2)) {
            batches = executor.invokeAll(tasks).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new AssertionError("Concurrent baseline failed", failure);
                }
            }).toList();
        }

        assertThat(batches).allSatisfy(value -> {
            assertThat(value.status()).isEqualTo("PASSED");
            assertThat(value.runCount()).isEqualTo(27);
            assertThat(value.realExternalCallCount()).isZero();
        });
        assertThat(batches.stream().flatMap(value -> value.cases().stream())
                .flatMap(value -> value.rounds().stream())
                .map(CapabilityStudioFeatureRehearsalBaselineProjection.RoundResult::runId)
                .distinct()).hasSize(54);
    }

    @Test
    void forbiddenWriteOracleUsesGraphOperatorFootprintAndTrace() {
        CapabilityStudioFeatureRehearsalProjection projection = rehearsal.rehearseForOracle(
                "case-forbidden-write-effect");

        assertThat(oracle.evaluate(projection).status())
                .as("a payload trace alone cannot prove the graph has no write operator")
                .isEqualTo(CapabilityStudioFeatureRehearsalOracle.FAIL);
        assertThat(oracle.evaluate(projection, rehearsal.operatorFootprints()).status())
                .isEqualTo(CapabilityStudioFeatureRehearsalOracle.PASS);
        assertThat(rehearsal.operatorFootprints())
                .extracting(CapabilityStudioFeatureRehearsalService.OperatorFootprint::sideEffectType)
                .doesNotContain(com.leanowtech.bloge.core.operator.SideEffectType.WRITE,
                        com.leanowtech.bloge.core.operator.SideEffectType.MIXED);
        assertThat(projection.dataLens().nodes())
                .extracting(CapabilityStudioDataLensProjection.Node::operatorRef)
                .noneMatch(value -> value.toLowerCase().contains("write"));
    }

    @Test
    void tamperedDecisionOutputCannotBeCoveredByRuntimePassedStatus() {
        CapabilityStudioFeatureRehearsalProjection original = rehearsal.rehearseForOracle(
                "case-standard-cancellation-fee");
        CapabilityStudioDataLensProjection.Node decision = original.dataLens().nodes().stream()
                .filter(node -> node.nodeId().equals("cancellationDecision"))
                .findFirst().orElseThrow();
        CapabilityStudioDataLensProjection.Node tampered = new CapabilityStudioDataLensProjection.Node(
                decision.nodeId(), decision.operatorRef(), decision.status(), decision.fidelity(),
                decision.graphPath(), decision.invocationSite(), decision.correlation(),
                decision.occurrence(), decision.graphOccurrence(), decision.input(),
                decision.inputFingerprint(), Map.of("action", "MANUAL_REVIEW",
                "reasonCode", "TAMPERED"), decision.outputFingerprint(), decision.errorCode(),
                decision.durationMs(), decision.attempts(), decision.retryCount(), decision.fallbackStatus());
        List<CapabilityStudioDataLensProjection.Node> nodes = original.dataLens().nodes().stream()
                .map(node -> node.nodeId().equals(decision.nodeId()) ? tampered : node).toList();
        CapabilityStudioDataLensProjection lens = new CapabilityStudioDataLensProjection(
                original.dataLens().schemaVersion(), original.dataLens().runId(),
                original.dataLens().runStatus(), PermissionMode.PAYLOAD_VISIBLE, nodes,
                original.dataLens().edges(), original.dataLens().firstDifference(),
                original.dataLens().truncation(), original.dataLens().fingerprint());
        CapabilityStudioFeatureRehearsalProjection forged = new CapabilityStudioFeatureRehearsalProjection(
                original.schemaVersion(), original.scenario(), original.graph(), original.run(), lens);

        assertThat(oracle.evaluate(forged).status()).isEqualTo(CapabilityStudioFeatureRehearsalOracle.FAIL);
    }

    private void assertDecision(String caseId, String action, String reasonCode) {
        CapabilityStudioFeatureRehearsalProjection projection = rehearsal.rehearseForOracle(caseId);
        assertThat(oracle.evaluate(projection).status()).as(caseId)
                .isEqualTo(CapabilityStudioFeatureRehearsalOracle.PASS);
        assertThat(decision(projection).get("action")).isEqualTo(action);
        assertThat(decision(projection).get("reasonCode")).isEqualTo(reasonCode);
    }

    private static Map<?, ?> decision(CapabilityStudioFeatureRehearsalProjection projection) {
        return projection.dataLens().nodes().stream()
                .filter(node -> node.nodeId().equals("cancellationDecision"))
                .map(CapabilityStudioDataLensProjection.Node::output)
                .filter(Map.class::isInstance)
                .map(value -> (Map<?, ?>) value)
                .findFirst().orElse(Map.of());
    }

    private static CapabilityStudioFeatureRehearsalProjection tamperDecision(
            CapabilityStudioFeatureRehearsalProjection original) {
        CapabilityStudioDataLensProjection.Node decision = original.dataLens().nodes().stream()
                .filter(node -> node.nodeId().equals("cancellationDecision"))
                .findFirst().orElseThrow();
        CapabilityStudioDataLensProjection.Node tampered = new CapabilityStudioDataLensProjection.Node(
                decision.nodeId(), decision.operatorRef(), decision.status(), decision.fidelity(),
                decision.graphPath(), decision.invocationSite(), decision.correlation(),
                decision.occurrence(), decision.graphOccurrence(), decision.input(),
                decision.inputFingerprint(), Map.of("action", "MANUAL_REVIEW",
                "reasonCode", "TAMPERED", "informationGap", "NONE"),
                decision.outputFingerprint(), decision.errorCode(), decision.durationMs(),
                decision.attempts(), decision.retryCount(), decision.fallbackStatus());
        List<CapabilityStudioDataLensProjection.Node> nodes = original.dataLens().nodes().stream()
                .map(node -> node.nodeId().equals(decision.nodeId()) ? tampered : node).toList();
        CapabilityStudioDataLensProjection lens = new CapabilityStudioDataLensProjection(
                original.dataLens().schemaVersion(), original.dataLens().runId(),
                original.dataLens().runStatus(), PermissionMode.PAYLOAD_VISIBLE, nodes,
                original.dataLens().edges(), original.dataLens().firstDifference(),
                original.dataLens().truncation(), original.dataLens().fingerprint());
        return new CapabilityStudioFeatureRehearsalProjection(
                original.schemaVersion(), original.scenario(), original.graph(), original.run(), lens);
    }
}
