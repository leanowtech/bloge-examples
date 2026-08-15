package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository.FixtureReferenceUsage;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.FixtureCatalogSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.FixtureSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.StaleReason;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Adds the payload-free Fixture catalog and exact-reference health to a Workspace. */
public final class FixtureCorrectnessWorkspaceComponentSource
        implements CorrectnessWorkspaceComponentSource {

    private static final int MAX_ROWS = 100;

    private final CorrectnessWorkspaceComponentSource delegate;
    private final ScenarioDraftSetV2Repository scenarios;
    private final FixtureAssetRepository fixtures;
    private final Clock clock;

    public FixtureCorrectnessWorkspaceComponentSource(
            CorrectnessWorkspaceComponentSource delegate,
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures
    ) {
        this(delegate, scenarios, fixtures, Clock.systemUTC());
    }

    public FixtureCorrectnessWorkspaceComponentSource(
            CorrectnessWorkspaceComponentSource delegate,
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Components load(Coordinate coordinate, PageRequest pageRequest) {
        Components base = delegate.load(coordinate, pageRequest);
        List<FixtureReferenceUsage> usages = scenarios.fixtureUsagesByTarget(
                coordinate.scope(), coordinate.target());
        Map<ExactAssetRef, Set<ExactAssetRef>> consumersByFixture = group(usages);
        List<FixtureSummary> rows = new ArrayList<>();
        List<StaleReason> staleReasons = new ArrayList<>(base.staleReasons());
        int active = 0;
        for (var entry : consumersByFixture.entrySet()) {
            Resolution resolution = resolve(coordinate, entry.getKey());
            if (resolution.staleCode() == null) {
                active++;
            } else {
                staleReasons.add(new StaleReason(
                        resolution.staleCode(), "FIXTURE_ASSET", entry.getKey()));
            }
            if (resolution.stored() != null && rows.size() < MAX_ROWS) {
                rows.add(toRow(resolution.stored(), entry.getValue().size()));
            }
        }
        int total = consumersByFixture.size();
        int stale = total - active;
        List<String> capabilities = new ArrayList<>(base.capabilities());
        capabilities.add("FIXTURE_CATALOG_METADATA_V1");
        capabilities.add("FIXTURE_USAGE_STALE_V1");
        return new Components(
                base.coverage(), base.oracleAssertions(), base.cases(),
                new FixtureCatalogSummary(
                        Availability.AVAILABLE, total, active, stale, List.copyOf(rows)),
                base.reviews(), base.lastPublication(), base.lastRun(),
                stale == 0 ? base.verdict() : staleVerdict(base.verdict()),
                List.copyOf(staleReasons), List.copyOf(capabilities), base.commandPolicy());
    }

    private Resolution resolve(Coordinate coordinate, ExactAssetRef reference) {
        StoredFixtureAsset revision = fixtures.findRevision(
                coordinate.scope(), reference.id(), reference.revision()).orElse(null);
        if (revision == null || !revision.exactRef().equals(reference)) {
            return new Resolution(revision, "FIXTURE_REFERENCE_MISSING");
        }
        StoredFixtureAsset head = fixtures.findHead(
                coordinate.scope(), reference.id()).orElse(null);
        if (head == null || !head.exactRef().equals(reference)) {
            return new Resolution(revision, "FIXTURE_HEAD_DRIFT");
        }
        FixtureAssetDescriptor descriptor = revision.descriptor();
        if (descriptor.lifecycle() != FixtureLifecycle.ACTIVE) {
            return new Resolution(revision, "FIXTURE_NOT_ACTIVE");
        }
        if (!descriptor.retention().expiresAt().isAfter(clock.instant())) {
            return new Resolution(revision, "FIXTURE_RETENTION_EXPIRED");
        }
        return new Resolution(revision, null);
    }

    private static Map<ExactAssetRef, Set<ExactAssetRef>> group(
            List<FixtureReferenceUsage> usages
    ) {
        Map<ExactAssetRef, Set<ExactAssetRef>> grouped = new LinkedHashMap<>();
        List<FixtureReferenceUsage> ordered = usages == null ? List.of() : usages.stream()
                .distinct()
                .sorted(Comparator.comparing(
                                (FixtureReferenceUsage usage) -> usage.fixtureAssetRef().id())
                        .thenComparingLong(usage -> usage.fixtureAssetRef().revision())
                        .thenComparing(usage -> usage.fixtureAssetRef().fingerprint())
                        .thenComparing(usage -> usage.scenarioDraftSetRef().id()))
                .toList();
        for (FixtureReferenceUsage usage : ordered) {
            grouped.computeIfAbsent(
                    usage.fixtureAssetRef(), ignored -> new LinkedHashSet<>())
                    .add(usage.scenarioDraftSetRef());
        }
        return grouped;
    }

    private static FixtureSummary toRow(StoredFixtureAsset stored, int usageCount) {
        FixtureAssetDescriptor value = stored.descriptor();
        return new FixtureSummary(
                stored.exactRef(), value.name(), value.variantKey(), value.lifecycle().name(),
                value.classification(), value.schemaRef(),
                value.materialRef().fingerprint(), usageCount);
    }

    private static CorrectnessVerdict staleVerdict(CorrectnessVerdict base) {
        List<CorrectnessVerdict.Reason> reasons = new ArrayList<>(base.reasons());
        reasons.add(new CorrectnessVerdict.Reason(
                "FIXTURE_REFERENCE_STALE", "GATE",
                "correctness.fixture.referenceStale"));
        List<CorrectnessVerdict.Remediation> actions = new ArrayList<>(base.nextActions());
        actions.add(new CorrectnessVerdict.Remediation(
                "OPEN_FIXTURE_CATALOG", "FIXTURE_REFERENCE_STALE"));
        return new CorrectnessVerdict(
                base.execution(), base.assertions(), base.coverage(), base.evidence(),
                CorrectnessVerdict.GateVerdict.BLOCKED, base.proofLevel(),
                List.copyOf(reasons), List.copyOf(actions));
    }

    private record Resolution(StoredFixtureAsset stored, String staleCode) {}
}
