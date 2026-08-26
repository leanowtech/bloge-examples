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
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerOwnedWorldDraftMaterializerTest {
    @Test
    void compilesAndExecutesAdmittedFragmentWithExactRequestAndResponse() {
        WorldDraftTestSupport.Fixture fixture = WorldDraftTestSupport.fixture(
                WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                        WorldDraftTestSupport.TENANT, "materializer"), WorldDraftTestSupport.policy(),
                WorldDraftTestSupport.NOW.plusSeconds(60));
        AtomicInteger reads = new AtomicInteger();
        WorldDraftRedactedPayloadVault vault = new InMemoryWorldDraftRedactedPayloadVault();
        WorldDraftMaterializer materializer = new ServerOwnedWorldDraftMaterializer(
                ServerOwnedWorldDraftMaterializer.bloge(new WorldFragmentTestKit()));
        WorldDraftCandidateService service = new WorldDraftCandidateService(
                WorldDraftTestSupport.authority(fixture, reads), new InMemoryWorldDraftCandidateRepository(),
                WorldDraftRedactor.schemaGuided(), vault, materializer,
                Clock.fixed(WorldDraftTestSupport.NOW, ZoneOffset.UTC));

        WorldDraftCandidate captured = service.capture("materializer", WorldDraftTestSupport.ACCESS, fixture.source());
        WorldDraftCandidate redacted = service.redact("materializer", captured.revision(),
                WorldDraftTestSupport.ACCESS, fixture.policy());
        WorldDraftCandidate ready = service.markReviewReady("materializer", redacted.revision(),
                WorldDraftTestSupport.ACCESS);
        WorldDraftCandidate approved = service.approve("materializer", ready.revision(),
                WorldDraftTestSupport.ACCESS);

        WorldDraftMaterializer.MaterializedDraft draft = service.materialize("materializer", approved.revision(),
                WorldDraftTestSupport.ACCESS, world(fixture));

        assertThat(draft.rule().fragment()).isNotNull();
        assertThat(draft.worldModel().slices()).singleElement().satisfies(slice ->
                assertThat(slice.behavior()).isEqualTo(draft.rule().fragment().blogeFragment()));
        assertThat(draft.rule().inputFingerprint()).startsWith("sha256:");
        assertThat(draft.rule().redactedPayloadRef()).isNotNull()
                .isEqualTo(draft.candidate().redactedPayloadRef());
        Object executed = new WorldFragmentTestKit().execute(draft.rule().fragment().blogeFragment(),
                Map.of("__draft_response", Map.of("result", "ok")));
        assertThat(executed).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) executed).get("value")).isEqualTo(Map.of("result", "ok"));
        assertThat(((Map<?, ?>) executed).get("value")).isNotEqualTo(Map.of("safe", "value"));
        assertThat(reads).hasValue(2);
    }

    @Test
    void exactAIsTheOnlyRequestThatCanExecuteAndTamperingIsRejected() {
        WorldDraftTestSupport.Fixture fixture = WorldDraftTestSupport.fixture(
                WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                        WorldDraftTestSupport.TENANT, "exact"), WorldDraftTestSupport.policy(),
                WorldDraftTestSupport.NOW.plusSeconds(60));
        WorldDraftRedactedPayload payload = WorldDraftRedactor.schemaGuided().redact(fixture.payload(),
                fixture.metadata(), fixture.policy()).payload();
        WorldDraftRedactedPayloadRef ref = WorldDraftRedactedPayloadRef.of(
                fixture.source().tenantId(), "exact", 1, payload);
        WorldDraftRedactedPayloadVault.StoredPayload stored =
                new InMemoryWorldDraftRedactedPayloadVault().put(ref, payload, WorldDraftTestSupport.ACCESS);
        BlogeFragmentRef fragment = BlogeFragmentRef.frozen("exact.bloge",
                "graph exact { transform result { value = ctx.__draft_response } }", "result");
        ResourceWorldModel world = world(fixture, fragment);
        WorldDraftRule rule = new WorldDraftRule(fixture.metadata().schemaFingerprint(),
                payload.requestFingerprint(), payload.responseFingerprint(), new WorldDraftFragmentRef(fragment));
        WorldDraftWorldRuntimeAdmitter runtime = ServerOwnedWorldDraftMaterializer.bloge(new WorldFragmentTestKit());

        assertThat(runtime.admit(world, rule, payload.request(), payload.response(), WorldDraftTestSupport.ACCESS))
                .isSameAs(world);
        assertThatThrownBy(() -> runtime.admit(world, rule, Map.of("safe", "B"), payload.response(),
                WorldDraftTestSupport.ACCESS)).isInstanceOf(WorldDraftCandidateException.class);
        assertThatThrownBy(() -> runtime.admit(world, rule, payload.request(), Map.of("result", "tampered"),
                WorldDraftTestSupport.ACCESS)).isInstanceOf(WorldDraftCandidateException.class);
        WorldDraftRule foreignFragment = new WorldDraftRule(rule.requestSchemaFingerprint(),
                rule.inputFingerprint(), rule.responseFingerprint(), new WorldDraftFragmentRef(
                BlogeFragmentRef.frozen("foreign.bloge",
                        "graph foreign { transform result { value = ctx.__draft_response } }", "result")));
        assertThatThrownBy(() -> runtime.admit(world, foreignFragment, payload.request(), payload.response(),
                WorldDraftTestSupport.ACCESS)).isInstanceOf(WorldDraftCandidateException.class);
        assertThat(stored.ref()).isEqualTo(ref);
    }

    private static ResourceWorldModel world(WorldDraftTestSupport.Fixture fixture) {
        return world(fixture, BlogeFragmentRef.frozen("base.bloge",
                "graph base { transform result { value = ctx.safe } }", "result"));
    }

    private static ResourceWorldModel world(WorldDraftTestSupport.Fixture fixture, BlogeFragmentRef behavior) {
        SchemaEnvelope requestSchema = SchemaEnvelope.object(Map.of("safe", Map.of("type", "string"),
                "secret", Map.of("type", "string")), List.of("safe"));
        SchemaEnvelope responseSchema = SchemaEnvelope.object(Map.of("result", Map.of("type", "string")), List.of("result"));
        LogicalResourceContract contract = new LogicalResourceContract("draft-contract",
                requestSchema, responseSchema,
                ResponseSemantics.confirmed("http.status in 200..299", Map.of("BUSINESS", List.of("NOT_FOUND")),
                        ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL));
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(), "draft-resource",
                "Draft resource", "", List.of(), contract.inputShape(), contract.outputShape(), Map.of(), "ACTIVE");
        LogicalResourceBinding binding = LogicalResourceBinding.bind("draft-provider", "v1", design,
                new VisualResourceDescriptor("draft-resource", "https://example.test/{id}", "GET", Map.of(),
                        null, Duration.ofSeconds(2), new VisualResourceParameterMapping(Map.of(), Map.of(), null),
                        new VisualResourceResponseProtocol.HttpStatus(), "data"), contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(WorldDraftTestSupport.TENANT,
                        "draft-provider", "v1", contract.contractId(), contract.contractFingerprint(),
                        binding.descriptorFingerprint(), true), contract, binding,
                behavior,
                StateSpec.empty());
        return new ResourceWorldModel("draft-world", WorldDraftTestSupport.TENANT, 1, List.of(slice));
    }
}
