package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionSource;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorFixtureScopeBinding;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.TestDslMutationPlanner;
import com.leanowtech.bloge.gateway.testing.protocol.TestAssetReference;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlEnvelope;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.gateway.testing.world.WorldReferenceExecutionPlanner;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioRunService;
import com.leanowtech.bloge.gateway.testing.world.access.AuthorizedWorldAssetResolver;
import com.leanowtech.bloge.gateway.testing.world.access.GovernedAssetAccessException;
import com.leanowtech.bloge.gateway.testing.world.access.ResolvedWorldAssetControl;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestExecutionApiServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ResourceRegistry resources = new EmptyResourceRegistry();
    private final InMemoryFixtures fixtures = new InMemoryFixtures();
    private final InMemoryRuns runs = new InMemoryRuns();
    private final InMemorySecurityEvents securityEvents = new InMemorySecurityEvents();
    private Graph graph;
    private GatewayGraphService graphService;
    private TestExecutionApiService service;
    private TestReplayPayloadService replayPayloadService;
    private String targetFingerprint;

    @BeforeEach
    void setUp() {
        Operator<Object, Object> operator = (input, context) -> Map.of("echo", input);
        graph = new GraphBuilder("controlled-graph")
                .node("subject", operator).input((results, context) -> context.get("input"))
                .build().withDefinitionSource(new GraphDefinitionSource(
                        "1.0.0", "bloge-dsl-json", "{\"name\":\"controlled-graph\"}"));
        graphService = mock(GatewayGraphService.class);
        when(graphService.requireGraph("controlled-graph")).thenReturn(graph);
        when(graphService.requireContract("controlled-graph")).thenReturn(
                new com.leanowtech.bloge.gateway.gateway.GatewayGraphContract(
                        "controlled-graph", SchemaEnvelope.object(Map.of(
                                "input", Map.of("type", "string", "minLength", 2)),
                                List.of("input")), null, List.of("subject")));
        doNothing().when(graphService).validateInput(org.mockito.ArgumentMatchers.eq("controlled-graph"),
                org.mockito.ArgumentMatchers.any());
        replayPayloadService = mock(TestReplayPayloadService.class);
        service = new TestExecutionApiService(graphService, new DefaultOperatorRegistry(), resources,
                new BlgeExpressionEvaluator(), mapper, fixtures, runs, securityEvents,
                Duration.ofDays(7), replayPayloadService,
                new TestEvidenceIntegrityService(mapper, new InMemoryVisualEvidenceSigner()));
        targetFingerprint = GraphExecutionTargetSnapshot.capture(mapper, graph, resources).fingerprint();
    }

    @Test
    void inlineExecutionIsExploratoryPersistsSanitizedFullEvidenceAndProjectsResponse() {
        FixtureRule fixture = new FixtureRule(FixtureRule.SCHEMA_VERSION, "secret-output",
                FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of("password", "do-not-store", "result", "ok")),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        TestExecutionApiRequest request = request(bundle("inline", fixture), null,
                TestExecutionApiRequest.Verbosity.STANDARD);

        TestExecutionApiResponse response = service.execute(request, identity("test"));

        assertThat(response.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
        assertThat(response.evidence().evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(response.integrity().signatureStatus())
                .isEqualTo(TestEvidenceIntegrity.SignatureStatus.VERIFIED);
        assertThat(response.integrity().projection()).isEqualTo(TestEvidenceIntegrity.Projection.STANDARD);
        assertThat(response.integrity().independentlyVerifiable()).isFalse();
        assertThat(response.evidence().nodeTrace()).singleElement().satisfies(node -> {
            assertThat(node.input()).isNull();
            assertThat(node.output()).isNull();
            assertThat(node.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.input()).isNull();
                assertThat(attempt.output()).isNull();
            });
        });
        TestRunRecord persisted = runs.find("tenant-a", "test", response.runId()).orElseThrow();
        assertThat(persisted.integrity().independentlyVerifiable()).isTrue();
        assertThat(persisted.evidence().metadata()).containsEntry("payloadSanitized", true);
        assertThat(persisted.evidence().nodeTrace()).singleElement().satisfies(node -> {
            assertThat(node.output()).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) node.output()).get("password")).isEqualTo("[REDACTED]");
            assertThat(String.valueOf(node.output())).doesNotContain("do-not-store");
            assertThat(node.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.output()).isInstanceOf(Map.class);
                assertThat(String.valueOf(attempt.output())).contains("[REDACTED]")
                        .doesNotContain("do-not-store");
            });
        });
        assertThat(response.plan().authorizedPurpose()).isEqualTo("GRAPH_CONTRACT_TEST");
    }

    @Test
    void persistedEvidenceTamperingIsRejectedAndAuditedBeforeProjection() {
        TestExecutionApiResponse executed = service.execute(request(bundle("inline"), null,
                TestExecutionApiRequest.Verbosity.FULL), identity("test"));
        TestRunRecord original = runs.values.get(executed.runId());
        TestRunEvidence evidence = original.evidence();
        TestRunEvidence tampered = new TestRunEvidence(evidence.schemaVersion(), evidence.runId(),
                TestRunEvidence.Status.EXECUTION_FAILED, evidence.evidenceClass(),
                evidence.executionPurpose(), evidence.targetFingerprint(),
                evidence.fixtureBundleFingerprint(), evidence.planFingerprint(), evidence.startedAt(),
                evidence.completedAt(), evidence.nodeTrace(), evidence.edgeTrace(),
                evidence.fixtureConsumptions(), evidence.assertionResults(), evidence.diagnostics(),
                evidence.metadata());
        runs.values.put(executed.runId(), new TestRunRecord(original.runId(), original.tenantId(),
                original.organizationId(), original.projectId(), original.environmentId(), original.actorId(),
                original.target(), original.fixtureBundleRef(), original.requestedVerbosity(), original.plan(),
                tampered, original.integrity(), original.createdAt(), original.expiresAt()));

        assertThatThrownBy(() -> service.find(executed.runId(), TestExecutionApiRequest.Verbosity.FULL,
                identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.TEST.EVIDENCE_INTEGRITY_INVALID"));
        assertThat(securityEvents.events).extracting(TestSecurityEvent::eventType)
                .contains("TEST_EVIDENCE_INTEGRITY_INVALID");
    }

    @Test
    void storageEnvelopeIntegrityFailureIsRejectedAndAuditedBeforeProjection() {
        TestExecutionApiResponse executed = service.execute(request(bundle("inline"), null,
                TestExecutionApiRequest.Verbosity.FULL), identity("test"));
        runs.failReadsWithIntegrity = true;

        assertThatThrownBy(() -> service.find(executed.runId(),
                TestExecutionApiRequest.Verbosity.FULL, identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.EVIDENCE_INTEGRITY_INVALID");
                    assertThat(failure.problem().status()).isEqualTo(409);
                });
        assertThat(securityEvents.events).extracting(TestSecurityEvent::eventType)
                .contains("TEST_EVIDENCE_INTEGRITY_INVALID");
    }

    @Test
    void signerOutageMakesOtherwisePassingEvidenceIncompleteAndNonCertifiable() {
        TestExecutionApiService unavailable = new TestExecutionApiService(graphService,
                new DefaultOperatorRegistry(), resources, new BlgeExpressionEvaluator(), mapper,
                fixtures, new InMemoryRuns(), securityEvents, Duration.ofDays(7), replayPayloadService,
                new TestEvidenceIntegrityService(mapper, VisualEvidenceSigner.unavailable()));

        TestExecutionApiResponse response = unavailable.execute(request(bundle("inline"), null,
                TestExecutionApiRequest.Verbosity.FULL), identity("test"));

        assertThat(response.evidence().status()).isEqualTo(TestRunEvidence.Status.EVIDENCE_INCOMPLETE);
        assertThat(response.evidence().evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(response.integrity().signatureStatus())
                .isEqualTo(TestEvidenceIntegrity.SignatureStatus.VERIFICATION_UNAVAILABLE);
        assertThat(response.evidence().diagnostics())
                .anyMatch(value -> value.contains(TestEvidenceIntegrityService.SIGNER_UNAVAILABLE));
    }

    @Test
    void targetDiscoveryExposesTheExactFingerprintAndGraphContractNeededByFixtureAuthoring() {
        TestGraphTargetDescriptor descriptor = service.describeGraphTarget("controlled-graph", identity("test"));

        assertThat(descriptor.target().fingerprint()).isEqualTo(targetFingerprint);
        assertThat(descriptor.contract().graphName()).isEqualTo("controlled-graph");
        assertThat(descriptor.contract().outputNodes()).containsExactly("subject");
        assertThat(descriptor.dependencyPolicy()).isEqualTo("CONSERVATIVE_ALL_REGISTERED");
        assertThat(descriptor.certificationEligible()).isTrue();
        assertThat(descriptor.certificationGaps()).isEmpty();
    }

    @Test
    void preflightUsesTheExecutionPlannerWithoutRunningOrLeakingFixturePayload() throws Exception {
        FixtureBundle fixture = bundle("preflight", new FixtureRule(
                FixtureRule.SCHEMA_VERSION, "controlled-subject",
                FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of(
                        "result", "ok", "password", "must-not-leak")),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict()));
        StoredFixtureBundle stored = service.registerFixture("preflight",
                new FixtureBundleRegistrationRequest("", target(), fixture), identity("test"));

        TestExecutionPreflightResponse response = service.preflight(request(
                null, new TestExecutionApiRequest.FixtureBundleRef(
                        stored.fixtureBundleId(), stored.revision(), stored.fingerprint()),
                TestExecutionApiRequest.Verbosity.STANDARD), identity("test"));

        assertThat(response.schemaVersion())
                .isEqualTo(TestExecutionPreflightResponse.SCHEMA_VERSION);
        assertThat(response.target().fingerprint()).isEqualTo(targetFingerprint);
        assertThat(response.fixtureBundleRef().fingerprint()).isEqualTo(stored.fingerprint());
        assertThat(response.effectivePlan().authorizedPurpose())
                .isEqualTo(TestExecutionApiService.AUTHORIZED_PURPOSE);
        assertThat(response.effectivePlan().resolvedSites()).singleElement().satisfies(site -> {
            assertThat(site.invocationSiteId()).isEqualTo("/root/subject#PRIMARY");
            assertThat(site.resolution().name()).isEqualTo("TEST_DOUBLE");
            assertThat(site.ruleRefs()).containsExactly("controlled-subject");
        });
        assertThat(response.invocationSites()).singleElement().satisfies(site -> {
            assertThat(site.site().nodeId()).isEqualTo("subject");
            assertThat(site.site().operatorRef()).isNotBlank();
            assertThat(site.sideEffectType()).isEqualTo("MIXED");
        });
        assertThat(response.rulePolicies()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("controlled-subject");
            assertThat(rule.behavior()).isEqualTo(FixtureRule.BehaviorKind.RETURN);
            assertThat(rule.onUnmatched()).isEqualTo(FixtureRule.UnmatchedAction.FAIL);
            assertThat(rule.onExhausted()).isEqualTo(FixtureRule.ExhaustedAction.FAIL);
        });
        assertThat(runs.values).isEmpty();
        assertThat(mapper.writeValueAsString(response)).doesNotContain("must-not-leak");
    }

    @Test
    void graphBoundaryPlanIsValidatorProvenAndBoundToTheCurrentTarget() {
        TestBoundaryCasePlan plan = service.planGraphBoundaryCases(
                "controlled-graph", identity("test"));
        TestSchemaAdmissionTarget resolved = service.resolveSchemaAdmissionTarget(
                new TestExecutionApiRequest.Target("GRAPH", "controlled-graph", "stale"),
                identity("test"));

        assertThat(plan.schemaVersion()).isEqualTo(TestBoundaryCasePlan.SCHEMA_VERSION);
        assertThat(plan.target()).isEqualTo(target());
        assertThat(resolved.target()).isEqualTo(plan.target());
        assertThat(resolved.boundaryPlan()).isEqualTo(plan);
        assertThat(ProtocolFingerprint.of(mapper, resolved.inputSchema()))
                .isEqualTo(plan.inputSchemaFingerprint());
        assertThat(plan.inputSchemaFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(plan.planFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(plan.status()).isEqualTo(TestBoundaryCasePlan.Status.GENERATED);
        assertThat(plan.cases()).extracting(TestBoundaryCasePlan.BoundaryCase::kind)
                .contains(TestBoundaryCasePlan.BoundaryKind.BASELINE,
                        TestBoundaryCasePlan.BoundaryKind.REQUIRED_PROPERTY_MISSING,
                        TestBoundaryCasePlan.BoundaryKind.MIN_LENGTH,
                        TestBoundaryCasePlan.BoundaryKind.BELOW_MIN_LENGTH);
    }

    @Test
    void governedStoredFixtureCanProduceCertifiableEvidenceAndIsTenantScoped() {
        FixtureBundle bundle = bundle("stored", new FixtureRule(FixtureRule.SCHEMA_VERSION, "fixed",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.returning(Map.of("result", "ok")),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict()));
        StoredFixtureBundle stored = service.registerFixture("stored",
                new FixtureBundleRegistrationRequest("", target(), bundle), identity("test"));

        TestExecutionApiResponse response = service.execute(request(null,
                new TestExecutionApiRequest.FixtureBundleRef("stored", 1, stored.fingerprint()),
                TestExecutionApiRequest.Verbosity.FULL), identity("test"));

        assertThat(response.fixtureBundleRef().source()).isEqualTo("STORED");
        assertThat(response.evidence().evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.CERTIFIABLE);
        assertThat(service.find(response.runId(), TestExecutionApiRequest.Verbosity.SUMMARY, identity("test"))
                .evidence().nodeTrace()).isEmpty();
        assertThatThrownBy(() -> service.findFixture("stored", 1, identity("test", "tenant-b")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().status())
                        .isEqualTo(404));
    }

    @Test
    void fixtureRegistrationCreatesAFullScopeMirrorAuthorizationWhenMirrorIsAssembled() {
        MirrorFixtureScopeRepository scopeRepository = mock(MirrorFixtureScopeRepository.class);
        when(scopeRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.configureMirrorFixtureScopes(scopeRepository);
        FixtureBundle bundle = bundle("mirror-scoped");

        StoredFixtureBundle stored = service.registerFixture("mirror-scoped",
                new FixtureBundleRegistrationRequest("", target(), bundle), identity("test"));

        ArgumentCaptor<MirrorFixtureScopeBinding> binding =
                ArgumentCaptor.forClass(MirrorFixtureScopeBinding.class);
        verify(scopeRepository).create(binding.capture());
        assertThat(binding.getValue().scope()).isEqualTo(
                new com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot.Scope(
                        "tenant-a", "org-a", "project-a", "test", "local"));
        assertThat(binding.getValue().fixtureBundleRef().id()).isEqualTo("mirror-scoped");
        assertThat(binding.getValue().fixtureBundleRef().fingerprint())
                .isEqualTo(stored.fingerprint());
        assertThat(binding.getValue().boundBy()).isEqualTo("test-runner");
    }

    @Test
    void fixtureRegistrationRejectsMalformedReservedExecutionServiceMetadata() {
        FixtureBundle malformed = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "malformed-services", 1, targetFingerprint, "INTERNAL", null, null,
                List.of(), List.of(), Map.of(FixtureExecutionServices.METADATA_KEY, Map.of(
                        "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                        "identityAttributes", Map.of(),
                        "featureFlags", Map.of("pricing-v2", "raw-secret-47"))));

        assertThatThrownBy(() -> service.registerFixture("malformed-services",
                new FixtureBundleRegistrationRequest("", target(), malformed), identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.FIXTURE_EXECUTION_SERVICES_INVALID");
                    assertThat(failure.problem().title())
                            .contains("featureFlags values must be booleans")
                            .doesNotContain("raw-secret-47", "pricing-v2");
                });
        assertThat(fixtures.values).isEmpty();
    }

    @Test
    void storedFixtureIntegrityDriftFailsClosedAndEmitsPayloadFreeSecurityAudit() {
        FixtureBundle original = bundle("integrity", new FixtureRule(FixtureRule.SCHEMA_VERSION,
                "fixed", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of("result", "ok")),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict()));
        StoredFixtureBundle stored = service.registerFixture("integrity",
                new FixtureBundleRegistrationRequest("", target(), original), identity("test"));
        FixtureBundle tampered = new FixtureBundle("", "integrity", 1, targetFingerprint,
                "INTERNAL", null, null, List.of(), List.of(),
                Map.of("payload", "must-never-escape-721"));
        fixtures.values.put(InMemoryFixtures.key(
                        new TestingArtifactScope(
                                "tenant-a", "org-a", "project-a", "test", "local"),
                        "integrity", 1),
                new StoredFixtureBundle("", "tenant-a", "org-a", "project-a",
                        "test", "local", "integrity", 1,
                        stored.fingerprint(), tampered, stored.createdAt(), stored.createdBy()));

        assertThatThrownBy(() -> service.findFixture("integrity", 1, identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.FIXTURE_INTEGRITY_INVALID");
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().title())
                            .doesNotContain("must-never-escape-721", "integrity", stored.fingerprint());
                });
        assertThat(securityEvents.events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("FIXTURE_INTEGRITY_INVALID");
            assertThat(event.reasonCode()).isEqualTo("RG.TEST.FIXTURE_INTEGRITY_INVALID");
            assertThat(event.facts()).isEmpty();
        });
    }

    @Test
    void storedFixtureReadReturnsCanonicalSnapshotDetachedFromRepositoryAliases() {
        MutableFixtureValue repositoryValue = new MutableFixtureValue("approved");
        FixtureBundle bundle = bundle("detached", new FixtureRule("", "mutable",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.returning(repositoryValue),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict()));
        String fingerprint = ProtocolFingerprint.of(mapper, bundle);
        StoredFixtureBundle repositoryObject = new StoredFixtureBundle(
                "", "tenant-a", "org-a", "project-a", "test", "local",
                "detached", 1, fingerprint, bundle, java.time.Instant.EPOCH, "repository");
        fixtures.values.put(InMemoryFixtures.key("tenant-a", "test", "detached", 1), repositoryObject);

        StoredFixtureBundle resolved = service.findFixture("detached", 1, identity("test"));
        repositoryValue.status = "denied";

        assertThat(resolved).isNotSameAs(repositoryObject);
        assertThat(resolved.bundle().rules().getFirst().behavior().value())
                .isEqualTo(Map.of("status", "approved"));
        assertThat(StoredFixtureBundleIntegrity.verifiedSnapshot(mapper, resolved))
                .isNotSameAs(resolved);
    }

    @Test
    void storedFixtureReadRejectsAValidCrossScopeRepositorySubstitution() {
        FixtureBundle bundle = bundle("cross-scope");
        StoredFixtureBundle foreign = new StoredFixtureBundle(
                "", "tenant-b", "org-a", "project-a", "test", "local",
                "cross-scope", 1, ProtocolFingerprint.of(mapper, bundle), bundle,
                java.time.Instant.EPOCH, "repository");
        fixtures.values.put(InMemoryFixtures.key("tenant-a", "test", "cross-scope", 1), foreign);

        assertThatThrownBy(() -> service.findFixture("cross-scope", 1, identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.FIXTURE_INTEGRITY_INVALID");
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().title())
                            .doesNotContain("tenant-a", "tenant-b", "cross-scope");
                });
        assertThat(securityEvents.events).singleElement().satisfies(event -> {
            assertThat(event.reasonCode()).isEqualTo("RG.TEST.FIXTURE_INTEGRITY_INVALID");
            assertThat(event.facts()).isEmpty();
        });
    }

    @Test
    void fixtureRegistrationRejectsAValidButSubstitutedRepositoryReceipt() {
        FixtureBundle bundle = bundle("create-substitution");
        fixtures.createOverride = new StoredFixtureBundle(
                "", "tenant-b", "org-a", "project-a", "test", "local",
                "create-substitution", 1, ProtocolFingerprint.of(mapper, bundle), bundle,
                java.time.Instant.EPOCH, "repository");

        assertThatThrownBy(() -> service.registerFixture("create-substitution",
                new FixtureBundleRegistrationRequest("", target(), bundle), identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.FIXTURE_INTEGRITY_INVALID");
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().title())
                            .doesNotContain("tenant-a", "tenant-b", "create-substitution");
                });
        assertThat(securityEvents.events).singleElement().satisfies(event -> {
            assertThat(event.reasonCode()).isEqualTo("RG.TEST.FIXTURE_INTEGRITY_INVALID");
            assertThat(event.facts()).isEmpty();
        });
    }

    @Test
    void fixtureRegistrationPreservesProvenanceFromAnIdempotentExistingRevision() {
        FixtureBundle bundle = bundle("idempotent-provenance");
        fixtures.createOverride = new StoredFixtureBundle(
                "", "tenant-a", "org-a", "project-a", "test", "local",
                "idempotent-provenance", 1, ProtocolFingerprint.of(mapper, bundle), bundle,
                java.time.Instant.EPOCH, "original-author");

        StoredFixtureBundle stored = service.registerFixture("idempotent-provenance",
                new FixtureBundleRegistrationRequest("", target(), bundle), identity("test"));

        assertThat(stored.createdAt()).isEqualTo(java.time.Instant.EPOCH);
        assertThat(stored.createdBy()).isEqualTo("original-author");
        assertThat(securityEvents.events).isEmpty();
    }

    @Test
    void staleTargetAndStoredFixtureFingerprintsFailClosedBeforeExecution() {
        FixtureBundle inline = bundle("stale", new FixtureRule(FixtureRule.SCHEMA_VERSION, "fixed",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.returning("ok"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict()));
        TestExecutionApiRequest staleTarget = new TestExecutionApiRequest("",
                new TestExecutionApiRequest.Target("GRAPH", "controlled-graph", "sha256:" + "0".repeat(64)),
                "GRAPH_CONTRACT_TEST", Map.of("input", "hello"), inline, null,
                TestExecutionApiRequest.Verbosity.FULL, Map.of());

        assertThatThrownBy(() -> service.execute(staleTarget, identity("test")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.TEST.TARGET_FINGERPRINT_CONFLICT"));

        StoredFixtureBundle stored = service.registerFixture("stale",
                new FixtureBundleRegistrationRequest("", target(), inline), identity("test"));
        TestExecutionApiRequest staleFixture = request(null,
                new TestExecutionApiRequest.FixtureBundleRef("stale", 1,
                        stored.fingerprint() + "changed"), TestExecutionApiRequest.Verbosity.FULL);
        assertThatThrownBy(() -> service.execute(staleFixture, identity("test")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.TEST.FIXTURE_FINGERPRINT_CONFLICT"));
        assertThat(runs.values).isEmpty();
    }

    @Test
    void productionIdentityIsRejectedAndEmitsSecurityEvent() {
        assertThatThrownBy(() -> service.execute(request(bundle("inline"), null,
                        TestExecutionApiRequest.Verbosity.FULL), identity("prod")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.TEST.ENVIRONMENT_FORBIDDEN"));

        assertThat(securityEvents.events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("TEST_PURPOSE_PRODUCTION_TOUCH");
            assertThat(event.outcome()).isEqualTo("REJECTED");
        });
        assertThat(runs.values).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "TEST", " test ", "staging", " STAGING "})
    void testAndStagingEnvironmentIdsAreCanonicalizedBeforeExecution(String environment) {
        FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, "canonical-output",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.returning("ok"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        TestExecutionApiResponse response = service.execute(
                request(bundle("canonical-" + environment.trim(), rule), null,
                        TestExecutionApiRequest.Verbosity.SUMMARY), identity(environment));

        assertThat(response.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "PROD", " prod ", "production", "PRODUCTION", " production "})
    void productionEnvironmentIdsAreCanonicalizedToTheSameForbiddenPolicy(String environment) {
        assertThatThrownBy(() -> service.execute(
                request(bundle("production-" + environment.trim()), null,
                        TestExecutionApiRequest.Verbosity.SUMMARY), identity(environment)))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.TEST.ENVIRONMENT_FORBIDDEN"));
    }

    @Test
    void controlsInBusinessContextAreRejectedAndAudited() {
        TestExecutionApiRequest request = new TestExecutionApiRequest("", target(),
                "GRAPH_CONTRACT_TEST", Map.of("input", "hello", "controlPlan", Map.of()),
                bundle("inline"), null, TestExecutionApiRequest.Verbosity.FULL, Map.of());

        assertThatThrownBy(() -> service.execute(request, identity("test")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.TEST.CONTROL_IN_BUSINESS_CONTEXT"));
        assertThat(securityEvents.events).singleElement()
                .extracting(TestSecurityEvent::reasonCode)
                .isEqualTo("RG.TEST.CONTROL_IN_BUSINESS_CONTEXT");
    }

    @Test
    void referencedWorldExecutionFailsClosedWhenOptionalWiringIsAbsent() {
        TestExecutionIngress ingress = new TestExecutionIngress(
                referencedRequest(targetFingerprint), "", "", worldEnvelope());

        assertThatThrownBy(() -> service.executeAdmittedIngress(ingress, identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.WORLD_REFERENCE_EXECUTION_UNAVAILABLE");
                    assertThat(failure.problem().status()).isEqualTo(503);
                });
        assertThat(runs.values).isEmpty();
    }

    @Test
    void referencedWorldExecutionRejectsBodyFixtureBeforeResolver() {
        TestExecutionApiRequest withInline = request(bundle("body-fixture"), null,
                TestExecutionApiRequest.Verbosity.FULL);
        TestExecutionIngress ingress = new TestExecutionIngress(
                withInline, "", "", worldEnvelope());

        assertThatThrownBy(() -> service.executeAdmittedIngress(ingress, identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code()).isEqualTo("RG.TEST.FIXTURE_SOURCE_INVALID");
                    assertThat(failure.toString()).doesNotContain("body-fixture", "world-a");
                });
        assertThat(runs.values).isEmpty();
    }

    @Test
    void referencedWorldExecutionUsesExplicitWiringAndStoresStoredFixtureProvenance() {
        ReferencedWiring wiring = referencedWiring();
        TestExecutionApiRequest request = referencedRequest("");
        TestControlEnvelope envelope = worldEnvelope();
        IntegrationRequestContext authenticated = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "local", "WORKLOAD", "runner-a",
                "delegated-a", "TEST_EXECUTION", "correlation-1", java.util.Set.of("quality", "ops"),
                "RESTRICTED", "grant-a");

        TestExecutionApiResponse response = wiring.service().executeAdmittedIngress(
                new TestExecutionIngress(request, "", "", envelope), authenticated);

        ArgumentCaptor<IntegrationRequestContext> trustedContext =
                ArgumentCaptor.forClass(IntegrationRequestContext.class);
        verify(wiring.resolver()).resolve(eq(envelope), trustedContext.capture());
        assertThat(trustedContext.getValue()).isEqualTo(new IntegrationRequestContext(
                authenticated.tenantId(), authenticated.organizationId(), authenticated.projectId(),
                authenticated.environmentId(), authenticated.region(), authenticated.actorType(),
                authenticated.actorId(), authenticated.delegatedBy(), "GRAPH_CONTRACT_TEST",
                authenticated.correlationId(), authenticated.groups(), authenticated.clearance(),
                authenticated.delegationGrantId()));

        assertThat(response.target().fingerprint())
                .isEqualTo(GraphArtifactFingerprint.of(mapper, graph));
        assertThat(response.fixtureBundleRef().source()).isEqualTo("STORED");
        assertThat(response.fixtureBundleRef().fixtureBundleId())
                .isEqualTo(wiring.bundle().fixtureBundleId());
        ArgumentCaptor<TestExecutionRequest> worldRequest =
                ArgumentCaptor.forClass(TestExecutionRequest.class);
        verify(wiring.runner()).execute(eq(wiring.compilation()), worldRequest.capture(),
                any(TestRunService.AdmissionFactory.class));
        assertThat(worldRequest.getValue().fixtureSource())
                .isEqualTo(TestExecutionRequest.FixtureSource.STORED);
        assertThat(worldRequest.getValue().authorizedPurpose()).isEqualTo("GRAPH_CONTRACT_TEST");
        assertThat(worldRequest.getValue().targetFingerprint())
                .isEqualTo(GraphArtifactFingerprint.of(mapper, graph));
        assertThat(worldRequest.getValue().metadata())
                .containsEntry("assetKind", GovernedCatalogKind.RESOURCE_WORLD_MODEL.name())
                .containsEntry("assetRevision", 1L)
                .containsEntry("worldProvenance", "RESOURCE_WORLD_MODEL")
                .doesNotContainValue("hello");

        InOrder executionOrder = inOrder(wiring.resolver(), wiring.planner(), wiring.runner());
        executionOrder.verify(wiring.resolver()).resolve(eq(envelope), any());
        executionOrder.verify(wiring.planner()).plan(any(), eq(graph), eq(request.context()));
        executionOrder.verify(wiring.runner()).execute(eq(wiring.compilation()),
                any(TestExecutionRequest.class), any(TestRunService.AdmissionFactory.class));
        assertThat(runs.find(authenticated.tenantId(), authenticated.environmentId(), response.runId()))
                .isPresent();
    }

    @Test
    void referencedWorldExecutionAcceptsBlankArtifactAndCompositeTargetFingerprints() {
        String artifactFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        for (String requestedFingerprint : List.of("", artifactFingerprint, targetFingerprint)) {
            ReferencedWiring wiring = referencedWiring();

            TestExecutionApiResponse response = wiring.service().executeAdmittedIngress(
                    new TestExecutionIngress(referencedRequest(requestedFingerprint), "", "",
                            worldEnvelope()), identity("test"));

            assertThat(response.target().fingerprint()).isEqualTo(artifactFingerprint);
            assertThat(response.fixtureBundleRef().source()).isEqualTo("STORED");
        }
    }

    @Test
    void referencedScenarioExecutionPreservesScenarioProvenanceAndSucceeds() {
        ReferencedWiring wiring = referencedWiring(GovernedCatalogKind.SCENARIO);

        TestExecutionApiResponse response = wiring.service().executeAdmittedIngress(
                new TestExecutionIngress(referencedRequest(""), "", "", scenarioEnvelope()),
                identity("test"));

        assertThat(response.target().fingerprint())
                .isEqualTo(GraphArtifactFingerprint.of(mapper, graph));
        assertThat(response.fixtureBundleRef().source()).isEqualTo("STORED");
        ArgumentCaptor<TestExecutionRequest> worldRequest =
                ArgumentCaptor.forClass(TestExecutionRequest.class);
        verify(wiring.runner()).execute(eq(wiring.compilation()), worldRequest.capture(),
                any(TestRunService.AdmissionFactory.class));
        assertThat(worldRequest.getValue().metadata())
                .containsEntry("assetKind", GovernedCatalogKind.SCENARIO.name())
                .containsEntry("assetRevision", 1L);
    }

    @Test
    void referencedAdmissionFactoryUsesOriginalIdentityAndRejectsBeforeEvidencePersistence() {
        TestRuntimeAdmissionGate admissions = mock(TestRuntimeAdmissionGate.class);
        ReferencedWiring wiring = referencedWiring(GovernedCatalogKind.RESOURCE_WORLD_MODEL, admissions);
        IntegrationRequestContext authenticated = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "local", "WORKLOAD", "runner-a",
                "delegated-a", "TEST_EXECUTION", "correlation-1", java.util.Set.of("quality"),
                "CONFIDENTIAL", "grant-a");
        TestExecutionApiRequest request = referencedRequest("");
        AtomicReference<TestRunService.AdmissionFactory> factory = new AtomicReference<>();
        when(admissions.admit(any(), any())).thenThrow(new IllegalStateException("capacity denied"));
        doAnswer(invocation -> {
            factory.set(invocation.getArgument(2));
            CompiledExecutionControl compiled = mock(CompiledExecutionControl.class);
            when(compiled.effectivePlan()).thenReturn(wiring.result().plan());
            when(compiled.inventory()).thenReturn(new InvocationInventory(List.of(), Map.of(), Map.of()));
            factory.get().admit(compiled);
            return wiring.result();
        }).when(wiring.runner()).execute(eq(wiring.compilation()), any(TestExecutionRequest.class),
                any(TestRunService.AdmissionFactory.class));
        int persistedBefore = runs.values.size();

        assertThatThrownBy(() -> wiring.service().executeAdmittedIngress(
                new TestExecutionIngress(request, "", "", worldEnvelope()), authenticated))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.WORLD_REFERENCE_EXECUTION_UNAVAILABLE");
                    assertThat(failure.toString()).doesNotContain("capacity denied", "hello");
                });

        ArgumentCaptor<TestRuntimeAdmissionGate.AdmissionIntent> intent =
                ArgumentCaptor.forClass(TestRuntimeAdmissionGate.AdmissionIntent.class);
        verify(admissions).admit(eq(authenticated), intent.capture());
        String artifactFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        assertThat(intent.getValue().kind()).isEqualTo(TestRuntimeAdmissionGate.Kind.GRAPH);
        assertThat(intent.getValue().dependencyRefs()).isEqualTo(
                GraphExecutionTargetSnapshot.capture(mapper, graph, resources)
                        .dependencyFingerprints().keySet());
        assertThat(intent.getValue().intentFingerprint()).isEqualTo(ProtocolFingerprint.of(mapper,
                Map.of("schemaVersion", "bloge.testRuntimeAdmissionWorkIntent.v1",
                        "request", request, "targetFingerprint", artifactFingerprint,
                        "planFingerprint", wiring.result().plan().planFingerprint())));
        assertThat(runs.values).hasSize(persistedBefore);
    }

    @Test
    void referencedWorldAuthorizationDenialStopsBeforePlannerAndPayloadExecution() {
        ReferencedWiring wiring = referencedWiring();
        when(wiring.resolver().resolve(any(), any())).thenThrow(GovernedAssetAccessException.denied());
        int persistedBefore = runs.values.size();

        assertThatThrownBy(() -> wiring.service().executeAdmittedIngress(
                new TestExecutionIngress(referencedRequest(""), "", "", worldEnvelope()),
                identity("test")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.GOVERNED_ASSET.ACCESS_DENIED");
                    assertThat(failure.problem().status()).isEqualTo(403);
                });
        verifyNoInteractions(wiring.planner(), wiring.runner());
        assertThat(runs.values).hasSize(persistedBefore);
    }

    @Test
    void batchIsBoundedBeforeAnyItemRuns() {
        List<TestExecutionApiRequest> tooMany = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> request(bundle("fixture-" + index), null,
                        TestExecutionApiRequest.Verbosity.SUMMARY)).toList();

        assertThatThrownBy(() -> service.executeBatch(
                new TestExecutionBatchRequest("", tooMany), identity("test")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.TEST.BATCH_SIZE_INVALID"));
        assertThat(runs.values).isEmpty();
    }

    @Test
    void unknownFixtureClassificationIsRejectedInsteadOfBypassingClearanceRanks() {
        FixtureBundle invalid = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "invalid-class", 1,
                targetFingerprint, "TOP_SECRET_PLUS", null, null, List.of(), List.of(), Map.of());

        assertThatThrownBy(() -> service.execute(request(invalid, null,
                        TestExecutionApiRequest.Verbosity.FULL), identity("test")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.TEST.FIXTURE_CLASSIFICATION_INVALID"));
    }

    @Test
    void persistenceFailureDowngradesTerminalResultToEvidenceIncomplete() {
        runs.failCreates = true;

        TestExecutionApiResponse response = service.execute(request(bundle("inline"), null,
                TestExecutionApiRequest.Verbosity.FULL), identity("test"));

        assertThat(response.evidence().status()).isEqualTo(TestRunEvidence.Status.EVIDENCE_INCOMPLETE);
        assertThat(response.evidence().evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(response.evidence().diagnostics()).anyMatch(value -> value.contains("could not be persisted"));
    }

    @Test
    void substitutedCreateReceiptDowngradesEvidenceAndEmitsSecurityEvent() {
        runs.substituteCreateReceipt = true;

        TestExecutionApiResponse response = service.execute(request(bundle("inline"), null,
                TestExecutionApiRequest.Verbosity.FULL), identity("test"));

        assertThat(response.evidence().status()).isEqualTo(TestRunEvidence.Status.EVIDENCE_INCOMPLETE);
        assertThat(response.evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(securityEvents.events).extracting(TestSecurityEvent::eventType)
                .contains("TEST_EVIDENCE_INTEGRITY_INVALID");
    }

    @Test
    void securityBoundaryFailsClosedWhenItsAuditSinkCannotCommit() {
        securityEvents.failAppends = true;

        assertThatThrownBy(() -> service.execute(request(bundle("inline"), null,
                        TestExecutionApiRequest.Verbosity.FULL), identity("prod")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE"));
    }

    @Test
    void replayClosureIsResolvedAtRegistrationAndAgainBeforeScheduling() {
        String replayRef = "bloge-replay:approved-order@7#sha256:" + "c".repeat(64);
        FixtureRule replay = new FixtureRule(FixtureRule.SCHEMA_VERSION, "approved-replay",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.replaying(replayRef),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        FixtureBundle replayBundle = bundle("governed-replay", replay);
        ResolvedReplayPayloads resolved = new ResolvedReplayPayloads(Map.of(replayRef,
                new ResolvedReplayPayloads.Payload(replayRef, "INTERNAL",
                        "{\"result\":\"approved\"}", "source-run", "subject", 1,
                        "sha256:" + "d".repeat(64), "sha256:" + "e".repeat(64),
                        java.time.Instant.parse("2030-01-01T00:00:00Z"), true, List.of())));
        when(replayPayloadService.resolve(any(FixtureBundle.class),
                any(IntegrationRequestContext.class))).thenReturn(resolved);

        StoredFixtureBundle stored = service.registerFixture("governed-replay",
                new FixtureBundleRegistrationRequest("", target(), replayBundle), identity("test"));
        TestExecutionApiResponse response = service.execute(request(null,
                new TestExecutionApiRequest.FixtureBundleRef("governed-replay", 1,
                        stored.fingerprint()), TestExecutionApiRequest.Verbosity.FULL), identity("test"));

        verify(replayPayloadService, times(2)).resolve(any(FixtureBundle.class),
                any(IntegrationRequestContext.class));
        assertThat(response.plan().replayDependencies()).singleElement()
                .satisfies(dependency -> assertThat(dependency.replayRef()).isEqualTo(replayRef));
        assertThat(response.evidence().nodeTrace()).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("MOCKED");
            assertThat(trace.fidelity()).isEqualTo("REPLAYED");
        });
    }

    @Test
    void serverRegeneratedMutationExecutesWithBaselineFixtureAndMutantEvidenceIdentity() {
        MutationHarness harness = mutationHarness();
        IntegrationRequestContext identity = identity("test");
        TestMutationCasePlan plan = harness.service().planGraphMutationCases(
                harness.graph().name(), 4, identity);
        List<TestDslMutationPlanner.RegeneratedMutant> closure =
                harness.service().regenerateGraphMutations(
                        harness.graph().name(), plan, identity);
        StoredFixtureBundle fixture = storeMutationFixture(harness, identity);
        TestDslMutationPlanner.RegeneratedMutant selected = closure.getFirst();
        TestExecutionApiRequest request = mutationRequest(harness, selected.coordinate(), fixture,
                null);

        TestExecutionApiResponse response = harness.service()
                .executeAdmittedMutationGraphCase(selected, harness.targetFingerprint(),
                        request, identity);

        assertThat(response.target().fingerprint())
                .isEqualTo(selected.coordinate().mutantTargetFingerprint());
        assertThat(response.fixtureBundleRef().source()).isEqualTo("STORED");
        assertThat(response.plan().targetFingerprint())
                .isEqualTo(selected.coordinate().mutantTargetFingerprint());
        assertThat(response.plan().authorizedPurpose()).isEqualTo("MUTATION_SUITE_EXECUTION");
        assertThat(response.evidence().executionPurpose()).isEqualTo("MUTATION_SUITE_EXECUTION");
        assertThat(response.evidence().targetFingerprint())
                .isEqualTo(selected.coordinate().mutantTargetFingerprint());
        assertThat(response.evidence().metadata())
                .containsEntry("baselineTargetFingerprint", harness.targetFingerprint())
                .containsEntry("mutationPlanFingerprint", plan.planFingerprint())
                .containsEntry("mutantId", selected.coordinate().mutantId())
                .containsEntry("mutantGraphArtifactFingerprint",
                        selected.coordinate().mutantGraphArtifactFingerprint());
        assertThat(response.integrity().independentlyVerifiable()).isTrue();
        assertThat(runs.values.get(response.runId()).target()).isEqualTo(response.target());
    }

    @Test
    void mutationExecutionRejectsCrossWiredCoordinatesBeforeCreatingAChildRun() {
        MutationHarness harness = mutationHarness();
        IntegrationRequestContext identity = identity("test");
        TestMutationCasePlan plan = harness.service().planGraphMutationCases(
                harness.graph().name(), 4, identity);
        List<TestDslMutationPlanner.RegeneratedMutant> closure =
                harness.service().regenerateGraphMutations(
                        harness.graph().name(), plan, identity);
        assertThat(closure).hasSizeGreaterThan(1);
        StoredFixtureBundle fixture = storeMutationFixture(harness, identity);
        TestDslMutationPlanner.RegeneratedMutant first = closure.getFirst();
        TestDslMutationPlanner.RegeneratedMutant forged =
                new TestDslMutationPlanner.RegeneratedMutant(
                        plan.planFingerprint(), first.coordinate(), closure.get(1).graph());

        assertThatThrownBy(() -> harness.service().executeAdmittedMutationGraphCase(
                forged, harness.targetFingerprint(),
                mutationRequest(harness, first.coordinate(), fixture, null), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.TEST.MUTATION_ARTIFACT_MISMATCH"));
        assertThat(runs.values).isEmpty();
    }

    @Test
    void mutationOnlyBoundaryCannotBeReachedThroughOrdinaryOrInlineExecution() {
        MutationHarness harness = mutationHarness();
        IntegrationRequestContext identity = identity("test");
        TestMutationCasePlan plan = harness.service().planGraphMutationCases(
                harness.graph().name(), 4, identity);
        TestDslMutationPlanner.RegeneratedMutant selected = harness.service()
                .regenerateGraphMutations(harness.graph().name(), plan, identity).getFirst();
        StoredFixtureBundle fixture = storeMutationFixture(harness, identity);
        TestExecutionApiRequest stored = mutationRequest(
                harness, selected.coordinate(), fixture, null);
        FixtureBundle inline = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "inline-mutation", 1, harness.targetFingerprint(), "INTERNAL", null, null,
                List.of(), List.of(), Map.of());

        assertThatThrownBy(() -> harness.service().execute(stored, identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.TEST.TARGET_FINGERPRINT_CONFLICT"));
        assertThatThrownBy(() -> harness.service().executeAdmittedMutationGraphCase(
                selected, harness.targetFingerprint(),
                mutationRequest(harness, selected.coordinate(), null, inline), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.TEST.MUTATION_FIXTURE_SOURCE_INVALID"));
        assertThat(runs.values).isEmpty();
    }

    private TestExecutionApiRequest request(FixtureBundle inline,
                                            TestExecutionApiRequest.FixtureBundleRef reference,
                                            TestExecutionApiRequest.Verbosity verbosity) {
        return new TestExecutionApiRequest("", target(), "GRAPH_CONTRACT_TEST",
                Map.of("input", "hello"), inline, reference, verbosity, Map.of("caseId", "case-1"));
    }

    private TestExecutionApiRequest.Target target() {
        return new TestExecutionApiRequest.Target("GRAPH", "controlled-graph", targetFingerprint);
    }

    private TestExecutionApiRequest referencedRequest(String fingerprint) {
        return new TestExecutionApiRequest("",
                new TestExecutionApiRequest.Target("GRAPH", "controlled-graph", fingerprint),
                TestExecutionApiService.AUTHORIZED_PURPOSE, Map.of("input", "hello"),
                null, null, TestExecutionApiRequest.Verbosity.FULL, Map.of("caseId", "world-case"));
    }

    private TestControlEnvelope worldEnvelope() {
        return new TestControlEnvelope(TestExecutionApiService.AUTHORIZED_PURPOSE, null,
                new TestAssetReference("world-a", 1, fingerprint('a')), "correlation-1");
    }

    private TestControlEnvelope scenarioEnvelope() {
        return new TestControlEnvelope(TestExecutionApiService.AUTHORIZED_PURPOSE,
                new TestAssetReference("scenario-a", 1, fingerprint('a')), null, "correlation-1");
    }

    private ReferencedWiring referencedWiring() {
        return referencedWiring(GovernedCatalogKind.RESOURCE_WORLD_MODEL);
    }

    private ReferencedWiring referencedWiring(GovernedCatalogKind kind) {
        return referencedWiring(kind, TestRuntimeAdmissionGate.unbounded());
    }

    private ReferencedWiring referencedWiring(TestRuntimeAdmissionGate admissions) {
        return referencedWiring(GovernedCatalogKind.RESOURCE_WORLD_MODEL, admissions);
    }

    private ReferencedWiring referencedWiring(GovernedCatalogKind kind,
                                              TestRuntimeAdmissionGate admissions) {
        FixtureBundle referencedBundle = bundle("referenced-world");
        TestExecutionApiResponse baseline = service.execute(
                request(referencedBundle, null, TestExecutionApiRequest.Verbosity.FULL), identity("test"));
        TestExecutionResult result = new TestExecutionResult(baseline.plan(), null, baseline.evidence());

        WorldScenarioCompilation compilation = mock(WorldScenarioCompilation.class);
        when(compilation.bundle()).thenReturn(referencedBundle);
        when(compilation.fingerprint()).thenReturn(fingerprint('c'));
        GovernedResourceRef primaryRef = new GovernedResourceRef("tenant-a",
                kind, kind == GovernedCatalogKind.SCENARIO ? "scenario-a" : "world-a", 1,
                fingerprint('a'));
        WorldReferenceExecutionPlanner.Plan plan = mock(WorldReferenceExecutionPlanner.Plan.class);
        when(plan.primaryRef()).thenReturn(primaryRef);
        when(plan.compilation()).thenReturn(compilation);
        when(plan.provenance()).thenReturn(
                kind == GovernedCatalogKind.SCENARIO
                        ? WorldReferenceExecutionPlanner.ProvenanceKind.SCENARIO
                        : WorldReferenceExecutionPlanner.ProvenanceKind.RESOURCE_WORLD_MODEL);

        AuthorizedWorldAssetResolver resolver = mock(AuthorizedWorldAssetResolver.class);
        WorldReferenceExecutionPlanner planner = mock(WorldReferenceExecutionPlanner.class);
        WorldScenarioRunService runner = mock(WorldScenarioRunService.class);
        ResolvedWorldAssetControl control = mock(ResolvedWorldAssetControl.class);
        when(resolver.resolve(any(), any())).thenReturn(control);
        when(planner.plan(any(), any(), any())).thenReturn(plan);
        doReturn(result).when(runner).execute(eq(compilation), any(TestExecutionRequest.class),
                any(TestRunService.AdmissionFactory.class));

        TestExecutionApiService referencedService = new TestExecutionApiService(
                graphService, new DefaultOperatorRegistry(), resources, new BlgeExpressionEvaluator(),
                mapper, fixtures, runs, securityEvents, Duration.ofDays(7), replayPayloadService,
                new TestEvidenceIntegrityService(mapper, new InMemoryVisualEvidenceSigner()),
                admissions, null, resolver, planner, runner);
        return new ReferencedWiring(referencedService, resolver, planner, runner, compilation,
                referencedBundle, result);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private FixtureBundle bundle(String id, FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, id, 1, targetFingerprint,
                "INTERNAL", null, null, List.of(rules), List.of(), Map.of());
    }

    private MutationHarness mutationHarness() {
        DefaultOperatorRegistry operators = new DefaultOperatorRegistry();
        Graph mutationGraph = new GraphLoader(operators).load("""
                graph mutationFlow {
                  transform output {
                    first = ctx.first
                    second = ctx.second
                    third = ctx.third
                  }
                }
                """);
        GatewayGraphService mutationGraphs = mock(GatewayGraphService.class);
        when(mutationGraphs.requireGraph(mutationGraph.name())).thenReturn(mutationGraph);
        when(mutationGraphs.requireContract(mutationGraph.name())).thenReturn(
                new com.leanowtech.bloge.gateway.gateway.GatewayGraphContract(
                        mutationGraph.name(), SchemaEnvelope.object(Map.of(
                                "first", Map.of("type", "string"),
                                "second", Map.of("type", "string"),
                                "third", Map.of("type", "string")),
                                List.of("first", "second", "third")),
                        null, List.of("output")));
        doNothing().when(mutationGraphs).validateInput(
                org.mockito.ArgumentMatchers.eq(mutationGraph.name()), any());
        TestExecutionApiService mutationService = new TestExecutionApiService(
                mutationGraphs, operators, resources, new BlgeExpressionEvaluator(), mapper,
                fixtures, runs, securityEvents, Duration.ofDays(7), replayPayloadService,
                new TestEvidenceIntegrityService(mapper, new InMemoryVisualEvidenceSigner()));
        return new MutationHarness(mutationService, mutationGraph,
                GraphExecutionTargetSnapshot.capture(mapper, mutationGraph, resources).fingerprint());
    }

    private StoredFixtureBundle storeMutationFixture(
            MutationHarness harness,
            IntegrationRequestContext identity) {
        TestExecutionApiRequest.Target target = new TestExecutionApiRequest.Target(
                "GRAPH", harness.graph().name(), harness.targetFingerprint());
        FixtureBundle fixture = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "mutation-fixture", 1, harness.targetFingerprint(), "INTERNAL", null, null,
                List.of(), List.of(), Map.of());
        return harness.service().registerFixture("mutation-fixture",
                new FixtureBundleRegistrationRequest("", target, fixture), identity);
    }

    private TestExecutionApiRequest mutationRequest(
            MutationHarness harness,
            TestMutationCasePlan.PlannedMutant coordinate,
            StoredFixtureBundle stored,
            FixtureBundle inline) {
        TestExecutionApiRequest.FixtureBundleRef ref = stored == null ? null
                : new TestExecutionApiRequest.FixtureBundleRef(stored.fixtureBundleId(),
                stored.revision(), stored.fingerprint());
        return new TestExecutionApiRequest("",
                new TestExecutionApiRequest.Target("GRAPH", harness.graph().name(),
                        coordinate.mutantTargetFingerprint()),
                "GRAPH_CONTRACT_TEST", Map.of(
                "first", "A", "second", "B", "third", "C"), inline, ref,
                TestExecutionApiRequest.Verbosity.FULL, Map.of("caseId", "oracle-1"));
    }

    private static IntegrationRequestContext identity(String environment) {
        return identity(environment, "tenant-a");
    }

    private static IntegrationRequestContext identity(String environment, String tenant) {
        return new IntegrationRequestContext(tenant, "org-a", "project-a", environment,
                "local", "WORKLOAD", "test-runner", "", "TEST_EXECUTION", "correlation-1",
                java.util.Set.of("quality"), "CONFIDENTIAL", "");
    }

    private static final class EmptyResourceRegistry implements ResourceRegistry {
        @Override public ResourceDescriptor resolve(String resourceId) { throw new IllegalArgumentException(); }
        @Override public boolean contains(String resourceId) { return false; }
        @Override public java.util.Collection<ResourceDescriptor> all() { return List.of(); }
    }

    private record MutationHarness(
            TestExecutionApiService service,
            Graph graph,
            String targetFingerprint
    ) {
    }

    private record ReferencedWiring(
            TestExecutionApiService service,
            AuthorizedWorldAssetResolver resolver,
            WorldReferenceExecutionPlanner planner,
            WorldScenarioRunService runner,
            WorldScenarioCompilation compilation,
            FixtureBundle bundle,
            TestExecutionResult result
    ) {
    }

    private static final class MutableFixtureValue {
        public String status;

        private MutableFixtureValue(String status) {
            this.status = status;
        }
    }

    private static final class InMemoryFixtures implements FixtureBundleRepository {
        private final Map<String, StoredFixtureBundle> values = new LinkedHashMap<>();
        private StoredFixtureBundle createOverride;
        @Override public StoredFixtureBundle create(StoredFixtureBundle value) {
            String key = key(value.tenantId(), value.environmentId(), value.fixtureBundleId(), value.revision());
            StoredFixtureBundle existing = values.putIfAbsent(key, value);
            if (existing != null && !existing.fingerprint().equals(value.fingerprint())) {
                throw new FixtureBundleConflictException("immutable conflict");
            }
            StoredFixtureBundle result = existing == null ? value : existing;
            return createOverride == null ? result : createOverride;
        }
        @Override public Optional<StoredFixtureBundle> find(String tenant, String environment,
                                                            String id, long revision) {
            return Optional.ofNullable(values.get(key(tenant, environment, id, revision)));
        }
        @Override public StoredFixtureBundle create(
                TestingArtifactScope scope, StoredFixtureBundle value) {
            String key = key(scope, value.fixtureBundleId(), value.revision());
            StoredFixtureBundle existing = values.putIfAbsent(key, value);
            if (existing != null && !existing.fingerprint().equals(value.fingerprint())) {
                throw new FixtureBundleConflictException("immutable conflict");
            }
            StoredFixtureBundle result = existing == null ? value : existing;
            return createOverride == null ? result : createOverride;
        }
        @Override public Optional<StoredFixtureBundle> find(
                TestingArtifactScope scope, String id, long revision) {
            StoredFixtureBundle value = values.get(key(scope, id, revision));
            if (value == null) {
                value = values.get(key(scope.tenantId(), scope.environmentId(), id, revision));
            }
            return Optional.ofNullable(value);
        }
        private static String key(String tenant, String environment, String id, long revision) {
            return tenant + "|" + environment + "|" + id + "|" + revision;
        }
        private static String key(TestingArtifactScope scope, String id, long revision) {
            return scope.tenantId() + "|" + scope.organizationId() + "|"
                    + scope.projectId() + "|" + scope.environmentId() + "|"
                    + scope.region() + "|" + id + "|" + revision;
        }
    }

    private static final class InMemoryRuns implements TestRunRepository {
        private final Map<String, TestRunRecord> values = new LinkedHashMap<>();
        private boolean failCreates;
        private boolean failReadsWithIntegrity;
        private boolean substituteCreateReceipt;
        @Override public TestRunRecord create(TestRunRecord record) {
            if (failCreates) {
                throw new IllegalStateException("run store unavailable");
            }
            values.put(record.runId(), record);
            if (substituteCreateReceipt) {
                return new TestRunRecord(record.runId(), "tenant-b", record.organizationId(),
                        record.projectId(), record.environmentId(), record.actorId(), record.target(),
                        record.fixtureBundleRef(), record.requestedVerbosity(), record.plan(),
                        record.evidence(), record.integrity(), record.createdAt(), record.expiresAt());
            }
            return record;
        }
        @Override public Optional<TestRunRecord> find(String tenant, String environment, String runId) {
            if (failReadsWithIntegrity) {
                throw new TestRunIntegrityException();
            }
            TestRunRecord record = values.get(runId);
            return record != null && record.tenantId().equals(tenant)
                    && record.environmentId().equals(environment) ? Optional.of(record) : Optional.empty();
        }
    }

    private static final class InMemorySecurityEvents implements TestSecurityEventRepository {
        private final List<TestSecurityEvent> events = new ArrayList<>();
        private boolean failAppends;
        @Override public TestSecurityEvent append(TestSecurityEvent event) {
            if (failAppends) {
                throw new IllegalStateException("security audit unavailable");
            }
            TestSecurityEvent stored = event.withSequence(events.size() + 1L);
            events.add(stored);
            return stored;
        }
        @Override public List<TestSecurityEvent> recent(int limit) { return List.copyOf(events); }
    }
}
