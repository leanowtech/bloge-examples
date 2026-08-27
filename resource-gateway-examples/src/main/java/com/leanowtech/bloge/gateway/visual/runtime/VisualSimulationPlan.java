package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture.ResourceFidelity;
import com.leanowtech.bloge.gateway.visual.simulation.ResourceResponseFixture;

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
            Object expectedInput,
            ResourceFidelity resourceFidelity,
            ResourceResponseFixture resourceResponse
    ) {
        public Standin {
            originalNodeId = normalized(originalNodeId);
            rewrittenOperatorRef = normalized(rewrittenOperatorRef);
            resourceFidelity = resourceFidelity == null ? ResourceFidelity.OUTPUT_LEVEL : resourceFidelity;
        }

        /** Backward-compatible output-level stand-in constructor. */
        public Standin(String originalNodeId, String rewrittenOperatorRef,
                       Object output, Object expectedInput) {
            this(originalNodeId, rewrittenOperatorRef, output, expectedInput,
                    ResourceFidelity.OUTPUT_LEVEL);
        }

        /** Backward-compatible fidelity constructor. */
        public Standin(String originalNodeId, String rewrittenOperatorRef, Object output,
                       Object expectedInput, ResourceFidelity resourceFidelity) {
            this(originalNodeId, rewrittenOperatorRef, output, expectedInput, resourceFidelity, null);
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
