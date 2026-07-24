package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable payload-free business handling assertion evaluated over mirror run evidence.
 *
 * <p>The assertion never embeds a request, response, entity, or expected business value. Exact
 * value assertions remain in the governed FixtureBundle and TestSuite. This layer answers the
 * business handling questions needed by a rehearsal gate: what ran, what status or error was
 * observed, which state/effect facts exist, and whether retry, latency, resource, or governance
 * bounds were respected.</p>
 *
 * @param schemaVersion exact handling-assertion wire version
 * @param assertionId stable assertion identity inside its enterprise scope
 * @param revision positive immutable revision
 * @param fingerprint canonical fingerprint with this field blanked
 * @param scope exact enterprise namespace
 * @param observation evidence dimension being asserted
 * @param selector payload-free evidence selector
 * @param expectation typed expected evidence facts
 * @param severity whether mismatch blocks certification or remains advisory
 * @param governanceCode stable workbook/publish-gate code emitted on mismatch
 * @param provenance owner, approval, and lineage facts
 * @param lifecycle governed artifact lifecycle
 * @param createdAt immutable creation time
 */
public record CaseHandlingAssertion(
        String schemaVersion,
        String assertionId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        Observation observation,
        Selector selector,
        Expectation expectation,
        Severity severity,
        String governanceCode,
        ArtifactProvenance provenance,
        CapabilitySnapshot.Lifecycle lifecycle,
        Instant createdAt
) {
    /** Current immutable handling-assertion protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.caseHandlingAssertion.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern MACHINE_VALUE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,191}");

    /** Normalizes one assertion and validates dimension-specific selectors and expectations. */
    public CaseHandlingAssertion {
        schemaVersion = version(schemaVersion);
        assertionId = identifier(assertionId, "assertionId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "handling assertion revision must be positive");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        observation = Objects.requireNonNull(observation, "observation");
        selector = selector == null ? Selector.empty() : selector;
        expectation = Objects.requireNonNull(expectation, "expectation");
        severity = severity == null ? Severity.BLOCKER : severity;
        governanceCode = machineValue(governanceCode, "governanceCode");
        provenance = Objects.requireNonNull(provenance, "provenance");
        lifecycle = lifecycle == null ? CapabilitySnapshot.Lifecycle.DRAFT : lifecycle;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        validateShape(observation, selector, expectation);
    }

    /** Evidence dimensions exposed by a deterministic rehearsal. */
    public enum Observation {
        GRAPH_OUTPUT_VALUE,
        GRAPH_OUTPUT_SCHEMA,
        NODE_STATUS,
        EDGE_STATUS,
        CAPABILITY_OCCURRENCE,
        INVOCATION_INPUT,
        ERROR,
        FALLBACK,
        COMPENSATION,
        STATE_TRANSITION,
        FINAL_STATE_INVARIANT,
        SIDE_EFFECT_RECEIPT,
        GOVERNANCE_EXPECTATION,
        LATENCY_BUDGET,
        RETRY_BUDGET,
        RESOURCE_BUDGET
    }

    /** Governance consequence of an assertion mismatch. */
    public enum Severity {
        BLOCKER,
        WARNING
    }

    /**
     * Payload-free coordinate inside graph, invocation, capability, or output evidence.
     *
     * @param nodeId optional graph node id
     * @param edgeId optional graph edge id
     * @param invocationSiteId optional structure-addressed invocation site
     * @param capabilityRef optional exact capability
     * @param path optional JSON Pointer whose value is compared by fingerprint or schema identity
     */
    public record Selector(
            String nodeId,
            String edgeId,
            String invocationSiteId,
            MirrorArtifactRef capabilityRef,
            String path
    ) {
        /** Normalizes bounded selector coordinates. */
        public Selector {
            nodeId = optionalIdentifier(nodeId, "nodeId");
            edgeId = optionalIdentifier(edgeId, "edgeId");
            invocationSiteId = optionalIdentifier(invocationSiteId, "invocationSiteId");
            if (capabilityRef != null && !"CAPABILITY".equals(capabilityRef.kind())) {
                throw new IllegalArgumentException(
                        "capabilityRef must be an exact CAPABILITY ref");
            }
            path = optionalPointer(path);
        }

        /** @return an assertion selector over the whole run */
        public static Selector empty() {
            return new Selector("", "", "", null, "");
        }
    }

    /**
     * Typed expected facts without business payload values.
     *
     * @param statuses accepted machine statuses, such as SUCCESS or TIMEOUT
     * @param errorCode exact stable error code, or blank
     * @param schemaFingerprint exact expected output schema identity, or blank
     * @param valueFingerprint exact expected business value identity, or blank
     * @param minimumOccurrences inclusive lower occurrence bound, or null
     * @param maximumOccurrences inclusive upper occurrence bound, or null
     * @param maximumDurationMillis inclusive latency bound, or null
     * @param expectedBoolean exact boolean expectation, or null
     */
    public record Expectation(
            List<String> statuses,
            String errorCode,
            String schemaFingerprint,
            String valueFingerprint,
            Long minimumOccurrences,
            Long maximumOccurrences,
            Long maximumDurationMillis,
            Boolean expectedBoolean
    ) {
        /** Canonicalizes statuses and validates non-negative ordered bounds. */
        public Expectation {
            statuses = statuses == null ? List.of() : statuses.stream()
                    .map(value -> machineValue(value, "status"))
                    .distinct()
                    .sorted()
                    .toList();
            errorCode = optionalMachineValue(errorCode, "errorCode");
            schemaFingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                    schemaFingerprint, "schemaFingerprint");
            valueFingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                    valueFingerprint, "valueFingerprint");
            if (minimumOccurrences != null && minimumOccurrences < 0
                    || maximumOccurrences != null && maximumOccurrences < 0
                    || minimumOccurrences != null && maximumOccurrences != null
                    && minimumOccurrences > maximumOccurrences
                    || maximumDurationMillis != null && maximumDurationMillis < 0) {
                throw new IllegalArgumentException(
                        "handling assertion expectation bounds are invalid");
            }
            if (statuses.isEmpty() && errorCode.isBlank()
                    && schemaFingerprint.isBlank() && valueFingerprint.isBlank()
                    && minimumOccurrences == null && maximumOccurrences == null
                    && maximumDurationMillis == null && expectedBoolean == null) {
                throw new IllegalArgumentException(
                        "handling assertion requires at least one expected fact");
            }
        }
    }

    /** @return identical assertion material carrying a replacement canonical fingerprint */
    public CaseHandlingAssertion withFingerprint(String value) {
        return new CaseHandlingAssertion(
                schemaVersion, assertionId, revision, value, scope, observation,
                selector, expectation, severity, governanceCode, provenance,
                lifecycle, createdAt);
    }

    private static void validateShape(
            Observation observation, Selector selector, Expectation expectation) {
        switch (observation) {
            case GRAPH_OUTPUT_VALUE -> {
                require(!selector.path().isBlank(), "graph output value requires a path");
                require(!expectation.valueFingerprint().isBlank(),
                        "graph output value requires a value fingerprint");
            }
            case GRAPH_OUTPUT_SCHEMA -> {
                require(!selector.path().isBlank(), "graph output schema requires a path");
                require(!expectation.schemaFingerprint().isBlank(),
                        "graph output schema requires a schema fingerprint");
            }
            case NODE_STATUS -> {
                require(!selector.nodeId().isBlank(), "node status requires a nodeId");
                require(!expectation.statuses().isEmpty(), "node status requires statuses");
            }
            case EDGE_STATUS -> {
                require(!selector.edgeId().isBlank(), "edge status requires an edgeId");
                require(!expectation.statuses().isEmpty(), "edge status requires statuses");
            }
            case CAPABILITY_OCCURRENCE -> {
                require(selector.capabilityRef() != null,
                        "capability occurrence requires a capabilityRef");
                require(expectation.minimumOccurrences() != null
                                || expectation.maximumOccurrences() != null,
                        "capability occurrence requires an occurrence bound");
            }
            case INVOCATION_INPUT -> {
                require(!selector.invocationSiteId().isBlank(),
                        "invocation input requires an invocationSiteId");
                require(!expectation.valueFingerprint().isBlank(),
                        "invocation input requires a value fingerprint");
            }
            case ERROR -> require(!expectation.errorCode().isBlank(),
                    "error assertion requires an errorCode");
            case LATENCY_BUDGET -> require(expectation.maximumDurationMillis() != null,
                    "latency budget requires maximumDurationMillis");
            case RETRY_BUDGET, RESOURCE_BUDGET -> require(
                    expectation.maximumOccurrences() != null,
                    "retry and resource budgets require maximumOccurrences");
            case FALLBACK, COMPENSATION, STATE_TRANSITION, FINAL_STATE_INVARIANT,
                    SIDE_EFFECT_RECEIPT, GOVERNANCE_EXPECTATION ->
                    require(expectation.expectedBoolean() != null
                                    || !expectation.statuses().isEmpty(),
                            "handling assertion requires a boolean or status expectation");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported handling assertion schemaVersion: " + normalized);
        }
        return normalized;
    }

    private static String identifier(String value, String field) {
        String normalized = MirrorStateProtocolSupport.required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optionalPointer(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "";
        }
        return MirrorStateProtocolSupport.nonRootPointer(normalized, "path");
    }

    private static String machineValue(String value, String field) {
        String normalized = MirrorStateProtocolSupport.required(value, field)
                .toUpperCase(Locale.ROOT);
        if (!MACHINE_VALUE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optionalMachineValue(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? "" : machineValue(normalized, field);
    }
}
