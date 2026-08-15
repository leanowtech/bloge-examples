package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;

import java.util.Objects;
import java.util.Set;

/** Scenario v2 reverse-index adapter for exact canonical obligation fulfillment. */
public final class ScenarioV2CoverageFulfillmentSource implements CoverageFulfillmentSource {

    private final ScenarioDraftSetV2Repository scenarios;

    public ScenarioV2CoverageFulfillmentSource(ScenarioDraftSetV2Repository scenarios) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
    }

    @Override
    public Set<String> fulfilledObligationIds(
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef inventoryRef
    ) {
        return scenarios.fulfilledObligationIds(scope, target, inventoryRef);
    }
}
