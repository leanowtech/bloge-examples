package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

/** Authorization-aware verifier for exact non-authoring references frozen inside a Scenario. */
@FunctionalInterface
public interface CorrectnessCompilationReferenceSource {

    boolean referenceIsCurrent(
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef reference,
            IntegrationRequestContext identity);
}
