package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationRequest;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationResponse;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationTableSuiteCaseRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void comparesJsonNumbersByValueRecursivelyAndHonorsTolerance() {
        ScenarioSimulationCompiler compiler = mock(ScenarioSimulationCompiler.class);
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        VisualGraphSimulationRequest request = new VisualGraphSimulationRequest(null, Map.of(), "", Map.of());
        ScenarioDraftSet.AssertionDraft payload = new ScenarioDraftSet.AssertionDraft(
                "payload", ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                "", "", "", "/payload", ScenarioDraftSet.AssertionOperator.EQUALS,
                Map.of("scores", List.of(728, 701), "nested", Map.of("count", 2)), null);
        ScenarioDraftSet.AssertionDraft confidence = new ScenarioDraftSet.AssertionDraft(
                "confidence", ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                "", "", "", "/confidence", ScenarioDraftSet.AssertionOperator.EQUALS,
                0.3d, 0.001d);
        when(compiler.compile(any(), any(), any(), any()))
                .thenReturn(plan(request, List.of(payload, confidence)));
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(response(Map.of(
                "confidence", new BigDecimal("0.3004"),
                "payload", Map.of("nested", Map.of("count", 2L),
                        "scores", List.of(728L, new BigDecimal("701.0"))))));
        SimulationTableSuiteCaseRunner runner = new SimulationTableSuiteCaseRunner(
                compiler, simulation, mapper, Clock.systemUTC());

        TableSuiteRunBatch.AttemptEvidence evidence = runner.run(command(10_000), "case-a", 1);

        assertThat(evidence.status()).isEqualTo(TableSuiteRunBatch.RowStatus.SUCCESS);
        assertThat(evidence.assertions()).isEqualTo(TableSuiteRunBatch.AssertionState.PASSED);
        assertThat(evidence.assertionEvidence()).allMatch(TableSuiteRunBatch.AssertionEvidence::passed);
    }

    @Test
    void emitsOnlyAssertionFingerprintsWhenBusinessValuesDiffer() throws Exception {
        ScenarioSimulationCompiler compiler = mock(ScenarioSimulationCompiler.class);
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        VisualGraphSimulationRequest request = new VisualGraphSimulationRequest(null, Map.of(), "", Map.of());
        ScenarioDraftSet.AssertionDraft assertion = new ScenarioDraftSet.AssertionDraft(
                "assert-a", ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                "", "", "", "/decision", ScenarioDraftSet.AssertionOperator.EQUALS,
                "expected-business-secret", null);
        when(compiler.compile(any(), any(), any(), any()))
                .thenReturn(plan(request, List.of(assertion)));
        when(simulation.simulate(any(), any(), any(), any()))
                .thenReturn(response(Map.of("decision", "actual-business-secret")));
        SimulationTableSuiteCaseRunner runner = new SimulationTableSuiteCaseRunner(
                compiler, simulation, mapper, Clock.systemUTC());

        TableSuiteRunBatch.AttemptEvidence evidence = runner.run(command(10_000), "case-a", 1);
        String encoded = mapper.writeValueAsString(evidence);

        assertThat(evidence.status()).isEqualTo(TableSuiteRunBatch.RowStatus.ASSERTION_FAILED);
        assertThat(evidence.assertions()).isEqualTo(TableSuiteRunBatch.AssertionState.FAILED);
        assertThat(evidence.assertionEvidence().getFirst().expectedFingerprint())
                .startsWith("sha256:");
        assertThat(encoded).doesNotContain("expected-business-secret", "actual-business-secret");
    }

    @Test
    void interruptsTheSimulationAtTheAdmittedHardTimeout() {
        ScenarioSimulationCompiler compiler = mock(ScenarioSimulationCompiler.class);
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        VisualGraphSimulationRequest request = new VisualGraphSimulationRequest(null, Map.of(), "", Map.of());
        when(compiler.compile(any(), any(), any(), any())).thenReturn(plan(request, List.of()));
        when(simulation.simulate(any(), any(), any(), any())).thenAnswer(ignored -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return response(Map.of());
        });
        SimulationTableSuiteCaseRunner runner = new SimulationTableSuiteCaseRunner(
                compiler, simulation, mapper, Clock.systemUTC());

        long started = System.nanoTime();
        TableSuiteRunBatch.AttemptEvidence evidence = runner.run(command(100), "case-timeout", 1);
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(evidence.status()).isEqualTo(TableSuiteRunBatch.RowStatus.TIMEOUT);
        assertThat(evidence.firstFailure().code()).isEqualTo("RG.TABLE_RUN.CASE_TIMEOUT");
        assertThat(elapsedMs).isLessThan(1_000);
    }

    private static ScenarioSimulationPlan plan(
            VisualGraphSimulationRequest request,
            List<ScenarioDraftSet.AssertionDraft> assertions) {
        return new ScenarioSimulationPlan("", true, "case-a", "", "",
                request, assertions, List.of());
    }

    private static VisualGraphSimulationResponse response(Object output) {
        return new VisualGraphSimulationResponse(true, true, true,
                "graph", "output", output, Map.of(), Map.of("output", "SUCCESS"),
                1, Map.of(), List.of("output"), List.of(), true,
                List.of(), List.of(), "");
    }

    private static TableSuiteRunCommand command(long timeoutMs) {
        return new TableSuiteRunCommand(TableSuiteRunCommand.SCHEMA_VERSION,
                "request-a", null, null, null,
                new TableSuiteRunCommand.Selection(TableSuiteRunCommand.SelectionMode.ALL, List.of()),
                new TableSuiteRunCommand.Preflight("test",
                        TableSuiteRunCommand.DependencyMode.SIMULATED,
                        TableSuiteRunCommand.EffectProfile.SIDE_EFFECT_FREE,
                        500, 10, 1, timeoutMs), "");
    }
}
