package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fluent builder for schema-complete fixture bundles and controlled graph execution requests.
 * Defaults are strict and fail closed: rules are required exactly once, unmatched/exhausted sites
 * fail, and schema checks are enabled.
 */
public final class FixtureBundleBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int MAX_COLLECTION_ITEMS = 1_000;
    private static final int MAX_METADATA_ENTRIES = 100;
    private static final int MAX_PROTOCOL_BODY_CHARACTERS = 1_048_576;
    private static final int MAX_ERROR_MESSAGE_CHARACTERS = 4_096;

    /** Fixture payload governance classification. */
    public enum Classification {
        /** Public test data. */
        PUBLIC,
        /** Internal test data. */
        INTERNAL,
        /** Confidential test data requiring restricted access. */
        CONFIDENTIAL,
        /** Highest-sensitivity governed test data. */
        RESTRICTED
    }

    private final String graphId;
    private final String targetFingerprint;
    private final Map<String, JsonNode> metadata = new LinkedHashMap<>();
    private final ArrayNode rules = JSON.createArrayNode();
    private final ArrayNode assertions = JSON.createArrayNode();
    private final Set<String> ruleIds = new LinkedHashSet<>();
    private String fixtureBundleId = "";
    private long revision = 1;
    private Classification classification = Classification.INTERNAL;
    private Instant logicalClock;
    private Long randomSeed;

    private FixtureBundleBuilder(String graphId, String targetFingerprint) {
        this.graphId = required(graphId, "graphId", 255);
        this.targetFingerprint = fingerprint(targetFingerprint, "targetFingerprint");
    }

    /**
     * Starts a fixture bundle for one frozen graph target.
     * @param graphId registered graph id
     * @param targetFingerprint composite fingerprint returned by target discovery
     * @return new strict fixture builder
     */
    public static FixtureBundleBuilder graph(String graphId, String targetFingerprint) {
        return new FixtureBundleBuilder(graphId, targetFingerprint);
    }

    /**
     * Sets the stable fixture bundle id.
     * @param value fixture id
     * @return this builder
     */
    public FixtureBundleBuilder id(String value) {
        fixtureBundleId = required(value, "fixtureBundleId", 255);
        return this;
    }

    /**
     * Sets the immutable registry revision.
     * @param value positive revision
     * @return this builder
     */
    public FixtureBundleBuilder revision(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        revision = value;
        return this;
    }

    /**
     * Sets the fixture payload classification.
     * @param value classification, defaulting to INTERNAL when null
     * @return this builder
     */
    public FixtureBundleBuilder classification(Classification value) {
        classification = value == null ? Classification.INTERNAL : value;
        return this;
    }

    /**
     * Sets the deterministic logical-clock origin reserved by protocol v1.
     * @param value logical-clock origin, or null
     * @return this builder
     */
    public FixtureBundleBuilder logicalClock(Instant value) {
        logicalClock = value;
        return this;
    }

    /**
     * Sets the deterministic random seed reserved by protocol v1.
     * @param value random seed
     * @return this builder
     */
    public FixtureBundleBuilder randomSeed(long value) {
        randomSeed = value;
        return this;
    }

    /**
     * Adds bounded fixture ownership or provenance metadata.
     * @param name metadata key
     * @param value JSON-serializable value
     * @return this builder
     */
    public FixtureBundleBuilder metadata(String name, Object value) {
        String key = required(name, "metadata name", 128);
        if (!metadata.containsKey(key) && metadata.size() >= MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("Fixture metadata may contain at most 100 entries");
        }
        metadata.put(key, JSON.valueToTree(value));
        return this;
    }

    /**
     * Starts one strict fail-closed rule. Call {@link RuleBuilder#add()} to finish it.
     * @param ruleId stable rule id within the fixture
     * @return unfinished rule builder
     */
    public RuleBuilder rule(String ruleId) {
        return new RuleBuilder(this, required(ruleId, "ruleId", 255));
    }

    /**
     * Adds an output-path assertion.
     * @param path output JSON Pointer
     * @param operator comparison operator
     * @param expected expected JSON value
     * @return this builder
     */
    public FixtureBundleBuilder assertOutput(String path, String operator, Object expected) {
        return assertion("OUTPUT_PATH", "", path, operator, expected, null);
    }

    /**
     * Adds an output-path assertion with absolute numeric tolerance.
     * @param path output JSON Pointer
     * @param operator numeric comparison operator
     * @param expected expected numeric value
     * @param tolerance non-negative absolute tolerance
     * @return this builder
     */
    public FixtureBundleBuilder assertNumericOutput(String path, String operator, Number expected,
                                                    double tolerance) {
        if (tolerance < 0 || !Double.isFinite(tolerance)) {
            throw new IllegalArgumentException("numeric tolerance must be finite and non-negative");
        }
        return assertion("OUTPUT_PATH", "", path, operator, expected, tolerance);
    }

    /**
     * Adds a node-status assertion.
     * @param nodeId graph node id
     * @param expectedStatus expected normalized node status
     * @return this builder
     */
    public FixtureBundleBuilder assertNodeStatus(String nodeId, String expectedStatus) {
        return assertion("NODE_STATUS", required(nodeId, "nodeId", 255), "", "EQUALS",
                required(expectedStatus, "expectedStatus", 96), null);
    }

    /**
     * Builds a defensive JSON fixture bundle containing every v1 wire property.
     * @return schema-complete fixture bundle
     */
    public ObjectNode buildBundle() {
        requireFixtureId();
        ObjectNode bundle = JSON.createObjectNode();
        bundle.put("schemaVersion", TestingProtocol.FIXTURE_BUNDLE_V1);
        bundle.put("fixtureBundleId", fixtureBundleId);
        bundle.put("revision", revision);
        bundle.put("targetFingerprint", targetFingerprint);
        bundle.put("classification", classification.name());
        if (logicalClock == null) {
            bundle.putNull("logicalClock");
        } else {
            bundle.put("logicalClock", logicalClock.toString());
        }
        if (randomSeed == null) {
            bundle.putNull("randomSeed");
        } else {
            bundle.put("randomSeed", randomSeed);
        }
        bundle.set("rules", rules.deepCopy());
        bundle.set("assertions", assertions.deepCopy());
        ObjectNode metadataNode = bundle.putObject("metadata");
        metadata.forEach((name, value) -> metadataNode.set(name, value.deepCopy()));
        return bundle;
    }

    /**
     * Builds a fixture-registry registration request for this target and revision.
     * @return schema-complete registration request
     */
    public ObjectNode registrationRequest() {
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion", TestingProtocol.FIXTURE_REGISTRATION_REQUEST_V1);
        request.set("target", target());
        request.set("fixtureBundle", buildBundle());
        return request;
    }

    /**
     * Builds an exploratory execution request carrying this bundle inline.
     * @param context business graph context
     * @param verbosity requested response projection
     * @param executionMetadata suite/case provenance
     * @return schema-complete inline execution request
     */
    public ObjectNode inlineExecution(Map<String, ?> context,
                                      ResourceGatewayTestClient.Verbosity verbosity,
                                      Map<String, ?> executionMetadata) {
        ObjectNode request = executionBase(context, verbosity, executionMetadata);
        request.set("fixtureBundle", buildBundle());
        request.putNull("fixtureBundleRef");
        return request;
    }

    /**
     * Builds a governed execution request referencing the registered immutable revision.
     * @param fixtureFingerprint fingerprint returned by fixture registration
     * @param context business graph context
     * @param verbosity requested response projection
     * @param executionMetadata suite/case provenance
     * @return schema-complete stored-fixture execution request
     */
    public ObjectNode storedExecution(String fixtureFingerprint, Map<String, ?> context,
                                      ResourceGatewayTestClient.Verbosity verbosity,
                                      Map<String, ?> executionMetadata) {
        requireFixtureId();
        ObjectNode request = executionBase(context, verbosity, executionMetadata);
        request.putNull("fixtureBundle");
        ObjectNode reference = request.putObject("fixtureBundleRef");
        reference.put("fixtureBundleId", fixtureBundleId);
        reference.put("revision", revision);
        reference.put("fingerprint", fingerprint(fixtureFingerprint, "fixtureFingerprint"));
        return request;
    }

    private FixtureBundleBuilder assertion(String scope, String nodeId, String path, String operator,
                                           Object expected, Double tolerance) {
        if (assertions.size() >= MAX_COLLECTION_ITEMS) {
            throw new IllegalArgumentException("A fixture may contain at most 1000 assertions");
        }
        ObjectNode assertion = assertions.addObject();
        assertion.put("scope", required(scope, "assertion scope", 96));
        assertion.put("nodeId", normalized(nodeId));
        assertion.put("path", optional(path, "assertion path", 1024));
        assertion.put("operator", required(operator, "assertion operator", 96));
        assertion.set("expected", JSON.valueToTree(expected));
        if (tolerance == null) {
            assertion.putNull("numericTolerance");
        } else {
            assertion.put("numericTolerance", tolerance);
        }
        return this;
    }

    private ObjectNode executionBase(Map<String, ?> context,
                                     ResourceGatewayTestClient.Verbosity verbosity,
                                     Map<String, ?> executionMetadata) {
        Map<String, ?> metadataValues = executionMetadata == null ? Map.of() : executionMetadata;
        if (metadataValues.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("Execution metadata may contain at most 100 entries");
        }
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion", TestingProtocol.TEST_EXECUTION_REQUEST_V1);
        request.set("target", target());
        request.put("executionPurpose", "GRAPH_CONTRACT_TEST");
        request.set("context", JSON.valueToTree(context == null ? Map.of() : context));
        request.put("verbosity", (verbosity == null
                ? ResourceGatewayTestClient.Verbosity.STANDARD : verbosity).name());
        request.set("metadata", JSON.valueToTree(metadataValues));
        return request;
    }

    private ObjectNode target() {
        ObjectNode target = JSON.createObjectNode();
        target.put("kind", "GRAPH");
        target.put("id", graphId);
        target.put("fingerprint", targetFingerprint);
        return target;
    }

    private void addRule(String ruleId, ObjectNode rule) {
        if (ruleIds.contains(ruleId)) {
            throw new IllegalArgumentException("Duplicate fixture ruleId: " + ruleId);
        }
        if (rules.size() >= MAX_COLLECTION_ITEMS) {
            throw new IllegalArgumentException("A fixture may contain at most 1000 rules");
        }
        ruleIds.add(ruleId);
        rules.add(rule);
    }

    private void requireFixtureId() {
        if (fixtureBundleId.isBlank()) {
            throw new IllegalStateException("fixtureBundleId must be set before building the fixture");
        }
    }

    private static String fingerprint(String value, String field) {
        String normalized = normalized(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical sha256 fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field, int maximum) {
        String normalized = normalized(value);
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maximum + " characters");
        }
        return normalized;
    }

    private static String optional(String value, String field, int maximum) {
        String normalized = normalized(value);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " may contain at most " + maximum + " characters");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /** Fluent builder for one invocation selector, behavior, and consumption contract. */
    public static final class RuleBuilder {
        private final FixtureBundleBuilder parent;
        private final String ruleId;
        private final ObjectNode selector = JSON.createObjectNode();
        private final ObjectNode match;
        private final ObjectNode behavior = JSON.createObjectNode();
        private final ObjectNode consumption = JSON.createObjectNode();
        private final ObjectNode schemaCheck = JSON.createObjectNode();
        private boolean selectorConfigured;
        private boolean behaviorConfigured;

        private RuleBuilder(FixtureBundleBuilder parent, String ruleId) {
            this.parent = parent;
            this.ruleId = ruleId;
            selector.put("graphPath", "");
            selector.put("nodeId", "");
            selector.put("operatorRef", "");
            selector.put("resourceRef", "");
            selector.put("functionRef", "");
            selector.putArray("capabilities");
            selector.putArray("tags");
            selector.put("invocationKind", "PRIMARY");
            selector.putArray("attempts");
            selector.putArray("occurrences");
            selector.put("correlationKey", "");
            match = selector.putObject("match");
            match.putNull("canonicalInput");
            match.putObject("pathEquals");
            match.putArray("pathsExist");
            match.putArray("pathsAbsent");
            match.putObject("schema");
            match.put("correlationKey", "");
            match.putObject("boundedRegex");
            initializeBehavior("REAL", "NODE");
            consumption.put("required", true);
            consumption.put("minUses", 1);
            consumption.put("maxUses", 1);
            consumption.put("onExhausted", "FAIL");
            consumption.put("onUnmatched", "FAIL");
            schemaCheck.put("mode", "STRICT");
            schemaCheck.put("waiverReason", "");
        }

        /**
         * Selects one root-graph node.
         * @param nodeId exact node id
         * @return this rule builder
         */
        public RuleBuilder node(String nodeId) {
            singleSelector();
            selector.put("graphPath", "/root");
            selector.put("nodeId", required(nodeId, "nodeId", 255));
            selector.put("invocationKind", "PRIMARY");
            selectorConfigured = true;
            return this;
        }

        /**
         * Selects all primary invocation sites for an operator.
         * @param operatorRef operator reference
         * @return this rule builder
         */
        public RuleBuilder operator(String operatorRef) {
            singleSelector();
            selector.put("operatorRef", required(operatorRef, "operatorRef", 512));
            selector.put("invocationKind", "PRIMARY");
            selectorConfigured = true;
            return this;
        }

        /**
         * Selects a descriptor-backed resource invocation.
         * @param resourceRef resource descriptor id
         * @return this rule builder
         */
        public RuleBuilder resource(String resourceRef) {
            singleSelector();
            selector.put("resourceRef", required(resourceRef, "resourceRef", 512));
            selector.put("invocationKind", "RESOURCE");
            selectorConfigured = true;
            return this;
        }

        /**
         * Selects a built-in function invocation.
         * @param functionRef built-in function reference
         * @return this rule builder
         */
        public RuleBuilder function(String functionRef) {
            singleSelector();
            selector.put("functionRef", required(functionRef, "functionRef", 512));
            selector.put("invocationKind", "FUNCTION");
            selectorConfigured = true;
            return this;
        }

        /**
         * Adds an exact JSON Pointer input match.
         * @param jsonPointer invocation-input JSON Pointer
         * @param expected expected JSON value
         * @return this rule builder
         */
        public RuleBuilder matchPath(String jsonPointer, Object expected) {
            match.withObject("pathEquals").set(required(jsonPointer, "match path", 1024),
                    JSON.valueToTree(expected));
            return this;
        }

        /**
         * Matches the entire canonical invocation input.
         * @param expected expected canonical input
         * @return this rule builder
         */
        public RuleBuilder matchCanonicalInput(Object expected) {
            match.set("canonicalInput", JSON.valueToTree(expected));
            return this;
        }

        /**
         * Returns a fixed node-level value.
         * @param value replacement output
         * @return this rule builder
         */
        public RuleBuilder returnValue(Object value) {
            initializeBehavior("RETURN", "NODE");
            behavior.set("value", JSON.valueToTree(value));
            behaviorConfigured = true;
            return this;
        }

        /**
         * Exercises real resource response handling using a transport-level protocol response.
         * @param rawBody raw upstream response body
         * @param statusCode upstream HTTP status
         * @param headers upstream response headers
         * @return this rule builder
         */
        public RuleBuilder protocolResponse(String rawBody, int statusCode, Map<String, String> headers) {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode must be between 100 and 599");
            }
            String body = rawBody == null ? "" : rawBody;
            if (body.length() > MAX_PROTOCOL_BODY_CHARACTERS) {
                throw new IllegalArgumentException("rawBody may contain at most 1048576 characters");
            }
            if (headers != null && headers.size() > MAX_METADATA_ENTRIES) {
                throw new IllegalArgumentException("Protocol response headers may contain at most 100 entries");
            }
            initializeBehavior("RETURN", "TRANSPORT");
            behavior.put("rawBody", body);
            behavior.put("statusCode", statusCode);
            ObjectNode headerNode = behavior.withObject("headers");
            if (headers != null) {
                headers.forEach((name, value) -> headerNode.put(required(name, "header name", 128),
                        value == null ? "" : value));
            }
            behaviorConfigured = true;
            return this;
        }

        /**
         * Injects a normalized node failure.
         * @param errorCode stable error code
         * @param errorType normalized error class
         * @param errorMessage bounded diagnostic
         * @return this rule builder
         */
        public RuleBuilder throwing(String errorCode, String errorType, String errorMessage) {
            initializeBehavior("THROW", "NODE");
            behavior.put("errorCode", required(errorCode, "errorCode", 160));
            behavior.put("errorType", required(errorType, "errorType", 160));
            behavior.put("errorMessage", optional(errorMessage, "errorMessage", MAX_ERROR_MESSAGE_CHARACTERS));
            behaviorConfigured = true;
            return this;
        }

        /**
         * Fails immediately if the selected site is invoked.
         * @param errorCode stable denial code
         * @param errorMessage bounded denial diagnostic
         * @return this rule builder
         */
        public RuleBuilder deny(String errorCode, String errorMessage) {
            initializeBehavior("DENY", "NODE");
            behavior.put("errorCode", required(errorCode, "errorCode", 160));
            behavior.put("errorType", "DENIED_INVOCATION");
            behavior.put("errorMessage", optional(errorMessage, "errorMessage", MAX_ERROR_MESSAGE_CHARACTERS));
            behaviorConfigured = true;
            return this;
        }

        /**
         * Executes the selected site for real while collecting invocation evidence.
         * @return this rule builder
         */
        public RuleBuilder spy() {
            initializeBehavior("SPY", "NODE");
            behaviorConfigured = true;
            return this;
        }

        /**
         * Sets a required bounded consumption range with fail-closed exhaustion.
         * @param minimum minimum uses
         * @param maximum maximum uses, or zero for unbounded
         * @return this rule builder
         */
        public RuleBuilder requiredUses(int minimum, int maximum) {
            return uses(true, minimum, maximum);
        }

        /**
         * Sets an optional bounded consumption range with fail-closed exhaustion.
         * @param minimum minimum uses
         * @param maximum maximum uses, or zero for unbounded
         * @return this rule builder
         */
        public RuleBuilder optionalUses(int minimum, int maximum) {
            return uses(false, minimum, maximum);
        }

        /**
         * Explicitly waives schema checks; resulting evidence cannot be certifiable.
         * @param reason audit explanation for the waiver
         * @return this rule builder
         */
        public RuleBuilder waiveSchema(String reason) {
            schemaCheck.put("mode", "WAIVED");
            schemaCheck.put("waiverReason", required(reason, "waiverReason", 4096));
            return this;
        }

        /**
         * Finishes this rule and returns the containing fixture builder.
         * @return containing fixture builder
         */
        public FixtureBundleBuilder add() {
            if (!selectorConfigured) {
                throw new IllegalStateException("Rule " + ruleId + " requires an invocation selector");
            }
            if (!behaviorConfigured) {
                throw new IllegalStateException("Rule " + ruleId + " requires an explicit behavior");
            }
            ObjectNode rule = JSON.createObjectNode();
            rule.put("schemaVersion", TestingProtocol.FIXTURE_RULE_V1);
            rule.put("ruleId", ruleId);
            rule.set("selector", selector.deepCopy());
            rule.set("behavior", behavior.deepCopy());
            rule.set("consumption", consumption.deepCopy());
            rule.set("schemaCheck", schemaCheck.deepCopy());
            parent.addRule(ruleId, rule);
            return parent;
        }

        private RuleBuilder uses(boolean required, int minimum, int maximum) {
            if (minimum < 0 || maximum < 0 || (maximum > 0 && minimum > maximum)) {
                throw new IllegalArgumentException("Fixture uses require 0 <= minimum <= maximum");
            }
            consumption.put("required", required);
            consumption.put("minUses", minimum);
            consumption.put("maxUses", maximum);
            return this;
        }

        private void singleSelector() {
            if (selectorConfigured) {
                throw new IllegalStateException("A v1 test-kit rule may declare only one selector identity");
            }
        }

        private void initializeBehavior(String kind, String boundary) {
            behavior.removeAll();
            behavior.put("kind", kind);
            behavior.put("boundary", boundary);
            behavior.putNull("value");
            behavior.put("rawBody", "");
            behavior.putNull("statusCode");
            behavior.putObject("headers");
            behavior.put("errorCode", "");
            behavior.put("errorType", "");
            behavior.put("errorMessage", "");
            behavior.putNull("after");
            behavior.putArray("sequence");
            behavior.put("replayRef", "");
        }
    }
}
