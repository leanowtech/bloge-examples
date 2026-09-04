package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the complete collect-token-invoke contract for platform and interactive Features. */
class SolutionInvocationServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(
            new InMemoryAgentTddStateRepository(), mapper);
    private final FeatureValueTokenService tokens = new FeatureValueTokenService(
            mapper, new InMemoryFeatureTokenKeyProvider("k1", Map.of("k1", secret())),
            Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private final IntegrationRequestContext identity = new IntegrationRequestContext(
            "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "agent-a", "",
            "AGENT_TDD_EXECUTION", "corr-a");

    @BeforeEach
    void defineSolution() {
        registry.upsertFeature(SCOPE, feature("responsibility.party", FeatureContract.EvaluationKind.API,
                FeatureContract.Determinism.DETERMINISTIC, "resource:party", ""));
        registry.upsertFeature(SCOPE, feature("dispute.orderSelected",
                FeatureContract.EvaluationKind.USER_COMPONENT,
                FeatureContract.Determinism.INTERACTIVE, "", "order-picker-v1"));
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:uphold", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "object"), "reasoning", "required")),
                InstructionContract.Effect.READ, "operator:uphold", null));
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1", mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:uphold", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
        registry.upsertSolution(SCOPE, new SolutionContract(
                "sol:cancel", "Resolve cancellation dispute.",
                Map.of("party", "responsibility.party", "orderId", "dispute.orderSelected"),
                "scn:root", List.of("ins:uphold"), "caseSet:cancel"), false);
    }

    @Test
    void exposesCollectionPlanAndInvokesOnlyAfterExactTokenVerification() {
        FeatureEvaluationService evaluations = new FeatureEvaluationService(registry,
                (feature, inputs, context) -> mapper.valueToTree("none"), tokens);
        FeatureEvaluationService.EvaluationResult party = evaluations.evaluate(
                SCOPE, "responsibility.party", mapper.valueToTree(Map.of("orderId", "O-1")), identity);
        SolutionInvocationService service = service();

        assertThat(service.contract(SCOPE, "sol:cancel").inputs())
                .extracting(SolutionInvocationService.InputView::evaluationKind)
                .containsExactlyInAnyOrder("API", "USER_COMPONENT");
        ObjectNode supplied = mapper.createObjectNode();
        supplied.set("party", mapper.valueToTree(Map.of(
                "value", party.value(), "inputs", Map.of("orderId", "O-1"),
                "evaluationToken", party.evaluationToken())));
        supplied.set("orderId", mapper.valueToTree(Map.of("value", "O-1", "source", "USER")));

        SolutionInvocationService.InvocationResult result = service.invoke(SCOPE, "sol:cancel", supplied);

        assertThat(result.result()).isEqualTo(Map.of("decision", "UPHELD"));
        assertThat(result.reasoning()).isEqualTo("rule R1");
        assertThat(result.rulePath()).containsExactly("R1");
        assertThat(result.verifiedFeatureCount()).isEqualTo(1);
    }

    @Test
    void refusesInteractiveEvaluationAndMissingOrReboundPlatformProofs() {
        FeatureEvaluationService evaluations = new FeatureEvaluationService(registry,
                (feature, inputs, context) -> mapper.valueToTree("none"), tokens);
        assertThatThrownBy(() -> evaluations.evaluate(
                SCOPE, "dispute.orderSelected", mapper.createObjectNode(), identity))
                .isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("USE_NATIVE_INTERACTION");

        ObjectNode supplied = mapper.createObjectNode();
        supplied.set("party", mapper.valueToTree(Map.of(
                "value", "none", "inputs", Map.of("orderId", "O-1"), "evaluationToken", "missing")));
        supplied.set("orderId", mapper.valueToTree(Map.of("value", "O-1", "source", "USER")));
        assertThatThrownBy(() -> service().invoke(SCOPE, "sol:cancel", supplied))
                .isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("FEATURE_TOKEN_INVALID");

        FeatureEvaluationService invalidBackend = new FeatureEvaluationService(registry,
                (feature, inputs, context) -> mapper.valueToTree(42), tokens);
        assertThatThrownBy(() -> invalidBackend.evaluate(
                SCOPE, "responsibility.party", mapper.valueToTree(Map.of("orderId", "O-1")), identity))
                .isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("FEATURE_OUTPUT_INVALID");
        assertThatThrownBy(() -> evaluations.evaluate(SCOPE, "responsibility.party",
                mapper.valueToTree(Map.of("orderId", "O-1", "undeclared", true)), identity))
                .isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("FEATURE_INPUT_INVALID");
    }

    private SolutionInvocationService service() {
        InstructionDispatchChannel channel = (instruction, values, context) ->
                Map.of("result", Map.of("decision", "UPHELD"), "reasoning", "rule R1");
        return new SolutionInvocationService(registry, tokens,
                new SolutionExecutionService(registry, mapper, channel), mapper);
    }

    private FeatureContract feature(String ref, FeatureContract.EvaluationKind kind,
                                    FeatureContract.Determinism determinism,
                                    String evaluationRef, String componentRef) {
        return new FeatureContract(ref, mapper.valueToTree(Map.of("type", "string")), kind, determinism,
                mapper.valueToTree(Map.of("orderId", "string")), evaluationRef, componentRef, "");
    }

    private static byte[] secret() {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) 7);
        return value;
    }
}
