package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-run graph and descriptor snapshot used to close the mutable resource-registry race.
 *
 * <p>Stage 2 conservatively fingerprints every registered descriptor because BLOGE expressions can
 * compute {@code resourceId} at runtime. This intentionally causes extra fixture invalidation rather
 * than certifying a run against an incomplete dependency set.</p>
 *
 * @param graph graph selected before execution
 * @param resourceRegistry immutable descriptor snapshot
 * @param fingerprint composite graph and descriptor fingerprint
 * @param dependencyFingerprints descriptor fingerprints keyed by resource id
 * @param certificationEligible whether the target has a recoverable immutable graph definition
 * @param certificationGaps bounded reasons why this target cannot issue certifiable evidence
 */
public record GraphExecutionTargetSnapshot(
        Graph graph,
        FrozenResourceRegistry resourceRegistry,
        String fingerprint,
        Map<String, String> dependencyFingerprints,
        boolean certificationEligible,
        List<String> certificationGaps
) {
    public static GraphExecutionTargetSnapshot capture(ObjectMapper mapper, Graph graph,
                                                       ResourceRegistry resourceRegistry) {
        FrozenResourceRegistry frozen = new FrozenResourceRegistry(
                resourceRegistry == null ? List.of() : resourceRegistry.all());
        Map<String, String> dependencies = new LinkedHashMap<>();
        frozen.all().stream().sorted(Comparator.comparing(ResourceDescriptor::resourceId))
                .forEach(descriptor -> dependencies.put(descriptor.resourceId(),
                        ProtocolFingerprint.of(mapper, descriptor)));
        String graphFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        String composite = ProtocolFingerprint.of(mapper, Map.of(
                "graphFingerprint", graphFingerprint,
                "resourceDescriptorFingerprints", dependencies));
        List<String> gaps = graph.definitionSource() == null
                || graph.definitionSource().payloadJson() == null
                || graph.definitionSource().payloadJson().isBlank()
                ? List.of("Graph has no recoverable immutable definition source.") : List.of();
        return new GraphExecutionTargetSnapshot(graph, frozen, composite, Map.copyOf(dependencies),
                gaps.isEmpty(), gaps);
    }
}
