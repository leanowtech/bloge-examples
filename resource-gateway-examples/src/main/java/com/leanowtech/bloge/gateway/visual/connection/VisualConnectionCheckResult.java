package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualRuntimeBindingRequirementKey;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Result of checking a proposed visual graph connection.
 *
 * @param accepted true when the proposed edge can be applied
 * @param edge normalized proposed edge
 * @param bindingKey storage key the canvas should use for a data/input binding
 * @param diagnostics schema, endpoint, or graph diagnostics for the proposed edge
 * @param validation full candidate draft validation/readiness after applying the preview connection
 * @param summary stable machine-readable decision summary for canvas controls and external control planes
 */
public record VisualConnectionCheckResult(
        boolean accepted,
        GraphDraft.DraftEdge edge,
        String bindingKey,
        List<VisualDiagnostic> diagnostics,
        VisualValidationResult validation,
        VisualConnectionCheckSummary summary
) {
    /**
     * Creates a connection check result.
     */
    public VisualConnectionCheckResult {
        bindingKey = bindingKey == null ? "" : bindingKey;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        validation = validation == null ? new VisualValidationResult(false, diagnostics) : validation;
        accepted = edge != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
        summary = summary == null
                ? VisualConnectionCheckSummary.from(accepted, edge, bindingKey, diagnostics, validation)
                : summary;
    }

    /**
     * Backward-compatible constructor for checks with candidate validation.
     */
    public VisualConnectionCheckResult(boolean accepted,
                                       GraphDraft.DraftEdge edge,
                                       String bindingKey,
                                       List<VisualDiagnostic> diagnostics,
                                       VisualValidationResult validation) {
        this(accepted, edge, bindingKey, diagnostics, validation, null);
    }

    /**
     * Backward-compatible constructor for checks that create input bindings.
     */
    public VisualConnectionCheckResult(boolean accepted,
                                       GraphDraft.DraftEdge edge,
                                       String bindingKey,
                                       List<VisualDiagnostic> diagnostics) {
        this(accepted, edge, bindingKey, diagnostics, new VisualValidationResult(false, diagnostics));
    }

    /**
     * Backward-compatible constructor for checks that do not create input bindings.
     */
    public VisualConnectionCheckResult(boolean accepted,
                                       GraphDraft.DraftEdge edge,
                                       List<VisualDiagnostic> diagnostics) {
        this(accepted, edge, "", diagnostics);
    }

    /**
     * Stable connection-check decision summary.
     *
     * @param schemaVersion summary contract version
     * @param accepted whether the proposed connection can be applied
     * @param kind canonical edge kind
     * @param source normalized source endpoint
     * @param target normalized target endpoint
     * @param bindingKey storage key the canvas should use for data/input bindings
     * @param createsBinding whether the proposal writes an input binding in addition to an edge
     * @param diagnosticCount connection-scoped diagnostic count
     * @param errorCount connection-scoped error count
     * @param warningCount connection-scoped warning count
     * @param infoCount connection-scoped info count
     * @param diagnosticCodeCounts connection-scoped diagnostic code counts
     * @param replacedBindingCount number of existing input bindings replaced by this accepted preview
     * @param replacedInputKeys existing target input binding keys replaced by this accepted preview
     * @param replacedEdgeCount number of existing data edges replaced by this accepted preview
     * @param replacedEdgeIds existing data edge ids replaced by this accepted preview
     * @param candidateValid whether the candidate draft is valid after applying the preview change
     * @param graphStillInvalid whether the connection is accepted but the resulting draft still has errors
     * @param validationDiagnosticCount full candidate validation diagnostic count
     * @param readinessState candidate graph readiness state
     * @param readinessLevel candidate graph readiness level
     * @param readinessExecutable whether the candidate graph is executable by the current runtime
     * @param runtimeBindingRequirementCount runtime binding requirement count in the candidate graph
     * @param runtimeBindingRequirementKeys preview-scoped stable runtime binding requirement keys
     * @param bindingKindCounts runtime binding requirement counts by binding kind
     * @param handoffLaneCounts runtime binding requirement counts by handoff lane
     * @param handoffKindCounts runtime binding requirement counts by handoff work kind
     * @param handoffTargetCounts runtime binding requirement counts by runtime-plane routing target
     * @param sourceKindCounts runtime binding requirement counts by operator source kind
     * @param operatorLibraryIdCounts runtime binding requirement counts by owner operator library id
     * @param loweringModeCounts runtime binding requirement counts by lowering mode
     * @param readinessStateCounts runtime binding requirement counts by node readiness state
     * @param message human-readable decision summary
     */
    public record VisualConnectionCheckSummary(
            String schemaVersion,
            boolean accepted,
            String kind,
            GraphDraft.Endpoint source,
            GraphDraft.Endpoint target,
            String bindingKey,
            boolean createsBinding,
            int diagnosticCount,
            int errorCount,
            int warningCount,
            int infoCount,
            Map<String, Integer> diagnosticCodeCounts,
            int replacedBindingCount,
            List<String> replacedInputKeys,
            int replacedEdgeCount,
            List<String> replacedEdgeIds,
            boolean candidateValid,
            boolean graphStillInvalid,
            int validationDiagnosticCount,
            String readinessState,
            String readinessLevel,
            boolean readinessExecutable,
            int runtimeBindingRequirementCount,
            List<String> runtimeBindingRequirementKeys,
            Map<String, Integer> bindingKindCounts,
            Map<String, Integer> handoffLaneCounts,
            Map<String, Integer> handoffKindCounts,
            Map<String, Integer> handoffTargetCounts,
            Map<String, Integer> sourceKindCounts,
            Map<String, Integer> operatorLibraryIdCounts,
            Map<String, Integer> loweringModeCounts,
            Map<String, Integer> readinessStateCounts,
            String message
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualConnectionCheckSummary.v1";

        /**
         * Creates a summary payload.
         */
        public VisualConnectionCheckSummary {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
            kind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
            source = source == null ? GraphDraft.Endpoint.empty() : source;
            target = target == null ? GraphDraft.Endpoint.empty() : target;
            bindingKey = bindingKey == null ? "" : bindingKey;
            diagnosticCodeCounts = diagnosticCodeCounts == null || diagnosticCodeCounts.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(diagnosticCodeCounts));
            replacedInputKeys = replacedInputKeys == null ? List.of() : List.copyOf(replacedInputKeys);
            replacedBindingCount = replacedInputKeys.isEmpty() ? Math.max(0, replacedBindingCount) : replacedInputKeys.size();
            replacedEdgeIds = replacedEdgeIds == null ? List.of() : List.copyOf(replacedEdgeIds);
            replacedEdgeCount = replacedEdgeIds.isEmpty() ? Math.max(0, replacedEdgeCount) : replacedEdgeIds.size();
            readinessState = readinessState == null ? "" : readinessState;
            readinessLevel = readinessLevel == null ? "" : readinessLevel;
            runtimeBindingRequirementKeys = runtimeBindingRequirementKeys == null
                    ? List.of()
                    : List.copyOf(runtimeBindingRequirementKeys);
            runtimeBindingRequirementCount = runtimeBindingRequirementKeys.isEmpty()
                    ? Math.max(0, runtimeBindingRequirementCount)
                    : runtimeBindingRequirementKeys.size();
            bindingKindCounts = immutableCounts(bindingKindCounts);
            handoffLaneCounts = immutableCounts(handoffLaneCounts);
            handoffKindCounts = immutableCounts(handoffKindCounts);
            handoffTargetCounts = immutableCounts(handoffTargetCounts);
            sourceKindCounts = immutableCounts(sourceKindCounts);
            operatorLibraryIdCounts = immutableCounts(operatorLibraryIdCounts);
            loweringModeCounts = immutableCounts(loweringModeCounts);
            readinessStateCounts = immutableCounts(readinessStateCounts);
            message = message == null ? "" : message;
        }

        private static VisualConnectionCheckSummary from(boolean accepted,
                                                         GraphDraft.DraftEdge edge,
                                                         String bindingKey,
                                                         List<VisualDiagnostic> diagnostics,
                                                         VisualValidationResult validation) {
            return from(accepted, edge, bindingKey, diagnostics, validation, List.of(), List.of());
        }

        static VisualConnectionCheckSummary from(boolean accepted,
                                                 GraphDraft.DraftEdge edge,
                                                 String bindingKey,
                                                 List<VisualDiagnostic> diagnostics,
                                                 VisualValidationResult validation,
                                                 List<String> replacedInputKeys,
                                                 List<String> replacedEdgeIds) {
            return from(accepted, edge, bindingKey, diagnostics, validation, replacedInputKeys, replacedEdgeIds,
                    Map.of());
        }

        static VisualConnectionCheckSummary from(boolean accepted,
                                                 GraphDraft.DraftEdge edge,
                                                 String bindingKey,
                                                 List<VisualDiagnostic> diagnostics,
                                                 VisualValidationResult validation,
                                                 List<String> replacedInputKeys,
                                                 List<String> replacedEdgeIds,
                                                 Map<String, String> operatorLibraryIdsByOperatorRef) {
            List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
            VisualValidationResult candidate = validation == null
                    ? new VisualValidationResult(false, safeDiagnostics)
                    : validation;
            int errors = 0;
            int warnings = 0;
            int infos = 0;
            Map<String, Integer> codeCounts = new LinkedHashMap<>();
            for (VisualDiagnostic diagnostic : safeDiagnostics) {
                String level = diagnostic.level() == null ? "" : diagnostic.level().trim().toUpperCase(Locale.ROOT);
                if ("ERROR".equals(level)) {
                    errors++;
                } else if ("WARNING".equals(level)) {
                    warnings++;
                } else {
                    infos++;
                }
                codeCounts.merge(diagnostic.code(), 1, Integer::sum);
            }
            boolean candidateValid = candidate.valid();
            boolean graphStillInvalid = accepted && !candidateValid;
            List<VisualGraphReadiness.RuntimeBindingRequirement> runtimeBindingRequirements =
                    runtimeBindingRequirements(candidate);
            List<String> runtimeBindingRequirementKeys = runtimeBindingRequirementKeys(runtimeBindingRequirements);
            return new VisualConnectionCheckSummary(
                    SCHEMA_VERSION,
                    accepted,
                    edge == null ? "" : edge.kind(),
                    edge == null ? GraphDraft.Endpoint.empty() : edge.source(),
                    edge == null ? GraphDraft.Endpoint.empty() : edge.target(),
                    bindingKey,
                    createsBinding(edge, bindingKey),
                    safeDiagnostics.size(),
                    errors,
                    warnings,
                    infos,
                    codeCounts,
                    replacedInputKeys == null ? 0 : replacedInputKeys.size(),
                    replacedInputKeys,
                    replacedEdgeIds == null ? 0 : replacedEdgeIds.size(),
                    replacedEdgeIds,
                    candidateValid,
                    graphStillInvalid,
                    candidate.diagnostics().size(),
                    candidate.readiness().state(),
                    candidate.readiness().level(),
                    candidate.readiness().executable(),
                    runtimeBindingRequirements.size(),
                    runtimeBindingRequirementKeys,
                    countBy(runtimeBindingRequirements, VisualGraphReadiness.RuntimeBindingRequirement::bindingKind),
                    countBy(runtimeBindingRequirements, VisualGraphReadiness.RuntimeBindingRequirement::handoffLane),
                    countBy(runtimeBindingRequirements, VisualGraphReadiness.RuntimeBindingRequirement::handoffKind),
                    countBy(runtimeBindingRequirements, VisualGraphReadiness.RuntimeBindingRequirement::handoffTarget),
                    countBy(runtimeBindingRequirements, VisualGraphReadiness.RuntimeBindingRequirement::sourceKind),
                    countBy(runtimeBindingRequirements,
                            requirement -> operatorLibraryId(requirement, operatorLibraryIdsByOperatorRef)),
                    countBy(runtimeBindingRequirements, VisualGraphReadiness.RuntimeBindingRequirement::loweringMode),
                    countBy(runtimeBindingRequirements, VisualGraphReadiness.RuntimeBindingRequirement::state),
                    summaryMessage(accepted, safeDiagnostics, graphStillInvalid, runtimeBindingRequirements.size())
            );
        }

        private static boolean createsBinding(GraphDraft.DraftEdge edge, String bindingKey) {
            return edge != null
                    && "data".equals(edge.kind())
                    && bindingKey != null
                    && !bindingKey.isBlank();
        }

        private static String summaryMessage(boolean accepted,
                                             List<VisualDiagnostic> diagnostics,
                                             boolean graphStillInvalid,
                                             int runtimeBindingRequirementCount) {
            boolean hasErrors = diagnostics.stream().anyMatch(VisualDiagnostic::error);
            boolean hasWarnings = diagnostics.stream()
                    .anyMatch(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()));
            if (!accepted || hasErrors) {
                return "Connection rejected by server.";
            }
            if (hasWarnings) {
                return "Connection accepted with diagnostics.";
            }
            if (graphStillInvalid) {
                return "Connection accepted; graph still has validation issues.";
            }
            if (runtimeBindingRequirementCount > 0) {
                return "Connection accepted; executable promotion needs runtime binding.";
            }
            return "Connection accepted.";
        }

        private static List<VisualGraphReadiness.RuntimeBindingRequirement> runtimeBindingRequirements(
                VisualValidationResult candidate) {
            if (candidate == null || candidate.readiness() == null
                    || candidate.readiness().runtimeBindingRequirements() == null) {
                return List.of();
            }
            return candidate.readiness().runtimeBindingRequirements().stream()
                    .filter(requirement -> requirement != null)
                    .toList();
        }

        private static List<String> runtimeBindingRequirementKeys(
                List<VisualGraphReadiness.RuntimeBindingRequirement> requirements) {
            return requirements == null ? List.of() : requirements.stream()
                    .map(requirement -> VisualRuntimeBindingRequirementKey.stable(
                            "connection-preview",
                            "",
                            requirement.nodeId(),
                            requirement.bindingKind(),
                            requirement.bindingTarget(),
                            ""))
                    .toList();
        }

        private static String operatorLibraryId(VisualGraphReadiness.RuntimeBindingRequirement requirement,
                                                Map<String, String> operatorLibraryIdsByOperatorRef) {
            if (requirement == null || operatorLibraryIdsByOperatorRef == null) {
                return "";
            }
            String value = operatorLibraryIdsByOperatorRef.get(requirement.operatorRef());
            return value == null ? "" : value;
        }

        private static Map<String, Integer> countBy(
                List<VisualGraphReadiness.RuntimeBindingRequirement> requirements,
                Function<VisualGraphReadiness.RuntimeBindingRequirement, String> classifier) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (VisualGraphReadiness.RuntimeBindingRequirement requirement
                    : requirements == null ? List.<VisualGraphReadiness.RuntimeBindingRequirement>of() : requirements) {
                String key = classifier.apply(requirement);
                if (key == null || key.isBlank()) {
                    continue;
                }
                counts.merge(key, 1, Integer::sum);
            }
            return immutableCounts(counts);
        }

        private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
            if (counts == null || counts.isEmpty()) {
                return Map.of();
            }
            Map<String, Integer> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                int count = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
                if (key.isBlank() || count == 0) {
                    continue;
                }
                normalized.put(key, count);
            }
            return normalized.isEmpty() ? Map.of() : Collections.unmodifiableMap(normalized);
        }
    }
}
