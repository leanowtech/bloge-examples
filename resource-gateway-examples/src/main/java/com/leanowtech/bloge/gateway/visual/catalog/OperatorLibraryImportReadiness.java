package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualRuntimeBindingRequirementKey;
import com.leanowtech.bloge.gateway.visual.validation.VisualRuntimeBindingRequirementPlanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-derived import readiness summary for a user-provided operator library.
 *
 * <p>The detailed {@link OperatorLibraryProfile} still describes the submitted
 * catalog surface. This summary is the control-plane decision layer: clients can
 * route review, acknowledgement, force-import, and design-only authoring without
 * reverse-engineering profile counters or diagnostic text.</p>
 *
 * @param schemaVersion readiness contract version
 * @param valid whether the validation result has no blocking errors
 * @param importableNow true when the library can be stored without additional acknowledgement
 * @param importableAfterReview true when the remaining blockers are review actions such as force/evidence/ack
 * @param state stable readiness state
 * @param level UI/control-plane severity
 * @param operatorCount submitted non-null operator count
 * @param runtimeExecutableOperatorCount request-response executable operator count
 * @param designOnlyOperatorCount schema/design-only operator count
 * @param runtimeBlockedOperatorCount operator count blocked by current runtime binding
 * @param governanceReviewOperatorCount operator count requiring governance review
 * @param catalogRepairOperatorCount operator count requiring catalog repair
 * @param diagnosticCount total diagnostics
 * @param errorCount blocking diagnostic count
 * @param warningCount warning diagnostic count
 * @param requiresAckWarnings true when warning acknowledgement is required before storing
 * @param requiresForce true when replacement impact requires force=true before storing
 * @param requiresGovernanceEvidence true when actor/reason evidence is required before storing
 * @param affectedDraftCount stored draft count affected by this import decision
 * @param affectedPublicationCount immutable publication count affected by this import decision
 * @param affectedOperatorCount operatorRef count affected by this import decision
 * @param changeRiskCount number of categorized schema/runtime/governance change risks
 * @param blockingCodes unique blocking diagnostic codes
 * @param warningCodes unique warning diagnostic codes
 * @param message human-readable decision summary
 * @param recommendedAction concise next action for UI or automation
 * @param runtimeBindingRequirementCount runtime binding requirement count for submitted operators
 * @param runtimeBindingRequirementKeys stable keys aligned with runtimeBindingRequirements
 * @param runtimeBindingRequirements per-operator runtime binding requirements before executable use
 */
public record OperatorLibraryImportReadiness(
        String schemaVersion,
        boolean valid,
        boolean importableNow,
        boolean importableAfterReview,
        String state,
        String level,
        int operatorCount,
        int runtimeExecutableOperatorCount,
        int designOnlyOperatorCount,
        int runtimeBlockedOperatorCount,
        int governanceReviewOperatorCount,
        int catalogRepairOperatorCount,
        int diagnosticCount,
        int errorCount,
        int warningCount,
        boolean requiresAckWarnings,
        boolean requiresForce,
        boolean requiresGovernanceEvidence,
        int affectedDraftCount,
        int affectedPublicationCount,
        int affectedOperatorCount,
        int changeRiskCount,
        List<String> blockingCodes,
        List<String> warningCodes,
        String message,
        String recommendedAction,
        int runtimeBindingRequirementCount,
        List<String> runtimeBindingRequirementKeys,
        List<RuntimeBindingRequirement> runtimeBindingRequirements
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorLibraryImportReadiness.v1";

    public static final String STATE_RUNTIME_EXECUTABLE_IMPORTABLE = "runtime-executable-importable";
    public static final String STATE_DESIGN_ONLY_IMPORTABLE = "design-only-importable";
    public static final String STATE_MIXED_IMPORTABLE = "mixed-importable";
    public static final String STATE_RUNTIME_BINDING_REQUIRED = "runtime-binding-required";
    public static final String STATE_GOVERNANCE_REVIEW_REQUIRED = "governance-review-required";
    public static final String STATE_WARNING_ACK_REQUIRED = "warning-ack-required";
    public static final String STATE_FORCE_REQUIRED = "force-required";
    public static final String STATE_GOVERNANCE_EVIDENCE_REQUIRED = "governance-evidence-required";
    public static final String STATE_CATALOG_REPAIR_REQUIRED = "catalog-repair-required";

    private static final Set<String> FORCE_REQUIRED_CODES = Set.of(
            "visual.library.inUse",
            "visual.library.publicationInUse"
    );
    private static final Set<String> GOVERNANCE_EVIDENCE_CODES = Set.of(
            "visual.library.governanceEvidenceMissing"
    );

    /**
     * Creates a normalized readiness summary.
     */
    public OperatorLibraryImportReadiness {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        state = state == null || state.isBlank()
                ? STATE_CATALOG_REPAIR_REQUIRED
                : state.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        level = level == null || level.isBlank()
                ? "info"
                : level.trim().toLowerCase(Locale.ROOT);
        operatorCount = Math.max(0, operatorCount);
        runtimeExecutableOperatorCount = Math.max(0, runtimeExecutableOperatorCount);
        designOnlyOperatorCount = Math.max(0, designOnlyOperatorCount);
        runtimeBlockedOperatorCount = Math.max(0, runtimeBlockedOperatorCount);
        governanceReviewOperatorCount = Math.max(0, governanceReviewOperatorCount);
        catalogRepairOperatorCount = Math.max(0, catalogRepairOperatorCount);
        diagnosticCount = Math.max(0, diagnosticCount);
        errorCount = Math.max(0, errorCount);
        warningCount = Math.max(0, warningCount);
        affectedDraftCount = Math.max(0, affectedDraftCount);
        affectedPublicationCount = Math.max(0, affectedPublicationCount);
        affectedOperatorCount = Math.max(0, affectedOperatorCount);
        changeRiskCount = Math.max(0, changeRiskCount);
        blockingCodes = immutableCodeList(blockingCodes);
        warningCodes = immutableCodeList(warningCodes);
        message = message == null ? "" : message;
        recommendedAction = recommendedAction == null ? "" : recommendedAction;
        runtimeBindingRequirements = immutableRuntimeBindingRequirements(runtimeBindingRequirements);
        runtimeBindingRequirementKeys = runtimeBindingRequirementKeys == null
                ? runtimeBindingRequirements.stream()
                .map(RuntimeBindingRequirement::requirementKey)
                .toList()
                : immutableStringList(runtimeBindingRequirementKeys);
        runtimeBindingRequirementCount = runtimeBindingRequirements.size();
    }

    /**
     * @return empty readiness summary
     */
    public static OperatorLibraryImportReadiness empty() {
        return from(false, List.of(), OperatorLibraryImpactReview.empty(), OperatorLibraryProfile.empty());
    }

    /**
     * Builds readiness from the same evidence used by validation responses.
     *
     * @param valid validation validity
     * @param diagnostics validation diagnostics
     * @param impact impact review
     * @param profile server-derived operator-library profile
     * @return import readiness summary
     */
    public static OperatorLibraryImportReadiness from(boolean valid,
                                                      List<VisualDiagnostic> diagnostics,
                                                      OperatorLibraryImpactReview impact,
                                                      OperatorLibraryProfile profile) {
        return from(valid, diagnostics, impact, profile, null);
    }

    /**
     * Builds readiness from the same evidence used by validation responses.
     *
     * @param valid validation validity
     * @param diagnostics validation diagnostics
     * @param impact impact review
     * @param profile server-derived operator-library profile
     * @param library submitted library snapshot used to derive per-operator binding targets
     * @return import readiness summary
     */
    public static OperatorLibraryImportReadiness from(boolean valid,
                                                      List<VisualDiagnostic> diagnostics,
                                                      OperatorLibraryImpactReview impact,
                                                      OperatorLibraryProfile profile,
                                                      OperatorLibrary library) {
        List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics.stream()
                .filter(diagnostic -> diagnostic != null)
                .toList();
        OperatorLibraryImpactReview safeImpact = impact == null ? OperatorLibraryImpactReview.empty() : impact;
        OperatorLibraryProfile safeProfile = profile == null ? OperatorLibraryProfile.empty() : profile;
        List<RuntimeBindingRequirement> runtimeBindingRequirements =
                runtimeBindingRequirements(library, safeProfile);
        int errors = 0;
        int warnings = 0;
        Set<String> blockingCodes = new LinkedHashSet<>();
        Set<String> warningCodes = new LinkedHashSet<>();
        for (VisualDiagnostic diagnostic : safeDiagnostics) {
            String level = diagnostic.level() == null ? "" : diagnostic.level().toUpperCase(Locale.ROOT);
            if ("ERROR".equals(level)) {
                errors++;
                blockingCodes.add(diagnostic.code());
            } else if ("WARNING".equals(level)) {
                warnings++;
                warningCodes.add(diagnostic.code());
            }
        }

        boolean requiresForce = blockingCodes.stream().anyMatch(FORCE_REQUIRED_CODES::contains);
        boolean requiresAckWarnings = warnings > 0;
        boolean governanceEvidenceMissing = blockingCodes.stream().anyMatch(GOVERNANCE_EVIDENCE_CODES::contains);
        boolean requiresGovernanceEvidence = governanceEvidenceMissing || requiresForce || requiresAckWarnings;
        boolean hasCatalogRepairError = errors > 0 && blockingCodes.stream()
                .anyMatch(code -> !FORCE_REQUIRED_CODES.contains(code) && !GOVERNANCE_EVIDENCE_CODES.contains(code));
        boolean importableAfterReview = !hasCatalogRepairError;
        boolean importableNow = errors == 0 && !requiresAckWarnings;

        String state = readinessState(
                safeProfile,
                errors,
                warnings,
                hasCatalogRepairError,
                requiresForce,
                governanceEvidenceMissing
        );
        String level = readinessLevel(state, errors, warnings);
        String message = readinessMessage(state);
        String recommendedAction = recommendedAction(state);
        return new OperatorLibraryImportReadiness(
                SCHEMA_VERSION,
                valid && errors == 0,
                importableNow,
                importableAfterReview,
                state,
                level,
                safeProfile.operatorCount(),
                safeProfile.runtimeExecutableOperatorCount(),
                safeProfile.designOnlyOperatorCount(),
                safeProfile.runtimeBlockedOperatorCount(),
                safeProfile.governanceReviewOperatorCount(),
                safeProfile.catalogRepairOperatorCount(),
                safeDiagnostics.size(),
                errors,
                warnings,
                requiresAckWarnings,
                requiresForce,
                requiresGovernanceEvidence,
                safeImpact.draftIds().size(),
                safeImpact.publicationIds().size(),
                safeImpact.operatorRefs().size(),
                safeImpact.changeRiskCounts().stream()
                        .mapToInt(OperatorLibraryImpactReview.ChangeRiskCount::count)
                        .sum(),
                List.copyOf(blockingCodes),
                List.copyOf(warningCodes),
                message,
                recommendedAction,
                runtimeBindingRequirements.size(),
                runtimeBindingRequirements.stream()
                        .map(RuntimeBindingRequirement::requirementKey)
                        .toList(),
                runtimeBindingRequirements
        );
    }

    private static String readinessState(OperatorLibraryProfile profile,
                                         int errors,
                                         int warnings,
                                         boolean hasCatalogRepairError,
                                         boolean requiresForce,
                                         boolean governanceEvidenceMissing) {
        if (hasCatalogRepairError || profile.catalogRepairOperatorCount() > 0) {
            return STATE_CATALOG_REPAIR_REQUIRED;
        }
        if (governanceEvidenceMissing) {
            return STATE_GOVERNANCE_EVIDENCE_REQUIRED;
        }
        if (requiresForce) {
            return STATE_FORCE_REQUIRED;
        }
        if (warnings > 0 && profile.governanceReviewOperatorCount() > 0) {
            return STATE_GOVERNANCE_REVIEW_REQUIRED;
        }
        if (warnings > 0 && profile.runtimeBlockedOperatorCount() > 0) {
            return STATE_RUNTIME_BINDING_REQUIRED;
        }
        if (warnings > 0 || errors > 0) {
            return STATE_WARNING_ACK_REQUIRED;
        }
        if (profile.governanceReviewOperatorCount() > 0) {
            return STATE_GOVERNANCE_REVIEW_REQUIRED;
        }
        if (profile.runtimeBlockedOperatorCount() > 0) {
            return STATE_RUNTIME_BINDING_REQUIRED;
        }
        if (profile.operatorCount() > 0 && profile.designOnlyOperatorCount() == profile.operatorCount()) {
            return STATE_DESIGN_ONLY_IMPORTABLE;
        }
        if (profile.designOnlyOperatorCount() > 0
                || profile.runtimeBlockedOperatorCount() > 0
                || profile.governanceReviewOperatorCount() > 0) {
            return STATE_MIXED_IMPORTABLE;
        }
        return STATE_RUNTIME_EXECUTABLE_IMPORTABLE;
    }

    private static String readinessLevel(String state, int errors, int warnings) {
        if (errors > 0 || STATE_CATALOG_REPAIR_REQUIRED.equals(state)
                || STATE_FORCE_REQUIRED.equals(state)
                || STATE_GOVERNANCE_EVIDENCE_REQUIRED.equals(state)) {
            return "error";
        }
        if (warnings > 0 || STATE_RUNTIME_BINDING_REQUIRED.equals(state)
                || STATE_GOVERNANCE_REVIEW_REQUIRED.equals(state)
                || STATE_WARNING_ACK_REQUIRED.equals(state)) {
            return "warning";
        }
        if (STATE_DESIGN_ONLY_IMPORTABLE.equals(state) || STATE_MIXED_IMPORTABLE.equals(state)) {
            return "info";
        }
        return "success";
    }

    private static String readinessMessage(String state) {
        return switch (state) {
            case STATE_CATALOG_REPAIR_REQUIRED ->
                    "Fix blocking catalog errors before this operator library can be imported.";
            case STATE_GOVERNANCE_EVIDENCE_REQUIRED ->
                    "Provide actor and reason evidence for this high-risk operator-library mutation.";
            case STATE_FORCE_REQUIRED ->
                    "Existing drafts or publications are affected; review impact and rerun with force=true.";
            case STATE_GOVERNANCE_REVIEW_REQUIRED ->
                    "Governance review is required before this operator library should be promoted.";
            case STATE_RUNTIME_BINDING_REQUIRED ->
                    "The library can support authoring, but runtime binding is incomplete for executable graphs.";
            case STATE_WARNING_ACK_REQUIRED ->
                    "Review warnings and acknowledge them before storing this operator library.";
            case STATE_DESIGN_ONLY_IMPORTABLE ->
                    "Schema-only library is ready for design-time authoring and DESIGN publications.";
            case STATE_MIXED_IMPORTABLE ->
                    "Mixed-readiness library is importable; executable use depends on each operator readiness.";
            default ->
                    "Operator library is ready for executable visual authoring.";
        };
    }

    private static String recommendedAction(String state) {
        return switch (state) {
            case STATE_CATALOG_REPAIR_REQUIRED -> "Repair the operator-library contract and validate again.";
            case STATE_GOVERNANCE_EVIDENCE_REQUIRED -> "Retry with non-empty actor and reason.";
            case STATE_FORCE_REQUIRED -> "Review affected assets, then retry with force=true, actor, and reason.";
            case STATE_GOVERNANCE_REVIEW_REQUIRED ->
                    "Complete governance review, then retry with ackWarnings=true, actor, and reason.";
            case STATE_RUNTIME_BINDING_REQUIRED ->
                    "Import for design work or bind the missing runtime before executable publication.";
            case STATE_WARNING_ACK_REQUIRED ->
                    "Review warnings, then retry with ackWarnings=true, actor, and reason.";
            case STATE_DESIGN_ONLY_IMPORTABLE ->
                    "Import and publish as DESIGN until executable lowering is provided.";
            case STATE_MIXED_IMPORTABLE -> "Import and route each operator by its runtime readiness.";
            default -> "Import normally.";
        };
    }

    private static List<String> immutableCodeList(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList());
    }

    private static List<RuntimeBindingRequirement> runtimeBindingRequirements(OperatorLibrary library,
                                                                              OperatorLibraryProfile profile) {
        if (library == null || library.operators().isEmpty()) {
            return List.of();
        }
        Map<String, OperatorLibraryProfile.OperatorProfile> profilesByOperatorRef = new LinkedHashMap<>();
        for (OperatorLibraryProfile.OperatorProfile operatorProfile : profile.operators()) {
            if (operatorProfile != null && !operatorProfile.operatorRef().isBlank()) {
                profilesByOperatorRef.put(operatorProfile.operatorRef(), operatorProfile);
            }
        }
        List<RuntimeBindingRequirement> requirements = new ArrayList<>();
        for (OperatorDefinition operator : library.operators()) {
            if (operator == null || operator.operatorRef().isBlank()) {
                continue;
            }
            OperatorLibraryProfile.OperatorProfile operatorProfile = profilesByOperatorRef.get(operator.operatorRef());
            requirements.addAll(RuntimeBindingRequirement.from(library.libraryId(), operator, operatorProfile));
        }
        return List.copyOf(requirements);
    }

    private static List<RuntimeBindingRequirement> immutableRuntimeBindingRequirements(
            List<RuntimeBindingRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(requirements.stream()
                .filter(requirement -> requirement != null)
                .toList());
    }

    private static List<String> immutableStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(values.stream()
                .map(value -> value == null ? "" : value)
                .toList());
    }

    /**
     * Runtime binding required for an imported operator before executable graph use.
     *
     * @param requirementKey stable import-time binding requirement key
     * @param operatorRef operator reference that needs runtime binding
     * @param label display label
     * @param state readiness state that produced the requirement
     * @param level UI/control-plane severity
     * @param sourceKind operator source kind
     * @param loweringMode requested lowering mode
     * @param bindingKind stable machine-readable binding kind
     * @param bindingTarget topic/tool/channel/path/operator target when declared
     * @param handoffLane runtime-plane responsibility lane
     * @param handoffKind runtime-plane work kind
     * @param handoffTarget runtime-plane routing target
     * @param title short display title
     * @param summary human-readable binding gap summary
     * @param recommendedAction human-readable next action
     */
    public record RuntimeBindingRequirement(
            String requirementKey,
            String operatorRef,
            String label,
            String state,
            String level,
            String sourceKind,
            String loweringMode,
            String bindingKind,
            String bindingTarget,
            String handoffLane,
            String handoffKind,
            String handoffTarget,
            String title,
            String summary,
            String recommendedAction
    ) {
        public RuntimeBindingRequirement {
            operatorRef = operatorRef == null ? "" : operatorRef;
            label = label == null ? "" : label;
            state = normalizeFacetValue(state);
            level = level == null || level.isBlank() ? "warning" : level.trim().toLowerCase(Locale.ROOT);
            sourceKind = normalizeFacetValue(sourceKind);
            loweringMode = normalizeFacetValue(loweringMode);
            bindingKind = normalizeFacetValue(bindingKind);
            bindingTarget = bindingTarget == null ? "" : bindingTarget;
            requirementKey = requirementKey == null || requirementKey.isBlank()
                    ? VisualRuntimeBindingRequirementKey.stable(
                    "operator-library",
                    "",
                    operatorRef,
                    bindingKind,
                    bindingTarget,
                    "")
                    : requirementKey;
            handoffLane = normalizeFacetValue(handoffLane);
            handoffKind = normalizeFacetValue(handoffKind);
            handoffTarget = handoffTarget == null ? "" : handoffTarget;
            title = title == null ? "" : title;
            summary = summary == null ? "" : summary;
            recommendedAction = recommendedAction == null ? "" : recommendedAction;
        }

        private static List<RuntimeBindingRequirement> from(
                String libraryId,
                OperatorDefinition operator,
                OperatorLibraryProfile.OperatorProfile operatorProfile) {
            String state = operatorProfile == null
                    ? normalizeFacetValue(operator.runtimeReadiness().state())
                    : operatorProfile.runtimeReadinessState();
            String level = operatorProfile == null
                    ? operator.runtimeReadiness().level()
                    : operatorProfile.runtimeReadinessLevel();
            String fallbackSummary = operatorProfile == null ? "" : operatorProfile.runtimeReadinessSummary();
            return VisualRuntimeBindingRequirementPlanner.from(operator, state, level, fallbackSummary).stream()
                    .map(requirement -> requirement(libraryId, requirement, importRecommendedAction(requirement)))
                    .toList();
        }

        private static RuntimeBindingRequirement requirement(
                String libraryId,
                VisualRuntimeBindingRequirementPlanner.OperatorRequirement requirement,
                String recommendedAction) {
            return new RuntimeBindingRequirement(
                    VisualRuntimeBindingRequirementKey.stable(
                            "operator-library",
                            libraryId,
                            requirement.operatorRef(),
                            requirement.bindingKind(),
                            requirement.bindingTarget(),
                            ""),
                    requirement.operatorRef(),
                    requirement.label(),
                    requirement.state(),
                    requirement.level(),
                    requirement.sourceKind(),
                    requirement.loweringMode(),
                    requirement.bindingKind(),
                    requirement.bindingTarget(),
                    requirement.handoffLane(),
                    requirement.handoffKind(),
                    requirement.handoffTarget(),
                    requirement.title(),
                    requirement.summary(),
                    recommendedAction
            );
        }

        private static String importRecommendedAction(
                VisualRuntimeBindingRequirementPlanner.OperatorRequirement requirement) {
            return switch (requirement.bindingKind()) {
                case "executable-lowering" ->
                        "Bind a native/resource/subgraph lowering before using this operator in EXECUTABLE graphs.";
                case "remote-worker-runtime" ->
                        "Bind worker dispatch for this topic before EXECUTABLE graph publication.";
                case "ai-tool-runtime" ->
                        "Bind tool invocation for this toolRef before EXECUTABLE graph publication.";
                case "event-source-runtime" ->
                        "Bind event subscription for this event type before EXECUTABLE graph publication.";
                case "message-runtime" ->
                        "Bind message consumption for this channel before EXECUTABLE graph publication.";
                case "webhook-ingress-runtime" ->
                        "Bind webhook ingress for this endpoint before EXECUTABLE graph publication.";
                case "streaming-runtime" -> "Bind a streaming runtime before EXECUTABLE graph publication.";
                case "durable-runtime" -> "Bind a durable runtime before EXECUTABLE graph publication.";
                default -> "Bind the missing runtime adapter before EXECUTABLE graph publication.";
            };
        }
    }

    private static String normalizeFacetValue(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }
}
