package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.solution.capability.BusinessContractMatcher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies strict and comparable business semantics for Scenario, Instruction and Solution. */
class BusinessSemanticContractFamilyTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SolutionAuthoringDecoder decoder = new SolutionAuthoringDecoder();

    @Test
    void decodesCompleteSemanticProfilesAndIncludesThemInContractIdentity() {
        ScenarioContract scenario = decoder.decodeScenario(bytes(scenarioYaml())).value();
        InstructionContract instruction = decoder.decodeInstruction(bytes(instructionYaml())).value();
        SolutionContract solution = decoder.decodeSolution(bytes(solutionYaml())).value();

        assertThat(scenario.businessDefinition().semanticKey()).isEqualTo("ride.cancel.decision");
        assertThat(instruction.businessDefinition().reasoningPolicy()).isEqualTo("REQUIRED");
        assertThat(solution.businessDefinition().dispositionSemanticKeys())
                .containsExactly("ride.cancel.uphold");
        assertThat(scenario.display().businessName()).isEqualTo("取消费争议判定");
        assertThat(instruction.display().businessName()).isEqualTo("维持取消费");
        assertThat(solution.display().businessName()).isEqualTo("取消费争议处理");
        assertThat(scenario.contractIdentity()).containsKey("businessDefinition");
        assertThat(instruction.contractIdentity()).containsKey("businessDefinition");
        assertThat(solution.contractIdentity()).containsKey("businessDefinition");
    }

    @Test
    void projectsLegacyContractsAsIncompleteInsteadOfInventingSemanticIdentity() {
        ScenarioContract scenario = decoder.decodeScenario(bytes(scenarioYaml()
                .replaceAll("(?s)\\n  businessDefinition:.*", ""))).value();
        InstructionContract instruction = decoder.decodeInstruction(bytes(instructionYaml()
                .replaceAll("(?s)\\n  businessDefinition:.*", ""))).value();
        SolutionContract solution = decoder.decodeSolution(bytes(solutionYaml()
                .replaceAll("(?s)\\n  businessDefinition:.*", ""))).value();

        assertThat(scenario.businessDefinition().incompleteLegacyProjection()).isTrue();
        assertThat(instruction.businessDefinition().incompleteLegacyProjection()).isTrue();
        assertThat(solution.businessDefinition().incompleteLegacyProjection()).isTrue();
    }

    @Test
    void matcherUsesEveryEntitySpecificClosedDimension() {
        JsonNode scenario = mapper.valueToTree(decoder.decodeScenario(bytes(scenarioYaml()))
                .value().businessDefinition());
        JsonNode changedScenario = scenario.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changedScenario)
                .put("otherwisePolicy", "SILENT_DEFAULT");
        JsonNode instruction = mapper.valueToTree(decoder.decodeInstruction(bytes(instructionYaml()))
                .value().businessDefinition());
        JsonNode changedInstruction = instruction.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changedInstruction)
                .put("failurePolicy", "IGNORE");
        JsonNode solution = mapper.valueToTree(decoder.decodeSolution(bytes(solutionYaml()))
                .value().businessDefinition());
        JsonNode changedSolution = solution.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changedSolution)
                .put("runtimeUse", "UNCONTROLLED");

        BusinessContractMatcher matcher = new BusinessContractMatcher();

        assertThat(matcher.match(scenario, scenario).type()).isEqualTo(BusinessContractMatcher.MatchType.EXACT);
        assertThat(matcher.match(changedScenario, scenario).conflicts()).contains("otherwisePolicy");
        assertThat(matcher.match(instruction, instruction).type())
                .isEqualTo(BusinessContractMatcher.MatchType.EXACT);
        assertThat(matcher.match(changedInstruction, instruction).conflicts()).contains("failurePolicy");
        assertThat(matcher.match(solution, solution).type()).isEqualTo(BusinessContractMatcher.MatchType.EXACT);
        assertThat(matcher.match(changedSolution, solution).conflicts()).contains("runtimeUse");
    }

    private static byte[] bytes(String source) {
        return source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String scenarioYaml() {
        return """
                scn:cancel:
                  display:
                    businessName: 取消费争议判定
                    description: 根据责任事实选择处置
                  inputs: [party]
                  hitPolicy: unique
                  rules:
                    - ruleId: R1
                      when: { party: { eq: passenger } }
                      outlet: { kind: INSTRUCTION, ref: 'ins:uphold', bind: { party: party } }
                  otherwise: { kind: TERMINAL, terminalKind: ESCALATE }
                  businessDefinition:
                    semanticKey: ride.cancel.decision
                    intent: 判定取消费争议处置
                    domain: ride-cancellation
                    businessObject: ride-order
                    inputFactKeys: [ride.cancel.party]
                    decisionPolicy: UNIQUE
                    outletSemanticKeys: [ride.cancel.uphold]
                    otherwisePolicy: ESCALATE
                """;
    }

    private static String instructionYaml() {
        return """
                ins:uphold:
                  display:
                    businessName: 维持取消费
                    description: 维持乘客取消费并解释原因
                  inputs: { party: string }
                  output:
                    result: { type: { fields: { disposition: string } } }
                    reasoning: required
                  effect: READ
                  businessSemantics: 维持取消费
                  businessDefinition:
                    semanticKey: ride.cancel.uphold
                    intent: 维持取消费并解释原因
                    domain: ride-cancellation
                    businessObject: ride-order
                    requiredFactKeys: [ride.cancel.party]
                    resultDomain: { type: object, disposition: string }
                    reasoningPolicy: REQUIRED
                    effect: READ
                    failurePolicy: ESCALATE
                    writeGovernanceClass: NONE
                """;
    }

    private static String solutionYaml() {
        return """
                sol:cancel:
                  display:
                    businessName: 取消费争议处理
                    description: 根据取消责任处理取消费争议
                  problem: 处理取消费争议
                  inputs: { party: feature:party }
                  scenarioTree: { root: 'scn:cancel' }
                  instructions: ['ins:uphold']
                  golden: 'caseSet:cancel'
                  businessDefinition:
                    semanticKey: ride.cancel.solution
                    intent: 完成取消费争议处置
                    domain: ride-cancellation
                    businessObject: ride-order
                    problemClass: CANCELLATION_FEE_DISPUTE
                    requiredFactKeys: [ride.cancel.party]
                    scenarioSemanticKey: ride.cancel.decision
                    dispositionSemanticKeys: [ride.cancel.uphold]
                    runtimeUse: GOVERNED_DECISION
                """;
    }
}
