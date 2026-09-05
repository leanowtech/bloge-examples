package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Canonical contract for one atomic fact that a calling Agent must collect before invoking a
 * solution.
 *
 * <p>The contract declares how the value is obtained; it is not a graph node. Deterministic and
 * model-backed values are evaluated by Resource Gateway, while interactive values remain owned by
 * the calling Agent. JSON schema fragments are copied on construction so persisted fingerprints
 * cannot drift through caller mutation.</p>
 *
 * @param featureRef stable feature reference inside one tenant/project/environment scope
 * @param output declared atomic output schema
 * @param evaluationKind runtime responsible for collecting the value
 * @param determinism trust classification applied by solution invocation
 * @param inputs declared evaluation input schema
 * @param evaluationRef deterministic or model evaluation binding; blank means design-only
 * @param componentRef interactive component reference for {@link EvaluationKind#USER_COMPONENT}
 * @param promptRef interactive prompt reference for {@link EvaluationKind#USER_CONVERSATION}
 * @param businessSemantics business-language meaning shown on engineering handoff tickets
 * @param businessDefinition structured, implementation-independent fact identity
 * @param display independently revised business discovery and presentation material
 */
public record FeatureContract(
        String featureRef,
        JsonNode output,
        EvaluationKind evaluationKind,
        Determinism determinism,
        JsonNode inputs,
        String evaluationRef,
        String componentRef,
        String promptRef,
        String businessSemantics,
        BusinessFactSemanticContract businessDefinition,
        @com.fasterxml.jackson.annotation.JsonIgnore BusinessCapabilityDisplay display
) {
    /**
     * Preserves the v1.4.4 constructor for existing callers that do not yet supply business text.
     */
    public FeatureContract(String featureRef,
                           JsonNode output,
                           EvaluationKind evaluationKind,
                           Determinism determinism,
                           JsonNode inputs,
                           String evaluationRef,
                           String componentRef,
                           String promptRef) {
        this(featureRef, output, evaluationKind, determinism, inputs, evaluationRef,
                componentRef, promptRef, featureRef, null, null);
    }

    /** Preserves v1.4.5 callers while projecting missing business dimensions as UNKNOWN. */
    public FeatureContract(String featureRef,
                           JsonNode output,
                           EvaluationKind evaluationKind,
                           Determinism determinism,
                           JsonNode inputs,
                           String evaluationRef,
                           String componentRef,
                           String promptRef,
                           String businessSemantics) {
        this(featureRef, output, evaluationKind, determinism, inputs, evaluationRef,
                componentRef, promptRef, businessSemantics, null, null);
    }

    /** Preserves v1.4.6 callers while deriving a compatibility display. */
    public FeatureContract(String featureRef,
                           JsonNode output,
                           EvaluationKind evaluationKind,
                           Determinism determinism,
                           JsonNode inputs,
                           String evaluationRef,
                           String componentRef,
                           String promptRef,
                           String businessSemantics,
                           BusinessFactSemanticContract businessDefinition) {
        this(featureRef, output, evaluationKind, determinism, inputs, evaluationRef,
                componentRef, promptRef, businessSemantics, businessDefinition, null);
    }

    /** Supported feature evaluation runtimes. */
    public enum EvaluationKind {
        API,
        DAG,
        MODEL,
        INSTRUCTION_RESULT,
        USER_CONVERSATION,
        USER_COMPONENT;

        /** @return whether Resource Gateway must refuse direct evaluation */
        public boolean interactive() {
            return this == USER_CONVERSATION || this == USER_COMPONENT;
        }
    }

    /** Trust class that determines whether a signed evaluation token is required. */
    public enum Determinism {
        DETERMINISTIC,
        NON_DETERMINISTIC,
        INTERACTIVE
    }

    /** Normalizes references, freezes schema fragments, and enforces kind/trust consistency. */
    public FeatureContract {
        featureRef = normalized(featureRef);
        evaluationRef = normalized(evaluationRef);
        componentRef = normalized(componentRef);
        promptRef = normalized(promptRef);
        businessSemantics = normalized(businessSemantics);
        output = copy(output);
        inputs = inputs == null || inputs.isMissingNode()
                ? JsonNodeFactory.instance.objectNode()
                : copy(inputs);
        if (featureRef.isBlank() || output == null || !output.isObject()
                || output.path("type").isMissingNode() || output.path("type").isNull()
                || evaluationKind == null || determinism == null
                || !inputs.isObject() || businessSemantics.isBlank()) {
            throw new IllegalArgumentException("Feature contract is incomplete");
        }
        businessDefinition = businessDefinition == null
                ? BusinessFactSemanticContract.legacy(featureRef, businessSemantics, inputs, output)
                : businessDefinition;
        display = display == null
                ? BusinessCapabilityDisplay.legacy(businessSemantics, businessDefinition.intent())
                : display;
        if (evaluationKind.interactive() != (determinism == Determinism.INTERACTIVE)) {
            throw new IllegalArgumentException("Interactive feature kind and determinism must agree");
        }
        if (evaluationKind == EvaluationKind.MODEL && determinism != Determinism.NON_DETERMINISTIC) {
            throw new IllegalArgumentException("MODEL features must be NON_DETERMINISTIC");
        }
        if (!evaluationKind.interactive() && evaluationKind != EvaluationKind.MODEL
                && determinism != Determinism.DETERMINISTIC) {
            throw new IllegalArgumentException("API, DAG and instruction features must be DETERMINISTIC");
        }
        if (evaluationKind == EvaluationKind.USER_COMPONENT && componentRef.isBlank()) {
            throw new IllegalArgumentException("USER_COMPONENT feature requires componentRef");
        }
        if (evaluationKind == EvaluationKind.USER_CONVERSATION && promptRef.isBlank()) {
            throw new IllegalArgumentException("USER_CONVERSATION feature requires promptRef");
        }
    }

    @Override
    public JsonNode output() {
        return output.deepCopy();
    }

    @Override
    public JsonNode inputs() {
        return inputs.deepCopy();
    }

    /** @return whether a platform-evaluated feature still lacks its implementation binding */
    public boolean speccing() {
        return !evaluationKind.interactive() && evaluationRef.isBlank();
    }

    /**
     * Returns the implementation-independent material used as the feature contract identity.
     *
     * <p>Evaluation, component and prompt references are deliberately excluded. Rebinding an
     * unchanged business contract must not create a new GOLDEN identity line.</p>
     */
    public Map<String, Object> contractIdentity() {
        return Map.of(
                "featureRef", featureRef,
                "output", output,
                "evaluationKind", evaluationKind.name(),
                "determinism", determinism.name(),
                "inputs", inputs,
                "businessSemantics", businessSemantics,
                "businessDefinition", businessDefinition
        );
    }

    /** @return executable Feature identity excluding independently revised display material */
    public Map<String, Object> implementationIdentity() {
        return Map.ofEntries(
                Map.entry("featureRef", featureRef), Map.entry("output", output),
                Map.entry("evaluationKind", evaluationKind),
                Map.entry("determinism", determinism), Map.entry("inputs", inputs),
                Map.entry("evaluationRef", evaluationRef), Map.entry("componentRef", componentRef),
                Map.entry("promptRef", promptRef), Map.entry("businessSemantics", businessSemantics),
                Map.entry("businessDefinition", businessDefinition));
    }

    /** Returns the same Feature with independently revised discovery material. */
    public FeatureContract withDisplay(BusinessCapabilityDisplay revisedDisplay) {
        return new FeatureContract(featureRef, output, evaluationKind, determinism, inputs,
                evaluationRef, componentRef, promptRef, businessSemantics, businessDefinition,
                revisedDisplay);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /** Parses a case-insensitive enum value without accepting unknown spellings. */
    public static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, normalized(value).toUpperCase(Locale.ROOT));
    }
}
