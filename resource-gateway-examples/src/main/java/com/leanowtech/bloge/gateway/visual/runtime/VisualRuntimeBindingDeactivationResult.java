package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Result contract for governed runtime binding unbind/deactivation lifecycle mutations.
 *
 * @param schemaVersion response contract version
 * @param resolvedAt server timestamp
 * @param accepted true when the lifecycle transition was applied
 * @param state accepted, rejected, missing, conflict, or failed
 * @param level UI/control-plane severity
 * @param message human-readable summary
 * @param binding implementation binding after the transition when available
 * @param deactivatedActivation adapter activation deactivated by the transition when present, or restored activation
 *        evidence when a failed transition was compensated
 * @param deactivatedIntegration executable lowering integration deactivated by the transition when present, or restored
 *        integration evidence when a failed transition was compensated
 * @param diagnostics structured diagnostics
 */
public record VisualRuntimeBindingDeactivationResult(
        String schemaVersion,
        Instant resolvedAt,
        boolean accepted,
        String state,
        String level,
        String message,
        VisualRuntimeBindingImplementationBinding binding,
        VisualRuntimeAdapterActivation deactivatedActivation,
        VisualExecutableLoweringIntegration deactivatedIntegration,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingDeactivationResult.v1";

    public VisualRuntimeBindingDeactivationResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        resolvedAt = resolvedAt == null ? Instant.now() : resolvedAt;
        state = state == null || state.isBlank() ? "rejected" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "error" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static VisualRuntimeBindingDeactivationResult accepted(
            String message,
            VisualRuntimeBindingImplementationBinding binding,
            VisualRuntimeAdapterActivation deactivatedActivation,
            VisualExecutableLoweringIntegration deactivatedIntegration) {
        return new VisualRuntimeBindingDeactivationResult(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                "accepted",
                "success",
                message,
                binding,
                deactivatedActivation,
                deactivatedIntegration,
                List.of()
        );
    }

    public static VisualRuntimeBindingDeactivationResult rejected(
            String state,
            String level,
            String message,
            VisualRuntimeBindingImplementationBinding binding,
            VisualRuntimeAdapterActivation deactivatedActivation,
            VisualExecutableLoweringIntegration deactivatedIntegration,
            List<VisualDiagnostic> diagnostics) {
        return new VisualRuntimeBindingDeactivationResult(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                state,
                level,
                message,
                binding,
                deactivatedActivation,
                deactivatedIntegration,
                diagnostics
        );
    }
}
