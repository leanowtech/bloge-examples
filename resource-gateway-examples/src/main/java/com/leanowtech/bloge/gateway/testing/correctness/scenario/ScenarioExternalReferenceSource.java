package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

/** Exact resolver for Contract, Fixture, generator, replay material, and other source refs. */
@FunctionalInterface
public interface ScenarioExternalReferenceSource {

    boolean referenceIsCurrent(
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef reference);

    static ScenarioExternalReferenceSource denyAll() {
        return (scope, target, reference) -> false;
    }
}
