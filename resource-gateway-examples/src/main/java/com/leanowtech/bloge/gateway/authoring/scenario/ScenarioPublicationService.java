package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorExecutionLowering;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recoverable publication saga from one mutable Scenario revision to governed test assets.
 *
 * <p>There is intentionally no distributed transaction across authoring storage, fixture
 * registry, and suite registry. Content-addressed asset identities make external creates
 * idempotent, while a durable payload-free state machine records progress. Every successful write
 * is followed by an independent read and content check before the next stage is admitted.</p>
 */
public final class ScenarioPublicationService {

    private static final int MAX_TARGET_BYTES = 16 * 1_048_576;
    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");

    private final ScenarioDraftSetRepository scenarioDrafts;
    private final ScenarioPublicationRepository publications;
    private final GraphDraftRepository graphDrafts;
    private final VisualOperatorCatalog operators;
    private final ContractDraftProjectionService contracts;
    private final ScenarioGovernedCompiler compiler;
    private final ScenarioGovernedRegistryGateway registry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates a publisher using the system UTC clock.
     *
     * @param scenarioDrafts retained mutable source revisions
     * @param publications durable publication saga state
     * @param graphDrafts current visual graph target repository
     * @param operators current visual operator catalog
     * @param contracts current Contract projector
     * @param compiler deterministic governed compiler
     * @param registry authoritative testing-control-plane port
     * @param objectMapper canonical protocol serializer
     */
    public ScenarioPublicationService(
            ScenarioDraftSetRepository scenarioDrafts,
            ScenarioPublicationRepository publications,
            GraphDraftRepository graphDrafts,
            VisualOperatorCatalog operators,
            ContractDraftProjectionService contracts,
            ScenarioGovernedCompiler compiler,
            ScenarioGovernedRegistryGateway registry,
            ObjectMapper objectMapper) {
        this(scenarioDrafts, publications, graphDrafts, operators, contracts, compiler, registry,
                objectMapper, Clock.systemUTC());
    }

    /**
     * Injectable-clock constructor used by deterministic lifecycle tests.
     */
    ScenarioPublicationService(
            ScenarioDraftSetRepository scenarioDrafts,
            ScenarioPublicationRepository publications,
            GraphDraftRepository graphDrafts,
            VisualOperatorCatalog operators,
            ContractDraftProjectionService contracts,
            ScenarioGovernedCompiler compiler,
            ScenarioGovernedRegistryGateway registry,
            ObjectMapper objectMapper,
            Clock clock) {
        this.scenarioDrafts = Objects.requireNonNull(scenarioDrafts, "scenarioDrafts");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.graphDrafts = Objects.requireNonNull(graphDrafts, "graphDrafts");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Publishes one exact retained Scenario source revision.
     *
     * <p>Retrying the same source and runtime target converges on the same publication, fixture,
     * and suite identities. A previously completed publication is still independently re-read
     * before it is returned.</p>
     *
     * @param scenarioDraftSetId stable source id
     * @param sourceRevision exact retained source revision
     * @param identity verified publisher identity with a dedicated purpose
     * @return current durable publication report
     */
    public StoredScenarioPublication publish(
            String scenarioDraftSetId,
            long sourceRevision,
            IntegrationRequestContext identity) {
        requireIdentity(identity, true);
        ScenarioDraftSet.EnterpriseScope scope = scope(identity);
        StoredScenarioDraftSet source = scenarioDrafts.findRevision(
                        scope, normalized(scenarioDraftSetId), sourceRevision)
                .orElseThrow(() -> notFound(identity, "RG.SCENARIO.PUBLICATION_SOURCE_NOT_FOUND",
                        "The exact Scenario source revision was not found."));
        requireClearance(source.draftSet().metadata().classification(), identity);

        ResolvedAuthoringTarget authoringTarget =
                currentTarget(source.draftSet(), identity);
        GraphDraft graph = authoringTarget.graph();
        OperatorDefinition operator = authoringTarget.operator();
        ContractDraft contract = authoringTarget.contract();
        TestExecutionApiRequest.Target runtimeTarget = authoringTarget.runtimeTarget();
        ScenarioGovernedCompilationPlan plan =
                compiler.compile(graph, operator, contract, source.draftSet(), runtimeTarget);
        String planFingerprint = ProtocolFingerprint.ofBounded(
                objectMapper, plan, MAX_PROTOCOL_BYTES);
        String publicationId = publicationId(source, runtimeTarget, planFingerprint);
        ScenarioPublicationReport.SourceRef sourceRef = new ScenarioPublicationReport.SourceRef(
                source.scenarioDraftSetId(), source.revision(), source.fingerprint(),
                source.draftSet().target().kind().name(),
                source.draftSet().target().id(),
                source.draftSet().target().fingerprint(),
                source.draftSet().contractFingerprint(),
                plan.schemaVersion(), planFingerprint);

        StoredScenarioPublication state = publications.find(scope, publicationId).orElse(null);
        if (state != null) {
            requireSameCoordinate(state.report(), sourceRef, runtimeTarget, identity);
            if (state.report().status() == ScenarioPublicationReport.Status.PUBLISHED) {
                verifyCompleted(plan, state.report(), identity);
                return state;
            }
        }
        Instant now = clock.instant();
        ScenarioPublicationReport report = state == null
                ? new ScenarioPublicationReport(
                "", publicationId, scope, sourceRef, runtimeTarget,
                ScenarioPublicationReport.Status.IN_PROGRESS, 1, List.of(), null,
                diagnosticCodes(plan), ScenarioPublicationReport.Failure.none(),
                now, now, null, identity.actorId())
                : state.report().retry(now, identity.actorId());
        state = persist(state == null ? 0 : state.stateVersion(), report, identity);

        if (!plan.compiled()) {
            state = persistFailure(state, "COMPILE",
                    "RG.SCENARIO.PUBLICATION_COMPILE_FAILED", false, identity);
            throw badRequest(identity, "RG.SCENARIO.PUBLICATION_COMPILE_FAILED",
                    "Scenario publication was blocked by governed compilation.",
                    Map.of("diagnosticCodes", state.report().diagnostics()));
        }

        String stage = "FIXTURE_REGISTER";
        try {
            for (ScenarioGovernedCompilationPlan.CompiledFixture fixture : plan.fixtures()) {
                FixtureBundle expected = fixture.request().fixtureBundle();
                registry.registerFixture(expected.fixtureBundleId(), fixture.request(), identity);
                stage = "FIXTURE_VERIFY";
                StoredFixtureBundle verified = registry.findFixture(
                        expected.fixtureBundleId(), expected.revision(), identity);
                requireFixture(verified, expected, fixture.fingerprint(), identity);
                ScenarioPublicationReport.AssetRef ref = new ScenarioPublicationReport.AssetRef(
                        "FIXTURE_BUNDLE", verified.fixtureBundleId(), verified.revision(),
                        verified.fingerprint());
                state = persist(state.stateVersion(),
                        state.report().verifiedFixture(ref, clock.instant()), identity);
                stage = "FIXTURE_REGISTER";
            }

            TestSuiteProtocol expectedSuite = plan.suite().testSuite();
            String suiteFingerprint = ProtocolFingerprint.ofBounded(
                    objectMapper, expectedSuite, MAX_PROTOCOL_BYTES);
            stage = "SUITE_REGISTER";
            registry.registerSuite(expectedSuite.suiteId(), plan.suite(), identity);
            stage = "SUITE_VERIFY";
            StoredTestSuite verifiedSuite = registry.findSuite(
                    expectedSuite.suiteId(), expectedSuite.revision(), identity);
            requireSuite(verifiedSuite, expectedSuite, suiteFingerprint, identity);
            ScenarioPublicationReport.AssetRef suiteRef = new ScenarioPublicationReport.AssetRef(
                    "TEST_SUITE", verifiedSuite.suiteId(), verifiedSuite.revision(),
                    verifiedSuite.fingerprint());
            return persist(state.stateVersion(),
                    state.report().published(suiteRef, clock.instant()), identity);
        } catch (RuntimeException failure) {
            FailureCode code = failureCode(failure);
            persistFailureBestEffort(state, stage, code.code(), code.retryable(), identity);
            throw failure;
        }
    }

    /** Reads one payload-free publication report in the caller's exact scope. */
    public StoredScenarioPublication find(
            String publicationId,
            IntegrationRequestContext identity) {
        requireIdentity(identity, false);
        return publications.find(scope(identity), normalized(publicationId))
                .orElseThrow(() -> notFound(identity, "RG.SCENARIO.PUBLICATION_NOT_FOUND",
                        "Scenario publication report was not found in the authorized scope."));
    }

    /** Reads immutable saga transition history in ascending state-version order. */
    public List<StoredScenarioPublication> history(
            String publicationId,
            IntegrationRequestContext identity) {
        requireIdentity(identity, false);
        return publications.history(scope(identity), normalized(publicationId));
    }

    private void verifyCompleted(
            ScenarioGovernedCompilationPlan plan,
            ScenarioPublicationReport report,
            IntegrationRequestContext identity) {
        if (!plan.compiled() || report.suite() == null
                || report.fixtures().size() != plan.fixtures().size()) {
            throw unavailable(identity, "RG.SCENARIO.PUBLICATION_RECEIPT_INCONSISTENT",
                    "Completed publication state does not match the current deterministic plan.");
        }
        List<ScenarioPublicationReport.AssetRef> verifiedFixtures = new java.util.ArrayList<>();
        for (ScenarioGovernedCompilationPlan.CompiledFixture fixture : plan.fixtures()) {
            FixtureBundle expected = fixture.request().fixtureBundle();
            StoredFixtureBundle verified = registry.findFixture(
                    expected.fixtureBundleId(), expected.revision(), identity);
            requireFixture(verified, expected, fixture.fingerprint(), identity);
            verifiedFixtures.add(new ScenarioPublicationReport.AssetRef(
                    "FIXTURE_BUNDLE", verified.fixtureBundleId(), verified.revision(),
                    verified.fingerprint()));
        }
        List<ScenarioPublicationReport.AssetRef> normalizedFixtures = verifiedFixtures.stream()
                .sorted(Comparator.comparing(ScenarioPublicationReport.AssetRef::kind)
                        .thenComparing(ScenarioPublicationReport.AssetRef::id)
                        .thenComparingLong(ScenarioPublicationReport.AssetRef::revision))
                .toList();
        if (!report.fixtures().equals(normalizedFixtures)) {
            throw unavailable(identity, "RG.SCENARIO.PUBLICATION_RECEIPT_INCONSISTENT",
                    "Completed publication fixture receipts failed independent verification.");
        }
        TestSuiteProtocol expectedSuite = plan.suite().testSuite();
        StoredTestSuite verifiedSuite = registry.findSuite(
                expectedSuite.suiteId(), expectedSuite.revision(), identity);
        requireSuite(verifiedSuite, expectedSuite,
                ProtocolFingerprint.ofBounded(objectMapper, expectedSuite, MAX_PROTOCOL_BYTES),
                identity);
        ScenarioPublicationReport.AssetRef actualSuite = new ScenarioPublicationReport.AssetRef(
                "TEST_SUITE", verifiedSuite.suiteId(), verifiedSuite.revision(),
                verifiedSuite.fingerprint());
        if (!actualSuite.equals(report.suite())) {
            throw unavailable(identity, "RG.SCENARIO.PUBLICATION_RECEIPT_INCONSISTENT",
                    "Completed publication suite receipt failed independent verification.");
        }
    }

    private void requireFixture(
            StoredFixtureBundle actual,
            FixtureBundle expected,
            String expectedFingerprint,
            IntegrationRequestContext identity) {
        String actualFingerprint = actual == null || actual.bundle() == null
                ? ""
                : ProtocolFingerprint.ofBounded(
                objectMapper, actual.bundle(), MAX_PROTOCOL_BYTES);
        if (actual == null
                || !actual.fixtureBundleId().equals(expected.fixtureBundleId())
                || actual.revision() != expected.revision()
                || !actual.fingerprint().equals(expectedFingerprint)
                || !actualFingerprint.equals(expectedFingerprint)) {
            throw unavailable(identity, "RG.SCENARIO.PUBLICATION_FIXTURE_VERIFY_FAILED",
                    "Registered fixture failed independent content verification.");
        }
    }

    private void requireSuite(
            StoredTestSuite actual,
            TestSuiteProtocol expected,
            String expectedFingerprint,
            IntegrationRequestContext identity) {
        String actualFingerprint = actual == null || actual.suite() == null
                ? ""
                : ProtocolFingerprint.ofBounded(
                objectMapper, actual.suite(), MAX_PROTOCOL_BYTES);
        if (actual == null
                || !actual.suiteId().equals(expected.suiteId())
                || actual.revision() != expected.revision()
                || !actual.fingerprint().equals(expectedFingerprint)
                || !actualFingerprint.equals(expectedFingerprint)) {
            throw unavailable(identity, "RG.SCENARIO.PUBLICATION_SUITE_VERIFY_FAILED",
                    "Registered suite failed independent content verification.");
        }
    }

    private StoredScenarioPublication persist(
            long expectedVersion,
            ScenarioPublicationReport report,
            IntegrationRequestContext identity) {
        return publications.saveIfVersion(expectedVersion, report)
                .orElseThrow(() -> new IntegrationProblemException(
                        IntegrationProblem.retryableConflict(
                                "RG.SCENARIO.PUBLICATION_CONCURRENT_UPDATE",
                                "Another publisher advanced the same publication.",
                                identity.correlationId(), Map.of())));
    }

    private StoredScenarioPublication persistFailure(
            StoredScenarioPublication state,
            String stage,
            String code,
            boolean retryable,
            IntegrationRequestContext identity) {
        return persist(state.stateVersion(),
                state.report().failed(stage, code, retryable, clock.instant()), identity);
    }

    private void persistFailureBestEffort(
            StoredScenarioPublication state,
            String stage,
            String code,
            boolean retryable,
            IntegrationRequestContext identity) {
        try {
            persistFailure(state, stage, code, retryable, identity);
        } catch (RuntimeException concurrentOrUnavailable) {
            // The original registry failure remains authoritative; retry reconstructs exact state.
        }
    }

    private ResolvedAuthoringTarget currentTarget(
            ScenarioDraftSet draftSet,
            IntegrationRequestContext identity) {
        if (draftSet.target().kind() == ContractDraft.TargetKind.OPERATOR) {
            OperatorDefinition operator = operators.find(draftSet.target().id())
                    .orElseThrow(() -> notFound(
                            identity,
                            "RG.SCENARIO.PUBLICATION_TARGET_NOT_FOUND",
                            "The current operator target was not found."));
            requireOperatorScope(operator, identity);
            return new ResolvedAuthoringTarget(
                    null,
                    operator,
                    contracts.project(operator),
                    registry.describeOperatorTarget(
                            OperatorExecutionLowering.runtimeOperatorRef(operator), identity));
        }
        GraphDraft graph = graphDrafts.find(draftSet.target().id())
                .orElseThrow(() -> notFound(identity, "RG.SCENARIO.PUBLICATION_TARGET_NOT_FOUND",
                        "The current visual graph target was not found."));
        if (!graph.tenantId().equals(identity.tenantId())
                || !graph.environment().equals(identity.environmentId())) {
            throw notFound(identity, "RG.SCENARIO.PUBLICATION_TARGET_NOT_FOUND",
                    "The current visual graph target was not found.");
        }
        String visualTargetFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, graph, MAX_TARGET_BYTES);
        return new ResolvedAuthoringTarget(
                graph,
                null,
                contracts.project(graph, visualTargetFingerprint),
                registry.describeGraphTarget(graph.graphName(), identity));
    }

    private static void requireOperatorScope(
            OperatorDefinition operator,
            IntegrationRequestContext identity) {
        if (!OperatorScenarioScope.allows(operator, identity)) {
            throw notFound(identity, "RG.SCENARIO.PUBLICATION_TARGET_NOT_FOUND",
                    "The current operator target was not found.");
        }
    }

    private static void requireSameCoordinate(
            ScenarioPublicationReport report,
            ScenarioPublicationReport.SourceRef source,
            TestExecutionApiRequest.Target target,
            IntegrationRequestContext identity) {
        if (!report.source().equals(source) || !report.runtimeTarget().equals(target)) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.SCENARIO.PUBLICATION_COORDINATE_CONFLICT",
                    "Existing publication state identifies different exact inputs.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireIdentity(
            IntegrationRequestContext identity,
            boolean publishing) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(Locale.ROOT))
                || identity.projectId().isBlank() || identity.region().isBlank()) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO.PUBLICATION_ENVIRONMENT_FORBIDDEN",
                    "Scenario publication is restricted to complete test or staging identities.",
                    identity.correlationId(), Map.of()));
        }
        if (publishing && !"TEST_SCENARIO_PUBLISH".equals(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO.PUBLICATION_PURPOSE_FORBIDDEN",
                    "Scenario publication requires the dedicated TEST_SCENARIO_PUBLISH purpose.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireClearance(
            String classification,
            IntegrationRequestContext identity) {
        if (!identity.hasClearanceAtLeast(classification)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO.PUBLICATION_CLEARANCE_FORBIDDEN",
                    "Publisher clearance cannot access the Scenario classification.",
                    identity.correlationId(), Map.of()));
        }
    }

    private String publicationId(
            StoredScenarioDraftSet source,
            TestExecutionApiRequest.Target target,
            String planFingerprint) {
        String fingerprint = ProtocolFingerprint.ofBounded(
                objectMapper,
                Map.of(
                        "sourceId", source.scenarioDraftSetId(),
                        "sourceRevision", source.revision(),
                        "sourceFingerprint", source.fingerprint(),
                        "runtimeTarget", target,
                        "compilationPlanFingerprint", planFingerprint),
                MAX_PROTOCOL_BYTES);
        return contentAddressedId(
                "scenario-publication-" + source.scenarioDraftSetId()
                        + "-r" + source.revision(),
                fingerprint.substring("sha256:".length()));
    }

    private static List<String> diagnosticCodes(ScenarioGovernedCompilationPlan plan) {
        return plan.diagnostics().stream().map(diagnostic -> diagnostic.code())
                .distinct().sorted().toList();
    }

    private static FailureCode failureCode(RuntimeException failure) {
        if (failure instanceof IntegrationProblemException integration) {
            return new FailureCode(
                    integration.problem().code(), integration.problem().retryable());
        }
        return new FailureCode("RG.SCENARIO.PUBLICATION_STAGE_FAILED", true);
    }

    private static ScenarioDraftSet.EnterpriseScope scope(IntegrationRequestContext identity) {
        return new ScenarioDraftSet.EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static String contentAddressedId(String prefix, String digest) {
        String normalizedPrefix = normalized(prefix).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        String normalizedDigest = normalized(digest).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-f0-9]+", "");
        int prefixLimit = Math.max(0, 255 - normalizedDigest.length() - 1);
        String boundedPrefix = normalizedPrefix.substring(
                0, Math.min(prefixLimit, normalizedPrefix.length()));
        return boundedPrefix + "-" + normalizedDigest;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private record FailureCode(String code, boolean retryable) {
    }

    private record ResolvedAuthoringTarget(
            GraphDraft graph,
            OperatorDefinition operator,
            ContractDraft contract,
            TestExecutionApiRequest.Target runtimeTarget) {
    }
}
