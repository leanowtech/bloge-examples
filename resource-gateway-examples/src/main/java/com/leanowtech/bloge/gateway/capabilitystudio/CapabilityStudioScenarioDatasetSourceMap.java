package com.leanowtech.bloge.gateway.capabilitystudio;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;

import java.util.List;

/** Payload-free source coordinates retained alongside a compiled ScenarioDraftSet. */
public record CapabilityStudioScenarioDatasetSourceMap(
        CapabilityStudioScenarioDatasetProjector.ExactRef datasetRef,
        CapabilityStudioScenarioDatasetProjector.ExactRef targetRef,
        String contractFingerprint,
        List<CaseSource> cases) {

    public CapabilityStudioScenarioDatasetSourceMap {
        contractFingerprint = contractFingerprint == null ? "" : contractFingerprint.trim();
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /** Source coordinates for one compiled ScenarioDraft. */
    public record CaseSource(
            String scenarioId,
            String originalCategory,
            ScenarioDraftSet.CaseType compiledCaseType,
            CapabilityStudioScenarioDatasetProjector.ExactRef caseRef,
            CapabilityStudioScenarioDatasetProjector.ExactRef sourceRef,
            CapabilityStudioScenarioDatasetProjector.ExactRef oracleRef,
            List<CapabilityStudioScenarioDatasetProjector.ExactRef> contractRefs,
            List<BehaviorSource> behaviors,
            List<ExpectationSource> expectations,
            List<String> assertionIds) {

        public CaseSource {
            scenarioId = scenarioId == null ? "" : scenarioId.trim();
            originalCategory = originalCategory == null ? "" : originalCategory.trim();
            contractRefs = contractRefs == null ? List.of() : List.copyOf(contractRefs);
            behaviors = behaviors == null ? List.of() : List.copyOf(behaviors);
            expectations = expectations == null ? List.of() : List.copyOf(expectations);
            assertionIds = assertionIds == null ? List.of() : List.copyOf(assertionIds);
        }
    }

    /** Payload-free mapping between a compiled dependency and its Dataset behavior profile. */
    public record BehaviorSource(
            String ruleId,
            CapabilityStudioScenarioDatasetProjector.ExactRef behaviorRef,
            CapabilityStudioScenarioDatasetProjector.ExactRef dependencyRef,
            String behavior) {
        public BehaviorSource {
            ruleId = ruleId == null ? "" : ruleId.trim();
            behavior = behavior == null ? "" : behavior.trim();
        }
    }

    /** Payload-free business obligation retained without lowering it into a fixture rule. */
    public record ExpectationSource(
            CapabilityStudioScenarioDatasetProjector.ExactRef behaviorRef,
            CapabilityStudioScenarioDatasetProjector.ExactRef dependencyRef,
            String behavior) {
        public ExpectationSource {
            behavior = behavior == null ? "" : behavior.trim();
        }
    }
}
