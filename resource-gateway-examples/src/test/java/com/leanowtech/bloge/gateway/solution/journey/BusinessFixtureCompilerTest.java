package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.solution.BusinessCapabilityDisplay;
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
            assertThat(plan.businessContractVector()).hasSize(3);
            assertThat(plan.businessContractVector())
                    .allSatisfy(coordinate -> assertThat(
                            coordinate.propertyStream().map(Map.Entry::getKey).toList())
                            .containsExactlyInAnyOrder(
                                    "assetKind", "assetRef", "semanticKey", "contractFingerprint"));
            assertThat(plan.businessContractVector())
                    .anySatisfy(coordinate -> {
                        assertThat(coordinate.path("assetKind").asText()).isEqualTo("SOLUTION");
                        assertThat(coordinate.path("assetRef").asText()).isEqualTo("sol:cancel");
                        assertThat(coordinate.path("semanticKey").asText())
                                .isEqualTo("legacy:sol:cancel");
                    });
            assertThat(plan.solutionRevision()).isPositive();
            assertThat(plan.solutionContractFingerprint()).startsWith("sha256:");
            assertThat(plan.featureValuesFingerprint()).startsWith("sha256:");
            assertThat(plan.dependencyPlanFingerprint()).startsWith("sha256:");
            assertThat(plan.frozenContextFingerprint()).startsWith("sha256:");
            assertThat(plan.planFingerprint()).startsWith("sha256:");
        }
    }

    @Test
    void usesTheSameExactStableCoordinatesForApprovalAndControlledExecution() {
        ObjectNode businessCase = (ObjectNode) fixture("UNAVAILABLE").deepCopy();
        businessCase.set("expectedOutcome", mapper.valueToTree(Map.of(
                "result", Map.of("dependencyStatus", "UNAVAILABLE"),
                "reasoningClass", "CONTROLLED_DEPENDENCY_UNAVAILABLE")));

        BusinessFixtureCompiler.BusinessCaseValidation validation =
                compiler.validateBusinessCase(SCOPE, "sol:cancel", businessCase);
        BusinessFixtureCompiler.ControlledAssumptionPlan plan =
                compiler.compile(SCOPE, "sol:cancel", businessCase);

        assertThat(validation.businessContractVector())
                .containsExactlyElementsOf(plan.businessContractVector());
        assertThat(validation.businessContractVector()).allSatisfy(coordinate -> {
            assertThat(coordinate.path("assetKind").asText())
                    .isIn("FEATURE", "INSTRUCTION", "SOLUTION");
            assertThat(coordinate.path("assetRef").asText()).isNotBlank();
            assertThat(coordinate.path("semanticKey").asText()).isNotBlank();
            assertThat(coordinate.path("contractFingerprint").asText()).startsWith("sha256:");
            assertThat(coordinate.has("revision")).isFalse();
        });
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

        assertThat(secondFeature.has("revision")).isFalse();
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
                .put("assetRef", "mutated");

        assertThat(plan.given().path("party").asText()).isEqualTo("passenger");
        assertThat(plan.dependencyAssumptions().has("ins:refund")).isTrue();
        assertThat(plan.businessContractVector().getFirst().path("assetRef").asText())
                .isNotEqualTo("mutated");
    }

    @Test
    void rejectsARevisionThatDriftsBetweenObservationAndLock() {
        AgentTddStateRepository drifting = new DriftOnLockRepository(
                states, SolutionEntityRegistry.FEATURE);
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
    void resolvesFactsAndInstructionsByExplicitBusinessDisplay() {
        FeatureContract party = registry.requireFeature(SCOPE, "feature:party").withDisplay(
                display("取消归责", List.of("谁导致取消"), "客服确认取消责任方"));
        InstructionContract balance = registry.requireInstruction(SCOPE, "ins:balance").withDisplay(
                display("读取账户余额", List.of("查余额"), "读取当前账户余额"));
        registry.upsertFeature(SCOPE, party);
        registry.upsertInstruction(SCOPE, balance);
        ObjectNode fixture = (ObjectNode) fixture("RETURNS", "读取账户余额").deepCopy();
        fixture.set("givenFacts", mapper.valueToTree(List.of(
                Map.of("factName", "取消归责", "value", "passenger"))));

        BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                SCOPE, "sol:cancel", fixture);

        assertThat(plan.given().path("party").asText()).isEqualTo("passenger");
        assertThat(plan.dependencyAssumptions().has("ins:balance")).isTrue();
    }

    @Test
    void resolvesFactsAndInstructionsByExplicitBusinessAliases() {
        registry.upsertFeature(SCOPE, registry.requireFeature(SCOPE, "feature:party").withDisplay(
                display("取消归责", List.of("谁导致取消"), "客服确认取消责任方")));
        registry.upsertInstruction(SCOPE,
                registry.requireInstruction(SCOPE, "ins:balance").withDisplay(
                        display("读取账户余额", List.of("查余额"), "读取当前账户余额")));
        ObjectNode fixture = (ObjectNode) fixture("RETURNS", "查余额").deepCopy();
        fixture.set("givenFacts", mapper.valueToTree(List.of(
                Map.of("factName", "谁导致取消", "value", "passenger"))));

        BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                SCOPE, "sol:cancel", fixture);

        assertThat(plan.given().path("party").asText()).isEqualTo("passenger");
        assertThat(plan.dependencyAssumptions().has("ins:balance")).isTrue();
    }

    @Test
    void rejectsADisplayRevisionThatDriftsWhileTheClosureIsFrozen() {
        registry.upsertFeature(SCOPE, registry.requireFeature(SCOPE, "feature:party").withDisplay(
                display("取消归责", List.of("谁导致取消"), "客服确认取消责任方")));
        AgentTddStateRepository drifting = new DriftOnLockRepository(
                states, SolutionEntityRegistry.CAPABILITY_DISPLAY);
        BusinessFixtureCompiler driftingCompiler = new BusinessFixtureCompiler(drifting, mapper);

        assertThatThrownBy(() -> driftingCompiler.compile(
                SCOPE, "sol:cancel", fixture("UNAVAILABLE")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("CAPABILITY_CONTEXT_STALE");
    }

    @Test
    void keepsTheControlledPlanStableAcrossDisplayOnlyRevisions() {
        FeatureContract party = registry.requireFeature(SCOPE, "feature:party").withDisplay(
                display("取消责任方", List.of("谁导致取消"), "客服确认取消责任方"));
        registry.upsertFeature(SCOPE, party);
        BusinessFixtureCompiler.ControlledAssumptionPlan first = compiler.compile(
                SCOPE, "sol:cancel", fixture("UNAVAILABLE"));

        registry.upsertFeature(SCOPE, party.withDisplay(
                display("取消责任方", List.of("取消归责"), "业务负责人确认取消责任方")));
        BusinessFixtureCompiler.ControlledAssumptionPlan second = compiler.compile(
                SCOPE, "sol:cancel", fixture("UNAVAILABLE"));

        assertThat(second.planFingerprint()).isEqualTo(first.planFingerprint());
        assertThat(second.frozenContextFingerprint()).isEqualTo(first.frozenContextFingerprint());
    }

    @Test
    void rejectsAnAliasSharedByTwoReachableInstructions() {
        registry.upsertInstruction(SCOPE, readInstruction("ins:balance", "余额查询").withDisplay(
                display("读取余额", List.of("账户查询"), "读取余额")));
        registry.upsertInstruction(SCOPE, readInstruction("ins:ledger", "账本查询").withDisplay(
                display("读取账本", List.of("账户查询"), "读取账本")));
        registry.upsertScenario(SCOPE, scenario(List.of("ins:balance", "ins:ledger")));
        registry.upsertSolution(SCOPE, solution(List.of("ins:balance", "ins:ledger")), false);

        assertThatThrownBy(() -> compiler.compile(
                SCOPE, "sol:cancel", fixture("RETURNS", "账户查询")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_AMBIGUOUS");
    }

    @Test
    void doesNotTreatDisplayDescriptionAsACapabilityIdentity() {
        registry.upsertInstruction(SCOPE, readInstruction("ins:balance", "余额查询").withDisplay(
                display("读取余额", List.of(), "供客服查询当前账户余额")));

        assertThatThrownBy(() -> compiler.compile(
                SCOPE, "sol:cancel", fixture("RETURNS", "供客服查询当前账户余额")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_AMBIGUOUS");
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

    private BusinessCapabilityDisplay display(
            String businessName, List<String> aliases, String description) {
        return new BusinessCapabilityDisplay(
                BusinessCapabilityDisplay.SCHEMA_VERSION, businessName, description,
                aliases, List.of("取消费争议"), List.of("处理取消费争议"), List.of());
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

    /** Test repository that simulates a committed edit immediately before a selected row locks. */
    private static final class DriftOnLockRepository implements AgentTddStateRepository {
        private final AgentTddStateRepository delegate;
        private final String driftingKind;
        private boolean drifted;

        private DriftOnLockRepository(AgentTddStateRepository delegate, String driftingKind) {
            this.delegate = delegate;
            this.driftingKind = driftingKind;
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
            if (!drifted && driftingKind.equals(kind)) {
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
