package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository.FixtureReferenceUsage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Rebuilds the disposable Fixture reverse-usage index from canonical Scenario heads. */
public final class FixtureUsageProjectionService {

    private final ScenarioDraftSetV2Repository scenarios;
    private final FixtureAssetRepository fixtures;

    public FixtureUsageProjectionService(
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures
    ) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
    }

    public ProjectionResult rebuild(
            EnterpriseScope scope,
            ExactTargetRef target
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(target, "target");
        List<ExactAssetRef> consumers = scenarios.currentDraftSetRefsByTarget(scope, target);
        Map<ExactAssetRef, List<ExactAssetRef>> fixturesByConsumer =
                scenarios.fixtureUsagesByTarget(scope, target).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                FixtureReferenceUsage::scenarioDraftSetRef,
                                LinkedHashMap::new,
                                java.util.stream.Collectors.mapping(
                                        FixtureReferenceUsage::fixtureAssetRef,
                                        java.util.stream.Collectors.collectingAndThen(
                                                java.util.stream.Collectors.toSet(),
                                                refs -> refs.stream()
                                                        .sorted(java.util.Comparator
                                                                .comparing(ExactAssetRef::id)
                                                                .thenComparingLong(
                                                                        ExactAssetRef::revision))
                                                        .toList()))));
        if (!consumers.containsAll(fixturesByConsumer.keySet())) {
            throw new IllegalStateException(
                    "Fixture usage projection referenced a non-current Scenario head");
        }
        int indexed = 0;
        for (ExactAssetRef consumer : consumers) {
            List<ExactAssetRef> refs = fixturesByConsumer.getOrDefault(consumer, List.of());
            fixtures.replaceUsageForConsumer(scope, consumer, refs);
            indexed += refs.size();
        }
        return new ProjectionResult(consumers.size(), indexed);
    }

    public record ProjectionResult(int consumerCount, int fixtureReferenceCount) {
        public ProjectionResult {
            if (consumerCount < 0 || fixtureReferenceCount < 0) {
                throw new IllegalArgumentException("Projection counts must not be negative");
            }
        }
    }
}
