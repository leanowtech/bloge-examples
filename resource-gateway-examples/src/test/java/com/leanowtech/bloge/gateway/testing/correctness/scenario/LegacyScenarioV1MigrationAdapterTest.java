package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.AssertionDraft;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.AssertionOperator;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.AssertionScope;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.DependencyBehavior;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.DependencyBehaviorDraft;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.DependencySelector;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.Given;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.ScenarioDraft;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.SchemaCheck;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.Then;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.LegacyScenarioV1MigrationPreview.DiagnosticSeverity;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyScenarioV1MigrationAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-15T16:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final LegacyScenarioV1MigrationAdapter adapter =
            new LegacyScenarioV1MigrationAdapter(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void preservesReviewableContentWithoutManufacturingGovernedAuthority() {
        LegacyScenarioV1MigrationPreview preview = migrate(legacy());

        assertThat(preview.reviewRequired()).isTrue();
        assertThat(preview.proposedDraftSet().revision()).isZero();
        assertThat(preview.proposedDraftSet().scenarios())
                .allMatch(scenario -> scenario.lifecycle() == ScenarioLifecycle.EXPLORATORY)
                .allMatch(scenario -> scenario.review().reviewer() == null)
                .allMatch(scenario -> scenario.obligationRefs().isEmpty())
                .allMatch(scenario -> scenario.oracleRefs().isEmpty())
                .allMatch(scenario -> scenario.assertionSetRefs().isEmpty())
                .allMatch(scenario -> scenario.sourceRefs().equals(List.of(sourceRef())));
        assertThat(preview.proposedDraftSet().scenarios().getFirst().dependencies())
                .extracting(value -> value.dependencyId())
                .containsExactly("score-return");
        assertThat(preview.assertionProposals()).hasSize(1);
        assertThat(preview.assertionProposals().getFirst().assertions().getFirst().expected())
                .isEqualTo("APPROVE");
        assertThat(preview.diagnostics())
                .extracting(value -> value.code())
                .contains(
                        "RG.CORRECTNESS.MIGRATION.RISK_REVIEW_REQUIRED",
                        "RG.CORRECTNESS.MIGRATION.ORACLE_BINDING_REQUIRED",
                        "RG.CORRECTNESS.MIGRATION.UNBOUNDED_USE_REVIEW_REQUIRED",
                        "RG.CORRECTNESS.MIGRATION.REPLAY_EXACT_REF_REQUIRED",
                        "RG.CORRECTNESS.MIGRATION.SELECTOR_COORDINATE_REQUIRED");
        assertThat(preview.diagnostics())
                .anyMatch(value -> value.severity() == DiagnosticSeverity.BLOCKER);
    }

    @Test
    void migrationIsDeterministicAndItsWireFieldsMatchTheMachineSchema() throws Exception {
        LegacyScenarioV1MigrationPreview first = migrate(legacy());
        LegacyScenarioV1MigrationPreview second = migrate(legacy());

        assertThat(CorrectnessProtocolFingerprint.fingerprint(
                mapper, first.proposedDraftSet()))
                .isEqualTo(CorrectnessProtocolFingerprint.fingerprint(
                        mapper, second.proposedDraftSet()));
        assertThat(mapper.writeValueAsString(first)).isEqualTo(mapper.writeValueAsString(second));

        var schema = mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-scenario-v1-migration-preview-v1.schema.json")));
        HashSet<String> actual = new HashSet<>();
        mapper.valueToTree(first).fieldNames().forEachRemaining(actual::add);
        HashSet<String> documented = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(documented::add);
        assertThat(documented).isEqualTo(actual);
    }

    @Test
    void rejectsScopeTargetContractAndSourceDrift() {
        assertThatThrownBy(() -> adapter.preview(
                legacy(),
                new EnterpriseScope("other", "org-a", "credit", "test", "sg"),
                target(), contractRef(), sourceRef(), actor(), owner()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        ExactTargetRef driftedTarget = new ExactTargetRef(
                TargetKind.GRAPH, "loan-graph", 4, fingerprint('9'));
        assertThatThrownBy(() -> adapter.preview(
                legacy(), scope(), driftedTarget, contractRef(), sourceRef(), actor(), owner()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
        ExactAssetRef driftedContract = new ExactAssetRef(
                "CONTRACT", "loan-contract", 2, fingerprint('9'));
        assertThatThrownBy(() -> adapter.preview(
                legacy(), scope(), target(), driftedContract, sourceRef(), actor(), owner()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contract");
    }

    @Test
    void isolatesDirtyLegacyRowsAsBlockersWithoutAbortingThePreview() {
        ScenarioDraft base = legacy().scenarios().getFirst();
        ScenarioDraft first = new ScenarioDraft(
                base.scenarioId(), base.name(), base.description(), base.caseType(), base.tags(),
                base.given(), List.of(validReturn(), validReturn(), invalidDelay()), base.then());
        ScenarioDraft duplicate = new ScenarioDraft(
                base.scenarioId(), "Duplicate", "Duplicate id", base.caseType(), List.of(),
                base.given(), List.of(), base.then());
        ScenarioDraft missingIdentity = new ScenarioDraft(
                "", "", "Missing identity", base.caseType(), List.of(),
                base.given(), List.of(), Then.empty());
        ScenarioDraftSet dirty = new ScenarioDraftSet(
                "", "loan-scenarios", 7, legacyScope(), legacyTarget(), fingerprint('c'),
                List.of(first, duplicate, missingIdentity), legacy().metadata());

        LegacyScenarioV1MigrationPreview preview = migrate(dirty);

        assertThat(preview.proposedDraftSet().scenarios()).hasSize(1);
        assertThat(preview.proposedDraftSet().scenarios().getFirst().dependencies())
                .extracting(value -> value.dependencyId()).containsExactly("score-return");
        assertThat(preview.diagnostics()).extracting(value -> value.code()).contains(
                "RG.CORRECTNESS.MIGRATION.DEPENDENCY_ID_DUPLICATE",
                "RG.CORRECTNESS.MIGRATION.DURATION_INVALID",
                "RG.CORRECTNESS.MIGRATION.CASE_ID_DUPLICATE",
                "RG.CORRECTNESS.MIGRATION.CASE_IDENTITY_REQUIRED");
    }

    private LegacyScenarioV1MigrationPreview migrate(ScenarioDraftSet source) {
        return adapter.preview(
                source, scope(), target(), contractRef(), sourceRef(), actor(), owner());
    }

    private ScenarioDraftSet legacy() {
        ScenarioDraft scenario = new ScenarioDraft(
                "prime-approved", "Prime approved", "Prove eligible approval",
                ScenarioDraftSet.CaseType.GOLDEN, List.of("loan"),
                new Given(Map.of("applicantId", "A-100"),
                        ScenarioDraftSet.ValueProvenance.MIGRATED),
                List.of(validReturn(), replayWithoutExactRef(), missingSelector()),
                new Then(List.of(new AssertionDraft(
                        "decision", AssertionScope.OUTPUT_PATH, "", "", "", "/decision",
                        AssertionOperator.EQUALS, "APPROVE", null))));
        return new ScenarioDraftSet(
                "", "loan-scenarios", 7, legacyScope(), legacyTarget(), fingerprint('c'),
                List.of(scenario), new ScenarioDraftSet.Metadata(
                        "credit-owner", "CONFIDENTIAL", NOW, NOW, Map.of("source", "legacy")));
    }

    private DependencyBehaviorDraft validReturn() {
        return new DependencyBehaviorDraft(
                "score-return", DependencySelector.node("score"),
                DependencyBehavior.returning(Map.of("score", 760)),
                new ScenarioDraftSet.Consumption(true, 1, 0, "FAIL", "FAIL"),
                SchemaCheck.strict(), "MIGRATED");
    }

    private DependencyBehaviorDraft replayWithoutExactRef() {
        return new DependencyBehaviorDraft(
                "legacy-replay", DependencySelector.node("history"),
                new DependencyBehavior(
                        ScenarioDraftSet.BehaviorKind.REPLAY,
                        ScenarioDraftSet.BehaviorBoundary.NODE, null, null, "", null,
                        Map.of(), "", "", "", Duration.ZERO, "run-123"),
                ScenarioDraftSet.Consumption.once(), SchemaCheck.strict(), "MIGRATED");
    }

    private DependencyBehaviorDraft missingSelector() {
        return new DependencyBehaviorDraft(
                "ambiguous", DependencySelector.any(), DependencyBehavior.real(),
                ScenarioDraftSet.Consumption.once(), SchemaCheck.strict(), "MIGRATED");
    }

    private DependencyBehaviorDraft invalidDelay() {
        return new DependencyBehaviorDraft(
                "invalid-delay", DependencySelector.node("slow-resource"),
                new DependencyBehavior(
                        ScenarioDraftSet.BehaviorKind.DELAY,
                        ScenarioDraftSet.BehaviorBoundary.NODE, Map.of("ok", true), null,
                        "", null, Map.of(), "", "", "", Duration.ofMillis(-1), ""),
                ScenarioDraftSet.Consumption.once(), SchemaCheck.strict(), "MIGRATED");
    }

    private ScenarioDraftSet.EnterpriseScope legacyScope() {
        return new ScenarioDraftSet.EnterpriseScope(
                "tenant-a", "org-a", "credit", "test", "sg");
    }

    private ContractDraft.Target legacyTarget() {
        return new ContractDraft.Target(
                ContractDraft.TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private ExactAssetRef contractRef() {
        return new ExactAssetRef("CONTRACT", "loan-contract", 2, fingerprint('c'));
    }

    private ExactAssetRef sourceRef() {
        return new ExactAssetRef(
                "SCENARIO_DRAFT_SET_V1", "loan-scenarios", 7, fingerprint('7'));
    }

    private PrincipalRef actor() {
        return new PrincipalRef("migration-service", PrincipalKind.SERVICE, "Migration Service");
    }

    private PrincipalRef owner() {
        return new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
