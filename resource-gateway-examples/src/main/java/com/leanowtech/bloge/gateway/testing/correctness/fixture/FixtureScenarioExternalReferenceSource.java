package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioExternalReferenceSource;

import java.time.Clock;
import java.util.Objects;

/** Resolves current governed Fixture descriptors without opening protected material. */
public final class FixtureScenarioExternalReferenceSource
        implements ScenarioExternalReferenceSource {

    private final FixtureAssetRepository fixtures;
    private final Clock clock;

    public FixtureScenarioExternalReferenceSource(FixtureAssetRepository fixtures) {
        this(fixtures, Clock.systemUTC());
    }

    public FixtureScenarioExternalReferenceSource(
            FixtureAssetRepository fixtures,
            Clock clock
    ) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean referenceIsCurrent(
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef reference
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(target, "target");
        if (reference == null || !"FIXTURE_ASSET".equals(reference.kind())) {
            return false;
        }
        var revision = fixtures.findRevision(scope, reference.id(), reference.revision())
                .orElse(null);
        if (revision == null || !revision.exactRef().equals(reference)) {
            return false;
        }
        var head = fixtures.findHead(scope, reference.id()).orElse(null);
        return head != null
                && head.exactRef().equals(reference)
                && revision.descriptor().lifecycle() == FixtureLifecycle.ACTIVE
                && revision.descriptor().retention().expiresAt().isAfter(clock.instant());
    }
}
