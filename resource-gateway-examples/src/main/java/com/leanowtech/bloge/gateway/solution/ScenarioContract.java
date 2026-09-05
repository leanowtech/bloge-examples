package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure decision contract that maps already-collected feature values to one bounded outlet.
 *
 * <p>Every scenario uses unique-hit semantics and has an explicit fallback. Recursive composition
 * is represented only through {@link OutletKind#SUB_SCENARIO}; validation and evaluation therefore
 * do not require interaction, waiting, or external reads inside the graph.</p>
 *
 * @param scenarioRef stable scenario reference
 * @param inputs feature-value names accepted by the decision
 * @param hitPolicy decision-table hit policy; currently only {@code UNIQUE}
 * @param rules ordered explicit rules
 * @param otherwise mandatory fallback outlet
 * @param businessDefinition structured, implementation-independent decision identity
 * @param display independently revised business discovery and presentation material
 */
public record ScenarioContract(
        String scenarioRef,
        List<String> inputs,
        HitPolicy hitPolicy,
        List<Rule> rules,
        Outlet otherwise,
        BusinessScenarioSemanticContract businessDefinition,
        @com.fasterxml.jackson.annotation.JsonIgnore BusinessCapabilityDisplay display
) {
    /** Supported deterministic decision-table hit policy. */
    public enum HitPolicy { UNIQUE }

    /** Kinds of result that a scenario may choose. */
    public enum OutletKind { SUB_SCENARIO, INSTRUCTION, TERMINAL }

    /** One explicit business rule with an opaque, declarative predicate object. */
    public record Rule(String ruleId, JsonNode when, Outlet outlet) {
        /** Freezes predicate material and requires an observable outlet. */
        public Rule {
            ruleId = normalized(ruleId);
            when = when == null ? null : when.deepCopy();
            if (ruleId.isBlank() || when == null || !when.isObject() || outlet == null) {
                throw new IllegalArgumentException("Scenario rule is incomplete");
            }
        }

        @Override
        public JsonNode when() {
            return when.deepCopy();
        }
    }

    /** One instruction, nested scenario, or terminal outlet and its input binding. */
    public record Outlet(
            OutletKind kind,
            String ref,
            Map<String, String> bind,
            String terminalKind
    ) {
        /** Normalizes references and enforces the selected outlet shape. */
        public Outlet {
            ref = normalized(ref);
            terminalKind = normalized(terminalKind);
            bind = bind == null ? Map.of() : Map.copyOf(bind);
            if (kind == null
                    || (kind == OutletKind.TERMINAL && terminalKind.isBlank())
                    || (kind != OutletKind.TERMINAL && ref.isBlank())) {
                throw new IllegalArgumentException("Scenario outlet is incomplete");
            }
        }
    }

    /** Freezes rule order and rejects duplicate rule IDs or an invalid fallback. */
    public ScenarioContract {
        scenarioRef = normalized(scenarioRef);
        inputs = inputs == null ? List.of() : inputs.stream().map(ScenarioContract::normalized).toList();
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (scenarioRef.isBlank() || inputs.isEmpty() || inputs.stream().anyMatch(String::isBlank)
                || hitPolicy != HitPolicy.UNIQUE || rules.isEmpty() || otherwise == null) {
            throw new IllegalArgumentException("Scenario contract is incomplete");
        }
        Set<String> ids = new LinkedHashSet<>();
        if (rules.stream().anyMatch(rule -> rule == null || !ids.add(rule.ruleId()))) {
            throw new IllegalArgumentException("Scenario rule IDs must be unique");
        }
        List<String> declaredInputs = inputs;
        if (rules.stream().flatMap(rule -> iterable(rule.when().fieldNames()).stream())
                .anyMatch(field -> !declaredInputs.contains(field))) {
            throw new IllegalArgumentException("Scenario predicate references an undeclared input");
        }
        businessDefinition = businessDefinition == null
                ? BusinessScenarioSemanticContract.legacy(scenarioRef, inputs) : businessDefinition;
        display = display == null
                ? BusinessCapabilityDisplay.legacy(businessDefinition.intent(), businessDefinition.intent())
                : display;
    }

    /** Preserves v1.4.6 callers while deriving a compatibility display. */
    public ScenarioContract(String scenarioRef, List<String> inputs, HitPolicy hitPolicy,
                            List<Rule> rules, Outlet otherwise,
                            BusinessScenarioSemanticContract businessDefinition) {
        this(scenarioRef, inputs, hitPolicy, rules, otherwise, businessDefinition, null);
    }

    /** Preserves contracts authored before structured Scenario business semantics. */
    public ScenarioContract(String scenarioRef, List<String> inputs, HitPolicy hitPolicy,
                            List<Rule> rules, Outlet otherwise) {
        this(scenarioRef, inputs, hitPolicy, rules, otherwise, null);
    }

    /** @return nested scenario references in first-use order */
    public List<String> referencedScenarios() {
        return outlets().stream().filter(outlet -> outlet.kind() == OutletKind.SUB_SCENARIO)
                .map(Outlet::ref).distinct().toList();
    }

    /** @return instruction references in first-use order */
    public List<String> referencedInstructions() {
        return outlets().stream().filter(outlet -> outlet.kind() == OutletKind.INSTRUCTION)
                .map(Outlet::ref).distinct().toList();
    }

    /** @return canonical logical identity material for GOLDEN drift detection */
    public Map<String, Object> contractIdentity() {
        return Map.of(
                "scenarioRef", scenarioRef,
                "inputs", inputs,
                "hitPolicy", hitPolicy.name(),
                "rules", rules,
                "otherwise", otherwise,
                "businessDefinition", businessDefinition
        );
    }

    /** @return executable Scenario identity excluding independently revised display material */
    public Map<String, Object> implementationIdentity() {
        return contractIdentity();
    }

    /** Returns the same Scenario with independently revised discovery material. */
    public ScenarioContract withDisplay(BusinessCapabilityDisplay revisedDisplay) {
        return new ScenarioContract(scenarioRef, inputs, hitPolicy, rules, otherwise,
                businessDefinition, revisedDisplay);
    }

    private List<Outlet> outlets() {
        java.util.ArrayList<Outlet> values = new java.util.ArrayList<>();
        rules.forEach(rule -> values.add(rule.outlet()));
        values.add(otherwise);
        return List.copyOf(values);
    }

    private static List<String> iterable(java.util.Iterator<String> iterator) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        iterator.forEachRemaining(values::add);
        return values;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /** Parses a case-insensitive enum value without accepting unknown spellings. */
    public static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, normalized(value).toUpperCase(Locale.ROOT));
    }
}
