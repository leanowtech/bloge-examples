package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Result contract for runtime implementation binding lifecycle mutations.
 *
 * @param schemaVersion response contract version
 * @param resolvedAt server timestamp
 * @param accepted true when the lifecycle transition was applied
 * @param state accepted, rejected, conflict, or failed
 * @param level UI/control-plane severity
 * @param message human-readable summary
 * @param binding primary binding record
 * @param replacementBinding replacement binding for supersede operations
 * @param diagnostics structured lifecycle diagnostics
 */
public record VisualRuntimeBindingImplementationLifecycleResult(
        String schemaVersion,
        Instant resolvedAt,
        boolean accepted,
        String state,
        String level,
        String message,
        VisualRuntimeBindingImplementationBinding binding,
        VisualRuntimeBindingImplementationBinding replacementBinding,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingImplementationLifecycleResult.v1";

    public VisualRuntimeBindingImplementationLifecycleResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        resolvedAt = resolvedAt == null ? Instant.now() : resolvedAt;
        state = state == null || state.isBlank() ? "rejected" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "error" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static VisualRuntimeBindingImplementationLifecycleResult accepted(
            String message,
            VisualRuntimeBindingImplementationBinding binding,
            VisualRuntimeBindingImplementationBinding replacementBinding) {
        return new VisualRuntimeBindingImplementationLifecycleResult(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                "accepted",
                "success",
                message,
                binding,
                replacementBinding,
                List.of()
        );
    }

    public static VisualRuntimeBindingImplementationLifecycleResult rejected(
            String state,
            String message,
            VisualRuntimeBindingImplementationBinding binding,
            VisualRuntimeBindingImplementationBinding replacementBinding,
            List<VisualDiagnostic> diagnostics) {
        return new VisualRuntimeBindingImplementationLifecycleResult(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                state,
                "error",
                message,
                binding,
                replacementBinding,
                diagnostics
        );
    }
}
