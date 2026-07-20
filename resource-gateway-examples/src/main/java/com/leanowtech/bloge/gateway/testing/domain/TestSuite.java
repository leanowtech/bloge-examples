package com.leanowtech.bloge.gateway.testing.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, target-bound collection of governed test cases and promotion policy.
 *
 * <p>A suite deliberately contains only exact fixture references. Control rules remain in the
 * fixture registry, so a suite revision cannot silently acquire different execution behavior
 * after review or promotion.</p>
 *
 * @param schemaVersion suite protocol version
 * @param suiteId stable suite identifier
 * @param revision immutable suite revision
 * @param target exact graph or operator artifact under test
 * @param classification maximum data classification of the suite and all referenced fixtures
 * @param cases ordered deterministic test cases
 * @param coveragePolicy minimum composition coverage required from the suite
 * @param promotionPolicy evidence requirements used by a later promotion gate
 * @param metadata bounded ownership and provenance facts
 */
public record TestSuite(
        String schemaVersion,
        String suiteId,
        long revision,
        Target target,
        String classification,
        List<TestCase> cases,
        CoveragePolicy coveragePolicy,
        PromotionPolicy promotionPolicy,
        Map<String, Object> metadata
) implements TestSuiteProtocol {
    /** Current immutable suite protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuite.v1";

    /** Normalizes identifiers and freezes all suite collections. */
    public TestSuite {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteId = trimmed(suiteId);
        classification = defaulted(classification, "INTERNAL").toUpperCase(Locale.ROOT);
        cases = cases == null ? List.of() : List.copyOf(cases);
        coveragePolicy = coveragePolicy == null ? CoveragePolicy.defaults() : coveragePolicy;
        promotionPolicy = promotionPolicy == null ? PromotionPolicy.defaults() : promotionPolicy;
        metadata = ProtocolJsonValue.freezeMap(metadata);
    }

    /**
     * Exact target identity frozen into a suite revision.
     *
     * @param kind {@code GRAPH} or {@code OPERATOR}
     * @param id registered target identifier
     * @param fingerprint full target dependency fingerprint
     */
    public record Target(String kind, String id, String fingerprint) {
        /** Normalizes the target identity without weakening fingerprint validation. */
        public Target {
            kind = trimmed(kind).toUpperCase(Locale.ROOT);
            id = trimmed(id);
            fingerprint = trimmed(fingerprint);
        }
    }

    /** Supported case intents used for coverage and governance reporting. */
    public enum CaseType {
        GOLDEN,
        NEGATIVE,
        BOUNDARY,
        REGRESSION,
        /** Seeded, validator-proven sample governed by an explicit property policy. */
        PROPERTY
    }

    /**
     * One deterministic input bound to one immutable fixture revision.
     *
     * @param caseId suite-local stable case identifier
     * @param caseType governance intent of the case
     * @param input graph context object or operator input value
     * @param fixtureBundleRef exact governed fixture dependency
     * @param tags bounded query and ownership labels
     * @param metadata bounded case provenance facts
     */
    public record TestCase(
            String caseId,
            CaseType caseType,
            Object input,
            FixtureBundleRef fixtureBundleRef,
            List<String> tags,
            Map<String, Object> metadata
    ) {
        /** Normalizes case identifiers and freezes labels and metadata. */
        public TestCase {
            caseId = trimmed(caseId);
            input = ProtocolJsonValue.freeze(input);
            tags = sortedIdentifiers(tags);
            metadata = ProtocolJsonValue.freezeMap(metadata);
        }
    }

    /**
     * Exact content-addressed dependency on a fixture registry revision.
     *
     * @param fixtureBundleId stable fixture identifier
     * @param revision immutable fixture revision
     * @param fingerprint full fixture content fingerprint
     */
    public record FixtureBundleRef(String fixtureBundleId, long revision, String fingerprint) {
        /** Normalizes the reference without permitting implicit latest-version lookup. */
        public FixtureBundleRef {
            fixtureBundleId = trimmed(fixtureBundleId);
            fingerprint = trimmed(fingerprint);
        }
    }

    /**
     * Registration-time and execution-time suite coverage requirements.
     *
     * <p>Case composition and assertion density are checked at registration. Node and edge
     * requirements are evaluated against run evidence by the suite runner.</p>
     *
     * @param minimumCases minimum number of cases in this revision
     * @param requiredCaseTypes case intents that must be represented
     * @param requiredInvocationSiteIds structure-addressed invocation sites that must be observed
     * @param requiredEdgeTransfers structure-addressed edge endpoints that must transfer
     * @param minimumAssertionsPerCase minimum fixture assertions attached to every case
     * @param requireAllFixtureRulesConsumed whether every required fixture rule must be consumed
     */
    public record CoveragePolicy(
            int minimumCases,
            List<CaseType> requiredCaseTypes,
            List<String> requiredInvocationSiteIds,
            List<EdgeTransferRef> requiredEdgeTransfers,
            int minimumAssertionsPerCase,
            boolean requireAllFixtureRulesConsumed
    ) {
        /** Canonicalizes set-like policy collections for stable fingerprints. */
        public CoveragePolicy {
            requiredCaseTypes = sortedCaseTypes(requiredCaseTypes);
            requiredInvocationSiteIds = sortedIdentifiers(requiredInvocationSiteIds);
            requiredEdgeTransfers = sortedEdgeTransfers(requiredEdgeTransfers);
        }

        /** @return conservative default for a single governed case */
        public static CoveragePolicy defaults() {
            return new CoveragePolicy(1, List.of(), List.of(), List.of(), 0, true);
        }
    }

    /**
     * Collision-free aggregate edge coverage coordinate.
     *
     * @param fromInvocationSiteId structure-addressed source invocation site
     * @param toInvocationSiteId structure-addressed destination invocation site
     */
    public record EdgeTransferRef(String fromInvocationSiteId, String toInvocationSiteId) {
        /** Normalizes both structural endpoint identifiers. */
        public EdgeTransferRef {
            fromInvocationSiteId = trimmed(fromInvocationSiteId);
            toInvocationSiteId = trimmed(toInvocationSiteId);
        }
    }

    /**
     * Evidence requirements that a suite run must satisfy before promotion.
     *
     * @param requireAllCasesPassed whether any failed case blocks promotion
     * @param minimumCertifiableCases minimum cases that must emit certifiable evidence
     * @param requireTargetCertificationEligible whether target-level certification gaps block promotion
     */
    public record PromotionPolicy(
            boolean requireAllCasesPassed,
            int minimumCertifiableCases,
            boolean requireTargetCertificationEligible
    ) {
        /** @return fail-closed default for a governed suite */
        public static PromotionPolicy defaults() {
            return new PromotionPolicy(true, 1, true);
        }
    }

    private static List<CaseType> sortedCaseTypes(List<CaseType> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<CaseType> unique = new LinkedHashSet<>(values);
        List<CaseType> sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparing(Enum::name));
        return List.copyOf(sorted);
    }

    private static List<String> sortedIdentifiers(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        values.stream().map(TestSuite::trimmed).forEach(unique::add);
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static List<EdgeTransferRef> sortedEdgeTransfers(List<EdgeTransferRef> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<EdgeTransferRef> unique = new LinkedHashSet<>(values);
        List<EdgeTransferRef> sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparing((EdgeTransferRef value) ->
                value == null ? "" : value.fromInvocationSiteId())
                .thenComparing(value -> value == null ? "" : value.toInvocationSiteId()));
        return List.copyOf(sorted);
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
