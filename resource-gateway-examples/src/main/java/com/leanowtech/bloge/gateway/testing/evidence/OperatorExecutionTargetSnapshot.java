package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.SideEffectProtocol;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.OperatorMetadata;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorRuntimeBindingSnapshotProvider;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable operator-binding snapshot used by the public operator testing adapter.
 *
 * <p>The snapshot fingerprints executable bytecode, declared schemas and behavioral contracts.
 * {@link HttpResourceOperator} additionally freezes every resource descriptor because its
 * {@code resourceId} may be selected from input at runtime. Non-synchronous bindings remain
 * discoverable, but cannot be submitted to the v1 micro-graph runner.</p>
 *
 * @param operatorRef stable registry reference
 * @param binding exact binding selected for this request
 * @param metadata frozen registry metadata
 * @param resourceRegistry immutable resource-descriptor snapshot
 * @param fingerprint composite target fingerprint
 * @param implementationFingerprint executable implementation-closure fingerprint
 * @param runtimeBindingStateFingerprint behavior-relevant configured-state fingerprint
 * @param schemaFingerprint input/output schema fingerprint
 * @param resourceDependencyFingerprints descriptor fingerprints keyed by resource id
 * @param dependencyPolicy descriptor dependency policy
 * @param executionModel synchronous, streaming, suspendable, or unsupported model
 * @param sideEffectType declared side-effect type
 * @param idempotency declared idempotency
 * @param sideEffectProtocol declared side-effect protocol
 * @param testabilityClass affirmative baseline testability classification
 * @param executionSupported whether v1 can execute this binding
 * @param certificationEligible whether a conforming governed fixture may issue certifiable evidence
 * @param certificationRequirements requirements that the fixture must satisfy at execution time
 * @param certificationGaps binding-level reasons certification is impossible
 */
public record OperatorExecutionTargetSnapshot(
        String operatorRef,
        Object binding,
        OperatorMetadata metadata,
        FrozenResourceRegistry resourceRegistry,
        String fingerprint,
        String implementationFingerprint,
        String runtimeBindingStateFingerprint,
        String schemaFingerprint,
        Map<String, String> resourceDependencyFingerprints,
        String dependencyPolicy,
        String executionModel,
        String sideEffectType,
        String idempotency,
        Map<String, Object> sideEffectProtocol,
        String testabilityClass,
        boolean executionSupported,
        boolean certificationEligible,
        List<String> certificationRequirements,
        List<String> certificationGaps
) {
    public static final String EXECUTABLE_UNIT = "EXECUTABLE_UNIT";
    public static final String CONDITIONAL_TRANSPORT = "CONDITIONAL_TRANSPORT";
    public static final String OPAQUE_RUNTIME = "OPAQUE_RUNTIME";
    public static final String UNSUPPORTED_EXECUTION_MODEL = "UNSUPPORTED_EXECUTION_MODEL";

    /**
     * Captures one registry binding and every dependency the v1 adapter can formally freeze.
     *
     * @param mapper canonical protocol mapper
     * @param operatorRef registered operator reference
     * @param operatorRegistry runtime binding registry
     * @param resourceRegistry mutable descriptor registry to snapshot
     * @return immutable target snapshot
     * @throws IllegalArgumentException when the operator is absent
     */
    public static OperatorExecutionTargetSnapshot capture(ObjectMapper mapper, String operatorRef,
                                                          OperatorRegistry operatorRegistry,
                                                          ResourceRegistry resourceRegistry) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        String ref = normalized(operatorRef);
        if (ref.isBlank() || !operatorRegistry.contains(ref)) {
            throw new IllegalArgumentException("No operator registered with name: " + ref);
        }
        Object binding = operatorRegistry.lookup(ref);
        OperatorMetadata metadata = operatorRegistry.metadata(ref);
        FrozenResourceRegistry frozenResources = new FrozenResourceRegistry(
                resourceRegistry == null ? List.of() : resourceRegistry.all());

        String model = executionModel(binding);
        BehaviorContract behavior = behaviorContract(binding);
        SideEffectType effect = behavior.sideEffectType();
        Idempotency idempotency = behavior.idempotency();
        SideEffectProtocol protocol = behavior.sideEffectProtocol();
        String schema = ProtocolFingerprint.of(mapper, Map.of(
                "input", metadata.inputSchema().toMap(),
                "output", metadata.outputSchema().toMap()));

        Map<String, String> dependencies = resourceDependencies(mapper, binding, frozenResources);
        String implementation = implementationFingerprint(mapper, binding);
        BindingState bindingState = bindingState(mapper, binding, dependencies);
        String dependencyPolicy = binding instanceof HttpResourceOperator
                ? "CONSERVATIVE_ALL_REGISTERED" : "NONE_DECLARED";
        List<String> requirements = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (!behavior.gap().isBlank()) {
            gaps.add(behavior.gap());
        }
        boolean synchronous = binding instanceof Operator<?, ?>;
        String classification;
        if (!synchronous) {
            classification = UNSUPPORTED_EXECUTION_MODEL;
            gaps.add("testing-control-plane v1 cannot execute " + model.toLowerCase()
                    + " operator bindings.");
        } else if (binding instanceof HttpResourceOperator) {
            classification = CONDITIONAL_TRANSPORT;
            requirements.add("Every selected resource invocation requires a strict TRANSPORT raw-response fixture.");
        } else if (effect == SideEffectType.READ_ONLY) {
            classification = EXECUTABLE_UNIT;
        } else {
            classification = OPAQUE_RUNTIME;
            gaps.add("Binding declares " + effect
                    + " but exposes no test composability port that proves side effects are isolated.");
        }
        if (implementation.isBlank()) {
            gaps.add("Executable class bytes are unavailable, so the runtime implementation cannot be frozen.");
        }
        if (!bindingState.gap().isBlank()) {
            gaps.add(bindingState.gap());
        }
        boolean eligible = synchronous && implementation.length() > 0
                && !bindingState.fingerprint().isBlank()
                && behavior.gap().isBlank()
                && (binding instanceof HttpResourceOperator || effect == SideEffectType.READ_ONLY);
        String targetFingerprint = ProtocolFingerprint.of(mapper, Map.ofEntries(
                Map.entry("operatorRef", ref),
                Map.entry("implementationFingerprint", implementation),
                Map.entry("runtimeBindingStateFingerprint", bindingState.fingerprint()),
                Map.entry("schemaFingerprint", schema),
                Map.entry("protocolMapperProfile", protocolMapperProfile(mapper)),
                Map.entry("inputType", typeName(metadata.inputType())),
                Map.entry("outputType", typeName(metadata.outputType())),
                Map.entry("executionModel", model),
                Map.entry("sideEffectType", effect.name()),
                Map.entry("idempotency", idempotency.name()),
                Map.entry("sideEffectProtocol", protocolMap(protocol)),
                Map.entry("resourceDependencyFingerprints", dependencies),
                Map.entry("dependencyPolicy", dependencyPolicy)));
        return new OperatorExecutionTargetSnapshot(ref, binding, metadata, frozenResources,
                targetFingerprint, implementation, bindingState.fingerprint(), schema,
                Map.copyOf(dependencies), dependencyPolicy,
                model, effect.name(), idempotency.name(), protocolMap(protocol), classification,
                synchronous, eligible, List.copyOf(requirements), List.copyOf(gaps));
    }

    /** @return the exact synchronous binding selected by the snapshot */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> synchronousOperator() {
        if (!(binding instanceof Operator<?, ?> operator)) {
            throw new IllegalStateException("Operator binding is not synchronous: " + executionModel);
        }
        return (Operator<Object, Object>) operator;
    }

    /** @return frozen input schema in BLOGE's serializable schema representation */
    public Map<String, Object> inputSchema() {
        return metadata.inputSchema().toMap();
    }

    /** @return frozen output schema in BLOGE's serializable schema representation */
    public Map<String, Object> outputSchema() {
        return metadata.outputSchema().toMap();
    }

    private static Map<String, String> resourceDependencies(ObjectMapper mapper, Object binding,
                                                             FrozenResourceRegistry resources) {
        Map<String, String> dependencies = new LinkedHashMap<>();
        if (!(binding instanceof HttpResourceOperator)) {
            return dependencies;
        }
        resources.all().stream().sorted(Comparator.comparing(ResourceDescriptor::resourceId))
                .forEach(descriptor -> dependencies.put(descriptor.resourceId(),
                        ProtocolFingerprint.of(mapper, descriptor)));
        return dependencies;
    }

    private static String implementationFingerprint(ObjectMapper mapper, Object binding) {
        LinkedHashSet<Class<?>> closure = new LinkedHashSet<>();
        closure.add(binding.getClass());
        if (binding instanceof HttpResourceOperator) {
            closure.add(HttpResourceOperator.class);
            closure.add(HttpRequestOperator.class);
            closure.add(BlgeExpressionEvaluator.class);
            closure.add(UrlTemplateRenderer.class);
            closure.add(PayloadExtractor.class);
            closure.add(ResponseValidator.class);
        }
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (Class<?> type : closure) {
            String fingerprint = classFingerprint(type);
            if (fingerprint.isBlank()) {
                return "";
            }
            fingerprints.put(type.getName(), fingerprint);
        }
        return ProtocolFingerprint.of(mapper, fingerprints);
    }

    private static String classFingerprint(Class<?> type) {
        String binaryName = type.getName();
        int packageEnd = binaryName.lastIndexOf('.');
        String classResource = binaryName.substring(packageEnd + 1) + ".class";
        try (InputStream input = type.getResourceAsStream(classResource)) {
            return input == null ? "" : ProtocolFingerprint.ofBytes(input.readAllBytes());
        } catch (IOException ignored) {
            return "";
        }
    }

    private static boolean hasNoInstanceState(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (java.util.Arrays.stream(current.getDeclaredFields())
                    .anyMatch(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            && !field.isSynthetic())) {
                return false;
            }
        }
        return true;
    }

    private static BindingState bindingState(ObjectMapper mapper, Object binding,
                                             Map<String, String> resourceDependencies) {
        Object state;
        if (binding instanceof HttpResourceOperator) {
            state = Map.of("resourceDependencyFingerprints", resourceDependencies);
        } else if (hasNoInstanceState(binding.getClass())) {
            state = Map.of();
        } else if (binding instanceof OperatorRuntimeBindingSnapshotProvider provider) {
            try {
                state = provider.runtimeBindingSnapshot();
                if (!(state instanceof Map<?, ?>)) {
                    return new BindingState("",
                            "Runtime-binding snapshot provider returned no configuration map.");
                }
            } catch (RuntimeException failure) {
                return new BindingState("",
                        "Runtime-binding snapshot provider failed: " + failure.getClass().getSimpleName());
            }
        } else {
            return new BindingState("",
                    "Binding has instance state without a formal runtime-binding snapshot contract.");
        }
        try {
            return new BindingState(ProtocolFingerprint.ofBounded(mapper, state, 65_536), "");
        } catch (IllegalArgumentException failure) {
            String gap = failure.getMessage() != null && failure.getMessage().contains("exceeds")
                    ? "Runtime-binding snapshot exceeds 65536 bytes."
                    : "Runtime-binding snapshot is not canonical JSON.";
            return new BindingState("", gap);
        }
    }

    private record BindingState(String fingerprint, String gap) {
    }

    private static String executionModel(Object binding) {
        if (binding instanceof StreamingOperator<?, ?>) {
            return "STREAMING";
        }
        if (binding instanceof SuspendableOperator<?, ?>) {
            return "SUSPENDABLE";
        }
        return binding instanceof Operator<?, ?> ? "SYNCHRONOUS" : "UNSUPPORTED";
    }

    private static BehaviorContract behaviorContract(Object binding) {
        try {
            SideEffectType sideEffectType;
            Idempotency idempotency;
            SideEffectProtocol protocol;
            if (binding instanceof Operator<?, ?> operator) {
                sideEffectType = operator.sideEffectType();
                idempotency = operator.idempotency();
                protocol = operator.sideEffectProtocol();
            } else if (binding instanceof StreamingOperator<?, ?> operator) {
                sideEffectType = operator.sideEffectType();
                idempotency = operator.idempotency();
                protocol = operator.sideEffectProtocol();
            } else if (binding instanceof SuspendableOperator<?, ?> operator) {
                sideEffectType = operator.sideEffectType();
                idempotency = operator.idempotency();
                protocol = operator.sideEffectProtocol();
            } else {
                return BehaviorContract.fallback(
                        "Binding exposes no supported operator behavioral contract.");
            }
            if (sideEffectType == null || idempotency == null || protocol == null) {
                return BehaviorContract.fallback(
                        "Operator behavioral declarations are incomplete and cannot be certified.");
            }
            return new BehaviorContract(sideEffectType, idempotency, protocol, "");
        } catch (RuntimeException failure) {
            return BehaviorContract.fallback("Operator behavioral contract inspection failed: "
                    + failure.getClass().getSimpleName() + ".");
        }
    }

    private record BehaviorContract(SideEffectType sideEffectType, Idempotency idempotency,
                                    SideEffectProtocol sideEffectProtocol, String gap) {
        private static BehaviorContract fallback(String gap) {
            return new BehaviorContract(SideEffectType.MIXED, Idempotency.UNKNOWN,
                    SideEffectProtocol.unmanaged(), gap);
        }
    }

    private static Map<String, Object> protocolMap(SideEffectProtocol protocol) {
        SideEffectProtocol safe = protocol == null ? SideEffectProtocol.unmanaged() : protocol;
        return Map.of(
                "schemaVersion", safe.schemaVersion(),
                "mode", safe.mode().name(),
                "commitReceiptRequired", safe.commitReceiptRequired(),
                "reconciliationRequired", safe.reconciliationRequired(),
                "reconcilerRef", safe.reconcilerRef());
    }

    private static Map<String, Object> protocolMapperProfile(ObjectMapper mapper) {
        List<String> modules = mapper.getRegisteredModuleIds().stream()
                .map(String::valueOf).sorted().toList();
        Object naming = mapper.getPropertyNamingStrategy();
        return Map.of(
                "jacksonVersion", mapper.version().toFullString(),
                "mapperType", mapper.getClass().getName(),
                "registeredModules", modules,
                "serializationFeatures", mapper.getSerializationConfig().getSerializationFeatures(),
                "deserializationFeatures", mapper.getDeserializationConfig().getDeserializationFeatures(),
                "propertyNamingStrategy", naming == null ? "" : naming.getClass().getName());
    }

    private static String typeName(java.lang.reflect.Type type) {
        return type == null ? "" : type.getTypeName();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
