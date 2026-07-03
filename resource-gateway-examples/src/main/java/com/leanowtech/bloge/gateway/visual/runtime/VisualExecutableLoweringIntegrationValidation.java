package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stateless validation result for a BLOGE executable lowering integration assertion.
 *
 * <p>This validation is the last control-plane gate before a future readiness
 * recomputation. It proves that an active runtime adapter activation has an
 * executor/lowering bridge, but it still does not rewrite operator definitions
 * or graph artifacts.</p>
 *
 * @param schemaVersion validation response contract version
 * @param validatedAt server validation timestamp
 * @param valid true when no blocking validation diagnostics were found
 * @param integratable true when the request can be persisted
 * @param state ready-to-integrate or rejected
 * @param level UI/control-plane severity
 * @param message human-readable validation summary
 * @param integrationId submitted integration id
 * @param activationId adapter activation id being integrated
 * @param activationRevision adapter activation revision observed by the executor platform
 * @param bindingId implementation binding id
 * @param bindingRevision implementation binding revision
 * @param operatorRef operator being integrated
 * @param operatorFingerprint operator fingerprint observed at integration time
 * @param currentCatalogFingerprint current catalog fingerprint for the operator
 * @param currentCatalogState current catalog comparison state
 * @param adapterKind runtime adapter kind
 * @param entrypoint runtime adapter entrypoint
 * @param runtimeEnvironment runtime environment
 * @param loweringMode executable lowering mode exposed to BLOGE
 * @param executorKind executor integration kind
 * @param executorEntrypoint executable BLOGE lowering/executor entrypoint
 * @param executorOwner owning executor platform team or service
 * @param integratedBy principal or service that confirmed integration
 * @param reason human-readable integration reason
 * @param diagnostics structured validation diagnostics
 */
public record VisualExecutableLoweringIntegrationValidation(
        String schemaVersion,
        Instant validatedAt,
        boolean valid,
        boolean integratable,
        String state,
        String level,
        String message,
        String integrationId,
        String activationId,
        long activationRevision,
        String bindingId,
        long bindingRevision,
        String operatorRef,
        String operatorFingerprint,
        String currentCatalogFingerprint,
        String currentCatalogState,
        String adapterKind,
        String entrypoint,
        String runtimeEnvironment,
        String loweringMode,
        String executorKind,
        String executorEntrypoint,
        String executorOwner,
        String integratedBy,
        String reason,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualExecutableLoweringIntegrationValidation.v1";
    public static final String REQUEST_SCHEMA_VERSION = "bloge.visualExecutableLoweringIntegrationRequest.v1";

    /**
     * Creates a normalized validation result.
     */
    public VisualExecutableLoweringIntegrationValidation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        validatedAt = validatedAt == null ? Instant.now() : validatedAt;
        state = state == null || state.isBlank() ? "rejected" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "error" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        integrationId = integrationId == null ? "" : integrationId.trim();
        activationId = activationId == null ? "" : activationId.trim();
        activationRevision = Math.max(0, activationRevision);
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
        runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
        loweringMode = loweringMode == null ? "" : loweringMode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        executorKind = executorKind == null ? "" : executorKind.trim().toLowerCase(Locale.ROOT);
        executorEntrypoint = executorEntrypoint == null ? "" : executorEntrypoint.trim();
        executorOwner = executorOwner == null ? "" : executorOwner.trim();
        integratedBy = integratedBy == null ? "" : integratedBy.trim();
        reason = reason == null ? "" : reason.trim();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        integratable = valid;
    }

    /**
     * Submitted executable lowering integration request.
     *
     * @param schemaVersion request contract version
     * @param integrationId caller-supplied integration id
     * @param activationId adapter activation id being integrated
     * @param activationRevision activation revision observed by the executor platform
     * @param bindingId implementation binding id echoed from the activation
     * @param bindingRevision binding revision echoed from the activation
     * @param operatorRef operator reference echoed from the activation
     * @param operatorFingerprint operator fingerprint echoed from the activation
     * @param adapterKind runtime adapter kind echoed from the activation
     * @param entrypoint runtime adapter entrypoint echoed from the activation
     * @param runtimeEnvironment runtime environment echoed from the activation
     * @param loweringMode executable lowering mode exposed to BLOGE
     * @param executorKind executor integration kind
     * @param executorEntrypoint executable BLOGE lowering/executor entrypoint
     * @param executorOwner owning executor platform team or service
     * @param integratedBy principal or service confirming integration
     * @param changeSource source system or workflow
     * @param reason human-readable integration reason
     * @param evidence external integration evidence
     */
    public record Request(
            String schemaVersion,
            String integrationId,
            String activationId,
            long activationRevision,
            String bindingId,
            long bindingRevision,
            String operatorRef,
            String operatorFingerprint,
            String adapterKind,
            String entrypoint,
            String runtimeEnvironment,
            String loweringMode,
            String executorKind,
            String executorEntrypoint,
            String executorOwner,
            String integratedBy,
            String changeSource,
            String reason,
            List<VisualExecutableLoweringIntegration.Evidence> evidence
    ) {
        public Request {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? REQUEST_SCHEMA_VERSION
                    : schemaVersion.trim();
            integrationId = integrationId == null ? "" : integrationId.trim();
            activationId = activationId == null ? "" : activationId.trim();
            activationRevision = Math.max(0, activationRevision);
            bindingId = bindingId == null ? "" : bindingId.trim();
            bindingRevision = Math.max(0, bindingRevision);
            operatorRef = operatorRef == null ? "" : operatorRef.trim();
            operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
            adapterKind = adapterKind == null ? "" : adapterKind.trim().toLowerCase(Locale.ROOT);
            entrypoint = entrypoint == null ? "" : entrypoint.trim();
            runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
            loweringMode = loweringMode == null ? "" : loweringMode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            executorKind = executorKind == null ? "" : executorKind.trim().toLowerCase(Locale.ROOT);
            executorEntrypoint = executorEntrypoint == null ? "" : executorEntrypoint.trim();
            executorOwner = executorOwner == null ? "" : executorOwner.trim();
            integratedBy = integratedBy == null ? "" : integratedBy.trim();
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
    public static VisualExecutableLoweringIntegrationValidation missingRequest() {
        return new VisualExecutableLoweringIntegrationValidation(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                false,
                "rejected",
                "error",
                "Executable lowering integration requires a request body.",
                "",
                "",
                0,
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
                "",
                "",
                "",
                "",
                "",
                List.of(VisualDiagnostic.error(
                        "visual.executableLoweringIntegration.requestMissing",
                        "Executable lowering integration requires a request body.",
                        "/"))
        );
    }

    /**
     * Validates one executable lowering integration request.
     *
     * @param request submitted integration request
     * @param activation active adapter activation when found
     * @param binding bound implementation binding when found
     * @param currentOperator current catalog operator when visible
     * @return validation result
     */
    public static VisualExecutableLoweringIntegrationValidation from(
            Request request,
            VisualRuntimeAdapterActivation activation,
            VisualRuntimeBindingImplementationBinding binding,
            OperatorDefinition currentOperator) {
        if (request == null) {
            return missingRequest();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!REQUEST_SCHEMA_VERSION.equals(request.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.schemaVersionUnsupported",
                    "Executable lowering integration request schemaVersion '%s' is not supported; expected '%s'."
                            .formatted(request.schemaVersion(), REQUEST_SCHEMA_VERSION),
                    "/schemaVersion",
                    Map.of("actual", request.schemaVersion(), "expected", REQUEST_SCHEMA_VERSION)));
        }
        addActivationDiagnostics(request, activation, diagnostics);
        addBindingDiagnostics(request, activation, binding, diagnostics);
        addCatalogDiagnostics(request, currentOperator, diagnostics);
        addExecutorDiagnostics(request, diagnostics);
        String state = diagnostics.stream().anyMatch(VisualDiagnostic::error) ? "rejected" : "ready-to-integrate";
        return new VisualExecutableLoweringIntegrationValidation(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                true,
                state,
                "ready-to-integrate".equals(state) ? "success" : "error",
                validationMessage(state, request.operatorRef(), diagnostics.size()),
                request.integrationId(),
                request.activationId(),
                request.activationRevision(),
                request.bindingId(),
                request.bindingRevision(),
                request.operatorRef(),
                request.operatorFingerprint(),
                currentOperator == null ? "" : currentOperator.fingerprint(),
                catalogState(request.operatorFingerprint(), currentOperator),
                request.adapterKind(),
                request.entrypoint(),
                request.runtimeEnvironment(),
                request.loweringMode(),
                request.executorKind(),
                request.executorEntrypoint(),
                request.executorOwner(),
                request.integratedBy(),
                request.reason(),
                diagnostics
        );
    }

    private static void addActivationDiagnostics(Request request,
                                                 VisualRuntimeAdapterActivation activation,
                                                 List<VisualDiagnostic> diagnostics) {
        if (request.activationId().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.activationIdMissing",
                    "Executable lowering integration requires activationId.",
                    "/activationId"));
        }
        if (activation == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.activationMissing",
                    "Executable lowering integration activation '%s' does not exist."
                            .formatted(request.activationId()),
                    "/activationId",
                    Map.of("activationId", request.activationId())));
            return;
        }
        addMismatch("activationRevision", request.activationRevision(), activation.revision(),
                "/activationRevision", diagnostics);
        addMismatch("bindingId", request.bindingId(), activation.bindingId(), "/bindingId", diagnostics);
        addMismatch("bindingRevision", request.bindingRevision(), activation.bindingRevision(),
                "/bindingRevision", diagnostics);
        addMismatch("operatorRef", request.operatorRef(), activation.operatorRef(), "/operatorRef", diagnostics);
        addMismatch("operatorFingerprint", request.operatorFingerprint(), activation.operatorFingerprint(),
                "/operatorFingerprint", diagnostics);
        addMismatch("adapterKind", request.adapterKind(), activation.adapterKind(), "/adapterKind", diagnostics);
        addMismatch("entrypoint", request.entrypoint(), activation.entrypoint(), "/entrypoint", diagnostics);
        addMismatch("runtimeEnvironment", request.runtimeEnvironment(), activation.runtimeEnvironment(),
                "/runtimeEnvironment", diagnostics);
        if (!activation.active()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.activationNotActive",
                    "Executable lowering integration requires an active adapter activation.",
                    "/activationId",
                    Map.of("activationId", activation.activationId(), "state", activation.state())));
        }
        if (!VisualRuntimeAdapterActivation.HEALTH_HEALTHY.equals(activation.healthState())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.activationNotHealthy",
                    "Executable lowering integration requires a healthy adapter activation.",
                    "/activationId",
                    Map.of("activationId", activation.activationId(), "healthState", activation.healthState())));
        }
    }

    private static void addBindingDiagnostics(Request request,
                                              VisualRuntimeAdapterActivation activation,
                                              VisualRuntimeBindingImplementationBinding binding,
                                              List<VisualDiagnostic> diagnostics) {
        if (binding == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.bindingMissing",
                    "Executable lowering integration binding '%s' does not exist.".formatted(request.bindingId()),
                    "/bindingId",
                    Map.of("bindingId", request.bindingId())));
            return;
        }
        if (!binding.bound()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.bindingNotBound",
                    "Executable lowering integration requires a bound implementation binding.",
                    "/bindingId",
                    Map.of("bindingId", binding.bindingId(), "state", binding.state())));
        }
        if (activation != null && !activation.bindingId().equals(binding.bindingId())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.activationBindingMismatch",
                    "Adapter activation bindingId '%s' does not match implementation bindingId '%s'."
                            .formatted(activation.bindingId(), binding.bindingId()),
                    "/bindingId",
                    Map.of("activationBindingId", activation.bindingId(), "bindingId", binding.bindingId())));
        }
        addMismatch("bindingRevision", request.bindingRevision(), binding.revision(),
                "/bindingRevision", diagnostics);
        addMismatch("operatorFingerprint", request.operatorFingerprint(), binding.operatorFingerprint(),
                "/operatorFingerprint", diagnostics);
    }

    private static void addCatalogDiagnostics(Request request,
                                              OperatorDefinition currentOperator,
                                              List<VisualDiagnostic> diagnostics) {
        if (currentOperator == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.catalogMissing",
                    "Executable lowering integration requires the operator to be visible in the current catalog.",
                    "/operatorRef",
                    Map.of("operatorRef", request.operatorRef())));
            return;
        }
        addMismatch("operatorRef", request.operatorRef(), currentOperator.operatorRef(), "/operatorRef", diagnostics);
        addMismatch("operatorFingerprint", request.operatorFingerprint(), currentOperator.fingerprint(),
                "/operatorFingerprint", diagnostics);
    }

    private static void addExecutorDiagnostics(Request request, List<VisualDiagnostic> diagnostics) {
        if (request.loweringMode().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.loweringModeMissing",
                    "Executable lowering integration requires loweringMode.",
                    "/loweringMode"));
        } else if ("design".equals(request.loweringMode())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.designLoweringUnsupported",
                    "Executable lowering integration cannot use loweringMode=design.",
                    "/loweringMode"));
        }
        if (request.executorKind().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.executorKindMissing",
                    "Executable lowering integration requires executorKind.",
                    "/executorKind"));
        }
        if (request.executorEntrypoint().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.executorEntrypointMissing",
                    "Executable lowering integration requires executorEntrypoint.",
                    "/executorEntrypoint"));
        }
        if (request.executorOwner().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.executorOwnerMissing",
                    "Executable lowering integration requires executorOwner.",
                    "/executorOwner"));
        }
        if (request.integratedBy().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.integratedByMissing",
                    "Executable lowering integration requires integratedBy.",
                    "/integratedBy"));
        }
        if (request.reason().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.reasonMissing",
                    "Executable lowering integration requires reason.",
                    "/reason"));
        }
        if (request.evidence().isEmpty() || request.evidence().stream().allMatch(evidence -> evidence.ref().isBlank())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.evidenceMissing",
                    "Executable lowering integration requires non-empty executor evidence.",
                    "/evidence"));
        }
    }

    private static void addMismatch(String field,
                                    String actual,
                                    String expected,
                                    String target,
                                    List<VisualDiagnostic> diagnostics) {
        String normalizedActual = actual == null ? "" : actual.trim();
        String normalizedExpected = expected == null ? "" : expected.trim();
        if (normalizedActual.isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.%sMissing".formatted(field),
                    "Executable lowering integration requires %s.".formatted(field),
                    target));
            return;
        }
        if (!normalizedActual.equals(normalizedExpected)) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.%sMismatch".formatted(field),
                    "Executable lowering integration %s '%s' does not match expected '%s'."
                            .formatted(field, normalizedActual, normalizedExpected),
                    target,
                    Map.of("actual", normalizedActual, "expected", normalizedExpected)));
        }
    }

    private static void addMismatch(String field,
                                    long actual,
                                    long expected,
                                    String target,
                                    List<VisualDiagnostic> diagnostics) {
        if (actual <= 0) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.%sMissing".formatted(field),
                    "Executable lowering integration requires %s.".formatted(field),
                    target));
            return;
        }
        if (actual != expected) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableLoweringIntegration.%sMismatch".formatted(field),
                    "Executable lowering integration %s '%d' does not match expected '%d'."
                            .formatted(field, actual, expected),
                    target,
                    Map.of("actual", actual, "expected", expected)));
        }
    }

    private static String catalogState(String operatorFingerprint, OperatorDefinition currentOperator) {
        if (currentOperator == null) {
            return "missing";
        }
        if (operatorFingerprint == null || operatorFingerprint.isBlank()) {
            return "unknown";
        }
        return operatorFingerprint.equals(currentOperator.fingerprint()) ? "current" : "drifted";
    }

    private static String validationMessage(String state, String operatorRef, int diagnosticCount) {
        String target = operatorRef == null || operatorRef.isBlank() ? "operator" : operatorRef;
        return switch (state) {
            case "ready-to-integrate" -> "Executable lowering integration for %s is ready to persist."
                    .formatted(target);
            default -> "Executable lowering integration for %s was rejected: %d diagnostic(s)."
                    .formatted(target, diagnosticCount);
        };
    }
}
