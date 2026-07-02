package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared runtime binding requirement planner for operator definitions.
 *
 * <p>Import-time readiness and draft/publication readiness must classify missing
 * runtime bindings the same way. This planner owns the operator-level binding
 * kind, target, title, and summary so those control-plane surfaces do not drift.</p>
 */
public final class VisualRuntimeBindingRequirementPlanner {

    private VisualRuntimeBindingRequirementPlanner() {
    }

    /**
     * Builds operator-level runtime binding requirements.
     *
     * @param operator operator definition
     * @param state normalized runtime readiness state
     * @param level readiness severity
     * @param fallbackSummary summary used for generic runtime adapter gaps
     * @return binding requirements
     */
    public static List<OperatorRequirement> from(OperatorDefinition operator,
                                                 String state,
                                                 String level,
                                                 String fallbackSummary) {
        if (operator == null) {
            return List.of();
        }
        String normalizedState = normalizeFacetValue(state);
        String sourceKind = normalizeFacetValue(operator.source().kind());
        String loweringMode = normalizeFacetValue(operator.lowering().mode());
        boolean streaming = operator.capabilities().streaming()
                || "java-streaming-operator".equals(sourceKind);
        boolean durable = operator.capabilities().durable()
                || "java-suspendable-operator".equals(sourceKind);
        List<OperatorRequirement> requirements = new ArrayList<>();
        if ("design-only".equals(normalizedState)) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "executable-lowering",
                    firstNonBlank(operator.lowering().operatorRef(), operator.operatorRef()),
                    "Executable lowering required",
                    "This operator is schema-authorable only; no executable lowering is bound."
            ));
            return List.copyOf(requirements);
        }
        if (!"runtime-blocked".equals(normalizedState)) {
            return List.of();
        }
        if ("remote-worker".equals(sourceKind) || "remote-worker".equals(loweringMode)) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "remote-worker-runtime",
                    parameter(operator.lowering(), "workerTopic"),
                    "Remote worker runtime required",
                    "A remote worker dispatcher is required before this operator can execute."
            ));
        }
        if ("ai-tool".equals(sourceKind) || "ai-tool".equals(loweringMode)) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "ai-tool-runtime",
                    parameter(operator.lowering(), "toolRef"),
                    "AI tool runtime required",
                    "An AI tool invocation runtime is required before this operator can execute."
            ));
        }
        if ("event-source".equals(sourceKind) || "event-source".equals(loweringMode)) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "event-source-runtime",
                    parameter(operator.lowering(), "eventType"),
                    "Event source runtime required",
                    "An event subscription runtime is required before this operator can execute."
            ));
        }
        if ("message-handler".equals(sourceKind) || "message-handler".equals(loweringMode)) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "message-runtime",
                    parameter(operator.lowering(), "channel"),
                    "Message runtime required",
                    "A message consumer runtime is required before this operator can execute."
            ));
        }
        if ("webhook".equals(sourceKind) || "webhook".equals(loweringMode)) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "webhook-ingress-runtime",
                    webhookTarget(operator.source(), operator.lowering()),
                    "Webhook ingress runtime required",
                    "A webhook ingress runtime is required before this operator can execute."
            ));
        }
        if (streaming) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "streaming-runtime",
                    "",
                    "Streaming runtime required",
                    "This operator requires streaming execution, which the request-response runtime cannot provide."
            ));
        }
        if (durable) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "durable-runtime",
                    "",
                    "Durable runtime required",
                    "This operator requires durable/suspendable execution, which the request-response runtime cannot provide."
            ));
        }
        if (requirements.isEmpty()) {
            requirements.add(requirement(
                    operator,
                    normalizedState,
                    level,
                    sourceKind,
                    loweringMode,
                    "runtime-adapter",
                    firstNonBlank(operator.lowering().operatorRef(), operator.operatorRef()),
                    "Runtime adapter required",
                    fallbackSummary
            ));
        }
        return List.copyOf(requirements);
    }

    private static OperatorRequirement requirement(OperatorDefinition operator,
                                                   String state,
                                                   String level,
                                                   String sourceKind,
                                                   String loweringMode,
                                                   String bindingKind,
                                                   String bindingTarget,
                                                   String title,
                                                   String summary) {
        return new OperatorRequirement(
                operator.operatorRef(),
                operator.display().name().isBlank() ? operator.operatorRef() : operator.display().name(),
                state,
                level,
                sourceKind,
                loweringMode,
                bindingKind,
                bindingTarget,
                title,
                summary
        );
    }

    private static String parameter(OperatorDefinition.Lowering lowering, String key) {
        if (lowering == null || lowering.parameters() == null) {
            return "";
        }
        Object value = lowering.parameters().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String webhookTarget(OperatorDefinition.Source source, OperatorDefinition.Lowering lowering) {
        String method = firstNonBlank(parameter(lowering, "method"), source == null ? "" : source.method());
        String path = firstNonBlank(parameter(lowering, "path"), source == null ? "" : source.urlTemplate());
        return firstNonBlank("%s %s".formatted(method, path).trim(), path, method);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizeFacetValue(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    /**
     * Operator-level runtime binding gap.
     *
     * @param operatorRef operator reference
     * @param label display label
     * @param state readiness state
     * @param level readiness severity
     * @param sourceKind source kind
     * @param loweringMode lowering mode
     * @param bindingKind missing runtime binding kind
     * @param bindingTarget runtime binding target
     * @param title title
     * @param summary summary
     */
    public record OperatorRequirement(
            String operatorRef,
            String label,
            String state,
            String level,
            String sourceKind,
            String loweringMode,
            String bindingKind,
            String bindingTarget,
            String title,
            String summary
    ) {
        public OperatorRequirement {
            operatorRef = operatorRef == null ? "" : operatorRef;
            label = label == null ? "" : label;
            state = normalizeFacetValue(state);
            level = level == null || level.isBlank() ? "warning" : level.trim().toLowerCase(Locale.ROOT);
            sourceKind = normalizeFacetValue(sourceKind);
            loweringMode = normalizeFacetValue(loweringMode);
            bindingKind = normalizeFacetValue(bindingKind);
            bindingTarget = bindingTarget == null ? "" : bindingTarget;
            title = title == null ? "" : title;
            summary = summary == null ? "" : summary;
        }
    }
}
