package com.leanowtech.bloge.gateway.visual.scenario;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualAuthoringJsonValue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mutable authoring asset containing business-readable scenarios for one exact target contract.
 *
 * <p>A Scenario draft set is deliberately separate from {@code GraphDraft}. It has independent
 * ownership, classification, revision, retention, and publication lifecycles. Publishing compiles
 * it into immutable {@code FixtureBundle} and {@code TestSuite} revisions; this mutable record is
 * never itself accepted as certifiable runtime evidence.</p>
 *
 * @param schemaVersion scenario-draft-set protocol version
 * @param scenarioDraftSetId stable authoring asset id
 * @param revision optimistic-concurrency revision
 * @param scope complete enterprise scope
 * @param target exact graph or operator target
 * @param contractFingerprint exact Contract draft fingerprint
 * @param scenarios ordered authoring scenarios
 * @param metadata ownership, classification, and audit timestamps
 */
public record ScenarioDraftSet(
        String schemaVersion,
        String scenarioDraftSetId,
        long revision,
        EnterpriseScope scope,
        ContractDraft.Target target,
        String contractFingerprint,
        List<ScenarioDraft> scenarios,
        Metadata metadata
) {
    /** Current mutable Scenario authoring protocol version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioDraftSet.v1";

    /** Normalizes identifiers and freezes the scenario collection. */
    public ScenarioDraftSet {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        scenarioDraftSetId = trimmed(scenarioDraftSetId);
        revision = Math.max(0, revision);
        scope = scope == null ? EnterpriseScope.empty() : scope;
        target = target == null ? ContractDraft.Target.unknown() : target;
        contractFingerprint = trimmed(contractFingerprint);
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        metadata = metadata == null ? Metadata.empty() : metadata;
    }

    /**
     * Returns the server-owned persistence projection for one optimistic-concurrency revision.
     *
     * <p>Callers cannot use this helper to change enterprise scope or payload content. The
     * repository supplies the stable id, revision, creation time, update time, and accountable
     * owner after it has checked the current stored revision.</p>
     *
     * @param id stable Scenario draft-set id
     * @param storedRevision assigned mutable-asset revision
     * @param createdAt original creation time
     * @param updatedAt current persistence time
     * @param owner accountable owner retained or derived by the service
     * @return copied draft set with server-owned persistence identity
     */
    public ScenarioDraftSet withStorageIdentity(String id,
                                                long storedRevision,
                                                Instant createdAt,
                                                Instant updatedAt,
                                                String owner) {
        return new ScenarioDraftSet(schemaVersion, id, storedRevision, scope, target,
                contractFingerprint, scenarios, new Metadata(owner, metadata.classification(),
                createdAt, updatedAt, metadata.provenance()));
    }

    /** Supported governed test case intents. */
    public enum CaseType {
        GOLDEN,
        NEGATIVE,
        BOUNDARY,
        REGRESSION,
        PROPERTY
    }

    /** Source of a Scenario value. */
    public enum ValueProvenance {
        AUTHORED,
        GENERATED,
        IMPORTED,
        CAPTURED,
        MIGRATED
    }

    /** Business-facing dependency behaviors. */
    public enum BehaviorKind {
        REAL,
        RETURN,
        ERROR,
        DELAY,
        TIMEOUT,
        REPLAY,
        OBSERVE,
        MUST_NOT_CALL
    }

    /** Boundary at which a dependency double is applied. */
    public enum BehaviorBoundary {
        NODE,
        TRANSPORT
    }

    /** Supported assertion scopes. */
    public enum AssertionScope {
        OUTPUT_PATH,
        NODE_OUTPUT,
        NODE_STATUS,
        EDGE_TRANSFER,
        INVOCATION
    }

    /** Supported authoring assertion operators. */
    public enum AssertionOperator {
        EQUALS,
        MATCHES_SCHEMA,
        EXISTS,
        ABSENT,
        STATUS,
        USED,
        NOT_USED
    }

    /**
     * Complete organization boundary for one authoring asset.
     *
     * @param tenantId tenant boundary
     * @param organizationId organization boundary
     * @param projectId project boundary
     * @param environment execution environment
     * @param region data-residency region
     */
    public record EnterpriseScope(
            String tenantId,
            String organizationId,
            String projectId,
            String environment,
            String region
    ) {
        /** Normalizes scope fields without supplying cross-tenant defaults. */
        public EnterpriseScope {
            tenantId = trimmed(tenantId);
            organizationId = trimmed(organizationId);
            projectId = trimmed(projectId);
            environment = trimmed(environment);
            region = trimmed(region);
        }

        /** @return an intentionally incomplete scope for an unsaved local draft */
        public static EnterpriseScope empty() {
            return new EnterpriseScope("", "", "", "", "");
        }
    }

    /**
     * Mutable-asset ownership and classification metadata.
     *
     * @param owner accountable author or team
     * @param classification PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED
     * @param createdAt creation time
     * @param updatedAt last authoring update time
     * @param provenance bounded source annotations
     */
    public record Metadata(
            String owner,
            String classification,
            Instant createdAt,
            Instant updatedAt,
            Map<String, Object> provenance
    ) {
        /** Normalizes labels and freezes provenance. */
        public Metadata {
            owner = trimmed(owner);
            classification = defaulted(classification, "INTERNAL").toUpperCase(Locale.ROOT);
            provenance = VisualAuthoringJsonValue.freezeMap(provenance);
        }

        /** @return empty metadata for an unsaved local authoring asset */
        public static Metadata empty() {
            return new Metadata("", "INTERNAL", null, null, Map.of());
        }
    }

    /**
     * One independently runnable Given/Dependencies/Then scenario.
     *
     * @param scenarioId stable id within the draft set
     * @param name business-readable name
     * @param description authoring intent
     * @param caseType governance test intent
     * @param tags bounded labels
     * @param given graph or operator input
     * @param dependencies controlled dependency behaviors
     * @param then executable expected-result assertions
     */
    public record ScenarioDraft(
            String scenarioId,
            String name,
            String description,
            CaseType caseType,
            List<String> tags,
            Given given,
            List<DependencyBehaviorDraft> dependencies,
            Then then
    ) {
        /** Normalizes identifiers and freezes collections. */
        public ScenarioDraft {
            scenarioId = trimmed(scenarioId);
            name = trimmed(name);
            description = trimmed(description);
            caseType = caseType == null ? CaseType.GOLDEN : caseType;
            tags = tags == null ? List.of() : tags.stream().map(ScenarioDraftSet::trimmed).distinct().sorted().toList();
            given = given == null ? Given.empty() : given;
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            then = then == null ? Then.empty() : then;
        }
    }

    /**
     * Concrete input supplied to one Scenario.
     *
     * @param input graph context or operator input
     * @param provenance value origin
     */
    public record Given(Object input, ValueProvenance provenance) {
        /** Deeply freezes payload-bearing input. */
        public Given {
            input = VisualAuthoringJsonValue.freeze(input);
            provenance = provenance == null ? ValueProvenance.AUTHORED : provenance;
        }

        /** @return empty authored graph input */
        public static Given empty() {
            return new Given(Map.of(), ValueProvenance.AUTHORED);
        }
    }

    /**
     * Author-facing dependency behavior compiled into NodeFixture or FixtureRule semantics.
     *
     * @param dependencyId stable scenario-local behavior id
     * @param selector dependency invocation selector
     * @param behavior behavior payload
     * @param consumption required and bounded use expectations
     * @param schemaCheck strict or explicitly waived schema checking
     * @param origin authoring or migration provenance
     */
    public record DependencyBehaviorDraft(
            String dependencyId,
            DependencySelector selector,
            DependencyBehavior behavior,
            Consumption consumption,
            SchemaCheck schemaCheck,
            String origin
    ) {
        /** Applies fail-closed defaults. */
        public DependencyBehaviorDraft {
            dependencyId = trimmed(dependencyId);
            selector = selector == null ? DependencySelector.any() : selector;
            behavior = behavior == null ? DependencyBehavior.real() : behavior;
            consumption = consumption == null ? Consumption.once() : consumption;
            schemaCheck = schemaCheck == null ? SchemaCheck.strict() : schemaCheck;
            origin = defaulted(origin, "AUTHORED").toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Structural and runtime selector for one dependency invocation.
     *
     * @param graphPath exact graph path
     * @param nodeId exact node id
     * @param operatorRef operator-wide selector
     * @param resourceRef resource selector
     * @param functionRef built-in function selector
     * @param attempts one-based delegate attempts
     * @param occurrences one-based site occurrences
     * @param correlationKey business correlation key
     * @param pathEquals input JSON-Pointer equality conditions
     */
    public record DependencySelector(
            String graphPath,
            String nodeId,
            String operatorRef,
            String resourceRef,
            String functionRef,
            List<Integer> attempts,
            List<Integer> occurrences,
            String correlationKey,
            Map<String, Object> pathEquals
    ) {
        /** Freezes selector coordinates and match values. */
        public DependencySelector {
            graphPath = trimmed(graphPath);
            nodeId = trimmed(nodeId);
            operatorRef = trimmed(operatorRef);
            resourceRef = trimmed(resourceRef);
            functionRef = trimmed(functionRef);
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
            occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
            correlationKey = trimmed(correlationKey);
            pathEquals = VisualAuthoringJsonValue.freezeMap(pathEquals);
        }

        /** @return selector with no declared coordinate */
        public static DependencySelector any() {
            return new DependencySelector("", "", "", "", "", List.of(), List.of(), "", Map.of());
        }

        /** @param nodeId exact node id @return node selector */
        public static DependencySelector node(String nodeId) {
            return new DependencySelector("/root", nodeId, "", "", "", List.of(), List.of(), "", Map.of());
        }
    }

    /**
     * Behavior values shared by the basic and governed compilers.
     *
     * @param kind business-facing behavior kind
     * @param boundary node or transport boundary
     * @param output fixed logical output for RETURN or DELAY
     * @param expectedInput optional input assertion used by transient NodeFixture
     * @param rawBody protocol body for transport-level RETURN
     * @param statusCode protocol response status
     * @param headers protocol response headers
     * @param errorCode stable injected error code
     * @param errorType normalized error type
     * @param errorMessage bounded error description
     * @param after deterministic delay or timeout
     * @param replayRef exact governed replay reference
     */
    public record DependencyBehavior(
            BehaviorKind kind,
            BehaviorBoundary boundary,
            Object output,
            Object expectedInput,
            String rawBody,
            Integer statusCode,
            Map<String, String> headers,
            String errorCode,
            String errorType,
            String errorMessage,
            Duration after,
            String replayRef
    ) {
        /** Freezes payload values and normalizes protocol metadata. */
        public DependencyBehavior {
            kind = kind == null ? BehaviorKind.REAL : kind;
            boundary = boundary == null ? BehaviorBoundary.NODE : boundary;
            output = VisualAuthoringJsonValue.freeze(output);
            expectedInput = VisualAuthoringJsonValue.freeze(expectedInput);
            rawBody = rawBody == null ? "" : rawBody;
            headers = headers == null ? Map.of() : immutableStringMap(headers);
            errorCode = trimmed(errorCode);
            errorType = trimmed(errorType);
            errorMessage = trimmed(errorMessage);
            replayRef = trimmed(replayRef);
        }

        /** @return explicit real execution */
        public static DependencyBehavior real() {
            return new DependencyBehavior(BehaviorKind.REAL, BehaviorBoundary.NODE, null, null,
                    "", null, Map.of(), "", "", "", null, "");
        }

        /** @param output fixed output @return node-boundary RETURN behavior */
        public static DependencyBehavior returning(Object output) {
            return new DependencyBehavior(BehaviorKind.RETURN, BehaviorBoundary.NODE, output, null,
                    "", null, Map.of(), "", "", "", null, "");
        }
    }

    /**
     * Expected use policy for one dependency behavior.
     *
     * @param required whether zero uses fail
     * @param minUses minimum successful uses
     * @param maxUses maximum uses, zero for unbounded
     * @param onExhausted FAIL or FALLBACK_TO_REAL
     * @param onUnmatched FAIL, WARN, or ALLOW_REAL
     */
    public record Consumption(
            boolean required,
            int minUses,
            int maxUses,
            String onExhausted,
            String onUnmatched
    ) {
        /** Normalizes bounds and keeps fail-closed defaults. */
        public Consumption {
            minUses = Math.max(0, minUses);
            maxUses = Math.max(0, maxUses);
            onExhausted = defaulted(onExhausted, "FAIL").toUpperCase(Locale.ROOT);
            onUnmatched = defaulted(onUnmatched, "FAIL").toUpperCase(Locale.ROOT);
        }

        /** @return exactly one required, fail-closed use */
        public static Consumption once() {
            return new Consumption(true, 1, 1, "FAIL", "FAIL");
        }
    }

    /**
     * Schema-check policy for one controlled dependency.
     *
     * @param mode STRICT or WAIVED
     * @param waiverReason mandatory governance reason when waived
     */
    public record SchemaCheck(String mode, String waiverReason) {
        /** Normalizes the check mode. */
        public SchemaCheck {
            mode = defaulted(mode, "STRICT").toUpperCase(Locale.ROOT);
            waiverReason = trimmed(waiverReason);
        }

        /** @return strict schema checking */
        public static SchemaCheck strict() {
            return new SchemaCheck("STRICT", "");
        }
    }

    /**
     * Executable Scenario expected results.
     *
     * @param assertions ordered assertions
     */
    public record Then(List<AssertionDraft> assertions) {
        /** Freezes assertions. */
        public Then {
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
        }

        /** @return no expected-result assertions */
        public static Then empty() {
            return new Then(List.of());
        }
    }

    /**
     * One author-facing assertion compiled into the testing control-plane assertion protocol.
     *
     * @param assertionId stable scenario-local id
     * @param scope assertion scope
     * @param nodeId optional node id
     * @param fromNodeId optional edge source
     * @param toNodeId optional edge target
     * @param path optional JSON Pointer
     * @param operator assertion operator
     * @param expected expected value
     * @param numericTolerance optional absolute tolerance
     */
    public record AssertionDraft(
            String assertionId,
            AssertionScope scope,
            String nodeId,
            String fromNodeId,
            String toNodeId,
            String path,
            AssertionOperator operator,
            Object expected,
            Double numericTolerance
    ) {
        /** Normalizes identifiers and freezes expected payload values. */
        public AssertionDraft {
            assertionId = trimmed(assertionId);
            scope = scope == null ? AssertionScope.OUTPUT_PATH : scope;
            nodeId = trimmed(nodeId);
            fromNodeId = trimmed(fromNodeId);
            toNodeId = trimmed(toNodeId);
            path = trimmed(path);
            operator = operator == null ? AssertionOperator.EQUALS : operator;
            expected = VisualAuthoringJsonValue.freeze(expected);
        }
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(trimmed(key), value == null ? "" : value));
        return Collections.unmodifiableMap(copy);
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
