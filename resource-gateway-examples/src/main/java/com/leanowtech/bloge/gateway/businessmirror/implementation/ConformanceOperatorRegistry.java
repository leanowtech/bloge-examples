package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.OperatorMetadata;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Frozen registry projection that replaces only the compiler-proven target operator references. */
final class ConformanceOperatorRegistry implements OperatorRegistry {
    private final OperatorRegistry delegate;
    private final Set<String> targetOperatorRefs;
    private final TargetOperator targetOperator;

    ConformanceOperatorRegistry(
            OperatorRegistry delegate,
            Set<String> targetOperatorRefs,
            Map<CapabilityImplementationConformancePlanCompiler.RuntimeCoordinate, String>
                    runtimeCoordinates,
            CapabilityImplementationBinding binding,
            CapabilityImplementationRuntimePort runtime,
            String conformanceId,
            String caseCoordinate,
            Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.targetOperatorRefs = Set.copyOf(targetOperatorRefs);
        this.targetOperator = new TargetOperator(runtimeCoordinates, binding, runtime,
                conformanceId, caseCoordinate, clock);
    }

    @Override
    public void register(String name, Operator<?, ?> operator) {
        throw new UnsupportedOperationException("conformance registry is frozen");
    }

    @Override
    public void registerRaw(String name, Object operator) {
        throw new UnsupportedOperationException("conformance registry is frozen");
    }

    @Override
    public Object lookup(String name) {
        return targetOperatorRefs.contains(name) ? targetOperator : delegate.lookup(name);
    }

    @Override
    public OperatorMetadata metadata(String name) {
        return delegate.metadata(name);
    }

    @Override
    public boolean contains(String name) {
        return targetOperatorRefs.contains(name) || delegate.contains(name);
    }

    @Override
    public List<String> discover(String pattern) {
        return delegate.discover(pattern);
    }

    @Override
    public void addRegistrationListener(RegistrationListener listener) {
        delegate.addRegistrationListener(listener);
    }

    int totalCalls() {
        return targetOperator.calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    List<String> invokedSiteIds() {
        return targetOperator.calls.entrySet().stream()
                .filter(value -> value.getValue().get() > 0)
                .map(Map.Entry::getKey).sorted().toList();
    }

    Object targetOperator() {
        return targetOperator;
    }

    private static final class TargetOperator implements Operator<Object, Object> {
        private final Map<CapabilityImplementationConformancePlanCompiler.RuntimeCoordinate, String>
                coordinates;
        private final CapabilityImplementationBinding binding;
        private final CapabilityImplementationRuntimePort runtime;
        private final String conformanceId;
        private final String caseCoordinate;
        private final Clock clock;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        private TargetOperator(
                Map<CapabilityImplementationConformancePlanCompiler.RuntimeCoordinate, String>
                        coordinates,
                CapabilityImplementationBinding binding,
                CapabilityImplementationRuntimePort runtime,
                String conformanceId,
                String caseCoordinate,
                Clock clock) {
            this.coordinates = Map.copyOf(coordinates);
            this.binding = Objects.requireNonNull(binding, "binding");
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.conformanceId = Objects.requireNonNull(conformanceId, "conformanceId");
            this.caseCoordinate = Objects.requireNonNull(caseCoordinate, "caseCoordinate");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public Object execute(Object input, OperatorContext context) throws Exception {
            CapabilityImplementationConformancePlanCompiler.RuntimeCoordinate coordinate =
                    new CapabilityImplementationConformancePlanCompiler.RuntimeCoordinate(
                            context.graphName(), context.nodeId());
            String siteId = coordinates.get(coordinate);
            if (siteId == null) {
                throw new IllegalStateException("CONFORMANCE_RUNTIME_COORDINATE_UNRESOLVED");
            }
            Instant now = clock.instant();
            if (!now.isBefore(binding.expiresAt())) {
                throw new IllegalStateException("CONFORMANCE_IMPLEMENTATION_BINDING_EXPIRED");
            }
            CapabilityImplementationRuntimePort.Descriptor descriptor = runtime.describe(
                            binding.scope(), binding.runtimePortRef())
                    .orElseThrow(() -> new IllegalStateException(
                            "CONFORMANCE_RUNTIME_DESCRIPTOR_UNAVAILABLE"));
            CapabilityImplementationBindingService.requireExactDescriptor(binding, descriptor);
            int occurrence = calls.computeIfAbsent(siteId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            String invocationId = "impl-" + ProtocolFingerprint.ofText(
                    conformanceId + "\u0000" + caseCoordinate + "\u0000" + siteId
                            + "\u0000" + occurrence + "\u0000" + context.retryAttempt())
                    .substring("sha256:".length(), 39);
            Instant logicalTime = context.timeSource().now();
            Instant deadline = context.deadlineAt().orElse(binding.expiresAt());
            if (binding.expiresAt().isBefore(deadline)) {
                deadline = binding.expiresAt();
            }
            return runtime.invoke(binding, new CapabilityImplementationRuntimePort.Invocation(
                    invocationId, siteId, input, logicalTime, deadline));
        }

        @Override
        public Idempotency idempotency() {
            return Idempotency.IDEMPOTENT;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }
}
