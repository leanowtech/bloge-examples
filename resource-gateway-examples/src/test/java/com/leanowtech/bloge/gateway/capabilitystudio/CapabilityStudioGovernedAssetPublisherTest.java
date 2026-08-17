package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationPlan;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract tests for the payload-free, independently verified governed asset publication edge. */
class CapabilityStudioGovernedAssetPublisherTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-18T00:00:00Z");

    private ObjectMapper objectMapper;
    private FakeRegistry registry;
    private CapabilityStudioGovernedAssetPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        registry = new FakeRegistry(objectMapper);
        publisher = new CapabilityStudioGovernedAssetPublisher(objectMapper, registry);
    }

    @Test
    void registersEveryAssetAndVerifiesIndependentReadsWhileIgnoringRegisterReturns()
            throws Exception {
        CapabilityStudioGovernedCompilation compilation = compilation("z-fixture", "a-fixture");

        CapabilityStudioGovernedAssetPublisher.Receipt receipt = publisher.publish(
                compilation, identity());

        assertThat(receipt.fixtureRefs())
                .extracting(CapabilityStudioGovernedAssetPublisher.ExactRef::id)
                .containsExactly("a-fixture", "z-fixture");
        assertThat(receipt.fixtureRefs())
                .allSatisfy(ref -> assertThat(ref.kind()).isEqualTo("FIXTURE_BUNDLE"));
        assertThat(receipt.suiteRef().kind()).isEqualTo("TEST_SUITE");
        assertThat(receipt.receiptFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(registry.fixtureRegisters).isEqualTo(2);
        assertThat(registry.fixtureReads).isEqualTo(2);
        assertThat(registry.suiteRegisters).isEqualTo(1);
        assertThat(registry.suiteReads).isEqualTo(1);
        assertThat(registry.registerReturnWasCorrupt).isTrue();
        assertThat(objectMapper.writeValueAsString(receipt))
                .doesNotContain("SECRET-FIXTURE-PAYLOAD", "SECRET-SUITE-PAYLOAD");
    }

    @Test
    void repeatsTheSameInputWithTheSameDeterministicReceipt() {
        CapabilityStudioGovernedCompilation compilation = compilation("z-fixture", "a-fixture");

        CapabilityStudioGovernedAssetPublisher.Receipt first = publisher.publish(
                compilation, identity());
        CapabilityStudioGovernedAssetPublisher.Receipt second = publisher.publish(
                compilation, identity());

        assertThat(second).isEqualTo(first);
        assertThat(second.receiptFingerprint()).isEqualTo(first.receiptFingerprint());
    }

    @Test
    void rejectsAnUncompiledPlanBeforeCallingTheRegistry() {
        CapabilityStudioGovernedCompilation blocked = compilation(false, List.of(), null);

        assertThatThrownBy(() -> publisher.publish(blocked, identity()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .satisfies(error -> {
                    CapabilityStudioGovernedCompilationException failure =
                            (CapabilityStudioGovernedCompilationException) error;
                    assertThat(failure.code())
                            .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.PLAN_NOT_COMPILED");
                    assertThat(failure.getMessage()).doesNotContain("SECRET");
                });
        assertThat(registry.fixtureRegisters).isZero();
        assertThat(registry.suiteRegisters).isZero();
    }

    @Test
    void rejectsErrorDiagnosticsEvenWhenCompilerMarkedThePlanCompiled() {
        CapabilityStudioGovernedCompilation blocked = compilation(
                true,
                List.of(fixture("one-fixture")),
                suite("suite"),
                List.of(VisualDiagnostic.error(
                        "visual.test.error", "secret diagnostic payload", "/payload")));

        assertThatThrownBy(() -> publisher.publish(blocked, identity()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .satisfies(error -> {
                    CapabilityStudioGovernedCompilationException failure =
                            (CapabilityStudioGovernedCompilationException) error;
                    assertThat(failure.code())
                            .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.ERROR_DIAGNOSTICS");
                    assertThat(failure.getMessage())
                            .doesNotContain("secret diagnostic payload", "SECRET");
                });
        assertThat(registry.fixtureRegisters).isZero();
    }

    @Test
    void rejectsFixtureWhenIndependentReadDrifts() {
        registry.driftFixtureRead = true;

        assertThatThrownBy(() -> publisher.publish(compilation("fixture", "other"), identity()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .satisfies(error -> assertThat(
                        ((CapabilityStudioGovernedCompilationException) error).code())
                        .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.FIXTURE_RE_READ_DRIFT"));
        assertThat(registry.suiteRegisters).isZero();
    }

    @Test
    void rejectsFixtureWhenIndependentReadContainsAReFingerprintingContentDrift() {
        registry.driftFixtureContentRead = true;

        assertThatThrownBy(() -> publisher.publish(compilation("fixture", "other"), identity()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .satisfies(error -> assertThat(
                        ((CapabilityStudioGovernedCompilationException) error).code())
                        .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.FIXTURE_RE_READ_DRIFT"));
    }

    @Test
    void rejectsSuiteWhenIndependentReadDrifts() {
        registry.driftSuiteRead = true;

        assertThatThrownBy(() -> publisher.publish(compilation("fixture", "other"), identity()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .satisfies(error -> assertThat(
                        ((CapabilityStudioGovernedCompilationException) error).code())
                        .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.SUITE_RE_READ_DRIFT"));
        assertThat(registry.suiteReads).isEqualTo(1);
    }

    @Test
    void rejectsSuiteWhenIndependentReadContainsAReFingerprintingContentDrift() {
        registry.driftSuiteContentRead = true;

        assertThatThrownBy(() -> publisher.publish(compilation("fixture", "other"), identity()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .satisfies(error -> assertThat(
                        ((CapabilityStudioGovernedCompilationException) error).code())
                        .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.SUITE_RE_READ_DRIFT"));
    }

    @Test
    void rejectsMissingFixturesAndSuiteBeforeAnyRegistration() {
        CapabilityStudioGovernedCompilation missingFixtures = compilation(
                true, List.of(), suite("suite"));
        CapabilityStudioGovernedCompilation missingSuite = compilation(
                true, List.of(fixture("fixture")), null);

        assertThatThrownBy(() -> publisher.publish(missingFixtures, identity()))
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.FIXTURES_MISSING");
        assertThatThrownBy(() -> publisher.publish(missingSuite, identity()))
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_PUBLISH.SUITE_MISSING");
        assertThat(registry.fixtureRegisters).isZero();
    }

    private CapabilityStudioGovernedCompilation compilation(String... fixtureIds) {
        return compilation(true,
                java.util.Arrays.stream(fixtureIds).map(this::fixture).toList(),
                suite("suite"));
    }

    private CapabilityStudioGovernedCompilation compilation(
            boolean compiled,
            List<ScenarioGovernedCompilationPlan.CompiledFixture> fixtures,
            TestSuiteRegistrationRequest suite) {
        return compilation(compiled, fixtures, suite, List.of());
    }

    private CapabilityStudioGovernedCompilation compilation(
            boolean compiled,
            List<ScenarioGovernedCompilationPlan.CompiledFixture> fixtures,
            TestSuiteRegistrationRequest suite,
            List<VisualDiagnostic> diagnostics) {
        TestExecutionApiRequest.Target target = target();
        ScenarioGovernedCompilationPlan plan = new ScenarioGovernedCompilationPlan(
                "", compiled, "source", 1, target.fingerprint(), "contract-fingerprint",
                target, fixtures, suite, diagnostics);
        CapabilityStudioScenarioDatasetSourceMap sourceMap =
                new CapabilityStudioScenarioDatasetSourceMap(null, null, "contract-fingerprint", List.of());
        String fingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "plan", plan, "sourceMap", sourceMap));
        return new CapabilityStudioGovernedCompilation(plan, sourceMap, fingerprint);
    }

    private ScenarioGovernedCompilationPlan.CompiledFixture fixture(String id) {
        FixtureBundle bundle = new FixtureBundle(
                "", id, 1, target().fingerprint(), "INTERNAL", null, null, List.of(),
                List.of(), Map.of("payload", "SECRET-FIXTURE-PAYLOAD"));
        FixtureBundleRegistrationRequest request = new FixtureBundleRegistrationRequest(
                "", target(), bundle);
        return new ScenarioGovernedCompilationPlan.CompiledFixture(
                id, ProtocolFingerprint.of(objectMapper, bundle), request);
    }

    private TestSuiteRegistrationRequest suite(String id) {
        TestSuite suite = new TestSuite(
                "", id, 1,
                new TestSuite.Target("GRAPH", "graph", target().fingerprint()), "INTERNAL",
                List.of(new TestSuite.TestCase(
                        "case", TestSuite.CaseType.GOLDEN, Map.of("input", "safe"),
                        new TestSuite.FixtureBundleRef("fixture", 1,
                                fixture("fixture").fingerprint()), List.of(),
                        Map.of("payload", "SECRET-SUITE-PAYLOAD"))),
                TestSuite.CoveragePolicy.defaults(), TestSuite.PromotionPolicy.defaults(), Map.of());
        return new TestSuiteRegistrationRequest("", suite);
    }

    private static TestExecutionApiRequest.Target target() {
        return new TestExecutionApiRequest.Target(
                "GRAPH", "graph", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant", "organization", "project", "test", "sg",
                "WORKLOAD", "publisher", "", "CAPABILITY_STUDIO_GOVERNED_PUBLISH", "correlation");
    }

    private static final class FakeRegistry implements ScenarioGovernedRegistryGateway {
        private final ObjectMapper objectMapper;
        private final Map<String, StoredFixtureBundle> fixtures = new LinkedHashMap<>();
        private final Map<String, StoredTestSuite> suites = new LinkedHashMap<>();
        private int fixtureRegisters;
        private int fixtureReads;
        private int suiteRegisters;
        private int suiteReads;
        private boolean driftFixtureRead;
        private boolean driftFixtureContentRead;
        private boolean driftSuiteRead;
        private boolean driftSuiteContentRead;
        private boolean registerReturnWasCorrupt;

        private FakeRegistry(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public TestExecutionApiRequest.Target describeGraphTarget(
                String graphName, IntegrationRequestContext identity) {
            return target();
        }

        @Override
        public TestExecutionApiRequest.Target describeOperatorTarget(
                String operatorRef, IntegrationRequestContext identity) {
            return target();
        }

        @Override
        public StoredFixtureBundle registerFixture(
                String fixtureBundleId,
                FixtureBundleRegistrationRequest request,
                IntegrationRequestContext identity) {
            fixtureRegisters++;
            FixtureBundle bundle = request.fixtureBundle();
            StoredFixtureBundle stored = new StoredFixtureBundle(
                    "", identity.tenantId(), identity.organizationId(), identity.projectId(),
                    identity.environmentId(), identity.region(), bundle.fixtureBundleId(),
                    bundle.revision(), ProtocolFingerprint.of(objectMapper, bundle), bundle,
                    CREATED_AT, identity.actorId());
            fixtures.put(key(bundle.fixtureBundleId(), bundle.revision()), stored);
            registerReturnWasCorrupt = true;
            return new StoredFixtureBundle(
                    "", identity.tenantId(), identity.organizationId(), identity.projectId(),
                    identity.environmentId(), identity.region(), "wrong-return", 99,
                    "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    bundle, CREATED_AT, identity.actorId());
        }

        @Override
        public StoredFixtureBundle findFixture(
                String fixtureBundleId, long revision, IntegrationRequestContext identity) {
            fixtureReads++;
            StoredFixtureBundle stored = fixtures.get(key(fixtureBundleId, revision));
            if (!driftFixtureRead || stored == null) {
                if (!driftFixtureContentRead || stored == null) {
                    return stored;
                }
                FixtureBundle original = stored.bundle();
                FixtureBundle changed = new FixtureBundle(
                        original.schemaVersion(), original.fixtureBundleId(), original.revision(),
                        original.targetFingerprint(), original.classification(), original.logicalClock(),
                        original.randomSeed(), original.rules(), original.assertions(),
                        Map.of("payload", "SECRET-FIXTURE-PAYLOAD-DRIFTED"));
                return new StoredFixtureBundle(
                        stored.schemaVersion(), stored.tenantId(), stored.organizationId(),
                        stored.projectId(), stored.environmentId(), stored.region(),
                        stored.fixtureBundleId(), stored.revision(),
                        ProtocolFingerprint.of(objectMapper, changed), changed,
                        stored.createdAt(), stored.createdBy());
            }
            return new StoredFixtureBundle(
                    stored.schemaVersion(), stored.tenantId(), stored.organizationId(),
                    stored.projectId(), stored.environmentId(), stored.region(),
                    stored.fixtureBundleId(), stored.revision(),
                    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    stored.bundle(), stored.createdAt(), stored.createdBy());
        }

        @Override
        public StoredTestSuite registerSuite(
                String suiteId,
                TestSuiteRegistrationRequest request,
                IntegrationRequestContext identity) {
            suiteRegisters++;
            TestSuiteProtocol suite = request.testSuite();
            StoredTestSuite stored = new StoredTestSuite(
                    "", identity.tenantId(), identity.organizationId(), identity.projectId(),
                    identity.environmentId(), identity.region(), suite.suiteId(), suite.revision(),
                    ProtocolFingerprint.of(objectMapper, suite), suite, CREATED_AT, identity.actorId());
            suites.put(key(suite.suiteId(), suite.revision()), stored);
            registerReturnWasCorrupt = true;
            return new StoredTestSuite(
                    "", identity.tenantId(), identity.organizationId(), identity.projectId(),
                    identity.environmentId(), identity.region(), "wrong-return", 99,
                    "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                    suite, CREATED_AT, identity.actorId());
        }

        @Override
        public StoredTestSuite findSuite(
                String suiteId, long revision, IntegrationRequestContext identity) {
            suiteReads++;
            StoredTestSuite stored = suites.get(key(suiteId, revision));
            if (!driftSuiteRead || stored == null) {
                if (!driftSuiteContentRead || stored == null) {
                    return stored;
                }
                TestSuite original = (TestSuite) stored.suite();
                TestSuite changed = new TestSuite(
                        original.schemaVersion(), original.suiteId(), original.revision(),
                        original.target(), original.classification(), original.cases(),
                        original.coveragePolicy(), original.promotionPolicy(),
                        Map.of("payload", "SECRET-SUITE-PAYLOAD-DRIFTED"));
                return new StoredTestSuite(
                        stored.schemaVersion(), stored.tenantId(), stored.organizationId(),
                        stored.projectId(), stored.environmentId(), stored.region(),
                        stored.suiteId(), stored.revision(),
                        ProtocolFingerprint.of(objectMapper, changed), changed,
                        stored.createdAt(), stored.createdBy());
            }
            return new StoredTestSuite(
                    stored.schemaVersion(), stored.tenantId(), stored.organizationId(),
                    stored.projectId(), stored.environmentId(), stored.region(),
                    stored.suiteId(), stored.revision(),
                    "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                    stored.suite(), stored.createdAt(), stored.createdBy());
        }

        private static String key(String id, long revision) {
            return id + "@" + revision;
        }
    }
}
