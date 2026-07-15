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
 * Fail-closed builder for an immutable v1 or semantic v2 suite registration request.
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
    private final List<SemanticRequirement> semanticRequirements = new ArrayList<>();
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

    /**
     * Sets the stable suite identifier.
     *
     * @param value stable suite id
     * @return this builder
     */
    public TestSuiteBuilder id(String value) {
        suiteId = required(value, "suiteId", 255);
        return this;
    }

    /**
     * Sets the positive immutable revision.
     *
     * @param value positive immutable revision
     * @return this builder
     */
    public TestSuiteBuilder revision(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        revision = value;
        return this;
    }

    /**
     * Sets the suite data classification.
     *
     * @param value suite data classification
     * @return this builder
     */
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
        JsonNode inputSnapshot = snapshot(input);
        if ("GRAPH".equals(targetKind) && !inputSnapshot.isObject()) {
            throw new IllegalArgumentException("Graph suite case input must be a JSON object");
        }
        cases.add(new SuiteCase(id, Objects.requireNonNull(caseType, "caseType"), inputSnapshot,
                required(fixture.fixtureBundleId(), "fixtureBundleId", 255), fixture.revision(),
                fingerprint(fixture.fingerprint()), normalizedTags(tags), snapshotMap(caseMetadata)));
        return this;
    }

    /**
     * Sets the minimum number of cases required for coverage.
     *
     * @param value explicit minimum case count
     * @return this builder
     */
    public TestSuiteBuilder minimumCases(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("minimumCases must be between 1 and 100");
        }
        minimumCases = value;
        return this;
    }

    /**
     * Replaces the case intents that must be represented and observed.
     *
     * @param values case intents that must be observed
     * @return this builder
     */
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

    /**
     * Adds one structure-addressed invocation-site coverage requirement.
     *
     * @param invocationSiteId structure-addressed required site
     * @return this builder
     */
    public TestSuiteBuilder requireInvocationSite(String invocationSiteId) {
        requiredInvocationSites.add(required(invocationSiteId, "invocationSiteId", 512));
        return this;
    }

    /**
     * Adds one required transferred edge between structural invocation sites.
     *
     * @param from source invocation site
     * @param to destination invocation site
     * @return this builder
     */
    public TestSuiteBuilder requireEdgeTransfer(String from, String to) {
        EdgeTransfer edge = new EdgeTransfer(required(from, "fromInvocationSiteId", 512),
                required(to, "toInvocationSiteId", 512));
        if (!requiredEdgeTransfers.contains(edge)) {
            requiredEdgeTransfers.add(edge);
        }
        return this;
    }

    /**
     * Adds a requirement that one addressed branch edge transfers a value.
     *
     * @param requirementId stable suite-local requirement identity
     * @param from source invocation site
     * @param to destination invocation site
     * @return this builder
     */
    public TestSuiteBuilder requireBranchTransferred(String requirementId, String from, String to) {
        return addSemantic(new SemanticRequirement(required(requirementId, "requirementId", 255),
                "BRANCH_TRANSFERRED", required(from, "fromInvocationSiteId", 512),
                required(to, "toInvocationSiteId", 512), "", null, 0, ""));
    }

    /**
     * Adds a requirement that one addressed conditional branch is skipped.
     *
     * @param requirementId stable suite-local requirement identity
     * @param from source invocation site
     * @param to skipped destination invocation site
     * @return this builder
     */
    public TestSuiteBuilder requireBranchSkipped(String requirementId, String from, String to) {
        return addSemantic(new SemanticRequirement(required(requirementId, "requirementId", 255),
                "BRANCH_SKIPPED", required(from, "fromInvocationSiteId", 512),
                required(to, "toInvocationSiteId", 512), "", null, 0, ""));
    }

    /**
     * Requires a scalar decision result at a sanitized node-output JSON Pointer.
     *
     * @param requirementId stable suite-local requirement identity
     * @param invocationSiteId structural decision invocation site
     * @param outputJsonPointer RFC 6901 pointer into sanitized node output
     * @param expectedScalar expected string, number, boolean, or null
     * @return this builder
     */
    public TestSuiteBuilder requireDecisionRule(String requirementId, String invocationSiteId,
                                                String outputJsonPointer, Object expectedScalar) {
        String pointer = required(outputJsonPointer, "outputJsonPointer", 1024);
        JsonNode expected = snapshot(expectedScalar);
        if (!pointer.startsWith("/") || !expected.isValueNode()) {
            throw new IllegalArgumentException(
                    "Decision semantic coverage requires a JSON Pointer and scalar expectation");
        }
        return addSemantic(new SemanticRequirement(required(requirementId, "requirementId", 255),
                "DECISION_RULE", required(invocationSiteId, "invocationSiteId", 512), "",
                pointer, expected, 0, ""));
    }

    /**
     * Adds a requirement for at least two delegate attempts at one addressed site.
     *
     * @param requirementId stable suite-local requirement identity
     * @param invocationSiteId structural retried invocation site
     * @param minimumAttempts required attempt count from 2 through 100000
     * @return this builder
     */
    public TestSuiteBuilder requireRetry(String requirementId, String invocationSiteId,
                                         int minimumAttempts) {
        if (minimumAttempts < 2 || minimumAttempts > 100_000) {
            throw new IllegalArgumentException("minimumAttempts must be between 2 and 100000");
        }
        return addSemantic(siteRequirement(requirementId, "RETRY", invocationSiteId,
                minimumAttempts, ""));
    }

    /**
     * Adds a requirement that engine fallback completes at one addressed site.
     *
     * @param requirementId stable suite-local requirement identity
     * @param invocationSiteId structural fallback invocation site
     * @return this builder
     */
    public TestSuiteBuilder requireFallback(String requirementId, String invocationSiteId) {
        return addSemantic(siteRequirement(requirementId, "FALLBACK", invocationSiteId, 0, ""));
    }

    /**
     * Adds a requirement for any timeout at one addressed site.
     *
     * @param requirementId stable suite-local requirement identity
     * @param invocationSiteId structural timed invocation site
     * @return this builder
     */
    public TestSuiteBuilder requireTimeout(String requirementId, String invocationSiteId) {
        return requireTimeout(requirementId, invocationSiteId, "");
    }

    /**
     * Adds a requirement for a timeout with an optional stable error code.
     *
     * @param requirementId stable suite-local requirement identity
     * @param invocationSiteId structural timed invocation site
     * @param errorCode optional stable timeout machine code
     * @return this builder
     */
    public TestSuiteBuilder requireTimeout(String requirementId, String invocationSiteId,
                                           String errorCode) {
        String code = errorCode == null ? "" : errorCode.trim();
        if (!code.isBlank() && !code.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
            throw new IllegalArgumentException("errorCode must be a stable machine code");
        }
        return addSemantic(siteRequirement(requirementId, "TIMEOUT", invocationSiteId, 0, code));
    }

    /**
     * Adds a requirement that an addressed compensation invocation executes.
     *
     * @param requirementId stable suite-local requirement identity
     * @param invocationSiteId structural site ending in {@code #COMPENSATION}
     * @return this builder
     */
    public TestSuiteBuilder requireCompensation(String requirementId, String invocationSiteId) {
        String site = required(invocationSiteId, "invocationSiteId", 512);
        if (!site.endsWith("#COMPENSATION")) {
            throw new IllegalArgumentException(
                    "Compensation requirements must address a #COMPENSATION invocation site");
        }
        return addSemantic(siteRequirement(requirementId, "COMPENSATION", site, 0, ""));
    }

    /**
     * Sets the minimum evaluated assertion count for every case.
     *
     * @param value minimum evaluated assertions for every case
     * @return this builder
     */
    public TestSuiteBuilder minimumAssertionsPerCase(int value) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException("minimumAssertionsPerCase must be between 0 and 1000");
        }
        minimumAssertionsPerCase = value;
        return this;
    }

    /**
     * Controls whether every required fixture rule must be consumed.
     *
     * @param value whether every required fixture rule must be consumed
     * @return this builder
     */
    public TestSuiteBuilder requireAllFixtureRulesConsumed(boolean value) {
        requireAllFixtureRulesConsumed = value;
        return this;
    }

    /**
     * Controls whether every case must pass for promotion eligibility.
     *
     * @param value whether every case must pass for promotion eligibility
     * @return this builder
     */
    public TestSuiteBuilder requireAllCasesPassed(boolean value) {
        requireAllCasesPassed = value;
        return this;
    }

    /**
     * Sets the minimum number of cases that must emit certifiable evidence.
     *
     * @param value minimum cases that must emit certifiable evidence
     * @return this builder
     */
    public TestSuiteBuilder minimumCertifiableCases(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("minimumCertifiableCases must be between 0 and 100");
        }
        minimumCertifiableCases = value;
        return this;
    }

    /**
     * Controls whether target-level certification eligibility is mandatory.
     *
     * @param value whether target-level eligibility is mandatory
     * @return this builder
     */
    public TestSuiteBuilder requireTargetCertificationEligible(boolean value) {
        requireTargetCertificationEligible = value;
        return this;
    }

    /**
     * Replaces bounded suite provenance metadata with a defensive snapshot.
     *
     * @param value bounded suite provenance metadata
     * @return this builder
     */
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
        suite.put("schemaVersion", semanticRequirements.isEmpty()
                ? TestingProtocol.TEST_SUITE_V1 : TestingProtocol.TEST_SUITE_V2);
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
        LinkedHashSet<CaseType> representedCaseTypes = cases.stream().map(SuiteCase::caseType)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<CaseType> missingCaseTypes = new LinkedHashSet<>(effectiveCaseTypes);
        missingCaseTypes.removeAll(representedCaseTypes);
        if (!missingCaseTypes.isEmpty()) {
            throw new IllegalStateException("requiredCaseTypes are not represented by cases: "
                    + missingCaseTypes.stream().map(Enum::name).sorted()
                    .collect(java.util.stream.Collectors.joining(",")));
        }
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
        if (!semanticRequirements.isEmpty()) {
            ArrayNode requirements = suite.putObject("semanticCoveragePolicy")
                    .putArray("requirements");
            semanticRequirements.stream()
                    .sorted(java.util.Comparator.comparing(SemanticRequirement::requirementId))
                    .forEach(value -> writeSemanticRequirement(requirements.addObject(), value));
        }
        ObjectNode promotion = suite.putObject("promotionPolicy");
        promotion.put("requireAllCasesPassed", requireAllCasesPassed);
        promotion.put("minimumCertifiableCases", certifiableCases);
        promotion.put("requireTargetCertificationEligible", requireTargetCertificationEligible);
        suite.set("metadata", metadata.deepCopy());
        TestingProtocolSchemaValidator.require(request, "testSuiteRegistrationRequest");
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

    private TestSuiteBuilder addSemantic(SemanticRequirement requirement) {
        if (semanticRequirements.size() >= 1_000) {
            throw new IllegalArgumentException("A suite may contain at most 1000 semantic requirements");
        }
        if (semanticRequirements.stream().anyMatch(existing ->
                existing.requirementId().equals(requirement.requirementId()))) {
            throw new IllegalArgumentException(
                    "Duplicate semantic requirement id: " + requirement.requirementId());
        }
        semanticRequirements.add(requirement);
        return this;
    }

    private static SemanticRequirement siteRequirement(String requirementId, String kind,
                                                       String invocationSiteId,
                                                       int minimumAttempts, String errorCode) {
        return new SemanticRequirement(required(requirementId, "requirementId", 255), kind,
                required(invocationSiteId, "invocationSiteId", 512), "", "", null,
                minimumAttempts, errorCode);
    }

    private static void writeSemanticRequirement(ObjectNode output, SemanticRequirement value) {
        output.put("requirementId", value.requirementId());
        output.put("kind", value.kind());
        switch (value.kind()) {
            case "BRANCH_TRANSFERRED", "BRANCH_SKIPPED" -> {
                output.put("fromInvocationSiteId", value.site());
                output.put("toInvocationSiteId", value.toSite());
            }
            case "DECISION_RULE" -> {
                output.put("invocationSiteId", value.site());
                output.put("outputJsonPointer", value.outputJsonPointer());
                output.set("expectedScalar", value.expectedScalar().deepCopy());
            }
            case "RETRY" -> {
                output.put("invocationSiteId", value.site());
                output.put("minimumAttempts", value.minimumAttempts());
            }
            case "FALLBACK", "TIMEOUT", "COMPENSATION" -> {
                output.put("invocationSiteId", value.site());
                output.put("errorCode", value.errorCode());
            }
            default -> throw new IllegalStateException("Unsupported semantic requirement kind");
        }
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

    private record SemanticRequirement(String requirementId, String kind, String site,
                                       String toSite, String outputJsonPointer,
                                       JsonNode expectedScalar, int minimumAttempts,
                                       String errorCode) {
    }
}
