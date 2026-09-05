package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies business fixtures compile to deterministic, Solution-scoped IoC plans. */
class BusinessFixtureCompilerTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private final BusinessFixtureCompiler compiler = new BusinessFixtureCompiler(states, mapper);

    @BeforeEach
    void defineReachableCapabilities() {
        registry.upsertFeature(SCOPE, new FeatureContract(
                "feature:party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:party", "", "", "取消责任方"));
        registry.upsertFeature(SCOPE, new FeatureContract(
                "feature:order", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:order", "", "", "订单编号"));
        registry.upsertInstruction(SCOPE, instruction("ins:refund", "退款执行"));
        registry.upsertInstruction(SCOPE, readInstruction("ins:balance", "余额查询"));
        registry.upsertScenario(SCOPE, scenario(List.of("ins:refund", "ins:balance")));
        registry.upsertSolution(SCOPE, solution(List.of("ins:refund", "ins:balance")), false);
    }

    @Test
    void compilesEveryControlledOutcomeWithIndependentPlanFingerprints() {
        for (String outcome : List.of("RETURNS", "UNAVAILABLE", "SUCCEEDS_WITHOUT_EFFECT",
                "FAILS_WITHOUT_EFFECT", "MUST_NOT_BE_USED")) {
            BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                    SCOPE, "sol:cancel",
                    fixture(outcome, "RETURNS".equals(outcome) ? "余额查询" : "退款执行"));

            assertThat(plan.given().path("party").asText()).isEqualTo("passenger");
            String expectedRef = "RETURNS".equals(outcome) ? "ins:balance" : "ins:refund";
            assertThat(plan.dependencyAssumptions().path(expectedRef).path("outcome").asText())
                    .isEqualTo(outcome);
            assertThat(plan.businessContractVector()).hasSize(2);
            assertThat(plan.businessContractVector())
                    .allSatisfy(coordinate -> assertThat(coordinate.path("revision").asLong())
                            .isPositive());
            assertThat(plan.businessContractVector().getLast().path("semanticKey").asText())
                    .startsWith("legacy:");
            assertThat(plan.solutionRevision()).isPositive();
            assertThat(plan.solutionContractFingerprint()).startsWith("sha256:");
            assertThat(plan.featureValuesFingerprint()).startsWith("sha256:");
            assertThat(plan.dependencyPlanFingerprint()).startsWith("sha256:");
            assertThat(plan.frozenContextFingerprint()).startsWith("sha256:");
            assertThat(plan.planFingerprint()).startsWith("sha256:");
        }
    }

    @Test
    void bindsThePlanToImplementationRevisionWithoutChangingBusinessIdentity() {
        BusinessFixtureCompiler.ControlledAssumptionPlan first = compiler.compile(
                SCOPE, "sol:cancel", fixture("UNAVAILABLE"));
        String firstFeatureContract = first.businessContractVector().stream()
                .filter(value -> "FEATURE".equals(value.path("assetKind").asText()))
                .findFirst().orElseThrow().path("contractFingerprint").asText();

        registry.upsertFeature(SCOPE, new FeatureContract(
                "feature:party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:party:v2", "", "",
                "取消责任方"));
        BusinessFixtureCompiler.ControlledAssumptionPlan second = compiler.compile(
                SCOPE, "sol:cancel", fixture("UNAVAILABLE"));
        JsonNode secondFeature = second.businessContractVector().stream()
                .filter(value -> "FEATURE".equals(value.path("assetKind").asText()))
                .findFirst().orElseThrow();

        assertThat(secondFeature.path("revision").asLong()).isEqualTo(2);
        assertThat(secondFeature.path("contractFingerprint").asText())
                .isEqualTo(firstFeatureContract);
        assertThat(second.frozenContextFingerprint()).isNotEqualTo(first.frozenContextFingerprint());
        assertThat(second.planFingerprint()).isNotEqualTo(first.planFingerprint());
    }

    @Test
    void returnsDefensiveCopiesOfAllMutablePlanMaterial() {
        BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                SCOPE, "sol:cancel", fixture("UNAVAILABLE"));

        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.given()).put("party", "mutated");
        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.dependencyAssumptions())
                .remove("ins:refund");
        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.businessContractVector().getFirst())
                .put("revision", 999);

        assertThat(plan.given().path("party").asText()).isEqualTo("passenger");
        assertThat(plan.dependencyAssumptions().has("ins:refund")).isTrue();
        assertThat(plan.businessContractVector().getFirst().path("revision").asLong())
                .isNotEqualTo(999);
    }

    @Test
    void rejectsARevisionThatDriftsBetweenObservationAndLock() {
        AgentTddStateRepository drifting = new DriftOnFeatureLockRepository(states);
        BusinessFixtureCompiler driftingCompiler = new BusinessFixtureCompiler(drifting, mapper);

        assertThatThrownBy(() -> driftingCompiler.compile(
                SCOPE, "sol:cancel", fixture("UNAVAILABLE")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("CAPABILITY_CONTEXT_STALE");
    }

    @Test
    void resolvesAnInstructionReferencedOnlyByTheReachableScenario() {
        registry.upsertInstruction(SCOPE, instruction("ins:scenario-only", "转人工复核"));
        registry.upsertScenario(SCOPE, scenario(List.of("ins:scenario-only")));
        registry.upsertSolution(SCOPE, solution(List.of("ins:refund")), false);
        JsonNode fixture = fixture("SUCCEEDS_WITHOUT_EFFECT").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixture.at("/dependencyAssumptions/0"))
                .put("capabilityName", "转人工复核");

        BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                SCOPE, "sol:cancel", fixture);

        assertThat(plan.dependencyAssumptions().has("ins:scenario-only")).isTrue();
    }

    @Test
    void failsClosedWhenOneBusinessNameResolvesToTwoReachableInstructions() {
        registry.upsertInstruction(SCOPE, instruction("ins:refund-duplicate", "退款执行"));
        registry.upsertScenario(SCOPE, scenario(List.of("ins:refund", "ins:refund-duplicate")));
        registry.upsertSolution(SCOPE, solution(List.of("ins:refund", "ins:refund-duplicate")), false);

        assertThatThrownBy(() -> compiler.compile(
                SCOPE, "sol:cancel",
                fixture("SUCCEEDS_WITHOUT_EFFECT")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_AMBIGUOUS");
    }

    @Test
    void rejectsReturnedFactSemanticsForAWriteCapability() {
        assertThatThrownBy(() -> compiler.compile(
                SCOPE, "sol:cancel", fixture("RETURNS")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_EFFECT_INVALID");
    }

    @Test
    void resolvesAFeatureDependencyAndInjectsItsContractCheckedReturn() {
        ObjectNode fixture = (ObjectNode) fixture("UNAVAILABLE").deepCopy();
        fixture.set("givenFacts", mapper.valueToTree(List.of(
                Map.of("factName", "订单编号", "value", "O-1"))));
        fixture.set("dependencyAssumptions", mapper.valueToTree(List.of(Map.of(
                "capabilityName", "取消责任方", "outcome", "RETURNS", "value", "passenger"))));

        BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                SCOPE, "sol:cancel", fixture);

        assertThat(plan.given().path("party").asText()).isEqualTo("passenger");
        assertThat(plan.dependencyAssumptions().at("/feature:party/assetKind").asText())
                .isEqualTo("FEATURE");
        assertThat(plan.dependencyAssumptions().at("/feature:party/inputAlias").asText())
                .isEqualTo("party");
    }

    @Test
    void rejectsInstructionReturnThatViolatesItsCurrentOutputDomain() {
        ObjectNode fixture = (ObjectNode) fixture("RETURNS", "余额查询").deepCopy();
        fixture.set("dependencyAssumptions", mapper.valueToTree(List.of(Map.of(
                "capabilityName", "余额查询", "outcome", "RETURNS",
                "value", Map.of("result", 42, "reasoning", "余额已读取")))));

        assertThatThrownBy(() -> compiler.compile(SCOPE, "sol:cancel", fixture))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_SCHEMA_INVALID");
    }

    @Test
    void rejectsAnInvalidEnumInsideANestedInstructionResult() {
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:balance", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of(
                        "result", Map.of("type", "object", "properties", Map.of(
                                "decision", Map.of("type", "string", "enum", List.of("WAIVED")),
                                "details", Map.of("type", "object", "properties", Map.of(
                                        "source", Map.of("type", "string")),
                                        "required", List.of("source"))),
                                "required", List.of("decision", "details")),
                        "reasoning", "required")),
                InstructionContract.Effect.READ, "operator:balance", null, "余额查询"));
        ObjectNode fixture = (ObjectNode) fixture("RETURNS", "余额查询").deepCopy();
        fixture.set("dependencyAssumptions", mapper.valueToTree(List.of(Map.of(
                "capabilityName", "余额查询", "outcome", "RETURNS",
                "value", Map.of("result", Map.of(
                        "decision", "NOT_DECLARED", "details", Map.of("source", "ledger")),
                        "reasoning", "余额已读取")))));

        assertThatThrownBy(() -> compiler.compile(SCOPE, "sol:cancel", fixture))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_SCHEMA_INVALID");
    }

    @Test
    void rejectsAnExpectedDispositionOutsideEveryReachableOutletContract() {
        ObjectNode fixture = (ObjectNode) fixture("UNAVAILABLE").deepCopy();
        fixture.set("expectedOutcome", mapper.valueToTree(Map.of(
                "result", Map.of("decision", "NOT_DECLARED"),
                "reasoningClass", "责任在乘客")));

        assertThatThrownBy(() -> compiler.validateBusinessCase(SCOPE, "sol:cancel", fixture))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_EXPECTED_OUTCOME_INVALID");
    }

    @Test
    void compilesFeatureFailureToAReachableBusinessFallbackWithoutCallingAFeatureBackend() {
        ObjectNode fixture = (ObjectNode) fixture("UNAVAILABLE").deepCopy();
        fixture.set("givenFacts", mapper.valueToTree(List.of(
                Map.of("factName", "订单编号", "value", "O-1"))));
        fixture.set("dependencyAssumptions", mapper.valueToTree(List.of(Map.of(
                "capabilityName", "取消责任方", "outcome", "UNAVAILABLE"))));
        fixture.set("expectedOutcome", mapper.valueToTree(Map.of(
                "result", Map.of("dependencyStatus", "UNAVAILABLE"),
                "reasoningClass", "CONTROLLED_DEPENDENCY_UNAVAILABLE")));

        BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                SCOPE, "sol:cancel", fixture);

        assertThat(plan.dependencyAssumptions().at("/feature:party/outcome").asText())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void rejectsTheSameFeatureAsBothAGivenFactAndADependencyAssumption() {
        ObjectNode fixture = (ObjectNode) fixture("UNAVAILABLE").deepCopy();
        fixture.set("dependencyAssumptions", mapper.valueToTree(List.of(Map.of(
                "capabilityName", "取消责任方", "outcome", "UNAVAILABLE"))));
        fixture.set("expectedOutcome", mapper.valueToTree(Map.of(
                "result", Map.of("dependencyStatus", "UNAVAILABLE"),
                "reasoningClass", "CONTROLLED_DEPENDENCY_UNAVAILABLE")));

        assertThatThrownBy(() -> compiler.validateBusinessCase(SCOPE, "sol:cancel", fixture))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_DUPLICATE");
    }

    private InstructionContract instruction(String ref, String semantics) {
        return new InstructionContract(ref, mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "string"),
                        "reasoning", "required")), InstructionContract.Effect.WRITE,
                "operator:" + ref, new InstructionContract.WriteGovernance(
                        "refund-service", "orderId", "recon:refund"), semantics);
    }

    private InstructionContract readInstruction(String ref, String semantics) {
        return new InstructionContract(ref, mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "string"),
                        "reasoning", "required")), InstructionContract.Effect.READ,
                "operator:" + ref, null, semantics);
    }

    private ScenarioContract scenario(List<String> instructions) {
        List<ScenarioContract.Rule> rules = instructions.stream().map(ref -> new ScenarioContract.Rule(
                "rule:" + ref, mapper.valueToTree(Map.of("party", Map.of("eq", "passenger"))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                        ref, Map.of("orderId", "orderId"), ""))).toList();
        return new ScenarioContract("scn:cancel", List.of("party"),
                ScenarioContract.HitPolicy.UNIQUE, rules,
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL,
                        "", Map.of(), "MANUAL_REVIEW"));
    }

    private SolutionContract solution(List<String> instructions) {
        return new SolutionContract("sol:cancel", "处理取消费争议",
                Map.of("party", "feature:party", "orderId", "feature:order"),
                "scn:cancel", instructions, "caseSet:cancel");
    }

    private JsonNode fixture(String outcome) {
        return fixture(outcome, "退款执行");
    }

    private JsonNode fixture(String outcome, String capabilityName) {
        Map<String, Object> dependency = new java.util.LinkedHashMap<>();
        dependency.put("capabilityName", capabilityName);
        dependency.put("outcome", outcome);
        if ("RETURNS".equals(outcome)) dependency.put("value", Map.of(
                "result", "WAIVED", "reasoning", "符合政策"));
        return mapper.valueToTree(Map.of(
                "givenFacts", List.of(Map.of("factName", "取消责任方", "value", "passenger")),
                "dependencyAssumptions", List.of(dependency)));
    }

    /** Test repository that simulates a committed Feature edit immediately before row locking. */
    private static final class DriftOnFeatureLockRepository implements AgentTddStateRepository {
        private final AgentTddStateRepository delegate;
        private boolean drifted;

        private DriftOnFeatureLockRepository(AgentTddStateRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<AgentTddStoredAsset> find(String scopeKey, String kind, String assetRef) {
            return delegate.find(scopeKey, kind, assetRef);
        }

        @Override
        public List<AgentTddStoredAsset> list(String scopeKey, String kind) {
            return delegate.list(scopeKey, kind);
        }

        @Override
        public AgentTddStoredAsset save(String scopeKey, String kind, String assetRef, JsonNode data) {
            return delegate.save(scopeKey, kind, assetRef, data);
        }

        @Override
        public AgentTddStoredAsset saveIfRevision(String scopeKey, String kind, String assetRef,
                                                  long expectedRevision, JsonNode data) {
            return delegate.saveIfRevision(scopeKey, kind, assetRef, expectedRevision, data);
        }

        @Override
        public <T> T executeAtomically(Supplier<T> action) {
            return delegate.executeAtomically(action);
        }

        @Override
        public AgentTddStoredAsset lockRevision(String scopeKey, String kind, String assetRef,
                                                long expectedRevision) {
            if (!drifted && SolutionEntityRegistry.FEATURE.equals(kind)) {
                drifted = true;
                JsonNode current = delegate.find(scopeKey, kind, assetRef).orElseThrow().data();
                delegate.save(scopeKey, kind, assetRef, current);
            }
            return delegate.lockRevision(scopeKey, kind, assetRef, expectedRevision);
        }

        @Override
        public Optional<JsonNode> replay(String scopeKey, String operation,
                                         String idempotencyKey, String requestFingerprint) {
            return delegate.replay(scopeKey, operation, idempotencyKey, requestFingerprint);
        }

        @Override
        public void record(String scopeKey, String operation, String idempotencyKey,
                           String requestFingerprint, JsonNode response) {
            delegate.record(scopeKey, operation, idempotencyKey, requestFingerprint, response);
        }

        @Override
        public JsonNode executeOnce(String scopeKey, String operation, String idempotencyKey,
                                    String requestFingerprint, Supplier<JsonNode> action) {
            return delegate.executeOnce(scopeKey, operation, idempotencyKey,
                    requestFingerprint, action);
        }

        @Override
        public ExternalExecutionReservation reserveExternalExecution(
                String scopeKey, String operation, String idempotencyKey,
                String requestFingerprint) {
            return delegate.reserveExternalExecution(
                    scopeKey, operation, idempotencyKey, requestFingerprint);
        }

        @Override
        public JsonNode completeExternalExecution(String scopeKey, String operation,
                                                  String idempotencyKey,
                                                  String requestFingerprint,
                                                  JsonNode response) {
            return delegate.completeExternalExecution(
                    scopeKey, operation, idempotencyKey, requestFingerprint, response);
        }
    }
}
