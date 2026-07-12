package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationValidation;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stateless validation result for a runtime adapter activation assertion.
 *
 * <p>Activation validation is stricter than implementation proposal validation:
 * it requires a current bound implementation, a current catalog fingerprint, and
 * a healthy runtime adapter assertion before the activation fact can be stored.</p>
 *
 * @param schemaVersion validation response contract version
 * @param validatedAt server validation timestamp
 * @param valid true when no blocking validation diagnostics were found
 * @param activatable true when the activation request can be persisted
 * @param state ready-to-activate or rejected
 * @param level UI/control-plane severity
 * @param message human-readable validation summary
 * @param activationId submitted activation id
 * @param bindingId active implementation binding id
 * @param bindingRevision active implementation binding revision
 * @param operatorRef operator being activated
 * @param operatorFingerprint operator fingerprint observed at activation time
 * @param currentCatalogFingerprint current catalog fingerprint for the operator
 * @param currentCatalogState current catalog comparison state
 * @param adapterKind runtime adapter kind
 * @param entrypoint runtime adapter entrypoint
 * @param runtimeOwner owning runtime team or service
 * @param runtimeEnvironment concrete runtime environment
 * @param healthState submitted runtime adapter health state
 * @param activatedBy principal or service that confirmed activation
 * @param reason human-readable activation reason
 * @param diagnostics structured validation diagnostics
 */
public record VisualRuntimeAdapterActivationValidation(
        String schemaVersion,
        Instant validatedAt,
        boolean valid,
        boolean activatable,
        String state,
        String level,
        String message,
        String activationId,
        String bindingId,
        long bindingRevision,
        String operatorRef,
        String operatorFingerprint,
        String currentCatalogFingerprint,
        String currentCatalogState,
        String adapterKind,
        String entrypoint,
        String runtimeOwner,
        String runtimeEnvironment,
        String healthState,
        String activatedBy,
        String reason,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeAdapterActivationValidation.v1";
    public static final String REQUEST_SCHEMA_VERSION = "bloge.visualRuntimeAdapterActivationRequest.v1";

    /**
     * Creates a normalized validation result.
     */
    public VisualRuntimeAdapterActivationValidation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        validatedAt = validatedAt == null ? Instant.now() : validatedAt;
        state = state == null || state.isBlank() ? "rejected" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "error" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        activationId = activationId == null ? "" : activationId.trim();
        bindingId = bindingId == null ? "" : bindingId.trim();
        bindingRevision = Math.max(0, bindingRevision);
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
        currentCatalogFingerprint = currentCatalogFingerprint == null ? "" : currentCatalogFingerprint.trim();
        currentCatalogState = currentCatalogState == null || currentCatalogState.isBlank()
                ? "unknown"
                : currentCatalogState.trim().toLowerCase(Locale.ROOT);
        adapterKind = adapterKind == null ? "" : adapterKind.trim().toLowerCase(Locale.ROOT);
        entrypoint = entrypoint == null ? "" : entrypoint.trim();
        runtimeOwner = runtimeOwner == null ? "" : runtimeOwner.trim();
        runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
        healthState = healthState == null || healthState.isBlank()
                ? VisualRuntimeAdapterActivation.HEALTH_HEALTHY
                : healthState.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        activatedBy = activatedBy == null ? "" : activatedBy.trim();
        reason = reason == null ? "" : reason.trim();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        activatable = valid;
    }

    /**
     * Submitted adapter activation request.
     *
     * @param schemaVersion request contract version
     * @param activationId caller-supplied activation id
     * @param bindingId implementation binding id being activated
     * @param bindingRevision implementation binding revision observed by the runtime platform
     * @param operatorRef operator reference echoed from the binding
     * @param operatorFingerprint operator fingerprint echoed from the binding
     * @param adapterKind runtime adapter kind echoed from the binding
     * @param entrypoint runtime adapter entrypoint echoed from the binding
     * @param runtimeOwner owning runtime team or service
     * @param runtimeEnvironment concrete runtime environment
     * @param healthState runtime adapter health state, currently expected to be healthy
     * @param activatedBy principal or service confirming activation
     * @param changeSource source system or workflow
     * @param reason human-readable activation reason
     * @param evidence external activation evidence
     */
    public record Request(
            String schemaVersion,
            String activationId,
            String bindingId,
            long bindingRevision,
            String operatorRef,
            String operatorFingerprint,
            String adapterKind,
            String entrypoint,
            String runtimeOwner,
            String runtimeEnvironment,
            String healthState,
            String activatedBy,
            String changeSource,
            String reason,
            List<VisualRuntimeAdapterActivation.Evidence> evidence
    ) {
        public Request {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? REQUEST_SCHEMA_VERSION
                    : schemaVersion.trim();
            activationId = activationId == null ? "" : activationId.trim();
            bindingId = bindingId == null ? "" : bindingId.trim();
            bindingRevision = Math.max(0, bindingRevision);
            operatorRef = operatorRef == null ? "" : operatorRef.trim();
            operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
            adapterKind = adapterKind == null ? "" : adapterKind.trim().toLowerCase(Locale.ROOT);
            entrypoint = entrypoint == null ? "" : entrypoint.trim();
            runtimeOwner = runtimeOwner == null ? "" : runtimeOwner.trim();
            runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
            healthState = healthState == null || healthState.isBlank()
                    ? VisualRuntimeAdapterActivation.HEALTH_HEALTHY
                    : healthState.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            activatedBy = activatedBy == null ? "" : activatedBy.trim();
            changeSource = changeSource == null ? "" : changeSource.trim();
            reason = reason == null ? "" : reason.trim();
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * Creates a rejected result when the request body is absent.
     *
     * @return validation result
     */
    public static VisualRuntimeAdapterActivationValidation missingRequest() {
        return new VisualRuntimeAdapterActivationValidation(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                false,
                "rejected",
                "error",
                "Runtime adapter activation requires a request body.",
                "",
                "",
                0,
                "",
                "",
                "",
                "unknown",
                "",
                "",
                "",
                "",
                VisualRuntimeAdapterActivation.HEALTH_HEALTHY,
                "",
                "",
                List.of(VisualDiagnostic.error(
                        "visual.runtimeAdapterActivation.requestMissing",
                        "Runtime adapter activation requires a request body.",
                        "/"))
        );
    }

    /**
     * Validates one adapter activation request.
     *
     * @param request submitted activation request
     * @param binding active implementation binding when found
     * @param currentOperator current catalog operator when visible
     * @return validation result
     */
    public static VisualRuntimeAdapterActivationValidation from(
            Request request,
            VisualRuntimeBindingImplementationBinding binding,
            OperatorDefinition currentOperator) {
        if (request == null) {
            return missingRequest();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!REQUEST_SCHEMA_VERSION.equals(request.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.schemaVersionUnsupported",
                    "Runtime adapter activation request schemaVersion '%s' is not supported; expected '%s'."
                            .formatted(request.schemaVersion(), REQUEST_SCHEMA_VERSION),
                    "/schemaVersion",
                    Map.of("actual", request.schemaVersion(), "expected", REQUEST_SCHEMA_VERSION)));
        }
        if (request.bindingId().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.bindingIdMissing",
                    "Runtime adapter activation requires bindingId.",
                    "/bindingId"));
        }
        if (binding == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.bindingMissing",
                    "Runtime adapter activation binding '%s' does not exist.".formatted(request.bindingId()),
                    "/bindingId",
                    Map.of("bindingId", request.bindingId())));
        } else {
            addBindingDiagnostics(request, binding, diagnostics);
            addImplementationDiagnostics(request, binding.implementation(), diagnostics);
            addCatalogDiagnostics(binding, currentOperator, diagnostics);
            addSideEffectConformanceDiagnostics(request, binding, currentOperator, diagnostics);
        }
        addRuntimeAssertionDiagnostics(request, diagnostics);
        String state = diagnostics.stream().anyMatch(VisualDiagnostic::error) ? "rejected" : "ready-to-activate";
        return new VisualRuntimeAdapterActivationValidation(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                true,
                state,
                "ready-to-activate".equals(state) ? "success" : "error",
                validationMessage(state, request.bindingId(), diagnostics.size()),
                request.activationId(),
                binding == null ? request.bindingId() : binding.bindingId(),
                binding == null ? request.bindingRevision() : binding.revision(),
                binding == null ? request.operatorRef() : binding.operatorRef(),
                binding == null ? request.operatorFingerprint() : binding.operatorFingerprint(),
                currentOperator == null ? "" : currentOperator.fingerprint(),
                catalogState(binding, currentOperator),
                binding == null || binding.implementation() == null
                        ? request.adapterKind()
                        : binding.implementation().adapterKind(),
                binding == null || binding.implementation() == null
                        ? request.entrypoint()
                        : binding.implementation().entrypoint(),
                binding == null || binding.implementation() == null
                        ? request.runtimeOwner()
                        : binding.implementation().runtimeOwner(),
                request.runtimeEnvironment(),
                request.healthState(),
                request.activatedBy(),
                request.reason(),
                diagnostics
        );
    }

    private static void addBindingDiagnostics(Request request,
                                              VisualRuntimeBindingImplementationBinding binding,
                                              List<VisualDiagnostic> diagnostics) {
        if (!binding.bound()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.bindingNotBound",
                    "Runtime adapter activation requires a bound implementation; binding '%s' is in state '%s'."
                            .formatted(binding.bindingId(), binding.state()),
                    "/bindingId",
                    Map.of("bindingId", binding.bindingId(), "state", binding.state())));
        }
        if (request.bindingRevision() == 0) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.bindingRevisionMissing",
                    "Runtime adapter activation requires bindingRevision.",
                    "/bindingRevision"));
        } else if (request.bindingRevision() != binding.revision()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.bindingRevisionMismatch",
                    "Runtime adapter activation bindingRevision '%d' does not match current binding revision '%d'."
                            .formatted(request.bindingRevision(), binding.revision()),
                    "/bindingRevision",
                    Map.of("actual", request.bindingRevision(), "expected", binding.revision())));
        }
        if (request.operatorRef().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.operatorRefMissing",
                    "Runtime adapter activation requires operatorRef.",
                    "/operatorRef"));
        } else if (!request.operatorRef().equals(binding.operatorRef())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.operatorRefMismatch",
                    "Runtime adapter activation operatorRef '%s' does not match binding operatorRef '%s'."
                            .formatted(request.operatorRef(), binding.operatorRef()),
                    "/operatorRef",
                    Map.of("actual", request.operatorRef(), "expected", binding.operatorRef())));
        }
        if (request.operatorFingerprint().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.operatorFingerprintMissing",
                    "Runtime adapter activation requires operatorFingerprint.",
                    "/operatorFingerprint"));
        } else if (!request.operatorFingerprint().equals(binding.operatorFingerprint())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.operatorFingerprintMismatch",
                    "Runtime adapter activation operatorFingerprint '%s' does not match binding fingerprint '%s'."
                            .formatted(request.operatorFingerprint(), binding.operatorFingerprint()),
                    "/operatorFingerprint",
                    Map.of("actual", request.operatorFingerprint(), "expected", binding.operatorFingerprint())));
        }
    }

    private static void addImplementationDiagnostics(
            Request request,
            VisualRuntimeBindingImplementationValidation.ImplementationMetadata implementation,
            List<VisualDiagnostic> diagnostics) {
        if (implementation == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.implementationMissing",
                    "Runtime adapter activation requires implementation metadata on the bound binding.",
                    "/bindingId"));
            return;
        }
        if (request.adapterKind().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.adapterKindMissing",
                    "Runtime adapter activation requires adapterKind.",
                    "/adapterKind"));
        } else if (!request.adapterKind().equals(implementation.adapterKind())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.adapterKindMismatch",
                    "Runtime adapter activation adapterKind '%s' does not match binding adapterKind '%s'."
                            .formatted(request.adapterKind(), implementation.adapterKind()),
                    "/adapterKind",
                    Map.of("actual", request.adapterKind(), "expected", implementation.adapterKind())));
        }
        if (request.entrypoint().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.entrypointMissing",
                    "Runtime adapter activation requires entrypoint.",
                    "/entrypoint"));
        } else if (!request.entrypoint().equals(implementation.entrypoint())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.entrypointMismatch",
                    "Runtime adapter activation entrypoint '%s' does not match binding entrypoint '%s'."
                            .formatted(request.entrypoint(), implementation.entrypoint()),
                    "/entrypoint",
                    Map.of("actual", request.entrypoint(), "expected", implementation.entrypoint())));
        }
        if (request.runtimeOwner().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.runtimeOwnerMissing",
                    "Runtime adapter activation requires runtimeOwner.",
                    "/runtimeOwner"));
        } else if (!request.runtimeOwner().equals(implementation.runtimeOwner())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.runtimeOwnerMismatch",
                    "Runtime adapter activation runtimeOwner '%s' does not match binding runtimeOwner '%s'."
                            .formatted(request.runtimeOwner(), implementation.runtimeOwner()),
                    "/runtimeOwner",
                    Map.of("actual", request.runtimeOwner(), "expected", implementation.runtimeOwner())));
        }
    }

    private static void addCatalogDiagnostics(VisualRuntimeBindingImplementationBinding binding,
                                              OperatorDefinition currentOperator,
                                              List<VisualDiagnostic> diagnostics) {
        if (currentOperator == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.catalogMissing",
                    "Current catalog does not contain operatorRef '%s'; activation cannot prove current runtime readiness."
                            .formatted(binding.operatorRef()),
                    "/operatorRef",
                    Map.of("operatorRef", binding.operatorRef())));
            return;
        }
        if (!binding.operatorFingerprint().equals(currentOperator.fingerprint())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.catalogFingerprintDrift",
                    "Current catalog fingerprint '%s' differs from bound implementation fingerprint '%s'."
                            .formatted(currentOperator.fingerprint(), binding.operatorFingerprint()),
                    "/operatorFingerprint",
                    Map.of(
                            "current", currentOperator.fingerprint(),
                            "bound", binding.operatorFingerprint())));
        }
    }

    private static void addRuntimeAssertionDiagnostics(Request request, List<VisualDiagnostic> diagnostics) {
        if (request.runtimeEnvironment().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.runtimeEnvironmentMissing",
                    "Runtime adapter activation requires runtimeEnvironment.",
                    "/runtimeEnvironment"));
        }
        if (!VisualRuntimeAdapterActivation.HEALTH_HEALTHY.equals(request.healthState())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.healthStateNotHealthy",
                    "Runtime adapter activation requires healthState 'healthy', but received '%s'."
                            .formatted(request.healthState()),
                    "/healthState",
                    Map.of("actual", request.healthState(),
                            "expected", VisualRuntimeAdapterActivation.HEALTH_HEALTHY)));
        }
        if (request.activatedBy().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.activatedByMissing",
                    "Runtime adapter activation requires activatedBy.",
                    "/activatedBy"));
        }
        if (request.reason().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.reasonMissing",
                    "Runtime adapter activation requires reason.",
                    "/reason"));
        }
        if (request.evidence().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.evidenceMissing",
                    "Runtime adapter activation requires at least one evidence item.",
                    "/evidence"));
        }
    }

    private static void addSideEffectConformanceDiagnostics(
            Request request,
            VisualRuntimeBindingImplementationBinding binding,
            OperatorDefinition currentOperator,
            List<VisualDiagnostic> diagnostics) {
        if (currentOperator == null || !currentOperator.capabilities().externalWrite()) {
            return;
        }
        if (!currentOperator.capabilities().sideEffectProtocol().managedWrite()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.sideEffectProtocolUnmanaged",
                    "External-write adapter activation requires a current managed side-effect protocol contract.",
                    "/operatorRef"));
            return;
        }
        Set<String> implementationCapabilities = binding.implementation().capabilities().stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT).replace('-', '_'))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> requiredCapabilities = Set.of(
                "SIDE_EFFECT_JOURNAL_V1", "COMMIT_RECEIPT_V1", "RECONCILIATION_LOOKUP_V1");
        if (!implementationCapabilities.containsAll(requiredCapabilities)) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.sideEffectCapabilitiesMissing",
                    "The bound external-write implementation no longer proves all side-effect protocol capabilities.",
                    "/bindingId"));
        }
        Set<String> activationEvidence = request.evidence().stream()
                .map(VisualRuntimeAdapterActivation.Evidence::kind)
                .collect(java.util.stream.Collectors.toSet());
        if (!activationEvidence.contains("reconciler-health")) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeAdapterActivation.reconcilerHealthEvidenceMissing",
                    "External-write adapter activation requires current reconciler-health evidence.",
                    "/evidence"));
        }
    }

    private static String catalogState(VisualRuntimeBindingImplementationBinding binding,
                                       OperatorDefinition currentOperator) {
        if (binding == null || currentOperator == null) {
            return "missing";
        }
        return binding.operatorFingerprint().equals(currentOperator.fingerprint()) ? "current" : "drifted";
    }

    private static String validationMessage(String state, String bindingId, int diagnosticCount) {
        String target = bindingId == null || bindingId.isBlank() ? "binding" : bindingId;
        if ("ready-to-activate".equals(state)) {
            return "Runtime adapter activation for %s is ready to persist.".formatted(target);
        }
        return "Runtime adapter activation for %s was rejected: %d diagnostic(s)."
                .formatted(target, diagnosticCount);
    }
}
