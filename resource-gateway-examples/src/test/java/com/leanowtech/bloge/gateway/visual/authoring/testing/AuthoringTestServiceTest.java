package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftRepository;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionAssertion;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionBindingStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCase;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCaseKind;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCaseStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionSuite;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunRequest;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestCase;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestDraftRequest;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestService;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringTestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ObjectMapper yaml = new YAMLMapper().findAndRegisterModules();
    private AuthoringDraftService drafts;
    private AuthoringTestService tests;
    private AuthoringDraft stored;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        drafts = new AuthoringDraftService(
                new InMemoryAuthoringDraftRepository(),
                new AuthoringPreviewService(
                        new AuthoringCompiler(mapper, new OperatorLibraryValidator()),
                        libraries,
                        mapper),
                libraries,
                mapper);
        VisualOperatorContractTestService operatorTests =
                new VisualOperatorContractTestService(
                        VisualCatalogTestSupport.catalogWithLibrary(
                                VisualCatalogTestSupport.eligibilityLibrary("integer")),
                        new JsonSchemaSampleGenerator(),
                        mapper);
        tests = new AuthoringTestService(drafts, operatorTests, mapper);
        stored = drafts.save(
                "authoring-tests",
                0,
                "quick",
                document(),
                "alice");
    }

    @AfterEach
    void tearDown() {
        tests.close();
    }

    @Test
    void generatesAndRunsOperatorContractEvidenceAgainstTheExactDraft() {
        var generated = tests.draftOperator(
                stored.draftId(),
                stored.revision(),
                new OperatorDraftRequest(
                        OperatorDraftRequest.SCHEMA_VERSION,
                        new VisualOperatorContractTestDraftRequest(
                                VisualOperatorContractTestDraftRequest.SCHEMA_VERSION,
                                "demo:echo",
                                "generated draft case",
                                true,
                                Map.of(),
                                Map.of(),
                                Map.of())));

        assertThat(generated.suite().cases()).hasSize(1);
        assertThat(generated.suite().cases().getFirst().inputs())
                .containsEntry("profile", null);
        assertThat(generated.suiteFingerprint()).startsWith("sha256:");
        assertThat(generated.artifactFingerprint()).startsWith("sha256:");
        assertThat(generated.payloadPersisted()).isFalse();

        var evidence = tests.runOperator(
                stored.draftId(),
                stored.revision(),
                new OperatorRunRequest(
                        OperatorRunRequest.SCHEMA_VERSION,
                        generated.suite()));

        assertThat(evidence.result().passed()).isTrue();
        assertThat(evidence.result().mode().name()).isEqualTo("SCHEMA_CONTRACT");
        assertThat(evidence.suiteFingerprint()).isEqualTo(generated.suiteFingerprint());
        assertThat(evidence.evidenceFingerprint()).startsWith("sha256:");
        assertThat(evidence.payloadPersisted()).isFalse();
    }

    @Test
    void rejectsOperatorSuiteWhoseTargetIsAbsentFromTheExactDraft() {
        OperatorRunRequest request = new OperatorRunRequest(
                OperatorRunRequest.SCHEMA_VERSION,
                new VisualOperatorContractTestSuiteRequest(
                        "demo:missing",
                        List.of(new VisualOperatorContractTestCase(
                                "wrong target",
                                Map.of("request", "hello"),
                                Map.of(),
                                Map.of("result", "hello"),
                                Map.of()))));

        assertThatThrownBy(() -> tests.runOperator(
                stored.draftId(),
                stored.revision(),
                request))
                .isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                        assertThat(exception.problem().code())
                                .isEqualTo("RG.AUTHORING.OPERATOR_TEST_TARGET_NOT_FOUND"));
    }

    @Test
    void rejectsEmptyOperatorSuiteConsistentlyWithTheWireSchema() {
        OperatorRunRequest request = new OperatorRunRequest(
                OperatorRunRequest.SCHEMA_VERSION,
                new VisualOperatorContractTestSuiteRequest("demo:echo", List.of()));

        assertThatThrownBy(() -> tests.runOperator(
                stored.draftId(),
                stored.revision(),
                request))
                .isInstanceOfSatisfying(AuthoringLifecycleException.class, exception -> {
                    assertThat(exception.problem().status()).isEqualTo(400);
                    assertThat(exception.problem().code())
                            .isEqualTo("RG.AUTHORING.OPERATOR_TEST_CASE_COUNT_INVALID");
                });
    }

    @Test
    void runsOnlyBoundPureServiceFreeRuntimeFunctions() {
        var draft = tests.draftFunction(
                stored.draftId(),
                stored.revision(),
                new FunctionDraftRequest(
                        FunctionDraftRequest.SCHEMA_VERSION,
                        "trim"));

        assertThat(draft.bindingStatus()).isEqualTo(FunctionBindingStatus.BOUND);
        assertThat(draft.runtimeFingerprint()).startsWith("sha256:");
        assertThat(draft.suite().cases()).singleElement()
                .satisfies(testCase -> assertThat(testCase.assertion())
                        .isEqualTo(FunctionAssertion.RETURN_TYPE));

        var evidence = tests.runFunction(
                stored.draftId(),
                stored.revision(),
                new FunctionRunRequest(
                        FunctionRunRequest.SCHEMA_VERSION,
                        draft.suite()));

        assertThat(evidence.passed()).isTrue();
        assertThat(evidence.results()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(FunctionCaseStatus.PASSED);
            assertThat(result.actual()).isEqualTo("sample");
            assertThat(result.actualType()).isEqualTo("string");
        });
        assertThat(evidence.functionFingerprint()).startsWith("sha256:");
        assertThat(evidence.runtimeFingerprint()).isEqualTo(draft.runtimeFingerprint());
        assertThat(evidence.payloadPersisted()).isFalse();
    }

    @Test
    void returnsAssertionFailureWithoutLeakingArgumentsIntoDiagnostics() {
        FunctionSuite suite = new FunctionSuite(
                FunctionSuite.SCHEMA_VERSION,
                "trim",
                List.of(new FunctionCase(
                        FunctionCase.SCHEMA_VERSION,
                        "wrong golden",
                        FunctionCaseKind.GOLDEN,
                        List.of(" secret-customer-value "),
                        FunctionAssertion.EQUALS,
                        "different",
                        null)));

        var evidence = tests.runFunction(
                stored.draftId(),
                stored.revision(),
                new FunctionRunRequest(FunctionRunRequest.SCHEMA_VERSION, suite));

        assertThat(evidence.passed()).isFalse();
        assertThat(evidence.results()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(FunctionCaseStatus.ASSERTION_FAILED);
            assertThat(result.actual()).isEqualTo("secret-customer-value");
            assertThat(result.diagnostics())
                    .allSatisfy(diagnostic -> assertThat(diagnostic.message())
                            .doesNotContain("secret-customer-value"));
        });
    }

    @Test
    void reportsUnboundAndEnvironmentDependentFunctionsWithoutInvokingThem() {
        var unbound = tests.draftFunction(
                stored.draftId(),
                stored.revision(),
                new FunctionDraftRequest(
                        FunctionDraftRequest.SCHEMA_VERSION,
                        "teamNormalize"));
        var blocked = tests.draftFunction(
                stored.draftId(),
                stored.revision(),
                new FunctionDraftRequest(
                        FunctionDraftRequest.SCHEMA_VERSION,
                        "now"));

        assertThat(unbound.bindingStatus()).isEqualTo(FunctionBindingStatus.UNBOUND);
        assertThat(unbound.diagnostics())
                .extracting("code")
                .contains("visual.authoring.functionTest.runtimeUnbound");
        assertThat(blocked.bindingStatus()).isEqualTo(FunctionBindingStatus.BLOCKED_BY_POLICY);
        assertThat(blocked.diagnostics())
                .extracting("code")
                .contains("visual.authoring.functionTest.runtimeBlocked");

        var evidence = tests.runFunction(
                stored.draftId(),
                stored.revision(),
                new FunctionRunRequest(
                        FunctionRunRequest.SCHEMA_VERSION,
                        unbound.suite()));
        assertThat(evidence.passed()).isFalse();
        assertThat(evidence.results()).singleElement()
                .satisfies(result -> assertThat(result.status())
                        .isEqualTo(FunctionCaseStatus.NOT_RUN));
    }

    @Test
    void rejectsStaleDraftRevisionBeforeRunningFixtures() {
        drafts.save(
                stored.draftId(),
                stored.revision(),
                stored.sourceMode(),
                stored.document(),
                "bob");

        assertThatThrownBy(() -> tests.draftFunction(
                stored.draftId(),
                stored.revision(),
                new FunctionDraftRequest(
                        FunctionDraftRequest.SCHEMA_VERSION,
                        "trim")))
                .isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                        assertThat(exception.problem().code())
                                .isEqualTo("RG.AUTHORING.DRAFT_REVISION_STALE"));
    }

    private VisualLibraryAuthoringDocument document() throws Exception {
        return yaml.readValue("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library:
                  id: authoring-tests
                  name: Authoring Tests
                  version: 1.0.0
                  owner: platform-quality
                operators:
                  demo:echo:
                    name: Echo
                    archetype: pure
                    input:
                      profile?: any
                      request: string
                    output:
                      result: string
                functions:
                  trim:
                    description: Trim text with the BLOGE runtime callable.
                    signatures:
                      - "(text: string) -> string"
                  teamNormalize:
                    description: A documented custom function without a runtime binding.
                    signatures:
                      - "(text: string) -> string"
                  now:
                    description: Runtime function that requires the TIME service.
                    signatures:
                      - "() -> string"
                """, VisualLibraryAuthoringDocument.class);
    }

    private static final class InMemoryAuthoringDraftRepository
            implements AuthoringDraftRepository {
        private final Map<String, AuthoringDraft> current = new LinkedHashMap<>();
        private final Map<String, List<AuthoringDraft>> history = new LinkedHashMap<>();

        @Override
        public Collection<AuthoringDraft> all() {
            return List.copyOf(current.values());
        }

        @Override
        public Optional<AuthoringDraft> find(String draftId) {
            return Optional.ofNullable(current.get(draftId));
        }

        @Override
        public List<AuthoringDraft> revisions(String draftId) {
            return history.getOrDefault(draftId, List.of()).reversed();
        }

        @Override
        public synchronized Optional<AuthoringDraft> saveIfRevision(
                long expectedRevision,
                AuthoringDraft candidate,
                String actor) {
            AuthoringDraft existing = current.get(candidate.draftId());
            if ((existing == null && expectedRevision != 0)
                    || (existing != null && existing.revision() != expectedRevision)) {
                return Optional.empty();
            }
            java.time.Instant now = java.time.Instant.now();
            AuthoringDraft stored = candidate.withStorageIdentity(
                    candidate.draftId(),
                    expectedRevision + 1,
                    "sha256:draft-" + (expectedRevision + 1),
                    existing == null ? now : existing.createdAt(),
                    now,
                    actor);
            current.put(stored.draftId(), stored);
            List<AuthoringDraft> revisions =
                    new java.util.ArrayList<>(history.getOrDefault(stored.draftId(), List.of()));
            revisions.add(stored);
            history.put(stored.draftId(), List.copyOf(revisions));
            return Optional.of(stored);
        }
    }
}
