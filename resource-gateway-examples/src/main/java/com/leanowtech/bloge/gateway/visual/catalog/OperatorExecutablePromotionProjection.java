package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-derived executable promotion projection for one catalog-visible operator.
 *
 * <p>This read model is intentionally stricter than runtime adapter activation.
 * It tells the visual canvas what is still blocking EXECUTABLE promotion without
 * rewriting imported operator definitions or claiming that a design-only
 * operator can run before a real BLOGE lowering/executor integration exists.</p>
 *
 * @param schemaVersion projection contract version
 * @param operatorRef operator reference
 * @param operatorFingerprint current catalog operator fingerprint
 * @param executableNow whether the current BLOGE request-response runtime can execute the operator
 * @param promotionReady true only when no remaining promotion work is required
 * @param promotionState already-executable, external-runtime-bound, binding-required, binding-drifted, activation-required,
 *                       activation-drifted, executor-integration-required, lowering-integration-drifted,
 *                       or readiness-recompute-required
 * @param level UI/control-plane severity
 * @param title short display title
 * @param summary human-readable projection summary
 * @param requiredNextAction machine-readable next control-plane action
 * @param activeBindingId active bound implementation id when present
 * @param activeAdapterActivationId active adapter activation id when present
 * @param adapterKind runtime adapter kind when known
 * @param entrypoint runtime adapter entrypoint when known
 * @param runtimeEnvironment active adapter runtime environment when known
 * @param activeExecutableLoweringIntegrationId active executable lowering integration id when present
 * @param activeExecutableLoweringIntegrationRevision active executable lowering integration revision
 * @param executableLoweringMode executable lowering mode when integrated
 * @param executorKind executor integration kind when integrated
 * @param executorEntrypoint executable BLOGE lowering/executor entrypoint when integrated
 * @param integratedAt active executable lowering integration update timestamp
 * @param observedAt server projection timestamp
 * @param diagnostics projection diagnostics
 */
public record OperatorExecutablePromotionProjection(
        String schemaVersion,
        String operatorRef,
        String operatorFingerprint,
        boolean executableNow,
        boolean promotionReady,
        String promotionState,
        String level,
        String title,
        String summary,
        String requiredNextAction,
        String activeBindingId,
        String activeAdapterActivationId,
        String adapterKind,
        String entrypoint,
        String runtimeEnvironment,
        String activeExecutableLoweringIntegrationId,
        long activeExecutableLoweringIntegrationRevision,
        String executableLoweringMode,
        String executorKind,
        String executorEntrypoint,
        Instant integratedAt,
        Instant observedAt,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.operatorExecutablePromotionProjection.v1";

    /**
     * Creates a normalized promotion projection.
     */
    public OperatorExecutablePromotionProjection {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
        promotionState = normalizeState(promotionState);
        level = level == null || level.isBlank() ? "info" : level.trim().toLowerCase(Locale.ROOT);
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        requiredNextAction = requiredNextAction == null
                ? ""
                : requiredNextAction.trim().toUpperCase(Locale.ROOT);
        activeBindingId = activeBindingId == null ? "" : activeBindingId.trim();
        activeAdapterActivationId = activeAdapterActivationId == null ? "" : activeAdapterActivationId.trim();
        adapterKind = adapterKind == null ? "" : adapterKind.trim().toLowerCase(Locale.ROOT);
        entrypoint = entrypoint == null ? "" : entrypoint.trim();
        runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
        activeExecutableLoweringIntegrationId = activeExecutableLoweringIntegrationId == null
                ? ""
                : activeExecutableLoweringIntegrationId.trim();
        activeExecutableLoweringIntegrationRevision = Math.max(0, activeExecutableLoweringIntegrationRevision);
        executableLoweringMode = executableLoweringMode == null
                ? ""
                : executableLoweringMode.trim().toLowerCase(Locale.ROOT);
        executorKind = executorKind == null ? "" : executorKind.trim().toLowerCase(Locale.ROOT);
        executorEntrypoint = executorEntrypoint == null ? "" : executorEntrypoint.trim();
        integratedAt = integratedAt == null ? Instant.EPOCH : integratedAt;
        observedAt = observedAt == null ? Instant.EPOCH : observedAt;
        diagnostics = diagnostics == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    /**
     * Builds executable promotion projections from runtime binding projections.
     *
     * @param runtimeBindingProjections runtime binding projections
     * @return executable promotion projections aligned with the supplied projection order
     */
    public static List<OperatorExecutablePromotionProjection> from(
            List<OperatorRuntimeBindingProjection> runtimeBindingProjections) {
        return from(runtimeBindingProjections, Map.of());
    }

    /**
     * Builds executable promotion projections from runtime binding projections and lowering integrations.
     *
     * @param runtimeBindingProjections runtime binding projections
     * @param activeIntegrationsByActivationId active lowering integrations keyed by adapter activation id
     * @return executable promotion projections aligned with the supplied projection order
     */
    public static List<OperatorExecutablePromotionProjection> from(
            List<OperatorRuntimeBindingProjection> runtimeBindingProjections,
            Map<String, VisualExecutableLoweringIntegration> activeIntegrationsByActivationId) {
        Map<String, VisualExecutableLoweringIntegration> activeIntegrations =
                activeIntegrationsByActivationId == null ? Map.of() : activeIntegrationsByActivationId;
        return (runtimeBindingProjections == null
                ? List.<OperatorRuntimeBindingProjection>of()
                : runtimeBindingProjections).stream()
                .filter(projection -> projection != null)
                .map(projection -> from(projection,
                        activeIntegrations.get(projection.activeAdapterActivationId())))
                .toList();
    }

    /**
     * Counts promotion states.
     *
     * @param projections projection list
     * @return counts by normalized promotion state
     */
    public static Map<String, Integer> stateCounts(List<OperatorExecutablePromotionProjection> projections) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (OperatorExecutablePromotionProjection projection
                : projections == null ? List.<OperatorExecutablePromotionProjection>of() : projections) {
            if (projection == null || projection.promotionState().isBlank()) {
                continue;
            }
            counts.merge(projection.promotionState(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /**
     * Builds one projection from runtime binding state.
     *
     * @param runtimeBindingProjection runtime binding projection
     * @return executable promotion projection
     */
    public static OperatorExecutablePromotionProjection from(
            OperatorRuntimeBindingProjection runtimeBindingProjection) {
        return from(runtimeBindingProjection, null);
    }

    /**
     * Builds one projection from runtime binding and optional executable lowering integration state.
     *
     * @param runtimeBindingProjection runtime binding projection
     * @param activeIntegration active executable lowering integration for the projection activation
     * @return executable promotion projection
     */
    public static OperatorExecutablePromotionProjection from(
            OperatorRuntimeBindingProjection runtimeBindingProjection,
            VisualExecutableLoweringIntegration activeIntegration) {
        if (runtimeBindingProjection == null) {
            return new OperatorExecutablePromotionProjection(
                    SCHEMA_VERSION,
                    "",
                    "",
                    false,
                    false,
                    "unknown",
                    "warning",
                    "Promotion state unknown",
                    "Executable promotion state is unavailable.",
                    "REFRESH_CATALOG",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    0,
                    "",
                    "",
                    "",
                    Instant.EPOCH,
                    Instant.now(),
                    List.of()
            );
        }
        return switch (runtimeBindingProjection.projectionState()) {
            case "not-required", "binding-bound-unneeded" -> alreadyExecutable(runtimeBindingProjection);
            case "external-runtime-bound" -> externalRuntimeBound(runtimeBindingProjection, activeIntegration);
            case "binding-required" -> bindingRequired(runtimeBindingProjection);
            case "binding-drifted" -> bindingDrifted(runtimeBindingProjection);
            case "binding-bound" -> activationRequired(runtimeBindingProjection);
            case "adapter-drifted" -> activationDrifted(runtimeBindingProjection);
            case "adapter-active" -> adapterActive(runtimeBindingProjection, activeIntegration);
            default -> unknown(runtimeBindingProjection);
        };
    }

    private static OperatorExecutablePromotionProjection externalRuntimeBound(
            OperatorRuntimeBindingProjection projection,
            VisualExecutableLoweringIntegration activeIntegration) {
        if (activeIntegration == null) {
            return executorIntegrationRequired(projection);
        }
        if (!integrationMatchesProjection(activeIntegration, projection)) {
            return loweringIntegrationDrifted(projection, activeIntegration);
        }
        return base(
                projection,
                activeIntegration,
                false,
                true,
                "external-runtime-bound",
                "warning",
                "External runtime bound",
                "The operator has trusted external executor integration; the current request-response runtime remains blocked.",
                "",
                projection.diagnostics()
        );
    }

    private static OperatorExecutablePromotionProjection alreadyExecutable(
            OperatorRuntimeBindingProjection projection) {
        return base(
                projection,
                true,
                true,
                "already-executable",
                "success",
                "Executable runtime available",
                "The operator is already executable by the current BLOGE request-response runtime.",
                "",
                List.of()
        );
    }

    private static OperatorExecutablePromotionProjection bindingRequired(
            OperatorRuntimeBindingProjection projection) {
        return base(
                projection,
                false,
                false,
                "binding-required",
                projection.level(),
                "Implementation binding required",
                "EXECUTABLE promotion requires a validated and bound runtime implementation.",
                "SUBMIT_IMPLEMENTATION_BINDING",
                projection.diagnostics()
        );
    }

    private static OperatorExecutablePromotionProjection bindingDrifted(
            OperatorRuntimeBindingProjection projection) {
        return base(
                projection,
                false,
                false,
                "binding-drifted",
                "warning",
                "Implementation binding drifted",
                "The active implementation binding no longer matches the current operator contract.",
                "SUPERSEDE_IMPLEMENTATION_BINDING",
                projection.diagnostics()
        );
    }

    private static OperatorExecutablePromotionProjection activationRequired(
            OperatorRuntimeBindingProjection projection) {
        return base(
                projection,
                false,
                false,
                "activation-required",
                "info",
                "Runtime adapter activation required",
                "A bound implementation exists; EXECUTABLE promotion still needs a healthy runtime adapter activation.",
                "ACTIVATE_RUNTIME_ADAPTER",
                projection.diagnostics()
        );
    }

    private static OperatorExecutablePromotionProjection activationDrifted(
            OperatorRuntimeBindingProjection projection) {
        return base(
                projection,
                false,
                false,
                "activation-drifted",
                "warning",
                "Runtime adapter activation drifted",
                "The active runtime adapter activation no longer matches the bound implementation.",
                "REACTIVATE_RUNTIME_ADAPTER",
                projection.diagnostics()
        );
    }

    private static OperatorExecutablePromotionProjection adapterActive(
            OperatorRuntimeBindingProjection projection,
            VisualExecutableLoweringIntegration activeIntegration) {
        if (activeIntegration == null) {
            return executorIntegrationRequired(projection);
        }
        if (!integrationMatchesProjection(activeIntegration, projection)) {
            return loweringIntegrationDrifted(projection, activeIntegration);
        }
        return readinessRecomputeRequired(projection, activeIntegration);
    }

    private static OperatorExecutablePromotionProjection executorIntegrationRequired(
            OperatorRuntimeBindingProjection projection) {
        VisualDiagnostic diagnostic = new VisualDiagnostic(
                "INFO",
                "visual.executablePromotion.executorIntegrationRequired",
                "Operator '%s' has an active adapter activation, but no BLOGE executable lowering/executor integration has promoted it."
                        .formatted(projection.operatorRef()),
                "/operators/" + projection.operatorRef() + "/executablePromotionProjection",
                -1,
                -1,
                Map.of(
                        "operatorRef", projection.operatorRef(),
                        "bindingId", projection.activeBindingId(),
                        "activationId", projection.activeAdapterActivationId(),
                        "adapterKind", projection.implementationAdapterKind(),
                        "entrypoint", projection.implementationEntrypoint(),
                        "runtimeEnvironment", projection.runtimeEnvironment()
                ));
        return base(
                projection,
                false,
                false,
                "executor-integration-required",
                "info",
                "Executable lowering integration required",
                "The runtime adapter is active; explicit BLOGE lowering/executor integration is still required before this design-only operator can run.",
                "INTEGRATE_EXECUTABLE_LOWERING",
                merge(projection.diagnostics(), diagnostic)
        );
    }

    private static OperatorExecutablePromotionProjection loweringIntegrationDrifted(
            OperatorRuntimeBindingProjection projection,
            VisualExecutableLoweringIntegration integration) {
        VisualDiagnostic diagnostic = new VisualDiagnostic(
                "WARNING",
                "visual.executablePromotion.loweringIntegrationDrift",
                "Operator '%s' has an executable lowering integration that no longer matches the active adapter activation."
                        .formatted(projection.operatorRef()),
                "/operators/" + projection.operatorRef() + "/executablePromotionProjection",
                -1,
                -1,
                Map.of(
                        "operatorRef", projection.operatorRef(),
                        "activationId", projection.activeAdapterActivationId(),
                        "integrationId", integration.integrationId(),
                        "integrationActivationId", integration.activationId(),
                        "integrationActivationRevision", integration.activationRevision(),
                        "activationRevision", projection.activeAdapterActivationRevision()
                ));
        return base(
                projection,
                integration,
                false,
                false,
                "lowering-integration-drifted",
                "warning",
                "Executable lowering integration drifted",
                "The recorded executable lowering integration no longer matches the active adapter activation.",
                "REVALIDATE_EXECUTABLE_LOWERING",
                merge(projection.diagnostics(), diagnostic)
        );
    }

    private static OperatorExecutablePromotionProjection readinessRecomputeRequired(
            OperatorRuntimeBindingProjection projection,
            VisualExecutableLoweringIntegration integration) {
        VisualDiagnostic diagnostic = new VisualDiagnostic(
                "INFO",
                "visual.executablePromotion.readinessRecomputeRequired",
                "Operator '%s' has executable lowering integration evidence; runtime readiness still needs catalog/readiness recomputation."
                        .formatted(projection.operatorRef()),
                "/operators/" + projection.operatorRef() + "/executablePromotionProjection",
                -1,
                -1,
                Map.of(
                        "operatorRef", projection.operatorRef(),
                        "bindingId", projection.activeBindingId(),
                        "activationId", projection.activeAdapterActivationId(),
                        "integrationId", integration.integrationId(),
                        "loweringMode", integration.loweringMode(),
                        "executorKind", integration.executorKind()
                ));
        return base(
                projection,
                integration,
                false,
                false,
                "readiness-recompute-required",
                "info",
                "Runtime readiness recompute required",
                "Executable lowering integration is recorded; operator readiness still waits for a catalog/library revision or readiness recomputation.",
                "RECOMPUTE_OPERATOR_READINESS",
                merge(projection.diagnostics(), diagnostic)
        );
    }

    private static OperatorExecutablePromotionProjection unknown(OperatorRuntimeBindingProjection projection) {
        return base(
                projection,
                projection.executable(),
                false,
                "unknown",
                "warning",
                "Executable promotion state unknown",
                "Runtime binding projection state '%s' is not recognized."
                        .formatted(projection.projectionState()),
                "REFRESH_CATALOG",
                projection.diagnostics()
        );
    }

    private static OperatorExecutablePromotionProjection base(
            OperatorRuntimeBindingProjection projection,
            boolean executableNow,
            boolean promotionReady,
            String promotionState,
            String level,
            String title,
            String summary,
            String requiredNextAction,
            List<VisualDiagnostic> diagnostics) {
        return base(projection, null, executableNow, promotionReady, promotionState, level, title,
                summary, requiredNextAction, diagnostics);
    }

    private static OperatorExecutablePromotionProjection base(
            OperatorRuntimeBindingProjection projection,
            VisualExecutableLoweringIntegration integration,
            boolean executableNow,
            boolean promotionReady,
            String promotionState,
            String level,
            String title,
            String summary,
            String requiredNextAction,
            List<VisualDiagnostic> diagnostics) {
        return new OperatorExecutablePromotionProjection(
                SCHEMA_VERSION,
                projection.operatorRef(),
                projection.operatorFingerprint(),
                executableNow,
                promotionReady,
                promotionState,
                level,
                title,
                summary,
                requiredNextAction,
                projection.activeBindingId(),
                projection.activeAdapterActivationId(),
                projection.implementationAdapterKind(),
                projection.implementationEntrypoint(),
                projection.runtimeEnvironment(),
                integration == null ? "" : integration.integrationId(),
                integration == null ? 0 : integration.revision(),
                integration == null ? "" : integration.loweringMode(),
                integration == null ? "" : integration.executorKind(),
                integration == null ? "" : integration.executorEntrypoint(),
                integration == null ? Instant.EPOCH : integration.updatedAt(),
                Instant.now(),
                diagnostics
        );
    }

    private static boolean integrationMatchesProjection(VisualExecutableLoweringIntegration integration,
                                                        OperatorRuntimeBindingProjection projection) {
        return integration.activationId().equals(projection.activeAdapterActivationId())
                && integration.activationRevision() == projection.activeAdapterActivationRevision()
                && integration.bindingId().equals(projection.activeBindingId())
                && integration.bindingRevision() == projection.activeBindingRevision()
                && integration.operatorRef().equals(projection.operatorRef())
                && integration.operatorFingerprint().equals(projection.operatorFingerprint())
                && integration.adapterKind().equals(projection.implementationAdapterKind())
                && integration.entrypoint().equals(projection.implementationEntrypoint())
                && integration.runtimeEnvironment().equals(projection.runtimeEnvironment());
    }

    private static List<VisualDiagnostic> merge(List<VisualDiagnostic> diagnostics, VisualDiagnostic diagnostic) {
        List<VisualDiagnostic> merged = new ArrayList<>(diagnostics == null ? List.of() : diagnostics);
        if (diagnostic != null) {
            merged.add(diagnostic);
        }
        return List.copyOf(merged);
    }

    private static String normalizeState(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }
}
