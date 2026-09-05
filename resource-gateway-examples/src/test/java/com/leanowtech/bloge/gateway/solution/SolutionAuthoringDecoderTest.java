package com.leanowtech.bloge.gateway.solution;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies strict decoding of the canonical four-entity authoring document. */
class SolutionAuthoringDecoderTest {
    private final SolutionAuthoringDecoder decoder = new SolutionAuthoringDecoder();

    @Test
    void decodesACompleteVersionedFourEntityDocument() {
        String source = """
                schemaVersion: bloge.solutionAuthoring.v1
                features:
                  responsibility.party:
                    output: { type: string }
                    evaluationKind: API
                    determinism: DETERMINISTIC
                    inputs: { orderId: string }
                    evaluationRef: resource:party#$.value
                scenarios:
                  scn:root:
                    inputs: [party]
                    hitPolicy: unique
                    rules:
                      - ruleId: R1
                        when: { party: { eq: none } }
                        outlet: { kind: INSTRUCTION, ref: 'ins:uphold' }
                    otherwise: { kind: INSTRUCTION, ref: 'ins:uphold' }
                instructions:
                  ins:uphold:
                    inputs: { orderId: string }
                    output: { result: { type: string }, reasoning: required }
                    businessSemantics: Keep the original fee.
                    effect: READ
                    bindingRef: tool:uphold-v1
                solutions:
                  sol:dispute:
                    problem: Resolve a dispute.
                    inputs: { party: responsibility.party }
                    scenarioTree: { root: 'scn:root' }
                    instructions: ['ins:uphold']
                    golden: 'caseSet:dispute'
                """;

        SolutionAuthoringDecoder.DecodeResult<SolutionDocument> result =
                decoder.decode(source.getBytes(StandardCharsets.UTF_8));

        assertThat(result.successful()).isTrue();
        assertThat(result.value().features()).containsOnlyKeys("responsibility.party");
        assertThat(result.value().scenarios()).containsOnlyKeys("scn:root");
        assertThat(result.value().instructions()).containsOnlyKeys("ins:uphold");
        assertThat(result.value().instructions().get("ins:uphold").businessSemantics())
                .isEqualTo("Keep the original fee.");
        assertThat(result.value().solutions()).containsOnlyKeys("sol:dispute");
    }

    @Test
    void rejectsUnknownContractFieldsWithPayloadFreeDiagnostic() {
        SolutionAuthoringDecoder.DecodeResult<FeatureContract> result = decoder.decodeFeature("""
                private.customer.value:
                  output: { type: string }
                  evaluationKind: API
                  determinism: DETERMINISTIC
                  inputs: {}
                  leakedSecret: should-never-return
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(result.successful()).isFalse();
        assertThat(result.diagnosticCode()).isEqualTo("FEATURE_CONTRACT_UNKNOWN_FIELD")
                .doesNotContain("private", "should-never-return");
    }

    @Test
    void decodesAClosedBusinessCapabilityDisplay() {
        SolutionAuthoringDecoder.DecodeResult<FeatureContract> result = decoder.decodeFeature("""
                feature:cancel-party:
                  display:
                    schemaVersion: rg.businessCapabilityDisplay.v1
                    businessName: 取消责任方
                    description: 判断订单取消的责任主体
                    aliases: [取消归责, 谁导致取消]
                    tags: [取消费]
                    whenToUse: [计算取消费前]
                    whenNotToUse: [交通事故责任认定]
                  output: { type: string }
                  evaluationKind: API
                  determinism: DETERMINISTIC
                  inputs: {}
                  businessSemantics: 取消责任方
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(result.successful()).isTrue();
        assertThat(result.value().display().businessName()).isEqualTo("取消责任方");
        assertThat(result.value().display().aliases()).containsExactly("取消归责", "谁导致取消");
    }

    @Test
    void rejectsUnknownDisplayFieldsWithPayloadFreeDiagnostic() {
        SolutionAuthoringDecoder.DecodeResult<FeatureContract> result = decoder.decodeFeature("""
                feature:private:
                  display:
                    businessName: 取消责任方
                    description: 判断取消责任
                    secretBinding: should-never-return
                  output: { type: string }
                  evaluationKind: API
                  determinism: DETERMINISTIC
                  inputs: {}
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(result.successful()).isFalse();
        assertThat(result.diagnosticCode()).isEqualTo("FEATURE_DISPLAY_INVALID")
                .doesNotContain("private", "should-never-return");
    }

    @Test
    void requiresDisplayForANewStructuredBusinessDefinition() {
        SolutionAuthoringDecoder.DecodeResult<FeatureContract> result = decoder.decodeFeature("""
                feature:cancel-party:
                  output: { type: string }
                  evaluationKind: API
                  determinism: DETERMINISTIC
                  inputs: {}
                  businessDefinition:
                    semanticKey: ride.cancel.party
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(result.successful()).isFalse();
        assertThat(result.diagnosticCode()).isEqualTo("FEATURE_DISPLAY_INVALID");
    }
}
