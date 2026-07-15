package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Payload-free exact-reference projection of one caller-scoped catalog materialization.
 *
 * @param catalogId stable source catalog id
 * @param catalogFingerprint fingerprint of the ordered destination reference inventory
 * @param tenantId verified destination tenant
 * @param environmentId verified non-production environment
 * @param totalSuites materialized suite count
 * @param totalCases materialized case and fixture count
 * @param suites ordered source-to-destination mappings
 * @param rawResponse defensive complete response for explicit protocol diagnostics
 */
public record TestSuiteCatalogMaterialization(
        String catalogId,
        String catalogFingerprint,
        String tenantId,
        String environmentId,
        int totalSuites,
        int totalCases,
        List<SuiteAsset> suites,
        JsonNode rawResponse
) {
    /** Validates aggregate cardinality, exact references, and source identity uniqueness. */
    public TestSuiteCatalogMaterialization {
        catalogId = required(catalogId, "catalogId");
        catalogFingerprint = fingerprint(catalogFingerprint, "catalogFingerprint");
        tenantId = required(tenantId, "tenantId");
        environmentId = required(environmentId, "environmentId");
        suites = suites == null ? List.of() : List.copyOf(suites);
        if (totalSuites < 1 || totalSuites != suites.size()) {
            throw new IllegalArgumentException("Materialized suite count is inconsistent");
        }
        int observedCases = suites.stream().mapToInt(SuiteAsset::caseCount).sum();
        if (totalCases < 1 || totalCases != observedCases) {
            throw new IllegalArgumentException("Materialized case count is inconsistent");
        }
        Set<String> sourceIds = new HashSet<>();
        Set<String> suiteRefs = new HashSet<>();
        Set<ExactFixtureRef> fixtureRefs = new HashSet<>();
        for (SuiteAsset suite : suites) {
            if (!sourceIds.add(suite.sourceSuiteId()) || !suiteRefs.add(suite.suiteRef().exactRef())) {
                throw new IllegalArgumentException("Materialized catalog identities must be unique");
            }
            for (ExactFixtureRef fixtureRef : suite.fixtureRefs()) {
                if (!fixtureRefs.add(fixtureRef)) {
                    throw new IllegalArgumentException(
                            "Each materialized case must have a unique exact fixture reference");
                }
            }
        }
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Projects and fully validates a {@code bloge.testSuiteCatalogMaterialization.v1} response.
     *
     * @param response decoded response object
     * @return immutable payload-free catalog projection
     */
    public static TestSuiteCatalogMaterialization from(JsonNode response) {
        TestingProtocolSchemaValidator.require(response, "testSuiteCatalogMaterialization");
        List<SuiteAsset> assets = stream(response.path("suites"))
                .map(SuiteAsset::from)
                .toList();
        return new TestSuiteCatalogMaterialization(response.path("catalogId").asText(),
                response.path("catalogFingerprint").asText(), response.path("tenantId").asText(),
                response.path("environmentId").asText(), response.path("totalSuites").asInt(),
                response.path("totalCases").asInt(), assets, response);
    }

    /**
     * Returns the complete authorized protocol value for explicit diagnostics.
     *
     * @return a defensive copy of the complete authorized response
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * One source suite mapped to an exact common suite and one fixture per case.
     *
     * @param sourceSuiteId source catalog suite id
     * @param graphName graph target id
     * @param caseCount case cardinality
     * @param suiteRef exact destination suite reference
     * @param fixtureRefs ordered exact fixture references
     */
    public record SuiteAsset(String sourceSuiteId, String graphName, int caseCount,
                             ExactSuiteRef suiteRef, List<ExactFixtureRef> fixtureRefs) {
        /** Validates source identity and one-to-one case/fixture cardinality. */
        public SuiteAsset {
            sourceSuiteId = required(sourceSuiteId, "sourceSuiteId");
            graphName = required(graphName, "graphName");
            if (suiteRef == null) {
                throw new IllegalArgumentException("suiteRef is required");
            }
            fixtureRefs = fixtureRefs == null ? List.of() : List.copyOf(fixtureRefs);
            if (caseCount < 1 || caseCount != fixtureRefs.size()) {
                throw new IllegalArgumentException("Materialized case and fixture counts must match");
            }
            if (new HashSet<>(fixtureRefs).size() != fixtureRefs.size()) {
                throw new IllegalArgumentException("Materialized fixture references must be unique");
            }
        }

        private static SuiteAsset from(JsonNode value) {
            List<ExactFixtureRef> fixtures = stream(value.path("fixtureBundleRefs"))
                    .map(ExactFixtureRef::from)
                    .toList();
            return new SuiteAsset(value.path("sourceSuiteId").asText(),
                    value.path("graphName").asText(), value.path("caseCount").asInt(),
                    ExactSuiteRef.from(value.path("suiteRef")), fixtures);
        }
    }

    /**
     * Exact immutable suite registry reference suitable for the suite execution CLI.
     *
     * @param suiteId stable suite id
     * @param revision exact positive immutable revision
     * @param fingerprint full suite content fingerprint
     */
    public record ExactSuiteRef(String suiteId, long revision, String fingerprint) {
        /** Validates a complete content reference. */
        public ExactSuiteRef {
            suiteId = required(suiteId, "suiteId");
            fingerprint = TestSuiteCatalogMaterialization.fingerprint(
                    fingerprint, "suite fingerprint");
            if (revision < 1) {
                throw new IllegalArgumentException("suite revision must be positive");
            }
        }

        /**
         * Returns a log-safe exact identity string.
         *
         * @return suite id, revision, and fingerprint joined without payloads
         */
        public String exactRef() {
            return suiteId + "@" + revision + "#" + fingerprint;
        }

        private static ExactSuiteRef from(JsonNode value) {
            return new ExactSuiteRef(value.path("suiteId").asText(), value.path("revision").asLong(),
                    value.path("fingerprint").asText());
        }
    }

    /**
     * Exact immutable fixture registry reference retained for lineage inspection.
     *
     * @param fixtureBundleId stable fixture bundle id
     * @param revision exact positive immutable revision
     * @param fingerprint full fixture content fingerprint
     */
    public record ExactFixtureRef(String fixtureBundleId, long revision, String fingerprint) {
        /** Validates a complete content reference. */
        public ExactFixtureRef {
            fixtureBundleId = required(fixtureBundleId, "fixtureBundleId");
            fingerprint = TestSuiteCatalogMaterialization.fingerprint(
                    fingerprint, "fixture fingerprint");
            if (revision < 1) {
                throw new IllegalArgumentException("fixture revision must be positive");
            }
        }

        private static ExactFixtureRef from(JsonNode value) {
            return new ExactFixtureRef(value.path("fixtureBundleId").asText(),
                    value.path("revision").asLong(), value.path("fingerprint").asText());
        }
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a full lowercase SHA-256 fingerprint");
        }
        return normalized;
    }
}
