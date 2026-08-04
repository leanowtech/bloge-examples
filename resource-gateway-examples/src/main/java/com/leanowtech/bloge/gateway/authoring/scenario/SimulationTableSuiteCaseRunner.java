package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationRequest;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationResponse;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Production adapter that compiles and simulates one Scenario without retaining payload values. */
public final class SimulationTableSuiteCaseRunner implements TableSuiteCaseRunner {

    private static final int FINGERPRINT_MAX_BYTES = 16 * 1_048_576;

    private final ScenarioSimulationCompiler compiler;
    private final VisualGraphSimulationService simulation;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Creates the exact transient Scenario execution adapter. */
    public SimulationTableSuiteCaseRunner(
            ScenarioSimulationCompiler compiler,
            VisualGraphSimulationService simulation,
            ObjectMapper mapper,
            Clock clock) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.simulation = Objects.requireNonNull(simulation, "simulation");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TableSuiteRunBatch.AttemptEvidence run(
            TableSuiteRunCommand command,
            String caseId,
            int attempt) {
        Instant startedAt = clock.instant();
        ScenarioSimulationPlan plan = compiler.compile(
                command.graphDraft(), command.contract(), command.draftSet(), caseId);
        if (!plan.compiled() || plan.request() == null) {
            return failed(attempt, TableSuiteRunBatch.RowStatus.COMPILE_ERROR,
                    TableSuiteRunBatch.AssertionState.INCONCLUSIVE,
                    new TableSuiteRunBatch.Failure("COMPILATION", "RG.TABLE_RUN.COMPILE_FAILED",
                            firstTarget(plan), "Scenario could not be compiled for simulation."),
                    List.of(), startedAt);
        }

        VisualGraphSimulationRequest request = plan.request();
        VisualGraphSimulationResponse response;
        FutureTask<VisualGraphSimulationResponse> simulationTask = new FutureTask<>(() ->
                simulation.simulate(request.draft(), request.context(),
                        request.outputNode(), request.fixtures()));
        Thread worker = Thread.ofVirtual()
                .name("resource-gateway-table-case-" + caseId)
                .start(simulationTask);
        try {
            response = simulationTask.get(command.preflight().caseTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            simulationTask.cancel(true);
            return failed(attempt, TableSuiteRunBatch.RowStatus.TIMEOUT,
                    TableSuiteRunBatch.AssertionState.INCONCLUSIVE,
                    new TableSuiteRunBatch.Failure("TIMEOUT", "RG.TABLE_RUN.CASE_TIMEOUT",
                            "/run", "Scenario exceeded its admitted case timeout."),
                    List.of(), startedAt);
        } catch (InterruptedException interrupted) {
            simulationTask.cancel(true);
            Thread.currentThread().interrupt();
            return failed(attempt, TableSuiteRunBatch.RowStatus.RUNTIME_ERROR,
                    TableSuiteRunBatch.AssertionState.INCONCLUSIVE,
                    new TableSuiteRunBatch.Failure("EXECUTION", "RG.TABLE_RUN.CASE_INTERRUPTED",
                            "/run", "Scenario execution was interrupted."),
                    List.of(), startedAt);
        } catch (ExecutionException execution) {
            return failed(attempt, TableSuiteRunBatch.RowStatus.RUNTIME_ERROR,
                    TableSuiteRunBatch.AssertionState.INCONCLUSIVE,
                    new TableSuiteRunBatch.Failure("EXECUTION", "RG.TABLE_RUN.RUNTIME_FAILED",
                            "/run", "Scenario simulation did not complete successfully."),
                    List.of(), startedAt);
        } finally {
            if (worker.isAlive() && simulationTask.isCancelled()) worker.interrupt();
        }
        long elapsedMs = Math.max(0, java.time.Duration.between(startedAt, clock.instant()).toMillis());
        boolean timedOut = elapsedMs > command.preflight().caseTimeoutMs()
                || response.diagnostics().stream().anyMatch(diagnostic -> "visual.simulate.timeout".equals(diagnostic.code()));
        if (timedOut) {
            return failed(attempt, TableSuiteRunBatch.RowStatus.TIMEOUT,
                    TableSuiteRunBatch.AssertionState.INCONCLUSIVE,
                    new TableSuiteRunBatch.Failure("TIMEOUT", "RG.TABLE_RUN.CASE_TIMEOUT",
                            "/run", "Scenario exceeded its admitted case timeout."),
                    List.of(), startedAt);
        }
        if (!response.validated() || !response.compiled() || !response.success()) {
            return failed(attempt, TableSuiteRunBatch.RowStatus.RUNTIME_ERROR,
                    TableSuiteRunBatch.AssertionState.INCONCLUSIVE,
                    new TableSuiteRunBatch.Failure("EXECUTION", "RG.TABLE_RUN.RUNTIME_FAILED",
                            "/run", "Scenario simulation did not complete successfully."),
                    List.of(), startedAt);
        }

        List<TableSuiteRunBatch.AssertionEvidence> assertionEvidence = new ArrayList<>();
        for (ScenarioDraftSet.AssertionDraft assertion : plan.assertions()) {
            assertionEvidence.add(compare(assertion, response));
        }
        boolean assertionsPassed = assertionEvidence.stream().allMatch(TableSuiteRunBatch.AssertionEvidence::passed);
        TableSuiteRunBatch.AssertionState assertionState = assertionEvidence.isEmpty()
                ? TableSuiteRunBatch.AssertionState.NONE
                : assertionsPassed ? TableSuiteRunBatch.AssertionState.PASSED
                : TableSuiteRunBatch.AssertionState.FAILED;
        TableSuiteRunBatch.RowStatus status = assertionsPassed
                ? TableSuiteRunBatch.RowStatus.SUCCESS
                : TableSuiteRunBatch.RowStatus.ASSERTION_FAILED;
        TableSuiteRunBatch.Failure failure = assertionEvidence.stream()
                .filter(result -> !result.passed())
                .findFirst()
                .map(result -> new TableSuiteRunBatch.Failure(
                        "ASSERTION", result.diagnosticCode(), result.path(),
                        "Expected and actual values differ."))
                .orElse(null);
        Instant completedAt = clock.instant();
        return new TableSuiteRunBatch.AttemptEvidence(attempt, status, assertionState,
                response.mockedNodeIds().isEmpty()
                        ? TableSuiteRunBatch.ProofStrength.RUNTIME
                        : TableSuiteRunBatch.ProofStrength.MOCK,
                Math.max(0, java.time.Duration.between(startedAt, completedAt).toMillis()),
                runFingerprint(response), failure, assertionEvidence, startedAt, completedAt);
    }

    private TableSuiteRunBatch.AssertionEvidence compare(
            ScenarioDraftSet.AssertionDraft assertion,
            VisualGraphSimulationResponse response) {
        JsonNode expected = mapper.valueToTree(assertion.expected());
        if (assertion.scope() != ScenarioDraftSet.AssertionScope.OUTPUT_PATH
                || assertion.operator() != ScenarioDraftSet.AssertionOperator.EQUALS) {
            return assertion(assertion, false, expected, mapper.missingNode(),
                    "RG.TABLE_RUN.GOVERNED_ASSERTION_REQUIRED");
        }
        JsonNode output = mapper.valueToTree(response.output());
        JsonNode actual = assertion.path().isBlank() ? output : output.at(assertion.path());
        boolean passed = equivalent(expected, actual, assertion.numericTolerance());
        return assertion(assertion, passed, expected, actual,
                passed ? "" : "RG.TABLE_RUN.ASSERTION_MISMATCH");
    }

    private TableSuiteRunBatch.AssertionEvidence assertion(
            ScenarioDraftSet.AssertionDraft assertion,
            boolean passed,
            JsonNode expected,
            JsonNode actual,
            String code) {
        return new TableSuiteRunBatch.AssertionEvidence(
                assertion.assertionId(), assertion.path(), passed,
                fingerprint(expected), fingerprint(actual), code);
    }

    private boolean equivalent(JsonNode expected, JsonNode actual, Double tolerance) {
        if (expected == null || actual == null || expected.isMissingNode() || actual.isMissingNode()) {
            return Objects.equals(expected, actual);
        }
        if (expected.isNumber() && actual.isNumber()) {
            if (tolerance != null) {
                return expected.decimalValue().subtract(actual.decimalValue()).abs()
                        .compareTo(java.math.BigDecimal.valueOf(Math.max(0, tolerance))) <= 0;
            }
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (expected.isObject() && actual.isObject()) {
            if (expected.size() != actual.size()) return false;
            var fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!actual.has(field.getKey())
                        || !equivalent(field.getValue(), actual.get(field.getKey()), tolerance)) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) return false;
            for (int index = 0; index < expected.size(); index++) {
                if (!equivalent(expected.get(index), actual.get(index), tolerance)) return false;
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private TableSuiteRunBatch.AttemptEvidence failed(
            int attempt,
            TableSuiteRunBatch.RowStatus status,
            TableSuiteRunBatch.AssertionState assertions,
            TableSuiteRunBatch.Failure failure,
            List<TableSuiteRunBatch.AssertionEvidence> assertionEvidence,
            Instant startedAt) {
        Instant completedAt = clock.instant();
        return new TableSuiteRunBatch.AttemptEvidence(attempt, status, assertions,
                TableSuiteRunBatch.ProofStrength.SCHEMA,
                Math.max(0, java.time.Duration.between(startedAt, completedAt).toMillis()),
                fingerprint(Map.of("status", status, "failureCode", failure.code())),
                failure, assertionEvidence, startedAt, completedAt);
    }

    private String runFingerprint(VisualGraphSimulationResponse response) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("validated", response.validated());
        material.put("compiled", response.compiled());
        material.put("success", response.success());
        material.put("outputFingerprint", fingerprint(response.output()));
        material.put("statusMap", response.statusMap());
        material.put("mockedNodeIds", response.mockedNodeIds());
        material.put("realNodeIds", response.realNodeIds());
        material.put("terminalOutputConforms", response.terminalOutputConforms());
        return fingerprint(material);
    }

    private String fingerprint(Object value) {
        return ScenarioImportFingerprint.of(mapper, value, FINGERPRINT_MAX_BYTES);
    }

    private static String firstTarget(ScenarioSimulationPlan plan) {
        return plan.diagnostics().isEmpty() ? "/scenario" : plan.diagnostics().getFirst().target();
    }
}
