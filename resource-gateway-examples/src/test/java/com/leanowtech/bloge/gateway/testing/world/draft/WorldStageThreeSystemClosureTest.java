package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecordIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompiler;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.testing.world.WorldSliceSelection;
import com.leanowtech.bloge.gateway.testing.world.impact.WorldContractImpactAnalyzer;
import com.leanowtech.bloge.gateway.testing.world.impact.WorldImpactIndexService;
import com.leanowtech.bloge.gateway.testing.world.impact.WorldImpactReconciliation;
import com.leanowtech.bloge.gateway.testing.world.impact.InMemoryWorldImpactSnapshotRepository;
import com.leanowtech.bloge.gateway.testing.world.impact.WorldImpactSnapshotRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class WorldStageThreeSystemClosureTest {
    private static final String CANARY = "S3F-PAYLOAD-CANARY-ONLY-IN-VAULT";
    private static final Instant START = WorldDraftTestSupport.NOW;

    @Test
    void governedGoldenCaptureClosesMaterializePublishRuntimeAndImpactEvidence() {
        SystemFixture system = systemFixture();
        WorldDraftCandidate captured = system.service().capture("s3f-system", system.access(), system.source());
        WorldDraftCandidate redacted = system.service().redact("s3f-system", captured.revision(),
                system.access(), system.policy());
        WorldDraftCandidate ready = system.service().markReviewReady("s3f-system", redacted.revision(),
                system.access());
        WorldDraftCandidate approved = system.service().approve("s3f-system", ready.revision(), system.access());
        WorldDraftMaterializer.MaterializedDraft draft = system.service().materialize(
                "s3f-system", approved.revision(), system.access(), system.baseWorld());

        assertThat(draft.worldModel().revision()).isEqualTo(2);
        assertThat(draft.published()).isFalse();
        WorldDraftCandidate materialized = system.service().find("s3f-system", system.access()).orElseThrow();
        assertThat(materialized.state()).isEqualTo(WorldDraftState.MATERIALIZED_DRAFT);
        WorldDraftAssetRepository.StoredAsset unpublished = system.assets().find(
                system.access().tenantId(), materialized.candidateId(), materialized.materializationFingerprint(),
                system.access()).orElseThrow();
        assertThat(unpublished.published()).isFalse();

        Graph graph = graph(system.contract());
        Scenario scenario = scenario(graph, draft.worldModel(), system.contract());
        WorldScenarioCompilation compilation = new WorldScenarioCompiler().compile(scenario,
                draft.worldModel(), graph, new DefaultOperatorRegistry(), Map.of(system.contract().contractId(),
                        new WorldSliceSelection(unpublished.worldModel().slices().getFirst().provider(),
                                unpublished.worldModel().slices().getFirst().apiVersion(),
                                unpublished.worldModel().slices().getFirst().fingerprint())));
        assertThat(compilation.sourceMap().sourceToOutputs(logicalSource(system.contract())))
                .containsExactly("invocation-site:/root/lookup#PRIMARY");

        WorldDraftCandidate published = system.service().publish("s3f-system", materialized.revision(),
                system.access());
        WorldDraftAssetRepository.StoredAsset asset = system.assets().find(system.access().tenantId(),
                published.candidateId(), published.materializationFingerprint(), system.access()).orElseThrow();
        assertThat(asset.published()).isTrue();
        WorldDraftPublishedBehaviorRuntime runtime = new WorldDraftPublishedBehaviorRuntime(
                system.assets(), system.vault(), new com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit());
        Object exactResult = runtime.execute(asset, Map.of("safe", "value"), system.access());
        assertThat(exactResult).isEqualTo(Map.of("result", "ok"));
        Throwable requestError = catchThrowable(() -> runtime.execute(asset,
                Map.of("safe", "changed"), system.access()));
        assertThat(requestError).isInstanceOf(WorldDraftCandidateException.class);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String invocationSite = compilation.sourceMap().sourceToOutputs(logicalSource(system.contract()))
                .getFirst().substring("invocation-site:".length());
        String ruleId = compilation.bundle().rules().getFirst().ruleId();
        TestRunEvidence rawEvidence = new TestRunEvidence("", "s3f-run", TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST", graphFingerprint(graph),
                ProtocolFingerprint.of(mapper, compilation.bundle()), "", START, START.plusSeconds(1),
                List.of(new TestRunEvidence.NodeTrace("lookup", "world-draft-runtime", "SUCCESS", "REAL",
                        Map.of("safe", "value"), exactResult, "", 1, invocationSite, "/root", "", 1, 1,
                        List.of(new TestRunEvidence.AttemptTrace(1, "SUCCESS", "REAL",
                                Map.of("safe", "value"), exactResult, "", 1)))),
                List.of(), List.of(new TestRunEvidence.FixtureConsumption(ruleId, 1, true, "CONSUMED")),
                List.of(), List.of(), identityMetadata());
        TestRunEvidence evidence = TestSemanticResultFingerprint.attach(mapper, rawEvidence);
        TestEvidenceIntegrityService integrity = new TestEvidenceIntegrityService(mapper,
                new InMemoryVisualEvidenceSigner());
        TestEvidenceIntegrityService.SealResult seal = integrity.seal(evidence);
        assertThat(seal.verified()).isTrue();
        TestRunRecord record = new TestRunRecord("s3f-run", system.access().tenantId(), "org-s3f",
                "project-s3f", "test", system.access().actorId(),
                new TestExecutionApiRequest.Target("GRAPH", graph.name(), graphFingerprint(graph)),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("STORED",
                        compilation.bundle().fixtureBundleId(), compilation.bundle().revision(),
                        evidence.fixtureBundleFingerprint()), TestExecutionApiRequest.Verbosity.FULL, null,
                seal.evidence(), seal.integrity(), evidence.completedAt(), START.plusSeconds(30));
        TestRunRecord verified = TestRunRecordIntegrity.verifiedCreateSnapshot(mapper, integrity, record);

        WorldImpactIndexService indexes = new WorldImpactIndexService(new InMemoryWorldImpactSnapshotRepository());
        WorldImpactSnapshotRepository.IndexedStatic indexedStatic = indexes.rebuildStatic(scenario,
                draft.worldModel(), compilation, 7, START);
        WorldImpactSnapshotRepository.IndexedRuntime indexedRuntime = indexes.indexVerifiedRuntime(verified,
                mapper, integrity, scenario, compilation, 7, START.plusSeconds(2));
        WorldImpactReconciliation reconciliation = WorldImpactReconciliation.reconcile(
                indexedStatic.snapshot(), indexedRuntime.snapshot());
        assertThat(reconciliation.publicationBlocked()).isFalse();
        assertThat(reconciliation.entries()).extracting(WorldImpactReconciliation.Entry::classification)
                .containsOnly(WorldImpactReconciliation.Classification.DECLARED_AND_OBSERVED);
        var report = new WorldContractImpactAnalyzer().analyze(indexes.repository(), system.access().tenantId(),
                system.contract().contractId(), system.contract(), system.contract(), START, START.plusSeconds(3));
        assertThat(report.scopeStatus()).isEqualTo(
                com.leanowtech.bloge.gateway.testing.world.impact.WorldContractImpactReport.ScopeStatus.COMPLETE);
        assertThat(report.gateBlocked()).isFalse();
        assertThat(report.status()).isEqualTo(
                com.leanowtech.bloge.gateway.testing.world.impact.WorldContractImpactReport.Status.COMPATIBLE_CHANGE);
        assertThat(report.affectedScenarioIds()).containsExactly(scenario.scenarioId());

        assertThat(indexedStatic.stale()).isFalse();
        assertThat(indexedRuntime.stale()).isFalse();
        assertPayloadFree(CANARY, captured, redacted, redacted.redactionReport(), ready, approved, materialized, published,
                draft, asset, seal.evidence(), indexedStatic.snapshot(), indexedRuntime.snapshot(),
                reconciliation, report, requestError);
        assertThat(system.payload().request().toString()).contains(CANARY);
    }

    @Test
    void unauthorizedCrossTenantExpiredTamperedAndProductionPurposeFailBeforePayloadRead() {
        SystemFixture unauthorized = systemFixture();
        assertCode(() -> unauthorized.service().capture("unauthorized", null, unauthorized.source()),
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        assertThat(unauthorized.reads()).hasValue(0);

        SystemFixture foreign = systemFixture(WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                "tenant-b", "foreign"), null);
        assertCode(() -> foreign.service().capture("foreign", foreign.access(), foreign.source()),
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        assertThat(foreign.reads()).hasValue(0);

        WorldDraftTestSupport.Fixture expired = fixture(WorldDraftTestSupport.NOW.minusSeconds(1));
        SystemFixture expiredSystem = systemFixture(expired, new AtomicReference<>(expired.metadata()), null, null, null, null);
        assertCode(() -> expiredSystem.service().capture("expired", expiredSystem.access(), expiredSystem.source()),
                WorldDraftCandidateException.Code.SOURCE_EXPIRED);
        assertThat(expiredSystem.reads()).hasValue(0);

        WorldDraftTestSupport.Fixture valid = fixture(WorldDraftTestSupport.NOW.plusSeconds(60));
        WorldDraftSourceAuthority.SourceMetadata tampered = WorldDraftSourceAuthority.SourceMetadata.unsafeForTest(
                valid.source(), valid.source().tenantId(), true, true, valid.metadata().expiresAt(),
                valid.metadata().requestSchema(), valid.metadata().responseSchema(), valid.metadata().schemaFingerprint(),
                valid.metadata().redactionPolicyFingerprint(), valid.metadata().requestFingerprint(),
                valid.metadata().responseFingerprint(), WorldDraftTestSupport.fp("tampered"));
        SystemFixture tamperedSystem = systemFixture(valid, new AtomicReference<>(tampered), null, null, null, null);
        assertCode(() -> tamperedSystem.service().capture("tampered", tamperedSystem.access(), tamperedSystem.source()),
                WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        assertThat(tamperedSystem.reads()).hasValue(0);

        AtomicInteger reads = new AtomicInteger();
        assertThatThrownBy(() -> new WorldDraftCandidateService.Access(
                WorldDraftTestSupport.TENANT, "PRODUCTION", "actor", "production"))
                .isInstanceOf(WorldDraftCandidateException.class);
        assertThat(reads).hasValue(0);
    }

    @Test
    void inlineKindDoesNotExistAndDlpUnsafePayloadCannotBecomeReviewReady() {
        assertThat(EnumSet.allOf(WorldDraftSourceRef.Kind.class).stream().map(Enum::name))
                .doesNotContain("INLINE");
        assertThatThrownBy(() -> Enum.valueOf(WorldDraftSourceRef.Kind.class, "INLINE"))
                .isInstanceOf(IllegalArgumentException.class);

        WorldDraftTestSupport.Fixture unsafe = unsafeFixture();
        SystemFixture system = systemFixture(unsafe, new AtomicReference<>(unsafe.metadata()), null, null, null, null);
        WorldDraftCandidate captured = system.service().capture("unsafe", system.access(), system.source());
        WorldDraftCandidate redacted = system.service().redact("unsafe", captured.revision(), system.access(),
                system.policy());
        assertThat(redacted.redactionReport().safe()).isFalse();
        assertCode(() -> system.service().markReviewReady("unsafe", redacted.revision(), system.access()),
                WorldDraftCandidateException.Code.REDACTION_REQUIRED);
        assertThat(system.reads()).hasValue(2);
    }

    @Test
    void approvalSourceSchemaAndPolicyDriftCannotMaterializeOrPublish() {
        SystemFixture badApproval = systemFixture(fixture(START.plusSeconds(60)), null, null, null,
                (candidate, access) -> new WorldDraftApproval(
                candidate.candidateId(), candidate.revision(), WorldDraftTestSupport.fp("other-source"),
                candidate.schemaFingerprint(), candidate.redactionPolicyFingerprint(), "bad-approval",
                access.actorId(), START), null);
        WorldDraftCandidate captured = badApproval.service().capture("bad-approval", badApproval.access(), badApproval.source());
        WorldDraftCandidate redacted = badApproval.service().redact("bad-approval", captured.revision(),
                badApproval.access(), badApproval.policy());
        WorldDraftCandidate ready = badApproval.service().markReviewReady("bad-approval", redacted.revision(),
                badApproval.access());
        assertCode(() -> badApproval.service().approve("bad-approval", ready.revision(), badApproval.access()),
                WorldDraftCandidateException.Code.APPROVAL_INVALID);
        assertThat(badApproval.assets().find(badApproval.access().tenantId(), "bad-approval", "missing",
                badApproval.access())).isEmpty();

        SystemFixture source = systemFixture();
        WorldDraftSourceRef changedSource = WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                source.access().tenantId(), "s3f-changed");
        assertMaterializationDrift(WorldDraftTestSupport.metadata(changedSource, source.policy(), START.plusSeconds(60),
                source.fixture().metadata().requestSchema(), source.fixture().metadata().responseSchema(), source.payload()),
                Drift.SOURCE);
        SystemFixture schema = systemFixture();
        assertMaterializationDrift(WorldDraftTestSupport.metadata(schema.source(), schema.policy(), START.plusSeconds(60),
                SchemaEnvelope.object(Map.of("safe", Map.of("type", "string"), "newField", Map.of("type", "string")),
                        List.of("safe")), schema.fixture().metadata().responseSchema(), schema.payload()), Drift.SCHEMA);
        SystemFixture policy = systemFixture();
        WorldDraftRedactionPolicy changedPolicy = new WorldDraftRedactionPolicy("policy-v2", policy.policy().requestRules(),
                policy.policy().responseRules());
        assertMaterializationDrift(WorldDraftTestSupport.metadata(policy.source(), changedPolicy, START.plusSeconds(60),
                policy.fixture().metadata().requestSchema(), policy.fixture().metadata().responseSchema(), policy.payload()),
                Drift.POLICY);

        SystemFixture publication = systemFixture();
        WorldDraftCandidate materialized = materialize(publication);
        publication.metadata().set(WorldDraftTestSupport.metadata(publication.source(), changedPolicy, START.plusSeconds(60),
                publication.fixture().metadata().requestSchema(), publication.fixture().metadata().responseSchema(),
                publication.payload()));
        assertCode(() -> publication.service().publish("s3f-system", materialized.revision(), publication.access()),
                WorldDraftCandidateException.Code.APPROVAL_STALE);
        assertThat(publication.assets().find(publication.access().tenantId(), "s3f-system",
                materialized.materializationFingerprint(), publication.access())).get()
                .extracting(WorldDraftAssetRepository.StoredAsset::published).isEqualTo(false);
    }

    @Test
    void vaultMaterializerAndPromotionFaultsLeaveNoPublishedOrPartialLifecycle() {
        SystemFixture vault = systemFixture(null, null, new FailingVault(), null, null, null);
        WorldDraftCandidate captured = vault.service().capture("vault-fault", vault.access(), vault.source());
        assertCode(() -> vault.service().redact("vault-fault", captured.revision(), vault.access(), vault.policy()),
                WorldDraftCandidateException.Code.REDACTION_REQUIRED);
        assertThat(vault.service().find("vault-fault", vault.access())).get()
                .extracting(WorldDraftCandidate::state).isEqualTo(WorldDraftState.CAPTURED);
        assertThat(vault.assets().find(vault.access().tenantId(), "vault-fault", "missing", vault.access())).isEmpty();

        SystemFixture materializer = systemFixture(null, null, null, request -> {
            throw new IllegalStateException("materializer fault");
        }, null, null);
        WorldDraftCandidate approved = approve(materializer, "materializer-fault");
        assertCode(() -> materializer.service().materialize("materializer-fault", approved.revision(),
                materializer.access(), materializer.baseWorld()), WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        assertThat(materializer.assets().find(materializer.access().tenantId(), "materializer-fault", "missing",
                materializer.access())).isEmpty();
        assertThat(materializer.service().find("materializer-fault", materializer.access())).get()
                .extracting(WorldDraftCandidate::state).isEqualTo(WorldDraftState.APPROVED);

        for (InMemoryWorldDraftPromotionTransaction.FailurePoint point :
                InMemoryWorldDraftPromotionTransaction.FailurePoint.values()) {
            AtomicReference<InMemoryWorldDraftPromotionTransaction.FailurePoint> failure = new AtomicReference<>(point);
            SystemFixture promotion = systemFixture(null, null, null, null, null, failedAfter(failure));
            WorldDraftCandidate current = approveAndMaterialize(promotion, "promotion-" + point.name());
            assertCode(() -> promotion.service().publish(current.candidateId(), current.revision(), promotion.access()),
                    WorldDraftCandidateException.Code.PUBLICATION_INVALID);
            WorldDraftCandidate head = promotion.service().find(current.candidateId(), promotion.access()).orElseThrow();
            assertThat(head.state()).isEqualTo(WorldDraftState.MATERIALIZED_DRAFT);
            WorldDraftAssetRepository.StoredAsset draft = promotion.assets().find(promotion.access().tenantId(),
                    head.candidateId(), head.materializationFingerprint(), promotion.access()).orElseThrow();
            assertThat(draft.published()).isFalse();
            WorldDraftPublicationReceipt receipt = ServerOwnedWorldDraftAuthorities.publication().issue(head,
                    promotion.access());
            String receiptFingerprint = InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt);
            assertThat(promotion.receipts().findPublication(promotion.access().tenantId(), head.candidateId(),
                    receiptFingerprint, promotion.access())).isEmpty();
            WorldDraftRedactedPayloadVault.PublishedBinding binding = new WorldDraftRedactedPayloadVault.PublishedBinding(
                    head.tenantId(), head.candidateId(), head.redactedPayloadRef().artifactRevision(),
                    draft.worldModel().fingerprint(), draft.rule().fingerprint(), receiptFingerprint);
            assertThat(promotion.vault().readPublished(head.redactedPayloadRef(), binding, promotion.access())).isEmpty();
        }
    }

    private static void assertMaterializationDrift(WorldDraftSourceAuthority.SourceMetadata drift, Drift kind) {
        SystemFixture system = systemFixture();
        WorldDraftCandidate approved = approve(system, "drift-" + kind.name());
        system.metadata().set(drift);
        assertCode(() -> system.service().materialize(approved.candidateId(), approved.revision(), system.access(),
                system.baseWorld()), kind == Drift.SOURCE ? WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED
                        : WorldDraftCandidateException.Code.APPROVAL_STALE);
        assertThat(system.assets().find(system.access().tenantId(), approved.candidateId(), "missing", system.access()))
                .isEmpty();
    }

    private static WorldDraftCandidate approve(SystemFixture system, String id) {
        WorldDraftCandidate captured = system.service().capture(id, system.access(), system.source());
        WorldDraftCandidate redacted = system.service().redact(id, captured.revision(), system.access(), system.policy());
        WorldDraftCandidate ready = system.service().markReviewReady(id, redacted.revision(), system.access());
        return system.service().approve(id, ready.revision(), system.access());
    }

    private static WorldDraftCandidate materialize(SystemFixture system) {
        WorldDraftCandidate approved = approve(system, "s3f-system");
        system.service().materialize("s3f-system", approved.revision(), system.access(), system.baseWorld());
        return system.service().find("s3f-system", system.access()).orElseThrow();
    }

    private static WorldDraftCandidate approveAndMaterialize(SystemFixture system, String id) {
        WorldDraftCandidate approved = approve(system, id);
        system.service().materialize(id, approved.revision(), system.access(), system.baseWorld());
        return system.service().find(id, system.access()).orElseThrow();
    }

    private static InMemoryWorldDraftPromotionTransaction.FailureInjector failedAfter(
            AtomicReference<InMemoryWorldDraftPromotionTransaction.FailurePoint> failure) {
        return point -> { if (failure.compareAndSet(point, null)) throw new IllegalStateException("promotion fault"); };
    }

    private static Scenario scenario(Graph graph, ResourceWorldModel world, LogicalResourceContract contract) {
        return new Scenario("s3f-scenario", world.tenantId(), 1,
                new Scenario.TargetRef("GRAPH", graph.name(), graphFingerprint(graph)), world, Map.of(),
                Scenario.WorldStateInit.EMPTY, List.of(), List.of(Scenario.ContractDependency.of(contract)));
    }

    private static Graph graph(LogicalResourceContract contract) {
        Operator<Object, Object> identity = (input, context) -> input;
        return new GraphBuilder("s3f-graph").node("lookup", identity)
                .meta("tags", WorldScenarioCompiler.logicalContractTag(contract.contractId(),
                        contract.contractFingerprint())).build();
    }

    private static String graphFingerprint(Graph graph) {
        return GraphArtifactFingerprint.of(new ObjectMapper(), graph);
    }

    private static String logicalSource(LogicalResourceContract contract) {
        return com.leanowtech.bloge.gateway.testing.world.WorldScenarioSourceMap.coordinate(
                "logical-contract", contract.contractId() + "@" + contract.contractFingerprint());
    }

    private static Map<String, Object> identityMetadata() {
        return Map.of("tenantId", WorldDraftTestSupport.TENANT, "organizationId", "org-s3f",
                "projectId", "project-s3f", "environmentId", "test", "actorId", "reviewer",
                "payloadSanitized", true);
    }

    private static void assertPayloadFree(String canary, Object... values) {
        for (Object value : values) assertThat(String.valueOf(value)).doesNotContain(canary);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   WorldDraftCandidateException.Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorldDraftCandidateException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }

    private static SystemFixture systemFixture() {
        return systemFixture(fixture(START.plusSeconds(60)), null, null, null, null, null);
    }

    private static SystemFixture systemFixture(WorldDraftSourceRef source, WorldDraftRedactionPolicy policy) {
        WorldDraftTestSupport.Fixture base = fixture(START.plusSeconds(60));
        return systemFixture(new WorldDraftTestSupport.Fixture(source,
                WorldDraftTestSupport.metadata(source, policy == null ? base.policy() : policy,
                        START.plusSeconds(60), base.metadata().requestSchema(), base.metadata().responseSchema(), base.payload()),
                base.payload(), policy == null ? base.policy() : policy), new AtomicReference<>(), null, null, null, null);
    }

    private static SystemFixture systemFixture(WorldDraftTestSupport.Fixture fixture,
                                               AtomicReference<WorldDraftSourceAuthority.SourceMetadata> metadata,
                                               WorldDraftRedactedPayloadVault vault,
                                               WorldDraftMaterializer materializer,
                                               WorldDraftApprovalAuthority approval,
                                               InMemoryWorldDraftPromotionTransaction.FailureInjector failureInjector) {
        fixture = fixture == null ? fixture(START.plusSeconds(60)) : fixture;
        AtomicReference<WorldDraftSourceAuthority.SourceMetadata> selected = metadata == null || metadata.get() == null
                ? new AtomicReference<>(fixture.metadata()) : metadata;
        AtomicInteger reads = new AtomicInteger();
        WorldDraftSourceAuthority authority = governedAuthority(fixture, selected, reads);
        InMemoryWorldDraftCandidateRepository candidates = new InMemoryWorldDraftCandidateRepository();
        InMemoryWorldDraftRedactedPayloadVault actualVault = vault instanceof InMemoryWorldDraftRedactedPayloadVault inMemory
                ? inMemory : vault == null ? new InMemoryWorldDraftRedactedPayloadVault() : null;
        InMemoryWorldDraftAssetRepository assets = new InMemoryWorldDraftAssetRepository();
        InMemoryWorldDraftAuthorityReceiptRepository receipts = new InMemoryWorldDraftAuthorityReceiptRepository();
        WorldDraftMaterializer actualMaterializer = materializer == null ? new ServerOwnedWorldDraftMaterializer(
                ServerOwnedWorldDraftMaterializer.bloge(new com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit()))
                : materializer;
        WorldDraftPromotionTransaction actualPromotion = new InMemoryWorldDraftPromotionTransaction(
                candidates, assets, receipts, ServerOwnedWorldDraftAuthorities.publication(), actualVault,
                failureInjector == null ? point -> { } : failureInjector);
        WorldDraftCandidateService service = new WorldDraftCandidateService(authority, candidates,
                WorldDraftRedactor.schemaGuided(), vault == null ? actualVault : vault, actualMaterializer,
                approval == null ? ServerOwnedWorldDraftAuthorities.approval(Clock.fixed(START, ZoneOffset.UTC)) : approval,
                ServerOwnedWorldDraftAuthorities.publication(), assets, receipts, actualPromotion,
                Clock.fixed(START, ZoneOffset.UTC));
        return new SystemFixture(fixture, selected, reads, service, candidates, actualVault, assets, receipts,
                new WorldDraftCandidateService.Access(WorldDraftTestSupport.TENANT,
                        WorldDraftCandidateService.PURPOSE, "reviewer", "s3f"),
                baseWorld(fixture), fixture.source(), fixture.policy());
    }

    private static WorldDraftSourceAuthority governedAuthority(WorldDraftTestSupport.Fixture fixture,
                                                                AtomicReference<WorldDraftSourceAuthority.SourceMetadata> metadata,
                                                                AtomicInteger reads) {
        List<WorldDraftSourceAdapter> adapters = new ArrayList<>();
        for (WorldDraftSourceRef.Kind kind : WorldDraftSourceRef.Kind.values()) {
            adapters.add(new WorldDraftSourceAdapter() {
                @Override public WorldDraftSourceRef.Kind kind() { return kind; }
                @Override public WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRef source,
                                                                                    WorldDraftCandidateService.Access access) {
                    if (kind != WorldDraftSourceRef.Kind.GOLDEN_CAPTURE || !fixture.source().equals(source)
                            || access == null || !access.tenantId().equals(source.tenantId())) {
                        throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
                    }
                    return metadata.get();
                }
                @Override public WorldDraftSourceAuthority.SourcePayload read(
                        WorldDraftSourceAuthority.SourceMetadata ignored,
                        WorldDraftCandidateService.Access access) {
                    reads.incrementAndGet();
                    return fixture.payload();
                }
            });
        }
        return new GovernedWorldDraftSourceRouter(adapters);
    }

    private static WorldDraftTestSupport.Fixture fixture(Instant expires) {
        WorldDraftSourceRef source = WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                WorldDraftTestSupport.TENANT, "s3f-golden");
        WorldDraftRedactionPolicy policy = WorldDraftTestSupport.policy();
        WorldDraftSourceAuthority.SourcePayload payload = new WorldDraftSourceAuthority.SourcePayload(
                Map.of("safe", "value", "secret", CANARY), Map.of("result", "ok"));
        return new WorldDraftTestSupport.Fixture(source, WorldDraftTestSupport.metadata(source, policy, expires,
                WorldDraftTestSupport.requestSchema(), WorldDraftTestSupport.responseSchema(), payload), payload, policy);
    }

    private static WorldDraftTestSupport.Fixture unsafeFixture() {
        WorldDraftSourceRef source = WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                WorldDraftTestSupport.TENANT, "s3f-unsafe");
        WorldDraftRedactionPolicy policy = WorldDraftTestSupport.policy();
        WorldDraftSourceAuthority.SourcePayload payload = new WorldDraftSourceAuthority.SourcePayload(
                Map.of("safe", "person@example.com", "secret", CANARY), Map.of("result", "ok"));
        return new WorldDraftTestSupport.Fixture(source, WorldDraftTestSupport.metadata(source, policy,
                START.plusSeconds(60), WorldDraftTestSupport.requestSchema(), WorldDraftTestSupport.responseSchema(), payload),
                payload, policy);
    }

    private static ResourceWorldModel baseWorld(WorldDraftTestSupport.Fixture fixture) {
        SchemaEnvelope requestSchema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")),
                List.of("id"));
        SchemaEnvelope responseSchema = SchemaEnvelope.object(Map.of("status", Map.of("type", "string")),
                List.of("status"));
        LogicalResourceContract contract = new LogicalResourceContract("s3f.contract",
                requestSchema, responseSchema,
                ResponseSemantics.confirmed("http.status in 200..299", Map.of("BUSINESS", List.of("NOT_FOUND")),
                        ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL));
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(), "s3f.resource",
                "S3F resource", "", List.of(), contract.inputShape(), contract.outputShape(), Map.of(), "ACTIVE");
        VisualResourceDescriptor descriptor = new VisualResourceDescriptor("s3f.resource",
                "https://example.test/s3f", "GET", Map.of(), null, Duration.ofSeconds(2),
                new VisualResourceParameterMapping(Map.of(), Map.of(), null),
                new VisualResourceResponseProtocol.HttpStatus(), "data");
        LogicalResourceBinding binding = LogicalResourceBinding.bind("s3f-provider", "v1", design, descriptor, contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(fixture.source().tenantId(),
                        "s3f-provider", "v1", contract.contractId(), contract.contractFingerprint(),
                        binding.descriptorFingerprint(), true), contract, binding,
                BlogeFragmentRef.frozen("s3f-base.bloge", "graph base { transform result { value = ctx.safe } }", "result"),
                StateSpec.empty());
        return new ResourceWorldModel("s3f-world", fixture.source().tenantId(), 1, List.of(slice));
    }

    private enum Drift { SOURCE, SCHEMA, POLICY }

    private record SystemFixture(WorldDraftTestSupport.Fixture fixture,
                                 AtomicReference<WorldDraftSourceAuthority.SourceMetadata> metadata,
                                 AtomicInteger reads, WorldDraftCandidateService service,
                                 InMemoryWorldDraftCandidateRepository candidates,
                                 InMemoryWorldDraftRedactedPayloadVault vault,
                                 InMemoryWorldDraftAssetRepository assets,
                                 InMemoryWorldDraftAuthorityReceiptRepository receipts,
                                 WorldDraftCandidateService.Access access, ResourceWorldModel baseWorld,
                                 WorldDraftSourceRef source, WorldDraftRedactionPolicy policy) {
        WorldDraftSourceAuthority.SourcePayload payload() { return fixture.payload(); }
        LogicalResourceContract contract() { return baseWorld.slices().getFirst().contract(); }
    }

    private static final class FailingVault implements WorldDraftRedactedPayloadVault {
        @Override public StoredPayload put(WorldDraftRedactedPayloadRef ref, WorldDraftRedactedPayload payload,
                                           WorldDraftCandidateService.Access access) {
            throw new IllegalStateException("vault fault");
        }
        @Override public Optional<StoredPayload> read(WorldDraftRedactedPayloadRef ref,
                                                      WorldDraftCandidateService.Access access) {
            return Optional.empty();
        }
    }
}
