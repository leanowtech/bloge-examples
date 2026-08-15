package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.DependencyBehaviorDraft;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.ScenarioDraft;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorBoundary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.Consumption;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledBehavior;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.DependencySelector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ExhaustionPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GivenV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.PathMatch;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.UnmatchedPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.LegacyScenarioV1MigrationPreview.DiagnosticSeverity;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.LegacyScenarioV1MigrationPreview.LegacyAssertionProposal;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.LegacyScenarioV1MigrationPreview.MigrationDiagnostic;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Deterministic, non-authoritative adapter from mutable Scenario v1 to governed v2 preview. */
public final class LegacyScenarioV1MigrationAdapter {

    private final Clock clock;

    public LegacyScenarioV1MigrationAdapter() {
        this(Clock.systemUTC());
    }

    public LegacyScenarioV1MigrationAdapter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LegacyScenarioV1MigrationPreview preview(
            ScenarioDraftSet legacy,
            EnterpriseScope authorizedScope,
            ExactTargetRef exactTarget,
            ExactAssetRef exactContractRef,
            ExactAssetRef legacySourceRef,
            PrincipalRef actor,
            PrincipalRef defaultOwner
    ) {
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(authorizedScope, "authorizedScope");
        Objects.requireNonNull(exactTarget, "exactTarget");
        Objects.requireNonNull(exactContractRef, "exactContractRef");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(defaultOwner, "defaultOwner");
        requireSource(legacy, legacySourceRef);
        requireScopeAndTarget(legacy, authorizedScope, exactTarget, exactContractRef);

        List<MigrationDiagnostic> diagnostics = new ArrayList<>();
        List<LegacyAssertionProposal> assertions = new ArrayList<>();
        List<ScenarioDraftV2> cases = new ArrayList<>();
        LinkedHashSet<String> caseIds = new LinkedHashSet<>();
        for (int index = 0; index < legacy.scenarios().size(); index++) {
            ScenarioDraft scenario = legacy.scenarios().get(index);
            if (scenario.scenarioId().isBlank() || scenario.name().isBlank()) {
                diagnostics.add(diagnostic(
                        DiagnosticSeverity.BLOCKER,
                        "RG.CORRECTNESS.MIGRATION.CASE_IDENTITY_REQUIRED",
                        scenario.scenarioId(), ""));
                continue;
            }
            if (!caseIds.add(scenario.scenarioId())) {
                diagnostics.add(diagnostic(
                        DiagnosticSeverity.BLOCKER,
                        "RG.CORRECTNESS.MIGRATION.CASE_ID_DUPLICATE",
                        scenario.scenarioId(), ""));
                continue;
            }
            cases.add(migrateCase(
                    scenario, legacySourceRef, defaultOwner, assertions, diagnostics));
        }
        var now = clock.instant();
        ScenarioDraftSetV2 proposal = new ScenarioDraftSetV2(
                "", legacy.scenarioDraftSetId(), 0, authorizedScope, exactTarget,
                exactContractRef, cases, new AuditMetadata(now, now, actor, actor));
        return new LegacyScenarioV1MigrationPreview(
                "", legacySourceRef, proposal, assertions, diagnostics, true);
    }

    private ScenarioDraftV2 migrateCase(
            ScenarioDraft source,
            ExactAssetRef legacySourceRef,
            PrincipalRef owner,
            List<LegacyAssertionProposal> assertionProposals,
            List<MigrationDiagnostic> diagnostics
    ) {
        diagnostics.add(diagnostic(
                DiagnosticSeverity.WARNING, "RG.CORRECTNESS.MIGRATION.RISK_REVIEW_REQUIRED",
                source.scenarioId(), ""));
        if (!source.then().assertions().isEmpty()) {
            assertionProposals.add(new LegacyAssertionProposal(
                    source.scenarioId(), source.then().assertions()));
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.WARNING,
                    "RG.CORRECTNESS.MIGRATION.ORACLE_BINDING_REQUIRED",
                    source.scenarioId(), ""));
        } else {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.ASSERTION_REQUIRED",
                    source.scenarioId(), ""));
        }

        List<ControlledDependencyV2> dependencies = new ArrayList<>();
        LinkedHashSet<String> dependencyIds = new LinkedHashSet<>();
        for (DependencyBehaviorDraft dependency : source.dependencies()) {
            if (dependency.dependencyId().isBlank()
                    || !dependencyIds.add(dependency.dependencyId())) {
                diagnostics.add(diagnostic(
                        DiagnosticSeverity.BLOCKER,
                        dependency.dependencyId().isBlank()
                                ? "RG.CORRECTNESS.MIGRATION.DEPENDENCY_ID_REQUIRED"
                                : "RG.CORRECTNESS.MIGRATION.DEPENDENCY_ID_DUPLICATE",
                        source.scenarioId(), dependency.dependencyId()));
                continue;
            }
            ControlledDependencyV2 migrated = migrateDependency(
                    source.scenarioId(), dependency, diagnostics);
            if (migrated != null) dependencies.add(migrated);
        }
        return new ScenarioDraftV2(
                source.scenarioId(), source.name(), businessIntent(source), source.description(),
                ScenarioDraftSetV2.CaseType.valueOf(source.caseType().name()), RiskLevel.HIGH,
                owner, ScenarioLifecycle.EXPLORATORY,
                List.of(), List.of(), List.of(), List.of(legacySourceRef),
                new GivenV2(new InlineValue(source.given().input())), dependencies,
                null, withMigrationTag(source.tags()));
    }

    private ControlledDependencyV2 migrateDependency(
            String scenarioId,
            DependencyBehaviorDraft source,
            List<MigrationDiagnostic> diagnostics
    ) {
        var selector = source.selector();
        if (List.of(
                selector.graphPath(), selector.nodeId(), selector.operatorRef(),
                selector.resourceRef(), selector.functionRef()).stream().allMatch(String::isBlank)) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.SELECTOR_COORDINATE_REQUIRED",
                    scenarioId, source.dependencyId()));
            return null;
        }
        if (selector.pathEquals().keySet().stream().anyMatch(String::isBlank)) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.SELECTOR_PATH_INVALID",
                    scenarioId, source.dependencyId()));
            return null;
        }
        var behavior = source.behavior();
        if (behavior.kind() == ScenarioDraftSet.BehaviorKind.REPLAY) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.REPLAY_EXACT_REF_REQUIRED",
                    scenarioId, source.dependencyId()));
            return null;
        }
        if (behavior.boundary() == ScenarioDraftSet.BehaviorBoundary.TRANSPORT
                && behavior.kind() == ScenarioDraftSet.BehaviorKind.RETURN) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.TRANSPORT_FIXTURE_REQUIRED",
                    scenarioId, source.dependencyId()));
            return null;
        }
        if (behavior.expectedInput() != null || !behavior.rawBody().isEmpty()
                || behavior.statusCode() != null || !behavior.headers().isEmpty()
                || !behavior.errorType().isEmpty() || !behavior.errorMessage().isEmpty()) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.WARNING,
                    "RG.CORRECTNESS.MIGRATION.LEGACY_BEHAVIOR_DETAIL_REVIEW_REQUIRED",
                    scenarioId, source.dependencyId()));
        }
        if (!"STRICT".equals(source.schemaCheck().mode())) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.WARNING,
                    "RG.CORRECTNESS.MIGRATION.SCHEMA_WAIVER_REVIEW_REQUIRED",
                    scenarioId, source.dependencyId()));
        }

        List<PathMatch> pathMatches = selector.pathEquals().entrySet().stream()
                .map(entry -> new PathMatch(entry.getKey(), entry.getValue()))
                .toList();
        DependencySelector targetSelector = new DependencySelector(
                selector.graphPath(), selector.nodeId(), selector.operatorRef(),
                selector.resourceRef(), selector.functionRef(), selector.attempts(),
                selector.occurrences(), selector.correlationKey(), pathMatches);
        InlineValue value = behavior.kind() == ScenarioDraftSet.BehaviorKind.RETURN
                || behavior.kind() == ScenarioDraftSet.BehaviorKind.DELAY
                ? new InlineValue(behavior.output()) : null;
        Long delayMs = delayMs(scenarioId, source.dependencyId(), behavior, diagnostics);
        if (delayMs == null) return null;
        ControlledBehavior targetBehavior = new ControlledBehavior(
                BehaviorKind.valueOf(behavior.kind().name()),
                BehaviorBoundary.valueOf(behavior.boundary().name()), value,
                behavior.errorCode(), delayMs);
        Consumption consumption = consumption(
                scenarioId, source.dependencyId(), source.consumption(), diagnostics);
        return new ControlledDependencyV2(
                source.dependencyId(), targetSelector, targetBehavior, consumption);
    }

    private Long delayMs(
            String scenarioId,
            String dependencyId,
            ScenarioDraftSet.DependencyBehavior behavior,
            List<MigrationDiagnostic> diagnostics
    ) {
        if ((behavior.kind() == ScenarioDraftSet.BehaviorKind.DELAY
                || behavior.kind() == ScenarioDraftSet.BehaviorKind.TIMEOUT)
                && (behavior.after() == null || behavior.after().isNegative())) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.DURATION_INVALID",
                    scenarioId, dependencyId));
            return null;
        }
        try {
            return behavior.after() == null ? 0 : behavior.after().toMillis();
        } catch (ArithmeticException failure) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.DURATION_INVALID",
                    scenarioId, dependencyId));
            return null;
        }
    }

    private Consumption consumption(
            String scenarioId,
            String dependencyId,
            ScenarioDraftSet.Consumption source,
            List<MigrationDiagnostic> diagnostics
    ) {
        int maximum = source.maxUses();
        if (maximum == 0) {
            maximum = Integer.MAX_VALUE;
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.WARNING,
                    "RG.CORRECTNESS.MIGRATION.UNBOUNDED_USE_REVIEW_REQUIRED",
                    scenarioId, dependencyId));
        }
        ExhaustionPolicy exhausted = parseExhaustion(
                source.onExhausted(), scenarioId, dependencyId, diagnostics);
        UnmatchedPolicy unmatched = parseUnmatched(
                source.onUnmatched(), scenarioId, dependencyId, diagnostics);
        return new Consumption(
                source.required(), source.minUses(), Math.max(source.minUses(), maximum),
                exhausted, unmatched);
    }

    private ExhaustionPolicy parseExhaustion(
            String value,
            String scenarioId,
            String dependencyId,
            List<MigrationDiagnostic> diagnostics
    ) {
        try {
            return ExhaustionPolicy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.EXHAUSTION_POLICY_UNSUPPORTED",
                    scenarioId, dependencyId));
            return ExhaustionPolicy.FAIL;
        }
    }

    private UnmatchedPolicy parseUnmatched(
            String value,
            String scenarioId,
            String dependencyId,
            List<MigrationDiagnostic> diagnostics
    ) {
        try {
            return UnmatchedPolicy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            diagnostics.add(diagnostic(
                    DiagnosticSeverity.BLOCKER,
                    "RG.CORRECTNESS.MIGRATION.UNMATCHED_POLICY_UNSUPPORTED",
                    scenarioId, dependencyId));
            return UnmatchedPolicy.FAIL;
        }
    }

    private static String businessIntent(ScenarioDraft source) {
        return source.description().isBlank() ? source.name() : source.description();
    }

    private static List<String> withMigrationTag(List<String> tags) {
        List<String> values = new ArrayList<>(tags);
        values.add("migrated-v1");
        return values;
    }

    private static MigrationDiagnostic diagnostic(
            DiagnosticSeverity severity,
            String code,
            String scenarioId,
            String dependencyId
    ) {
        return new MigrationDiagnostic(severity, code, scenarioId, dependencyId);
    }

    private static void requireSource(ScenarioDraftSet legacy, ExactAssetRef sourceRef) {
        if (sourceRef == null || !"SCENARIO_DRAFT_SET_V1".equals(sourceRef.kind())
                || !legacy.scenarioDraftSetId().equals(sourceRef.id())
                || legacy.revision() != sourceRef.revision()) {
            throw new IllegalArgumentException(
                    "Migration requires the exact persisted Scenario v1 source revision");
        }
    }

    private static void requireScopeAndTarget(
            ScenarioDraftSet legacy,
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef contractRef
    ) {
        ScenarioDraftSet.EnterpriseScope legacyScope = legacy.scope();
        if (!legacyScope.tenantId().equals(scope.tenantId())
                || !legacyScope.organizationId().equals(scope.organizationId())
                || !legacyScope.projectId().equals(scope.projectId())
                || !legacyScope.environment().equals(scope.environment())
                || !legacyScope.region().equals(scope.region())) {
            throw new IllegalArgumentException("Legacy Scenario scope does not match authorization");
        }
        if (!legacy.target().kind().name().equals(target.kind().name())
                || !legacy.target().id().equals(target.id())
                || legacy.target().revision() != target.revision()
                || !legacy.target().fingerprint().equals(target.fingerprint())) {
            throw new IllegalArgumentException("Legacy Scenario target is not the exact target");
        }
        if (!"CONTRACT".equals(contractRef.kind())
                || !legacy.contractFingerprint().equals(contractRef.fingerprint())) {
            throw new IllegalArgumentException("Legacy Scenario contract fingerprint drifted");
        }
    }
}
