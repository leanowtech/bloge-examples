package com.leanowtech.bloge.gateway.solution;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.List;
import java.util.Map;

/** Canonical visual contracts for the two pure-function solution dispatch operators. */
public final class SolutionOperatorDefinitions {
    /** Stable Scenario evaluator operator reference. */
    public static final String SCENARIO_CALL = "bloge:scenarioCall";
    /** Stable Instruction dispatcher operator reference. */
    public static final String INSTRUCTION_CALL = "bloge:instructionCall";

    private SolutionOperatorDefinitions() { }

    /** @return immutable built-in operator definitions in execution order */
    public static List<OperatorDefinition> all() {
        return List.of(scenarioCall(), instructionCall());
    }

    /** @return pure Scenario tree evaluator contract */
    public static OperatorDefinition scenarioCall() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", SCENARIO_CALL, "1.0.0",
                new OperatorDefinition.Display(
                        "Scenario Call", "Evaluate a bounded pure Scenario tree.",
                        List.of("solution", "decision")),
                OperatorDefinition.Source.builtIn("resource-gateway-solution"),
                new OperatorDefinition.Ports(
                        List.of(port("scenarioRef", scalar("string"), true),
                                port("values", SchemaEnvelope.opaque(), true)),
                        List.of(port("outletKind", scalar("string"), true),
                                port("ref", scalar("string"), false),
                                port("terminalKind", scalar("string"), false),
                                port("bind", SchemaEnvelope.opaque(), true),
                                port("rulePath", array("string"), true))),
                SchemaEnvelope.opaque(), OperatorDefinition.Capabilities.pure(),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", "scenarioCall", Map.of()), List.of());
    }

    /** @return effect-aware Instruction dispatch contract */
    public static OperatorDefinition instructionCall() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", INSTRUCTION_CALL, "1.0.0",
                new OperatorDefinition.Display(
                        "Instruction Call", "Dispatch an Instruction and require result plus reasoning.",
                        List.of("solution", "action")),
                OperatorDefinition.Source.builtIn("resource-gateway-solution"),
                new OperatorDefinition.Ports(
                        List.of(port("instructionRef", scalar("string"), true),
                                port("values", SchemaEnvelope.opaque(), true)),
                        List.of(port("result", SchemaEnvelope.opaque(), true),
                                port("reasoning", scalar("string"), true))),
                SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities("MIXED", "UNKNOWN", false, false, false),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", "instructionCall", Map.of()), List.of());
    }

    private static OperatorDefinition.Port port(
            String name, SchemaEnvelope schema, boolean required) {
        return new OperatorDefinition.Port(name, schema, required, name);
    }

    private static SchemaEnvelope scalar(String type) {
        return new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of("type", type));
    }

    private static SchemaEnvelope array(String itemType) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                Map.of("type", "array", "items", Map.of("type", itemType)));
    }
}
