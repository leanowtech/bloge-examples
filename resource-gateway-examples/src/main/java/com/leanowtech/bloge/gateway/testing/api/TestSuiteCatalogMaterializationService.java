package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.gateway.BuiltInGatewayGraphContractTestSuites;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractFixtureMapper;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestCase;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestCoveragePolicy;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestSuite;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Materializes the trusted legacy built-in graph catalog into common immutable testing assets.
 *
 * <p>Destination ids remain stable while revisions are derived from canonical source content and
 * the exact target dependency fingerprint. Repeating materialization is therefore idempotent;
 * changing a graph, resource descriptor, source case, or policy creates a new immutable revision
 * without mutating prior evidence. Each fixture is committed before its suite reference. A failed
 * multi-suite attempt can leave only unreferenced immutable assets and is safe to resume.</p>
 */
public final class TestSuiteCatalogMaterializationService {

    /** Stable identity of the trusted seven-graph source catalog. */
    public static final String CATALOG_ID = "resource-gateway.built-in-graph-contracts";

    private static final String DESTINATION_PREFIX = "rg-built-in-";

    private final TestExecutionApiService executionService;
    private final TestSuiteRegistryService suiteRegistry;
    private final GatewayGraphService graphService;
    private final ObjectMapper objectMapper;
    private final GatewayGraphContractFixtureMapper fixtureMapper =
            new GatewayGraphContractFixtureMapper();

    /**
     * Creates a materializer over the existing immutable fixture and suite registries.
     *
     * @param executionService target discovery and fixture registry service
     * @param suiteRegistry dependency-validating immutable suite registry
     * @param graphService graph catalog used to derive planner-owned structural coordinates
     * @param objectMapper canonical fingerprint serializer
     */
    public TestSuiteCatalogMaterializationService(TestExecutionApiService executionService,
                                                  TestSuiteRegistryService suiteRegistry,
                                                  GatewayGraphService graphService,
                                                  ObjectMapper objectMapper) {
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.suiteRegistry = Objects.requireNonNull(suiteRegistry, "suiteRegistry");
        this.graphService = Objects.requireNonNull(graphService, "graphService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Materializes every built-in graph case into the caller's authorized test registry scope.
     *
     * @param identity verified test/staging workload identity
     * @return deterministic exact source-to-destination reference mapping
     */
    public TestSuiteCatalogMaterializationResponse materializeBuiltIn(
            IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        List<TestSuiteCatalogMaterializationResponse.SuiteAsset> assets =
                BuiltInGatewayGraphContractTestSuites.all().stream()
                        .sorted(Comparator.comparing(GatewayGraphContractTestSuite::suiteId))
                        .map(source -> materialize(source, identity))
                        .toList();
        int totalCases = assets.stream()
                .mapToInt(TestSuiteCatalogMaterializationResponse.SuiteAsset::caseCount)
                .sum();
        String catalogFingerprint = ProtocolFingerprint.of(objectMapper,
                Map.of("catalogId", CATALOG_ID, "suites", assets));
        return new TestSuiteCatalogMaterializationResponse("", CATALOG_ID, catalogFingerprint,
                identity.tenantId(), identity.environmentId(), assets.size(), totalCases, assets);
    }

    private TestSuiteCatalogMaterializationResponse.SuiteAsset materialize(
            GatewayGraphContractTestSuite source,
            IntegrationRequestContext identity) {
        String graphName = source.request().graphName();
        TestGraphTargetDescriptor target = executionService.describeGraphTarget(graphName, identity);
        String suiteId = destinationSuiteId(source.suiteId());
        List<StoredFixtureBundle> storedFixtures = new ArrayList<>();
        List<TestSuite.TestCase> cases = new ArrayList<>();

        for (int index = 0; index < source.request().cases().size(); index++) {
            GatewayGraphContractTestCase sourceCase = source.request().cases().get(index);
            String fixtureId = destinationFixtureId(source.suiteId(), index);
            Map<String, Object> metadata = caseMetadata(source, sourceCase, index);
            FixtureBundle revisionDraft = fixtureMapper.map(fixtureId, 1,
                    target.target().fingerprint(), sourceCase, target.contract(), metadata);
            long fixtureRevision = contentRevision("FIXTURE", revisionDraft);
            FixtureBundle fixture = fixtureMapper.map(fixtureId, fixtureRevision,
                    target.target().fingerprint(), sourceCase, target.contract(), metadata);
            StoredFixtureBundle stored = executionService.registerFixture(fixtureId,
                    new FixtureBundleRegistrationRequest("", target.target(), fixture), identity);
            storedFixtures.add(stored);
            cases.add(new TestSuite.TestCase(caseId(index), commonCaseType(sourceCase.caseType()),
                    sourceCase.context(), new TestSuite.FixtureBundleRef(stored.fixtureBundleId(),
                    stored.revision(), stored.fingerprint()), caseTags(source, sourceCase), metadata));
        }

        TestSuite.CoveragePolicy coverage = coveragePolicy(source, storedFixtures, target);
        TestSuite.PromotionPolicy promotion = new TestSuite.PromotionPolicy(
                true, cases.size(), true);
        Map<String, Object> suiteMetadata = suiteMetadata(source);
        TestSuite revisionDraft = new TestSuite("", suiteId, 1,
                new TestSuite.Target("GRAPH", graphName, target.target().fingerprint()),
                "INTERNAL", cases, coverage, promotion, suiteMetadata);
        long suiteRevision = contentRevision("SUITE", revisionDraft);
        TestSuite suite = new TestSuite("", suiteId, suiteRevision, revisionDraft.target(),
                revisionDraft.classification(), revisionDraft.cases(), revisionDraft.coveragePolicy(),
                revisionDraft.promotionPolicy(), revisionDraft.metadata());
        StoredTestSuite storedSuite = suiteRegistry.register(suiteId,
                new TestSuiteRegistrationRequest("", suite), identity);
        List<TestSuite.FixtureBundleRef> fixtureRefs = storedFixtures.stream()
                .map(stored -> new TestSuite.FixtureBundleRef(stored.fixtureBundleId(),
                        stored.revision(), stored.fingerprint()))
                .toList();
        return new TestSuiteCatalogMaterializationResponse.SuiteAsset(source.suiteId(), graphName,
                cases.size(), new TestSuiteExecutionRequest.SuiteRef(storedSuite.suiteId(),
                storedSuite.revision(), storedSuite.fingerprint()), fixtureRefs);
    }

    private TestSuite.CoveragePolicy coveragePolicy(GatewayGraphContractTestSuite source,
                                                    List<StoredFixtureBundle> fixtures,
                                                    TestGraphTargetDescriptor target) {
        GatewayGraphContractTestCoveragePolicy legacy = source.coveragePolicy();
        List<TestSuite.CaseType> requiredTypes = source.request().cases().stream()
                .map(GatewayGraphContractTestCase::caseType)
                .map(TestSuiteCatalogMaterializationService::commonCaseType)
                .distinct()
                .toList();
        InvocationInventory inventory = new InvocationInventoryBuilder(
                graphService.engine().operatorRegistry().orElseThrow(() ->
                        new IllegalStateException("Graph engine does not expose its operator registry.")))
                .build(graphService.requireGraph(source.request().graphName()),
                        target.target().fingerprint());
        List<String> requiredSites = legacy.requiredOutputNodes().stream()
                .map(nodeId -> requiredRootSite(inventory, nodeId))
                .toList();
        int minimumAssertions = fixtures.stream()
                .mapToInt(fixture -> fixture.bundle().assertions().size())
                .min()
                .orElse(0);
        return new TestSuite.CoveragePolicy(Math.max(1, legacy.minCases()), requiredTypes,
                requiredSites, List.of(), minimumAssertions, true);
    }

    private static String requiredRootSite(InvocationInventory inventory, String nodeId) {
        return inventory.entries().stream()
                .map(InvocationInventory.Entry::site)
                .filter(site -> "/root".equals(site.graphPath()))
                .filter(site -> nodeId.equals(site.nodeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Legacy coverage policy references unknown root output node '" + nodeId + "'."))
                .invocationSiteId();
    }

    private Map<String, Object> suiteMetadata(GatewayGraphContractTestSuite source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "legacy-graph-contract-catalog");
        metadata.put("sourceCatalogId", CATALOG_ID);
        metadata.put("sourceSuiteId", source.suiteId());
        metadata.put("sourceSchemaVersion", source.schemaVersion());
        metadata.put("displayName", source.displayName());
        metadata.put("description", source.description());
        metadata.put("sourceTags", source.tags());
        metadata.put("sourceCoveragePolicy", source.coveragePolicy());
        return Map.copyOf(metadata);
    }

    private Map<String, Object> caseMetadata(GatewayGraphContractTestSuite source,
                                             GatewayGraphContractTestCase sourceCase,
                                             int index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "legacy-graph-contract-catalog");
        metadata.put("sourceCatalogId", CATALOG_ID);
        metadata.put("sourceSuiteId", source.suiteId());
        metadata.put("sourceCaseIndex", index);
        metadata.put("sourceCaseName", sourceCase.name());
        metadata.put("sourceCaseDescription", sourceCase.description());
        metadata.put("sourceCaseType", sourceCase.caseType().name());
        metadata.put("sourceOutputNode", sourceCase.outputNode());
        return Map.copyOf(metadata);
    }

    private static List<String> caseTags(GatewayGraphContractTestSuite source,
                                         GatewayGraphContractTestCase sourceCase) {
        LinkedHashSet<String> tags = new LinkedHashSet<>(source.tags());
        tags.add("catalog-materialized");
        tags.add(sourceCase.caseType().name().toLowerCase(Locale.ROOT));
        return tags.stream().sorted().toList();
    }

    private long contentRevision(String assetKind, Object content) {
        String fingerprint = ProtocolFingerprint.of(objectMapper,
                Map.of("assetKind", assetKind, "content", content));
        long revision = Long.parseLong(fingerprint.substring("sha256:".length(),
                "sha256:".length() + 15), 16);
        return Math.max(1, revision);
    }

    private static TestSuite.CaseType commonCaseType(GatewayGraphContractTestCase.CaseType value) {
        return TestSuite.CaseType.valueOf(value.name());
    }

    private static String destinationSuiteId(String sourceSuiteId) {
        return boundedId(DESTINATION_PREFIX + sourceSuiteId);
    }

    private static String destinationFixtureId(String sourceSuiteId, int caseIndex) {
        return boundedId(DESTINATION_PREFIX + sourceSuiteId + "-case-" + String.format("%03d", caseIndex + 1));
    }

    private static String caseId(int index) {
        return "case-" + String.format("%03d", index + 1);
    }

    private static String boundedId(String value) {
        String normalized = value == null ? "" : value.trim()
                .replaceAll("[^A-Za-z0-9._-]", "-")
                .replaceAll("-+", "-");
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException("Materialized test asset id must contain at most 255 safe characters.");
        }
        return normalized;
    }
}
