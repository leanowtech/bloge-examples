package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationValidation;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-derived runtime binding projection for one catalog-visible operator.
 *
 * <p>This projection lets the visual canvas show implementation binding progress
 * without trusting imported operator libraries to declare their own runtime state.
 * A bound implementation is still not the same thing as executable adapter
 * availability.</p>
 *
 * @param schemaVersion projection contract version
 * @param operatorRef operator reference
 * @param operatorFingerprint current catalog operator fingerprint
 * @param runtimeReadinessState server-derived operator runtime readiness state
 * @param executable whether the current request-response runtime can execute the operator
 * @param implementationBindingRequired true when an implementation binding is still missing or stale
 * @param runtimeActivationRequired true when a bound implementation still needs adapter runtime activation
 * @param projectionState not-required, binding-required, binding-bound, binding-drifted,
 *                        adapter-active, adapter-drifted, binding-bound-unneeded, or external-runtime-bound
 * @param level UI/control-plane severity
 * @param title short display title
 * @param summary human-readable projection summary
 * @param activeBindingId active bound implementation id when present
 * @param activeBindingRevision active binding revision
 * @param activeBindingState active binding lifecycle state
 * @param implementationAdapterKind submitted runtime adapter kind
 * @param implementationEntrypoint submitted runtime adapter entrypoint
 * @param runtimeOwner implementation owner
 * @param boundAt active binding update timestamp
 * @param activeAdapterActivationId active adapter activation id when present
 * @param activeAdapterActivationRevision active adapter activation revision
 * @param adapterActivationState active adapter activation state
 * @param adapterHealthState active adapter health state
 * @param runtimeEnvironment active adapter runtime environment
 * @param activatedAt active adapter activation update timestamp
 * @param diagnostics projection diagnostics
 */
public record OperatorRuntimeBindingProjection(
        String schemaVersion,
        String operatorRef,
        String operatorFingerprint,
        String runtimeReadinessState,
        boolean executable,
        boolean implementationBindingRequired,
        boolean runtimeActivationRequired,
        String projectionState,
        String level,
        String title,
        String summary,
        String activeBindingId,
        long activeBindingRevision,
        String activeBindingState,
        String implementationAdapterKind,
        String implementationEntrypoint,
        String runtimeOwner,
        Instant boundAt,
        String activeAdapterActivationId,
        long activeAdapterActivationRevision,
        String adapterActivationState,
        String adapterHealthState,
        String runtimeEnvironment,
        Instant activatedAt,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.operatorRuntimeBindingProjection.v1";

    /**
     * Creates a normalized projection.
     */
    public OperatorRuntimeBindingProjection {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
        runtimeReadinessState = normalizeState(runtimeReadinessState);
        projectionState = normalizeState(projectionState);
        level = level == null || level.isBlank() ? "info" : level.trim().toLowerCase(Locale.ROOT);
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        activeBindingId = activeBindingId == null ? "" : activeBindingId.trim();
        activeBindingRevision = Math.max(0, activeBindingRevision);
        activeBindingState = activeBindingState == null ? "" : activeBindingState.trim().toLowerCase(Locale.ROOT);
        implementationAdapterKind = implementationAdapterKind == null
                ? ""
                : implementationAdapterKind.trim().toLowerCase(Locale.ROOT);
        implementationEntrypoint = implementationEntrypoint == null ? "" : implementationEntrypoint.trim();
        runtimeOwner = runtimeOwner == null ? "" : runtimeOwner.trim();
        boundAt = boundAt == null ? Instant.EPOCH : boundAt;
        activeAdapterActivationId = activeAdapterActivationId == null ? "" : activeAdapterActivationId.trim();
        activeAdapterActivationRevision = Math.max(0, activeAdapterActivationRevision);
        adapterActivationState = adapterActivationState == null
                ? ""
                : adapterActivationState.trim().toLowerCase(Locale.ROOT);
        adapterHealthState = adapterHealthState == null ? "" : adapterHealthState.trim().toLowerCase(Locale.ROOT);
        runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
        activatedAt = activatedAt == null ? Instant.EPOCH : activatedAt;
        diagnostics = diagnostics == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    /**
     * Backward-compatible constructor for callers that do not yet project adapter activation state.
     */
    public OperatorRuntimeBindingProjection(String schemaVersion,
                                            String operatorRef,
                                            String operatorFingerprint,
                                            String runtimeReadinessState,
                                            boolean executable,
                                            boolean implementationBindingRequired,
                                            boolean runtimeActivationRequired,
                                            String projectionState,
                                            String level,
                                            String title,
                                            String summary,
                                            String activeBindingId,
                                            long activeBindingRevision,
                                            String activeBindingState,
                                            String implementationAdapterKind,
                                            String implementationEntrypoint,
                                            String runtimeOwner,
                                            Instant boundAt,
                                            List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, operatorRef, operatorFingerprint, runtimeReadinessState, executable,
                implementationBindingRequired, runtimeActivationRequired, projectionState, level, title, summary,
                activeBindingId, activeBindingRevision, activeBindingState, implementationAdapterKind,
                implementationEntrypoint, runtimeOwner, boundAt, "", 0, "", "", "", Instant.EPOCH, diagnostics);
    }

    /**
     * Builds projections for the supplied operator window.
     *
     * @param operators catalog-visible operators
     * @param activeBindingsByOperatorRef active bound bindings keyed by operatorRef
     * @return projection list aligned with the operators list
     */
    public static List<OperatorRuntimeBindingProjection> from(
            List<OperatorDefinition> operators,
            Map<String, VisualRuntimeBindingImplementationBinding> activeBindingsByOperatorRef) {
        return from(operators, activeBindingsByOperatorRef, Map.of());
    }

    /**
     * Builds projections for the supplied operator window.
     *
     * @param operators catalog-visible operators
     * @param activeBindingsByOperatorRef active bound bindings keyed by operatorRef
     * @param activeActivationsByBindingId active adapter activations keyed by bindingId
     * @return projection list aligned with the operators list
     */
    public static List<OperatorRuntimeBindingProjection> from(
            List<OperatorDefinition> operators,
            Map<String, VisualRuntimeBindingImplementationBinding> activeBindingsByOperatorRef,
            Map<String, VisualRuntimeAdapterActivation> activeActivationsByBindingId) {
        Map<String, VisualRuntimeBindingImplementationBinding> activeBindings =
                activeBindingsByOperatorRef == null ? Map.of() : activeBindingsByOperatorRef;
        Map<String, VisualRuntimeAdapterActivation> activeActivations =
                activeActivationsByBindingId == null ? Map.of() : activeActivationsByBindingId;
        return (operators == null ? List.<OperatorDefinition>of() : operators).stream()
                .filter(operator -> operator != null)
                .map(operator -> {
                    VisualRuntimeBindingImplementationBinding activeBinding =
                            activeBindings.get(operator.operatorRef());
                    VisualRuntimeAdapterActivation activeActivation = activeBinding == null
                            ? null
                            : activeActivations.get(activeBinding.bindingId());
                    return from(operator, activeBinding, activeActivation);
                })
                .toList();
    }

    /**
     * Counts projection states.
     *
     * @param projections projection list
     * @return counts by normalized projection state
     */
    public static Map<String, Integer> stateCounts(List<OperatorRuntimeBindingProjection> projections) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (OperatorRuntimeBindingProjection projection
                : projections == null ? List.<OperatorRuntimeBindingProjection>of() : projections) {
            if (projection == null || projection.projectionState().isBlank()) {
                continue;
            }
            counts.merge(projection.projectionState(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /**
     * Builds one projection.
     *
     * @param operator current operator definition
     * @param activeBinding active bound binding when present
     * @return projection
     */
    public static OperatorRuntimeBindingProjection from(OperatorDefinition operator,
                                                        VisualRuntimeBindingImplementationBinding activeBinding) {
        return from(operator, activeBinding, null);
    }

    /**
     * Builds one projection with optional adapter activation state.
     *
     * @param operator current operator definition
     * @param activeBinding active bound binding when present
     * @param activeActivation active adapter activation when present
     * @return projection
     */
    public static OperatorRuntimeBindingProjection from(OperatorDefinition operator,
                                                        VisualRuntimeBindingImplementationBinding activeBinding,
                                                        VisualRuntimeAdapterActivation activeActivation) {
        OperatorDefinition.RuntimeReadiness readiness = operator == null ? null : operator.runtimeReadiness();
        String operatorRef = operator == null ? "" : operator.operatorRef();
        String fingerprint = operator == null ? "" : operator.fingerprint();
        String readinessState = readiness == null ? "unknown" : readiness.state();
        boolean executable = readiness != null && readiness.executable();
        BindingFields binding = BindingFields.from(activeBinding);
        ActivationFields activation = ActivationFields.from(activeActivation);
        if (activeBinding == null || activeBinding.bindingId().isBlank()) {
            return unboundProjection(operatorRef, fingerprint, readinessState, executable, readiness);
        }
        if (!fingerprint.isBlank() && !activeBinding.operatorFingerprint().isBlank()
                && !fingerprint.equals(activeBinding.operatorFingerprint())) {
            return driftedProjection(operatorRef, fingerprint, readinessState, executable, binding,
                    activation, activeBinding);
        }
        if (executable) {
            return new OperatorRuntimeBindingProjection(
                    SCHEMA_VERSION,
                    operatorRef,
                    fingerprint,
                    readinessState,
                    true,
                    false,
                    false,
                    "binding-bound-unneeded",
                    "info",
                    "Runtime binding recorded",
                    "The operator is already runtime-executable; the active binding is retained as audit evidence.",
                    binding.bindingId(),
                    binding.revision(),
                    binding.state(),
                    binding.adapterKind(),
                    binding.entrypoint(),
                    binding.runtimeOwner(),
                    binding.boundAt(),
                    activation.activationId(),
                    activation.revision(),
                    activation.state(),
                    activation.healthState(),
                    activation.runtimeEnvironment(),
                    activation.activatedAt(),
                    List.of()
            );
        }
        if (activeActivation != null && !activationMatchesBinding(activeActivation, activeBinding, binding)) {
            return adapterDriftedProjection(operatorRef, fingerprint, readinessState, binding, activation,
                    activeBinding, activeActivation);
        }
        if (activeActivation != null && externalRuntimeBound(readinessState)) {
            return externalRuntimeBoundProjection(operatorRef, fingerprint, readinessState, binding, activation);
        }
        if (activeActivation != null) {
            return new OperatorRuntimeBindingProjection(
                    SCHEMA_VERSION,
                    operatorRef,
                    fingerprint,
                    readinessState,
                    false,
                    false,
                    false,
                    "adapter-active",
                    "success",
                    "Runtime adapter active",
                    "A healthy runtime adapter activation is recorded; executable promotion still requires explicit BLOGE runtime integration.",
                    binding.bindingId(),
                    binding.revision(),
                    binding.state(),
                    binding.adapterKind(),
                    binding.entrypoint(),
                    binding.runtimeOwner(),
                    binding.boundAt(),
                    activation.activationId(),
                    activation.revision(),
                    activation.state(),
                    activation.healthState(),
                    activation.runtimeEnvironment(),
                    activation.activatedAt(),
                    List.of()
            );
        }
        return new OperatorRuntimeBindingProjection(
                SCHEMA_VERSION,
                operatorRef,
                fingerprint,
                readinessState,
                false,
                false,
                true,
                "binding-bound",
                "info",
                "Runtime binding bound",
                "An active implementation binding is present; EXECUTABLE promotion still waits for runtime adapter activation.",
                binding.bindingId(),
                binding.revision(),
                binding.state(),
                binding.adapterKind(),
                binding.entrypoint(),
                binding.runtimeOwner(),
                binding.boundAt(),
                "",
                0,
                "",
                "",
                "",
                Instant.EPOCH,
                List.of()
        );
    }

    private static OperatorRuntimeBindingProjection unboundProjection(String operatorRef,
                                                                      String fingerprint,
                                                                      String readinessState,
                                                                      boolean executable,
                                                                      OperatorDefinition.RuntimeReadiness readiness) {
        if (executable) {
            return new OperatorRuntimeBindingProjection(
                    SCHEMA_VERSION,
                    operatorRef,
                    fingerprint,
                    readinessState,
                    true,
                    false,
                    false,
                    "not-required",
                    "success",
                    "Runtime binding not required",
                    "The operator is already executable by the current request-response visual runtime.",
                    "",
                    0,
                    "",
                    "",
                    "",
                    "",
                    Instant.EPOCH,
                    "",
                    0,
                    "",
                    "",
                    "",
                    Instant.EPOCH,
                    List.of()
            );
        }
        String level = readiness == null ? "warning" : readiness.level();
        String summary = readiness == null || readiness.summary().isBlank()
                ? "An implementation binding is required before this operator can be promoted for executable use."
                : readiness.summary();
        return new OperatorRuntimeBindingProjection(
                SCHEMA_VERSION,
                operatorRef,
                fingerprint,
                readinessState,
                false,
                true,
                false,
                "binding-required",
                level,
                "Runtime binding required",
                summary,
                "",
                0,
                "",
                "",
                "",
                "",
                Instant.EPOCH,
                "",
                0,
                "",
                "",
                "",
                Instant.EPOCH,
                List.of()
        );
    }

    private static OperatorRuntimeBindingProjection driftedProjection(String operatorRef,
                                                                      String fingerprint,
                                                                      String readinessState,
                                                                      boolean executable,
                                                                      BindingFields binding,
                                                                      ActivationFields activation,
                                                                      VisualRuntimeBindingImplementationBinding activeBinding) {
        VisualDiagnostic diagnostic = VisualDiagnostic.warning(
                "visual.runtimeBindingProjection.fingerprintDrift",
                "Active runtime binding '%s' was created for operator fingerprint '%s', but the current catalog fingerprint is '%s'."
                        .formatted(activeBinding.bindingId(), activeBinding.operatorFingerprint(), fingerprint),
                "/operators/" + operatorRef + "/runtimeBindingProjection",
                Map.of(
                        "bindingId", activeBinding.bindingId(),
                        "bindingOperatorFingerprint", activeBinding.operatorFingerprint(),
                        "currentOperatorFingerprint", fingerprint,
                        "operatorRef", operatorRef
                ));
        return new OperatorRuntimeBindingProjection(
                SCHEMA_VERSION,
                operatorRef,
                fingerprint,
                readinessState,
                executable,
                true,
                !executable,
                "binding-drifted",
                "warning",
                "Runtime binding drifted",
                "The active binding no longer matches the current operator contract; revalidate or supersede it before executable promotion.",
                binding.bindingId(),
                binding.revision(),
                binding.state(),
                binding.adapterKind(),
                binding.entrypoint(),
                binding.runtimeOwner(),
                binding.boundAt(),
                activation.activationId(),
                activation.revision(),
                activation.state(),
                activation.healthState(),
                activation.runtimeEnvironment(),
                activation.activatedAt(),
                List.of(diagnostic)
        );
    }

    private static OperatorRuntimeBindingProjection adapterDriftedProjection(
            String operatorRef,
            String fingerprint,
            String readinessState,
            BindingFields binding,
            ActivationFields activation,
            VisualRuntimeBindingImplementationBinding activeBinding,
            VisualRuntimeAdapterActivation activeActivation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("activationId", activeActivation.activationId());
        metadata.put("activationBindingId", activeActivation.bindingId());
        metadata.put("bindingId", activeBinding.bindingId());
        metadata.put("activationBindingRevision", activeActivation.bindingRevision());
        metadata.put("bindingRevision", activeBinding.revision());
        metadata.put("activationOperatorFingerprint", activeActivation.operatorFingerprint());
        metadata.put("bindingOperatorFingerprint", activeBinding.operatorFingerprint());
        metadata.put("activationAdapterKind", activeActivation.adapterKind());
        metadata.put("bindingAdapterKind", binding.adapterKind());
        metadata.put("activationEntrypoint", activeActivation.entrypoint());
        metadata.put("bindingEntrypoint", binding.entrypoint());
        VisualDiagnostic diagnostic = VisualDiagnostic.warning(
                "visual.runtimeBindingProjection.adapterActivationDrift",
                "Active adapter activation '%s' no longer matches runtime binding '%s'; re-activate after implementation binding review."
                        .formatted(activeActivation.activationId(), activeBinding.bindingId()),
                "/operators/" + operatorRef + "/runtimeBindingProjection",
                metadata);
        return new OperatorRuntimeBindingProjection(
                SCHEMA_VERSION,
                operatorRef,
                fingerprint,
                readinessState,
                false,
                false,
                true,
                "adapter-drifted",
                "warning",
                "Runtime adapter activation drifted",
                "The active adapter activation no longer matches the bound implementation; re-activate it before executable promotion.",
                binding.bindingId(),
                binding.revision(),
                binding.state(),
                binding.adapterKind(),
                binding.entrypoint(),
                binding.runtimeOwner(),
                binding.boundAt(),
                activation.activationId(),
                activation.revision(),
                activation.state(),
                activation.healthState(),
                activation.runtimeEnvironment(),
                activation.activatedAt(),
                List.of(diagnostic)
        );
    }

    private static OperatorRuntimeBindingProjection externalRuntimeBoundProjection(
            String operatorRef,
            String fingerprint,
            String readinessState,
            BindingFields binding,
            ActivationFields activation) {
        return new OperatorRuntimeBindingProjection(
                SCHEMA_VERSION,
                operatorRef,
                fingerprint,
                readinessState,
                false,
                false,
                false,
                "external-runtime-bound",
                "warning",
                "External runtime bound",
                "The operator has trusted external runtime evidence; the current request-response runtime still cannot execute it directly.",
                binding.bindingId(),
                binding.revision(),
                binding.state(),
                binding.adapterKind(),
                binding.entrypoint(),
                binding.runtimeOwner(),
                binding.boundAt(),
                activation.activationId(),
                activation.revision(),
                activation.state(),
                activation.healthState(),
                activation.runtimeEnvironment(),
                activation.activatedAt(),
                List.of()
        );
    }

    private static boolean activationMatchesBinding(VisualRuntimeAdapterActivation activation,
                                                    VisualRuntimeBindingImplementationBinding binding,
                                                    BindingFields fields) {
        return activation.active()
                && VisualRuntimeAdapterActivation.HEALTH_HEALTHY.equals(activation.healthState())
                && activation.bindingId().equals(binding.bindingId())
                && activation.bindingRevision() == binding.revision()
                && activation.operatorRef().equals(binding.operatorRef())
                && activation.operatorFingerprint().equals(binding.operatorFingerprint())
                && activation.adapterKind().equals(fields.adapterKind())
                && activation.entrypoint().equals(fields.entrypoint())
                && activation.runtimeOwner().equals(fields.runtimeOwner());
    }

    private static boolean externalRuntimeBound(String readinessState) {
        return "external-runtime-bound".equals(normalizeState(readinessState));
    }

    private static String normalizeState(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private record BindingFields(String bindingId,
                                 long revision,
                                 String state,
                                 String adapterKind,
                                 String entrypoint,
                                 String runtimeOwner,
                                 Instant boundAt) {
        private static BindingFields from(VisualRuntimeBindingImplementationBinding binding) {
            VisualRuntimeBindingImplementationValidation.ImplementationMetadata implementation =
                    binding == null ? null : binding.implementation();
            return new BindingFields(
                    binding == null ? "" : binding.bindingId(),
                    binding == null ? 0 : binding.revision(),
                    binding == null ? "" : binding.state(),
                    implementation == null ? "" : implementation.adapterKind(),
                    implementation == null ? "" : implementation.entrypoint(),
                    implementation == null ? "" : implementation.runtimeOwner(),
                    binding == null ? Instant.EPOCH : binding.updatedAt()
            );
        }
    }

    private record ActivationFields(String activationId,
                                    long revision,
                                    String state,
                                    String healthState,
                                    String runtimeEnvironment,
                                    Instant activatedAt) {
        private static ActivationFields from(VisualRuntimeAdapterActivation activation) {
            return new ActivationFields(
                    activation == null ? "" : activation.activationId(),
                    activation == null ? 0 : activation.revision(),
                    activation == null ? "" : activation.state(),
                    activation == null ? "" : activation.healthState(),
                    activation == null ? "" : activation.runtimeEnvironment(),
                    activation == null ? Instant.EPOCH : activation.updatedAt()
            );
        }
    }
}
