package com.leanowtech.bloge.gateway.capabilitystudio;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;

import java.util.List;
import java.util.Objects;

/**
 * Controlled, package-private runtime material for Dataset compilation.
 *
 * <p>The public Dataset projection deliberately contains no payload. This type is kept inside
 * Capability Studio so controllers cannot accidentally turn fixture material into a metadata
 * endpoint.</p>
 */
final class CapabilityStudioScenarioDatasetMaterial {

    private CapabilityStudioScenarioDatasetMaterial() {
    }

    record CaseMaterial(
            CapabilityStudioScenarioDatasetProjector.ExactRef caseRef,
            CapabilityStudioScenarioDatasetProjector.ExactRef sourceRef,
            CapabilityStudioScenarioDatasetProjector.ExactRef oracleRef,
            ScenarioDraftSet.Given given,
            List<DependencyMaterial> dependencies,
            List<ScenarioDraftSet.AssertionDraft> assertions) {
        CaseMaterial {
            Objects.requireNonNull(caseRef, "caseRef");
            Objects.requireNonNull(sourceRef, "sourceRef");
            Objects.requireNonNull(oracleRef, "oracleRef");
            Objects.requireNonNull(given, "given");
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
        }
    }

    record DependencyMaterial(
            CapabilityStudioScenarioDatasetProjector.ExactRef behaviorRef,
            CapabilityStudioScenarioDatasetProjector.ExactRef dependencyRef,
            ScenarioDraftSet.DependencySelector selector,
            ScenarioDraftSet.DependencyBehavior behavior,
            ScenarioDraftSet.Consumption consumption,
            ScenarioDraftSet.SchemaCheck schemaCheck) {
        DependencyMaterial {
            Objects.requireNonNull(behaviorRef, "behaviorRef");
            Objects.requireNonNull(dependencyRef, "dependencyRef");
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(behavior, "behavior");
            Objects.requireNonNull(consumption, "consumption");
            Objects.requireNonNull(schemaCheck, "schemaCheck");
        }
    }
}
