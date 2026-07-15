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
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceSanitizer;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final TestEvidenceSanitizer sanitizer;
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
        this.graphService = Objects.requireNonNull(graphService, "graphService");
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.resourceRegistry = Objects.requireNonNull(resourceRegistry, "resourceRegistry");
        this.expressionEvaluator = Objects.requireNonNull(expressionEvaluator, "expressionEvaluator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.fixtureRepository = Objects.requireNonNull(fixtureRepository, "fixtureRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.sanitizer = new TestEvidenceSanitizer(objectMapper);
        this.retention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofDays(30) : retention;
    }

    /** Executes one request after authorization and stores full sanitized evidence. */
    public TestExecutionApiResponse execute(TestExecutionApiRequest request,
                                            IntegrationRequestContext identity) {
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
        ResourceFixtureRuntime resourceRuntime = new ResourceFixtureRuntime(
                target.resourceRegistry(), expressionEvaluator, objectMapper);
        TestRunService kernel = new TestRunService(operatorRegistry, objectMapper, resourceRuntime);
        Map<String, Object> metadata = executionMetadata(request, identity, target);
        TestExecutionResult result = kernel.execute(new TestExecutionRequest(
                target.graph(), new GraphContext(request.context()), fixture.bundle(), AUTHORIZED_PURPOSE,
                target.fingerprint(), fixture.source(), metadata, target.certificationEligible()));
        TestRunEvidence sanitized = sanitizer.sanitize(result.evidence());
        TestRunRecord record = new TestRunRecord(sanitized.runId(), identity.tenantId(),
                identity.organizationId(), identity.projectId(), identity.environmentId(), identity.actorId(),
                new TestExecutionApiRequest.Target("GRAPH", graph.name(), target.fingerprint()),
                fixture.reference(), request.verbosity(), result.plan(), sanitized,
                sanitized.completedAt(), sanitized.completedAt().plus(retention));
        try {
            runRepository.create(record);
        } catch (RuntimeException persistenceFailure) {
            TestRunEvidence incomplete = evidenceIncomplete(sanitized, persistenceFailure);
            return response(record, result.plan(), incomplete, request.verbosity());
        }
        return response(record, result.plan(), sanitized, request.verbosity());
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
        TestExecutionApiRequest.Verbosity effective = verbosity == null ? record.requestedVerbosity() : verbosity;
        return response(record, record.plan(), record.evidence(), effective);
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
        Graph graph = requireGraph(request.target(), identity);
        GraphExecutionTargetSnapshot target = GraphExecutionTargetSnapshot.capture(
                objectMapper, graph, resourceRegistry);
        requireTargetFingerprint(request.target(), target.fingerprint(), identity);
        if (!target.fingerprint().equals(bundle.targetFingerprint())) {
            throw conflict(identity, "RG.TEST.FIXTURE_TARGET_STALE",
                    "Fixture targetFingerprint does not identify the current frozen graph dependencies.",
                    Map.of("currentTargetFingerprint", target.fingerprint()));
        }
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
        if (request.fixtureBundle() != null) {
            FixtureBundle inline = request.fixtureBundle();
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
        TestExecutionApiRequest.FixtureBundleRef reference = request.fixtureBundleRef();
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

    private TestExecutionApiResponse response(TestRunRecord record,
                                              com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan plan,
                                              TestRunEvidence evidence,
                                              TestExecutionApiRequest.Verbosity verbosity) {
        return new TestExecutionApiResponse("", evidence.runId(), record.target(), record.fixtureBundleRef(),
                plan, project(evidence, verbosity));
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
                evidence.fixtureBundleFingerprint(), evidence.planFingerprint(), evidence.startedAt(),
                evidence.completedAt(), nodes, edges, evidence.fixtureConsumptions(), assertions,
                evidence.diagnostics(), evidence.metadata());
    }

    private static TestRunEvidence evidenceIncomplete(TestRunEvidence evidence, RuntimeException failure) {
        List<String> diagnostics = new java.util.ArrayList<>(evidence.diagnostics());
        diagnostics.add("Sanitized test evidence could not be persisted: "
                + failure.getClass().getSimpleName());
        return new TestRunEvidence(evidence.schemaVersion(), evidence.runId(),
                TestRunEvidence.Status.EVIDENCE_INCOMPLETE, TestRunEvidence.EvidenceClass.EXPLORATORY,
                evidence.executionPurpose(), evidence.targetFingerprint(), evidence.fixtureBundleFingerprint(),
                evidence.planFingerprint(), evidence.startedAt(), evidence.completedAt(), evidence.nodeTrace(),
                evidence.edgeTrace(), evidence.fixtureConsumptions(), evidence.assertionResults(), diagnostics,
                evidence.metadata());
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
}
