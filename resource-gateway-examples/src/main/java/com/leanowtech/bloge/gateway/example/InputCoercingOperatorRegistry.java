package com.leanowtech.bloge.gateway.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.SideEffectProtocol;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.OperatorMetadata;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.stream.NodeChannel;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Adapts DSL-assembled map inputs to the Java input types declared by registered operators.
 */
final class InputCoercingOperatorRegistry implements OperatorRegistry {

    private final OperatorRegistry delegate;
    private final ObjectMapper objectMapper;

    private InputCoercingOperatorRegistry(OperatorRegistry delegate, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    static OperatorRegistry wrap(OperatorRegistry delegate, ObjectMapper objectMapper) {
        return new InputCoercingOperatorRegistry(delegate, objectMapper);
    }

    @Override
    public void register(String name, Operator<?, ?> operator) {
        delegate.register(name, operator);
    }

    @Override
    public void registerRaw(String name, Object operator) {
        delegate.registerRaw(name, operator);
    }

    @Override
    public Object lookup(String name) {
        Object operator = delegate.lookup(name);
        OperatorMetadata metadata = delegate.metadata(name);
        return adapt(operator, metadata, objectMapper);
    }

    @Override
    public OperatorMetadata metadata(String name) {
        return delegate.metadata(name);
    }

    @Override
    public boolean contains(String name) {
        return delegate.contains(name);
    }

    @Override
    public List<String> discover(String pattern) {
        return delegate.discover(pattern);
    }

    @Override
    public void addRegistrationListener(RegistrationListener listener) {
        delegate.addRegistrationListener(listener);
    }

    @SuppressWarnings("unchecked")
    private static Object adapt(Object operator, OperatorMetadata metadata, ObjectMapper objectMapper) {
        if (operator instanceof StreamingOperator<?, ?> streaming) {
            return new CoercingStreamingOperator(
                    (StreamingOperator<Object, Object>) streaming,
                    metadata,
                    objectMapper);
        }
        if (operator instanceof SuspendableOperator<?, ?> suspendable) {
            return new CoercingSuspendableOperator(
                    (SuspendableOperator<Object, Object>) suspendable,
                    metadata,
                    objectMapper);
        }
        if (operator instanceof Operator<?, ?> typed) {
            return new CoercingOperator((Operator<Object, Object>) typed, metadata, objectMapper);
        }
        return operator;
    }

    private static Object coerce(Object input, OperatorMetadata metadata, ObjectMapper objectMapper) {
        Class<?> targetClass = metadata.inputClass();
        if (targetClass == null || Object.class.equals(targetClass)) {
            return input;
        }
        if (input == null || targetClass.isInstance(input)) {
            return input;
        }
        if (Void.class.equals(targetClass) || Void.TYPE.equals(targetClass)) {
            return null;
        }
        if (Map.class.isAssignableFrom(targetClass)) {
            return input;
        }

        Object source = input;
        if (input instanceof Map<?, ?> map && shouldUnwrapEnvelope(targetClass)) {
            source = unwrapEnvelope(map);
        }
        Type targetType = metadata.inputType() == null ? targetClass : metadata.inputType();
        try {
            return objectMapper.convertValue(source, objectMapper.constructType(targetType));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Cannot coerce visual DSL input from %s to %s: %s"
                            .formatted(input.getClass().getName(), targetType.getTypeName(), ex.getMessage()),
                    ex);
        }
    }

    private static boolean shouldUnwrapEnvelope(Class<?> targetClass) {
        return targetClass.isPrimitive()
                || targetClass.isEnum()
                || targetClass.isArray()
                || CharSequence.class.isAssignableFrom(targetClass)
                || Number.class.isAssignableFrom(targetClass)
                || Boolean.class.equals(targetClass)
                || Character.class.equals(targetClass)
                || Collection.class.isAssignableFrom(targetClass);
    }

    private static Object unwrapEnvelope(Map<?, ?> map) {
        if (map.containsKey("input")) {
            return map.get("input");
        }
        if (map.containsKey("value")) {
            return map.get("value");
        }
        if (map.size() == 1) {
            return map.values().iterator().next();
        }
        return map;
    }

    private record CoercingOperator(Operator<Object, Object> delegate,
                                    OperatorMetadata metadata,
                                    ObjectMapper objectMapper) implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) throws Exception {
            return delegate.execute(coerce(input, metadata, objectMapper), ctx);
        }

        @Override
        public Idempotency idempotency() {
            return delegate.idempotency();
        }

        @Override
        public SideEffectType sideEffectType() {
            return delegate.sideEffectType();
        }

        @Override
        public SideEffectProtocol sideEffectProtocol() {
            return delegate.sideEffectProtocol();
        }
    }

    private record CoercingSuspendableOperator(SuspendableOperator<Object, Object> delegate,
                                               OperatorMetadata metadata,
                                               ObjectMapper objectMapper) implements SuspendableOperator<Object, Object> {
        @Override
        public OperatorResult<Object> execute(Object input, OperatorContext ctx) throws Exception {
            return delegate.execute(coerce(input, metadata, objectMapper), ctx);
        }

        @Override
        public Idempotency idempotency() {
            return delegate.idempotency();
        }

        @Override
        public SideEffectType sideEffectType() {
            return delegate.sideEffectType();
        }

        @Override
        public SideEffectProtocol sideEffectProtocol() {
            return delegate.sideEffectProtocol();
        }
    }

    private record CoercingStreamingOperator(StreamingOperator<Object, Object> delegate,
                                             OperatorMetadata metadata,
                                             ObjectMapper objectMapper) implements StreamingOperator<Object, Object> {
        @Override
        public void execute(Object input, NodeChannel<Object> output, OperatorContext ctx) throws Exception {
            delegate.execute(coerce(input, metadata, objectMapper), output, ctx);
        }

        @Override
        public Idempotency idempotency() {
            return delegate.idempotency();
        }

        @Override
        public SideEffectType sideEffectType() {
            return delegate.sideEffectType();
        }

        @Override
        public SideEffectProtocol sideEffectProtocol() {
            return delegate.sideEffectProtocol();
        }
    }
}
