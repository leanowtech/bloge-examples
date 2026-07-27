package com.leanowtech.bloge.gateway.visual.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies publication integrity, recovery, authorization, and payload-free lineage.
 */
class ScenarioPublicationServiceTest {

    private static final int MAX_TARGET_BYTES = 16 * 1_048_576;
    private static final Instant NOW = Instant.parse("2026-07-27T05:00:00Z");

    private ObjectMapper objectMapper;
    private JdbcTemplate jdbc;
    private DatabaseScenarioDraftSetRepository scenarioDrafts;
    private DatabaseScenarioPublicationRepository publications;
    private InMemoryGraphDraftRepository graphDrafts;
    private ContractDraftProjectionService contracts;
    private FakeRegistry registry;
    private ScenarioPublicationService service;
    private GraphDraft graph;
    private ContractDraft contract;
    private StoredScenarioDraftSet source;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        scenarioDrafts = new DatabaseScenarioDraftSetRepository(jdbc, objectMapper);
        scenarioDrafts.init();
        publications = new DatabaseScenarioPublicationRepository(jdbc, objectMapper);
        publications.init();
        graphDrafts = new InMemoryGraphDraftRepository();
        contracts = new ContractDraftProjectionService();
        graph = graphDrafts.save(ScenarioValidationServiceTest.graphDraft());
        contract = contracts.project(graph, VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, graph, MAX_TARGET_BYTES));
        source = scenarioDrafts.saveIfRevision(
                0, draftSet(ScenarioDraftSet.CaseType.GOLDEN), "author-a").orElseThrow();
        registry = new FakeRegistry(objectMapper, runtimeTarget(), scope());
        ScenarioValidationService validation = new ScenarioValidationService(objectMapper);
        service = new ScenarioPublicationService(
                scenarioDrafts,
                publications,
                graphDrafts,
                contracts,
                new ScenarioGovernedCompiler(validation, objectMapper),
                registry,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesReReadsAndReturnsOnlyPayloadFreeLineage() throws Exception {
        StoredScenarioPublication published = service.publish(
                source.scenarioDraftSetId(), source.revision(), publisher());

        assertThat(published.report().status())
                .isEqualTo(ScenarioPublicationReport.Status.PUBLISHED);
        assertThat(published.report().fixtures()).hasSize(1);
        assertThat(published.report().suite()).isNotNull();
        assertThat(published.report().attempt()).isEqualTo(1);
        assertThat(registry.fixtureRegisters).isEqualTo(1);
        assertThat(registry.fixtureReads).isEqualTo(1);
        assertThat(registry.suiteRegisters).isEqualTo(1);
        assertThat(registry.suiteReads).isEqualTo(1);
        assertThat(publications.history(scope(), published.report().publicationId()))
                .extracting(value -> value.report().status())
                .containsExactly(
                        ScenarioPublicationReport.Status.IN_PROGRESS,
                        ScenarioPublicationReport.Status.IN_PROGRESS,
                        ScenarioPublicationReport.Status.PUBLISHED);

        String receiptJson = objectMapper.writeValueAsString(published);
        assertThat(receiptJson)
                .doesNotContain(
                        "A-SENSITIVE-1",
                        "EXPECTED-DECISION-APPROVED",
                        "SCORE-PAYLOAD-720",
                        "decision-approved");

        StoredScenarioPublication retried = service.publish(
                source.scenarioDraftSetId(), source.revision(), publisher());
        assertThat(retried).isEqualTo(published);
        assertThat(registry.fixtureRegisters).isEqualTo(1);
        assertThat(registry.suiteRegisters).isEqualTo(1);
        assertThat(registry.fixtureReads).isEqualTo(2);
        assertThat(registry.suiteReads).isEqualTo(2);
    }

    @Test
    void convergesAfterSuiteRegistrationFailsFollowingFixturePublication() {
        registry.failNextSuiteRegistration = true;

        assertThatThrownBy(() -> service.publish(
                source.scenarioDraftSetId(), source.revision(), publisher()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.TEST.SUITE_STORE_UNAVAILABLE"));

        StoredScenarioPublication partial = onlyPublication();
        assertThat(partial.report().status())
                .isEqualTo(ScenarioPublicationReport.Status.PARTIAL);
        assertThat(partial.report().failure())
                .isEqualTo(new ScenarioPublicationReport.Failure(
                        "SUITE_REGISTER", "RG.TEST.SUITE_STORE_UNAVAILABLE", true));
        assertThat(partial.report().fixtures()).hasSize(1);

        StoredScenarioPublication recovered = service.publish(
                source.scenarioDraftSetId(), source.revision(), publisher());
        assertThat(recovered.report().status())
                .isEqualTo(ScenarioPublicationReport.Status.PUBLISHED);
        assertThat(recovered.report().attempt()).isEqualTo(2);
        assertThat(registry.fixtures).hasSize(1);
        assertThat(registry.suites).hasSize(1);
        assertThat(registry.fixtureRegisters).isEqualTo(2);
        assertThat(registry.suiteRegisters).isEqualTo(2);
    }

    @Test
    void rejectsARegistryReadThatDoesNotMatchTheJustRegisteredFixture() {
        registry.corruptFixtureRead = true;

        assertThatThrownBy(() -> service.publish(
                source.scenarioDraftSetId(), source.revision(), publisher()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.PUBLICATION_FIXTURE_VERIFY_FAILED"));

        StoredScenarioPublication failed = onlyPublication();
        assertThat(failed.report().status())
                .isEqualTo(ScenarioPublicationReport.Status.FAILED);
        assertThat(failed.report().failure().stage()).isEqualTo("FIXTURE_VERIFY");
        assertThat(failed.report().fixtures()).isEmpty();
        assertThat(registry.suiteRegisters).isZero();
    }

    @Test
    void persistsCompileDiagnosticsWithoutCreatingRegistryAssets() {
        source = scenarioDrafts.saveIfRevision(
                source.revision(), draftSet(ScenarioDraftSet.CaseType.PROPERTY), "author-a")
                .orElseThrow();

        assertThatThrownBy(() -> service.publish(
                source.scenarioDraftSetId(), source.revision(), publisher()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.PUBLICATION_COMPILE_FAILED"));

        StoredScenarioPublication failed = onlyPublication();
        assertThat(failed.report().diagnostics())
                .contains("visual.scenario.compile.propertyMaterializationRequired");
        assertThat(failed.report().status())
                .isEqualTo(ScenarioPublicationReport.Status.FAILED);
        assertThat(registry.fixtureRegisters).isZero();
        assertThat(registry.suiteRegisters).isZero();
    }

    @Test
    void requiresDedicatedPublisherPurposeAndExactRetainedRevision() {
        IntegrationRequestContext author = identity("TEST_SUITE_WRITE", "test");

        assertThatThrownBy(() -> service.publish(
                source.scenarioDraftSetId(), source.revision(), author))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.PUBLICATION_PURPOSE_FORBIDDEN"));
        assertThatThrownBy(() -> service.publish(
                source.scenarioDraftSetId(), 999, publisher()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.PUBLICATION_SOURCE_NOT_FOUND"));
        assertThatThrownBy(() -> service.publish(
                source.scenarioDraftSetId(), source.revision(),
                identity("TEST_SCENARIO_PUBLISH", "production")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.PUBLICATION_ENVIRONMENT_FORBIDDEN"));
    }

    @Test
    void repositorySurvivesRestartAndIsolatesCompleteScope() {
        StoredScenarioPublication published = service.publish(
                source.scenarioDraftSetId(), source.revision(), publisher());
        DatabaseScenarioPublicationRepository reloaded =
                new DatabaseScenarioPublicationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find(scope(), published.report().publicationId()))
                .contains(published);
        ScenarioDraftSet.EnterpriseScope other = new ScenarioDraftSet.EnterpriseScope(
                "tenant-a", "org-a", "other-project", "test", "sg");
        assertThat(reloaded.find(other, published.report().publicationId())).isEmpty();
    }

    private StoredScenarioPublication onlyPublication() {
        List<StoredScenarioPublication> candidates = publications.history(
                scope(),
                publicationId());
        assertThat(candidates).isNotEmpty();
        return candidates.getLast();
    }

    private String publicationId() {
        ScenarioGovernedCompilationPlan plan = new ScenarioGovernedCompiler(
                new ScenarioValidationService(objectMapper), objectMapper)
                .compile(graph, contract, source.draftSet(), runtimeTarget());
        String planFingerprint = ProtocolFingerprint.ofBounded(
                objectMapper, plan, 16 * 1_048_576);
        String fingerprint = ProtocolFingerprint.ofBounded(
                objectMapper,
                Map.of(
                        "sourceId", source.scenarioDraftSetId(),
                        "sourceRevision", source.revision(),
                        "sourceFingerprint", source.fingerprint(),
                        "runtimeTarget", runtimeTarget(),
                        "compilationPlanFingerprint", planFingerprint),
                16 * 1_048_576);
        String prefix = "scenario-publication-" + source.scenarioDraftSetId()
                + "-r" + source.revision();
        String digest = fingerprint.substring("sha256:".length());
        int prefixLimit = 255 - digest.length() - 1;
        return prefix.substring(0, Math.min(prefixLimit, prefix.length())) + "-" + digest;
    }

    private ScenarioDraftSet draftSet(ScenarioDraftSet.CaseType type) {
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "approved",
                "Approved applicant",
                "Controlled CRM response produces approval.",
                type,
                List.of("loan"),
                new ScenarioDraftSet.Given(
                        Map.of("applicantId", "A-SENSITIVE-1"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(new ScenarioDraftSet.DependencyBehaviorDraft(
                        "crm-return",
                        ScenarioDraftSet.DependencySelector.node("crm"),
                        ScenarioDraftSet.DependencyBehavior.returning(
                                Map.of("score", "SCORE-PAYLOAD-720")),
                        ScenarioDraftSet.Consumption.once(),
                        ScenarioDraftSet.SchemaCheck.strict(),
                        "AUTHORED")),
                new ScenarioDraftSet.Then(List.of(new ScenarioDraftSet.AssertionDraft(
                        "decision-approved",
                        ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                        "", "", "", "/decision",
                        ScenarioDraftSet.AssertionOperator.EQUALS,
                        "EXPECTED-DECISION-APPROVED", null))));
        return new ScenarioDraftSet(
                "", "loan-scenarios",
                source == null ? 0 : source.revision(),
                scope(), contract.target(), contract.fingerprint(objectMapper),
                List.of(scenario),
                new ScenarioDraftSet.Metadata(
                        "credit-platform", "INTERNAL", null, null, Map.of("source", "test")));
    }

    private IntegrationRequestContext publisher() {
        return identity("TEST_SCENARIO_PUBLISH", "test");
    }

    private IntegrationRequestContext identity(String purpose, String environment) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", environment, "sg",
                "WORKLOAD", "publisher-a", "", purpose, "correlation-a",
                java.util.Set.of(), "RESTRICTED", "");
    }

    private static ScenarioDraftSet.EnterpriseScope scope() {
        return new ScenarioDraftSet.EnterpriseScope(
                "tenant-a", "org-a", "project-a", "test", "sg");
    }

    private static TestExecutionApiRequest.Target runtimeTarget() {
        return new TestExecutionApiRequest.Target(
                "GRAPH", "loanPolicy", ScenarioValidationServiceTest.fingerprint('e'));
    }

    private static final class FakeRegistry implements ScenarioGovernedRegistryGateway {
        private final ObjectMapper objectMapper;
        private final TestExecutionApiRequest.Target target;
        private final ScenarioDraftSet.EnterpriseScope scope;
        private final Map<String, StoredFixtureBundle> fixtures = new LinkedHashMap<>();
        private final Map<String, StoredTestSuite> suites = new LinkedHashMap<>();
        private int fixtureRegisters;
        private int fixtureReads;
        private int suiteRegisters;
        private int suiteReads;
        private boolean failNextSuiteRegistration;
        private boolean corruptFixtureRead;
        private FakeRegistry(
                ObjectMapper objectMapper,
                TestExecutionApiRequest.Target target,
                ScenarioDraftSet.EnterpriseScope scope) {
            this.objectMapper = objectMapper;
            this.target = target;
            this.scope = scope;
        }

        @Override
        public TestExecutionApiRequest.Target describeGraphTarget(
                String graphName,
                IntegrationRequestContext identity) {
            return target;
        }

        @Override
        public StoredFixtureBundle registerFixture(
                String fixtureBundleId,
                FixtureBundleRegistrationRequest request,
                IntegrationRequestContext identity) {
            fixtureRegisters++;
            FixtureBundle bundle = request.fixtureBundle();
            String fingerprint = ProtocolFingerprint.of(objectMapper, bundle);
            StoredFixtureBundle stored = new StoredFixtureBundle(
                    "", scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), bundle.fixtureBundleId(), bundle.revision(),
                    fingerprint, bundle, NOW, identity.actorId());
            StoredFixtureBundle existing = fixtures.putIfAbsent(key(fixtureBundleId, bundle.revision()), stored);
            return existing == null ? stored : existing;
        }

        @Override
        public StoredFixtureBundle findFixture(
                String fixtureBundleId,
                long revision,
                IntegrationRequestContext identity) {
            fixtureReads++;
            StoredFixtureBundle stored = fixtures.get(key(fixtureBundleId, revision));
            if (!corruptFixtureRead || stored == null) {
                return stored;
            }
            return new StoredFixtureBundle(
                    stored.schemaVersion(), stored.tenantId(), stored.organizationId(),
                    stored.projectId(), stored.environmentId(), stored.region(),
                    stored.fixtureBundleId(), stored.revision(),
                    ScenarioValidationServiceTest.fingerprint('f'), stored.bundle(),
                    stored.createdAt(), stored.createdBy());
        }

        @Override
        public StoredTestSuite registerSuite(
                String suiteId,
                TestSuiteRegistrationRequest request,
                IntegrationRequestContext identity) {
            suiteRegisters++;
            if (failNextSuiteRegistration) {
                failNextSuiteRegistration = false;
                throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                        "RG.TEST.SUITE_STORE_UNAVAILABLE",
                        "Suite store unavailable.", identity.correlationId(), Map.of()));
            }
            TestSuiteProtocol suite = request.testSuite();
            StoredTestSuite stored = new StoredTestSuite(
                    "", scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), suite.suiteId(), suite.revision(),
                    ProtocolFingerprint.of(objectMapper, suite), suite, NOW, identity.actorId());
            StoredTestSuite existing = suites.putIfAbsent(key(suiteId, suite.revision()), stored);
            return existing == null ? stored : existing;
        }

        @Override
        public StoredTestSuite findSuite(
                String suiteId,
                long revision,
                IntegrationRequestContext identity) {
            suiteReads++;
            return suites.get(key(suiteId, revision));
        }

        private static String key(String id, long revision) {
            return id + "@" + revision;
        }
    }
}
