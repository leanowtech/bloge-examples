package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.util.Set;

/** Read-side authority for obligation ids fulfilled by current canonical Cases. */
@FunctionalInterface
public interface CoverageFulfillmentSource {

    Set<String> fulfilledObligationIds(
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef inventoryRef);
}
