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
 * Stateless validation result for a runtime rollout execution observation.
 *
 * <p>Observation validation verifies identity, revision, fingerprint, rollout
 * strategy, traffic window, and governance evidence before a runtime-plane
 * canary/ramp/rollback fact is persisted.</p>
 *
 * @param schemaVersion validation response contract version
 * @param validatedAt server validation timestamp
 * @param valid true when no blocking validation diagnostics were found
 * @param recordable true when the observation request can be persisted
 * @param state ready-to-record or rejected
 * @param level UI/control-plane severity; failed rollout facts remain recordable but are level=error
 * @param message human-readable validation summary
 * @param observationId submitted observation id
 * @param activationId adapter activation id being observed
 * @param activationRevision adapter activation revision observed by the rollout system
 * @param bindingId implementation binding id
 * @param bindingRevision implementation binding revision
 * @param operatorRef operator being rolled out
 * @param operatorFingerprint operator fingerprint observed at rollout time
 * @param currentCatalogFingerprint current catalog fingerprint for the operator
 * @param currentCatalogState current catalog comparison state
 * @param adapterKind runtime adapter kind
 * @param runtimeEnvironment concrete runtime environment
 * @param rolloutStrategy rollout strategy being executed
 * @param trafficPercent observed traffic percentage for this rollout step
 * @param rolloutPhase rollout phase label
 * @param observationState in-progress, healthy, degraded, failed, rolled-back, or completed
 * @param rollbackTriggered whether rollback was triggered by this observation
 * @param rollbackSignal concrete rollback signal when rollback was triggered
 * @param rolloutSignals structured rollout guardrail signals observed by the runtime system
 * @param observedBy principal or service that emitted the observation
 * @param reason human-readable observation reason
 * @param diagnostics structured validation diagnostics
 */
public record VisualRuntimeRolloutObservationValidation(
        String schemaVersion,
        Instant validatedAt,
        boolean valid,
        boolean recordable,
        String state,
        String level,
        String message,
        String observationId,
        String activationId,
        long activationRevision,
        String bindingId,
        long bindingRevision,
        String operatorRef,
        String operatorFingerprint,
        String currentCatalogFingerprint,
        String currentCatalogState,
        String adapterKind,
        String runtimeEnvironment,
        String rolloutStrategy,
        int trafficPercent,
        String rolloutPhase,
        String observationState,
        boolean rollbackTriggered,
        String rollbackSignal,
        List<VisualRuntimeRolloutObservation.RolloutSignal> rolloutSignals,
        String observedBy,
        String reason,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeRolloutObservationValidation.v1";
    public static final String REQUEST_SCHEMA_VERSION = "bloge.visualRuntimeRolloutObservationRequest.v1";

    private static final Set<String> SUPPORTED_OBSERVATION_STATES = Set.of(
            VisualRuntimeRolloutObservation.STATE_IN_PROGRESS,
            VisualRuntimeRolloutObservation.STATE_HEALTHY,
            VisualRuntimeRolloutObservation.STATE_DEGRADED,
            VisualRuntimeRolloutObservation.STATE_FAILED,
            VisualRuntimeRolloutObservation.STATE_ROLLED_BACK,
            VisualRuntimeRolloutObservation.STATE_COMPLETED
    );

    /**
     * Creates a normalized validation result.
     */
    public VisualRuntimeRolloutObservationValidation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        validatedAt = validatedAt == null ? Instant.now() : validatedAt;
        state = state == null || state.isBlank() ? "rejected" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "error" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        observationId = observationId == null ? "" : observationId.trim();
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
        runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
        rolloutStrategy = VisualRuntimeRolloutObservation.normalizeState(rolloutStrategy, "");
        trafficPercent = Math.max(0, Math.min(100, trafficPercent));
        rolloutPhase = VisualRuntimeRolloutObservation.normalizeState(rolloutPhase, "");
        observationState = VisualRuntimeRolloutObservation.normalizeState(
                observationState,
                VisualRuntimeRolloutObservation.STATE_IN_PROGRESS);
        rollbackSignal = rollbackSignal == null ? "" : rollbackSignal.trim();
        rolloutSignals = rolloutSignals == null ? List.of() : List.copyOf(rolloutSignals);
        observedBy = observedBy == null ? "" : observedBy.trim();
        reason = reason == null ? "" : reason.trim();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        recordable = valid;
    }

    /**
     * Submitted runtime rollout observation request.
     *
     * @param schemaVersion request contract version
     * @param observationId caller-supplied observation id
     * @param activationId adapter activation id being observed
     * @param activationRevision activation revision observed by the rollout system
     * @param bindingId implementation binding id echoed from the activation
     * @param bindingRevision binding revision echoed from the activation
     * @param operatorRef operator reference echoed from the activation
     * @param operatorFingerprint operator fingerprint echoed from the activation
     * @param rolloutStrategy rollout strategy being executed
     * @param trafficPercent observed traffic percentage for this rollout step
     * @param rolloutPhase rollout phase label
     * @param observationState in-progress, healthy, degraded, failed, rolled-back, or completed
     * @param rollbackTriggered whether rollback was triggered by this observation
     * @param rollbackSignal concrete rollback signal when rollback was triggered
     * @param rolloutSignals structured rollout guardrail signals observed by the runtime system
     * @param observedBy principal or service that emitted the observation
     * @param changeSource source system or workflow
     * @param reason human-readable observation reason
     * @param evidence external rollout evidence
     * @param observedAt timestamp from the runtime observation source
     */
    public record Request(
            String schemaVersion,
            String observationId,
            String activationId,
            long activationRevision,
            String bindingId,
            long bindingRevision,
            String operatorRef,
            String operatorFingerprint,
            String rolloutStrategy,
            Integer trafficPercent,
            String rolloutPhase,
            String observationState,
            boolean rollbackTriggered,
            String rollbackSignal,
            List<VisualRuntimeRolloutObservation.RolloutSignal> rolloutSignals,
            String observedBy,
            String changeSource,
            String reason,
            List<VisualRuntimeRolloutObservation.Evidence> evidence,
            Instant observedAt
    ) {
        public Request {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? REQUEST_SCHEMA_VERSION
                    : schemaVersion.trim();
            observationId = observationId == null ? "" : observationId.trim();
            activationId = activationId == null ? "" : activationId.trim();
            activationRevision = Math.max(0, activationRevision);
            bindingId = bindingId == null ? "" : bindingId.trim();
            bindingRevision = Math.max(0, bindingRevision);
            operatorRef = operatorRef == null ? "" : operatorRef.trim();
            operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
            rolloutStrategy = VisualRuntimeRolloutObservation.normalizeState(rolloutStrategy, "");
            trafficPercent = trafficPercent == null ? -1 : trafficPercent;
            rolloutPhase = VisualRuntimeRolloutObservation.normalizeState(rolloutPhase, "");
            observationState = VisualRuntimeRolloutObservation.normalizeState(
                    observationState,
                    VisualRuntimeRolloutObservation.STATE_IN_PROGRESS);
            rollbackSignal = rollbackSignal == null ? "" : rollbackSignal.trim();
            rolloutSignals = rolloutSignals == null ? List.of() : List.copyOf(rolloutSignals);
            observedBy = observedBy == null ? "" : observedBy.trim();
            changeSource = changeSource == null ? "" : changeSource.trim();
            reason = reason == null ? "" : reason.trim();
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            observedAt = observedAt == null ? Instant.now() : observedAt;
        }
    }

    /**
     * Creates a rejected result when the request body is absent.
     *
     * @return validation result
     */
    public static VisualRuntimeRolloutObservationValidation missingRequest() {
        return new VisualRuntimeRolloutObservationValidation(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                false,
                "rejected",
                "error",
                "Runtime rollout observation requires a request body.",
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
                0,
                "",
                VisualRuntimeRolloutObservation.STATE_IN_PROGRESS,
                false,
                "",
                List.of(),
                "",
                "",
                List.of(VisualDiagnostic.error(
                        "visual.runtimeRolloutObservation.requestMissing",
                        "Runtime rollout observation requires a request body.",
                        "/"))
        );
    }

    /**
     * Validates one rollout observation request.
     *
     * @param request submitted observation request
     * @param activation active adapter activation when found
     * @param binding implementation binding when found
     * @param currentOperator current catalog operator when visible
     * @return validation result
     */
    public static VisualRuntimeRolloutObservationValidation from(
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
                    "visual.runtimeRolloutObservation.schemaVersionUnsupported",
                    "Runtime rollout observation request schemaVersion '%s' is not supported; expected '%s'."
                            .formatted(request.schemaVersion(), REQUEST_SCHEMA_VERSION),
                    "/schemaVersion",
                    Map.of("actual", request.schemaVersion(), "expected", REQUEST_SCHEMA_VERSION)));
        }
        addActivationDiagnostics(request, activation, diagnostics);
        addBindingDiagnostics(request, activation, binding, diagnostics);
        addCatalogDiagnostics(request, currentOperator, diagnostics);
        addRolloutDiagnostics(request, binding, diagnostics);
        addObservationDiagnostics(request, diagnostics);

        boolean rejected = diagnostics.stream().anyMatch(VisualDiagnostic::error);
        String nextState = rejected ? "rejected" : "ready-to-record";
        String nextLevel = rejected
                ? "error"
                : VisualRuntimeRolloutObservation.levelForState(request.observationState());
        return new VisualRuntimeRolloutObservationValidation(
                SCHEMA_VERSION,
                Instant.now(),
                !rejected,
                !rejected,
                nextState,
                nextLevel,
                validationMessage(nextState, request.activationId(), diagnostics.size()),
                request.observationId(),
                activation == null ? request.activationId() : activation.activationId(),
                activation == null ? request.activationRevision() : activation.revision(),
                binding == null ? request.bindingId() : binding.bindingId(),
                binding == null ? request.bindingRevision() : binding.revision(),
                activation == null ? request.operatorRef() : activation.operatorRef(),
                activation == null ? request.operatorFingerprint() : activation.operatorFingerprint(),
                currentOperator == null ? "" : currentOperator.fingerprint(),
                catalogState(request.operatorFingerprint(), currentOperator),
                activation == null ? "" : activation.adapterKind(),
                activation == null ? "" : activation.runtimeEnvironment(),
                request.rolloutStrategy(),
                Math.max(0, request.trafficPercent()),
                request.rolloutPhase(),
                request.observationState(),
                request.rollbackTriggered(),
                request.rollbackSignal(),
                request.rolloutSignals(),
                request.observedBy(),
                request.reason(),
                diagnostics
        );
    }

    private static void addActivationDiagnostics(Request request,
                                                 VisualRuntimeAdapterActivation activation,
                                                 List<VisualDiagnostic> diagnostics) {
        if (request.activationId().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.activationIdMissing",
                    "Runtime rollout observation requires activationId.",
                    "/activationId"));
            return;
        }
        if (activation == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.activationMissing",
                    "Runtime rollout observation activation '%s' does not exist."
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
        if (!activation.active()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.activationNotActive",
                    "Runtime rollout observation requires an active adapter activation.",
                    "/activationId",
                    Map.of("activationId", activation.activationId(), "state", activation.state())));
        }
    }

    private static void addBindingDiagnostics(Request request,
                                              VisualRuntimeAdapterActivation activation,
                                              VisualRuntimeBindingImplementationBinding binding,
                                              List<VisualDiagnostic> diagnostics) {
        if (binding == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.bindingMissing",
                    "Runtime rollout observation binding '%s' does not exist.".formatted(request.bindingId()),
                    "/bindingId",
                    Map.of("bindingId", request.bindingId())));
            return;
        }
        if (!binding.bound()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.bindingNotBound",
                    "Runtime rollout observation requires a bound implementation binding.",
                    "/bindingId",
                    Map.of("bindingId", binding.bindingId(), "state", binding.state())));
        }
        if (activation != null && !activation.bindingId().equals(binding.bindingId())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.activationBindingMismatch",
                    "Adapter activation bindingId '%s' does not match implementation bindingId '%s'."
                            .formatted(activation.bindingId(), binding.bindingId()),
                    "/bindingId",
                    Map.of("activationBindingId", activation.bindingId(), "bindingId", binding.bindingId())));
        }
        addMismatch("bindingRevision", request.bindingRevision(), binding.revision(), "/bindingRevision", diagnostics);
        addMismatch("operatorFingerprint", request.operatorFingerprint(), binding.operatorFingerprint(),
                "/operatorFingerprint", diagnostics);
    }

    private static void addCatalogDiagnostics(Request request,
                                              OperatorDefinition currentOperator,
                                              List<VisualDiagnostic> diagnostics) {
        if (currentOperator == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.catalogMissing",
                    "Runtime rollout observation requires the operator to be visible in the current catalog.",
                    "/operatorRef",
                    Map.of("operatorRef", request.operatorRef())));
            return;
        }
        addMismatch("operatorRef", request.operatorRef(), currentOperator.operatorRef(), "/operatorRef", diagnostics);
        addMismatch("operatorFingerprint", request.operatorFingerprint(), currentOperator.fingerprint(),
                "/operatorFingerprint", diagnostics);
    }

    private static void addRolloutDiagnostics(Request request,
                                              VisualRuntimeBindingImplementationBinding binding,
                                              List<VisualDiagnostic> diagnostics) {
        VisualRuntimeBindingImplementationValidation.RolloutPlan plan =
                binding == null || binding.implementation() == null
                        ? null
                        : binding.implementation().rolloutPlan();
        if (plan == null || plan.strategy().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.rolloutPlanMissing",
                    "Runtime rollout observation requires rolloutPlan on the bound implementation.",
                    "/rolloutStrategy"));
            return;
        }
        if (request.rolloutStrategy().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.rolloutStrategyMissing",
                    "Runtime rollout observation requires rolloutStrategy.",
                    "/rolloutStrategy"));
        } else if (!request.rolloutStrategy().equals(plan.strategy())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.rolloutStrategyMismatch",
                    "Runtime rollout observation rolloutStrategy '%s' does not match bound rolloutPlan strategy '%s'."
                            .formatted(request.rolloutStrategy(), plan.strategy()),
                    "/rolloutStrategy",
                    Map.of("actual", request.rolloutStrategy(), "expected", plan.strategy())));
        }
        if (request.trafficPercent() < 0 || request.trafficPercent() > 100) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.trafficPercentInvalid",
                    "Runtime rollout observation trafficPercent must be between 0 and 100.",
                    "/trafficPercent",
                    Map.of("actual", request.trafficPercent())));
        } else if (plan.maxTrafficPercent() > 0 && request.trafficPercent() > plan.maxTrafficPercent()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.trafficExceedsPlan",
                    "Runtime rollout observation trafficPercent '%d' exceeds rolloutPlan maxTrafficPercent '%d'."
                            .formatted(request.trafficPercent(), plan.maxTrafficPercent()),
                    "/trafficPercent",
                    Map.of("actual", request.trafficPercent(), "maxTrafficPercent", plan.maxTrafficPercent())));
        }
    }

    private static void addObservationDiagnostics(Request request, List<VisualDiagnostic> diagnostics) {
        if (request.rolloutPhase().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.rolloutPhaseMissing",
                    "Runtime rollout observation requires rolloutPhase.",
                    "/rolloutPhase"));
        }
        if (!SUPPORTED_OBSERVATION_STATES.contains(request.observationState())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.observationStateUnsupported",
                    "Runtime rollout observation state '%s' is not supported."
                            .formatted(request.observationState()),
                    "/observationState",
                    Map.of("actual", request.observationState(),
                            "supported", String.join(",", SUPPORTED_OBSERVATION_STATES))));
        }
        if (request.rollbackTriggered() || VisualRuntimeRolloutObservation.STATE_ROLLED_BACK
                .equals(request.observationState())) {
            if (request.rollbackSignal().isBlank()) {
                diagnostics.add(VisualDiagnostic.error(
                        "visual.runtimeRolloutObservation.rollbackSignalMissing",
                        "Runtime rollout observation requires rollbackSignal when rollback is triggered.",
                        "/rollbackSignal"));
            }
        }
        for (int index = 0; index < request.rolloutSignals().size(); index++) {
            VisualRuntimeRolloutObservation.RolloutSignal signal = request.rolloutSignals().get(index);
            if (signal == null || signal.name().isBlank()) {
                diagnostics.add(VisualDiagnostic.warning(
                        "visual.runtimeRolloutObservation.rolloutSignalNameMissing",
                        "Runtime rollout observation signal should include a stable name for aggregation.",
                        "/rolloutSignals/%d/name".formatted(index)));
            }
        }
        if (riskyObservation(request) && request.rolloutSignals().stream()
                .filter(signal -> signal != null)
                .noneMatch(VisualRuntimeRolloutObservation.RolloutSignal::breached)) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.runtimeRolloutObservation.breachedSignalMissing",
                    "Risky rollout observations should include at least one breached structured rollout signal.",
                    "/rolloutSignals"));
        }
        if (request.observedBy().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.observedByMissing",
                    "Runtime rollout observation requires observedBy.",
                    "/observedBy"));
        }
        if (request.reason().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.reasonMissing",
                    "Runtime rollout observation requires reason.",
                    "/reason"));
        }
        if (request.evidence().isEmpty() || request.evidence().stream().allMatch(evidence -> evidence.ref().isBlank())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.evidenceMissing",
                    "Runtime rollout observation requires non-empty rollout evidence.",
                    "/evidence"));
        }
    }

    private static boolean riskyObservation(Request request) {
        return request.rollbackTriggered()
                || VisualRuntimeRolloutObservation.STATE_DEGRADED.equals(request.observationState())
                || VisualRuntimeRolloutObservation.STATE_FAILED.equals(request.observationState())
                || VisualRuntimeRolloutObservation.STATE_ROLLED_BACK.equals(request.observationState());
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
                    "visual.runtimeRolloutObservation.%sMissing".formatted(field),
                    "Runtime rollout observation requires %s.".formatted(field),
                    target));
            return;
        }
        if (!normalizedActual.equals(normalizedExpected)) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.%sMismatch".formatted(field),
                    "Runtime rollout observation %s '%s' does not match expected '%s'."
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
        if (actual == 0) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.%sMissing".formatted(field),
                    "Runtime rollout observation requires %s.".formatted(field),
                    target));
            return;
        }
        if (actual != expected) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeRolloutObservation.%sMismatch".formatted(field),
                    "Runtime rollout observation %s '%d' does not match expected '%d'."
                            .formatted(field, actual, expected),
                    target,
                    Map.of("actual", actual, "expected", expected)));
        }
    }

    private static String catalogState(String operatorFingerprint, OperatorDefinition currentOperator) {
        if (currentOperator == null) {
            return "missing";
        }
        return currentOperator.fingerprint().equals(operatorFingerprint) ? "current" : "drifted";
    }

    private static String validationMessage(String state, String activationId, int diagnosticCount) {
        String target = activationId == null || activationId.isBlank() ? "activation" : activationId;
        if ("ready-to-record".equals(state)) {
            return "Runtime rollout observation for %s is ready to persist.".formatted(target);
        }
        return "Runtime rollout observation for %s was rejected: %d diagnostic(s)."
                .formatted(target, diagnosticCount);
    }
}
