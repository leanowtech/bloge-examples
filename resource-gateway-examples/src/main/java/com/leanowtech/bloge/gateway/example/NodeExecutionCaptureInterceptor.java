package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.core.operator.DecisionTableInput;
import com.leanowtech.bloge.core.spi.OperatorInterceptor;
import com.leanowtech.bloge.core.spi.OperatorInvocation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-correlated operator interceptor that captures exact node invocation inputs and outputs.
 */
final class NodeExecutionCaptureInterceptor implements OperatorInterceptor {

    static final String CAPTURE_ID_CONTEXT_KEY = "_blogeEvidenceCaptureId";

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, List<DynamicGraphRunResponse.NodeAttempt>>>
            captures = new ConcurrentHashMap<>();

    void begin(String captureId) {
        captures.put(captureId, new ConcurrentHashMap<>());
    }

    Map<String, List<DynamicGraphRunResponse.NodeAttempt>> complete(String captureId) {
        Map<String, List<DynamicGraphRunResponse.NodeAttempt>> captured = captures.remove(captureId);
        if (captured == null || captured.isEmpty()) {
            return Map.of();
        }
        Map<String, List<DynamicGraphRunResponse.NodeAttempt>> ordered = new LinkedHashMap<>();
        captured.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparingInt(DynamicGraphRunResponse.NodeAttempt::attempt)
                                .thenComparing(DynamicGraphRunResponse.NodeAttempt::startedAt))
                        .toList()));
        return ordered;
    }

    @Override
    public Object intercept(OperatorInvocation invocation) throws Exception {
        String captureId = captureId(invocation);
        if (captureId.isBlank() || !captures.containsKey(captureId)) {
            return invocation.proceed();
        }
        Instant startedAt = Instant.now();
        try {
            Object output = invocation.proceed();
            record(captureId, invocation, startedAt, output, "SUCCESS", "", "");
            return output;
        } catch (Exception exception) {
            record(captureId, invocation, startedAt, null, "FAILED", exception.getClass().getName(),
                    message(exception));
            throw exception;
        }
    }

    private void record(String captureId,
                        OperatorInvocation invocation,
                        Instant startedAt,
                        Object output,
                        String status,
                        String errorType,
                        String errorMessage) {
        ConcurrentHashMap<String, List<DynamicGraphRunResponse.NodeAttempt>> byNode = captures.get(captureId);
        if (byNode == null) {
            return;
        }
        DynamicGraphRunResponse.NodeAttempt attempt = new DynamicGraphRunResponse.NodeAttempt(
                invocation.operatorContext().retryAttempt(), userFacingInput(invocation.input()), output, status,
                startedAt, Math.max(0, Duration.between(startedAt, Instant.now()).toMillis()), errorType, errorMessage
        );
        byNode.computeIfAbsent(invocation.nodeId(), ignored -> java.util.Collections.synchronizedList(
                        new ArrayList<>()))
                .add(attempt);
    }

    private static Object userFacingInput(Object input) {
        if (input instanceof DecisionTableInput decisionTableInput) {
            return decisionTableInput.params();
        }
        return input;
    }

    private static String captureId(OperatorInvocation invocation) {
        Object value = invocation.operatorContext().graphContext().get(CAPTURE_ID_CONTEXT_KEY);
        return value == null ? "" : String.valueOf(value);
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
