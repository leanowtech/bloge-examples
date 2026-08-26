package com.leanowtech.bloge.gateway.testing.world.impact;

import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractCompatibility;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldImpactAnalysisTest {
    private static final String TARGET = fp('a');
    private static final String SCENARIO = fp('b');
    private static final String CONTRACT = fp('c');
    private static final String SLICE = fp('d');
    private static final String FRAGMENT = fp('e');
    private static final Instant START = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void reconciliationSeparatesDeclaredOnlyAndObservedOnlyAndBlocksPublication() {
        WorldStaticDependencySnapshot declared = staticSnapshot("scenario-a", List.of("site-a", "site-b"));
        WorldRuntimeConsumptionSnapshot observed = runtimeSnapshot("scenario-a", List.of("site-b"));
        WorldImpactReconciliation normal = WorldImpactReconciliation.reconcile(declared, observed);

        assertThat(normal.publicationBlocked()).isFalse();
        assertThat(normal.entries()).extracting(WorldImpactReconciliation.Entry::classification)
                .containsExactly(WorldImpactReconciliation.Classification.DECLARED_ONLY,
                        WorldImpactReconciliation.Classification.DECLARED_AND_OBSERVED);
        assertThat(normal.toString()).doesNotContain("secret-canary", "request-payload", "response-payload");

        WorldImpactReconciliation drift = WorldImpactReconciliation.reconcile(declared,
                runtimeSnapshot("scenario-a", List.of("site-c")));
        assertThat(drift.publicationBlocked()).isTrue();
        assertThat(drift.entries()).extracting(WorldImpactReconciliation.Entry::classification)
                .contains(WorldImpactReconciliation.Classification.OBSERVED_ONLY);
    }

    @Test
    void analyzerUsesStaticFullDenominatorForBreakingAndSelectiveRuntimeForCompatible() {
        WorldStaticDependencySnapshot first = staticSnapshot("scenario-a", List.of("site-a"));
        WorldStaticDependencySnapshot second = staticSnapshot("scenario-b", List.of("site-b"));
        WorldRuntimeConsumptionSnapshot observed = runtimeSnapshot("scenario-b", List.of("site-b"));
        var statics = List.of(new WorldImpactSnapshotRepository.IndexedStatic(first, 3),
                new WorldImpactSnapshotRepository.IndexedStatic(second, 3));
        var runtimes = List.of(new WorldImpactSnapshotRepository.IndexedRuntime(observed, 3));
        LogicalResourceContract oldContract = contract("logical.customer", false);
        LogicalResourceContract sameContract = contract("logical.customer", false);
        LogicalResourceContract breakingContract = contract("logical.customer", true);
        WorldContractImpactAnalyzer analyzer = new WorldContractImpactAnalyzer();

        WorldContractImpactReport compatible = analyzer.analyze("tenant-a", "logical.customer", oldContract,
                sameContract, statics, runtimes, 3, 3, true, true, START, START.plusSeconds(10));
        assertThat(compatible.status()).isEqualTo(WorldContractImpactReport.Status.COMPATIBLE_CHANGE);
        assertThat(compatible.affectedScenarioIds()).containsExactly("scenario-b");
        assertThat(compatible.toString()).doesNotContain("secret-canary", "request-payload", "response-payload");

        WorldContractImpactReport breaking = analyzer.analyze("tenant-a", "logical.customer", oldContract,
                breakingContract, statics, runtimes, 3, 3, true, true, START, START.plusSeconds(10));
        assertThat(breaking.status()).isEqualTo(WorldContractImpactReport.Status.BREAKING_CHANGE);
        assertThat(breaking.affectedScenarioIds()).containsExactly("scenario-a", "scenario-b");
    }

    @Test
    void staleOrIncompleteIndexesFailClosedToUnknownAndFullStaticSet() {
        WorldStaticDependencySnapshot snapshot = staticSnapshot("scenario-a", List.of("site-a"));
        var statics = List.of(new WorldImpactSnapshotRepository.IndexedStatic(snapshot, 4));
        var runtimes = List.<WorldImpactSnapshotRepository.IndexedRuntime>of();
        var contract = contract("logical.customer", false);
        WorldContractImpactReport report = new WorldContractImpactAnalyzer().analyze(
                "tenant-a", "logical.customer", contract, contract, statics, runtimes,
                4, 1, true, false, START, START.plusSeconds(1));

        assertThat(report.status()).isEqualTo(WorldContractImpactReport.Status.UNKNOWN);
        assertThat(report.conservativeFullSet()).isTrue();
        assertThat(report.scopeStatus()).isEqualTo(WorldContractImpactReport.ScopeStatus.DENOMINATOR_UNAVAILABLE);
        assertThat(report.gateBlocked()).isTrue();
        assertThat(report.affectedScenarioIds()).containsExactly("scenario-a");
        assertThatThrownBy(() -> new WorldContractImpactAnalyzer().analyze("tenant-a", "logical.customer",
                contract, contract, statics, runtimes, 4, 1, true, true,
                START.plusSeconds(2), START)).isInstanceOf(WorldImpactException.class);
    }

    @Test
    void repositoryDerivedMissingIndexesNeverBecomeSafeEmptyImpact() {
        LogicalResourceContract contract = contract("logical.customer", false);
        WorldContractImpactReport report = new WorldContractImpactAnalyzer().analyze(
                new InMemoryWorldImpactSnapshotRepository(), "tenant-a", "logical.customer",
                contract, contract, START, START.plusSeconds(1));

        assertThat(report.status()).isEqualTo(WorldContractImpactReport.Status.UNKNOWN);
        assertThat(report.scopeStatus()).isEqualTo(WorldContractImpactReport.ScopeStatus.DENOMINATOR_UNAVAILABLE);
        assertThat(report.gateBlocked()).isTrue();
        assertThat(report.conservativeFullSet()).isTrue();
    }

    @Test
    void repositoryDerivedMissingStaticAndMissingRuntimeAreIndependentlyBlocked() {
        LogicalResourceContract contract = contract("logical.customer", false);
        WorldStaticDependencySnapshot staticSnapshot = staticSnapshot("scenario-a", List.of("site-a"));
        WorldRuntimeConsumptionSnapshot runtimeSnapshot = runtimeSnapshot("scenario-a", List.of("site-a"));

        InMemoryWorldImpactSnapshotRepository runtimeOnly = new InMemoryWorldImpactSnapshotRepository();
        runtimeOnly.upsertRuntime(runtimeSnapshot);
        WorldContractImpactReport missingStatic = new WorldContractImpactAnalyzer().analyze(runtimeOnly,
                "tenant-a", "logical.customer", contract, contract, START, START.plusSeconds(1));
        assertThat(missingStatic.gateBlocked()).isTrue();
        assertThat(missingStatic.scopeStatus()).isEqualTo(WorldContractImpactReport.ScopeStatus.DENOMINATOR_UNAVAILABLE);

        InMemoryWorldImpactSnapshotRepository staticOnly = new InMemoryWorldImpactSnapshotRepository();
        staticOnly.upsertStatic(staticSnapshot);
        WorldContractImpactReport missingRuntime = new WorldContractImpactAnalyzer().analyze(staticOnly,
                "tenant-a", "logical.customer", contract, contract, START, START.plusSeconds(1));
        assertThat(missingRuntime.gateBlocked()).isTrue();
        assertThat(missingRuntime.scopeStatus()).isEqualTo(WorldContractImpactReport.ScopeStatus.DENOMINATOR_UNAVAILABLE);
        assertThat(missingRuntime.affectedScenarioIds()).containsExactly("scenario-a");
    }

    @Test
    void analyzerRejectsCrossTenantDuplicateOrCrossContractInputs() {
        WorldStaticDependencySnapshot first = staticSnapshot("scenario-a", List.of("site-a"));
        WorldRuntimeConsumptionSnapshot runtime = runtimeSnapshot("scenario-a", List.of("site-a"));
        LogicalResourceContract contract = contract("logical.customer", false);
        assertThatThrownBy(() -> new WorldContractImpactAnalyzer().analyze("tenant-a", "logical.customer",
                contract("logical.other", false), contract, List.of(new WorldImpactSnapshotRepository.IndexedStatic(first, 3)),
                List.of(new WorldImpactSnapshotRepository.IndexedRuntime(runtime, 3)), 3, 3, true, true,
                START, START.plusSeconds(1))).isInstanceOf(WorldImpactException.class);

        WorldStaticDependencySnapshot foreign = staticSnapshot("tenant-b", "scenario-b", List.of("site-b"));
        assertThatThrownBy(() -> new WorldContractImpactAnalyzer().analyze("tenant-a", "logical.customer",
                contract, contract, List.of(new WorldImpactSnapshotRepository.IndexedStatic(first, 3),
                        new WorldImpactSnapshotRepository.IndexedStatic(foreign, 3)),
                List.of(new WorldImpactSnapshotRepository.IndexedRuntime(runtime, 3)), 3, 3, true, true,
                START, START.plusSeconds(1))).isInstanceOf(WorldImpactException.class);

        assertThatThrownBy(() -> new WorldContractImpactAnalyzer().analyze("tenant-a", "logical.customer",
                contract, contract, List.of(new WorldImpactSnapshotRepository.IndexedStatic(first, 3),
                        new WorldImpactSnapshotRepository.IndexedStatic(first, 3)),
                List.of(new WorldImpactSnapshotRepository.IndexedRuntime(runtime, 3)), 3, 3, true, true,
                START, START.plusSeconds(1))).isInstanceOf(WorldImpactException.class);
    }

    @Test
    void staleWatermarkRaceAndUnexpectedObservedChainFailClosed() {
        WorldStaticDependencySnapshot declared = staticSnapshot("scenario-a", List.of("site-a"));
        WorldRuntimeConsumptionSnapshot observed = runtimeSnapshot("scenario-a", List.of("site-a"));
        WorldContractImpactReport report = new WorldContractImpactAnalyzer().analyze("tenant-a", "logical.customer",
                contract("logical.customer", false), contract("logical.customer", false),
                List.of(new WorldImpactSnapshotRepository.IndexedStatic(declared, 2)),
                List.of(new WorldImpactSnapshotRepository.IndexedRuntime(observed, 3)),
                2, 3, true, true, START, START.plusSeconds(1));
        assertThat(report.scopeStatus()).isEqualTo(WorldContractImpactReport.ScopeStatus.INDEX_STALE);
        assertThat(report.gateBlocked()).isTrue();

        WorldRuntimeConsumptionSnapshot drifted = WorldRuntimeConsumptionSnapshot.create("tenant-a", "scenario-a", 1,
                SCENARIO, "run-drift", fp('1'), TARGET, fp('2'), 3, START, START.plusSeconds(1),
                START.plusSeconds(2), List.of(new WorldRuntimeConsumptionSnapshot.Consumption("rule-a", "rule-a",
                        "logical.customer", fp('8'), SLICE, FRAGMENT, List.of("site-a"))));
        assertThatThrownBy(() -> WorldImpactReconciliation.reconcile(declared, drifted))
                .isInstanceOf(WorldImpactException.class)
                .hasMessage("RG.WORLD_IMPACT.SOURCE_INTEGRITY");
    }

    @Test
    void reportAndReconciliationFingerprintsAreStableAcrossTwentyRebuilds() {
        WorldStaticDependencySnapshot first = staticSnapshot("scenario-a", List.of("site-a", "site-b"));
        WorldStaticDependencySnapshot second = staticSnapshot("scenario-b", List.of("site-c"));
        WorldRuntimeConsumptionSnapshot observed = runtimeSnapshot("scenario-b", List.of("site-c"));
        var statics = List.of(new WorldImpactSnapshotRepository.IndexedStatic(first, 3),
                new WorldImpactSnapshotRepository.IndexedStatic(second, 3));
        var runtimes = List.of(new WorldImpactSnapshotRepository.IndexedRuntime(observed, 3));
        LogicalResourceContract contract = contract("logical.customer", false);
        WorldContractImpactAnalyzer analyzer = new WorldContractImpactAnalyzer();
        WorldContractImpactReport expected = analyzer.analyze("tenant-a", "logical.customer", contract, contract,
                statics, runtimes, 3, 3, true, true, START, START.plusSeconds(1));
        WorldImpactReconciliation reconciliation = WorldImpactReconciliation.reconcile(second, observed);
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(analyzer.analyze("tenant-a", "logical.customer", contract, contract,
                    List.of(statics.get(1), statics.get(0)), runtimes, 3, 3, true, true,
                    START, START.plusSeconds(1)).fingerprint()).isEqualTo(expected.fingerprint());
            assertThat(WorldImpactReconciliation.reconcile(second, observed).fingerprint())
                    .isEqualTo(reconciliation.fingerprint());
        }
    }

    private static WorldStaticDependencySnapshot staticSnapshot(String scenario, List<String> sites) {
        return staticSnapshot("tenant-a", scenario, sites);
    }

    private static WorldStaticDependencySnapshot staticSnapshot(String tenant, String scenario, List<String> sites) {
        return WorldStaticDependencySnapshot.create(tenant, scenario, 1, SCENARIO, "world-a", 1,
                fp('f'), TARGET, 3, START, List.of(new WorldStaticDependencySnapshot.Dependency(
                        "rule-a", "logical.customer", CONTRACT, SLICE, FRAGMENT, TARGET, sites)));
    }

    private static WorldRuntimeConsumptionSnapshot runtimeSnapshot(String scenario, List<String> sites) {
        return runtimeSnapshot("tenant-a", scenario, sites);
    }

    private static WorldRuntimeConsumptionSnapshot runtimeSnapshot(String tenant, String scenario, List<String> sites) {
        return WorldRuntimeConsumptionSnapshot.create(tenant, scenario, 1, SCENARIO, "run-" + scenario,
                fp('1'), TARGET, fp('2'), 3, START, START.plusSeconds(1), START.plusSeconds(2),
                List.of(new WorldRuntimeConsumptionSnapshot.Consumption("rule-a", "rule-a",
                        "logical.customer", CONTRACT, SLICE, FRAGMENT, sites)));
    }

    private static LogicalResourceContract contract(String id, boolean addRequiredProperty) {
        Map<String, Object> properties = addRequiredProperty
                ? Map.of("id", Map.of("type", "string"), "new", Map.of("type", "string"))
                : Map.of("id", Map.of("type", "string"));
        List<String> required = addRequiredProperty ? List.of("id", "new") : List.of("id");
        return new LogicalResourceContract(id, SchemaEnvelope.object(properties, required),
                SchemaEnvelope.object(Map.of("result", Map.of("type", "string")), List.of("result")),
                ResponseSemantics.confirmed("http.status in 200..299", Map.of("BUSINESS", List.of("NOT_FOUND")),
                        ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL));
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
