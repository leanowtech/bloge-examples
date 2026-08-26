package com.leanowtech.bloge.gateway.testing.function;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable static control rule for one or more function invocation sites. */
public final class FunctionControlRule {

    public enum Behavior {
        RETURN,
        THROW,
        DELAY,
        TIMEOUT
    }

    /** Explicit selector; blank location fields are intentional wildcards. */
    public record Selector(String graphPath, String nodeId, String functionName, int line, int column) {
        public Selector {
            graphPath = normalizeGraphPathWildcard(graphPath);
            nodeId = normalizeTextWildcard(nodeId);
            functionName = FunctionValueSupport.text(functionName, true,
                    FunctionControlException.Code.RULE_INVALID);
            if (line < -1 || column < -1 || line > 1_000_000 || column > 1_000_000) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
            }
        }

        public boolean matches(FunctionInvocationSite site) {
            return (graphPath.isEmpty() || graphPath.equals(site.graphPath()))
                    && (nodeId.isEmpty() || nodeId.equals(site.nodeId()))
                    && functionName.equals(site.functionName())
                    && (line < 0 || line == site.line())
                    && (column < 0 || column == site.column());
        }

        public boolean functionNameOnly() {
            return graphPath.isEmpty() && nodeId.isEmpty() && line < 0 && column < 0;
        }

        public String structuralKey() {
            return FunctionInvocationSite.SCHEMA_VERSION + ":selector:" +
                    encode(graphPath) + "." + encode(nodeId) + "." + encode(functionName)
                    + "." + line + "." + column;
        }

        private static String normalizeGraphPathWildcard(String value) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.length() > FunctionValueSupport.MAX_STRING_LENGTH) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
            }
            if (!normalized.isEmpty()) {
                try {
                    normalized = new FunctionInvocationSite(normalized, "node", "function", 0, 0).graphPath();
                } catch (FunctionControlException failure) {
                    throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID, failure);
                }
            }
            return normalized;
        }

        private static String normalizeTextWildcard(String value) {
            return FunctionValueSupport.text(value, false,
                    FunctionControlException.Code.RULE_INVALID);
        }

        private static String encode(String value) {
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public record Consumption(long minimum, long maximum) {
        public Consumption {
            if (minimum < 0 || maximum < minimum || maximum > FunctionValueSupport.MAX_CONSUMPTION) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
            }
        }

        public static Consumption exactly(long value) {
            return new Consumption(value, value);
        }
    }

    private final String ruleId;
    private final Selector selector;
    private final List<Object> expectedArguments;
    private final Behavior behavior;
    private final boolean returnValueProvided;
    private final Object returnValue;
    private final String returnValueFingerprint;
    private final String errorMessage;
    private final String errorFingerprint;
    private final Duration duration;
    private final Consumption consumption;
    private final boolean forcePureOverride;
    private final int priority;

    public FunctionControlRule(
            String ruleId,
            Selector selector,
            List<?> expectedArguments,
            Behavior behavior,
            Object returnValue,
            String errorMessage,
            Duration duration,
            Consumption consumption,
            boolean forcePureOverride,
            int priority
    ) {
        this(ruleId, selector, expectedArguments, behavior, returnValue, errorMessage, duration,
                consumption, forcePureOverride, priority,
                behavior == Behavior.RETURN || behavior == Behavior.DELAY);
    }

    private FunctionControlRule(
            String ruleId,
            Selector selector,
            List<?> expectedArguments,
            Behavior behavior,
            Object returnValue,
            String errorMessage,
            Duration duration,
            Consumption consumption,
            boolean forcePureOverride,
            int priority,
            boolean returnValueProvided
    ) {
        this.ruleId = FunctionValueSupport.text(ruleId, true, FunctionControlException.Code.RULE_INVALID);
        if (selector == null) {
            throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
        }
        this.selector = selector;
        this.expectedArguments = FunctionValueSupport.arguments(expectedArguments);
        if (behavior == null) {
            throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
        }
        this.behavior = behavior;
        this.returnValueProvided = returnValueProvided;
        this.returnValue = FunctionValueSupport.freeze(returnValue);
        this.returnValueFingerprint = this.returnValueProvided
                ? FunctionValueSupport.fingerprint(this.returnValue) : "";
        this.errorMessage = sanitizeError(errorMessage);
        this.errorFingerprint = behavior == Behavior.THROW
                ? FunctionValueSupport.fingerprint(this.errorMessage) : "";
        this.duration = duration == null ? Duration.ZERO : duration;
        long durationMillis;
        try {
            durationMillis = this.duration.toMillis();
        } catch (ArithmeticException failure) {
            throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID, failure);
        }
        if (this.duration.isNegative() || durationMillis > FunctionValueSupport.MAX_DURATION_MILLIS) {
            throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
        }
        this.consumption = consumption == null ? Consumption.exactly(1) : consumption;
        this.forcePureOverride = forcePureOverride;
        if (priority < 0 || priority > 1_000_000) {
            throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
        }
        this.priority = priority;
        boolean hasError = !this.errorMessage.isBlank();
        boolean hasDuration = !this.duration.isZero();
        switch (behavior) {
            case RETURN -> {
                if (!returnValueProvided || hasError || hasDuration) {
                    throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
                }
            }
            case THROW -> {
                if (!hasError || returnValueProvided || returnValue != null || hasDuration) {
                    throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
                }
            }
            case DELAY -> {
                if (!returnValueProvided || !hasDuration || hasError) {
                    throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
                }
            }
            case TIMEOUT -> {
                if (!hasDuration || returnValueProvided || returnValue != null || hasError) {
                    throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
                }
            }
        }
    }

    public FunctionControlRule(String ruleId, Selector selector, Behavior behavior) {
        this(ruleId, selector, null, behavior, null, "", Duration.ZERO,
                Consumption.exactly(1), false, 0, false);
    }

    public FunctionControlRule(String ruleId, Selector selector, Behavior behavior,
                               Object returnValue, String errorMessage, Duration duration,
                               Consumption consumption, boolean forcePureOverride, int priority) {
        this(ruleId, selector, null, behavior, returnValue, errorMessage, duration,
                consumption, forcePureOverride, priority);
    }

    public String ruleId() { return ruleId; }
    public Selector selector() { return selector; }
    public List<Object> expectedArguments() { return expectedArguments; }
    public Behavior behavior() { return behavior; }
    public String returnValueFingerprint() { return returnValueFingerprint; }
    public boolean returnValueProvided() { return returnValueProvided; }
    public String errorFingerprint() { return errorFingerprint; }
    public Duration duration() { return duration; }
    public Consumption consumption() { return consumption; }
    public boolean forcePureOverride() { return forcePureOverride; }
    public int priority() { return priority; }

    /** Package-private server-owned executable value; public plans expose only its fingerprint. */
    Object executableReturnValue() { return returnValue; }

    String executableErrorMessage() { return errorMessage; }

    String semanticFingerprint() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("ruleId", ruleId);
        material.put("selector", selector.structuralKey());
        material.put("expectedArgumentsFingerprint", expectedArguments == null ? ""
                : FunctionValueSupport.fingerprint(expectedArguments));
        material.put("behavior", behavior.name());
        material.put("returnValueFingerprint", returnValueFingerprint);
        material.put("returnValueProvided", returnValueProvided);
        material.put("errorFingerprint", errorFingerprint);
        material.put("durationMillis", duration.toMillis());
        material.put("minimumConsumption", consumption.minimum());
        material.put("maximumConsumption", consumption.maximum());
        material.put("forcePureOverride", forcePureOverride);
        material.put("priority", priority);
        return FunctionValueSupport.fingerprint(material);
    }

    @Override
    public String toString() {
        return "FunctionControlRule[id=" + ruleId + ", selector=" + selector.structuralKey()
                + ", behavior=" + behavior + ", priority=" + priority + "]";
    }

    private static String sanitizeError(String value) {
        String raw = value == null ? "" : value;
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
            }
        }
        String normalized = raw.trim();
        if (normalized.length() > 512) {
            throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
        }
        return normalized;
    }
}
