package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.ResilienceConfig;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.BoundedStateExpression;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshotIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAgentSnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestation;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationStatusPublication;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityKeySetPublication;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationRunTrust;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationRunTrustAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolutionIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionPayload;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionProtocolIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationToken;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTrustProvider;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpaceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateModel;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpec;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpecIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompilationRequest;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanRejectedException;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorRunServiceTest {
    private static final String TARGET = fingerprint('a');
    private static final Instant COMPILED_AT = Instant.parse("2026-07-22T08:00:00Z");
    private static final String PURPOSE = "MIRROR_REHEARSAL";
    private static final String REPLAY_REF = "bloge-replay:customer-approved@7#"
            + fingerprint('9');
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "support", "test", "sg");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
    private final InMemoryVisualEvidenceSigner evidenceSigner = new InMemoryVisualEvidenceSigner();
    private final AtomicInteger externalCalls = new AtomicInteger();
    private final AtomicInteger internalCalls = new AtomicInteger();
    private final AtomicReference<Instant> observedDeadline = new AtomicReference<>();
    private Graph graph;
    private CapabilityClosure closure;
    private MirrorPlanCompiler compiler;
    private MirrorRunService runtime;

    @BeforeEach
    void setUp() {
        registry.register("customer.lookup", new ExternalReadOperator(externalCalls));
        registry.register("customer.format", new InternalOperator(internalCalls, observedDeadline));
        graph = graph();
        closure = closure();
        compiler = new MirrorPlanCompiler(registry, mapper);
        runtime = new MirrorRunService(registry, mapper, null,
                Clock.fixed(COMPILED_AT.plusSeconds(1), ZoneOffset.UTC), evidenceSigner);
    }

    @Test
    void executesTheExactCompiledGenerationAndNeverCallsTheExternalLeaf() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning(Map.of("customerId", "C-1")))));
        MirrorRunRequest request = request(compiled, SCOPE, PURPOSE);

        MirrorRunResult result = runtime.execute(request);

        assertThat(result.passed()).isTrue();
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(1);
        assertThat(observedDeadline).hasValue(COMPILED_AT.plus(Duration.ofMinutes(5)));
        assertThat(result.execution().plan().planFingerprint())
                .isEqualTo(compiled.plan().executionControlFingerprint());
        assertThat(result.execution().evidence().metadata())
                .containsEntry("mirrorPlanFingerprint", compiled.plan().planFingerprint())
                .containsEntry("capabilityClosureFingerprint",
                        compiled.plan().capabilityClosureFingerprint());
        assertThat(result.evidenceBundle().evidence().requestContextFingerprint())
                .isEqualTo(ProtocolFingerprint.of(mapper, request.context().asMap()));
        assertThat(result.evidenceBundle().evidence().planFingerprint())
                .isEqualTo(compiled.plan().planFingerprint());
        assertThat(result.evidenceBundle().evidence().resolutions())
                .isEqualTo(result.resolutions());
        assertThat(result.evidenceBundle().evidence().evidenceClass())
                .isEqualTo(com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence
                        .EvidenceClass.EXPLORATORY);
        assertThat(result.evidenceBundle().evidence().limitations())
                .contains("DEPLOYMENT_EGRESS_NOT_ATTESTED");
        assertThat(new MirrorEvidenceIntegrityService(mapper, evidenceSigner, Clock.systemUTC())
                .verify(result.evidenceBundle()))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.VERIFIED);
        assertThat(result.execution().evidence().nodeTrace())
                .filteredOn(trace -> trace.invocationSiteId().equals(
                        "/root/loadCustomer#PRIMARY"))
                .singleElement().satisfies(trace -> {
                    assertThat(trace.status()).isEqualTo("MOCKED");
                    assertThat(trace.output()).isEqualTo(Map.of("customerId", "C-1"));
                });
        var externalTrace = result.execution().evidence().nodeTrace().stream()
                .filter(trace -> trace.invocationSiteId().equals(
                        "/root/loadCustomer#PRIMARY"))
                .findFirst().orElseThrow();
        assertThat(result.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.runId()).isEqualTo(result.execution().evidence().runId());
            assertThat(resolution.planFingerprint()).isEqualTo(compiled.plan().planFingerprint());
            assertThat(resolution.invocationSiteId())
                    .isEqualTo("/root/loadCustomer#PRIMARY");
            assertThat(resolution.occurrence()).isEqualTo(1);
            assertThat(resolution.attempt()).isEqualTo(1);
            assertThat(resolution.status()).isEqualTo(MirrorResolution.Status.RESOLVED);
            assertThat(resolution.source()).isEqualTo(MirrorPlan.MirrorSource.OWNER_SPECIFIED);
            assertThat(resolution.payloadVisibility())
                    .isEqualTo(MirrorResolution.PayloadVisibility.HASH_ONLY);
            assertThat(resolution.outputIncluded()).isFalse();
            assertThat(resolution.output()).isNull();
            assertThat(resolution.outputFingerprint()).isEqualTo(
                    ProtocolFingerprint.of(mapper, Map.of("customerId", "C-1")));
            assertThat(resolution.requestFingerprint()).isEqualTo(
                    ProtocolFingerprint.of(mapper, externalTrace.input()));
            assertThat(resolution.capabilityRef()).isEqualTo(
                    compiled.plan().externalBindings().getFirst().capabilityRef());
            assertThat(resolution.matchedArtifactRefs())
                    .containsExactly(compiled.plan().fixtureBundleRef());
            assertThat(resolution.matchedRuleRefs()).containsExactly("customer-response");
            MirrorResolutionIntegrity.verify(mapper, resolution);
        });
        assertThat(runtime.engineConfiguration().interceptorTypes()).isEmpty();
        assertThat(runtime.engineConfiguration().durableStores()).isFalse();
        assertThat(runtime.engineConfiguration().productionContextCarriers()).isFalse();
    }

    @Test
    void executesStateBackedReadFromOneFrozenSessionRevision() {
        graph = statefulGraph();
        StateModel model = customerStateModel();
        MirrorArtifactRef modelRef =
                StateModelIntegrity.reference(model);
        CapabilityClosure statefulClosure =
                statefulClosure(modelRef);
        closure = statefulClosure;
        CompiledMirrorPlan compiled = compile(fixture());
        MirrorArtifactRef queryCapability =
                compiled.plan().externalBindings().stream()
                        .filter(binding -> "loadCustomer".equals(
                                binding.dependencyNodeId()))
                        .map(MirrorPlan.ExternalBinding::capabilityRef)
                        .findFirst().orElseThrow();
        WriteEffectSpec effect = customerWriteEffect(model);
        StateReadSpec readSpec = StateReadSpecIntegrity.seal(
                mapper, new StateReadSpec(
                        StateReadSpec.SCHEMA_VERSION,
                        "query-customer", 1, "", SCOPE,
                        queryCapability, modelRef,
                        "customer", "customer-id",
                        List.of(BoundedStateExpression.input(
                                "/customerId")),
                        BoundedStateExpression.entity(
                                StateReadSpec.RESULT_ALIAS, ""),
                        stateProvenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        COMPILED_AT));
        SessionStateSpace.EntitySnapshot customer =
                SessionStateSpaceIntegrity.sealEntity(
                        mapper,
                        new SessionStateSpace.EntitySnapshot(
                                new SessionStateSpace.EntityKey(
                                        "customer", "C-1"),
                                1,
                                Map.of(
                                        "customerId", "C-1",
                                        "name", "Session Alice",
                                        "segment", "ENTERPRISE"),
                                ""));
        SessionStateSpace state =
                SessionStateSpaceIntegrity.seal(
                        mapper, new SessionStateSpace(
                                SessionStateSpace.SCHEMA_VERSION,
                                "customer-session-1", SCOPE,
                                compiled.plan().planFingerprint(),
                                modelRef,
                                List.of(WriteEffectSpecIntegrity.reference(
                                        effect)),
                                0, COMPILED_AT, 42,
                                List.of(customer), List.of(),
                                List.of(
                                        SessionStateSpaceIntegrity
                                                .businessKey(
                                                        mapper,
                                                        "customer-id",
                                                        List.of("C-1"),
                                                        customer.key())),
                                List.of(), List.of(),
                                COMPILED_AT.plus(
                                        Duration.ofHours(1)),
                                "", ""));
        MirrorSessionPayload payload =
                MirrorSessionProtocolIntegrity.sealInitial(
                        mapper,
                        new MirrorSessionPayload(
                                MirrorSessionPayload.SCHEMA_VERSION,
                                model, List.of(readSpec),
                                List.of(effect), state, ""),
                        COMPILED_AT.plusSeconds(1));
        MirrorResolver.SessionContext sessionContext =
                new MirrorResolver.SessionContext(
                        payload, compiled.plan().planFingerprint(),
                        Map.of(
                                compiled.plan().externalBindings()
                                        .getFirst()
                                        .invocationSiteId(),
                                queryCapability));
        MirrorRunRequest request = new MirrorRunRequest(
                "request-state-1", compiled,
                new GraphContext(
                        Map.of("customerId", "C-1")),
                SCOPE, PURPOSE, null, sessionContext);

        MirrorRunResult result = runtime.execute(request);

        assertThat(result.passed()).isTrue();
        assertThat(externalCalls).hasValue(0);
        assertThat(result.execution().evidence().nodeTrace())
                .filteredOn(trace -> trace.invocationSiteId()
                        .equals("/root/loadCustomer#PRIMARY"))
                .singleElement()
                .satisfies(trace -> assertThat(trace.output())
                        .isEqualTo(Map.of(
                                "customerId", "C-1",
                                "name", "Session Alice",
                                "segment", "ENTERPRISE")));
        assertThat(result.resolutions())
                .filteredOn(resolution -> resolution.invocationSiteId()
                        .equals("/root/loadCustomer#PRIMARY"))
                .singleElement()
                .satisfies(resolution -> {
                    assertThat(resolution.source())
                            .isEqualTo(
                                    MirrorPlan.MirrorSource
                                            .SESSION_STATE);
                    assertThat(resolution.matchedArtifactRefs())
                            .extracting(MirrorArtifactRef::kind)
                            .containsExactly(
                                    "SESSION_STATE",
                                    "STATE_MODEL",
                                    "STATE_READ_SPEC");
                    assertThat(resolution.matchedRuleRefs())
                            .anyMatch(ref -> ref.startsWith(
                                    "state-read-spec:query-customer:"));
                });
    }

    @Test
    void executesRecordedExactCorpusAndExportsDirectPublicationProvenance() throws Exception {
        MirrorArtifactRef capabilityRef = closure.snapshots().stream()
                .filter(snapshot -> snapshot.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference)
                .findFirst().orElseThrow();
        MirrorArtifactRef publicationRef = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_PUBLICATION", "customer-corpus", 4, fingerprint('4'));
        MirrorArtifactRef revisionRef = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_REVISION", "customer-corpus", 7, fingerprint('5'));
        MirrorArtifactRef observationRef = new MirrorArtifactRef(
                "CAPABILITY_OBSERVATION", "customer-observation", 1, fingerprint('6'));
        String requestFingerprint = ProtocolFingerprint.of(mapper, null);
        byte[] responseJson = mapper.writeValueAsBytes(
                Map.of("customerId", "C-recorded"));
        ResolvedCorpusPayloads corpus = ResolvedCorpusPayloads.of(List.of(
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef, publicationRef, revisionRef,
                        COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(2)),
                        List.of(ResolvedCorpusPayloads.Sample.response(
                                requestFingerprint, responseJson,
                                List.of(publicationRef, revisionRef, observationRef),
                                List.of(observationRef.id()), 0.95, List.of())))));
        corpus = governedCorpus(corpus).payloads();
        CompiledMirrorPlan compiled = compiler.compile(new MirrorPlanCompilationRequest(
                "plan-customer-corpus", graph, TARGET, closure, fixture(),
                ResolvedReplayPayloads.empty(), corpus, policy(), null,
                COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(1))));

        MirrorRunResult result = runtime.execute(request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isTrue();
        assertThat(externalCalls).hasValue(0);
        assertThat(result.execution().evidence().nodeTrace())
                .filteredOn(trace -> trace.invocationSiteId()
                        .equals("/root/loadCustomer#PRIMARY"))
                .singleElement()
                .satisfies(trace -> assertThat(trace.output())
                        .isEqualTo(Map.of("customerId", "C-recorded")));
        assertThat(compiled.plan().externalBindings()).singleElement()
                .satisfies(binding -> assertThat(binding.resolverOrder()).containsExactly(
                        MirrorPlan.MirrorSource.RECORDED_EXACT,
                        MirrorPlan.MirrorSource.ABSTAINED));
        assertThat(result.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.source())
                    .isEqualTo(MirrorPlan.MirrorSource.RECORDED_EXACT);
            assertThat(resolution.matchedArtifactRefs())
                    .containsExactlyInAnyOrder(
                            publicationRef, revisionRef, observationRef);
            assertThat(resolution.matchedRuleRefs())
                    .containsExactly(observationRef.id());
            assertThat(resolution.confidence().method())
                    .isEqualTo("RECORDED_EXACT_V1");
            assertThat(resolution.freshness()).isEqualTo(0.95);
            assertThat(resolution.outputFingerprint()).isEqualTo(
                    ProtocolFingerprint.of(
                            mapper, Map.of("customerId", "C-recorded")));
        });
        assertThat(compiled.executionControl().corpusPayloads().toString())
                .doesNotContain("C-recorded");
        assertThat(compiled.executionControl().corpusPayloads().lifecycle())
                .isEqualTo(new ResolvedCorpusPayloads.GenerationLifecycle(
                        ResolvedCorpusPayloads.GenerationState.OPEN,
                        0, responseJson.length, 0));

        compiled.close();

        assertThat(compiled.executionControl().corpusPayloads().lifecycle())
                .isEqualTo(new ResolvedCorpusPayloads.GenerationLifecycle(
                        ResolvedCorpusPayloads.GenerationState.CLOSED,
                        0, 0, responseJson.length));
        assertRunRejected(
                () -> runtime.execute(request(compiled, SCOPE, PURPOSE)),
                "RG.MIRROR.RUNTIME_GENERATION_CLOSED");
    }

    @Test
    void executesRecordedExactBusinessErrorWithDetailsFingerprint() {
        MirrorArtifactRef capabilityRef = closure.snapshots().stream()
                .filter(snapshot -> snapshot.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference)
                .findFirst().orElseThrow();
        MirrorArtifactRef publicationRef = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_PUBLICATION", "customer-error-corpus",
                1, fingerprint('4'));
        MirrorArtifactRef revisionRef = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_REVISION", "customer-error-corpus",
                1, fingerprint('5'));
        MirrorArtifactRef observationRef = new MirrorArtifactRef(
                "CAPABILITY_OBSERVATION", "customer-error-observation",
                1, fingerprint('6'));
        String detailsFingerprint = fingerprint('e');
        ResolvedCorpusPayloads corpus = ResolvedCorpusPayloads.of(List.of(
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef, publicationRef, revisionRef,
                        COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(2)),
                        List.of(ResolvedCorpusPayloads.Sample.error(
                                ProtocolFingerprint.of(mapper, null),
                                "CUSTOMER_NOT_FOUND",
                                "BUSINESS",
                                false,
                                detailsFingerprint,
                                List.of(publicationRef, revisionRef, observationRef),
                                List.of(observationRef.id()),
                                0.9,
                                List.of())))));
        corpus = governedCorpus(corpus).payloads();
        CompiledMirrorPlan compiled = compiler.compile(
                new MirrorPlanCompilationRequest(
                        "plan-customer-error-corpus",
                        graph,
                        TARGET,
                        closure,
                        fixture(),
                        ResolvedReplayPayloads.empty(),
                        corpus,
                        policy(),
                        null,
                        COMPILED_AT,
                        COMPILED_AT.plus(Duration.ofHours(1))));

        MirrorRunResult result = runtime.execute(
                request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isFalse();
        assertThat(externalCalls).hasValue(0);
        assertThat(result.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.status()).isEqualTo(
                    MirrorResolution.Status.RESOLVED);
            assertThat(resolution.source()).isEqualTo(
                    MirrorPlan.MirrorSource.RECORDED_EXACT);
            assertThat(resolution.error()).isEqualTo(
                    new MirrorResolution.MirrorError(
                            "CUSTOMER_NOT_FOUND",
                            "BUSINESS",
                            detailsFingerprint));
            assertThat(resolution.matchedArtifactRefs())
                    .containsExactlyInAnyOrder(
                            publicationRef, revisionRef, observationRef);
        });
    }

    @Test
    void stopsBeforeTheFirstOperatorWhenFloorAdvancesAfterRunAdmission()
            throws Exception {
        MirrorArtifactRef capabilityRef = closure.snapshots().stream()
                .filter(snapshot ->
                        snapshot.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference)
                .findFirst().orElseThrow();
        MirrorArtifactRef publicationRef = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_PUBLICATION",
                "customer-race-corpus", 1, fingerprint('2'));
        MirrorArtifactRef revisionRef = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_REVISION",
                "customer-race-corpus", 1, fingerprint('3'));
        ResolvedCorpusPayloads raw = ResolvedCorpusPayloads.of(List.of(
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef, publicationRef, revisionRef,
                        COMPILED_AT,
                        COMPILED_AT.plus(Duration.ofHours(2)),
                        List.of(ResolvedCorpusPayloads.Sample.response(
                                ProtocolFingerprint.of(mapper, null),
                                mapper.writeValueAsBytes(
                                        Map.of("customerId", "C-recorded")),
                                List.of(publicationRef, revisionRef),
                                List.of("recorded-race"),
                                0.95, List.of())))));
        GovernedCorpus governed = governedCorpus(
                raw,
                Duration.ofNanos(1),
                new TickingClock(
                        COMPILED_AT.plusSeconds(1),
                        Duration.ofSeconds(1)));
        CompiledMirrorPlan compiled = compiler.compile(
                new MirrorPlanCompilationRequest(
                        "plan-serving-generation-race", graph, TARGET,
                        closure, fixture(), ResolvedReplayPayloads.empty(),
                        governed.payloads(), policy(), null,
                        COMPILED_AT,
                        COMPILED_AT.plus(Duration.ofHours(1))));
        governed.authority().advanceAfterFirstRead =
                successor(governed);

        MirrorRunResult result = runtime.execute(
                request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isFalse();
        assertThat(governed.authority().floorReads).isGreaterThanOrEqualTo(2);
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(0);
        assertThat(result.execution().evidence().nodeTrace())
                .anySatisfy(trace -> assertThat(trace.errorCode())
                        .isEqualTo(
                                "MIRROR_SERVING_GENERATION_STALE"));
    }

    @Test
    void executesRecordedTrajectoryThroughTheRealBlogeRetryLoop()
            throws Exception {
        Map<String, NodeSpec> retryNodes = new LinkedHashMap<>();
        retryNodes.put(
                "loadCustomer",
                new NodeSpec(
                        "loadCustomer",
                        "customer.lookup",
                        null,
                        ResilienceConfig.builder(1)
                                .retryBackoff(Duration.ZERO)
                                .build(),
                        Map.of(),
                        OpaqueSchema.INSTANCE,
                        OpaqueSchema.INSTANCE));
        retryNodes.put(
                "formatCustomer",
                node("formatCustomer", "customer.format"));
        Graph retryGraph = new Graph(
                "customerView",
                retryNodes,
                List.of(),
                Set.copyOf(retryNodes.keySet()),
                Set.copyOf(retryNodes.keySet()),
                SchemaValidationLevel.OFF);
        MirrorArtifactRef capabilityRef = closure.snapshots().stream()
                .filter(snapshot -> snapshot.kind()
                        == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference)
                .findFirst()
                .orElseThrow();
        MirrorArtifactRef corpusPublication = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_PUBLICATION",
                "customer-retry-corpus",
                1,
                fingerprint('2'));
        MirrorArtifactRef corpusRevision = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_REVISION",
                "customer-retry-corpus",
                1,
                fingerprint('3'));
        MirrorArtifactRef trajectoryPublication = new MirrorArtifactRef(
                "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION",
                "customer-timeout-retry",
                1,
                fingerprint('4'));
        MirrorArtifactRef retryPolicy = new MirrorArtifactRef(
                "RETRY_POLICY",
                "customer-retry-policy",
                3,
                fingerprint('5'));
        MirrorArtifactRef firstObservation = new MirrorArtifactRef(
                "CAPABILITY_OBSERVATION",
                "customer-attempt-1",
                1,
                fingerprint('6'));
        MirrorArtifactRef secondObservation = new MirrorArtifactRef(
                "CAPABILITY_OBSERVATION",
                "customer-attempt-2",
                1,
                fingerprint('7'));
        String requestFingerprint = ProtocolFingerprint.of(mapper, null);
        List<MirrorArtifactRef> common = List.of(
                corpusPublication,
                corpusRevision,
                trajectoryPublication,
                retryPolicy);
        List<MirrorArtifactRef> firstArtifacts =
                new java.util.ArrayList<>(common);
        firstArtifacts.add(firstObservation);
        List<MirrorArtifactRef> secondArtifacts =
                new java.util.ArrayList<>(common);
        secondArtifacts.add(secondObservation);
        ResolvedCorpusPayloads corpus = ResolvedCorpusPayloads.of(List.of(
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef,
                        corpusPublication,
                        corpusRevision,
                        COMPILED_AT,
                        COMPILED_AT.plus(Duration.ofHours(2)),
                        List.of(),
                        List.of(new ResolvedCorpusPayloads.Trajectory(
                                requestFingerprint,
                                trajectoryPublication,
                                List.of(
                                        ResolvedCorpusPayloads.Sample.error(
                                                requestFingerprint,
                                                "UPSTREAM_TIMEOUT",
                                                "TRANSIENT",
                                                true,
                                                fingerprint('8'),
                                                firstArtifacts,
                                                List.of(
                                                        "customer-timeout-retry@1:attempt:1"),
                                                0.9,
                                                List.of()),
                                        ResolvedCorpusPayloads.Sample.response(
                                                requestFingerprint,
                                                mapper.writeValueAsBytes(
                                                        Map.of(
                                                                "customerId",
                                                                "C-recovered")),
                                                secondArtifacts,
                                                List.of(
                                                        "customer-timeout-retry@1:attempt:2"),
                                                0.9,
                                                List.of())))))));
        corpus = governedCorpus(corpus).payloads();
        CompiledMirrorPlan compiled = compiler.compile(
                new MirrorPlanCompilationRequest(
                        "plan-customer-trajectory",
                        retryGraph,
                        TARGET,
                        closure,
                        fixture(),
                        ResolvedReplayPayloads.empty(),
                        corpus,
                        policy(2),
                        null,
                        COMPILED_AT,
                        COMPILED_AT.plus(Duration.ofHours(1))));

        MirrorRunResult result =
                runtime.execute(request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isTrue();
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(1);
        assertThat(compiled.plan().externalBindings()).singleElement()
                .satisfies(binding -> assertThat(binding.resolverOrder())
                        .containsExactly(
                                MirrorPlan.MirrorSource.RECORDED_TRAJECTORY,
                                MirrorPlan.MirrorSource.ABSTAINED));
        assertThat(result.resolutions()).hasSize(2)
                .allSatisfy(resolution -> {
                    assertThat(resolution.source()).isEqualTo(
                            MirrorPlan.MirrorSource.RECORDED_TRAJECTORY);
                    assertThat(resolution.confidence().method())
                            .isEqualTo("RECORDED_TRAJECTORY_V1");
                    assertThat(resolution.matchedArtifactRefs())
                            .contains(
                                    trajectoryPublication,
                                    retryPolicy);
                });
        assertThat(result.resolutions())
                .extracting(
                        MirrorResolution::attempt,
                        MirrorResolution::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1,
                                MirrorResolution.Status.RESOLVED),
                        org.assertj.core.groups.Tuple.tuple(
                                2,
                                MirrorResolution.Status.RESOLVED));
        assertThat(result.resolutions().getFirst().error().code())
                .isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(result.execution().evidence().nodeTrace())
                .filteredOn(trace -> trace.invocationSiteId().equals(
                        "/root/loadCustomer#PRIMARY"))
                .singleElement()
                .satisfies(trace -> assertThat(trace.output())
                        .isEqualTo(Map.of(
                                "customerId", "C-recovered")));
        assertThat(mapper.writeValueAsString(result.evidenceBundle()))
                .doesNotContain("C-recovered")
                .contains(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorEvidenceBundle.PayloadPolicy.HASH_ONLY.name());
    }

    @Test
    void producesCertifiableV2EvidenceOnlyAfterDoubleObservedDeploymentTrust() {
        CompiledMirrorPlan compiled = compileCertification(fixture(rule(
                "customer-response", "loadCustomer",
                FixtureRule.Behavior.returning(Map.of("customerId", "C-1")))));
        TestTrustAuthority trust = new TestTrustAuthority(false);
        MirrorEvidenceIntegrityService integrity = new MirrorEvidenceIntegrityService(
                mapper, evidenceSigner, Clock.systemUTC());
        MirrorRunService trustedRuntime = new MirrorRunService(
                registry, mapper, null,
                Clock.fixed(COMPILED_AT.plusSeconds(1), ZoneOffset.UTC), integrity, trust);
        MirrorDeploymentIsolationRunTrust.Admission admission = trust.admit(SCOPE);
        MirrorRunRequest request = new MirrorRunRequest("request-certifiable", compiled,
                new GraphContext(Map.of("customerId", "C-1")), SCOPE, PURPOSE, admission);

        MirrorRunResult result = trustedRuntime.execute(request);

        assertThat(result.evidenceBundle().evidence().schemaVersion())
                .isEqualTo(com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence
                        .SCHEMA_VERSION);
        assertThat(result.evidenceBundle().evidence().evidenceClass())
                .isEqualTo(com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence
                        .EvidenceClass.CERTIFIABLE);
        assertThat(result.evidenceBundle().evidence().limitations()).isEmpty();
        assertThat(result.evidenceBundle().evidence().isolation().limitations()).isEmpty();
        assertThat(result.evidenceBundle().evidence().isolation().deploymentTrustBinding())
                .satisfies(binding -> {
                    assertThat(binding.decisionRef()).isEqualTo(admission.decisionRef());
                    assertThat(binding.admittedSnapshotRef())
                            .isEqualTo(admission.admittedSnapshotRef());
                    assertThat(binding.committedSnapshotRef().revision()).isEqualTo(2);
                });
        assertThat(integrity.verify(result.evidenceBundle()))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.VERIFIED);
    }

    @Test
    void withholdsExecutedCertificationRunWhenTerminalTrustConfirmationFails() {
        CompiledMirrorPlan compiled = compileCertification(fixture(rule(
                "customer-response", "loadCustomer",
                FixtureRule.Behavior.returning(Map.of("customerId", "C-1")))));
        TestTrustAuthority trust = new TestTrustAuthority(true);
        MirrorRunService trustedRuntime = new MirrorRunService(
                registry, mapper, null,
                Clock.fixed(COMPILED_AT.plusSeconds(1), ZoneOffset.UTC),
                new MirrorEvidenceIntegrityService(mapper, evidenceSigner, Clock.systemUTC()),
                trust);
        MirrorRunRequest request = new MirrorRunRequest("request-trust-changed", compiled,
                new GraphContext(Map.of("customerId", "C-1")), SCOPE, PURPOSE,
                trust.admit(SCOPE));

        assertRunRejected(() -> trustedRuntime.execute(request),
                "RG.MIRROR.DEPLOYMENT_TRUST_CHANGED");
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(1);
    }

    @Test
    void stopsDynamicForeachExpansionAtTheSealedOccurrenceBudget() {
        AtomicInteger itemCalls = new AtomicInteger();
        DefaultOperatorRegistry itemRegistry = new DefaultOperatorRegistry();
        itemRegistry.register("item.lookup", new ExternalReadOperator(itemCalls));
        Graph itemGraph = new Graph("itemBody",
                Map.of("process", node("process", "item.lookup")), List.of(),
                Set.of("process"), Set.of("process"), SchemaValidationLevel.OFF);
        Graph foreachGraph = new GraphBuilder("foreachCustomer")
                .node("expand", new ForEachOperator(itemGraph, itemRegistry, true))
                .input((results, context) -> context.get("input"))
                .build();
        CapabilityClosure foreachClosure = foreachClosure(foreachGraph, itemGraph);
        FixtureRule nestedRule = new FixtureRule("", "item-response",
                new FixtureRule.Selector("/root/expand/itemBody", "process", "", "", "",
                        List.of(), List.of(),
                        com.leanowtech.bloge.gateway.testing.domain.InvocationSite.InvocationKind.PRIMARY,
                        List.of(), List.of(), "", FixtureRule.Match.none()),
                FixtureRule.Behavior.returning(Map.of("accepted", true)),
                new FixtureRule.Consumption(true, 2, 2,
                        FixtureRule.ExhaustedAction.FAIL, FixtureRule.UnmatchedAction.FAIL),
                FixtureRule.SchemaCheck.strict());
        CompiledMirrorPlan compiled = compiler.compile(new MirrorPlanCompilationRequest(
                "plan-foreach-customer", foreachGraph, TARGET, foreachClosure,
                fixture(nestedRule),
                ResolvedReplayPayloads.empty(), policy(3), null, COMPILED_AT,
                COMPILED_AT.plus(Duration.ofHours(1))));
        MirrorRunRequest request = new MirrorRunRequest("request-foreach", compiled,
                new GraphContext(Map.of("input", List.of("A", "B", "C", "D", "E"))),
                SCOPE, PURPOSE);

        MirrorRunResult result = runtime.execute(request);

        assertThat(result.passed()).isFalse();
        assertThat(itemCalls).hasValue(0);
        assertThat(result.resolutions()).hasSize(2);
        assertThat(result.execution().evidence().metadata().get("mirrorInvocationBudget"))
                .isEqualTo(Map.of(
                        "maximumInvocations", 3,
                        "admittedInvocations", 3,
                        "rejectedInvocations", 1));
        assertThat(result.execution().evidence().nodeTrace()).hasSize(3);
        assertThat(result.evidenceBundle().evidence().limitations())
                .contains(MirrorInvocationBudget.EXHAUSTED_LIMITATION);
        assertThat(result.evidenceBundle().evidence().status())
                .isEqualTo(com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence.Status
                        .EXECUTION_FAILED);
        assertThat(new MirrorEvidenceIntegrityService(mapper, evidenceSigner, Clock.systemUTC())
                .verify(result.evidenceBundle()))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.VERIFIED);
    }

    @Test
    void countsRetryAttemptsInsideOneAdmittedOccurrence() {
        Map<String, NodeSpec> retryNodes = new LinkedHashMap<>();
        retryNodes.put("loadCustomer", new NodeSpec("loadCustomer", "customer.lookup", null,
                ResilienceConfig.builder(1).retryBackoff(Duration.ZERO).build(), Map.of(),
                OpaqueSchema.INSTANCE, OpaqueSchema.INSTANCE));
        retryNodes.put("formatCustomer", node("formatCustomer", "customer.format"));
        Graph retryGraph = new Graph("customerView", retryNodes, List.of(),
                Set.copyOf(retryNodes.keySet()), Set.copyOf(retryNodes.keySet()),
                SchemaValidationLevel.OFF);
        FixtureRule firstAttempt = new FixtureRule("", "first-attempt-timeout",
                attemptSelector("loadCustomer", 1),
                FixtureRule.Behavior.timeout(Duration.ofSeconds(1),
                        "FIRST_ATTEMPT_TIMEOUT", "retry this controlled call"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        FixtureRule secondAttempt = new FixtureRule("", "second-attempt-return",
                attemptSelector("loadCustomer", 2),
                FixtureRule.Behavior.returning(Map.of("customerId", "C-1")),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        CompiledMirrorPlan compiled = compiler.compile(new MirrorPlanCompilationRequest(
                "plan-retry-customer", retryGraph, TARGET, closure,
                fixture(firstAttempt, secondAttempt), ResolvedReplayPayloads.empty(),
                policy(2), null, COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(1))));

        MirrorRunResult result = runtime.execute(request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isTrue();
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(1);
        assertThat(result.resolutions())
                .extracting(resolution -> resolution.occurrence() + ":" + resolution.attempt())
                .containsExactly("1:1", "1:2");
        assertThat(result.execution().evidence().metadata().get("mirrorInvocationBudget"))
                .isEqualTo(Map.of(
                        "maximumInvocations", 2,
                        "admittedInvocations", 2,
                        "rejectedInvocations", 0));
    }

    @Test
    void turnsAnUnmatchedExternalLeafIntoARecordedFailureWithoutRealFallback() {
        CompiledMirrorPlan compiled = compile(fixture());

        MirrorRunResult result = runtime.execute(request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isFalse();
        assertThat(externalCalls).hasValue(0);
        assertThat(result.execution().evidence().status())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence.Status
                        .FIXTURE_UNMATCHED);
        assertThat(result.execution().evidence().nodeTrace())
                .filteredOn(trace -> trace.invocationSiteId().equals(
                        "/root/loadCustomer#PRIMARY"))
                .singleElement().satisfies(trace ->
                        assertThat(trace.errorCode()).isEqualTo("FIXTURE_UNMATCHED"));
        assertThat(result.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.status()).isEqualTo(MirrorResolution.Status.ABSTAINED);
            assertThat(resolution.source()).isEqualTo(MirrorPlan.MirrorSource.ABSTAINED);
            assertThat(resolution.payloadVisibility())
                    .isEqualTo(MirrorResolution.PayloadVisibility.NONE);
            assertThat(resolution.matchedArtifactRefs()).isEmpty();
            assertThat(resolution.matchedRuleRefs()).isEmpty();
            assertThat(resolution.confidence().point()).isZero();
            MirrorResolutionIntegrity.verify(mapper, resolution);
        });
    }

    @Test
    void exportsGovernedReplayWithExactPayloadProvenance() {
        FixtureRule replayRule = rule("customer-replay", "loadCustomer",
                FixtureRule.Behavior.replaying(REPLAY_REF));
        ResolvedReplayPayloads replayPayloads = replayPayloads();
        CompiledMirrorPlan compiled = compile(fixture(replayRule), replayPayloads);

        MirrorRunResult result = runtime.execute(request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isTrue();
        assertThat(externalCalls).hasValue(0);
        assertThat(result.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.source())
                    .isEqualTo(MirrorPlan.MirrorSource.GOVERNED_REPLAY);
            assertThat(resolution.status()).isEqualTo(MirrorResolution.Status.RESOLVED);
            assertThat(resolution.outputFingerprint()).isEqualTo(
                    ProtocolFingerprint.of(mapper, Map.of("customerId", "C-replay")));
            assertThat(resolution.matchedArtifactRefs()).hasSize(2);
            assertThat(resolution.matchedArtifactRefs())
                    .extracting(ref -> ref.kind() + ":" + ref.id())
                    .containsExactly("FIXTURE_BUNDLE:customer-fixture",
                            "REPLAY_PAYLOAD:customer-approved");
            assertThat(resolution.matchedRuleRefs()).containsExactly("customer-replay");
            MirrorResolutionIntegrity.verify(mapper, resolution);
        });
    }

    @Test
    void exportsAnOwnerSpecifiedBusinessErrorAsAResolvedOutcome() {
        CompiledMirrorPlan compiled = compile(fixture(rule(
                "customer-error", "loadCustomer",
                FixtureRule.Behavior.throwing(
                        "CUSTOMER_NOT_FOUND", "BUSINESS", "fixture-only diagnostic"))));

        MirrorRunResult result = runtime.execute(request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isFalse();
        assertThat(result.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.status()).isEqualTo(MirrorResolution.Status.RESOLVED);
            assertThat(resolution.source()).isEqualTo(MirrorPlan.MirrorSource.OWNER_SPECIFIED);
            assertThat(resolution.payloadVisibility())
                    .isEqualTo(MirrorResolution.PayloadVisibility.NONE);
            assertThat(resolution.error().code()).isEqualTo("CUSTOMER_NOT_FOUND");
            assertThat(resolution.error().type()).isEqualTo("BUSINESS");
            assertThat(resolution.error().message()).isEmpty();
            assertThat(resolution.outputFingerprint()).isEmpty();
            MirrorResolutionIntegrity.verify(mapper, resolution);
        });
    }

    @Test
    void rejectsScopePurposeAndTimeBeforeSchedulingAnyNode() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("value"))));

        assertRunRejected(() -> runtime.execute(request(compiled,
                        new CapabilitySnapshot.Scope("tenant-b", "org-a", "support", "test", "sg"),
                        PURPOSE)),
                "RG.MIRROR.RUN_SCOPE_MISMATCH");
        assertRunRejected(() -> runtime.execute(request(compiled, SCOPE, "CHANGE_SYNC")),
                "RG.MIRROR.RUN_PURPOSE_MISMATCH");

        MirrorRunService expired = new MirrorRunService(registry, mapper, null,
                Clock.fixed(compiled.plan().expiresAt(), ZoneOffset.UTC), evidenceSigner);
        assertRunRejected(() -> expired.execute(request(compiled, SCOPE, PURPOSE)),
                "RG.MIRROR.RUN_EXPIRED");
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(0);
    }

    @Test
    void refusesToDeliverAnExecutedRunWhenNoSigningAuthorityIsConfigured() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning(Map.of("customerId", "C-1")))));
        MirrorRunService unsigned = new MirrorRunService(registry, mapper, null,
                Clock.fixed(COMPILED_AT.plusSeconds(1), ZoneOffset.UTC));

        assertRunRejected(() -> unsigned.execute(request(compiled, SCOPE, PURPOSE)),
                "RG.MIRROR.EVIDENCE_SIGNER_UNAVAILABLE");
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(1);
    }

    @Test
    void rejectsMissingOrMismatchedExternalResolutionClosure() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning(Map.of("customerId", "C-1")))));
        MirrorRunRequest request = request(compiled, SCOPE, PURPOSE);
        MirrorRunResult result = runtime.execute(request);
        MirrorRunEvidenceProjector projector = new MirrorRunEvidenceProjector(mapper);
        MirrorInvocationBudget.Snapshot budget =
                new MirrorInvocationBudget.Snapshot(1000, 2, 0);

        assertThatThrownBy(() -> projector.project(request, result.execution(), List.of(),
                runtime.engineConfiguration()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budget snapshot is required");

        assertThatThrownBy(() -> projector.project(request, result.execution(), List.of(),
                runtime.engineConfiguration(), budget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact closure");

        MirrorResolution source = result.resolutions().getFirst();
        MirrorResolution mismatched = copyResolutionWithRequest(source, fingerprint('b'));
        assertThatThrownBy(() -> projector.project(request, result.execution(),
                List.of(mismatched), runtime.engineConfiguration(), budget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differs from its external delegate attempt");

        assertThatThrownBy(() -> projector.project(request, result.execution(),
                result.resolutions(), runtime.engineConfiguration(),
                new MirrorInvocationBudget.Snapshot(1000, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differs from the runtime invocation budget");
    }

    @Test
    void rejectsEvidenceWhenSignerCannotVerifyItsOwnSignature() {
        VisualEvidenceSigner invalidSigner = new VisualEvidenceSigner() {
            @Override
            public VisualRunEvidenceSeal seal(String materialFingerprint) {
                return evidenceSigner.seal(materialFingerprint);
            }

            @Override
            public Verification verify(
                    VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
                return new Verification(false, "INVALID", "injected verification failure");
            }

            @Override
            public Optional<VerificationKey> key(String keyId) {
                return evidenceSigner.key(keyId);
            }

            @Override
            public boolean available() {
                return true;
            }
        };
        MirrorRunService invalid = new MirrorRunService(registry, mapper, null,
                Clock.fixed(COMPILED_AT.plusSeconds(1), ZoneOffset.UTC), invalidSigner);
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("value"))));

        assertRunRejected(() -> invalid.execute(request(compiled, SCOPE, PURPOSE)),
                "RG.MIRROR.EVIDENCE_INTEGRITY_REJECTED");
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(1);
    }

    @Test
    void rejectsACompiledCompanionWhoseFixturePayloadWasReplaced() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("original"))));
        FixtureBundle replacement = fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("replacement")));
        CompiledMirrorPlan drifted = new CompiledMirrorPlan(compiled.plan(), compiled.graph(),
                replacement, compiled.executionControl());

        assertRunRejected(() -> runtime.execute(request(drifted, SCOPE, PURPOSE)),
                "RG.MIRROR.FIXTURE_GENERATION_DRIFT");
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(0);
    }

    @Test
    void compilerRejectsFixtureControlsThatReplaceInternalBusinessNodes() {
        FixtureBundle fixture = fixture(
                rule("external", "loadCustomer", FixtureRule.Behavior.returning("customer")),
                rule("internal", "formatCustomer", FixtureRule.Behavior.returning("formatted")));

        assertThatThrownBy(() -> compile(fixture))
                .isInstanceOfSatisfying(MirrorPlanRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "RG.MIRROR.CONTROL_PLAN_MIRROR_INTERNAL_CONTROL"));
    }

    @Test
    void sharedPrecompiledKernelRejectsPurposeDriftEvenWithoutTheMirrorAdmissionLayer() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("value"))));
        TestRunService shared = new TestRunService(registry, mapper, null);
        TestExecutionRequest drifted = new TestExecutionRequest(compiled.graph(), new GraphContext(),
                compiled.fixtureBundle(), "CHANGE_SYNC",
                compiled.executionControl().effectivePlan().targetFingerprint(),
                TestExecutionRequest.FixtureSource.STORED, Map.of(), true,
                compiled.executionControl().replayPayloads(), ResolvedTestSecrets.empty());

        assertThatThrownBy(() -> shared.executeCompiled(drifted, compiled.executionControl()))
                .isInstanceOfSatisfying(
                        com.leanowtech.bloge.gateway.testing.planning.ControlPlanRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "CONTROL_PLAN_COMPILED_BINDING_MISMATCH"));
    }

    @Test
    void mirrorResolutionJournalHasAStrictSingleCompletionLifecycle() {
        CompiledMirrorPlan compiled = compile(fixture());
        MirrorResolutionJournal journal = new MirrorResolutionJournal(
                mapper, compiled.plan(), compiled.executionControl().replayPayloads());

        assertThat(journal.complete("test-run-1")).isEmpty();
        assertThatThrownBy(() -> journal.complete("test-run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already complete");
    }

    private CompiledMirrorPlan compile(FixtureBundle fixture) {
        return compile(fixture, ResolvedReplayPayloads.empty());
    }

    private CompiledMirrorPlan compile(
            FixtureBundle fixture, ResolvedReplayPayloads replayPayloads) {
        return compiler.compile(new MirrorPlanCompilationRequest(
                "plan-customer-view", graph, TARGET, closure, fixture,
                replayPayloads, policy(), null,
                COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(1))));
    }

    private CompiledMirrorPlan compileCertification(FixtureBundle fixture) {
        return compiler.compile(new MirrorPlanCompilationRequest(
                "plan-customer-view-certification", graph, TARGET, closure, fixture,
                ResolvedReplayPayloads.empty(), policy(1000, true), null,
                COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(1))));
    }

    private GovernedCorpus governedCorpus(ResolvedCorpusPayloads payloads) {
        return governedCorpus(
                payloads,
                Duration.ofSeconds(5),
                Clock.fixed(
                        COMPILED_AT.plusSeconds(1),
                        ZoneOffset.UTC));
    }

    private GovernedCorpus governedCorpus(
            ResolvedCorpusPayloads payloads,
            Duration maximumStaleness,
            Clock fenceClock) {
        Clock authorityClock = Clock.fixed(
                COMPILED_AT, ZoneOffset.UTC);
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(authorityClock);
        MirrorServingGenerationIntegrity integrity =
                new MirrorServingGenerationIntegrity(mapper);
        MirrorServingGenerationToken token = integrity.seal(
                new MirrorServingGenerationToken.Material(
                        "customer-corpus-serving", 1, "", SCOPE,
                        PURPOSE,
                        ProtocolFingerprint.of(
                                mapper,
                                payloads.generationDependencies()),
                        1, COMPILED_AT,
                        COMPILED_AT.plus(Duration.ofHours(2)),
                        maximumStaleness),
                "test-corpus-authority", signer);
        VisualEvidenceSigner.VerificationKey key = signer.key(
                token.seal().keyId()).orElseThrow();
        MirrorServingGenerationTrustProvider trust =
                MirrorServingGenerationTrustProvider.fixed(
                        new MirrorServingGenerationTrustProvider.AuthorityKey(
                                token.seal().authorityId(),
                                key.keyId(), key.algorithm(),
                                key.encodedPublicKey(),
                                COMPILED_AT.minus(Duration.ofHours(1)),
                                COMPILED_AT.plus(Duration.ofHours(3)),
                                MirrorServingGenerationTrustProvider
                                        .KeyState.ACTIVE));
        FixedGenerationAuthority authority =
                new FixedGenerationAuthority(token);
        ResolvedCorpusPayloads governed =
                payloads.withServingGeneration(
                        new MirrorServingGenerationFence(
                                token, authority, trust, integrity,
                                fenceClock));
        return new GovernedCorpus(
                governed, authority, token, signer, integrity);
    }

    private MirrorServingGenerationToken successor(
            GovernedCorpus governed) {
        MirrorServingGenerationToken previous = governed.token();
        MirrorServingGenerationToken.Material material =
                previous.material();
        return governed.integrity().seal(
                new MirrorServingGenerationToken.Material(
                        material.streamId(),
                        material.generation() + 1,
                        previous.tokenFingerprint(),
                        material.scope(),
                        material.authorizedPurpose(),
                        material.dependencyClosureFingerprint(),
                        material.revocationCursor() + 1,
                        material.issuedAt(),
                        material.expiresAt(),
                        material.maximumStaleness()),
                previous.seal().authorityId(),
                governed.signer());
    }

    private static ResolvedReplayPayloads replayPayloads() {
        return new ResolvedReplayPayloads(Map.of(REPLAY_REF,
                new ResolvedReplayPayloads.Payload(
                        REPLAY_REF, "CONFIDENTIAL", "{\"customerId\":\"C-replay\"}",
                        "source-run-1", "loadCustomer", 1, fingerprint('6'),
                        fingerprint('9'), COMPILED_AT.plus(Duration.ofHours(2)),
                        true, List.of())));
    }

    private static MirrorRunRequest request(
            CompiledMirrorPlan compiled,
            CapabilitySnapshot.Scope scope,
            String purpose) {
        return new MirrorRunRequest("request-1", compiled,
                new GraphContext(Map.of("customerId", "C-1")), scope, purpose);
    }

    private CapabilityClosure closure() {
        EffectContract effect = EffectContract.readOnly(List.of("customer:*"));
        CapabilitySnapshot external = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "operator:customer.lookup", 1, "",
                        CapabilitySnapshot.Kind.EXTERNAL, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.OPERATOR,
                                "customer.lookup", fingerprint('e')),
                        contract(effect), runtime("OPERATOR", "customer.lookup", 'f'),
                        List.of(), ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(), COMPILED_AT));
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "graph:customerView", 1, "",
                        CapabilitySnapshot.Kind.COMPOSED, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                                "customerView", TARGET), contract(effect),
                        runtime("BLOGE_GRAPH", "customerView", '8'),
                        List.of(new CapabilitySnapshot.Dependency("loadCustomer",
                                CapabilityClosureIntegrity.reference(external), true, List.of())),
                        ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(), COMPILED_AT));
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, external), ""));
    }

    private CapabilityClosure statefulClosure(
            MirrorArtifactRef stateModelRef) {
        EffectContract effect =
                EffectContract.readOnly(List.of("customer:*"));
        CapabilitySnapshot external =
                CapabilitySnapshotIntegrity.seal(
                        mapper,
                        new CapabilitySnapshot(
                                "", "operator:customer.lookup", 1,
                                "", CapabilitySnapshot.Kind.EXTERNAL,
                                SCOPE,
                                new CapabilitySnapshot.Source(
                                        CapabilitySnapshot.SourceKind
                                                .OPERATOR,
                                        "customer.lookup",
                                        fingerprint('e')),
                                contract(effect, stateModelRef),
                                runtime(
                                        "OPERATOR",
                                        "customer.lookup", 'f'),
                                List.of(), ownership(),
                                CapabilitySnapshot.Lifecycle.ACTIVE,
                                provenance(), COMPILED_AT));
        CapabilitySnapshot root =
                CapabilitySnapshotIntegrity.seal(
                        mapper,
                        new CapabilitySnapshot(
                                "", "graph:customerView", 1, "",
                                CapabilitySnapshot.Kind.COMPOSED,
                                SCOPE,
                                new CapabilitySnapshot.Source(
                                        CapabilitySnapshot.SourceKind.GRAPH,
                                        "customerView", TARGET),
                                contract(effect, stateModelRef),
                                runtime(
                                        "BLOGE_GRAPH",
                                        "customerView", '8'),
                                List.of(
                                        new CapabilitySnapshot.Dependency(
                                                "loadCustomer",
                                                CapabilityClosureIntegrity
                                                        .reference(
                                                                external),
                                                true, List.of())),
                                ownership(),
                                CapabilitySnapshot.Lifecycle.ACTIVE,
                                provenance(), COMPILED_AT));
        return CapabilityClosureIntegrity.seal(
                mapper, new CapabilityClosure(
                        "",
                        CapabilityClosureIntegrity.reference(root),
                        List.of(root, external), ""));
    }

    private StateModel customerStateModel() {
        return StateModelIntegrity.seal(
                mapper, new StateModel(
                        StateModel.SCHEMA_VERSION,
                        "customer-world", 1, "", SCOPE,
                        List.of(new StateModel.EntityType(
                                "customer",
                                SchemaEnvelope.object(
                                        Map.of(
                                                "customerId",
                                                Map.of("type", "string"),
                                                "name",
                                                Map.of("type", "string"),
                                                "segment",
                                                Map.of("type", "string")),
                                        List.of(
                                                "customerId",
                                                "name", "segment")),
                                List.of(
                                        new StateModel
                                                .BusinessKeyDefinition(
                                                "customer-id",
                                                List.of(
                                                        "/customerId"))))),
                        List.of(), stateProvenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        COMPILED_AT));
    }

    private WriteEffectSpec customerWriteEffect(
            StateModel model) {
        WriteEffectSpec.Mutation mutation =
                new WriteEffectSpec.Mutation(
                        "update-customer",
                        WriteEffectSpec.Operation.UPDATE,
                        "customer",
                        BoundedStateExpression.input("/customerId"),
                        null, List.of(), List.of(
                        new WriteEffectSpec.FieldEffect(
                                "/name",
                                BoundedStateExpression.input("/name"))),
                        List.of(
                                new WriteEffectSpec.BusinessKeyRule(
                                        "customer-id",
                                        List.of(
                                                BoundedStateExpression
                                                        .entity(
                                                                "update-customer",
                                                                "/customerId")))));
        return WriteEffectSpecIntegrity.seal(
                mapper, new WriteEffectSpec(
                        WriteEffectSpec.SCHEMA_VERSION,
                        "update-customer", 1, "", SCOPE,
                        new MirrorArtifactRef(
                                "CAPABILITY", "customer.update", 1,
                                fingerprint('7')),
                        StateModelIntegrity.reference(model),
                        List.of(mutation),
                        BoundedStateExpression.entity(
                                "update-customer", ""),
                        new WriteEffectSpec.Idempotency(
                                "/requestId", true),
                        stateProvenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        COMPILED_AT));
    }

    private CapabilityClosure foreachClosure(Graph rootGraph, Graph itemGraph) {
        EffectContract effect = EffectContract.readOnly(List.of("item:*"));
        CapabilitySnapshot external = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "operator:item.lookup", 1, "",
                        CapabilitySnapshot.Kind.EXTERNAL, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.OPERATOR,
                                "item.lookup", fingerprint('4')),
                        contract(effect), runtime("OPERATOR", "item.lookup", '3'),
                        List.of(), ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(), COMPILED_AT));
        CapabilitySnapshot child = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "graph:" + itemGraph.name(), 1, "",
                        CapabilitySnapshot.Kind.COMPOSED, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                                itemGraph.name(), fingerprint('7')),
                        contract(effect), runtime("BLOGE_GRAPH", itemGraph.name(), '6'),
                        List.of(new CapabilitySnapshot.Dependency("process",
                                CapabilityClosureIntegrity.reference(external), true, List.of())),
                        ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(), COMPILED_AT));
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "graph:" + rootGraph.name(), 1, "",
                        CapabilitySnapshot.Kind.COMPOSED, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                                rootGraph.name(), TARGET),
                        contract(effect), runtime("BLOGE_GRAPH", rootGraph.name(), '5'),
                        List.of(new CapabilitySnapshot.Dependency("expand",
                                CapabilityClosureIntegrity.reference(child), true, List.of())),
                        ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(), COMPILED_AT));
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, child, external), ""));
    }

    private static CapabilityContract contract(EffectContract effect) {
        return contract(effect, null);
    }

    private static CapabilityContract contract(
            EffectContract effect,
            MirrorArtifactRef stateModelRef) {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(), effect, CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true),
                stateModelRef,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, false,
                        List.of("sg"), false), CapabilityContract.SloContract.unspecified());
    }

    private static CapabilitySnapshot.RuntimeBinding runtime(
            String kind,
            String ref,
            char value) {
        return new CapabilitySnapshot.RuntimeBinding(kind, ref, fingerprint(value), true, List.of());
    }

    private static CapabilitySnapshot.Ownership ownership() {
        return new CapabilitySnapshot.Ownership("owner-a", "support", "pager");
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), PURPOSE, null, null, null, null, List.of(),
                "owner-a", COMPILED_AT.minus(Duration.ofDays(1)),
                COMPILED_AT.plus(Duration.ofDays(1)), "");
    }

    private static ArtifactProvenance stateProvenance() {
        return new ArtifactProvenance(
                ArtifactProvenance.SCHEMA_VERSION,
                ArtifactProvenance.SourceType.OWNER,
                List.of(), SCOPE.tenantId(), PURPOSE,
                null, null, null, null, List.of(),
                "owner-a", COMPILED_AT,
                COMPILED_AT.plus(Duration.ofDays(1)), "");
    }

    private static MirrorPlan.ExecutionPolicy policy() {
        return policy(1000);
    }

    private static MirrorPlan.ExecutionPolicy policy(int maximumInvocations) {
        return policy(maximumInvocations, false);
    }

    private static MirrorPlan.ExecutionPolicy policy(
            int maximumInvocations, boolean certificationRequired) {
        return new MirrorPlan.ExecutionPolicy(PURPOSE, false, false, false, false,
                certificationRequired,
                MirrorPlan.UnmatchedResolution.ABSTAINED, maximumInvocations,
                Duration.ofMinutes(5),
                CapabilityContract.DataClassification.CONFIDENTIAL, List.of("sg"),
                List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
    }

    private static FixtureBundle fixture(FixtureRule... rules) {
        return new FixtureBundle("", "customer-fixture", 1, TARGET, "CONFIDENTIAL",
                COMPILED_AT, 42L, List.of(rules), List.of(), Map.of());
    }

    private static FixtureRule rule(String id, String nodeId, FixtureRule.Behavior behavior) {
        return new FixtureRule("", id, FixtureRule.Selector.node(nodeId), behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule.Selector attemptSelector(String nodeId, int attempt) {
        return new FixtureRule.Selector("/root", nodeId, "", "", "", List.of(), List.of(),
                com.leanowtech.bloge.gateway.testing.domain.InvocationSite.InvocationKind.PRIMARY,
                List.of(attempt), List.of(), "", FixtureRule.Match.none());
    }

    private static Graph graph() {
        Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        nodes.put("loadCustomer", node("loadCustomer", "customer.lookup"));
        nodes.put("formatCustomer", node("formatCustomer", "customer.format"));
        return new Graph("customerView", nodes, List.of(), Set.copyOf(nodes.keySet()),
                Set.copyOf(nodes.keySet()), SchemaValidationLevel.OFF);
    }

    private static Graph statefulGraph() {
        Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        nodes.put("loadCustomer", new NodeSpec(
                "loadCustomer", "customer.lookup",
                (results, context) -> Map.of(
                        "customerId",
                        context.get("customerId")),
                ResilienceConfig.DEFAULT, Map.of(),
                OpaqueSchema.INSTANCE,
                OpaqueSchema.INSTANCE));
        nodes.put(
                "formatCustomer",
                node("formatCustomer", "customer.format"));
        return new Graph(
                "customerView", nodes, List.of(),
                Set.copyOf(nodes.keySet()),
                Set.copyOf(nodes.keySet()),
                SchemaValidationLevel.OFF);
    }

    private static NodeSpec node(String id, String operatorRef) {
        return new NodeSpec(id, operatorRef, null, ResilienceConfig.DEFAULT,
                Map.of(), OpaqueSchema.INSTANCE, OpaqueSchema.INSTANCE);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static MirrorResolution copyResolutionWithRequest(
            MirrorResolution source, String requestFingerprint) {
        return new MirrorResolution(source.schemaVersion(), source.resolutionFingerprint(),
                source.runId(), source.planFingerprint(), source.capabilityRef(),
                source.invocationSiteId(), source.graphPath(), source.correlationKey(),
                source.occurrence(), source.attempt(), requestFingerprint, source.status(),
                source.source(), source.payloadVisibility(), source.outputIncluded(),
                source.output(), source.outputFingerprint(), source.error(),
                source.matchedArtifactRefs(), source.matchedRuleRefs(), source.confidence(),
                source.freshness(), source.limitations());
    }

    private static void assertRunRejected(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MirrorRunRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private record GovernedCorpus(
            ResolvedCorpusPayloads payloads,
            FixedGenerationAuthority authority,
            MirrorServingGenerationToken token,
            InMemoryVisualEvidenceSigner signer,
            MirrorServingGenerationIntegrity integrity
    ) {
    }

    private static final class FixedGenerationAuthority
            implements MirrorServingGenerationAuthority {
        private MirrorServingGenerationToken current;
        private boolean available = true;
        private int floorReads;
        private MirrorServingGenerationToken advanceAfterFirstRead;

        private FixedGenerationAuthority(
                MirrorServingGenerationToken current) {
            this.current = current;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Resolution admit(AdmissionRequest request) {
            return available
                    ? Resolution.current(current)
                    : Resolution.unavailable("TEST_AUTHORITY_UNAVAILABLE");
        }

        @Override
        public Resolution currentFloor(FloorRequest request) {
            floorReads++;
            if (!available) {
                return Resolution.unavailable(
                        "TEST_AUTHORITY_UNAVAILABLE");
            }
            MirrorServingGenerationToken returned = current;
            if (floorReads == 1 && advanceAfterFirstRead != null) {
                current = advanceAfterFirstRead;
            }
            return Resolution.current(returned);
        }
    }

    private static final class TickingClock extends Clock {
        private Instant current;
        private final Duration step;

        private TickingClock(Instant current, Duration step) {
            this.current = current;
            this.step = step;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant value = current;
            current = current.plus(step);
            return value;
        }
    }

    private static final class TestTrustAuthority
            implements MirrorDeploymentIsolationRunTrustAuthority {
        private final boolean rejectConfirmation;

        private TestTrustAuthority(boolean rejectConfirmation) {
            this.rejectConfirmation = rejectConfirmation;
        }

        @Override
        public MirrorDeploymentIsolationRunTrust.Admission admit(
                CapabilitySnapshot.Scope scope) {
            return new MirrorDeploymentIsolationRunTrust.Admission(scope,
                    new MirrorArtifactRef(
                            MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                            "runtime-isolation-bundle", 7, fingerprint('b')),
                    new MirrorArtifactRef(
                            MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                            "runtime-isolation-authority", 3, fingerprint('c')),
                    new MirrorArtifactRef(MirrorDeploymentIsolationAttestation.ARTIFACT_KIND,
                            "runtime-isolation-attestation", 5, fingerprint('d')),
                    new MirrorArtifactRef(
                            MirrorDeploymentIsolationAttestationStatusPublication.ARTIFACT_KIND,
                            "runtime-isolation-attestation", 7, fingerprint('e')),
                    new MirrorArtifactRef(MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                            "runtime-isolation-agent", 1, fingerprint('f')),
                    COMPILED_AT, COMPILED_AT.plus(Duration.ofDays(1)));
        }

        @Override
        public MirrorDeploymentIsolationRunTrust.Binding confirm(
                MirrorDeploymentIsolationRunTrust.Admission admission,
                Instant startedAt,
                Instant completedAt) {
            if (rejectConfirmation) {
                throw new TrustException("RUN_TRUST_DECISION_CHANGED");
            }
            return new MirrorDeploymentIsolationRunTrust.Binding("",
                    admission.decisionRef(), admission.authorityKeySetRef(),
                    admission.attestationRef(), admission.statusRef(),
                    admission.admittedSnapshotRef(),
                    new MirrorArtifactRef(MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                            admission.admittedSnapshotRef().id(), 2, fingerprint('0')),
                    admission.admittedAt(), completedAt);
        }

        @Override
        public CommitPermit acquireCommitPermit(
                CapabilitySnapshot.Scope scope,
                MirrorDeploymentIsolationRunTrust.Binding binding) {
            return () -> { };
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final class ExternalReadOperator implements Operator<Object, Object> {
        private final AtomicInteger calls;

        private ExternalReadOperator(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Object execute(Object input, OperatorContext context) {
            calls.incrementAndGet();
            return Map.of("real", true);
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static final class InternalOperator implements Operator<Object, Object> {
        private final AtomicInteger calls;
        private final AtomicReference<Instant> deadline;

        private InternalOperator(AtomicInteger calls, AtomicReference<Instant> deadline) {
            this.calls = calls;
            this.deadline = deadline;
        }

        @Override
        public Object execute(Object input, OperatorContext context) {
            calls.incrementAndGet();
            deadline.set(context.executionBudget().deadline().orElse(null));
            return Map.of("formatted", true);
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

}
