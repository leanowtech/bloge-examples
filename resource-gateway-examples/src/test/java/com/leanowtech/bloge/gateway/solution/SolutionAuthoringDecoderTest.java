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
}
