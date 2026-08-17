package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical development rehearsal application service.
 *
 * <p>It is a batch coordinator only. Every observation is delegated to the existing Feature
 * Rehearsal service, which in turn executes the real BLOGE Graph, TestRunService, FixtureBundle,
 * and TestRunEvidence path. This service never executes an operator itself.</p>
 */
public final class CapabilityStudioFeatureRehearsalBaselineService {
    public static final String BASELINE_ID = "cancellation-fee-canonical-baseline";
    public static final int ROUNDS = 3;
    public static final List<String> CANONICAL_CASE_IDS = List.of(
            "case-standard-cancellation-fee",
            "case-rider-not-responsible",
            "case-driver-responsible",
            "case-city-policy-missing",
            "case-compensation-history-empty",
            "case-compensation-history-timeout",
            "case-duplicate-cancellation",
            "case-forbidden-write-effect",
            "case-policy-revision-regression");

    private final CapabilityStudioGoldenDemoPack pack;
    private final CapabilityStudioFeatureRehearsalService rehearsal;
    private final CapabilityStudioFeatureRehearsalOracle oracle;

    public CapabilityStudioFeatureRehearsalBaselineService(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioFeatureRehearsalService rehearsal,
            CapabilityStudioFeatureRehearsalOracle oracle) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.rehearsal = Objects.requireNonNull(rehearsal, "rehearsal");
        this.oracle = Objects.requireNonNull(oracle, "oracle");
    }

    public CapabilityStudioFeatureRehearsalBaselineService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper objectMapper,
            com.leanowtech.bloge.core.spi.OperatorRegistry operatorRegistry) {
        this(pack,
                new CapabilityStudioFeatureRehearsalService(pack, objectMapper, operatorRegistry),
                new CapabilityStudioFeatureRehearsalOracle(objectMapper));
    }

    /** Runs all 9 cases in canonical order for exactly 3 rounds. */
    public CapabilityStudioFeatureRehearsalBaselineProjection run() {
        List<CapabilityStudioGoldenDemoPack.TestScenario> scenarios = canonicalScenarios();
        List<CapabilityStudioFeatureRehearsalBaselineProjection.CaseResult> caseResults =
                new ArrayList<>(scenarios.size());
        List<CapabilityStudioFeatureRehearsalProjection> allRuns = new ArrayList<>();
        List<CapabilityStudioFeatureRehearsalService.OperatorFootprint> operatorFootprints =
                rehearsal.operatorFootprints();
        String graphFingerprint = "";
        String graphId = "";
        int realExternalCallCount = 0;

        for (CapabilityStudioGoldenDemoPack.TestScenario scenario : scenarios) {
            List<CapabilityStudioFeatureRehearsalProjection> observations = new ArrayList<>(ROUNDS);
            List<CapabilityStudioFeatureRehearsalBaselineProjection.RoundResult> rounds =
                    new ArrayList<>(ROUNDS);
            for (int round = 1; round <= ROUNDS; round++) {
                CapabilityStudioFeatureRehearsalProjection projection =
                        rehearsal.rehearseForOracle(scenario.id());
                observations.add(projection);
                allRuns.add(projection);
                graphId = firstNonBlank(graphId, projection.graph().id());
                graphFingerprint = firstNonBlank(graphFingerprint, projection.graph().fingerprint());
                realExternalCallCount += projection.run().realExternalCallCount();
                rounds.add(new CapabilityStudioFeatureRehearsalBaselineProjection.RoundResult(
                        round,
                        projection.run().runId(),
                        projection.run().status(),
                        projection.run().semanticFingerprint(),
                        projection.run().realExternalCallCount()));
            }
            CapabilityStudioFeatureRehearsalOracle.Evaluation evaluation = oracle.evaluate(
                    observations, operatorFootprints);
            String businessFingerprint = oracle.businessFingerprint(observations.getFirst());
            boolean businessStable = observations.stream().map(oracle::businessFingerprint)
                    .distinct().count() == 1;
            if (!businessStable && CapabilityStudioFeatureRehearsalOracle.PASS.equals(evaluation.status())) {
                evaluation = new CapabilityStudioFeatureRehearsalOracle.Evaluation(
                        evaluation.assertionId(), CapabilityStudioFeatureRehearsalOracle.FAIL,
                        evaluation.expectedSummary(),
                        "business fingerprint changed between rounds", evaluation.actualFingerprint());
            }
            caseResults.add(new CapabilityStudioFeatureRehearsalBaselineProjection.CaseResult(
                    scenario.id(), scenario.name(), rounds, evaluation, businessFingerprint));
        }

        List<String> diagnostics = new ArrayList<>();
        Set<String> runIds = allRuns.stream().map(value -> value.run().runId()).collect(Collectors.toSet());
        Set<String> graphFingerprints = allRuns.stream()
                .map(value -> value.graph().fingerprint()).collect(Collectors.toSet());
        Set<String> graphIds = allRuns.stream()
                .map(value -> value.graph().id()).collect(Collectors.toSet());
        if (runIds.size() != CANONICAL_CASE_IDS.size() * ROUNDS) {
            diagnostics.add("RUN_ID_CARDINALITY_INVALID");
        }
        if (graphIds.size() != 1 || graphIds.contains("")
                || graphFingerprints.size() != 1
                || graphFingerprints.stream().noneMatch(
                value -> value.matches("sha256:[a-f0-9]{64}"))) {
            diagnostics.add("GRAPH_FINGERPRINT_NOT_STABLE");
        }
        if (realExternalCallCount != 0) {
            diagnostics.add("REAL_EXTERNAL_CALL_FORBIDDEN");
        }
        if (caseResults.stream().anyMatch(value ->
                !CapabilityStudioFeatureRehearsalOracle.PASS.equals(value.oracle().status()))) {
            diagnostics.add("BUSINESS_ORACLE_FAILED");
        }
        String status = diagnostics.isEmpty() ? "PASSED" : "FAILED_CLOSED";
        List<CapabilityStudioFeatureRehearsalBaselineProjection.OperatorSummary> operators =
                operatorFootprints.stream()
                        .map(value -> new CapabilityStudioFeatureRehearsalBaselineProjection.OperatorSummary(
                                value.nodeId(), value.operatorRef(), value.sideEffectType().name()))
                        .toList();
        return new CapabilityStudioFeatureRehearsalBaselineProjection(
                CapabilityStudioFeatureRehearsalBaselineProjection.SCHEMA_VERSION,
                CapabilityStudioFeatureRehearsalBaselineProjection.EVIDENCE_KIND,
                BASELINE_ID,
                status,
                graphId,
                graphFingerprint,
                scenarios.size(),
                ROUNDS,
                allRuns.size(),
                realExternalCallCount,
                caseResults,
                operators,
                diagnostics);
    }

    private List<CapabilityStudioGoldenDemoPack.TestScenario> canonicalScenarios() {
        Map<String, CapabilityStudioGoldenDemoPack.TestScenario> byId = pack.scenarios().stream()
                .collect(Collectors.toMap(CapabilityStudioGoldenDemoPack.TestScenario::id,
                        value -> value, (left, right) -> left, LinkedHashMap::new));
        if (byId.size() != CANONICAL_CASE_IDS.size()
                || !byId.keySet().containsAll(CANONICAL_CASE_IDS)
                || !Set.copyOf(byId.keySet()).equals(Set.copyOf(CANONICAL_CASE_IDS))) {
            throw new IllegalStateException("Canonical Feature Rehearsal Case set was changed");
        }
        return CANONICAL_CASE_IDS.stream().map(byId::get).toList();
    }

    private static String firstNonBlank(String current, String candidate) {
        return current == null || current.isBlank() ? candidate : current;
    }
}
