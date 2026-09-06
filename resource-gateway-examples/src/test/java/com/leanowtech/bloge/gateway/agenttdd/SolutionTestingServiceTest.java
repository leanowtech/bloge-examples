package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionExecutableSnapshot;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.journey.BusinessFixtureCompiler;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenMaterialStore;
import com.leanowtech.bloge.gateway.solution.journey.ControlledTestEgressGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies Scenario contracts and approved Solution GOLDEN baselines remain pure and zero-egress. */
class SolutionTestingServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private final AtomicInteger writes = new AtomicInteger();
    private final SolutionTestingService testing = new SolutionTestingService(
            states, registry, mapper, (instruction, values, context) -> {
                writes.incrementAndGet();
                return Map.of("result", Map.of("decision", "REAL"), "reasoning", "real");
            });

    @BeforeEach
    void defineTree() {
        registry.upsertFeature(SCOPE, new FeatureContract(
                "responsibility.party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:party", "", ""));
        registry.upsertFeature(SCOPE, new FeatureContract(
                "dispute.orderSelected", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:order", "", ""));
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of(
                        "result", Map.of("type", Map.of("fields", Map.of(
                                "decision", Map.of("enum", List.of("WAIVED"))))),
                        "reasoning", "required")),
                InstructionContract.Effect.WRITE, "",
                new InstructionContract.WriteGovernance("refund", "orderId", "recon:refund")));
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:refund", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(
                        ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
        registry.upsertSolution(SCOPE, new SolutionContract(
                "sol:cancel", "Resolve cancellation dispute.",
                Map.of("party", "responsibility.party", "orderId", "dispute.orderSelected"),
                "scn:root", List.of("ins:refund"), "caseSet:cancel"), true);
    }

    @Test
    void testsScenarioOutletsWithoutCallingAnyInstruction() {
        Map<String, Object> result = testing.testScenario(SCOPE, "scn:root", mapper.valueToTree(List.of(
                Map.of("caseId", "none-party", "given", Map.of("party", "none", "orderId", "O-1"),
                        "expect", Map.of("outletKind", "INSTRUCTION", "ref", "ins:refund")),
                Map.of("caseId", "fallback", "given", Map.of("party", "driver", "orderId", "O-2"),
                        "expect", Map.of("outletKind", "TERMINAL", "terminalKind", "ESCALATE")))));

        assertThat(result).containsEntry("passed", 2L).containsEntry("failed", 0L)
                .containsEntry("realExternalCalls", 0);
        assertThat(writes).hasValue(0);
    }

    @Test
    void runsOnlyApprovedGoldenAndSynthesizesContractShapedWriteResult() {
        storeCases("ACTIVE", Map.of("result", Map.of("decision", "WAIVED")));

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO")
                .containsEntry("realExternalCalls", 0)
                .containsKeys("goldenSetId", "evidenceRef");
        assertThat((List<?>) result.get("businessBacklog")).isEmpty();
        assertThat(writes).hasValue(0);
        assertThat(states.find(SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel")).isPresent();
        assertThat(states.find(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel")
                .orElseThrow().data().at("/rows/0/qualityState").asText()).isEqualTo("READY");
    }

    @Test
    void compilesWriteAssumptionsIntoACaseScopedNoEffectChannel() {
        storeControlledCases(Map.of("ins:refund", Map.of("outcome", "SUCCEEDS_WITHOUT_EFFECT")));

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO").containsEntry("realExternalCalls", 0);
        assertThat(writes).hasValue(0);
    }

    @Test
    void bindsJourneyBaselineEvidenceToTheFrozenPlanAndBusinessContracts() {
        storeControlledCases(Map.of("ins:refund", Map.of("outcome", "SUCCEEDS_WITHOUT_EFFECT")));
        SolutionTestingService.BaselineContext context = new SolutionTestingService.BaselineContext(
                "journey:cancel", 7, "sha256:solution-context");

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN", null, context);

        assertThat(result).containsEntry("journeyRef", "journey:cancel")
                .containsEntry("journeyRevision", 7L)
                .containsEntry("solutionContextFingerprint", "sha256:solution-context")
                .containsEntry("compilerVersion", "rg.solution-controlled-test.v1")
                .containsEntry("egressPolicy", "DENY_ALL")
                .containsKeys("scopeFingerprint", "planFingerprint", "implementationFingerprint");
        JsonNode evidence = states.find(
                SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel").orElseThrow().data();
        assertThat(evidence.path("controlledAssumptionPlanFingerprints")).hasSize(1);
        assertThat(evidence.path("frozenFeatureContracts")).hasSize(2);
        assertThat(evidence.path("frozenScenarioContracts")).hasSize(1);
        assertThat(evidence.path("frozenInstructionContracts")).hasSize(1);
        assertThat(evidence.path("frozenExecutableContracts")).hasSize(5);
        assertThat(evidence.path("implementationFingerprint").asText()).startsWith("sha256:");
        assertThat(evidence.toString()).doesNotContain("SUCCEEDS_WITHOUT_EFFECT", "O-1");
    }

    @Test
    void executesAndAttestsOneFrozenClosureWhenAuthoringContractsChangeAfterFreeze() {
        storeCases("ACTIVE", Map.of("result", Map.of("decision", "WAIVED")));
        AgentTddStoredAsset oldScenario = states.find(
                SCOPE, SolutionEntityRegistry.SCENARIO, "scn:root").orElseThrow();
        AgentTddStoredAsset oldInstruction = states.find(
                SCOPE, SolutionEntityRegistry.INSTRUCTION, "ins:refund").orElseThrow();
        java.util.concurrent.atomic.AtomicReference<SolutionExecutableSnapshot> observed =
                new java.util.concurrent.atomic.AtomicReference<>();
        SolutionTestingService frozenTesting = new SolutionTestingService(
                states, registry, mapper, (instruction, values, context) -> {
                    throw new AssertionError("A controlled baseline must not retain the runtime channel.");
                }, null, new ControlledTestEgressGuard(), executable -> {
                    observed.set(executable);
                    registry.upsertScenario(SCOPE, new ScenarioContract(
                            "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                            List.of(new ScenarioContract.Rule("R1",
                                    mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                                    new ScenarioContract.Outlet(
                                            ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "REVIEW"))),
                            new ScenarioContract.Outlet(
                                    ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
                    registry.upsertInstruction(SCOPE, new InstructionContract(
                            "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                            mapper.valueToTree(Map.of(
                                    "result", Map.of("type", Map.of("fields", Map.of(
                                            "decision", Map.of("enum", List.of("DENIED"))))),
                                    "reasoning", "required")), InstructionContract.Effect.WRITE, "",
                            new InstructionContract.WriteGovernance("refund", "orderId", "recon:refund")));
                });

        Map<String, Object> result = frozenTesting.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO").containsEntry("realExternalCalls", 0);
        assertThat(observed).doesNotHaveNullValue();
        JsonNode evidence = states.find(
                SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel").orElseThrow().data();
        assertThat(evidence.path("frozenScenarioContracts").get(0).path("revision").asLong())
                .isEqualTo(oldScenario.revision());
        assertThat(evidence.path("frozenInstructionContracts").get(0).path("revision").asLong())
                .isEqualTo(oldInstruction.revision());
        assertThat(evidence.path("implementationFingerprint").asText())
                .isEqualTo(SolutionImplementationIdentity.fingerprint(
                        mapper, observed.get().contracts()))
                .isNotEqualTo(SolutionImplementationIdentity.fingerprint(
                        registry, mapper, SCOPE, registry.requireSolution(SCOPE, "sol:cancel")));
        assertThat(states.find(SCOPE, SolutionEntityRegistry.SCENARIO, "scn:root")
                .orElseThrow().revision()).isGreaterThan(oldScenario.revision());
    }

    @Test
    void deniesAnUncontrolledReadInstructionWithoutRetainingTheRuntimeChannelOrGreenEvidence() {
        storeCases("ACTIVE", Map.of("result", Map.of("decision", "REAL")));
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of(
                        "result", Map.of("type", Map.of("fields", Map.of(
                                "decision", Map.of("enum", List.of("REAL"))))),
                        "reasoning", "required")), InstructionContract.Effect.READ,
                "operator:refund-read", null));
        ControlledTestEgressGuard guard = new ControlledTestEgressGuard();
        AtomicInteger runtimeCalls = new AtomicInteger();
        SolutionTestingService guarded = new SolutionTestingService(
                states, registry, mapper, (instruction, values, context) -> {
                    runtimeCalls.incrementAndGet();
                    return Map.of("result", Map.of("decision", "REAL"), "reasoning", "runtime");
                }, null, guard);

        assertThatThrownBy(() -> guarded.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN"))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTROLLED_TEST_EGRESS_DENIED"));
        assertThat(guard.deniedAttempts()).isEqualTo(1);
        assertThat(runtimeCalls).hasValue(0);
        assertThat(states.find(SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel")).isEmpty();
        assertThat(states.find(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel")
                .orElseThrow().data().at("/rows/0/qualityState").asText())
                .isEqualTo("DESIGNED_NOT_RUN");
    }

    @Test
    void recompilesApprovedBusinessMaterialAfterAnImplementationRevision() {
        JsonNode businessCase = mapper.valueToTree(Map.ofEntries(
                Map.entry("caseId", "g-business"),
                Map.entry("businessIntent", "乘客无责时免除取消费"),
                Map.entry("givenFacts", List.of(
                        Map.of("factName", "responsibility.party", "value", "none"),
                        Map.of("factName", "dispute.orderSelected", "value", "O-1"))),
                Map.entry("dependencyAssumptions", List.of(Map.of(
                        "capabilityName", "ins:refund", "outcome", "SUCCEEDS_WITHOUT_EFFECT"))),
                Map.entry("expectedOutcome", Map.of(
                        "result", Map.of("decision", "WAIVED"), "reasoningClass", "STUBBED_WRITE")),
                Map.entry("oracleOwner", "cx-ops"),
                Map.entry("businessCaseFingerprint", "sha256:" + "a".repeat(64)),
                Map.entry("goldenCaseFingerprint", "sha256:" + "b".repeat(64))));
        BusinessFixtureCompiler.BusinessCaseValidation validation =
                new BusinessFixtureCompiler(states, mapper)
                        .validateBusinessCase(SCOPE, "sol:cancel", businessCase);
        ObjectNode caseSet = mapper.createObjectNode();
        caseSet.put("caseSetRef", "caseSet:cancel");
        caseSet.put("toolRef", "sol:cancel");
        caseSet.set("rows", mapper.valueToTree(List.of(Map.ofEntries(
                Map.entry("caseId", "g-business"), Map.entry("category", "GOLDEN"),
                Map.entry("lifecycle", "ACTIVE"), Map.entry("qualityState", "DESIGNED_NOT_RUN"),
                Map.entry("oracleOwner", "cx-ops"),
                Map.entry("businessCaseFingerprint", "sha256:" + "a".repeat(64)),
                Map.entry("goldenCaseFingerprint", "sha256:" + "b".repeat(64)),
                Map.entry("businessContractVector", validation.businessContractVector()),
                Map.entry("materialReceipt", Map.of("materialRef", "business:g-business")),
                Map.entry("proposedOracle", Map.of("status", "APPROVED"))))));
        states.save(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel", caseSet);
        assertThat(caseSet.toString()).doesNotContain("controlledAssumptionPlanFingerprint",
                "featureValuesFingerprint", "dependencyPlanFingerprint", "frozenContextFingerprint");
        BusinessGoldenMaterialStore materials = new FixedBusinessMaterialStore(mapper, businessCase);
        SolutionTestingService protectedTesting = new SolutionTestingService(
                states, registry, mapper, (instruction, values, context) -> {
                    throw new AssertionError("Protected baseline must never use the runtime channel.");
                }, materials);

        protectedTesting.baseline(SCOPE, "sol:cancel", "caseSet:cancel", "GREEN", readIdentity());
        String firstCompiledPlan = states.find(
                SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel")
                .orElseThrow().data().at("/controlledAssumptionPlanFingerprints/0").asText();
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:refund", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(
                        ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "REVIEW")));

        protectedTesting.baseline(SCOPE, "sol:cancel", "caseSet:cancel", "GREEN", readIdentity());
        String recompiledPlan = states.find(
                SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel")
                .orElseThrow().data().at("/controlledAssumptionPlanFingerprints/0").asText();

        assertThat(recompiledPlan).isNotEqualTo(firstCompiledPlan);
        JsonNode approvedMetadata = states.find(
                SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel").orElseThrow().data().at("/rows/0");
        assertThat(approvedMetadata.path("lifecycle").asText()).isEqualTo("ACTIVE");
        assertThat(approvedMetadata.at("/proposedOracle/status").asText()).isEqualTo("APPROVED");
        assertThat(writes).hasValue(0);
    }

    @Test
    void failsClosedWhenAnApprovedCaseForbidsTheSelectedDependency() {
        storeControlledCases(Map.of("ins:refund", Map.of("outcome", "MUST_NOT_BE_USED")));

        assertThatThrownBy(() -> testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN"))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CONTROLLED_DEPENDENCY_FORBIDDEN"));
        assertThat(writes).hasValue(0);
        assertThat(states.find(SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel")).isEmpty();
    }

    @Test
    void treatsAControlledInstructionFailureAsTheApprovedBusinessFallback() {
        storeControlledCases(Map.of("ins:refund", Map.of(
                        "assetKind", "INSTRUCTION", "outcome", "UNAVAILABLE")),
                Map.of("result", Map.of("dependencyStatus", "UNAVAILABLE"),
                        "reasoning", "CONTROLLED_DEPENDENCY_UNAVAILABLE"));

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO").containsEntry("realExternalCalls", 0);
        assertThat(writes).hasValue(0);
    }

    @Test
    void exercisesAFeatureFailureWithoutEnteringARealFeatureBackend() {
        storeControlledCases(Map.of("responsibility.party", Map.of(
                        "assetKind", "FEATURE", "inputAlias", "party", "outcome", "UNAVAILABLE")),
                Map.of("result", Map.of("dependencyStatus", "UNAVAILABLE"),
                        "reasoning", "CONTROLLED_DEPENDENCY_UNAVAILABLE"));

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO").containsEntry("realExternalCalls", 0);
        assertThat(writes).hasValue(0);
    }

    @Test
    void exercisesAFeatureFailureWithoutEffectAsAnApprovedBusinessFallback() {
        storeControlledCases(Map.of("responsibility.party", Map.of(
                        "assetKind", "FEATURE", "inputAlias", "party",
                        "outcome", "FAILS_WITHOUT_EFFECT")),
                Map.of("result", Map.of("dependencyStatus", "FAILED_WITHOUT_EFFECT"),
                        "reasoning", "CONTROLLED_DEPENDENCY_FAILED_WITHOUT_EFFECT"));

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO").containsEntry("realExternalCalls", 0);
        assertThat(writes).hasValue(0);
    }

    @Test
    void exercisesAnInstructionFailureWithoutEffectAsAnApprovedBusinessFallback() {
        storeControlledCases(Map.of("ins:refund", Map.of(
                        "assetKind", "INSTRUCTION", "outcome", "FAILS_WITHOUT_EFFECT")),
                Map.of("result", Map.of("dependencyStatus", "FAILED_WITHOUT_EFFECT"),
                        "reasoning", "CONTROLLED_DEPENDENCY_FAILED_WITHOUT_EFFECT"));

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO").containsEntry("realExternalCalls", 0);
        assertThat(writes).hasValue(0);
    }

    @Test
    void rejectsAnEgressProbeBeforeExecutionAndDoesNotSaveGreenEvidence() {
        storeControlledCases(Map.of("ins:refund", Map.of(
                "assetKind", "INSTRUCTION", "outcome", "SUCCEEDS_WITHOUT_EFFECT")));
        ControlledTestEgressGuard guard = new ControlledTestEgressGuard(
                candidate -> candidate.deny("HTTP"));
        SolutionTestingService guarded = new SolutionTestingService(
                states, registry, mapper, (instruction, values, context) -> {
                    writes.incrementAndGet();
                    return Map.of();
                }, null, guard);

        assertThatThrownBy(() -> guarded.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN"))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTROLLED_TEST_EGRESS_DENIED"));
        assertThat(guard.deniedAttempts()).isEqualTo(1);
        assertThat(writes).hasValue(0);
        assertThat(states.find(SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel")).isEmpty();
        assertThat(states.find(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel")
                .orElseThrow().data().at("/rows/0/qualityState").asText())
                .isEqualTo("DESIGNED_NOT_RUN");
    }

    @Test
    void doesNotFailAMustNotBeUsedAssumptionWhenTheScenarioDoesNotSelectIt() {
        storeControlledCases(Map.of("ins:refund", Map.of(
                        "assetKind", "INSTRUCTION", "outcome", "MUST_NOT_BE_USED")),
                Map.of("result", Map.of("terminalKind", "ESCALATE"),
                        "reasoning", "TERMINAL_SCENARIO_OUTLET"),
                Map.of("party", "driver", "orderId", "O-1"));

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO");
        assertThat(writes).hasValue(0);
    }

    @Test
    void reportsBusinessBacklogAndRefusesUnapprovedOracleRows() {
        storeCases("ACTIVE", Map.of("result", Map.of("decision", "UPHELD")));
        Map<String, Object> failed = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "RED");
        assertThat(failed).containsEntry("status", "NO_GO");
        assertThat((List<?>) failed.get("businessBacklog")).hasSize(1);

        storeCases("DRAFT", Map.of("result", Map.of("decision", "WAIVED")));
        assertThatThrownBy(() -> testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN"))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("GOLDEN_REQUIRES_APPROVAL");
    }

    @Test
    void keepsTestFailuresVisibleWithoutCountingThemAsLivePerformance() {
        storeCases("ACTIVE", Map.of("result", Map.of("decision", "UPHELD")));
        testing.baseline(SCOPE, "sol:cancel", "caseSet:cancel", "RED");

        Map<String, Object> performance = new SolutionPerformanceService(states)
                .performance("sol:cancel", readIdentity());

        assertThat(performance).containsEntry("totalCases", 1)
                .containsEntry("totalInvocations", 0)
                .containsEntry("escalationRate", 0.0d);
        assertThat(((List<?>) performance.get("redGolden")).stream().map(Object::toString).toList())
                .containsExactly("g1");
        assertThat((List<?>) performance.get("hitDistribution")).isEmpty();
        assertThat((List<?>) performance.get("dispositionDistribution")).isEmpty();
        assertThat(performance.toString()).doesNotContain("UPHELD", "party=none", "O-1");
    }

    private void storeCases(String lifecycle, Map<String, Object> expect) {
        ObjectNode data = mapper.createObjectNode();
        data.put("caseSetRef", "caseSet:cancel");
        data.put("toolRef", "sol:cancel");
        data.set("rows", mapper.valueToTree(List.of(Map.of(
                "caseId", "g1", "category", "GOLDEN", "lifecycle", lifecycle,
                "qualityState", "DESIGNED_NOT_RUN", "oracleOwner", "cx-ops",
                "given", Map.of("party", "none", "orderId", "O-1"),
                "stubs", Map.of(), "expect", expect))));
        states.save(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel", data);
    }

    private void storeControlledCases(Map<String, Object> assumptions) {
        storeControlledCases(assumptions, Map.of("result", Map.of("decision", "WAIVED")));
    }

    private void storeControlledCases(
            Map<String, Object> assumptions, Map<String, Object> expect) {
        storeControlledCases(assumptions, expect, Map.of("party", "none", "orderId", "O-1"));
    }

    private void storeControlledCases(
            Map<String, Object> assumptions, Map<String, Object> expect,
            Map<String, Object> given) {
        ObjectNode data = mapper.createObjectNode();
        data.put("caseSetRef", "caseSet:cancel");
        data.put("toolRef", "sol:cancel");
        data.set("rows", mapper.valueToTree(List.of(Map.ofEntries(
                Map.entry("caseId", "g-controlled"), Map.entry("category", "GOLDEN"),
                Map.entry("lifecycle", "ACTIVE"), Map.entry("qualityState", "DESIGNED_NOT_RUN"),
                Map.entry("oracleOwner", "cx-ops"),
                Map.entry("given", given),
                Map.entry("stubs", Map.of()), Map.entry("controlledAssumptions", assumptions),
                Map.entry("expect", expect)))));
        states.save(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel", data);
    }

    private static IntegrationRequestContext readIdentity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "agent-1", "", "AGENT_TDD_READ", "corr-1");
    }

    private static final class FixedBusinessMaterialStore extends BusinessGoldenMaterialStore {
        private final JsonNode payload;

        private FixedBusinessMaterialStore(ObjectMapper mapper, JsonNode payload) {
            super((com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService) null,
                    mapper);
            this.payload = payload.deepCopy();
        }

        @Override
        public JsonNode read(JsonNode receiptNode, IntegrationRequestContext caller) {
            return payload.deepCopy();
        }
    }
}
