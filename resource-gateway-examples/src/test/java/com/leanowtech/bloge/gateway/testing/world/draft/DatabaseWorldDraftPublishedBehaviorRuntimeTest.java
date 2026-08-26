package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRepository;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseWorldDraftPublishedBehaviorRuntimeTest {
    @Test
    void aNewRepositoryAndRuntimeInstanceReloadsPinnedBehaviorWithoutPayloadInAssetJson() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("published-behavior-" + System.nanoTime()).build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/h2/V20260827_001__world_draft_candidates.sql")).execute(database);
            ObjectMapper mapper = new ObjectMapper();
            DatabaseWorldDraftAuditSink audit = new DatabaseWorldDraftAuditSink(jdbc);
            WorldDraftPayloadProtector protector = WorldDraftPayloadProtector.fromConfiguration("test-v1",
                    "test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
            Clock clock = Clock.fixed(WorldDraftTestSupport.NOW, ZoneOffset.UTC);
            DatabaseWorldDraftRedactedPayloadVault vault = new DatabaseWorldDraftRedactedPayloadVault(
                    jdbc, mapper, clock, Duration.ofDays(30), audit, protector);
            GovernedCatalogRepository catalog = mock(GovernedCatalogRepository.class);
            when(catalog.findExact(any(GovernedResourceRef.class))).thenReturn(Optional.empty());
            DatabaseWorldDraftAssetRepository assets = new DatabaseWorldDraftAssetRepository(
                    jdbc, mapper, catalog, audit);

            WorldDraftRedactedPayload payload = new WorldDraftRedactedPayload(
                    Map.of("safe", "A"), Map.of("result", "response-A"));
            WorldDraftRedactedPayloadRef payloadRef = WorldDraftRedactedPayloadRef.of(
                    WorldDraftTestSupport.TENANT, "db-published", 1, payload);
            vault.put(payloadRef, payload, WorldDraftTestSupport.ACCESS);
            BlogeFragmentRef fragment = BlogeFragmentRef.frozen("db-published.bloge",
                    "graph dbPublished { transform result { value = ctx.__draft_response } }", "result");
            WorldDraftRule rule = new WorldDraftRule(WorldDraftTestSupport.fp("schema"),
                    payloadRef.requestFingerprint(), payloadRef.responseFingerprint(),
                    new WorldDraftFragmentRef(fragment), payloadRef);
            WorldDraftCandidate approved = candidate(payloadRef, rule);
            WorldDraftCandidate current = approved.next(WorldDraftState.MATERIALIZED_DRAFT,
                    approved.approvalFingerprint(), rule.fingerprint(), payloadRef,
                    approved.redactionReportFingerprint(), approved.redactionReport());
            WorldDraftAssetRepository.StoredAsset draft = new WorldDraftAssetRepository.StoredAsset(
                    new WorldDraftMaterializer.MaterializedDraft(approved, world(), rule, false));
            assets.saveDraft(new WorldDraftMaterializer.MaterializedDraft(approved, world(), rule, false),
                    WorldDraftTestSupport.ACCESS);
            WorldDraftPublicationReceipt receipt = new WorldDraftPublicationReceipt(current.candidateId(),
                    current.revision(), current.materializationFingerprint(), "db-publication");
            WorldDraftAssetRepository.StoredAsset published = assets.publish(draft, current, receipt,
                    WorldDraftTestSupport.ACCESS);
            String receiptFingerprint = InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt);
            WorldDraftRedactedPayloadVault.PublishedBinding binding =
                    new WorldDraftRedactedPayloadVault.PublishedBinding(WorldDraftTestSupport.TENANT,
                            current.candidateId(), payloadRef.artifactRevision(), published.worldModel().fingerprint(),
                            published.rule().fingerprint(), receiptFingerprint);
            vault.pin(payloadRef, binding, WorldDraftTestSupport.ACCESS);

            DatabaseWorldDraftAssetRepository restartedAssets = new DatabaseWorldDraftAssetRepository(
                    jdbc, mapper, catalog, audit);
            DatabaseWorldDraftRedactedPayloadVault restartedVault = new DatabaseWorldDraftRedactedPayloadVault(
                    jdbc, mapper, clock, Duration.ofDays(30), audit, protector);
            WorldDraftAssetRepository.StoredAsset reloaded = restartedAssets.find(
                    WorldDraftTestSupport.TENANT, current.candidateId(), current.materializationFingerprint(),
                    WorldDraftTestSupport.ACCESS).orElseThrow();
            WorldDraftPublishedBehaviorRuntime runtime = new WorldDraftPublishedBehaviorRuntime(
                    restartedAssets, restartedVault, new WorldFragmentTestKit());
            assertThat(runtime.execute(reloaded, Map.of("safe", "A"), WorldDraftTestSupport.ACCESS))
                    .isEqualTo(Map.of("result", "response-A"));
            assertThatThrownBy(() -> runtime.execute(reloaded, Map.of("safe", "B"),
                    WorldDraftTestSupport.ACCESS)).isInstanceOf(WorldDraftCandidateException.class);
            jdbc.update("UPDATE rg_world_draft_redacted_payloads SET published_rule_fingerprint=? "
                            + "WHERE candidate_id=?", WorldDraftTestSupport.fp("tampered-rule"), current.candidateId());
            assertThatThrownBy(() -> runtime.execute(reloaded, Map.of("safe", "A"),
                    WorldDraftTestSupport.ACCESS)).isInstanceOf(WorldDraftCandidateException.class);
            jdbc.update("UPDATE rg_world_draft_assets SET canonical_json=REPLACE(canonical_json, "
                            + "'\"artifactRevision\":1', '\"artifactRevision\":2') WHERE candidate_id=?",
                    current.candidateId());
            assertThatThrownBy(() -> restartedAssets.find(WorldDraftTestSupport.TENANT,
                    current.candidateId(), current.materializationFingerprint(), WorldDraftTestSupport.ACCESS))
                    .isInstanceOf(WorldDraftCandidateException.class);
            assertThat(jdbc.queryForObject("SELECT canonical_json FROM rg_world_draft_assets WHERE candidate_id=?",
                    String.class, current.candidateId())).doesNotContain("response-A");
        } finally {
            database.shutdown();
        }
    }

    private static WorldDraftCandidate candidate(WorldDraftRedactedPayloadRef ref, WorldDraftRule rule) {
        return new WorldDraftCandidate("db-published", 3, WorldDraftState.APPROVED,
                WorldDraftTestSupport.TENANT,
                WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                        WorldDraftTestSupport.TENANT, "db-published"),
                WorldDraftTestSupport.fp("metadata"), WorldDraftTestSupport.fp("schema"),
                WorldDraftTestSupport.fp("policy"), WorldDraftTestSupport.fp("request-raw"),
                WorldDraftTestSupport.fp("response-raw"), ref, WorldDraftTestSupport.fp("report"),
                WorldDraftRedactionReport.notProcessed(), WorldDraftTestSupport.fp("approval"), "");
    }

    private static ResourceWorldModel world() {
        LogicalResourceContract contract = new LogicalResourceContract("db-published-contract",
                com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope.object(
                        Map.of("safe", Map.of("type", "string")), List.of("safe")),
                com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope.object(
                        Map.of("result", Map.of("type", "string")), List.of("result")),
                ResponseSemantics.confirmed("http.status in 200..299", Map.of("BUSINESS", List.of("NOT_FOUND")),
                        ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL));
        com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract design =
                new com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract(contract.contractId(),
                        "db-published-resource", "DB published resource", "", List.of(),
                        contract.inputShape(), contract.outputShape(), Map.of(), "ACTIVE");
        LogicalResourceBinding binding = LogicalResourceBinding.bind("db-published-provider", "v1", design,
                new com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor(
                        "db-published-resource", "https://example.test/{id}", "GET", Map.of(), null,
                        Duration.ofSeconds(2), new com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping(
                                Map.of(), Map.of(), null), new com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol.HttpStatus(), "data"), contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(WorldDraftTestSupport.TENANT,
                        "db-published-provider", "v1", contract.contractId(), contract.contractFingerprint(),
                        binding.descriptorFingerprint(), true), contract, binding,
                BlogeFragmentRef.frozen("db-base.bloge",
                        "graph dbBase { transform result { value = ctx.safe } }", "result"), StateSpec.empty());
        return new ResourceWorldModel("db-published-world", WorldDraftTestSupport.TENANT, 2, List.of(slice));
    }
}
