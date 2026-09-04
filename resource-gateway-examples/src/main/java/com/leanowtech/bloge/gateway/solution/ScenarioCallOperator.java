package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** BLOGE operator that evaluates one bounded Scenario tree using already-collected values. */
@Component
@BlogeOperator(
        value = "scenarioCall",
        description = "Evaluate one bounded pure Scenario tree",
        owner = "bloge-platform",
        tags = {"solution", "decision", "pure"})
public final class ScenarioCallOperator implements Operator<Map<String, Object>, Map<String, Object>> {
    /** Runtime registry name used by Solution lowering. */
    public static final String NAME = "scenarioCall";

    private final SolutionEntityRegistry registry;
    private final ObjectMapper mapper;

    /** Creates the Spring runtime operator. */
    @Autowired
    public ScenarioCallOperator(SolutionEntityRegistry registry, ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Creates a focused operator for contract tests. */
    public ScenarioCallOperator(SolutionEntityRegistry registry) {
        this(registry, new ObjectMapper().findAndRegisterModules());
    }

    /** Evaluates supplied values without collecting features or touching external systems. */
    @Override
    public Map<String, Object> execute(Map<String, Object> input, OperatorContext context) {
        SolutionExecutionAuthority authority = SolutionExecutionAuthority.require(context.graphContext());
        String scenarioRef = text(input, "scenarioRef");
        Object values = input == null ? null : input.get("values");
        JsonNode valuesNode = mapper.valueToTree(values == null ? Map.of() : values);
        ScenarioTreeEvaluator.Outcome outcome = new ScenarioTreeEvaluator(registry, 8)
                .evaluate(authority.scopeKey(), scenarioRef, valuesNode);
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        output.put("outletKind", outcome.outletKind());
        output.put("ref", outcome.ref());
        output.put("terminalKind", outcome.terminalKind());
        output.put("bind", outcome.bind());
        output.put("rulePath", outcome.rulePath());
        return Map.copyOf(output);
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.IDEMPOTENT;
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.READ_ONLY;
    }

    private static String text(Map<String, Object> input, String field) {
        String value = Objects.toString(input == null ? null : input.get(field), "").trim();
        if (value.isBlank()) throw new SolutionContractException(
                "SCENARIO_INPUT_INVALID", "Scenario reference is required.");
        return value;
    }
}
