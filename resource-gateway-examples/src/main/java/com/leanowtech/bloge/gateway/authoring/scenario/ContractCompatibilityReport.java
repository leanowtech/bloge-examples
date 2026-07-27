package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualAuthoringJsonValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deterministic explanation of Contract drift and its exact Scenario impact.
 *
 * <p>The report is an authoring and governance input, not proof that a migrated Scenario passed.
 * Automatic actions only describe deterministic data edits; a migrated revision still requires
 * normal validation, execution, evidence, and publication gates.</p>
 *
 * @param schemaVersion report protocol version
 * @param scenarioDraftSetId source Scenario asset id
 * @param scenarioRevision exact retained source revision
 * @param target current authoritative target coordinate
 * @param baselineContractFingerprint Contract accepted with the source revision
 * @param currentContractFingerprint current authoritative Contract fingerprint
 * @param policy compatibility policy applied by the analyzer
 * @param classification overall compatibility classification
 * @param findings deterministic field and target findings
 * @param impactedScenarios exact Scenario impact projection
 * @param migrations guided migration actions
 * @param generatedAt report generation time, excluded from semantic fingerprinting
 * @param reportFingerprint canonical fingerprint of deterministic report content
 */
public record ContractCompatibilityReport(
        String schemaVersion,
        String scenarioDraftSetId,
        long scenarioRevision,
        ContractDraft.Target target,
        String baselineContractFingerprint,
        String currentContractFingerprint,
        String policy,
        Classification classification,
        List<Finding> findings,
        List<ScenarioImpact> impactedScenarios,
        List<MigrationAction> migrations,
        Instant generatedAt,
        String reportFingerprint
) {
    /** Current compatibility-report protocol version. */
    public static final String SCHEMA_VERSION = "bloge.contractCompatibilityReport.v1";

    /** Normalizes identifiers and freezes ordered report collections. */
    public ContractCompatibilityReport {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        scenarioDraftSetId = trimmed(scenarioDraftSetId);
        scenarioRevision = Math.max(0, scenarioRevision);
        target = target == null ? ContractDraft.Target.unknown() : target;
        baselineContractFingerprint = trimmed(baselineContractFingerprint);
        currentContractFingerprint = trimmed(currentContractFingerprint);
        policy = defaulted(policy, "STRICT");
        classification = classification == null ? Classification.REVIEW_REQUIRED : classification;
        findings = findings == null ? List.of() : List.copyOf(findings);
        impactedScenarios = impactedScenarios == null ? List.of() : List.copyOf(impactedScenarios);
        migrations = migrations == null ? List.of() : List.copyOf(migrations);
        reportFingerprint = trimmed(reportFingerprint);
    }

    /** Overall deterministic compatibility result. */
    public enum Classification {
        UNCHANGED,
        COMPATIBLE,
        BREAKING,
        REVIEW_REQUIRED
    }

    /** Schema side containing a finding. */
    public enum Scope {
        INPUT,
        OUTPUT,
        CONTRACT
    }

    /** Machine-readable structural change. */
    public enum ChangeKind {
        ADDED,
        REMOVED,
        RENAMED,
        REQUIRED_CHANGED,
        TYPE_CHANGED,
        ENUM_CHANGED,
        CONSTRAINT_CHANGED,
        TARGET_CHANGED,
        OPAQUE
    }

    /** Per-finding compatibility outcome. */
    public enum FindingClassification {
        COMPATIBLE,
        BREAKING,
        REVIEW_REQUIRED
    }

    /** Scenario-level migration readiness. */
    public enum ImpactStatus {
        MIGRATION_AVAILABLE,
        BLOCKED,
        REVIEW_REQUIRED
    }

    /** Supported guided action. */
    public enum MigrationKind {
        ADD_DEFAULT,
        REMOVE_INPUT,
        RENAME_INPUT,
        REBIND_OUTPUT_ASSERTION,
        SET_REQUIRED_VALUE,
        CONVERT_VALUE,
        MANUAL_REVIEW
    }

    /**
     * One field-level or target-level compatibility fact.
     *
     * @param findingId deterministic report-local id
     * @param scope input, output, or target Contract
     * @param path candidate JSON Pointer
     * @param previousPath baseline path for rename findings
     * @param change structural change
     * @param classification compatibility outcome
     * @param code stable machine code
     * @param message bounded author-facing explanation
     * @param details payload-free structural summary
     */
    public record Finding(
            String findingId,
            Scope scope,
            String path,
            String previousPath,
            ChangeKind change,
            FindingClassification classification,
            String code,
            String message,
            Map<String, Object> details
    ) {
        /** Freezes structural details and normalizes coordinates. */
        public Finding {
            findingId = trimmed(findingId);
            scope = scope == null ? Scope.CONTRACT : scope;
            path = trimmed(path);
            previousPath = trimmed(previousPath);
            change = change == null ? ChangeKind.OPAQUE : change;
            classification = classification == null
                    ? FindingClassification.REVIEW_REQUIRED : classification;
            code = trimmed(code);
            message = trimmed(message);
            details = VisualAuthoringJsonValue.freezeMap(details);
        }
    }

    /**
     * Exact impact of report findings on one retained Scenario.
     *
     * @param scenarioId stable source Scenario id
     * @param status migration readiness
     * @param findingIds findings touching this Scenario
     * @param paths Scenario or Contract paths requiring attention
     */
    public record ScenarioImpact(
            String scenarioId,
            ImpactStatus status,
            List<String> findingIds,
            List<String> paths
    ) {
        /** Freezes and normalizes impact coordinates. */
        public ScenarioImpact {
            scenarioId = trimmed(scenarioId);
            status = status == null ? ImpactStatus.REVIEW_REQUIRED : status;
            findingIds = findingIds == null ? List.of() : findingIds.stream()
                    .map(ContractCompatibilityReport::trimmed).distinct().sorted().toList();
            paths = paths == null ? List.of() : paths.stream()
                    .map(ContractCompatibilityReport::trimmed).distinct().sorted().toList();
        }
    }

    /**
     * Explicit migration proposed for affected Scenario values or assertions.
     *
     * @param actionId deterministic report-local id
     * @param kind migration operation
     * @param scope input, output, or Contract
     * @param fromPath baseline path
     * @param toPath candidate path
     * @param automatic whether the UI can apply the edit without inventing a value
     * @param scenarioIds exact affected Scenarios
     * @param rationale bounded author-facing reason
     */
    public record MigrationAction(
            String actionId,
            MigrationKind kind,
            Scope scope,
            String fromPath,
            String toPath,
            boolean automatic,
            List<String> scenarioIds,
            String rationale
    ) {
        /** Normalizes paths and freezes exact Scenario refs. */
        public MigrationAction {
            actionId = trimmed(actionId);
            kind = kind == null ? MigrationKind.MANUAL_REVIEW : kind;
            scope = scope == null ? Scope.CONTRACT : scope;
            fromPath = trimmed(fromPath);
            toPath = trimmed(toPath);
            scenarioIds = scenarioIds == null ? List.of() : scenarioIds.stream()
                    .map(ContractCompatibilityReport::trimmed).distinct().sorted().toList();
            rationale = trimmed(rationale);
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
