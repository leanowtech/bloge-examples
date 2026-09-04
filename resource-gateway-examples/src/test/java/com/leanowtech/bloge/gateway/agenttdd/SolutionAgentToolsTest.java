package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InMemoryFeatureTokenKeyProvider;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the public four-entity solution authoring application boundary. */
class SolutionAgentToolsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionAgentTools tools = new SolutionAgentTools(states, mapper);

    @Test
    void definesTypedFeatureAsScopedCanonicalAsset() {
        ObjectNode arguments = mapper.createObjectNode()
                .put("featureYaml", """
                        responsibility.party:
                          output:
                            type:
                              enum: [passenger, driver, platform, none]
                          evaluationKind: API
                          determinism: DETERMINISTIC
                          inputs:
                            orderId: string
                          evaluationRef: ride-responsibility-service.decide#$.party
                        """)
                .put("idempotencyKey", "feature-party-v1");

        Map<String, Object> first = tools.defineFeature(arguments, identity("project-a"));
        Map<String, Object> replay = tools.defineFeature(arguments, identity("project-a"));
        Map<String, Object> otherProject = tools.defineFeature(arguments, identity("project-b"));

        assertThat(first).containsEntry("featureId", "responsibility.party")
                .containsEntry("evaluationKind", "API")
                .containsEntry("determinism", "DETERMINISTIC")
                .containsEntry("speccing", false)
                .containsEntry("revision", 1L);
        assertThat(first.get("contractFingerprint").toString()).startsWith("sha256:");
        assertThat(replay).isEqualTo(first);
        assertThat(otherProject).containsEntry("revision", 1L)
                .containsEntry("contractFingerprint", first.get("contractFingerprint"));
    }

    @Test
    void definesScenarioAsACompleteUniqueDecisionTable() {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("scenarioYaml", """
                        scn:cancel-dispute:
                          inputs: [party, withinFree, abuse]
                          hitPolicy: unique
                          rules:
                            - ruleId: R1
                              when:
                                party: { eq: none }
                                withinFree: { eq: true }
                                abuse: { ne: confirmed }
                              outlet:
                                kind: INSTRUCTION
                                ref: ins:refund-waive-full
                                bind: { orderId: orderId, feeCharged: feeCharged }
                            - ruleId: R4
                              when:
                                party: { eq: driver }
                                abuse: { ne: confirmed }
                              outlet:
                                kind: SUB_SCENARIO
                                ref: scn:driver-liability
                                bind: { party: party, abuse: abuse }
                          otherwise:
                            kind: INSTRUCTION
                            ref: ins:escalate-human-ticket
                            bind: { orderId: orderId }
                        """);
        arguments.putArray("libraryRefs").add("ride-cancel");
        arguments.put("idempotencyKey", "scenario-cancel-v1");

        Map<String, Object> result = tools.defineScenario(arguments, identity("project-a"));

        assertThat(result).containsEntry("scenarioId", "scn:cancel-dispute")
                .containsEntry("revision", 1L)
                .containsEntry("speccing", false);
        Map<?, ?> tree = (Map<?, ?>) result.get("tree");
        assertThat(tree.get("acyclic")).isEqualTo(true);
        assertThat(tree.get("maxDepth")).isEqualTo(2);
        Map<?, ?> ruleMatrix = (Map<?, ?>) result.get("ruleMatrix");
        assertThat(ruleMatrix.keySet().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("conditions", "rules", "otherwise");
    }

    @Test
    void definesDesignOnlyWriteInstructionWithReconciliationContract() {
        ObjectNode arguments = mapper.createObjectNode()
                .put("instructionYaml", """
                        ins:refund-waive-full:
                          inputs: { orderId: string, feeCharged: number }
                          output:
                            result:
                              type:
                                fields:
                                  decision: { enum: [WAIVED] }
                                  waiveAmount: number
                            reasoning: required
                          effect: WRITE
                          writeGovernance:
                            downstreamSystem: refund-service
                            reconciliationKey: orderId
                            reconciliationAdapterRef: recon:refund-v1
                        """)
                .put("idempotencyKey", "instruction-refund-v1");

        Map<String, Object> result = tools.defineInstruction(arguments, identity("project-a"));

        assertThat(result).containsEntry("instructionId", "ins:refund-waive-full")
                .containsEntry("effect", "WRITE")
                .containsEntry("reasoningRequired", true)
                .containsEntry("speccing", true)
                .containsEntry("revision", 1L);
        Map<?, ?> governance = (Map<?, ?>) result.get("writeGovernance");
        assertThat(governance.get("downstreamSystem")).isEqualTo("refund-service");
        assertThat(governance.get("reconciliationKey")).isEqualTo("orderId");
        assertThat(governance.get("reconciliationAdapterRef")).isEqualTo("recon:refund-v1");
    }

    @Test
    void composesPureSolutionOnlyFromResolvableFourEntityContracts() {
        IntegrationRequestContext identity = identity("project-a");
        defineStringFeature("responsibility.party", "feature-party-compose", identity);
        defineStringFeature("cancel.withinFree", "feature-free-compose", identity);
        tools.defineInstruction(mapper.createObjectNode()
                .put("instructionYaml", """
                        ins:uphold:
                          inputs: { orderId: string }
                          output:
                            result: { type: { fields: { decision: { enum: [UPHELD] } } } }
                            reasoning: required
                          effect: READ
                          bindingRef: tool:uphold-v1
                        """)
                .put("idempotencyKey", "instruction-uphold-compose"), identity);
        ObjectNode scenario = mapper.createObjectNode();
        scenario.put("scenarioYaml", """
                scn:root:
                  inputs: [party, withinFree]
                  hitPolicy: unique
                  rules:
                    - ruleId: R1
                      when: { party: { eq: none } }
                      outlet: { kind: INSTRUCTION, ref: 'ins:uphold', bind: { orderId: orderId } }
                  otherwise: { kind: INSTRUCTION, ref: 'ins:uphold', bind: { orderId: orderId } }
                """);
        scenario.putArray("libraryRefs").add("ride-cancel");
        scenario.put("idempotencyKey", "scenario-root-compose");
        tools.defineScenario(scenario, identity);
        ObjectNode arguments = mapper.createObjectNode()
                .put("solutionYaml", """
                        sol:cancel-dispute:
                          problem: Resolve a cancellation-fee dispute consistently.
                          inputs:
                            party: responsibility.party
                            withinFree: cancel.withinFree
                          scenarioTree: { root: 'scn:root' }
                          instructions: ['ins:uphold']
                          golden: 'caseSet:cancel-dispute'
                        """)
                .put("authoringContextFingerprint", "sha256:ctx-1")
                .put("idempotencyKey", "solution-cancel-v1");

        Map<String, Object> result = tools.composeSolution(arguments, identity);

        assertThat(result).containsEntry("solutionRef", "sol:cancel-dispute")
                .containsEntry("scenarioTreeValid", true)
                .containsEntry("speccing", false)
                .containsEntry("authoringContextFingerprint", "sha256:ctx-1")
                .containsEntry("revision", 1L);
        assertThat(((Map<?, ?>) result.get("inputContract")).keySet().stream()
                .map(Object::toString).toList()).containsExactlyInAnyOrder("party", "withinFree");
        assertThat(((Map<?, ?>) result.get("pureFunctionProjection")).get("pure")).isEqualTo(true);
    }

    @Test
    void rejectsCrossProjectSolutionReferencesWithoutDisclosingTheirNames() {
        defineStringFeature("private.customer.segment", "feature-private-v1", identity("project-a"));
        ObjectNode arguments = mapper.createObjectNode()
                .put("solutionYaml", """
                        sol:cross-project:
                          problem: Must not resolve another project's facts.
                          inputs: { segment: private.customer.segment }
                          scenarioTree: { root: 'scn:private' }
                          instructions: ['ins:private']
                          golden: 'caseSet:private'
                        """)
                .put("authoringContextFingerprint", "sha256:ctx-private")
                .put("idempotencyKey", "solution-cross-project-v1");

        assertThatThrownBy(() -> tools.composeSolution(arguments, identity("project-b")))
                .isInstanceOf(AgentTddToolException.class)
                .hasMessage("Referenced solution entity is unavailable.")
                .hasMessageNotContaining("private.customer.segment")
                .hasMessageNotContaining("project-a");
    }

    @Test
    void exposesFeatureEvaluationContractAndTrustedSolutionInvocation() {
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 9);
        SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
        String scope = "tenant-a|org-a|project-a|test|sg";
        registry.upsertFeature(scope, new FeatureContract(
                "responsibility.party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:party", "", ""));
        registry.upsertFeature(scope, new FeatureContract(
                "dispute.orderSelected", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.USER_COMPONENT, FeatureContract.Determinism.INTERACTIVE,
                mapper.createObjectNode(), "", "order-picker-v1", ""));
        registry.upsertInstruction(scope, new InstructionContract(
                "ins:uphold", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "object"), "reasoning", "required")),
                InstructionContract.Effect.READ, "operator:uphold", null));
        registry.upsertScenario(scope, new ScenarioContract(
                "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1", mapper.valueToTree(Map.of()),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:uphold", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
        registry.upsertSolution(scope, new SolutionContract(
                "sol:cancel", "Resolve cancellation dispute.",
                Map.of("party", "responsibility.party", "orderId", "dispute.orderSelected"),
                "scn:root", List.of("ins:uphold"), "caseSet:cancel"), false);
        SolutionAgentTools runtime = new SolutionAgentTools(states, mapper, null,
                (feature, inputs, context) -> mapper.valueToTree("none"),
                (instruction, values, context) -> Map.of(
                        "result", Map.of("decision", "UPHELD"), "reasoning", "rule R1"),
                new InMemoryFeatureTokenKeyProvider("k1", Map.of("k1", secret)));
        IntegrationRequestContext identity = identity("project-a");

        Map<String, Object> evaluated = runtime.evaluateFeature(mapper.valueToTree(Map.of(
                "featureRef", "responsibility.party", "inputs", Map.of("orderId", "O-1"))), identity);
        Map<String, Object> contract = runtime.getSolutionContract(
                mapper.valueToTree(Map.of("solutionRef", "sol:cancel")), identity);
        Map<String, Object> invoked = runtime.invokeSolution(mapper.valueToTree(Map.of(
                "solutionRef", "sol:cancel", "inputs", Map.of(
                        "party", Map.of("value", "none", "inputs", Map.of("orderId", "O-1"),
                                "evaluationToken", evaluated.get("evaluationToken")),
                        "orderId", Map.of("value", "O-1", "source", "USER")))), identity);

        assertThat(evaluated).containsEntry("evaluationKind", "API");
        assertThat(evaluated.get("evaluationToken").toString().split("\\.")).hasSize(3);
        assertThat((List<?>) contract.get("inputs")).hasSize(2);
        assertThat(invoked).containsEntry("reasoning", "rule R1")
                .containsEntry("verifiedFeatureCount", 1);
    }

    private void defineStringFeature(
            String featureRef, String idempotencyKey, IntegrationRequestContext identity) {
        tools.defineFeature(mapper.createObjectNode()
                .put("featureYaml", "%s: { output: { type: string }, evaluationKind: API, "
                        .formatted(featureRef)
                        + "determinism: DETERMINISTIC, inputs: { orderId: string }, "
                        + "evaluationRef: resource:test#$.value }")
                .put("idempotencyKey", idempotencyKey), identity);
    }

    private static IntegrationRequestContext identity(String projectId) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", projectId, "test", "sg", "WORKLOAD", "agent-1",
                "", "AGENT_TDD_AUTHORING", "corr-1");
    }
}
