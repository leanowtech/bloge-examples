package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.exception.DecisionTableViolationException;
import com.leanowtech.bloge.core.model.NodeError;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Independent, bounded runner for one admitted stateless or stateful BLOGE world fragment. */
public final class WorldFragmentTestKit {
    private static final int MAX_DEPTH = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public record Limits(
            int maxSourceBytes,
            int maxPrimitiveCount,
            int maxExpressionDepth,
            int maxInputBytes,
            int maxOutputBytes,
            int maxReplayCount,
            Duration timeout
    ) {
        public Limits {
            if (maxSourceBytes <= 0 || maxPrimitiveCount <= 0 || maxExpressionDepth <= 0
                    || maxExpressionDepth > MAX_DEPTH || maxInputBytes <= 0
                    || maxOutputBytes <= 0 || maxReplayCount <= 0 || timeout == null
                    || timeout.isNegative() || timeout.isZero()) {
                throw failure(WorldModelException.Code.LIMIT_EXCEEDED);
            }
        }

        public static Limits defaults() {
            return new Limits(1_000_000, 64, 32, 1_000_000,
                    1_000_000, 100, Duration.ofSeconds(2));
        }
    }

    public record ReplayResult(Object response, String responseFingerprint,
                               Map<String, Object> state, int replayCount, Duration elapsed) {
        public ReplayResult {
            state = state == null ? Map.of() : immutableMap(state);
        }

        public ReplayResult(Object response, String responseFingerprint,
                            int replayCount, Duration elapsed) {
            this(response, responseFingerprint, Map.of(), replayCount, elapsed);
        }
    }

    /** Result of an isolated stateful fragment trial. All values are detached and immutable. */
    public record StatefulReplayResult(
            Object response,
            Map<String, Object> newState,
            Map<String, Object> stateWrites,
            String responseFingerprint,
            String stateFingerprint,
            String transitionFingerprint,
            int replayCount,
            Duration elapsed
    ) {
        public StatefulReplayResult {
            response = freezeResult(response);
            newState = freezeResultMap(newState);
            stateWrites = freezeResultMap(stateWrites);
            if (responseFingerprint == null || stateFingerprint == null
                    || transitionFingerprint == null || replayCount < 1 || elapsed == null) {
                throw failure(WorldModelException.Code.STATE_INPUT_INVALID);
            }
        }

        public StatefulReplayResult(Object response, Map<String, Object> newState,
                                    String responseFingerprint, String stateFingerprint,
                                    String transitionFingerprint, int replayCount,
                                    Duration elapsed) {
            this(response, newState, Map.of(), responseFingerprint, stateFingerprint,
                    transitionFingerprint, replayCount, elapsed);
        }

        /** Naming convenience for callers that use the state term from the fragment envelope. */
        public Map<String, Object> state() {
            return newState;
        }
    }

    private record StatefulOutcome(
            Object response,
            Map<String, Object> newState,
            Map<String, Object> stateWrites,
            String responseFingerprint,
            String writeSetFingerprint,
            String stateFingerprint,
            String transitionFingerprint
    ) {
    }

    private record DepthFrame(Object value, int depth, boolean exit) {
    }

    @FunctionalInterface
    interface ExecutorFactory {
        ExecutorService create();
    }

    @FunctionalInterface
    interface EngineFactory {
        GraphEngine create(BlogeFragmentAdmission.Executable executable);
    }

    private final Limits limits;
    private final ExecutorFactory executorFactory;
    private final EngineFactory engineFactory;

    public WorldFragmentTestKit() {
        this(Limits.defaults(), Executors::newVirtualThreadPerTaskExecutor,
                WorldFragmentTestKit::newEngine);
    }

    public WorldFragmentTestKit(Limits limits) {
        this(limits, Executors::newVirtualThreadPerTaskExecutor,
                WorldFragmentTestKit::newEngine);
    }

    WorldFragmentTestKit(Limits limits, ExecutorFactory executorFactory,
                         EngineFactory engineFactory) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
    }

    public Limits limits() {
        return limits;
    }

    public PureBlogeFragmentValidator.ValidationResult admit(BlogeFragmentRef fragment) {
        enforceSourceLimit(fragment);
        BlogeFragmentAdmission.Result result = BlogeFragmentAdmission.compile(fragment).result();
        enforceFragmentLimits(result);
        return new PureBlogeFragmentValidator.ValidationResult(
                result.fingerprint(), result.primitiveCount(), result.expressionDepth(),
                result.outputNodeId(), result.findings());
    }

    @SuppressWarnings("unchecked")
    public <T> T execute(BlogeFragmentRef fragment, Object renderedRequest) {
        return (T) execute(fragment, renderedRequest, 1).response();
    }

    public <T> T execute(BlogeFragmentRef fragment, Object renderedRequest, Class<T> responseType) {
        Object response = execute(fragment, renderedRequest, 1).response();
        if (responseType == null || !responseType.isInstance(response)) {
            throw failure(WorldModelException.Code.FRAGMENT_EXECUTION_FAILED);
        }
        return responseType.cast(response);
    }

    public ReplayResult execute(BlogeFragmentRef fragment, Object renderedRequest, int replayCount) {
        if (replayCount < 1 || replayCount > limits.maxReplayCount()) {
            throw failure(WorldModelException.Code.LIMIT_EXCEEDED);
        }
        validateDepth(renderedRequest);
        Map<String, Object> request = renderedRequest(renderedRequest);
        enforceInputLimit(request);
        enforceSourceLimit(fragment);
        BlogeFragmentAdmission.Executable executable = BlogeFragmentAdmission.compile(fragment);
        enforceFragmentLimits(executable.result());

        long started = System.nanoTime();
        Object firstResponse = null;
        String firstFingerprint = null;
        for (int replay = 0; replay < replayCount; replay++) {
            Object response = executeOnce(executable, request);
            validateDepth(response);
            String fingerprint = fingerprint(response);
            if (firstFingerprint == null) {
                firstFingerprint = fingerprint;
                firstResponse = immutableCopy(response);
            } else if (!firstFingerprint.equals(fingerprint)) {
                throw failure(WorldModelException.Code.NON_DETERMINISTIC_REPLAY);
            }
        }
        return new ReplayResult(firstResponse, firstFingerprint, Map.of(), replayCount,
                Duration.ofNanos(System.nanoTime() - started));
    }

    /**
     * Executes a stateful fragment using the v2 state declaration. Each replay receives the same
     * detached defaults-plus-overrides snapshot. The fragment sees the fixed {@code {request,
     * state}} envelope; it must return {@code {response,stateWrites}}.
     */
    public StatefulReplayResult executeStateful(BlogeFragmentRef fragment,
                                                StateSpecV2 stateSpec,
                                                Object renderedRequest,
                                                Map<String, ?> stateOverrides,
                                                int replayCount) {
        if (replayCount < 1 || replayCount > limits.maxReplayCount()) {
            throw failure(WorldModelException.Code.LIMIT_EXCEEDED);
        }
        if (stateSpec == null || stateOverrides == null) {
            throw failure(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        validateDepth(renderedRequest);
        Map<String, Object> request = renderedRequest(renderedRequest);
        enforceInputLimit(request);
        Map<String, Object> initialState = normalizeInitialState(stateSpec, stateOverrides);
        validateDepth(initialState);

        enforceSourceLimit(fragment);
        BlogeFragmentAdmission.Executable executable = BlogeFragmentAdmission.compile(fragment);
        enforceFragmentLimits(executable.result());

        long started = System.nanoTime();
        StatefulOutcome first = null;
        for (int replay = 0; replay < replayCount; replay++) {
            StatefulOutcome current = executeStatefulOnce(executable, stateSpec, request, initialState);
            if (first == null) {
                first = current;
            } else if (!sameStatefulOutcome(first, current)) {
                throw failure(WorldModelException.Code.NON_DETERMINISTIC_REPLAY);
            }
        }
        return new StatefulReplayResult(first.response(), first.newState(), first.stateWrites(),
                first.responseFingerprint(), first.stateFingerprint(), first.transitionFingerprint(),
                replayCount, Duration.ofNanos(System.nanoTime() - started));
    }

    /** Alias that keeps the public API discoverable beside the existing stateless execute method. */
    public StatefulReplayResult execute(BlogeFragmentRef fragment,
                                         StateSpecV2 stateSpec,
                                         Object renderedRequest,
                                         Map<String, ?> stateOverrides,
                                         int replayCount) {
        return executeStateful(fragment, stateSpec, renderedRequest, stateOverrides, replayCount);
    }

    private StatefulOutcome executeStatefulOnce(BlogeFragmentAdmission.Executable executable,
                                                StateSpecV2 stateSpec,
                                                Map<String, Object> request,
                                                Map<String, Object> initialState) {
        Map<String, Object> visibleState = visibleState(stateSpec, initialState);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("request", request);
        envelope.put("state", visibleState);
        Map<String, Object> frozenEnvelope = immutableMap(envelope);
        enforceInputLimit(frozenEnvelope);
        Object raw = executeOnce(executable, frozenEnvelope);
        validateDepth(raw);
        enforceOutputLimit(raw);
        Map<?, ?> result = requireStatefulEnvelope(raw);
        Object response = immutableCopy(result.get("response"));
        Object rawWrites = result.get("stateWrites");
        if (!(rawWrites instanceof Map<?, ?> writes)) {
            throw failure(WorldModelException.Code.STATE_WRITESET_INVALID);
        }
        Map<String, Object> stateWrites = normalizeStateWrites(stateSpec, writes);
        validateDepth(response);
        validateDepth(stateWrites);
        enforceOutputLimit(response);
        enforceOutputLimit(stateWrites);

        // Validate the complete write set before constructing the next state snapshot.
        for (Map.Entry<String, Object> write : stateWrites.entrySet()) {
            StateKeySpec declaration = declaration(stateSpec, write.getKey());
            if (declaration.access() == StateKeySpec.Access.READ) {
                throw failure(WorldModelException.Code.STATE_READ_ONLY_WRITE);
            }
            if (!accepts(declaration, write.getValue())) {
                throw failure(WorldModelException.Code.STATE_SCHEMA_MISMATCH);
            }
        }
        Map<String, Object> newState = new LinkedHashMap<>(initialState);
        stateWrites.forEach((key, value) -> newState.put(key, immutableCopy(value)));
        Map<String, Object> frozenState = immutableMap(newState);
        enforceOutputLimit(frozenState);
        String responseFingerprint = fingerprint(response, limits.maxOutputBytes());
        String stateFingerprint = fingerprint(frozenState, limits.maxOutputBytes());
        String writeSetFingerprint = fingerprint(stateWrites, limits.maxOutputBytes());
        Map<String, Object> transitionMaterial = new LinkedHashMap<>();
        transitionMaterial.put("fragmentFingerprint", executable.result().fingerprint());
        transitionMaterial.put("stateSpecFingerprint", stateSpec.fingerprint());
        transitionMaterial.put("requestFingerprint", fingerprint(request, limits.maxInputBytes()));
        transitionMaterial.put("initialStateFingerprint", fingerprint(initialState, limits.maxInputBytes()));
        transitionMaterial.put("responseFingerprint", responseFingerprint);
        transitionMaterial.put("writeSetFingerprint", writeSetFingerprint);
        transitionMaterial.put("finalStateFingerprint", stateFingerprint);
        String transitionFingerprint = fingerprint(transitionMaterial, limits.maxOutputBytes());
        return new StatefulOutcome(response, frozenState, stateWrites, responseFingerprint,
                writeSetFingerprint, stateFingerprint, transitionFingerprint);
    }

    private Map<String, Object> normalizeInitialState(StateSpecV2 stateSpec,
                                                       Map<String, ?> overrides) {
        Map<String, Object> normalizedOverrides = immutableMapCopy(overrides,
                WorldModelException.Code.STATE_INPUT_INVALID);
        Map<String, StateKeySpec> declarations = declarations(stateSpec);
        Map<String, Object> state = new LinkedHashMap<>();
        for (StateKeySpec declaration : stateSpec.declarations()) {
            state.put(declaration.key(), immutableCopy(declaration.defaultValue()));
        }
        for (Map.Entry<String, Object> override : normalizedOverrides.entrySet()) {
            StateKeySpec declaration = declarations.get(override.getKey());
            if (declaration == null) {
                throw failure(WorldModelException.Code.STATE_INPUT_INVALID);
            }
            if (!accepts(declaration, override.getValue())) {
                throw failure(WorldModelException.Code.STATE_SCHEMA_MISMATCH);
            }
            state.put(declaration.key(), immutableCopy(override.getValue()));
        }
        return immutableMap(state);
    }

    private Map<String, Object> visibleState(StateSpecV2 stateSpec, Map<String, Object> state) {
        Map<String, Object> visible = new LinkedHashMap<>();
        for (StateKeySpec declaration : stateSpec.declarations()) {
            if (declaration.access() == StateKeySpec.Access.WRITE) {
                continue;
            }
            putVisiblePath(visible, declaration.key(), state.get(declaration.key()));
        }
        return immutableMap(visible);
    }

    private Map<String, Object> normalizeStateWrites(StateSpecV2 stateSpec, Map<?, ?> rawWrites) {
        Map<String, StateKeySpec> declarations = declarations(stateSpec);
        Map<String, Object> writes = new java.util.TreeMap<>();
        for (Map.Entry<?, ?> entry : rawWrites.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw failure(WorldModelException.Code.STATE_UNKNOWN_WRITE);
            }
            flattenWriteSet(key, entry.getValue(), "", declarations, writes);
        }
        return immutableMap(writes);
    }

    private static void flattenWriteSet(String key,
                                        Object value,
                                        String parent,
                                        Map<String, StateKeySpec> declarations,
                                        Map<String, Object> writes) {
        String pointer;
        if (key.startsWith("/")) {
            try {
                pointer = StatePointer.normalize(key);
            } catch (WorldModelException invalid) {
                throw failure(WorldModelException.Code.STATE_UNKNOWN_WRITE);
            }
        } else {
            try {
                if (parent.isEmpty()) {
                    pointer = StatePointer.append("/root", key).substring("/root".length());
                } else {
                    pointer = StatePointer.append(parent, key);
                }
            } catch (WorldModelException invalid) {
                throw failure(WorldModelException.Code.STATE_UNKNOWN_WRITE);
            }
        }
        if (declarations.containsKey(pointer)) {
            if (writes.put(pointer, immutableCopy(value)) != null) {
                throw failure(WorldModelException.Code.STATE_WRITESET_INVALID);
            }
            return;
        }
        if (!(value instanceof Map<?, ?> nested) || nested.isEmpty()) {
            throw failure(WorldModelException.Code.STATE_UNKNOWN_WRITE);
        }
        List<String> keys = new ArrayList<>();
        for (Object nestedKey : nested.keySet()) {
            if (!(nestedKey instanceof String text) || text.isBlank()) {
                throw failure(WorldModelException.Code.STATE_UNKNOWN_WRITE);
            }
            keys.add(text);
        }
        Collections.sort(keys);
        for (String nestedKey : keys) {
            flattenWriteSet(nestedKey, nested.get(nestedKey), pointer, declarations, writes);
        }
    }

    private static StateKeySpec declaration(StateSpecV2 stateSpec, String key) {
        for (StateKeySpec declaration : stateSpec.declarations()) {
            if (declaration.key().equals(key)) {
                return declaration;
            }
        }
        throw failure(WorldModelException.Code.STATE_UNKNOWN_WRITE);
    }

    private static Map<String, StateKeySpec> declarations(StateSpecV2 stateSpec) {
        Map<String, StateKeySpec> declarations = new LinkedHashMap<>();
        for (StateKeySpec declaration : stateSpec.declarations()) {
            declarations.put(declaration.key(), declaration);
        }
        return declarations;
    }

    static Map<?, ?> requireStatefulEnvelope(Object raw) {
        if (!(raw instanceof Map<?, ?> result)) {
            throw failure(WorldModelException.Code.STATE_OUTPUT_MISSING);
        }
        for (Object key : result.keySet()) {
            if (!(key instanceof String text)
                    || (!text.equals("response") && !text.equals("stateWrites"))) {
                throw failure(WorldModelException.Code.STATE_OUTPUT_EXTRA_FIELDS);
            }
        }
        if (!result.containsKey("response") || !result.containsKey("stateWrites")) {
            throw failure(WorldModelException.Code.STATE_OUTPUT_MISSING);
        }
        return result;
    }

    private static boolean accepts(StateKeySpec declaration, Object value) {
        return declaration.accepts(value);
    }

    private static void putVisiblePath(Map<String, Object> root, String pointer, Object value) {
        List<String> segments = StatePointer.decode(pointer);
        Map<String, Object> current = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            Object next = current.get(segments.get(index));
            if (!(next instanceof Map<?, ?>)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(segments.get(index), created);
                current = created;
            } else {
                @SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) next;
                current = nested;
            }
        }
        current.put(segments.get(segments.size() - 1), immutableCopy(value));
    }

    private boolean sameStatefulOutcome(StatefulOutcome left, StatefulOutcome right) {
        return left.responseFingerprint().equals(right.responseFingerprint())
                && left.writeSetFingerprint().equals(right.writeSetFingerprint())
                && left.stateFingerprint().equals(right.stateFingerprint())
                && left.transitionFingerprint().equals(right.transitionFingerprint())
                && Objects.deepEquals(left.response(), right.response())
                && Objects.equals(left.newState(), right.newState());
    }

    private void enforceOutputLimit(Object value) {
        fingerprint(value, limits.maxOutputBytes());
    }

    private Object executeOnce(BlogeFragmentAdmission.Executable executable,
                               Map<String, Object> request) {
        GraphEngine engine = engineFactory.create(executable);
        ExecutorService executor = executorFactory.create();
        Future<Object> future = executor.submit(() -> runGraph(engine, executable, request));
        try {
            return future.get(timeoutNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw failure(WorldModelException.Code.LIMIT_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(WorldModelException.Code.FRAGMENT_EXECUTION_FAILED);
        } catch (ExecutionException exception) {
            WorldModelException worldFailure = findWorldFailure(exception.getCause());
            if (worldFailure != null) {
                throw worldFailure;
            }
            throw failure(WorldModelException.Code.FRAGMENT_EXECUTION_FAILED);
        } finally {
            future.cancel(true);
            executor.shutdownNow();
            try {
                executor.awaitTermination(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            engine.shutdown();
        }
    }

    private static Object runGraph(GraphEngine engine,
                                   BlogeFragmentAdmission.Executable executable,
                                   Map<String, Object> request) {
        GraphResult result = engine.execute(executable.graph(), new GraphContext(request));
        if (!result.isSuccess()) {
            if (ambiguous(result.errors())) {
                throw failure(WorldModelException.Code.FRAGMENT_AMBIGUOUS);
            }
            throw failure(WorldModelException.Code.FRAGMENT_EXECUTION_FAILED);
        }
        if (!result.results().hasResult(executable.result().outputNodeId())) {
            throw failure(WorldModelException.Code.FRAGMENT_EXECUTION_FAILED);
        }
        return result.results().getRaw(executable.result().outputNodeId());
    }

    private void enforceFragmentLimits(BlogeFragmentAdmission.Result result) {
        if (result.primitiveCount() > limits.maxPrimitiveCount()) {
            throw failure(WorldModelException.Code.LIMIT_NODE_EXCEEDED);
        }
        if (result.expressionDepth() > limits.maxExpressionDepth()) {
            throw failure(WorldModelException.Code.LIMIT_DEPTH_EXCEEDED);
        }
    }

    private void enforceSourceLimit(BlogeFragmentRef fragment) {
        if (fragment != null && fragment.source().getBytes(StandardCharsets.UTF_8).length
                > limits.maxSourceBytes()) {
            throw failure(WorldModelException.Code.LIMIT_EXCEEDED);
        }
    }

    private void enforceInputLimit(Map<String, Object> request) {
        try {
            ProtocolFingerprint.ofBounded(MAPPER, request, limits.maxInputBytes());
        } catch (WorldModelException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw failure(WorldModelException.Code.LIMIT_EXCEEDED);
        }
    }

    private long timeoutNanos() {
        try {
            return limits.timeout().toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private String fingerprint(Object response) {
        return fingerprint(response, limits.maxOutputBytes());
    }

    private String fingerprint(Object response, int maximumBytes) {
        try {
            if (MAPPER.writeValueAsBytes(response).length > maximumBytes) {
                throw failure(WorldModelException.Code.LIMIT_OUTPUT_EXCEEDED);
            }
            return ProtocolFingerprint.ofBounded(MAPPER, response, maximumBytes);
        } catch (JsonProcessingException exception) {
            throw failure(WorldModelException.Code.FRAGMENT_EXECUTION_FAILED);
        } catch (IllegalArgumentException exception) {
            if (exception instanceof WorldModelException worldFailure) {
                throw worldFailure;
            }
            throw failure(WorldModelException.Code.LIMIT_OUTPUT_EXCEEDED);
        }
    }

    private static boolean ambiguous(List<NodeError> errors) {
        for (NodeError error : errors) {
            for (Throwable cause = error.exception(); cause != null; cause = cause.getCause()) {
                if (cause instanceof DecisionTableViolationException violation
                        && (DecisionTableViolationException.CODE_AMBIGUOUS_MATCH.equals(violation.code())
                        || DecisionTableViolationException.CODE_CONFLICTING_MATCH.equals(violation.code()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> renderedRequest(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw failure(WorldModelException.Code.FRAGMENT_INVALID);
        }
        return immutableMapCopy(map, WorldModelException.Code.FRAGMENT_INVALID);
    }

    private static Map<String, Object> immutableMapCopy(Map<?, ?> map,
                                                         WorldModelException.Code code) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw failure(code);
            }
            copy.put(key, immutableCopy(entry.getValue()));
        }
        return immutableMap(copy);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static Object immutableCopy(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw failure(WorldModelException.Code.FRAGMENT_EXECUTION_FAILED);
                }
                copy.put(key, immutableCopy(entry.getValue()));
            }
            return immutableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableCopy(item)));
            return Collections.unmodifiableList(copy);
        }
        throw failure(WorldModelException.Code.FRAGMENT_EXECUTION_FAILED);
    }

    private static Object freezeResult(Object value) {
        try {
            return ProtocolJsonValue.freeze(value);
        } catch (RuntimeException invalid) {
            throw failure(WorldModelException.Code.STATE_OUTPUT_INVALID);
        }
    }

    private static Map<String, Object> freezeResultMap(Map<String, Object> value) {
        try {
            return immutableMapCopy(value, WorldModelException.Code.STATE_OUTPUT_INVALID);
        } catch (RuntimeException invalid) {
            if (invalid instanceof WorldModelException worldFailure) {
                throw worldFailure;
            }
            throw failure(WorldModelException.Code.STATE_OUTPUT_INVALID);
        }
    }

    private static WorldModelException findWorldFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof WorldModelException worldFailure) {
                return worldFailure;
            }
        }
        return null;
    }

    private void validateDepth(Object root) {
        if (root == null) {
            return;
        }
        Deque<DepthFrame> pending = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> ancestors = new IdentityHashMap<>();
        pending.push(new DepthFrame(root, 1, false));
        while (!pending.isEmpty()) {
            DepthFrame frame = pending.pop();
            Object value = frame.value();
            if (frame.exit()) {
                ancestors.remove(value);
                continue;
            }
            if (frame.depth() > limits.maxExpressionDepth()) {
                throw failure(WorldModelException.Code.LIMIT_DEPTH_EXCEEDED);
            }
            if (value == null || value instanceof String
                    || value instanceof Number || value instanceof Boolean) {
                continue;
            }
            Iterable<?> children;
            if (value instanceof Map<?, ?> map) {
                children = map.values();
            } else if (value instanceof List<?> list) {
                children = list;
            } else {
                throw failure(WorldModelException.Code.FRAGMENT_INVALID);
            }
            if (ancestors.put(value, Boolean.TRUE) != null) {
                throw failure(WorldModelException.Code.FRAGMENT_INVALID);
            }
            pending.push(new DepthFrame(value, frame.depth(), true));
            for (Object child : children) {
                pending.push(new DepthFrame(child, frame.depth() + 1, false));
            }
        }
    }

    private static GraphEngine newEngine(BlogeFragmentAdmission.Executable executable) {
        return GraphEngine.builder()
                .registry(executable.isolatedRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .maxGlobalConcurrency(1)
                .build();
    }

    private static WorldModelException failure(WorldModelException.Code code) {
        return new WorldModelException(code);
    }
}
