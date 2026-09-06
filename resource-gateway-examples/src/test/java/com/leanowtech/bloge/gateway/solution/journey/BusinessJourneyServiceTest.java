package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddReviewService;
import com.leanowtech.bloge.gateway.agenttdd.SolutionTestingService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.BusinessFactSemanticContract;
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

/** Proves that business navigation and complete GOLDEN governance share one current asset line. */
class BusinessJourneyServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private final BusinessJourneyService journeys = new BusinessJourneyService(states, registry, mapper);
    private final TestMaterialStore materials = new TestMaterialStore(mapper);
    private final BusinessGoldenService golden = new BusinessGoldenService(states, mapper, materials);

    @BeforeEach
    void defineCurrentBusinessContracts() {
        BusinessFactSemanticContract semantics = new BusinessFactSemanticContract(
                BusinessFactSemanticContract.SCHEMA_VERSION,
                "ride.cancel.party", "判断取消责任", "ride-cancellation", "ride-order",
                mapper.createArrayNode(),
                mapper.valueToTree(Map.of("type", "enum", "values", List.of("passenger", "driver"))),
                "CANCELLATION_OCCURRED_AT", "REQUIRE_HUMAN_REVIEW", "PLATFORM",
                "responsibility-center", mapper.valueToTree(Map.of("mode", "AS_OF_EVENT")), "READ", "ACTIVE");
        registry.upsertFeature(SCOPE, new FeatureContract("responsibility.party",
                mapper.valueToTree(Map.of("type", Map.of("enum", List.of("passenger", "driver")))),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:responsibility#$.party",
                "", "", "取消责任方", semantics));
        registry.upsertInstruction(SCOPE, new InstructionContract("ins:uphold",
                mapper.valueToTree(Map.of("party", "string")), mapper.valueToTree(Map.of(
                "result", Map.of("type", Map.of("fields", Map.of(
                        "decision", Map.of("enum", List.of("UPHELD"))))), "reasoning", "required")),
                InstructionContract.Effect.READ, "tool:uphold", null, "维持费用"));
        registry.upsertScenario(SCOPE, new ScenarioContract("scn:cancel", List.of("party"),
                ScenarioContract.HitPolicy.UNIQUE, List.of(new ScenarioContract.Rule("R1",
                mapper.valueToTree(Map.of("party", Map.of("eq", "passenger"))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                        "ins:uphold", Map.of("party", "party"), ""))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
        registry.upsertSolution(SCOPE, new SolutionContract("sol:cancel", "处理取消费争议",
                Map.of("party", "responsibility.party"),
                "scn:cancel", List.of("ins:uphold"), "caseSet:journey"), true);
    }

    @Test
    void derivesStagesFromAssetsAndRejectsStaleOrOutOfOrderActions() {
        Map<String, Object> started = journeys.start(startRequest("journey-start-1"), agent());
        String ref = started.get("journeyRef").toString();
        assertThat(started).containsEntry("stage", "DEFINING_FEATURES");

        ObjectNode featureAction = action(ref, 1);
        journeys.executeAction("rg.feature.define", featureAction, agent(),
                () -> Map.of("featureId", "responsibility.party", "revision", 1));
        Map<String, Object> rules = journeys.next(next(ref, 2), agent());
        assertThat(rules).containsEntry("stage", "DEFINING_RULES");

        assertThatThrownBy(() -> journeys.executeAction("rg.solution.compose", action(ref, 2), agent(), Map::of))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("JOURNEY_ACTION_NOT_ALLOWED"));
        assertThatThrownBy(() -> journeys.next(next(ref, 1), agent()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("JOURNEY_REVISION_STALE"));
    }

    @Test
    void suppliesTheLockedJourneyCoordinateToAnAtomicAction() {
        String ref = journeys.start(startRequest("journey-action-context"), agent())
                .get("journeyRef").toString();

        Map<String, Object> observed = journeys.executeActionWithContext(
                "rg.feature.define", action(ref, 1), agent(), context -> Map.of(
                        "featureId", "responsibility.party",
                        "revision", 1,
                        "observedJourneyRef", context.journeyRef(),
                        "observedJourneyRevision", context.journeyRevision(),
                        "observedScopeFingerprint", context.scopeFingerprint()));

        assertThat(observed).containsEntry("observedJourneyRef", ref)
                .containsEntry("observedJourneyRevision", 1L);
        assertThat(observed.get("observedScopeFingerprint").toString()).startsWith("sha256:");
    }

    @Test
    void proposesSummaryOnlyCasesAndRequiresIndependentHumanApprovalBeforeTesting() {
        String ref = journeys.start(startRequest("journey-start-2"), agent()).get("journeyRef").toString();
        associate(ref, 1, "rg.feature.define", Map.of("featureId", "responsibility.party", "revision", 1));
        associate(ref, 2, "rg.scenario.define", Map.of("scenarioId", "scn:cancel", "revision", 1));
        associate(ref, 3, "rg.instruction.define", Map.of("instructionId", "ins:uphold", "revision", 1));
        Map<String, Object> composing = journeys.next(next(ref, 4), agent());
        String context = composing.get("solutionContextFingerprint").toString();
        ObjectNode compose = action(ref, 4).put("solutionContextFingerprint", context);
        journeys.executeAction("rg.solution.compose", compose, agent(),
                () -> Map.of("solutionRef", "sol:cancel", "revision", 1));

        ObjectNode proposal = action(ref, 5).put("solutionRef", "sol:cancel")
                .put("idempotencyKey", "golden-proposal-1");
        proposal.set("cases", mapper.valueToTree(List.of(Map.of(
                "caseId", "g1", "businessIntent", "乘客超时取消由乘客承担",
                "givenFacts", List.of(Map.of("factName", "取消责任方", "value", "passenger")),
                "dependencyAssumptions", List.of(Map.of("capabilityName", "维持费用",
                        "outcome", "RETURNS", "value", Map.of("result", Map.of("decision", "UPHELD"),
                                "reasoning", "责任在乘客"))),
                "expectedOutcome", Map.of("result", Map.of("decision", "UPHELD"),
                        "reasoningClass", "责任在乘客"), "oracleOwner", "cx-policy"))));
        Map<String, Object> proposed = journeys.executeAction("rg.solution.golden.propose", proposal, agent(),
                () -> golden.propose(proposal, agent()));

        assertThat(proposed.toString()).doesNotContain("passenger", "UPHELD", "责任在乘客");
        JsonNode metadata = states.find(SCOPE, AgentTddMutationService.CASE_SET,
                proposed.get("caseSetRef").toString()).orElseThrow().data().at("/rows/0");
        assertThat(metadata.has("materialReceipt")).isTrue();
        assertThat(metadata.has("given")).isFalse();
        assertThat(metadata.has("expect")).isFalse();
        assertThat(metadata.has("controlledAssumptions")).isFalse();
        assertThat(journeys.next(next(ref, 6), agent())).containsEntry("stage", "WAITING_GOLDEN_APPROVAL");
        Map<?, ?> summary = (Map<?, ?>) ((List<?>) proposed.get("caseSummaries")).getFirst();
        AgentTddReviewService reviews = new AgentTddReviewService(states, materials);
        Map<String, Object> review = reviews.oracleReview(
                proposed.get("caseSetRef").toString(), "g1", 1, reviewer());
        assertThat(mapper.valueToTree(review).path("businessIntent").asText())
                .isEqualTo("乘客超时取消由乘客承担");
        assertThat(review).containsKeys("givenFacts", "dependencyAssumptions", "expectedOutcome")
                .doesNotContainKeys("given", "stubs", "expect", "controlledAssumptions");
        AgentTddStoredAsset approved = reviews.approveOracle(
                proposed.get("caseSetRef").toString(), "g1", 1,
                summary.get("goldenCaseFingerprint").toString(), reviewer());
        assertThat(approved.data().at("/rows/0/lifecycle").asText()).isEqualTo("ACTIVE");
        assertThat(journeys.next(next(ref, 6), agent())).containsEntry("stage", "TESTING");

        SolutionTestingService testing = new SolutionTestingService(states, registry, mapper,
                (instruction, values, executionContext) -> {
                    throw new AssertionError("A protected controlled test must not use the runtime channel.");
                }, materials);
        Map<String, Object> baseline = testing.baseline(SCOPE, "sol:cancel",
                proposed.get("caseSetRef").toString(), "GREEN", agent());
        assertThat(baseline).containsEntry("status", "GO").containsEntry("realExternalCalls", 0);
        assertThat(((List<?>) baseline.get("cases"))).singleElement().asString().contains("GREEN_PASS");
        assertThat(journeys.next(next(ref, 6), agent())).containsEntry("stage", "WAITING_SIGNOFF");

        AgentTddStoredAsset observedEvidence = states.find(
                SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel").orElseThrow();
        ObjectNode changedPlan = (ObjectNode) observedEvidence.data().deepCopy();
        changedPlan.withArray("controlledAssumptionPlanFingerprints").add("sha256:"
                + "a".repeat(64));
        states.save(SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel", changedPlan);
        assertThat(journeys.next(next(ref, 6), agent())).containsEntry("stage", "TESTING");

        testing.baseline(SCOPE, "sol:cancel", proposed.get("caseSetRef").toString(), "GREEN", agent());
        assertThat(journeys.next(next(ref, 6), agent())).containsEntry("stage", "WAITING_SIGNOFF");
        registry.upsertScenario(SCOPE, new ScenarioContract("scn:cancel", List.of("party"),
                ScenarioContract.HitPolicy.UNIQUE, List.of(new ScenarioContract.Rule("R1",
                mapper.valueToTree(Map.of("party", Map.of("eq", "passenger"))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                        "ins:uphold", Map.of("party", "party"), ""))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "REVIEW")));
        assertThat(journeys.next(next(ref, 6), agent())).containsEntry("stage", "TESTING");

        BusinessFactSemanticContract revisedSemantics = new BusinessFactSemanticContract(
                BusinessFactSemanticContract.SCHEMA_VERSION,
                "ride.cancel.party", "判断取消责任和平台责任", "ride-cancellation", "ride-order",
                mapper.createArrayNode(),
                mapper.valueToTree(Map.of("type", "enum",
                        "values", List.of("passenger", "driver", "platform"))),
                "CANCELLATION_OCCURRED_AT", "REQUIRE_HUMAN_REVIEW", "PLATFORM",
                "responsibility-center", mapper.valueToTree(Map.of("mode", "AS_OF_EVENT")),
                "READ", "ACTIVE");
        registry.upsertFeature(SCOPE, new FeatureContract("responsibility.party",
                mapper.valueToTree(Map.of("type", Map.of(
                        "enum", List.of("passenger", "driver", "platform")))),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:responsibility#$.party",
                "", "", "取消责任方", revisedSemantics));
        assertThat(journeys.next(next(ref, 6), agent()).get("blockingReasons"))
                .isEqualTo(List.of("GOLDEN_CASE_STALE"));
        assertThatThrownBy(() -> testing.baseline(SCOPE, "sol:cancel",
                proposed.get("caseSetRef").toString(), "GREEN", agent()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GOLDEN_CASE_STALE"));
    }

    @Test
    void legacyPlaintextGoldenCannotAdvanceTheBusinessJourney() {
        String ref = journeys.start(startRequest("journey-start-legacy"), agent()).get("journeyRef").toString();
        associate(ref, 1, "rg.feature.define", Map.of("featureId", "responsibility.party", "revision", 1));
        associate(ref, 2, "rg.scenario.define", Map.of("scenarioId", "scn:cancel", "revision", 1));
        associate(ref, 3, "rg.instruction.define", Map.of("instructionId", "ins:uphold", "revision", 1));
        ObjectNode compose = action(ref, 4).put("solutionContextFingerprint",
                journeys.next(next(ref, 4), agent()).get("solutionContextFingerprint").toString());
        journeys.executeAction("rg.solution.compose", compose, agent(),
                () -> Map.of("solutionRef", "sol:cancel", "revision", 1));
        ObjectNode legacy = mapper.createObjectNode().put("toolRef", "sol:cancel");
        legacy.putArray("rows").addObject().put("caseId", "legacy").put("category", "GOLDEN")
                .put("lifecycle", "ACTIVE").set("expect", mapper.valueToTree(Map.of("result", Map.of())));
        AgentTddStoredAsset stored = states.save(SCOPE, AgentTddMutationService.CASE_SET,
                "caseSet:legacy", legacy);
        associate(ref, 5, "rg.solution.golden.propose",
                Map.of("caseSetRef", stored.assetRef(), "revision", stored.revision()));

        assertThat(journeys.next(next(ref, 6), agent()))
                .containsEntry("stage", "WAITING_GOLDEN_APPROVAL")
                .extractingByKey("blockingReasons")
                .asList().containsExactly("LEGACY_GOLDEN_REAPPROVAL_REQUIRED");
    }

    @Test
    void changesSolutionContextWhenAnAssociatedContractChanges() {
        String ref = journeys.start(startRequest("journey-start-3"), agent()).get("journeyRef").toString();
        associate(ref, 1, "rg.feature.define", Map.of("featureId", "responsibility.party", "revision", 1));
        associate(ref, 2, "rg.scenario.define", Map.of("scenarioId", "scn:cancel", "revision", 1));
        associate(ref, 3, "rg.instruction.define", Map.of("instructionId", "ins:uphold", "revision", 1));
        String before = journeys.next(next(ref, 4), agent()).get("solutionContextFingerprint").toString();

        registry.upsertInstruction(SCOPE, new InstructionContract("ins:uphold",
                mapper.valueToTree(Map.of("orderId", "string")), mapper.valueToTree(Map.of(
                "result", Map.of("type", Map.of("fields", Map.of(
                        "decision", Map.of("enum", List.of("UPHELD", "REVIEW"))))), "reasoning", "required")),
                InstructionContract.Effect.READ, "tool:uphold", null, "维持费用"));

        String after = journeys.next(next(ref, 4), agent()).get("solutionContextFingerprint").toString();
        assertThat(after).isNotEqualTo(before);
        ObjectNode stale = action(ref, 4).put("solutionContextFingerprint", before);
        assertThatThrownBy(() -> journeys.executeAction("rg.solution.compose", stale, agent(), Map::of))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SOLUTION_CONTEXT_STALE"));
    }

    @Test
    void cancelledJourneyIsTerminalAndExposesOnlyReadNavigation() {
        String ref = journeys.start(startRequest("journey-cancelled"), agent())
                .get("journeyRef").toString();
        AgentTddStoredAsset current = states.find(SCOPE, BusinessJourneyService.JOURNEY, ref)
                .orElseThrow();
        ObjectNode cancelled = (ObjectNode) current.data().deepCopy();
        cancelled.put("status", "CANCELLED");
        long cancelledRevision = states.saveIfRevision(SCOPE, BusinessJourneyService.JOURNEY,
                ref, current.revision(), cancelled).revision();

        Map<String, Object> projection = journeys.next(next(ref, cancelledRevision), agent());

        assertThat(projection).containsEntry("stage", "CANCELLED")
                .containsEntry("stageStatus", "READY")
                .containsEntry("allowedNextTools", List.of("rg.journey.next"));
        assertThatThrownBy(() -> journeys.executeAction("rg.feature.define",
                action(ref, cancelledRevision), agent(), Map::of))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("JOURNEY_ACTION_NOT_ALLOWED"));
    }

    @Test
    void blockedFeatureJourneyRecoversFromCurrentAuthoritativeAssets() {
        registry.upsertFeature(SCOPE, featureWithBinding(""));
        String ref = journeys.start(startRequest("journey-blocked-recovery"), agent())
                .get("journeyRef").toString();
        associate(ref, 1, "rg.feature.define",
                Map.of("featureId", "responsibility.party", "revision", 2));

        assertThat(journeys.next(next(ref, 2), agent()))
                .containsEntry("stage", "WAITING_FEATURE_ENGINEERING")
                .containsEntry("stageStatus", "BLOCKED")
                .extractingByKey("blockingReasons").asList()
                .containsExactly("FEATURE_BINDING_REQUIRED");

        registry.upsertFeature(SCOPE, featureWithBinding("resource:responsibility#$.party"));

        assertThat(journeys.next(next(ref, 2), agent()))
                .containsEntry("stage", "DEFINING_RULES")
                .containsEntry("stageStatus", "READY")
                .containsEntry("blockingReasons", List.of());
    }

    @Test
    void nextDerivesOneProjectionFromOneFrozenAssetReadPoint() {
        registry.upsertFeature(SCOPE, featureWithBinding(""));
        String ref = journeys.start(startRequest("journey-frozen-read"), agent())
                .get("journeyRef").toString();
        associate(ref, 1, "rg.feature.define",
                Map.of("featureId", "responsibility.party", "revision", 2));
        MutationAfterSnapshotRepository drifting = new MutationAfterSnapshotRepository(states,
                () -> registry.upsertFeature(SCOPE,
                        featureWithBinding("resource:responsibility#$.party")));
        BusinessJourneyService frozenJourneys = new BusinessJourneyService(
                drifting, new SolutionEntityRegistry(drifting, mapper), mapper);
        drifting.arm();

        assertThat(frozenJourneys.next(next(ref, 2), agent()))
                .containsEntry("stage", "WAITING_FEATURE_ENGINEERING")
                .containsEntry("stageStatus", "BLOCKED");
        assertThat(frozenJourneys.next(next(ref, 2), agent()))
                .containsEntry("stage", "DEFINING_RULES")
                .containsEntry("stageStatus", "READY");
    }

    private FeatureContract featureWithBinding(String evaluationRef) {
        FeatureContract current = registry.requireFeature(SCOPE, "responsibility.party");
        return new FeatureContract(current.featureRef(), current.output(), current.evaluationKind(),
                current.determinism(), current.inputs(), evaluationRef, current.componentRef(),
                current.promptRef(), current.businessSemantics(), current.businessDefinition());
    }

    private void associate(String ref, long revision, String tool, Map<String, Object> result) {
        journeys.executeAction(tool, action(ref, revision), agent(), () -> result);
    }

    private ObjectNode startRequest(String key) {
        return mapper.createObjectNode().put("intentKind", "CREATE_SOLUTION")
                .put("businessGoal", "处理取消费争议").put("idempotencyKey", key);
    }

    private ObjectNode action(String ref, long revision) {
        return mapper.createObjectNode().put("journeyRef", ref).put("expectedJourneyRevision", revision);
    }

    private ObjectNode next(String ref, long revision) {
        return mapper.createObjectNode().put("journeyRef", ref).put("expectedRevision", revision);
    }

    private static IntegrationRequestContext agent() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "codex", "INTERNAL", "AGENT_TDD_AUTHORING", "corr-agent");
    }

    private static IntegrationRequestContext reviewer() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "reviewer", "INTERNAL", "AGENT_TDD_GOVERNANCE", "corr-review");
    }

    private static final class TestMaterialStore extends BusinessGoldenMaterialStore {
        private final ObjectMapper mapper;
        private final Map<String, JsonNode> payloads = new java.util.LinkedHashMap<>();

        private TestMaterialStore(ObjectMapper mapper) {
            super((com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService) null, mapper);
            this.mapper = mapper;
        }

        @Override
        public JsonNode write(String solutionRef, long solutionRevision, String solutionFingerprint,
                              String caseId, String goldenFingerprint, String proposalFingerprint, JsonNode payload,
                              IntegrationRequestContext caller) {
            payloads.put(goldenFingerprint, payload.deepCopy());
            return mapper.valueToTree(Map.of("fingerprint", goldenFingerprint));
        }

        @Override
        public JsonNode read(JsonNode receiptNode, IntegrationRequestContext caller) {
            return payloads.get(receiptNode.path("fingerprint").asText()).deepCopy();
        }

        @Override
        public JsonNode renew(JsonNode receiptNode, IntegrationRequestContext caller) {
            return receiptNode.deepCopy();
        }
    }

    /** Moves one authoritative asset immediately after the caller's chosen read boundary. */
    private static final class MutationAfterSnapshotRepository implements AgentTddStateRepository {
        private final AgentTddStateRepository delegate;
        private final Runnable mutation;
        private boolean armed;

        private MutationAfterSnapshotRepository(AgentTddStateRepository delegate, Runnable mutation) {
            this.delegate = delegate;
            this.mutation = mutation;
        }

        private void arm() {
            armed = true;
        }

        @Override
        public Optional<AgentTddStoredAsset> find(String scopeKey, String kind, String assetRef) {
            Optional<AgentTddStoredAsset> observed = delegate.find(scopeKey, kind, assetRef);
            if (armed && BusinessJourneyService.JOURNEY.equals(kind)) mutate();
            return observed;
        }

        @Override
        public List<AgentTddStoredAsset> list(String scopeKey, String kind) {
            return delegate.list(scopeKey, kind);
        }

        @Override
        public AssetReadSnapshot readSnapshot(String scopeKey, List<String> kinds) {
            AssetReadSnapshot snapshot = delegate.readSnapshot(scopeKey, kinds);
            if (armed) mutate();
            return snapshot;
        }

        private void mutate() {
            armed = false;
            mutation.run();
        }

        @Override
        public AgentTddStoredAsset save(String scopeKey, String kind, String assetRef, JsonNode data) {
            return delegate.save(scopeKey, kind, assetRef, data);
        }

        @Override
        public AgentTddStoredAsset saveIfRevision(
                String scopeKey, String kind, String assetRef, long expectedRevision, JsonNode data) {
            return delegate.saveIfRevision(scopeKey, kind, assetRef, expectedRevision, data);
        }

        @Override
        public <T> T executeAtomically(Supplier<T> action) {
            return delegate.executeAtomically(action);
        }

        @Override
        public AgentTddStoredAsset lockRevision(
                String scopeKey, String kind, String assetRef, long expectedRevision) {
            return delegate.lockRevision(scopeKey, kind, assetRef, expectedRevision);
        }

        @Override
        public Optional<JsonNode> replay(
                String scopeKey, String operation, String idempotencyKey, String requestFingerprint) {
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
            return delegate.executeOnce(scopeKey, operation, idempotencyKey, requestFingerprint, action);
        }

        @Override
        public ExternalExecutionReservation reserveExternalExecution(
                String scopeKey, String operation, String idempotencyKey, String requestFingerprint) {
            return delegate.reserveExternalExecution(scopeKey, operation, idempotencyKey, requestFingerprint);
        }

        @Override
        public JsonNode completeExternalExecution(
                String scopeKey, String operation, String idempotencyKey,
                String requestFingerprint, JsonNode response) {
            return delegate.completeExternalExecution(
                    scopeKey, operation, idempotencyKey, requestFingerprint, response);
        }
    }
}
