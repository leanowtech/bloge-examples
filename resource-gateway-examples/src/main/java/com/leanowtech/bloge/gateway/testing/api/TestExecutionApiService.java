package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionIntent;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionSubjects;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.Kind;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceSanitizer;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorInputCoercer;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorMicroGraphRunner;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Authorized public adapter over the execution data-control kernel.
 *
 * <p>This service owns the trust transition. Caller fields remain untrusted until target,
 * fixture, environment, and identity scope have been verified. It then mints the fixed internal
 * purpose {@value #AUTHORIZED_PURPOSE}, snapshots mutable dependencies, creates a short-lived
 * test kernel, sanitizes terminal evidence, and only then crosses the persistence boundary.</p>
 */
public final class TestExecutionApiService {

    public static final String AUTHORIZED_PURPOSE = "GRAPH_CONTRACT_TEST";
    public static final String AUTHORIZED_OPERATOR_PURPOSE = "OPERATOR_UNIT_TEST";
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> FIXTURE_CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> CONTROL_CONTEXT_KEYS = Set.of(
            "controlplan", "fixturebundle", "fixturebundleref", "testmode", "executionpurpose");
    private static final int MAX_CONTEXT_BYTES = 1_048_576;
    private static final int MAX_METADATA_BYTES = 16_384;

    private final GatewayGraphService graphService;
    private final OperatorRegistry operatorRegistry;
    private final ResourceRegistry resourceRegistry;
    private final BlgeExpressionEvaluator expressionEvaluator;
    private final ObjectMapper objectMapper;
    private final FixtureBundleRepository fixtureRepository;
    private final TestRunRepository runRepository;
    private final TestSecurityEventRepository securityEvents;
    private final TestReplayPayloadService replayPayloads;
    private final TestEvidenceIntegrityService evidenceIntegrity;
    private final TestEvidenceSanitizer sanitizer;
    private final TestRuntimeAdmissionGate admissions;
    private final Duration retention;

    public TestExecutionApiService(GatewayGraphService graphService,
                                   OperatorRegistry operatorRegistry,
                                   ResourceRegistry resourceRegistry,
                                   BlgeExpressionEvaluator expressionEvaluator,
                                   ObjectMapper objectMapper,
                                   FixtureBundleRepository fixtureRepository,
                                   TestRunRepository runRepository,
                                   TestSecurityEventRepository securityEvents,
                                   Duration retention) {
        this(graphService, operatorRegistry, resourceRegistry, expressionEvaluator, objectMapper,
                fixtureRepository, runRepository, securityEvents, retention, null,
                new TestEvidenceIntegrityService(objectMapper, VisualEvidenceSigner.unavailable()));
    }

    /**
     * Creates the public execution adapter with governed replay dependency resolution.
     *
     * @param graphService graph target registry
     * @param operatorRegistry frozen operator registry
     * @param resourceRegistry frozen resource registry
     * @param expressionEvaluator resource expression evaluator
     * @param objectMapper protocol mapper
     * @param fixtureRepository immutable fixture registry
     * @param runRepository isolated test-run store
     * @param securityEvents required security audit sink
     * @param retention test-run evidence retention
     * @param replayPayloads governed replay resolver; required only by REPLAY fixtures
     */
    public TestExecutionApiService(GatewayGraphService graphService,
                                   OperatorRegistry operatorRegistry,
                                   ResourceRegistry resourceRegistry,
                                   BlgeExpressionEvaluator expressionEvaluator,
                                   ObjectMapper objectMapper,
                                   FixtureBundleRepository fixtureRepository,
                                   TestRunRepository runRepository,
                                   TestSecurityEventRepository securityEvents,
                                   Duration retention,
                                   TestReplayPayloadService replayPayloads) {
        this(graphService, operatorRegistry, resourceRegistry, expressionEvaluator, objectMapper,
                fixtureRepository, runRepository, securityEvents, retention, replayPayloads,
                new TestEvidenceIntegrityService(objectMapper, VisualEvidenceSigner.unavailable()),
                TestRuntimeAdmissionGate.unbounded());
    }

    /**
     * Creates the complete public execution adapter with governed replay and evidence integrity.
     *
     * @param graphService graph target registry
     * @param operatorRegistry frozen operator registry
     * @param resourceRegistry frozen resource registry
     * @param expressionEvaluator resource expression evaluator
     * @param objectMapper protocol mapper
     * @param fixtureRepository immutable fixture registry
     * @param runRepository isolated test-run store
     * @param securityEvents required security audit sink
     * @param retention test-run evidence retention
     * @param replayPayloads governed replay resolver; required only by REPLAY fixtures
     * @param evidenceIntegrity detached test-evidence signing and verification boundary
     */
    public TestExecutionApiService(GatewayGraphService graphService,
                                   OperatorRegistry operatorRegistry,
                                   ResourceRegistry resourceRegistry,
                                   BlgeExpressionEvaluator expressionEvaluator,
                                   ObjectMapper objectMapper,
                                   FixtureBundleRepository fixtureRepository,
                                   TestRunRepository runRepository,
                                   TestSecurityEventRepository securityEvents,
                                   Duration retention,
                                   TestReplayPayloadService replayPayloads,
                                   TestEvidenceIntegrityService evidenceIntegrity) {
        this(graphService, operatorRegistry, resourceRegistry, expressionEvaluator, objectMapper,
                fixtureRepository, runRepository, securityEvents, retention, replayPayloads,
                evidenceIntegrity, TestRuntimeAdmissionGate.unbounded());
    }

    /**
     * Creates the complete adapter with distributed multi-dimensional admission control.
     *
     * @param graphService graph target registry
     * @param operatorRegistry frozen operator registry
     * @param resourceRegistry frozen resource registry
     * @param expressionEvaluator resource expression evaluator
     * @param objectMapper protocol mapper
     * @param fixtureRepository immutable fixture registry
     * @param runRepository isolated test-run store
     * @param securityEvents required security audit sink
     * @param retention test-run evidence retention
     * @param replayPayloads governed replay resolver
     * @param evidenceIntegrity detached evidence signing boundary
     * @param admissions database-authoritative capacity gate
     */
    public TestExecutionApiService(GatewayGraphService graphService,
                                   OperatorRegistry operatorRegistry,
                                   ResourceRegistry resourceRegistry,
                                   BlgeExpressionEvaluator expressionEvaluator,
                                   ObjectMapper objectMapper,
                                   FixtureBundleRepository fixtureRepository,
                                   TestRunRepository runRepository,
                                   TestSecurityEventRepository securityEvents,
                                   Duration retention,
                                   TestReplayPayloadService replayPayloads,
                                   TestEvidenceIntegrityService evidenceIntegrity,
                                   TestRuntimeAdmissionGate admissions) {
        this.graphService = Objects.requireNonNull(graphService, "graphService");
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.resourceRegistry = Objects.requireNonNull(resourceRegistry, "resourceRegistry");
        this.expressionEvaluator = Objects.requireNonNull(expressionEvaluator, "expressionEvaluator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.fixtureRepository = Objects.requireNonNull(fixtureRepository, "fixtureRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.replayPayloads = replayPayloads;
        this.evidenceIntegrity = Objects.requireNonNull(evidenceIntegrity, "evidenceIntegrity");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.sanitizer = new TestEvidenceSanitizer(objectMapper);
        this.retention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofDays(30) : retention;
    }

    /** Executes one request after authorization and stores full sanitized evidence. */
    public TestExecutionApiResponse execute(TestExecutionApiRequest request,
                                            IntegrationRequestContext identity) {
        return execute(request, identity, admissions);
    }

    /**
     * Executes a suite child under the suite's already-held all-dimension parent permit.
     *
     * <p>This package-private entry cannot be reached by the HTTP adapter. It prevents a suite from
     * acquiring tenant/operator/dependency capacity twice while preserving the same authorization,
     * fixture, isolated-engine, sanitization, signing, and persistence path as a direct run.</p>
     */
    TestExecutionApiResponse executeAdmittedSuiteGraphCase(
            TestExecutionApiRequest request,
            IntegrationRequestContext identity) {
        return execute(request, identity, TestRuntimeAdmissionGate.unbounded());
    }

    private TestExecutionApiResponse execute(
            TestExecutionApiRequest request,
            IntegrationRequestContext identity,
            TestRuntimeAdmissionGate admissionGate) {
        requireTestIdentity(identity);
        validateRequest(request, identity);
        Graph graph = requireGraph(request.target(), identity);
        GraphExecutionTargetSnapshot target = GraphExecutionTargetSnapshot.capture(
                objectMapper, graph, resourceRegistry);
        requireTargetFingerprint(request.target(), target.fingerprint(), identity);
        try {
            graphService.validateInput(graph.name(), new GraphContext(request.context()));
        } catch (IllegalArgumentException invalidInput) {
            throw badRequest(identity, "RG.TEST.GRAPH_INPUT_INVALID", invalidInput.getMessage(), Map.of());
        }

        ResolvedFixture fixture = resolveFixture(request, target.fingerprint(), identity);
        ResolvedReplayPayloads resolvedReplays = resolveReplayPayloads(fixture.bundle(), identity);
        ResourceFixtureRuntime resourceRuntime = new ResourceFixtureRuntime(
                target.resourceRegistry(), expressionEvaluator, objectMapper);
        TestRunService kernel = new TestRunService(operatorRegistry, objectMapper, resourceRuntime);
        Map<String, Object> metadata = executionMetadata(request, identity, target);
        TestExecutionResult result = kernel.execute(new TestExecutionRequest(
                target.graph(), new GraphContext(request.context()), fixture.bundle(), AUTHORIZED_PURPOSE,
                target.fingerprint(), fixture.source(), metadata, target.certificationEligible(),
                resolvedReplays), compiled -> admissionGate.admit(identity,
                admissionIntent(Kind.GRAPH, request, target.fingerprint(),
                        compiled, target.dependencyFingerprints().keySet())));
        SecuredEvidence secured = secureEvidence(sanitizer.sanitize(result.evidence()));
        TestRunRecord record = new TestRunRecord(secured.evidence().runId(), identity.tenantId(),
                identity.organizationId(), identity.projectId(), identity.environmentId(), identity.actorId(),
                new TestExecutionApiRequest.Target("GRAPH", graph.name(), target.fingerprint()),
                fixture.reference(), request.verbosity(), result.plan(), secured.evidence(), secured.integrity(),
                secured.evidence().completedAt(), secured.evidence().completedAt().plus(retention));
        try {
            runRepository.create(record);
        } catch (RuntimeException persistenceFailure) {
            SecuredEvidence incomplete = secureEvidence(
                    evidenceIncomplete(secured.evidence(), persistenceFailure));
            return response(record, result.plan(), incomplete.evidence(), incomplete.integrity(),
                    request.verbosity());
        }
        return response(record, result.plan(), secured.evidence(), secured.integrity(), request.verbosity());
    }

    /** Returns the current graph contract and composite fingerprint needed to bind fixtures. */
    public TestGraphTargetDescriptor describeGraphTarget(String graphName,
                                                         IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        TestExecutionApiRequest.Target requested = new TestExecutionApiRequest.Target(
                "GRAPH", normalized(graphName), "");
        Graph graph = requireGraph(requested, identity);
        GraphExecutionTargetSnapshot target = GraphExecutionTargetSnapshot.capture(
                objectMapper, graph, resourceRegistry);
        return new TestGraphTargetDescriptor("",
                new TestExecutionApiRequest.Target("GRAPH", graph.name(), target.fingerprint()),
                graphService.requireContract(graph.name()), target.dependencyFingerprints(),
                "CONSERVATIVE_ALL_REGISTERED", target.certificationEligible(), target.certificationGaps());
    }

    /** Returns the frozen binding, schema and testability facts needed to author operator fixtures. */
    public TestOperatorTargetDescriptor describeOperatorTarget(String operatorRef,
                                                               IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        OperatorExecutionTargetSnapshot target = requireOperator(operatorRef, identity);
        return new TestOperatorTargetDescriptor("",
                new TestExecutionApiRequest.Target("OPERATOR", target.operatorRef(), target.fingerprint()),
                target.implementationFingerprint(), target.runtimeBindingStateFingerprint(),
                target.schemaFingerprint(), target.composabilityFingerprint(), target.composabilityManifest(),
                target.inputSchema(),
                target.outputSchema(), target.executionModel(), target.sideEffectType(), target.idempotency(),
                target.sideEffectProtocol(), target.testabilityClass(),
                target.resourceDependencyFingerprints(), target.dependencyPolicy(),
                target.executionSupported(), target.certificationEligible(),
                target.certificationRequirements(), target.certificationGaps());
    }

    /**
     * Executes one frozen synchronous operator as a one-node BLOGE graph and persists sanitized evidence.
     *
     * @param operatorRef path-bound registry reference
     * @param request versioned operator execution request
     * @param identity verified integration identity
     * @return controlled execution response using the common evidence contract
     */
    public TestExecutionApiResponse executeOperator(String operatorRef,
                                                    TestOperatorExecutionApiRequest request,
                                                    IntegrationRequestContext identity) {
        return executeOperator(operatorRef, request, identity, admissions);
    }

    /** Executes an operator suite child under its already-held parent capacity permit. */
    TestExecutionApiResponse executeAdmittedSuiteOperatorCase(
            String operatorRef,
            TestOperatorExecutionApiRequest request,
            IntegrationRequestContext identity) {
        return executeOperator(operatorRef, request, identity,
                TestRuntimeAdmissionGate.unbounded());
    }

    private TestExecutionApiResponse executeOperator(
            String operatorRef,
            TestOperatorExecutionApiRequest request,
            IntegrationRequestContext identity,
            TestRuntimeAdmissionGate admissionGate) {
        requireTestIdentity(identity);
        validateOperatorRequest(operatorRef, request, identity);
        OperatorExecutionTargetSnapshot target = requireOperator(operatorRef, identity);
        if (!target.executionSupported()) {
            throw badRequest(identity, "RG.TEST.OPERATOR_EXECUTION_MODEL_UNSUPPORTED",
                    "testing-control-plane v1 only executes synchronous operator bindings.",
                    Map.of("executionModel", target.executionModel()));
        }
        requireTargetFingerprint(request.target(), target.fingerprint(), identity);
        ResolvedFixture fixture = resolveFixture(request.fixtureBundle(), request.fixtureBundleRef(),
                target.fingerprint(), identity);
        ResolvedReplayPayloads resolvedReplays = resolveReplayPayloads(fixture.bundle(), identity);
        Object typedInput;
        try {
            typedInput = OperatorInputCoercer.coerce(request.input(), target.metadata(), objectMapper);
        } catch (IllegalArgumentException invalidInput) {
            throw badRequest(identity, "RG.TEST.OPERATOR_INPUT_INVALID", invalidInput.getMessage(), Map.of());
        }
        ResourceFixtureRuntime resourceRuntime = new ResourceFixtureRuntime(
                target.resourceRegistry(), expressionEvaluator, objectMapper);
        TestRunService kernel = new TestRunService(operatorRegistry, objectMapper, resourceRuntime);
        OperatorMicroGraphRunner.Result result = new OperatorMicroGraphRunner(kernel).execute(
                new OperatorMicroGraphRunner.Request(target.operatorRef(), target.synchronousOperator(),
                        target.fingerprint(), typedInput, fixture.bundle(), AUTHORIZED_OPERATOR_PURPOSE,
                        fixture.source(), target.certificationEligible(),
                        operatorExecutionMetadata(request, identity, target), resolvedReplays),
                compiled -> admissionGate.admit(identity,
                        admissionIntent(Kind.OPERATOR, request,
                                target.fingerprint(), compiled,
                                target.resourceDependencyFingerprints().keySet())));
        SecuredEvidence secured = secureEvidence(sanitizer.sanitize(result.execution().evidence()));
        TestRunRecord record = new TestRunRecord(secured.evidence().runId(), identity.tenantId(),
                identity.organizationId(), identity.projectId(), identity.environmentId(), identity.actorId(),
                new TestExecutionApiRequest.Target("OPERATOR", target.operatorRef(), target.fingerprint()),
                fixture.reference(), request.verbosity(), result.execution().plan(), secured.evidence(),
                secured.integrity(), secured.evidence().completedAt(),
                secured.evidence().completedAt().plus(retention));
        try {
            runRepository.create(record);
        } catch (RuntimeException persistenceFailure) {
            SecuredEvidence incomplete = secureEvidence(
                    evidenceIncomplete(secured.evidence(), persistenceFailure));
            return response(record, result.execution().plan(), incomplete.evidence(),
                    incomplete.integrity(), request.verbosity());
        }
        return response(record, result.execution().plan(), secured.evidence(),
                secured.integrity(), request.verbosity());
    }

    /** Executes a bounded set of independent requests sequentially. */
    public TestExecutionBatchResponse executeBatch(TestExecutionBatchRequest request,
                                                   IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        if (request == null || !TestExecutionBatchRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw badRequest(identity, "RG.TEST.BATCH_SCHEMA_VERSION_INVALID",
                    "Unsupported test execution batch schemaVersion.", Map.of());
        }
        if (request.executions().isEmpty() || request.executions().size() > TestExecutionBatchRequest.MAX_EXECUTIONS) {
            throw badRequest(identity, "RG.TEST.BATCH_SIZE_INVALID",
                    "A batch must contain between 1 and 100 executions.",
                    Map.of("maximum", TestExecutionBatchRequest.MAX_EXECUTIONS));
        }
        return new TestExecutionBatchResponse("", request.executions().stream()
                .map(item -> execute(item, identity)).toList());
    }

    /** Returns persisted sanitized evidence with a caller-selected projection. */
    public TestExecutionApiResponse find(String runId, TestExecutionApiRequest.Verbosity verbosity,
                                         IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        TestRunRecord record;
        try {
            record = runRepository.find(identity.tenantId(), identity.environmentId(), normalized(runId))
                    .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.TEST.RUN_NOT_FOUND", "Test run was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException notFound) {
            throw notFound;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.RUN_STORE_UNAVAILABLE",
                    "The independent test-run store is unavailable.");
        }
        verifyStoredEvidence(record, identity);
        TestExecutionApiRequest.Verbosity effective = verbosity == null ? record.requestedVerbosity() : verbosity;
        return response(record, record.plan(), record.evidence(), record.integrity(), effective);
    }

    /** Registers one immutable, clearance-checked fixture revision. */
    public StoredFixtureBundle registerFixture(String fixtureBundleId,
                                               FixtureBundleRegistrationRequest request,
                                               IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        if (request == null || !FixtureBundleRegistrationRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || request.fixtureBundle() == null) {
            throw badRequest(identity, "RG.TEST.FIXTURE_REQUEST_INVALID",
                    "A versioned fixture registration request is required.", Map.of());
        }
        FixtureBundle bundle = request.fixtureBundle();
        if (!normalized(fixtureBundleId).equals(bundle.fixtureBundleId()) || bundle.revision() <= 0) {
            throw badRequest(identity, "RG.TEST.FIXTURE_IDENTITY_INVALID",
                    "Path id, fixtureBundleId, and positive revision must identify the same immutable revision.",
                    Map.of());
        }
        requireClearance(bundle.classification(), identity);
        String targetFingerprint = currentTargetFingerprint(request.target(), identity);
        requireTargetFingerprint(request.target(), targetFingerprint, identity);
        if (!targetFingerprint.equals(bundle.targetFingerprint())) {
            throw conflict(identity, "RG.TEST.FIXTURE_TARGET_STALE",
                    "Fixture targetFingerprint does not identify the current frozen target dependencies.",
                    Map.of("currentTargetFingerprint", targetFingerprint));
        }
        resolveReplayPayloads(bundle, identity);
        String fingerprint = ProtocolFingerprint.of(objectMapper, bundle);
        StoredFixtureBundle stored = new StoredFixtureBundle("", identity.tenantId(), identity.environmentId(),
                bundle.fixtureBundleId(), bundle.revision(), fingerprint, bundle, Instant.now(), identity.actorId());
        try {
            return fixtureRepository.create(stored);
        } catch (FixtureBundleConflictException conflict) {
            throw conflict(identity, "RG.TEST.FIXTURE_REVISION_CONFLICT", conflict.getMessage(), Map.of());
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.FIXTURE_STORE_UNAVAILABLE",
                    "The independent fixture registry is unavailable.");
        }
    }

    /** Resolves one governed fixture revision after scope and classification checks. */
    public StoredFixtureBundle findFixture(String fixtureBundleId, long revision,
                                           IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        StoredFixtureBundle stored;
        try {
            stored = fixtureRepository.find(identity.tenantId(), identity.environmentId(),
                            normalized(fixtureBundleId), revision)
                    .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.TEST.FIXTURE_NOT_FOUND", "Fixture bundle was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException notFound) {
            throw notFound;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.FIXTURE_STORE_UNAVAILABLE",
                    "The independent fixture registry is unavailable.");
        }
        requireClearance(stored.bundle().classification(), identity);
        return stored;
    }

    private ResolvedFixture resolveFixture(TestExecutionApiRequest request, String targetFingerprint,
                                           IntegrationRequestContext identity) {
        return resolveFixture(request.fixtureBundle(), request.fixtureBundleRef(), targetFingerprint, identity);
    }

    private ResolvedReplayPayloads resolveReplayPayloads(FixtureBundle bundle,
                                                          IntegrationRequestContext identity) {
        boolean hasReplay = bundle.rules().stream().filter(Objects::nonNull)
                .anyMatch(rule -> rule.behavior().kind()
                        == com.leanowtech.bloge.gateway.testing.domain.FixtureRule.BehaviorKind.REPLAY);
        if (!hasReplay) {
            return ResolvedReplayPayloads.empty();
        }
        if (replayPayloads == null) {
            throw unavailable(identity, "RG.TEST.REPLAY_RESOLVER_UNAVAILABLE",
                    "Governed replay payload resolution is unavailable.");
        }
        return replayPayloads.resolve(bundle, identity);
    }

    private ResolvedFixture resolveFixture(FixtureBundle inline,
                                           TestExecutionApiRequest.FixtureBundleRef fixtureReference,
                                           String targetFingerprint,
                                           IntegrationRequestContext identity) {
        if (inline != null) {
            requireClearance(inline.classification(), identity);
            if (!targetFingerprint.equals(inline.targetFingerprint())) {
                throw conflict(identity, "RG.TEST.FIXTURE_TARGET_STALE",
                        "Inline fixture targetFingerprint does not match the frozen target.",
                        Map.of("currentTargetFingerprint", targetFingerprint));
            }
            String fingerprint = ProtocolFingerprint.of(objectMapper, inline);
            return new ResolvedFixture(inline, TestExecutionRequest.FixtureSource.INLINE,
                    new TestExecutionApiResponse.ResolvedFixtureBundleRef("INLINE", inline.fixtureBundleId(),
                            inline.revision(), fingerprint));
        }
        TestExecutionApiRequest.FixtureBundleRef reference = fixtureReference;
        StoredFixtureBundle stored = findFixture(reference.fixtureBundleId(), reference.revision(), identity);
        if (!reference.fingerprint().isBlank() && !reference.fingerprint().equals(stored.fingerprint())) {
            throw conflict(identity, "RG.TEST.FIXTURE_FINGERPRINT_CONFLICT",
                    "Stored fixture fingerprint differs from the requested immutable reference.", Map.of());
        }
        if (!targetFingerprint.equals(stored.bundle().targetFingerprint())) {
            throw conflict(identity, "RG.TEST.FIXTURE_TARGET_STALE",
                    "Stored fixture targets a stale graph dependency snapshot.",
                    Map.of("currentTargetFingerprint", targetFingerprint));
        }
        return new ResolvedFixture(stored.bundle(), TestExecutionRequest.FixtureSource.STORED,
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("STORED", stored.fixtureBundleId(),
                        stored.revision(), stored.fingerprint()));
    }

    private void validateRequest(TestExecutionApiRequest request, IntegrationRequestContext identity) {
        if (request == null || !TestExecutionApiRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw badRequest(identity, "RG.TEST.REQUEST_SCHEMA_VERSION_INVALID",
                    "Unsupported test execution request schemaVersion.", Map.of());
        }
        if (!AUTHORIZED_PURPOSE.equals(request.executionPurpose())) {
            throw badRequest(identity, "RG.TEST.EXECUTION_PURPOSE_INVALID",
                    "executionPurpose must explicitly be GRAPH_CONTRACT_TEST; authorization remains server-owned.",
                    Map.of());
        }
        boolean inline = request.fixtureBundle() != null;
        boolean stored = request.fixtureBundleRef() != null;
        if (inline == stored) {
            throw badRequest(identity, "RG.TEST.FIXTURE_SOURCE_INVALID",
                    "Exactly one of fixtureBundle or fixtureBundleRef is required.", Map.of());
        }
        if (request.target() == null || !"GRAPH".equals(request.target().kind())
                || request.target().id().isBlank()) {
            throw badRequest(identity, "RG.TEST.TARGET_INVALID",
                    "Stage 2 requires a GRAPH target with a registered graph id.", Map.of());
        }
        request.context().keySet().stream().map(TestExecutionApiService::compactKey)
                .filter(CONTROL_CONTEXT_KEYS::contains).findFirst().ifPresent(key -> {
                    securityEvent(identity, "PRODUCTION_DATA_PLANE_CONTROL_FIELD", "REJECTED",
                            "RG.TEST.CONTROL_IN_BUSINESS_CONTEXT", Map.of("field", key));
                    throw badRequest(identity, "RG.TEST.CONTROL_IN_BUSINESS_CONTEXT",
                            "Execution controls must use the test protocol, never business context.",
                            Map.of("field", key));
                });
        requireBounded(request.context(), MAX_CONTEXT_BYTES, "context", identity);
        requireBounded(request.metadata(), MAX_METADATA_BYTES, "metadata", identity);
        if (request.metadata().containsKey(null) || request.metadata().containsValue(null)) {
            throw badRequest(identity, "RG.TEST.METADATA_INVALID",
                    "metadata keys and values must be non-null protocol facts.", Map.of());
        }
    }

    private void validateOperatorRequest(String operatorRef, TestOperatorExecutionApiRequest request,
                                         IntegrationRequestContext identity) {
        if (request == null
                || !TestOperatorExecutionApiRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw badRequest(identity, "RG.TEST.OPERATOR_REQUEST_SCHEMA_VERSION_INVALID",
                    "Unsupported operator execution request schemaVersion.", Map.of());
        }
        if (!AUTHORIZED_OPERATOR_PURPOSE.equals(request.executionPurpose())) {
            throw badRequest(identity, "RG.TEST.EXECUTION_PURPOSE_INVALID",
                    "executionPurpose must explicitly be OPERATOR_UNIT_TEST; authorization remains server-owned.",
                    Map.of());
        }
        boolean inline = request.fixtureBundle() != null;
        boolean stored = request.fixtureBundleRef() != null;
        if (inline == stored) {
            throw badRequest(identity, "RG.TEST.FIXTURE_SOURCE_INVALID",
                    "Exactly one of fixtureBundle or fixtureBundleRef is required.", Map.of());
        }
        String pathRef = normalized(operatorRef);
        if (request.target() == null || !"OPERATOR".equals(request.target().kind())
                || request.target().id().isBlank() || !pathRef.equals(request.target().id())) {
            throw badRequest(identity, "RG.TEST.OPERATOR_TARGET_INVALID",
                    "Path and request target must identify the same registered OPERATOR.", Map.of());
        }
        requireBounded(request.input(), MAX_CONTEXT_BYTES, "input", identity);
        requireBounded(request.metadata(), MAX_METADATA_BYTES, "metadata", identity);
        if (request.metadata().containsKey(null) || request.metadata().containsValue(null)) {
            throw badRequest(identity, "RG.TEST.METADATA_INVALID",
                    "metadata keys and values must be non-null protocol facts.", Map.of());
        }
    }

    private Graph requireGraph(TestExecutionApiRequest.Target target, IntegrationRequestContext identity) {
        if (target == null || !"GRAPH".equals(target.kind()) || target.id().isBlank()) {
            throw badRequest(identity, "RG.TEST.TARGET_INVALID", "A GRAPH target is required.", Map.of());
        }
        try {
            return graphService.requireGraph(target.id());
        } catch (IllegalArgumentException notFound) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.TEST.TARGET_NOT_FOUND", "Graph target was not found.", identity.correlationId(), Map.of()));
        }
    }

    private OperatorExecutionTargetSnapshot requireOperator(String operatorRef,
                                                             IntegrationRequestContext identity) {
        try {
            return OperatorExecutionTargetSnapshot.capture(
                    objectMapper, normalized(operatorRef), operatorRegistry, resourceRegistry);
        } catch (IllegalArgumentException notFound) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.TEST.OPERATOR_TARGET_NOT_FOUND", "Operator target was not found.",
                    identity.correlationId(), Map.of()));
        }
    }

    private String currentTargetFingerprint(TestExecutionApiRequest.Target target,
                                            IntegrationRequestContext identity) {
        if (target == null) {
            throw badRequest(identity, "RG.TEST.TARGET_INVALID", "A fixture target is required.", Map.of());
        }
        return switch (target.kind()) {
            case "GRAPH" -> GraphExecutionTargetSnapshot.capture(
                    objectMapper, requireGraph(target, identity), resourceRegistry).fingerprint();
            case "OPERATOR" -> requireOperator(target.id(), identity).fingerprint();
            default -> throw badRequest(identity, "RG.TEST.TARGET_INVALID",
                    "Fixture target kind must be GRAPH or OPERATOR.", Map.of("kind", target.kind()));
        };
    }

    private void requireTestIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String environment = identity.environmentId().toLowerCase(Locale.ROOT);
        if (!ENABLED_ENVIRONMENTS.contains(environment)) {
            securityEvent(identity, "TEST_PURPOSE_PRODUCTION_TOUCH", "REJECTED",
                    "RG.TEST.ENVIRONMENT_FORBIDDEN", Map.of("allowedEnvironments", ENABLED_ENVIRONMENTS));
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Caller-driven execution control is restricted to test and staging identities.",
                    identity.correlationId(), Map.of("environmentId", identity.environmentId())));
        }
    }

    private void requireTargetFingerprint(TestExecutionApiRequest.Target requested, String actual,
                                          IntegrationRequestContext identity) {
        if (!requested.fingerprint().isBlank() && !requested.fingerprint().equals(actual)) {
            throw conflict(identity, "RG.TEST.TARGET_FINGERPRINT_CONFLICT",
                    "Target changed after the caller selected it.", Map.of("currentTargetFingerprint", actual));
        }
    }

    private void requireClearance(String classification, IntegrationRequestContext identity) {
        String required = normalized(classification).isBlank()
                ? "INTERNAL" : classification.trim().toUpperCase(Locale.ROOT);
        if (!FIXTURE_CLASSIFICATIONS.contains(required)) {
            throw badRequest(identity, "RG.TEST.FIXTURE_CLASSIFICATION_INVALID",
                    "Fixture classification must be PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED.",
                    Map.of("classification", required));
        }
        if (!identity.hasClearanceAtLeast(required)) {
            securityEvent(identity, "FIXTURE_CLEARANCE_VIOLATION", "REJECTED",
                    "RG.TEST.FIXTURE_CLEARANCE_FORBIDDEN", Map.of("classification", required));
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.FIXTURE_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot access this fixture classification.",
                    identity.correlationId(), Map.of("classification", required)));
        }
    }

    private Map<String, Object> executionMetadata(TestExecutionApiRequest request,
                                                  IntegrationRequestContext identity,
                                                  GraphExecutionTargetSnapshot target) {
        Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("tenantId", identity.tenantId());
        metadata.put("organizationId", identity.organizationId());
        metadata.put("projectId", identity.projectId());
        metadata.put("environmentId", identity.environmentId());
        metadata.put("actorId", identity.actorId());
        metadata.put("correlationId", identity.correlationId());
        metadata.put("resourceDependencyFingerprints", target.dependencyFingerprints());
        metadata.put("resourceDependencyPolicy", "CONSERVATIVE_ALL_REGISTERED");
        metadata.put("targetCertificationEligible", target.certificationEligible());
        metadata.put("targetCertificationGaps", target.certificationGaps());
        return Map.copyOf(metadata);
    }

    private Map<String, Object> operatorExecutionMetadata(TestOperatorExecutionApiRequest request,
                                                          IntegrationRequestContext identity,
                                                          OperatorExecutionTargetSnapshot target) {
        Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("tenantId", identity.tenantId());
        metadata.put("organizationId", identity.organizationId());
        metadata.put("projectId", identity.projectId());
        metadata.put("environmentId", identity.environmentId());
        metadata.put("actorId", identity.actorId());
        metadata.put("correlationId", identity.correlationId());
        metadata.put("implementationFingerprint", target.implementationFingerprint());
        metadata.put("runtimeBindingStateFingerprint", target.runtimeBindingStateFingerprint());
        metadata.put("schemaFingerprint", target.schemaFingerprint());
        metadata.put("composabilityFingerprint", target.composabilityFingerprint());
        metadata.put("resourceDependencyFingerprints", target.resourceDependencyFingerprints());
        metadata.put("resourceDependencyPolicy", target.dependencyPolicy());
        metadata.put("baselineTestabilityClass", target.testabilityClass());
        metadata.put("targetCertificationEligible", target.certificationEligible());
        metadata.put("targetCertificationRequirements", target.certificationRequirements());
        metadata.put("targetCertificationGaps", target.certificationGaps());
        return Map.copyOf(metadata);
    }

    /**
     * Freezes the complete operator/dependency quota closure for one exact suite target.
     *
     * <p>The suite runner calls this before it creates a RUNNING checkpoint, then reserves that
     * capacity once for all serial cases. Graph resource dependencies intentionally use the same
     * conservative all-registered policy as target fingerprinting because a runtime expression can
     * select a resource id dynamically.</p>
     */
    AdmissionSubjects admissionSubjects(
            TestExecutionApiRequest.Target requested,
            IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        if (requested == null) {
            throw badRequest(identity, "RG.TEST.TARGET_INVALID",
                    "An exact suite target is required for capacity admission.", Map.of());
        }
        try {
            return switch (requested.kind()) {
                case "GRAPH" -> {
                    Graph graph = requireGraph(requested, identity);
                    GraphExecutionTargetSnapshot target = GraphExecutionTargetSnapshot.capture(
                            objectMapper, graph, resourceRegistry);
                    requireTargetFingerprint(requested, target.fingerprint(), identity);
                    yield AdmissionSubjects.from(
                            new InvocationInventoryBuilder(operatorRegistry).build(
                                    target.graph(), target.fingerprint()),
                            target.dependencyFingerprints().keySet());
                }
                case "OPERATOR" -> {
                    OperatorExecutionTargetSnapshot target = requireOperator(requested.id(), identity);
                    requireTargetFingerprint(requested, target.fingerprint(), identity);
                    Graph graph = OperatorMicroGraphRunner.microGraph(
                            target.operatorRef(), target.synchronousOperator());
                    yield AdmissionSubjects.from(
                            new InvocationInventoryBuilder(operatorRegistry).build(
                                    graph, target.fingerprint()),
                            target.resourceDependencyFingerprints().keySet());
                }
                default -> throw badRequest(identity, "RG.TEST.TARGET_INVALID",
                        "Suite capacity admission requires a GRAPH or OPERATOR target.", Map.of());
            };
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException inventoryFailure) {
            throw unavailable(identity, "RG.TEST.ADMISSION_TARGET_INVENTORY_UNAVAILABLE",
                    "The exact suite target capacity closure cannot be frozen.");
        }
    }

    private AdmissionIntent admissionIntent(
            Kind kind,
            Object request,
            String targetFingerprint,
            CompiledExecutionControl compiled,
            Set<String> targetDependencyRefs) {
        AdmissionSubjects subjects = AdmissionSubjects.from(compiled, targetDependencyRefs);
        String intentFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testRuntimeAdmissionWorkIntent.v1",
                "request", request,
                "targetFingerprint", targetFingerprint,
                "planFingerprint", compiled.effectivePlan().planFingerprint()));
        return new AdmissionIntent(kind, UUID.randomUUID().toString(), intentFingerprint, "",
                subjects.operatorRefs(), subjects.dependencyRefs());
    }

    private TestExecutionApiResponse response(TestRunRecord record,
                                              com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan plan,
                                              TestRunEvidence evidence,
                                              TestEvidenceIntegrity integrity,
                                              TestExecutionApiRequest.Verbosity verbosity) {
        TestExecutionApiRequest.Verbosity selected = verbosity == null
                ? TestExecutionApiRequest.Verbosity.STANDARD : verbosity;
        TestRunEvidence projected = project(evidence, selected);
        TestEvidenceIntegrity projectedIntegrity = evidenceIntegrity.project(integrity, projected,
                TestEvidenceIntegrity.Projection.valueOf(selected.name()));
        return new TestExecutionApiResponse("", evidence.runId(), record.target(), record.fixtureBundleRef(),
                plan, projectedIntegrity, projected);
    }

    /**
     * Revalidates a complete child response before suite aggregation trusts its evidence class.
     *
     * @param response child graph or operator execution response
     * @return true only when the response carries complete independently verifiable evidence
     */
    boolean verifyEvidence(TestExecutionApiResponse response) {
        return response != null && response.evidence() != null && response.integrity() != null
                && response.integrity().independentlyVerifiable()
                && evidenceIntegrity.verify(response.evidence(), response.integrity())
                == TestEvidenceIntegrityService.Verification.VERIFIED;
    }

    private SecuredEvidence secureEvidence(TestRunEvidence evidence) {
        TestEvidenceIntegrityService.SealResult sealed = evidenceIntegrity.seal(evidence);
        if (sealed.verified()) {
            return new SecuredEvidence(evidence, sealed.integrity());
        }
        TestRunEvidence incomplete = evidenceIntegrityIncomplete(evidence, sealed.failureCode());
        return new SecuredEvidence(incomplete, evidenceIntegrity.unavailable(incomplete));
    }

    private void verifyStoredEvidence(TestRunRecord record, IntegrationRequestContext identity) {
        TestEvidenceIntegrityService.Verification verification =
                evidenceIntegrity.verify(record.evidence(), record.integrity());
        if (verification == TestEvidenceIntegrityService.Verification.VERIFIED) {
            return;
        }
        if (verification == TestEvidenceIntegrityService.Verification.UNAVAILABLE) {
            throw unavailable(identity, "RG.TEST.EVIDENCE_VERIFICATION_UNAVAILABLE",
                    "The test-evidence verification authority is unavailable.");
        }
        securityEvent(identity, "TEST_EVIDENCE_INTEGRITY_INVALID", "REJECTED",
                "RG.TEST.EVIDENCE_INTEGRITY_INVALID",
                Map.of("runId", record.runId(), "verification", verification.name()));
        throw conflict(identity, "RG.TEST.EVIDENCE_INTEGRITY_INVALID",
                "Persisted test evidence is unsigned or failed integrity verification.", Map.of());
    }

    private static TestRunEvidence project(TestRunEvidence evidence,
                                           TestExecutionApiRequest.Verbosity verbosity) {
        TestExecutionApiRequest.Verbosity safe = verbosity == null
                ? TestExecutionApiRequest.Verbosity.STANDARD : verbosity;
        if (safe == TestExecutionApiRequest.Verbosity.FULL) {
            return evidence;
        }
        var assertions = evidence.assertionResults().stream().map(assertion ->
                new TestRunEvidence.AssertionResult(assertion.scope(), assertion.path(), assertion.passed(),
                        null, null, assertion.diagnostic())).toList();
        var nodes = safe == TestExecutionApiRequest.Verbosity.SUMMARY ? List.<TestRunEvidence.NodeTrace>of()
                : evidence.nodeTrace().stream().map(node -> new TestRunEvidence.NodeTrace(
                        node.nodeId(), node.operatorRef(), node.status(), node.fidelity(),
                        null, null, node.errorCode(), node.durationMs(), node.invocationSiteId(),
                        node.graphPath(), node.correlationKey(), node.occurrence(),
                        node.graphOccurrence(),
                        node.attempts().stream().map(attempt -> new TestRunEvidence.AttemptTrace(
                                attempt.attempt(), attempt.status(), attempt.fidelity(), null, null,
                                attempt.errorCode(), attempt.durationMs())).toList())).toList();
        var edges = safe == TestExecutionApiRequest.Verbosity.SUMMARY ? List.<TestRunEvidence.EdgeTrace>of()
                : evidence.edgeTrace().stream().map(edge ->
                        new TestRunEvidence.EdgeTrace(edge.edgeId(), edge.status(), null,
                                edge.graphPath(), edge.correlationKey(), edge.graphOccurrence(),
                                edge.fromInvocationSiteId(), edge.toInvocationSiteId())).toList();
        return new TestRunEvidence(evidence.schemaVersion(), evidence.runId(), evidence.status(),
                evidence.evidenceClass(), evidence.executionPurpose(), evidence.targetFingerprint(),
                evidence.fixtureBundleFingerprint(), evidence.planFingerprint(),
                evidence.semanticResultFingerprint(), evidence.startedAt(), evidence.completedAt(),
                nodes, edges, evidence.fixtureConsumptions(), assertions, evidence.diagnostics(),
                evidence.metadata());
    }

    private TestRunEvidence evidenceIncomplete(TestRunEvidence evidence, RuntimeException failure) {
        List<String> diagnostics = new java.util.ArrayList<>(evidence.diagnostics());
        diagnostics.add("Sanitized test evidence could not be persisted: "
                + failure.getClass().getSimpleName());
        TestRunEvidence incomplete = new TestRunEvidence(evidence.schemaVersion(), evidence.runId(),
                TestRunEvidence.Status.EVIDENCE_INCOMPLETE, TestRunEvidence.EvidenceClass.EXPLORATORY,
                evidence.executionPurpose(), evidence.targetFingerprint(), evidence.fixtureBundleFingerprint(),
                evidence.planFingerprint(), evidence.startedAt(), evidence.completedAt(), evidence.nodeTrace(),
                evidence.edgeTrace(), evidence.fixtureConsumptions(), evidence.assertionResults(), diagnostics,
                evidence.metadata());
        return TestSemanticResultFingerprint.attach(objectMapper, incomplete);
    }

    private TestRunEvidence evidenceIntegrityIncomplete(TestRunEvidence evidence, String failureCode) {
        List<String> diagnostics = new java.util.ArrayList<>(evidence.diagnostics());
        diagnostics.add("Test evidence integrity could not be established: "
                + normalized(failureCode));
        TestRunEvidence incomplete = new TestRunEvidence(evidence.schemaVersion(), evidence.runId(),
                TestRunEvidence.Status.EVIDENCE_INCOMPLETE, TestRunEvidence.EvidenceClass.EXPLORATORY,
                evidence.executionPurpose(), evidence.targetFingerprint(), evidence.fixtureBundleFingerprint(),
                evidence.planFingerprint(), evidence.startedAt(), evidence.completedAt(), evidence.nodeTrace(),
                evidence.edgeTrace(), evidence.fixtureConsumptions(), evidence.assertionResults(), diagnostics,
                evidence.metadata());
        return TestSemanticResultFingerprint.attach(objectMapper, incomplete);
    }

    private void requireBounded(Object value, int maximumBytes, String field,
                                IntegrationRequestContext identity) {
        try {
            if (objectMapper.writeValueAsBytes(value).length > maximumBytes) {
                throw badRequest(identity, "RG.TEST.REQUEST_FIELD_TOO_LARGE",
                        field + " exceeds the bounded protocol size.", Map.of("field", field,
                                "maximumBytes", maximumBytes));
            }
        } catch (JsonProcessingException failure) {
            throw badRequest(identity, "RG.TEST.REQUEST_FIELD_INVALID",
                    field + " cannot be serialized as protocol JSON.", Map.of("field", field));
        }
    }

    private void securityEvent(IntegrationRequestContext identity, String type, String outcome,
                               String reason, Map<String, Object> facts) {
        try {
            securityEvents.append(new TestSecurityEvent(0, Instant.now(), identity.correlationId(),
                    identity.tenantId(), identity.environmentId(), identity.actorId(), type, outcome, reason, facts));
        } catch (RuntimeException failure) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Test execution is unavailable because the required security audit sink cannot commit.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static IntegrationProblemException badRequest(IntegrationRequestContext identity,
                                                          String code, String title,
                                                          Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException conflict(IntegrationRequestContext identity,
                                                        String code, String title,
                                                        Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException unavailable(IntegrationRequestContext identity,
                                                           String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String compactKey(String value) {
        return normalized(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record ResolvedFixture(
            FixtureBundle bundle,
            TestExecutionRequest.FixtureSource source,
            TestExecutionApiResponse.ResolvedFixtureBundleRef reference
    ) {
    }

    private record SecuredEvidence(TestRunEvidence evidence, TestEvidenceIntegrity integrity) {
    }
}
