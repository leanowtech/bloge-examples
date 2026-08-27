package com.leanowtech.bloge.gateway.testing.world.migration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Versioned output of one deterministic, one-way legacy migration.
 *
 * <p>The package keeps the governed authoring draft, while its explicit projection is safe for
 * indexes, logs, evidence, and API summaries. A package never publishes or mutates a legacy asset.</p>
 */
public record WorldMigrationDraftPackage(
        String schemaVersion,
        String algorithmVersion,
        String tenantId,
        String fixtureBundleFingerprint,
        String testSuiteFingerprint,
        String targetGraphArtifactFingerprint,
        String graphCompilationFingerprint,
        String graphInventoryFingerprint,
        List<WorldDraftMaterializationPlan> worldDrafts,
        ScenarioDraftSet scenarioDraftSet,
        List<LogicalContractCandidate> logicalContractCandidates,
        List<Diagnostic> diagnostics,
        List<ChecklistItem> completionChecklist,
        List<SourceMapping> legacyToDraft,
        List<SourceMapping> draftToLegacy,
        String fingerprint) {

    public WorldMigrationDraftPackage {
        if (!MigrationSupport.SCHEMA_VERSION.equals(schemaVersion)
                || !MigrationSupport.ALGORITHM_VERSION.equals(algorithmVersion)) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        tenantId = MigrationSupport.text(tenantId);
        fixtureBundleFingerprint = MigrationSupport.fingerprint(fixtureBundleFingerprint);
        testSuiteFingerprint = MigrationSupport.fingerprint(testSuiteFingerprint);
        targetGraphArtifactFingerprint = MigrationSupport.fingerprint(targetGraphArtifactFingerprint);
        graphCompilationFingerprint = MigrationSupport.fingerprint(graphCompilationFingerprint);
        graphInventoryFingerprint = MigrationSupport.fingerprint(graphInventoryFingerprint);
        worldDrafts = sorted(MigrationSupport.list(worldDrafts),
                Comparator.comparing(WorldDraftMaterializationPlan::draftId));
        if (scenarioDraftSet == null) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        logicalContractCandidates = sorted(MigrationSupport.list(logicalContractCandidates),
                Comparator.comparing(LogicalContractCandidate::contractId));
        diagnostics = sorted(MigrationSupport.list(diagnostics), Comparator.comparing(Diagnostic::key));
        completionChecklist = sorted(MigrationSupport.list(completionChecklist),
                Comparator.comparing(ChecklistItem::code));
        legacyToDraft = sorted(MigrationSupport.list(legacyToDraft), Comparator.comparing(SourceMapping::key));
        draftToLegacy = sorted(MigrationSupport.list(draftToLegacy), Comparator.comparing(SourceMapping::key));
        unique(worldDrafts.stream().map(WorldDraftMaterializationPlan::draftId).toList());
        unique(scenarioDraftSet.scenarios().stream().map(ScenarioDraftSet.ScenarioDraft::scenarioId).toList());
        unique(diagnostics.stream().map(Diagnostic::key).toList());
        fingerprint = MigrationSupport.fingerprint(fingerprint);
        if (!fingerprint.equals(computeFingerprint(schemaVersion, algorithmVersion, tenantId,
                fixtureBundleFingerprint, testSuiteFingerprint, targetGraphArtifactFingerprint,
                graphCompilationFingerprint, graphInventoryFingerprint, worldDrafts, scenarioDraftSet,
                logicalContractCandidates, diagnostics, completionChecklist, legacyToDraft, draftToLegacy))) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TAMPERED);
        }
    }

    public static WorldMigrationDraftPackage create(String tenantId, String fixtureFingerprint,
                                                     String suiteFingerprint, String targetFingerprint,
                                                     String compilationFingerprint, String inventoryFingerprint,
                                                     List<WorldDraftMaterializationPlan> worlds,
                                                     ScenarioDraftSet scenarioDraftSet,
                                                     List<LogicalContractCandidate> contracts,
                                                     List<Diagnostic> diagnostics,
                                                     List<ChecklistItem> checklist,
                                                     List<SourceMapping> legacyToDraft,
                                                     List<SourceMapping> draftToLegacy) {
        String tenant = MigrationSupport.text(tenantId);
        String fixture = MigrationSupport.fingerprint(fixtureFingerprint);
        String suite = MigrationSupport.fingerprint(suiteFingerprint);
        String target = MigrationSupport.fingerprint(targetFingerprint);
        String compilation = MigrationSupport.fingerprint(compilationFingerprint);
        String inventory = MigrationSupport.fingerprint(inventoryFingerprint);
        List<WorldDraftMaterializationPlan> orderedWorlds = sorted(MigrationSupport.list(worlds),
                Comparator.comparing(WorldDraftMaterializationPlan::draftId));
        List<LogicalContractCandidate> orderedContracts = sorted(MigrationSupport.list(contracts),
                Comparator.comparing(LogicalContractCandidate::contractId));
        List<Diagnostic> orderedDiagnostics = sorted(MigrationSupport.list(diagnostics),
                Comparator.comparing(Diagnostic::key));
        List<ChecklistItem> orderedChecklist = sorted(MigrationSupport.list(checklist),
                Comparator.comparing(ChecklistItem::code));
        List<SourceMapping> orderedLegacy = sorted(MigrationSupport.list(legacyToDraft),
                Comparator.comparing(SourceMapping::key));
        List<SourceMapping> orderedReverse = sorted(MigrationSupport.list(draftToLegacy),
                Comparator.comparing(SourceMapping::key));
        return new WorldMigrationDraftPackage(MigrationSupport.SCHEMA_VERSION,
                MigrationSupport.ALGORITHM_VERSION, tenant, fixture, suite, target, compilation, inventory,
                orderedWorlds, scenarioDraftSet, orderedContracts, orderedDiagnostics, orderedChecklist,
                orderedLegacy, orderedReverse,
                computeFingerprint(MigrationSupport.SCHEMA_VERSION, MigrationSupport.ALGORITHM_VERSION,
                        tenant, fixture, suite, target, compilation, inventory, orderedWorlds,
                        scenarioDraftSet, orderedContracts, orderedDiagnostics, orderedChecklist,
                        orderedLegacy, orderedReverse));
    }

    /** @return the real authoring scenarios, with their governed values intact. */
    @JsonIgnore
    public List<ScenarioDraftSet.ScenarioDraft> scenarioDrafts() {
        return scenarioDraftSet.scenarios();
    }

    /**
     * Rebinds this migrated authoring draft to the authoritative current Contract projection.
     * Migration has no license to synthesize a Contract fingerprint from a fixture tag.
     */
    @JsonIgnore
    public ScenarioDraftSet scenarioDraftSetFor(ContractDraft contract,
                                                com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (contract == null || mapper == null || contract.target() == null
                || !targetGraphArtifactFingerprint.equals(contract.target().fingerprint())
                || !scenarioDraftSet.target().kind().equals(contract.target().kind())
                || !scenarioDraftSet.target().id().equals(contract.target().id())) {
            throw MigrationSupport.fail(WorldMigrationException.Code.MAPPING_MISSING);
        }
        return new ScenarioDraftSet(scenarioDraftSet.schemaVersion(), scenarioDraftSet.scenarioDraftSetId(),
                scenarioDraftSet.revision(), scenarioDraftSet.scope(), contract.target(),
                contract.fingerprint(mapper), scenarioDraftSet.scenarios(), scenarioDraftSet.metadata());
    }

    /** Payload-free material used by external summaries and evidence. */
    @JsonIgnore
    public PayloadFreeProjection payloadFreeProjection() {
        return new PayloadFreeProjection(schemaVersion, algorithmVersion, tenantId,
                fixtureBundleFingerprint, testSuiteFingerprint, targetGraphArtifactFingerprint,
                graphCompilationFingerprint, graphInventoryFingerprint, scenarioDraftSet.scenarioDraftSetId(),
                scenarioDraftSet.revision(), scenarioDraftSet.scenarios().size(), worldDrafts.size(),
                diagnostics.size(), fingerprint);
    }

    @Override
    public String toString() {
        return "WorldMigrationDraftPackage[tenantId=" + tenantId + ",fixtureBundleFingerprint="
                + fixtureBundleFingerprint + ",testSuiteFingerprint=" + testSuiteFingerprint
                + ",scenarioDraftSetId=" + scenarioDraftSet.scenarioDraftSetId()
                + ",worldDraftCount=" + worldDrafts.size() + ",diagnosticCount=" + diagnostics.size()
                + ",fingerprint=" + fingerprint + "]";
    }

    private static String computeFingerprint(String schemaVersion, String algorithmVersion, String tenantId,
                                             String fixtureFingerprint, String suiteFingerprint,
                                             String targetFingerprint, String compilationFingerprint,
                                             String inventoryFingerprint, List<WorldDraftMaterializationPlan> worlds,
                                             ScenarioDraftSet scenarioDraftSet,
                                             List<LogicalContractCandidate> contracts,
                                             List<Diagnostic> diagnostics, List<ChecklistItem> checklist,
                                             List<SourceMapping> legacy, List<SourceMapping> reverse) {
        return MigrationSupport.hash(MigrationSupport.material("schemaVersion", schemaVersion,
                "algorithmVersion", algorithmVersion, "tenantId", tenantId,
                "fixtureBundleFingerprint", fixtureFingerprint, "testSuiteFingerprint", suiteFingerprint,
                "targetGraphArtifactFingerprint", targetFingerprint,
                "graphCompilationFingerprint", compilationFingerprint,
                "graphInventoryFingerprint", inventoryFingerprint,
                "worldDrafts", worlds, "scenarioDraftSet", scenarioDraftSet,
                "logicalContractCandidates", contracts, "diagnostics", diagnostics,
                "completionChecklist", checklist, "legacyToDraft", legacy, "draftToLegacy", reverse));
    }

    private static <T> List<T> sorted(List<T> values, Comparator<T> comparator) {
        List<T> copy = new ArrayList<>(values);
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static void unique(List<String> values) {
        if (values.size() != new HashSet<>(values).size()) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
    }

    public record PayloadFreeProjection(String schemaVersion, String algorithmVersion, String tenantId,
                                        String fixtureBundleFingerprint, String testSuiteFingerprint,
                                        String targetGraphArtifactFingerprint, String graphCompilationFingerprint,
                                        String graphInventoryFingerprint, String scenarioDraftSetId,
                                        long scenarioDraftSetRevision, int scenarioCount, int worldDraftCount,
                                        int diagnosticCount, String packageFingerprint) {
        public PayloadFreeProjection {
            schemaVersion = MigrationSupport.text(schemaVersion);
            algorithmVersion = MigrationSupport.text(algorithmVersion);
            tenantId = MigrationSupport.text(tenantId);
            fixtureBundleFingerprint = MigrationSupport.fingerprint(fixtureBundleFingerprint);
            testSuiteFingerprint = MigrationSupport.fingerprint(testSuiteFingerprint);
            targetGraphArtifactFingerprint = MigrationSupport.fingerprint(targetGraphArtifactFingerprint);
            graphCompilationFingerprint = MigrationSupport.fingerprint(graphCompilationFingerprint);
            graphInventoryFingerprint = MigrationSupport.fingerprint(graphInventoryFingerprint);
            scenarioDraftSetId = MigrationSupport.text(scenarioDraftSetId);
            packageFingerprint = MigrationSupport.fingerprint(packageFingerprint);
            if (scenarioDraftSetRevision < 0 || scenarioCount < 0 || worldDraftCount < 0 || diagnosticCount < 0) {
                throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
            }
        }
    }

    public record LogicalContractCandidate(String contractId, String contractFingerprint,
                                           List<String> sourceRuleIds) {
        public LogicalContractCandidate {
            contractId = MigrationSupport.text(contractId);
            contractFingerprint = MigrationSupport.fingerprint(contractFingerprint);
            sourceRuleIds = MigrationSupport.sorted(sourceRuleIds, Comparator.naturalOrder());
            if (sourceRuleIds.isEmpty()) throw MigrationSupport.fail(WorldMigrationException.Code.MAPPING_MISSING);
        }
    }

    public enum DiagnosticCode {
        UNMAPPED_NO_LOGICAL_CONTRACT,
        UNSUPPORTED_BEHAVIOR,
        SPY_NOT_PROMOTABLE,
        ALLOW_REAL_NOT_PROMOTABLE,
        FUZZY_SELECTOR_NOT_PROMOTABLE,
        UNFROZEN_REPLAY,
        SCHEMA_STANDIN_EXPLORATION,
        SOURCE_MAPPING_REQUIRED,
        EXECUTION_SERVICE_CONTROL_ONLY,
        MATERIALIZATION_PREREQUISITE_MISSING
    }

    public record Diagnostic(DiagnosticCode code, String sourceRuleId, String invocationSiteId) {
        public Diagnostic {
            if (code == null) throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
            sourceRuleId = sourceRuleId == null ? "" : sourceRuleId.trim();
            invocationSiteId = invocationSiteId == null ? "" : invocationSiteId.trim();
            if (sourceRuleId.chars().anyMatch(Character::isISOControl)
                    || invocationSiteId.chars().anyMatch(Character::isISOControl)) {
                throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
            }
        }
        String key() { return code.name() + "\u0000" + sourceRuleId + "\u0000" + invocationSiteId; }
    }

    public record ChecklistItem(DiagnosticCode code) {
        public ChecklistItem {
            if (code == null) throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
    }

    public record SourceMapping(String sourceKind, String sourceId, long sourceRevision,
                                String sourceFingerprint, String draftKind, String draftId) {
        public SourceMapping {
            sourceKind = MigrationSupport.text(sourceKind);
            sourceId = MigrationSupport.text(sourceId);
            sourceFingerprint = MigrationSupport.fingerprint(sourceFingerprint);
            draftKind = MigrationSupport.text(draftKind);
            draftId = MigrationSupport.text(draftId);
            if (sourceRevision < 1) throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        String key() { return sourceKind + "\u0000" + sourceId + "\u0000" + draftKind + "\u0000" + draftId; }
    }
}
