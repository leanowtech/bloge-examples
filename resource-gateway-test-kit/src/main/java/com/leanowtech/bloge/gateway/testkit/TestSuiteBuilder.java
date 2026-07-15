package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed builder for one immutable {@code bloge.testSuite.v1} registration request.
 *
 * <p>The builder accepts only exact stored fixture revisions and a discovered target fingerprint.
 * Its conservative defaults require every case to pass, every case to be certifiable, at least one
 * assertion per case, and every required fixture rule to be consumed.</p>
 */
public final class TestSuiteBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Supported governed case intents. */
    public enum CaseType {
        /** Expected successful business behavior. */
        GOLDEN,
        /** Expected error or rejection behavior. */
        NEGATIVE,
        /** Boundary-value behavior. */
        BOUNDARY,
        /** Previously observed behavior protected against regression. */
        REGRESSION
    }

    /** Fixture and suite data classification. */
    public enum Classification {
        /** Publicly distributable test data. */
        PUBLIC,
        /** Internal non-sensitive test data. */
        INTERNAL,
        /** Confidential test data with restricted readership. */
        CONFIDENTIAL,
        /** Highly restricted test data. */
        RESTRICTED
    }

    private final String targetKind;
    private final String targetId;
    private final String targetFingerprint;
    private final List<SuiteCase> cases = new ArrayList<>();
    private final LinkedHashSet<CaseType> requiredCaseTypes = new LinkedHashSet<>();
    private final LinkedHashSet<String> requiredInvocationSites = new LinkedHashSet<>();
    private final List<EdgeTransfer> requiredEdgeTransfers = new ArrayList<>();
    private String suiteId = "";
    private long revision = 1;
    private Classification classification = Classification.INTERNAL;
    private int minimumCases;
    private int minimumAssertionsPerCase = 1;
    private boolean requireAllFixtureRulesConsumed = true;
    private boolean requireAllCasesPassed = true;
    private Integer minimumCertifiableCases;
    private boolean requireTargetCertificationEligible = true;
    private JsonNode metadata = JSON.createObjectNode();

    private TestSuiteBuilder(String targetKind, String targetId, String targetFingerprint) {
        this.targetKind = required(targetKind, "targetKind", 32);
        this.targetId = required(targetId, "targetId", 512);
        this.targetFingerprint = fingerprint(targetFingerprint);
    }

    /**
     * Starts a governed graph-suite builder from server-discovered target identity.
     *
     * @param target graph target descriptor
     * @return mutable builder
     */
    public static TestSuiteBuilder graph(GraphTargetDescriptor target) {
        Objects.requireNonNull(target, "target");
        return new TestSuiteBuilder("GRAPH", target.graphId(), target.fingerprint());
    }

    /**
     * Starts a governed operator-suite builder from server-discovered target identity.
     *
     * @param target operator target descriptor
     * @return mutable builder
     */
    public static TestSuiteBuilder operator(OperatorTargetDescriptor target) {
        Objects.requireNonNull(target, "target");
        return new TestSuiteBuilder("OPERATOR", target.operatorRef(), target.fingerprint());
    }

    /** @param value stable suite id @return this builder */
    public TestSuiteBuilder id(String value) {
        suiteId = required(value, "suiteId", 255);
        return this;
    }

    /** @param value positive immutable revision @return this builder */
    public TestSuiteBuilder revision(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        revision = value;
        return this;
    }

    /** @param value suite data classification @return this builder */
    public TestSuiteBuilder classification(Classification value) {
        classification = Objects.requireNonNull(value, "classification");
        return this;
    }

    /**
     * Adds one exact input-to-fixture case.
     *
     * @param caseId suite-local stable case id
     * @param caseType governance intent
     * @param input graph context object or operator input value
     * @param fixture exact immutable fixture revision
     * @return this builder
     */
    public TestSuiteBuilder addCase(String caseId, CaseType caseType, Object input,
                                    FixtureBundleRevision fixture) {
        return addCase(caseId, caseType, input, fixture, List.of(), Map.of());
    }

    /**
     * Adds one exact case with bounded tags and provenance metadata.
     *
     * @param caseId suite-local stable case id
     * @param caseType governance intent
     * @param input graph context object or operator input value
     * @param fixture exact immutable fixture revision
     * @param tags query and ownership labels
     * @param caseMetadata bounded provenance metadata
     * @return this builder
     */
    public TestSuiteBuilder addCase(String caseId, CaseType caseType, Object input,
                                    FixtureBundleRevision fixture, List<String> tags,
                                    Map<String, ?> caseMetadata) {
        Objects.requireNonNull(fixture, "fixture");
        String id = required(caseId, "caseId", 255);
        if (cases.stream().anyMatch(existing -> existing.caseId().equals(id))) {
            throw new IllegalArgumentException("Duplicate suite case id: " + id);
        }
        if (cases.size() >= 100) {
            throw new IllegalArgumentException("A suite may contain at most 100 cases");
        }
        if (fixture.revision() < 1) {
            throw new IllegalArgumentException("fixture revision must be at least 1");
        }
        cases.add(new SuiteCase(id, Objects.requireNonNull(caseType, "caseType"), snapshot(input),
                required(fixture.fixtureBundleId(), "fixtureBundleId", 255), fixture.revision(),
                fingerprint(fixture.fingerprint()), normalizedTags(tags), snapshotMap(caseMetadata)));
        return this;
    }

    /** @param value explicit minimum case count @return this builder */
    public TestSuiteBuilder minimumCases(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("minimumCases must be between 1 and 100");
        }
        minimumCases = value;
        return this;
    }

    /** @param values case intents that must be observed @return this builder */
    public TestSuiteBuilder requireCaseTypes(CaseType... values) {
        requiredCaseTypes.clear();
        if (values != null) {
            requiredCaseTypes.addAll(Arrays.asList(values));
        }
        if (requiredCaseTypes.contains(null)) {
            throw new IllegalArgumentException("required case types cannot contain null");
        }
        return this;
    }

    /** @param invocationSiteId structure-addressed required site @return this builder */
    public TestSuiteBuilder requireInvocationSite(String invocationSiteId) {
        requiredInvocationSites.add(required(invocationSiteId, "invocationSiteId", 512));
        return this;
    }

    /** @param from source invocation site @param to destination invocation site @return this builder */
    public TestSuiteBuilder requireEdgeTransfer(String from, String to) {
        EdgeTransfer edge = new EdgeTransfer(required(from, "fromInvocationSiteId", 512),
                required(to, "toInvocationSiteId", 512));
        if (!requiredEdgeTransfers.contains(edge)) {
            requiredEdgeTransfers.add(edge);
        }
        return this;
    }

    /** @param value minimum evaluated assertions for every case @return this builder */
    public TestSuiteBuilder minimumAssertionsPerCase(int value) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException("minimumAssertionsPerCase must be between 0 and 1000");
        }
        minimumAssertionsPerCase = value;
        return this;
    }

    /** @param value whether every required fixture rule must be consumed @return this builder */
    public TestSuiteBuilder requireAllFixtureRulesConsumed(boolean value) {
        requireAllFixtureRulesConsumed = value;
        return this;
    }

    /** @param value whether every case must pass for promotion eligibility @return this builder */
    public TestSuiteBuilder requireAllCasesPassed(boolean value) {
        requireAllCasesPassed = value;
        return this;
    }

    /** @param value minimum cases that must emit certifiable evidence @return this builder */
    public TestSuiteBuilder minimumCertifiableCases(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("minimumCertifiableCases must be between 0 and 100");
        }
        minimumCertifiableCases = value;
        return this;
    }

    /** @param value whether target-level eligibility is mandatory @return this builder */
    public TestSuiteBuilder requireTargetCertificationEligible(boolean value) {
        requireTargetCertificationEligible = value;
        return this;
    }

    /** @param value bounded suite provenance metadata @return this builder */
    public TestSuiteBuilder metadata(Map<String, ?> value) {
        metadata = snapshotMap(value);
        return this;
    }

    /**
     * Builds the schema-complete registration request consumed by
     * {@link ResourceGatewayTestClient#registerSuite(String, com.fasterxml.jackson.databind.JsonNode)}.
     *
     * @return defensive JSON request object
     */
    public ObjectNode registrationRequest() {
        if (suiteId.isBlank()) {
            throw new IllegalStateException("suiteId is required");
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("At least one governed case is required");
        }
        int requiredCases = minimumCases == 0 ? cases.size() : minimumCases;
        if (requiredCases > cases.size()) {
            throw new IllegalStateException("minimumCases cannot exceed the registered case count");
        }
        int certifiableCases = minimumCertifiableCases == null ? cases.size() : minimumCertifiableCases;
        if (certifiableCases > cases.size()) {
            throw new IllegalStateException("minimumCertifiableCases cannot exceed the registered case count");
        }
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion", TestingProtocol.TEST_SUITE_REGISTRATION_REQUEST_V1);
        ObjectNode suite = request.putObject("testSuite");
        suite.put("schemaVersion", TestingProtocol.TEST_SUITE_V1);
        suite.put("suiteId", suiteId);
        suite.put("revision", revision);
        ObjectNode target = suite.putObject("target");
        target.put("kind", targetKind);
        target.put("id", targetId);
        target.put("fingerprint", targetFingerprint);
        suite.put("classification", classification.name());
        ArrayNode caseValues = suite.putArray("cases");
        cases.forEach(value -> writeCase(caseValues.addObject(), value));
        ObjectNode coverage = suite.putObject("coveragePolicy");
        coverage.put("minimumCases", requiredCases);
        ArrayNode caseTypes = coverage.putArray("requiredCaseTypes");
        LinkedHashSet<CaseType> effectiveCaseTypes = requiredCaseTypes.isEmpty()
                ? cases.stream().map(SuiteCase::caseType)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : requiredCaseTypes;
        effectiveCaseTypes.stream().map(Enum::name).sorted().forEach(caseTypes::add);
        ArrayNode sites = coverage.putArray("requiredInvocationSiteIds");
        requiredInvocationSites.stream().sorted().forEach(sites::add);
        ArrayNode edges = coverage.putArray("requiredEdgeTransfers");
        requiredEdgeTransfers.stream()
                .sorted(java.util.Comparator.comparing(EdgeTransfer::from).thenComparing(EdgeTransfer::to))
                .forEach(edge -> {
                    ObjectNode value = edges.addObject();
                    value.put("fromInvocationSiteId", edge.from());
                    value.put("toInvocationSiteId", edge.to());
                });
        coverage.put("minimumAssertionsPerCase", minimumAssertionsPerCase);
        coverage.put("requireAllFixtureRulesConsumed", requireAllFixtureRulesConsumed);
        ObjectNode promotion = suite.putObject("promotionPolicy");
        promotion.put("requireAllCasesPassed", requireAllCasesPassed);
        promotion.put("minimumCertifiableCases", certifiableCases);
        promotion.put("requireTargetCertificationEligible", requireTargetCertificationEligible);
        suite.set("metadata", metadata.deepCopy());
        return request;
    }

    private static void writeCase(ObjectNode output, SuiteCase value) {
        output.put("caseId", value.caseId());
        output.put("caseType", value.caseType().name());
        output.set("input", value.input().deepCopy());
        ObjectNode fixture = output.putObject("fixtureBundleRef");
        fixture.put("fixtureBundleId", value.fixtureBundleId());
        fixture.put("revision", value.fixtureRevision());
        fixture.put("fingerprint", value.fixtureFingerprint());
        ArrayNode tags = output.putArray("tags");
        value.tags().forEach(tags::add);
        output.set("metadata", value.metadata().deepCopy());
    }

    private static List<String> normalizedTags(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String value : values) {
            tags.add(required(value, "tag", 128));
        }
        if (tags.size() > 64) {
            throw new IllegalArgumentException("A suite case may contain at most 64 tags");
        }
        return tags.stream().sorted().toList();
    }

    private static JsonNode snapshotMap(Map<String, ?> values) {
        if (values == null) {
            return JSON.createObjectNode();
        }
        if (values.size() > 100) {
            throw new IllegalArgumentException("Suite metadata may contain at most 100 properties");
        }
        return snapshot(new LinkedHashMap<>(values));
    }

    private static JsonNode snapshot(Object value) {
        JsonNode snapshot = JSON.valueToTree(value);
        return snapshot == null ? JSON.nullNode() : snapshot.deepCopy();
    }

    private static String fingerprint(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A full lowercase SHA-256 fingerprint is required");
        }
        return normalized;
    }

    private static String required(String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximum
                || normalized.contains("\r") || normalized.contains("\n")) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maximum + " safe characters");
        }
        return normalized;
    }

    private record SuiteCase(String caseId, CaseType caseType, JsonNode input,
                             String fixtureBundleId, long fixtureRevision, String fixtureFingerprint,
                             List<String> tags, JsonNode metadata) {
    }

    private record EdgeTransfer(String from, String to) {
    }
}
