package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioExternalReferenceSource;

import java.util.List;

/** Reuses the Scenario review authority when publication revalidates frozen external refs. */
public final class ScenarioExternalCompilationReferenceSource
        implements CorrectnessCompilationReferenceSource {

    private final List<ScenarioExternalReferenceSource> sources;

    public ScenarioExternalCompilationReferenceSource(
            List<ScenarioExternalReferenceSource> sources
    ) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one exact Scenario external reference authority is required");
        }
    }

    @Override
    public boolean referenceIsCurrent(
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef reference,
            IntegrationRequestContext identity
    ) {
        return sources.stream().anyMatch(source ->
                source.referenceIsCurrent(scope, target, reference));
    }
}
