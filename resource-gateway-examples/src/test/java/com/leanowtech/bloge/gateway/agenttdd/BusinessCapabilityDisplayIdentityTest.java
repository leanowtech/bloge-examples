package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.solution.BusinessCapabilityDisplay;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionAuthoringDecoder;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionExecutableSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that discovery copy cannot invalidate approved or executable Solution evidence. */
class BusinessCapabilityDisplayIdentityTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SolutionAuthoringDecoder decoder = new SolutionAuthoringDecoder();

    @Test
    void revisesDisplayIndependentlyWithoutChangingContractOrImplementationIdentity() {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
        String scope = "tenant|org|project|test|sg";
        FeatureContract feature = decoder.decodeFeature(bytes(featureYaml())).value();
        ScenarioContract scenario = decoder.decodeScenario(bytes(scenarioYaml())).value();
        InstructionContract instruction = decoder.decodeInstruction(bytes(instructionYaml())).value();
        SolutionContract solution = decoder.decodeSolution(bytes(solutionYaml())).value();

        SolutionEntityRegistry.RegisteredEntity featureV1 = registry.upsertFeature(scope, feature);
        SolutionEntityRegistry.RegisteredEntity scenarioV1 = registry.upsertScenario(scope, scenario);
        SolutionEntityRegistry.RegisteredEntity instructionV1 = registry.upsertInstruction(scope, instruction);
        SolutionEntityRegistry.RegisteredEntity solutionV1 = registry.upsertSolution(
                scope, solution, false, null, "sha256:receipt");
        String implementationV1 = SolutionImplementationIdentity.fingerprint(
                registry, mapper, scope, solution);
        SolutionExecutableSnapshot approvedClosure = registry.freezeExecutable(scope, solution.solutionRef());
        long displayRevisionV1 = registry.requireDisplay(
                scope, "FEATURE", feature.featureRef()).revision();

        BusinessCapabilityDisplay revised = new BusinessCapabilityDisplay(
                BusinessCapabilityDisplay.SCHEMA_VERSION, "取消归责判定",
                "判断乘客、司机或平台谁应承担取消责任",
                List.of("谁导致取消", "取消责任方"), List.of("取消费"),
                List.of("计算取消费前"), List.of("交通事故责任认定"));
        SolutionEntityRegistry.RegisteredEntity featureV2 = registry.upsertFeature(
                scope, feature.withDisplay(revised));
        SolutionEntityRegistry.RegisteredEntity scenarioV2 = registry.upsertScenario(
                scope, scenario.withDisplay(revised));
        SolutionEntityRegistry.RegisteredEntity instructionV2 = registry.upsertInstruction(
                scope, instruction.withDisplay(revised));
        SolutionEntityRegistry.RegisteredEntity solutionV2 = registry.upsertSolution(
                scope, solution.withDisplay(revised), false, null, "sha256:receipt");
        String implementationV2 = SolutionImplementationIdentity.fingerprint(
                registry, mapper, scope, solutionV2Contract(registry, scope, solution));

        assertThat(featureV2.revision()).isEqualTo(featureV1.revision());
        assertThat(scenarioV2.revision()).isEqualTo(scenarioV1.revision());
        assertThat(instructionV2.revision()).isEqualTo(instructionV1.revision());
        assertThat(solutionV2.revision()).isEqualTo(solutionV1.revision());
        assertThat(featureV2.contractFingerprint()).isEqualTo(featureV1.contractFingerprint());
        assertThat(scenarioV2.contractFingerprint()).isEqualTo(scenarioV1.contractFingerprint());
        assertThat(instructionV2.contractFingerprint()).isEqualTo(instructionV1.contractFingerprint());
        assertThat(solutionV2.contractFingerprint()).isEqualTo(solutionV1.contractFingerprint());
        assertThat(implementationV2).isEqualTo(implementationV1);
        assertThat(approvedClosure.isCurrent(states, scope)).isTrue();
        assertThat(registry.requireDisplay(scope, "FEATURE", feature.featureRef()).revision())
                .isEqualTo(displayRevisionV1 + 1);
        assertThat(registry.requireFeature(scope, feature.featureRef()).display())
                .isEqualTo(revised);
        assertThat(registry.requireScenario(scope, scenario.scenarioRef()).display())
                .isEqualTo(revised);
        assertThat(registry.requireInstruction(scope, instruction.instructionRef()).display())
                .isEqualTo(revised);
        assertThat(registry.requireSolution(scope, solution.solutionRef()).display())
                .isEqualTo(revised);
    }

    @Test
    void keepsLegacyProjectionReadOnlyAndIgnoresCorruptDisplayDuringExecutionReads() {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
        String scope = "tenant|org|project|test|sg";
        FeatureContract legacy = new FeatureContract("feature:legacy", mapper.createObjectNode()
                .put("type", "string"), FeatureContract.EvaluationKind.API,
                FeatureContract.Determinism.DETERMINISTIC, mapper.createObjectNode(),
                "resource:test", "", "", "Legacy fact");

        registry.upsertFeature(scope, legacy);

        assertThat(states.list(scope, SolutionEntityRegistry.CAPABILITY_DISPLAY)).isEmpty();
        assertThat(registry.requireFeature(scope, legacy.featureRef()).display().legacyProjection()).isTrue();

        FeatureContract explicit = decoder.decodeFeature(bytes(featureYaml())).value();
        registry.upsertFeature(scope, explicit);
        AgentTddStoredAsset stored = states.find(scope, SolutionEntityRegistry.CAPABILITY_DISPLAY,
                SolutionEntityRegistry.displayAssetRef("FEATURE", explicit.featureRef())).orElseThrow();
        com.fasterxml.jackson.databind.node.ObjectNode corrupt = stored.data().deepCopy();
        corrupt.put("displayFingerprint", "sha256:corrupt");
        states.save(scope, SolutionEntityRegistry.CAPABILITY_DISPLAY, stored.assetRef(), corrupt);

        assertThat(registry.requireFeature(scope, explicit.featureRef()).featureRef())
                .isEqualTo(explicit.featureRef());
        assertThatThrownBy(() -> registry.requireDisplay(scope, "FEATURE", explicit.featureRef()))
                .isInstanceOf(SolutionEntityRegistry.EntityUnavailableException.class);
    }

    private static SolutionContract solutionV2Contract(
            SolutionEntityRegistry registry, String scope, SolutionContract original) {
        return registry.requireSolution(scope, original.solutionRef());
    }

    private static byte[] bytes(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }

    private static String display() {
        return """
                  display:
                    schemaVersion: rg.businessCapabilityDisplay.v1
                    businessName: 取消责任方
                    description: 判断订单取消的责任主体
                    aliases: [取消归责]
                """;
    }

    private static String featureYaml() {
        return """
                feature:party:
                """ + display() + """
                  output: { type: string }
                  evaluationKind: API
                  determinism: DETERMINISTIC
                  inputs: {}
                  evaluationRef: resource:test
                  businessSemantics: 取消责任方
                """;
    }

    private static String scenarioYaml() {
        return """
                scn:root:
                """ + display() + """
                  inputs: [party]
                  hitPolicy: unique
                  rules:
                    - ruleId: R1
                      when: { party: { eq: passenger } }
                      outlet: { kind: INSTRUCTION, ref: 'ins:uphold' }
                  otherwise: { kind: INSTRUCTION, ref: 'ins:uphold' }
                """;
    }

    private static String instructionYaml() {
        return """
                ins:uphold:
                """ + display() + """
                  inputs: { party: string }
                  output: { result: { type: string }, reasoning: required }
                  effect: READ
                  bindingRef: tool:uphold
                  businessSemantics: 维持取消费
                """;
    }

    private static String solutionYaml() {
        return """
                sol:cancel:
                """ + display() + """
                  problem: 处理取消费争议
                  inputs: { party: 'feature:party' }
                  scenarioTree: { root: 'scn:root' }
                  instructions: ['ins:uphold']
                  golden: 'caseSet:cancel'
                """;
    }
}
