package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuite;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact caller-scoped result of materializing a trusted source catalog into immutable test assets.
 *
 * <p>The response deliberately contains no creation timestamps. Repeating the same operation over
 * the same source and frozen dependencies therefore returns the same value and can be compared by
 * CI, deployment automation, or governance synchronizers without parsing registry internals.</p>
 *
 * @param schemaVersion materialization response protocol version
 * @param catalogId stable source catalog identity
 * @param catalogFingerprint fingerprint of the ordered exact destination references
 * @param tenantId verified destination tenant
 * @param environmentId verified non-production destination environment
 * @param totalSuites number of materialized suite revisions
 * @param totalCases number of materialized cases and fixture revisions
 * @param suites ordered source-to-destination asset mapping
 */
public record TestSuiteCatalogMaterializationResponse(
        String schemaVersion,
        String catalogId,
        String catalogFingerprint,
        String tenantId,
        String environmentId,
        int totalSuites,
        int totalCases,
        List<SuiteAsset> suites
) {
    /** Current catalog materialization protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteCatalogMaterialization.v1";

    /** Normalizes identifiers, counters, and deterministic suite ordering. */
    public TestSuiteCatalogMaterializationResponse {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        catalogId = normalized(catalogId);
        catalogFingerprint = normalized(catalogFingerprint);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId);
        totalSuites = Math.max(0, totalSuites);
        totalCases = Math.max(0, totalCases);
        suites = suites == null ? List.of() : suites.stream()
                .sorted(Comparator.comparing(SuiteAsset::sourceSuiteId))
                .toList();
        if (!SCHEMA_VERSION.equals(schemaVersion) || catalogId.isBlank()
                || tenantId.isBlank() || environmentId.isBlank()
                || !catalogFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Complete catalog materialization identity is required.");
        }
        int observedCases = suites.stream().mapToInt(SuiteAsset::caseCount).sum();
        if (totalSuites < 1 || totalSuites != suites.size()
                || totalCases < 1 || totalCases != observedCases) {
            throw new IllegalArgumentException("Catalog materialization counters must match its assets.");
        }
        Set<String> sourceSuiteIds = new HashSet<>();
        Set<String> destinationSuiteRefs = new HashSet<>();
        Set<String> destinationFixtureRefs = new HashSet<>();
        for (SuiteAsset suite : suites) {
            if (!sourceSuiteIds.add(suite.sourceSuiteId())
                    || !destinationSuiteRefs.add(suite.suiteRef().suiteId() + "@"
                    + suite.suiteRef().revision() + "#" + suite.suiteRef().fingerprint())) {
                throw new IllegalArgumentException("Catalog suite identities must be unique.");
            }
            for (TestSuite.FixtureBundleRef reference : suite.fixtureBundleRefs()) {
                if (!destinationFixtureRefs.add(reference.fixtureBundleId() + "@"
                        + reference.revision() + "#" + reference.fingerprint())) {
                    throw new IllegalArgumentException(
                            "Each materialized case must have a unique exact fixture reference.");
                }
            }
        }
    }

    /**
     * One exact mapping from a legacy catalog suite to common registry assets.
     *
     * @param sourceSuiteId stable source catalog suite id
     * @param graphName graph target id
     * @param caseCount source and destination case count
     * @param suiteRef exact immutable common suite reference
     * @param fixtureBundleRefs ordered exact fixture references, one per case
     */
    public record SuiteAsset(
            String sourceSuiteId,
            String graphName,
            int caseCount,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            List<TestSuite.FixtureBundleRef> fixtureBundleRefs
    ) {
        /** Normalizes source labels and freezes case-order fixture references. */
        public SuiteAsset {
            sourceSuiteId = normalized(sourceSuiteId);
            graphName = normalized(graphName);
            caseCount = Math.max(0, caseCount);
            fixtureBundleRefs = fixtureBundleRefs == null ? List.of() : List.copyOf(fixtureBundleRefs);
            if (sourceSuiteId.isBlank() || graphName.isBlank() || !valid(suiteRef)
                    || caseCount < 1 || caseCount != fixtureBundleRefs.size()
                    || new HashSet<>(fixtureBundleRefs).size() != fixtureBundleRefs.size()
                    || fixtureBundleRefs.stream().anyMatch(reference -> !valid(reference))) {
                throw new IllegalArgumentException(
                        "A suite asset requires an exact suite reference and one unique exact fixture reference per case.");
            }
        }
    }

    private static boolean valid(TestSuiteExecutionRequest.SuiteRef reference) {
        return reference != null && !normalized(reference.suiteId()).isBlank()
                && reference.revision() > 0
                && normalized(reference.fingerprint()).matches("sha256:[0-9a-f]{64}");
    }

    private static boolean valid(TestSuite.FixtureBundleRef reference) {
        return reference != null && !normalized(reference.fixtureBundleId()).isBlank()
                && reference.revision() > 0
                && normalized(reference.fingerprint()).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
