package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One immutable execution-control rule that selects invocation sites, supplies a declarative test
 * behavior, and defines how use of that behavior is validated.
 *
 * @param schemaVersion fixture-rule schema version
 * @param ruleId stable id within a fixture bundle
 * @param selector invocation selector and input match constraints
 * @param behavior replacement, observation, or denial behavior
 * @param consumption required and bounded use policy
 * @param schemaCheck schema validation policy and explicit waiver
 */
public record FixtureRule(
        String schemaVersion,
        String ruleId,
        Selector selector,
        Behavior behavior,
        Consumption consumption,
        SchemaCheck schemaCheck
) {
    /** Current fixture-rule protocol version. */
    public static final String SCHEMA_VERSION = "bloge.fixtureRule.v1";

    /** All behavior names are frozen in v1; STREAM remains reserved. */
    public enum BehaviorKind {
        REAL,
        RETURN,
        THROW,
        DELAY,
        TIMEOUT,
        STREAM,
        REPLAY,
        SPY,
        DENY
    }

    /** Boundary at which a double replaces behavior. */
    public enum DoubleBoundary {
        NODE,
        TRANSPORT
    }

    /** Action when a bounded rule has no remaining uses. */
    public enum ExhaustedAction {
        FAIL,
        FALLBACK_TO_REAL
    }

    /** Action when no rule matches an observed invocation. */
    public enum UnmatchedAction {
        FAIL,
        WARN,
        ALLOW_REAL
    }

    /** Schema enforcement mode. Waived rules can never produce certifiable evidence. */
    public enum SchemaCheckMode {
        STRICT,
        WAIVED
    }

    /** Normalizes nested policy objects and defaults to fail-closed behavior. */
    public FixtureRule {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        ruleId = trimmed(ruleId);
        selector = selector == null ? Selector.any() : selector;
        behavior = behavior == null ? Behavior.real() : behavior;
        consumption = consumption == null ? Consumption.once() : consumption;
        schemaCheck = schemaCheck == null ? SchemaCheck.strict() : schemaCheck;
    }

    /**
     * Declarative selector over a frozen structural site and its runtime coordinates.
     *
     * <p>{@code attempts} and {@code occurrences} are one-based, strictly increasing sets. Values
     * within one set are alternatives; non-empty attempt and occurrence sets are combined with
     * AND. Attempt counts actual delegate calls within one occurrence. Occurrence counts repeated
     * bindings of the same invocation site and correlation key, so a retry never advances the
     * occurrence coordinate.</p>
     *
     * @param graphPath exact graph path
     * @param nodeId exact node id
     * @param operatorRef operator-wide selector
     * @param resourceRef resource-wide selector
     * @param functionRef built-in-function selector
     * @param capabilities required operator capability labels
     * @param tags required governance or authoring tags
     * @param invocationKind invocation kind, defaulting to PRIMARY
     * @param attempts allowed one-based delegate attempts, or empty for every attempt
     * @param occurrences allowed one-based site occurrences, or empty for every occurrence
     * @param correlationKey exact business/foreach correlation key
     * @param match canonical input match constraints
     */
    public record Selector(
            String graphPath,
            String nodeId,
            String operatorRef,
            String resourceRef,
            String functionRef,
            List<String> capabilities,
            List<String> tags,
            InvocationSite.InvocationKind invocationKind,
            List<Integer> attempts,
            List<Integer> occurrences,
            String correlationKey,
            Match match
    ) {
        /** Creates an immutable selector. */
        public Selector {
            graphPath = trimmed(graphPath);
            nodeId = trimmed(nodeId);
            operatorRef = trimmed(operatorRef);
            resourceRef = trimmed(resourceRef);
            functionRef = trimmed(functionRef);
            capabilities = immutableList(capabilities);
            tags = immutableList(tags);
            invocationKind = invocationKind == null ? InvocationSite.InvocationKind.PRIMARY : invocationKind;
            attempts = immutableList(attempts);
            occurrences = immutableList(occurrences);
            correlationKey = trimmed(correlationKey);
            match = match == null ? Match.none() : match;
        }

        /** @return an unconstrained primary-invocation selector */
        public static Selector any() {
            return new Selector("", "", "", "", "", List.of(), List.of(),
                    InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "", Match.none());
        }

        /** @return an exact root-graph node selector */
        public static Selector node(String nodeId) {
            return new Selector("/root", nodeId, "", "", "", List.of(), List.of(),
                    InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "", Match.none());
        }

        /** @return an operator-wide primary invocation selector */
        public static Selector operator(String operatorRef) {
            return new Selector("", "", operatorRef, "", "", List.of(), List.of(),
                    InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "", Match.none());
        }

        /** @return a descriptor-backed resource selector */
        public static Selector resource(String resourceRef) {
            return new Selector("", "", "", resourceRef, "", List.of(), List.of(),
                    InvocationSite.InvocationKind.RESOURCE, List.of(), List.of(), "", Match.none());
        }

        /**
         * Returns a copy with additional input-match constraints.
         *
         * @param value input constraints
         * @return copied selector
         */
        public Selector matching(Match value) {
            return new Selector(graphPath, nodeId, operatorRef, resourceRef, functionRef,
                    capabilities, tags, invocationKind, attempts, occurrences, correlationKey, value);
        }
    }

    /**
     * Auditable, non-executable input match constraints.
     *
     * @param canonicalInput whole-input canonical JSON equality target
     * @param pathEquals JSON Pointer to expected-value equality constraints
     * @param pathsExist JSON Pointers that must exist
     * @param pathsAbsent JSON Pointers that must be absent
     * @param schema JSON Schema the invocation input must satisfy
     * @param correlationKey correlation key equality constraint
     * @param boundedRegex JSON Pointer to bounded regular-expression constraints
     */
    public record Match(
            Object canonicalInput,
            Map<String, Object> pathEquals,
            List<String> pathsExist,
            List<String> pathsAbsent,
            Map<String, Object> schema,
            String correlationKey,
            Map<String, String> boundedRegex
    ) {
        /** Creates immutable match collections. */
        public Match {
            pathEquals = immutableMap(pathEquals);
            pathsExist = immutableList(pathsExist);
            pathsAbsent = immutableList(pathsAbsent);
            schema = immutableMap(schema);
            correlationKey = trimmed(correlationKey);
            boundedRegex = boundedRegex == null ? Map.of() : Map.copyOf(boundedRegex);
        }

        /** @return match specification with no constraints */
        public static Match none() {
            return new Match(null, Map.of(), List.of(), List.of(), Map.of(), "", Map.of());
        }

        /** @return an exact JSON Pointer equality constraint */
        public static Match pathEquals(String path, Object value) {
            return new Match(null, Map.of(path, value), List.of(), List.of(), Map.of(), "", Map.of());
        }
    }

    /**
     * Declarative double behavior. Time controls require a bundle logical clock; stream, sequence,
     * and sequence fields remain protocol reservations in v1. REPLAY accepts only an exact
     * governed replay reference resolved by the authorized server boundary.
     *
     * @param kind requested behavior
     * @param boundary node or transport replacement boundary
     * @param value return value for output-level doubles
     * @param rawBody raw protocol body for protocol-derived resource doubles
     * @param statusCode protocol status code
     * @param headers protocol response headers
     * @param errorCode stable platform error code
     * @param errorType normalized error class
     * @param errorMessage bounded diagnostic message
     * @param after required logical delay or timeout duration for DELAY/TIMEOUT
     * @param sequence scripted behavior sequence, reserved in v1
     * @param replayRef governed replay payload reference used only by REPLAY
     */
    public record Behavior(
            BehaviorKind kind,
            DoubleBoundary boundary,
            Object value,
            String rawBody,
            Integer statusCode,
            Map<String, String> headers,
            String errorCode,
            String errorType,
            String errorMessage,
            Duration after,
            List<BehaviorStep> sequence,
            String replayRef
    ) {
        /** Creates an immutable behavior with node-boundary defaults. */
        public Behavior {
            kind = kind == null ? BehaviorKind.REAL : kind;
            boundary = boundary == null ? DoubleBoundary.NODE : boundary;
            rawBody = rawBody == null ? "" : rawBody;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            errorCode = trimmed(errorCode);
            errorType = trimmed(errorType);
            errorMessage = errorMessage == null ? "" : errorMessage;
            sequence = immutableList(sequence);
            replayRef = trimmed(replayRef);
        }

        /** @return explicit real-execution behavior */
        public static Behavior real() {
            return new Behavior(BehaviorKind.REAL, DoubleBoundary.NODE, null, "", null,
                    Map.of(), "", "", "", null, List.of(), "");
        }

        /** @return a node-boundary fixed return behavior */
        public static Behavior returning(Object value) {
            return new Behavior(BehaviorKind.RETURN, DoubleBoundary.NODE, value, "", null,
                    Map.of(), "", "", "", null, List.of(), "");
        }

        /** @return a protocol response used to exercise real resource response handling */
        public static Behavior protocolResponse(String rawBody, int statusCode,
                                                Map<String, String> headers,
                                                DoubleBoundary boundary) {
            return new Behavior(BehaviorKind.RETURN, boundary, null, rawBody, statusCode,
                    headers, "", "", "", null, List.of(), "");
        }

        /** @return a standardized non-retryable injected failure */
        public static Behavior throwing(String errorCode, String errorType, String errorMessage) {
            return new Behavior(BehaviorKind.THROW, DoubleBoundary.NODE, null, "", null,
                    Map.of(), errorCode, errorType, errorMessage, null, List.of(), "");
        }

        /** @return a node-boundary logical delay followed by a schema-gated fixed return */
        public static Behavior delayed(Duration after, Object value) {
            return new Behavior(BehaviorKind.DELAY, DoubleBoundary.NODE, value, "", null,
                    Map.of(), "", "", "", after, List.of(), "");
        }

        /** @return a node-boundary timeout using the standard test timeout error code */
        public static Behavior timeout(Duration after) {
            return timeout(after, "TEST_TIMEOUT", "Injected deterministic timeout.");
        }

        /** @return a node-boundary timeout with an application-stable evidence error code */
        public static Behavior timeout(Duration after, String errorCode, String errorMessage) {
            return new Behavior(BehaviorKind.TIMEOUT, DoubleBoundary.NODE, null, "", null,
                    Map.of(), errorCode, "TIMEOUT", errorMessage, after, List.of(), "");
        }

        /** @return a node-boundary replay backed by one exact governed payload revision */
        public static Behavior replaying(String replayRef) {
            return new Behavior(BehaviorKind.REPLAY, DoubleBoundary.NODE, null, "", null,
                    Map.of(), "", "", "", null, List.of(), replayRef);
        }

        /** @return a fail-closed behavior that proves a site was not invoked */
        public static Behavior deny(String errorCode, String errorMessage) {
            return new Behavior(BehaviorKind.DENY, DoubleBoundary.NODE, null, "", null,
                    Map.of(), errorCode, "DENIED_INVOCATION", errorMessage, null, List.of(), "");
        }

        /** @return a real invocation whose input, output, and side-effect facts are observed */
        public static Behavior spy() {
            return new Behavior(BehaviorKind.SPY, DoubleBoundary.NODE, null, "", null,
                    Map.of(), "", "", "", null, List.of(), "");
        }
    }

    /**
     * One reserved declarative sequence step.
     *
     * @param kind step behavior name
     * @param after logical delay before the step
     * @param value step return value
     * @param errorCode step error code
     */
    public record BehaviorStep(String kind, Duration after, Object value, String errorCode) {
        /** Normalizes sequence-step identifiers. */
        public BehaviorStep {
            kind = trimmed(kind);
            errorCode = trimmed(errorCode);
        }
    }

    /**
     * Required and bounded rule-use policy.
     *
     * @param required whether zero uses fail the run
     * @param minUses minimum successful uses
     * @param maxUses maximum successful uses, or zero for unbounded
     * @param onExhausted behavior after maxUses
     * @param onUnmatched behavior when no rule matches an invocation
     */
    public record Consumption(
            boolean required,
            int minUses,
            int maxUses,
            ExhaustedAction onExhausted,
            UnmatchedAction onUnmatched
    ) {
        /** Creates a fail-closed consumption policy. */
        public Consumption {
            minUses = Math.max(0, minUses);
            maxUses = Math.max(0, maxUses);
            onExhausted = onExhausted == null ? ExhaustedAction.FAIL : onExhausted;
            onUnmatched = onUnmatched == null ? UnmatchedAction.FAIL : onUnmatched;
        }

        /** @return one required, fail-closed use */
        public static Consumption once() {
            return new Consumption(true, 1, 1, ExhaustedAction.FAIL, UnmatchedAction.FAIL);
        }

        /** @return an optional, single-use fail-closed policy */
        public static Consumption optionalOnce() {
            return new Consumption(false, 0, 1, ExhaustedAction.FAIL, UnmatchedAction.FAIL);
        }
    }

    /**
     * Schema validation policy.
     *
     * @param mode strict validation or explicit waiver
     * @param waiverReason mandatory audit explanation when waived
     */
    public record SchemaCheck(SchemaCheckMode mode, String waiverReason) {
        /** Normalizes schema-check policy. */
        public SchemaCheck {
            mode = mode == null ? SchemaCheckMode.STRICT : mode;
            waiverReason = waiverReason == null ? "" : waiverReason.trim();
        }

        /** @return strict schema validation */
        public static SchemaCheck strict() {
            return new SchemaCheck(SchemaCheckMode.STRICT, "");
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
