package com.leanowtech.bloge.gateway.capabilitystudio;

import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.NestedGraphProvider;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorComposabilityManifest;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorComposabilityManifestProvider;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorRuntimeBindingSnapshotProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact Tool binding over the canonical cancellation-dispute Feature graph.
 *
 * <p>The binding delegates nested execution to BLOGE and exposes the child graph to the existing
 * test-control inventory. Its composability manifest declares the four resource-control ports of
 * the canonical Feature graph. This is deliberately a dependency declaration, not a claim that
 * the Tool is self-contained; certification still requires a fixture for every declared resource
 * and the exact graph fingerprint as the conformance artifact.</p>
 */
final class CapabilityStudioFeatureToolOperator
        implements Operator<Map<String, Object>, Map<String, Object>>, NestedGraphProvider,
        OperatorRuntimeBindingSnapshotProvider, OperatorComposabilityManifestProvider {

    private static final String CONFORMANCE_SUITE_REF =
            "capability-studio:feature-cancellation-dispute-context";
    private static final List<String> CANONICAL_RESOURCE_REFS = List.of(
            "api-order-lookup",
            "api-cancellation-responsibility",
            "api-city-pricing-policy",
            "api-compensation-history");

    private final Graph featureGraph;
    private final OperatorRegistry registry;
    private final SubGraphOperator delegate;
    private final String graphFingerprint;

    CapabilityStudioFeatureToolOperator(
            Graph featureGraph,
            OperatorRegistry registry,
            String graphFingerprint) {
        this.featureGraph = Objects.requireNonNull(featureGraph, "featureGraph");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.graphFingerprint = Objects.requireNonNull(graphFingerprint, "graphFingerprint");
        this.delegate = new SubGraphOperator(featureGraph, registry);
    }

    @Override
    public Map<String, Object> execute(
            Map<String, Object> input,
            OperatorContext context) throws Exception {
        return delegate.execute(input, context);
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.READ_ONLY;
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.IDEMPOTENT;
    }

    @Override
    public List<NestedGraphBinding> nestedGraphBindings() {
        return List.of(new NestedGraphBinding(featureGraph.name(), featureGraph, registry));
    }

    @Override
    public Map<String, ?> runtimeBindingSnapshot() {
        return Map.of(
                "bindingType", "CAPABILITY_STUDIO_FEATURE_TOOL",
                "featureGraph", featureGraph.name(),
                "featureGraphFingerprint", graphFingerprint,
                "nestedPathSegment", featureGraph.name());
    }

    @Override
    public OperatorComposabilityManifest operatorComposabilityManifest() {
        return new OperatorComposabilityManifest(
                OperatorComposabilityManifest.SCHEMA_VERSION,
                OperatorComposabilityManifest.DependencyMode.DECLARED,
                CANONICAL_RESOURCE_REFS.stream()
                        .map(ref -> new OperatorComposabilityManifest.Dependency(
                                ref,
                                OperatorComposabilityManifest.DependencyKind.RESOURCE,
                                OperatorComposabilityManifest.ControlBoundary.RESOURCE_BINDING))
                        .toList(),
                List.of(),
                true,
                CONFORMANCE_SUITE_REF,
                graphFingerprint);
    }
}
