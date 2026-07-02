package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                    summaryMessage(accepted, safeDiagnostics, graphStillInvalid)
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
                                             boolean graphStillInvalid) {
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
            return "Connection accepted.";
        }
    }
}
