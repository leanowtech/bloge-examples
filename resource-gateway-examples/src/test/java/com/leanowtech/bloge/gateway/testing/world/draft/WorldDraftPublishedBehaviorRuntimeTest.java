package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldDraftPublishedBehaviorRuntimeTest {
    @Test
    void publishedBehaviorReloadsByReferenceAndRejectsForeignTamperedOrRevokedAccess() {
        WorldDraftTestSupport.Fixture fixture = WorldDraftTestSupport.fixture(
                WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                        WorldDraftTestSupport.TENANT, "published-behavior"),
                WorldDraftTestSupport.policy(), WorldDraftTestSupport.NOW.plusSeconds(60));
        Clock clock = Clock.fixed(WorldDraftTestSupport.NOW, ZoneOffset.UTC);
        WorldDraftCandidateRepository candidates = new InMemoryWorldDraftCandidateRepository();
        WorldDraftRedactedPayloadVault vault = new InMemoryWorldDraftRedactedPayloadVault();
        InMemoryWorldDraftAssetRepository assets = new InMemoryWorldDraftAssetRepository();
        InMemoryWorldDraftAuthorityReceiptRepository receipts = new InMemoryWorldDraftAuthorityReceiptRepository();
        WorldDraftPublicationAuthority publication = ServerOwnedWorldDraftAuthorities.publication();
        WorldDraftPromotionTransaction promotion = new InMemoryWorldDraftPromotionTransaction(
                candidates, assets, receipts, publication, vault);
        WorldDraftCandidateService service = new WorldDraftCandidateService(
                WorldDraftTestSupport.authority(fixture, new java.util.concurrent.atomic.AtomicInteger()),
                candidates, WorldDraftRedactor.schemaGuided(), vault,
                new ServerOwnedWorldDraftMaterializer(ServerOwnedWorldDraftMaterializer.bloge(new WorldFragmentTestKit())),
                ServerOwnedWorldDraftAuthorities.approval(clock), publication, assets, receipts, promotion, clock);

        WorldDraftCandidate captured = service.capture("published-behavior", WorldDraftTestSupport.ACCESS,
                fixture.source());
        WorldDraftCandidate redacted = service.redact("published-behavior", captured.revision(),
                WorldDraftTestSupport.ACCESS, fixture.policy());
        WorldDraftCandidate ready = service.markReviewReady("published-behavior", redacted.revision(),
                WorldDraftTestSupport.ACCESS);
        WorldDraftCandidate approved = service.approve("published-behavior", ready.revision(),
                WorldDraftTestSupport.ACCESS);
        WorldDraftMaterializer.MaterializedDraft draft = service.materialize("published-behavior", approved.revision(),
                WorldDraftTestSupport.ACCESS, world());
        WorldDraftCandidate published = service.publish("published-behavior",
                service.find("published-behavior", WorldDraftTestSupport.ACCESS).orElseThrow().revision(),
                WorldDraftTestSupport.ACCESS);
        WorldDraftAssetRepository.StoredAsset asset = assets.find(WorldDraftTestSupport.TENANT,
                published.candidateId(), published.materializationFingerprint(), WorldDraftTestSupport.ACCESS).orElseThrow();
        assertThat(asset.rule().redactedPayloadRef()).isEqualTo(redacted.redactedPayloadRef());

        WorldDraftPublishedBehaviorRuntime runtime = new WorldDraftPublishedBehaviorRuntime(
                assets, vault, new WorldFragmentTestKit());
        Object responseA = runtime.execute(asset, Map.of("safe", "value"), WorldDraftTestSupport.ACCESS);
        assertThat(responseA).isEqualTo(Map.of("result", "ok"));
        assertThatThrownBy(() -> runtime.execute(asset, Map.of("safe", "B"), WorldDraftTestSupport.ACCESS))
                .isInstanceOf(WorldDraftCandidateException.class);

        WorldDraftPublishedBehaviorRuntime restarted = new WorldDraftPublishedBehaviorRuntime(
                assets, vault, new WorldFragmentTestKit());
        assertThat(restarted.execute(published.candidateId(), published.materializationFingerprint(),
                Map.of("safe", "value"), WorldDraftTestSupport.ACCESS)).isEqualTo(Map.of("result", "ok"));

        WorldDraftCandidateService.Access foreign = new WorldDraftCandidateService.Access(
                "tenant-b", WorldDraftCandidateService.PURPOSE, "reviewer", "foreign");
        assertThatThrownBy(() -> restarted.execute(published.candidateId(), published.materializationFingerprint(),
                Map.of("safe", "value"), foreign)).isInstanceOf(WorldDraftCandidateException.class);
        vault.revoke(asset.rule().redactedPayloadRef(), WorldDraftTestSupport.ACCESS);
        assertThatThrownBy(() -> restarted.execute(asset, Map.of("safe", "value"), WorldDraftTestSupport.ACCESS))
                .isInstanceOf(WorldDraftCandidateException.class);
        assertThat(draft.rule().redactedPayloadRef()).isEqualTo(asset.rule().redactedPayloadRef());
    }

    private static ResourceWorldModel world() {
        LogicalResourceContract contract = new LogicalResourceContract("published-contract",
                SchemaEnvelope.object(Map.of("safe", Map.of("type", "string"),
                        "secret", Map.of("type", "string")), List.of("safe")),
                SchemaEnvelope.object(Map.of("result", Map.of("type", "string")), List.of("result")),
                ResponseSemantics.confirmed("http.status in 200..299", Map.of("BUSINESS", List.of("NOT_FOUND")),
                        ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL));
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(), "published-resource",
                "Published resource", "", List.of(), contract.inputShape(), contract.outputShape(), Map.of(), "ACTIVE");
        LogicalResourceBinding binding = LogicalResourceBinding.bind("published-provider", "v1", design,
                new VisualResourceDescriptor("published-resource", "https://example.test/{id}", "GET", Map.of(),
                        null, Duration.ofSeconds(2), new VisualResourceParameterMapping(Map.of(), Map.of(), null),
                        new VisualResourceResponseProtocol.HttpStatus(), "data"), contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(WorldDraftTestSupport.TENANT,
                        "published-provider", "v1", contract.contractId(), contract.contractFingerprint(),
                        binding.descriptorFingerprint(), true), contract, binding,
                BlogeFragmentRef.frozen("base-published.bloge",
                        "graph basePublished { transform result { value = ctx.safe } }", "result"),
                StateSpec.empty());
        return new ResourceWorldModel("published-world", WorldDraftTestSupport.TENANT, 1, List.of(slice));
    }
}
