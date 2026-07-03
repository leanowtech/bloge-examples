package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Result contract for post-apply runtime evidence refresh.
 *
 * <p>The refresh creates a new bound implementation, adapter activation, and executable
 * lowering integration evidence chain against the current executable operator fingerprint.
 * It keeps the old facts for audit instead of mutating them in place.</p>
 *
 * @param schemaVersion response contract version
 * @param refreshedAt server mutation timestamp
 * @param refreshed true when a new evidence chain was created
 * @param state refreshed, current, failed, ack-required, rejected, blocked, or missing
 * @param level UI/control-plane severity
 * @param message human-readable summary
 * @param operatorRef refreshed operator reference
 * @param previousOperatorFingerprint previous evidence fingerprint
 * @param currentOperatorFingerprint current trusted operator fingerprint
 * @param changeRisk highest-risk surface change category
 * @param changeCategories all surface change categories observed
 * @param changeSummary human-readable surface change summary
 * @param sourceBinding previous binding, usually superseded by this operation
 * @param refreshedBinding new bound binding against the current operator contract
 * @param sourceActivation previous adapter activation evidence
 * @param refreshedActivation new adapter activation evidence
 * @param sourceIntegration previous executable lowering integration evidence
 * @param refreshedIntegration new executable lowering integration evidence
 * @param diagnostics structured diagnostics
 */
public record VisualExecutableReadinessEvidenceRefreshResult(
        String schemaVersion,
        Instant refreshedAt,
        boolean refreshed,
        String state,
        String level,
        String message,
        String operatorRef,
        String previousOperatorFingerprint,
        String currentOperatorFingerprint,
        String changeRisk,
        List<String> changeCategories,
        String changeSummary,
        VisualRuntimeBindingImplementationBinding sourceBinding,
        VisualRuntimeBindingImplementationBinding refreshedBinding,
        VisualRuntimeAdapterActivation sourceActivation,
        VisualRuntimeAdapterActivation refreshedActivation,
        VisualExecutableLoweringIntegration sourceIntegration,
        VisualExecutableLoweringIntegration refreshedIntegration,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualExecutableReadinessEvidenceRefreshResult.v1";

    public VisualExecutableReadinessEvidenceRefreshResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        refreshedAt = refreshedAt == null ? Instant.now() : refreshedAt;
        state = state == null || state.isBlank() ? "rejected" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "error" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        previousOperatorFingerprint = previousOperatorFingerprint == null ? "" : previousOperatorFingerprint.trim();
        currentOperatorFingerprint = currentOperatorFingerprint == null ? "" : currentOperatorFingerprint.trim();
        changeRisk = changeRisk == null ? "" : changeRisk.trim();
        changeCategories = changeCategories == null ? List.of() : List.copyOf(changeCategories);
        changeSummary = changeSummary == null ? "" : changeSummary;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        refreshed = refreshed && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    public static VisualExecutableReadinessEvidenceRefreshResult refreshed(
            String operatorRef,
            String previousOperatorFingerprint,
            String currentOperatorFingerprint,
            String changeRisk,
            List<String> changeCategories,
            String changeSummary,
            VisualRuntimeBindingImplementationBinding sourceBinding,
            VisualRuntimeBindingImplementationBinding refreshedBinding,
            VisualRuntimeAdapterActivation sourceActivation,
            VisualRuntimeAdapterActivation refreshedActivation,
            VisualExecutableLoweringIntegration sourceIntegration,
            VisualExecutableLoweringIntegration refreshedIntegration) {
        return new VisualExecutableReadinessEvidenceRefreshResult(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                "refreshed",
                "success",
                "Executable readiness evidence for '%s' was refreshed against the current operator fingerprint."
                        .formatted(operatorRef),
                operatorRef,
                previousOperatorFingerprint,
                currentOperatorFingerprint,
                changeRisk,
                changeCategories,
                changeSummary,
                sourceBinding,
                refreshedBinding,
                sourceActivation,
                refreshedActivation,
                sourceIntegration,
                refreshedIntegration,
                List.of()
        );
    }

    public static VisualExecutableReadinessEvidenceRefreshResult current(
            String operatorRef,
            String currentOperatorFingerprint,
            VisualRuntimeBindingImplementationBinding binding) {
        return current(operatorRef, currentOperatorFingerprint, binding, null, null);
    }

    public static VisualExecutableReadinessEvidenceRefreshResult current(
            String operatorRef,
            String currentOperatorFingerprint,
            VisualRuntimeBindingImplementationBinding binding,
            VisualRuntimeAdapterActivation activation,
            VisualExecutableLoweringIntegration integration) {
        return new VisualExecutableReadinessEvidenceRefreshResult(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                "current",
                "success",
                "Executable readiness evidence for '%s' already matches the current operator fingerprint."
                        .formatted(operatorRef),
                operatorRef,
                currentOperatorFingerprint,
                currentOperatorFingerprint,
                "",
                List.of(),
                "",
                binding,
                binding,
                activation,
                activation,
                integration,
                integration,
                List.of()
        );
    }

    public static VisualExecutableReadinessEvidenceRefreshResult rejected(
            String operatorRef,
            String previousOperatorFingerprint,
            String currentOperatorFingerprint,
            String state,
            String level,
            String message,
            VisualRuntimeBindingImplementationBinding sourceBinding,
            VisualRuntimeAdapterActivation sourceActivation,
            VisualExecutableLoweringIntegration sourceIntegration,
            List<VisualDiagnostic> diagnostics) {
        return new VisualExecutableReadinessEvidenceRefreshResult(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                state,
                level,
                message,
                operatorRef,
                previousOperatorFingerprint,
                currentOperatorFingerprint,
                "",
                List.of(),
                "",
                sourceBinding,
                null,
                sourceActivation,
                null,
                sourceIntegration,
                null,
                diagnostics
        );
    }

    public static VisualExecutableReadinessEvidenceRefreshResult failed(
            String operatorRef,
            String previousOperatorFingerprint,
            String currentOperatorFingerprint,
            String changeRisk,
            List<String> changeCategories,
            String changeSummary,
            String message,
            VisualRuntimeBindingImplementationBinding sourceBinding,
            VisualRuntimeBindingImplementationBinding refreshedBinding,
            VisualRuntimeAdapterActivation sourceActivation,
            VisualRuntimeAdapterActivation refreshedActivation,
            VisualExecutableLoweringIntegration sourceIntegration,
            VisualExecutableLoweringIntegration refreshedIntegration,
            List<VisualDiagnostic> diagnostics) {
        return new VisualExecutableReadinessEvidenceRefreshResult(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                "failed",
                "error",
                message,
                operatorRef,
                previousOperatorFingerprint,
                currentOperatorFingerprint,
                changeRisk,
                changeCategories,
                changeSummary,
                sourceBinding,
                refreshedBinding,
                sourceActivation,
                refreshedActivation,
                sourceIntegration,
                refreshedIntegration,
                diagnostics
        );
    }
}
