package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorExecutablePromotionProjection;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only preview for turning executor integration evidence into a candidate operator surface.
 *
 * <p>This is the gate before writing a trusted operator-library/catalog revision. It computes the
 * candidate lowering, fingerprint, and runtime readiness, but it does not persist anything.</p>
 *
 * @param schemaVersion response contract version
 * @param previewedAt server preview timestamp
 * @param recomputable true when a trusted revision could be created from this preview
 * @param state ready-to-apply, blocked, not-required, or missing
 * @param level UI/control-plane severity
 * @param message human-readable summary
 * @param operatorRef operator reference
 * @param operatorLibraryId imported operator library owner when present
 * @param currentOperatorFingerprint current catalog fingerprint
 * @param currentRuntimeReadinessState current trusted runtime readiness state
 * @param currentLoweringMode current trusted lowering mode
 * @param currentLoweringOperatorRef current trusted lowering operatorRef
 * @param activeBindingId active implementation binding id
 * @param activeAdapterActivationId active adapter activation id
 * @param activeExecutableLoweringIntegrationId active executable lowering integration id
 * @param candidateOperatorFingerprint candidate fingerprint after applying executable lowering
 * @param candidateRuntimeReadinessState candidate runtime readiness state
 * @param candidateLoweringMode candidate lowering mode
 * @param candidateLoweringOperatorRef candidate lowering operatorRef
 * @param candidateOperator candidate operator definition to be written by a later governed revision mutation
 * @param diagnostics structured preview diagnostics
 */
public record VisualExecutableReadinessRecomputePreview(
        String schemaVersion,
        Instant previewedAt,
        boolean recomputable,
        String state,
        String level,
        String message,
        String operatorRef,
        String operatorLibraryId,
        String currentOperatorFingerprint,
        String currentRuntimeReadinessState,
        String currentLoweringMode,
        String currentLoweringOperatorRef,
        String activeBindingId,
        String activeAdapterActivationId,
        String activeExecutableLoweringIntegrationId,
        String candidateOperatorFingerprint,
        String candidateRuntimeReadinessState,
        String candidateLoweringMode,
        String candidateLoweringOperatorRef,
        OperatorDefinition candidateOperator,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualExecutableReadinessRecomputePreview.v1";

    /**
     * Creates a normalized preview payload.
     */
    public VisualExecutableReadinessRecomputePreview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        previewedAt = previewedAt == null ? Instant.now() : previewedAt;
        state = state == null || state.isBlank() ? "blocked" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "info" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorLibraryId = operatorLibraryId == null ? "" : operatorLibraryId.trim();
        currentOperatorFingerprint = currentOperatorFingerprint == null ? "" : currentOperatorFingerprint.trim();
        currentRuntimeReadinessState = currentRuntimeReadinessState == null
                ? ""
                : currentRuntimeReadinessState.trim().toUpperCase(Locale.ROOT);
        currentLoweringMode = currentLoweringMode == null
                ? ""
                : currentLoweringMode.trim().toLowerCase(Locale.ROOT);
        currentLoweringOperatorRef = currentLoweringOperatorRef == null ? "" : currentLoweringOperatorRef.trim();
        activeBindingId = activeBindingId == null ? "" : activeBindingId.trim();
        activeAdapterActivationId = activeAdapterActivationId == null ? "" : activeAdapterActivationId.trim();
        activeExecutableLoweringIntegrationId = activeExecutableLoweringIntegrationId == null
                ? ""
                : activeExecutableLoweringIntegrationId.trim();
        candidateOperatorFingerprint = candidateOperatorFingerprint == null
                ? ""
                : candidateOperatorFingerprint.trim();
        candidateRuntimeReadinessState = candidateRuntimeReadinessState == null
                ? ""
                : candidateRuntimeReadinessState.trim().toUpperCase(Locale.ROOT);
        candidateLoweringMode = candidateLoweringMode == null
                ? ""
                : candidateLoweringMode.trim().toLowerCase(Locale.ROOT);
        candidateLoweringOperatorRef = candidateLoweringOperatorRef == null
                ? ""
                : candidateLoweringOperatorRef.trim();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        recomputable = recomputable && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    /**
     * Creates a bad request response when no operatorRef was supplied.
     *
     * @return rejected preview
     */
    public static VisualExecutableReadinessRecomputePreview missingOperatorRef() {
        return blocked(
                "",
                VisualDiagnostic.error(
                        "visual.executableReadinessRecompute.operatorRefMissing",
                        "Executable readiness recompute preview requires operatorRef.",
                        "/operatorRef")
        );
    }

    /**
     * Creates a not-found response when the operator is not visible.
     *
     * @param operatorRef requested operator ref
     * @return missing preview
     */
    public static VisualExecutableReadinessRecomputePreview missingOperator(String operatorRef) {
        return new VisualExecutableReadinessRecomputePreview(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                "missing",
                "error",
                "Operator '%s' is not visible in the current catalog.".formatted(safe(operatorRef)),
                safe(operatorRef),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                List.of(VisualDiagnostic.error(
                        "visual.executableReadinessRecompute.operatorMissing",
                        "Operator '%s' is not visible in the current catalog.".formatted(safe(operatorRef)),
                        "/operatorRef",
                        Map.of("operatorRef", safe(operatorRef))))
        );
    }

    /**
     * Computes a candidate executable operator surface from a promotion projection.
     *
     * @param currentOperator current trusted operator definition
     * @param promotionProjection current executable promotion projection
     * @return recompute preview
     */
    public static VisualExecutableReadinessRecomputePreview from(
            OperatorDefinition currentOperator,
            OperatorExecutablePromotionProjection promotionProjection) {
        if (currentOperator == null) {
            return missingOperator("");
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        String operatorRef = currentOperator.operatorRef();
        if (promotionProjection == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.promotionProjectionMissing",
                    "Executable readiness recompute requires a current executable promotion projection.",
                    "/operators/" + operatorRef + "/executableReadinessRecompute"));
            return blocked(currentOperator, null, diagnostics);
        }
        if (!currentOperator.fingerprint().equals(promotionProjection.operatorFingerprint())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.operatorFingerprintMismatch",
                    "Promotion projection fingerprint '%s' does not match current operator fingerprint '%s'."
                            .formatted(promotionProjection.operatorFingerprint(), currentOperator.fingerprint()),
                    "/operators/" + operatorRef + "/operatorFingerprint",
                    Map.of(
                            "projectionFingerprint", promotionProjection.operatorFingerprint(),
                            "currentOperatorFingerprint", currentOperator.fingerprint()
                    )));
            return blocked(currentOperator, promotionProjection, diagnostics);
        }
        if (!"readiness-recompute-required".equals(promotionProjection.promotionState())) {
            if ("already-executable".equals(promotionProjection.promotionState())) {
                return new VisualExecutableReadinessRecomputePreview(
                        SCHEMA_VERSION,
                        Instant.now(),
                        false,
                        "not-required",
                        "success",
                        "Operator '%s' is already executable; no readiness recompute is required."
                                .formatted(operatorRef),
                        operatorRef,
                        currentOperator.source().libraryId(),
                        currentOperator.fingerprint(),
                        currentReadinessState(currentOperator),
                        currentOperator.lowering().mode(),
                        currentOperator.lowering().operatorRef(),
                        promotionProjection.activeBindingId(),
                        promotionProjection.activeAdapterActivationId(),
                        promotionProjection.activeExecutableLoweringIntegrationId(),
                        "",
                        "",
                        "",
                        "",
                        null,
                        List.of()
                );
            }
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.promotionStateNotReady",
                    "Operator '%s' cannot recompute readiness from promotion state '%s'."
                            .formatted(operatorRef, promotionProjection.promotionState()),
                    "/operators/" + operatorRef + "/executablePromotionProjection",
                    Map.of("promotionState", promotionProjection.promotionState())));
            return blocked(currentOperator, promotionProjection, diagnostics);
        }
        if (!"native".equals(promotionProjection.executableLoweringMode())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.loweringModeUnsupported",
                    "Executable readiness recompute preview currently supports loweringMode=native; got '%s'."
                            .formatted(promotionProjection.executableLoweringMode()),
                    "/operators/" + operatorRef + "/executableLoweringMode",
                    Map.of("loweringMode", promotionProjection.executableLoweringMode())));
            return blocked(currentOperator, promotionProjection, diagnostics);
        }
        if (promotionProjection.executorEntrypoint().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.executorEntrypointMissing",
                    "Executable readiness recompute requires executorEntrypoint.",
                    "/operators/" + operatorRef + "/executorEntrypoint"));
            return blocked(currentOperator, promotionProjection, diagnostics);
        }
        OperatorDefinition candidate = candidateOperator(currentOperator, promotionProjection);
        if (candidate.runtimeReadiness() == null || !candidate.runtimeReadiness().executable()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.candidateNotExecutable",
                    "Candidate operator for '%s' still does not derive executable runtime readiness."
                            .formatted(operatorRef),
                    "/operators/" + operatorRef + "/candidateOperator/runtimeReadiness",
                    Map.of("candidateRuntimeReadinessState", currentReadinessState(candidate))));
            return blocked(currentOperator, promotionProjection, diagnostics, candidate);
        }
        String level = "success".equals(candidate.runtimeReadiness().level())
                ? "success"
                : "warning";
        return new VisualExecutableReadinessRecomputePreview(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                "ready-to-apply",
                level,
                "Executable readiness recompute preview for '%s' is ready to become a governed catalog/library revision."
                        .formatted(operatorRef),
                operatorRef,
                currentOperator.source().libraryId(),
                currentOperator.fingerprint(),
                currentReadinessState(currentOperator),
                currentOperator.lowering().mode(),
                currentOperator.lowering().operatorRef(),
                promotionProjection.activeBindingId(),
                promotionProjection.activeAdapterActivationId(),
                promotionProjection.activeExecutableLoweringIntegrationId(),
                candidate.fingerprint(),
                currentReadinessState(candidate),
                candidate.lowering().mode(),
                candidate.lowering().operatorRef(),
                candidate,
                diagnostics
        );
    }

    private static VisualExecutableReadinessRecomputePreview blocked(String operatorRef,
                                                                     VisualDiagnostic diagnostic) {
        return new VisualExecutableReadinessRecomputePreview(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                "blocked",
                "error",
                diagnostic.message(),
                safe(operatorRef),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                List.of(diagnostic)
        );
    }

    private static VisualExecutableReadinessRecomputePreview blocked(
            OperatorDefinition currentOperator,
            OperatorExecutablePromotionProjection promotionProjection,
            List<VisualDiagnostic> diagnostics) {
        return blocked(currentOperator, promotionProjection, diagnostics, null);
    }

    private static VisualExecutableReadinessRecomputePreview blocked(
            OperatorDefinition currentOperator,
            OperatorExecutablePromotionProjection promotionProjection,
            List<VisualDiagnostic> diagnostics,
            OperatorDefinition candidate) {
        String operatorRef = currentOperator == null ? "" : currentOperator.operatorRef();
        String candidateState = currentReadinessState(candidate);
        return new VisualExecutableReadinessRecomputePreview(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                "blocked",
                "error",
                "Executable readiness recompute preview for '%s' is blocked by %d diagnostic(s)."
                        .formatted(operatorRef, diagnostics == null ? 0 : diagnostics.size()),
                operatorRef,
                currentOperator == null ? "" : currentOperator.source().libraryId(),
                currentOperator == null ? "" : currentOperator.fingerprint(),
                currentReadinessState(currentOperator),
                currentOperator == null ? "" : currentOperator.lowering().mode(),
                currentOperator == null ? "" : currentOperator.lowering().operatorRef(),
                promotionProjection == null ? "" : promotionProjection.activeBindingId(),
                promotionProjection == null ? "" : promotionProjection.activeAdapterActivationId(),
                promotionProjection == null ? "" : promotionProjection.activeExecutableLoweringIntegrationId(),
                candidate == null ? "" : candidate.fingerprint(),
                candidateState,
                candidate == null ? "" : candidate.lowering().mode(),
                candidate == null ? "" : candidate.lowering().operatorRef(),
                candidate,
                diagnostics == null ? List.of() : diagnostics
        );
    }

    private static OperatorDefinition candidateOperator(OperatorDefinition currentOperator,
                                                        OperatorExecutablePromotionProjection projection) {
        return new OperatorDefinition(
                currentOperator.schemaVersion(),
                currentOperator.operatorRef(),
                currentOperator.operatorVersion(),
                "",
                currentOperator.display(),
                currentOperator.source(),
                currentOperator.ports(),
                currentOperator.configSchema(),
                currentOperator.capabilities(),
                currentOperator.policy(),
                new OperatorDefinition.Lowering(
                        projection.executableLoweringMode(),
                        projection.executorEntrypoint(),
                        candidateLoweringParameters(projection)
                ),
                currentOperator.diagnostics()
        );
    }

    private static Map<String, Object> candidateLoweringParameters(
            OperatorExecutablePromotionProjection projection) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("runtimeBindingId", projection.activeBindingId());
        parameters.put("adapterActivationId", projection.activeAdapterActivationId());
        parameters.put("executableLoweringIntegrationId", projection.activeExecutableLoweringIntegrationId());
        parameters.put("adapterKind", projection.adapterKind());
        parameters.put("adapterEntrypoint", projection.entrypoint());
        parameters.put("runtimeEnvironment", projection.runtimeEnvironment());
        parameters.put("executorKind", projection.executorKind());
        parameters.put("executorEntrypoint", projection.executorEntrypoint());
        parameters.put("integrationRevision", projection.activeExecutableLoweringIntegrationRevision());
        return parameters;
    }

    private static String currentReadinessState(OperatorDefinition operator) {
        if (operator == null || operator.runtimeReadiness() == null) {
            return "";
        }
        return operator.runtimeReadiness().state();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
