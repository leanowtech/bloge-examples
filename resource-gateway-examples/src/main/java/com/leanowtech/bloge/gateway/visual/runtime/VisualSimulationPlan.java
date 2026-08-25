package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable visual-owned input for a generated DSL simulation. */
public record VisualSimulationPlan(
        String generatedDsl,
        Map<String, Object> businessContext,
        String selectedOutputNode,
        List<Standin> standins
) {
    public VisualSimulationPlan {
        generatedDsl = normalized(generatedDsl);
        businessContext = businessContext == null ? Map.of() : Map.copyOf(businessContext);
        selectedOutputNode = normalized(selectedOutputNode);
        standins = standins == null ? List.of() : List.copyOf(standins);
    }

    /** A visual node whose operator execution is replaced by a fixed schema-level output. */
    public record Standin(
            String originalNodeId,
            String rewrittenOperatorRef,
            Object output,
            Object expectedInput
    ) {
        public Standin {
            originalNodeId = normalized(originalNodeId);
            rewrittenOperatorRef = normalized(rewrittenOperatorRef);
        }

        /** Returns the optional input assertion without changing the wire shape. */
        public Optional<Object> expectedInputOptional() {
            return Optional.ofNullable(expectedInput);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
