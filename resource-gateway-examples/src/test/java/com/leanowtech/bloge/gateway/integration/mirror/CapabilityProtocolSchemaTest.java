package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemasMatchEverySerializedProtocolField() throws Exception {
        CapabilitySnapshot snapshot = CapabilitySnapshotIntegrity.seal(mapper, snapshot());
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper, composedRoot(snapshot));
        CapabilityClosure closure = CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, snapshot), ""));
        JsonNode snapshotValue = mapper.valueToTree(snapshot);
        JsonNode closureValue = mapper.valueToTree(closure);
        JsonNode contractValue = mapper.valueToTree(snapshot.contract());
        JsonNode effectValue = mapper.valueToTree(snapshot.contract().effect());
        JsonNode provenanceValue = mapper.valueToTree(snapshot.provenance());
        JsonNode snapshotSchema = schema("capability-snapshot-v1.schema.json");
        JsonNode closureSchema = schema("capability-closure-v1.schema.json");
        JsonNode contractSchema = schema("capability-contract-v1.schema.json");
        JsonNode effectSchema = schema("effect-contract-v1.schema.json");
        JsonNode provenanceSchema = schema("artifact-provenance-v1.schema.json");
        JsonNode transitionSchema = schema("capability-lifecycle-transition-v1.schema.json");
        JsonNode transitionValue = mapper.valueToTree(new CapabilityLifecycleTransitionRequest("", 4,
                CapabilitySnapshot.Lifecycle.REVIEWED, Instant.parse("2026-08-22T00:00:00Z"), ""));
        MirrorPlan plan = mirrorPlan(closure);
        JsonNode planValue = mapper.valueToTree(plan);
        JsonNode planSchema = schema("mirror-plan-v1.schema.json");
        JsonNode planCreateValue = mapper.valueToTree(new MirrorPlanCreateRequest("",
                plan.planId(), "ordersView", "sha256:" + "d".repeat(64), closure,
                plan.fixtureBundleRef(), 1000, Duration.ofMinutes(5), true,
                Instant.parse("2026-07-22T01:00:00Z")));
        JsonNode planCreateSchema = schema("mirror-plan-create-request-v1.schema.json");
        MirrorResolution resolution = mirrorResolution(plan);
        JsonNode resolutionValue = mapper.valueToTree(resolution);
        JsonNode resolutionSchema = schema("mirror-resolution-v1.schema.json");

        assertProperties(snapshotValue, snapshotSchema.path("properties"));
        assertProperties(closureValue, closureSchema.path("properties"));
        assertProperties(contractValue, contractSchema.path("properties"));
        assertProperties(effectValue, effectSchema.path("properties"));
        assertProperties(provenanceValue, provenanceSchema.path("properties"));
        assertProperties(transitionValue, transitionSchema.path("properties"));
        assertProperties(planValue, planSchema.path("properties"));
        assertProperties(planCreateValue, planCreateSchema.path("properties"));
        assertProperties(resolutionValue, resolutionSchema.path("properties"));
        assertProperties(snapshotValue.path("source"), snapshotSchema.at("/$defs/source/properties"));
        assertProperties(snapshotValue.path("scope"), snapshotSchema.at("/$defs/scope/properties"));
        assertProperties(snapshotValue.path("runtime"), snapshotSchema.at("/$defs/runtime/properties"));
        assertProperties(snapshotValue.path("ownership"), snapshotSchema.at("/$defs/ownership/properties"));
        assertProperties(closureValue.path("rootRef"), closureSchema.at("/$defs/capabilityRef/properties"));
        assertProperties(contractValue.path("idempotency"), contractSchema.at("/$defs/idempotency/properties"));
        assertProperties(contractValue.path("compatibility"), contractSchema.at("/$defs/compatibility/properties"));
        assertProperties(contractValue.path("security"), contractSchema.at("/$defs/security/properties"));
        assertProperties(contractValue.path("slo"), contractSchema.at("/$defs/slo/properties"));
        assertProperties(planValue.path("scope"), planSchema.at("/$defs/scope/properties"));
        assertProperties(planValue.path("externalBindings").get(0),
                planSchema.at("/$defs/externalBinding/properties"));
        assertProperties(planValue.path("executionServices"),
                planSchema.at("/$defs/executionServices/properties"));
        assertProperties(planValue.path("policy"), planSchema.at("/$defs/policy/properties"));
        assertProperties(planCreateValue.path("fixtureBundleRef"),
                planCreateSchema.at("/$defs/fixtureBundleRef/properties"));
        assertProperties(resolutionValue.path("capabilityRef"),
                resolutionSchema.at("/$defs/artifactRef/properties"));
        assertProperties(resolutionValue.path("confidence"),
                resolutionSchema.at("/$defs/confidence/properties"));
        assertProperties(mapper.valueToTree(new MirrorResolution.MirrorError(
                        "CUSTOMER_NOT_FOUND", "BUSINESS", "")),
                resolutionSchema.at("/$defs/error/properties"));
        assertThat(snapshotSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(closureSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(contractSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(effectSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(provenanceSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(transitionSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(planSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(planCreateSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(resolutionSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(resolutionSchema.at(
                "/properties/matchedArtifactRefs/maxItems").asInt())
                .isEqualTo(MirrorResolution.MAXIMUM_ARTIFACT_REFS);
        assertThat(snapshotValue.at("/contract/slo/timeout").asText()).isEqualTo("PT3S");
    }

    @Test
    void servingGenerationAndMirrorPlanV2SchemasMatchSerializedFields()
            throws Exception {
        CapabilitySnapshot snapshot =
                CapabilitySnapshotIntegrity.seal(mapper, snapshot());
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(
                mapper, composedRoot(snapshot));
        CapabilityClosure closure = CapabilityClosureIntegrity.seal(
                mapper, new CapabilityClosure(
                        "", CapabilityClosureIntegrity.reference(root),
                        List.of(root, snapshot), ""));
        Instant issuedAt = Instant.parse("2026-07-22T00:00:00Z");
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        Clock.fixed(issuedAt, ZoneOffset.UTC));
        MirrorServingGenerationToken token =
                new MirrorServingGenerationIntegrity(mapper).seal(
                        new MirrorServingGenerationToken.Material(
                                "orders-serving", 2,
                                "sha256:" + "7".repeat(64),
                                root.scope(), "MIRROR_REHEARSAL",
                                "sha256:" + "8".repeat(64), 14,
                                issuedAt, issuedAt.plus(Duration.ofHours(2)),
                                Duration.ofSeconds(5)),
                        "corpus-authority-a", signer);
        MirrorPlan plan = mirrorPlanV2(closure, token);
        JsonNode tokenValue = mapper.valueToTree(token);
        JsonNode tokenSchema = schema(
                "mirror-serving-generation-token-v1.schema.json");
        JsonNode planValue = mapper.valueToTree(plan);
        JsonNode planSchema = schema("mirror-plan-v2.schema.json");

        assertProperties(tokenValue, tokenSchema.path("properties"));
        assertProperties(
                tokenValue.path("material"),
                tokenSchema.at("/$defs/material/properties"));
        assertProperties(
                tokenValue.path("seal"),
                tokenSchema.at("/$defs/seal/properties"));
        assertProperties(planValue, planSchema.path("properties"));
        assertThat(tokenSchema.path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(planSchema.path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(planValue.path("schemaVersion").asText())
                .isEqualTo(MirrorPlan.SCHEMA_VERSION);
        assertThat(planValue.path("servingGeneration")
                .path("tokenFingerprint").asText())
                .isEqualTo(token.tokenFingerprint());
    }

    private MirrorResolution mirrorResolution(MirrorPlan plan) {
        MirrorPlan.ExternalBinding binding = plan.externalBindings().getFirst();
        return MirrorResolutionIntegrity.seal(mapper, new MirrorResolution("", "", "run-orders-1",
                plan.planFingerprint(), binding.capabilityRef(), binding.invocationSiteId(),
                binding.graphPath(), "O-1", 1, 1, "sha256:" + "8".repeat(64),
                MirrorResolution.Status.RESOLVED, MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                MirrorResolution.PayloadVisibility.REDACTED, true,
                Map.of("orderId", "[redacted]"), "", null,
                List.of(plan.fixtureBundleRef()), binding.fixtureRuleRefs(),
                new ArtifactProvenance.Confidence(1, 1, 1, "owner-rule-v1"), 1,
                List.of("PAYLOAD_REDACTED")));
    }

    private MirrorPlan mirrorPlan(CapabilityClosure closure) {
        CapabilitySnapshot root = closure.snapshots().stream()
                .filter(value -> value.kind() == CapabilitySnapshot.Kind.COMPOSED)
                .findFirst().orElseThrow();
        CapabilitySnapshot child = closure.snapshots().stream()
                .filter(value -> value.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .findFirst().orElseThrow();
        MirrorPlan.ExternalBinding binding = new MirrorPlan.ExternalBinding(
                closure.rootRef(), root.dependencies().getFirst().nodeId(),
                CapabilityClosureIntegrity.reference(child), "/root/loadOrders#RESOURCE", "/root",
                child.source().sourceKind(), child.source().sourceRef(),
                List.of(MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                        MirrorPlan.MirrorSource.ABSTAINED), List.of("orders-response"));
        return MirrorPlanIntegrity.seal(mapper, new MirrorPlan("", "orders-mirror", "",
                closure.rootRef(), closure.fingerprint(), closure.snapshots(), root.scope(),
                new MirrorArtifactRef("FIXTURE_BUNDLE", "orders-fixture", 1,
                        "sha256:" + "e".repeat(64)), "sha256:" + "9".repeat(64),
                null, List.of(binding), null, List.of(),
                new MirrorPlan.ExecutionServices(Instant.parse("2026-07-22T00:00:00Z"),
                        42L, null, null),
                new MirrorPlan.ExecutionPolicy("MIRROR_REHEARSAL", false, false, false,
                        false, true, MirrorPlan.UnmatchedResolution.ABSTAINED, 1000,
                        Duration.ofMinutes(5), CapabilityContract.DataClassification.CONFIDENTIAL,
                        List.of("sg"), List.of(CapabilitySnapshot.Lifecycle.ACTIVE)),
                Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-22T01:00:00Z")));
    }

    private MirrorPlan mirrorPlanV2(
            CapabilityClosure closure,
            MirrorServingGenerationToken token) {
        MirrorPlan baseline = mirrorPlan(closure);
        MirrorPlan.ExternalBinding previous =
                baseline.externalBindings().getFirst();
        MirrorPlan.ExternalBinding recorded =
                new MirrorPlan.ExternalBinding(
                        previous.parentCapabilityRef(),
                        previous.dependencyNodeId(),
                        previous.capabilityRef(),
                        previous.invocationSiteId(),
                        previous.graphPath(),
                        previous.sourceKind(),
                        previous.sourceRef(),
                        List.of(
                                MirrorPlan.MirrorSource.RECORDED_EXACT,
                                MirrorPlan.MirrorSource.ABSTAINED),
                        List.of());
        return MirrorPlanIntegrity.seal(
                mapper, new MirrorPlan(
                        "", baseline.planId(), "",
                        baseline.rootCapability(),
                        baseline.capabilityClosureFingerprint(),
                        baseline.capabilityClosure(), baseline.scope(),
                        baseline.fixtureBundleRef(),
                        baseline.executionControlFingerprint(),
                        token, List.of(recorded),
                        baseline.scenarioPackRef(),
                        baseline.stateModelRefs(),
                        baseline.executionServices(),
                        baseline.policy(),
                        baseline.compiledAt(),
                        baseline.expiresAt()));
    }

    @Test
    void schemasFreezeAllLifecycleRiskAndSourceEnums() throws Exception {
        JsonNode snapshot = schema("capability-snapshot-v1.schema.json");
        JsonNode effect = schema("effect-contract-v1.schema.json");
        JsonNode provenance = schema("artifact-provenance-v1.schema.json");
        JsonNode resolution = schema("mirror-resolution-v1.schema.json");

        assertThat(snapshot.at("/properties/lifecycle/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("DRAFT", "REVIEWED", "ACTIVE", "DEPRECATED", "STALE", "REVOKED");
        assertThat(effect.at("/properties/mode/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("READ_ONLY", "VIRTUAL_MUTATION", "EXTERNAL_MUTATION", "MIXED", "UNKNOWN");
        assertThat(provenance.at("/properties/sourceType/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("OWNER", "RECORDED", "INFERRED", "SYNTHESIZED");
        assertThat(resolution.at("/$defs/source/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("SESSION_STATE", "OWNER_SPECIFIED", "RECORDED_EXACT",
                        "RECORDED_TRAJECTORY", "RECORDED_CLUSTER", "GOVERNED_REPLAY",
                        "SCHEMA_SYNTHESIZED", "CONTRACT_MOCK", "ABSTAINED");
        assertThat(resolution.at("/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("RESOLVED", "ABSTAINED", "REJECTED");
        assertThat(resolution.at("/properties/payloadVisibility/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("FULL", "REDACTED", "HASH_ONLY", "NONE");
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-mirror", file)));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static CapabilitySnapshot snapshot() {
        String sourceFingerprint = "sha256:" + "a".repeat(64);
        MirrorArtifactRef sourceRef = new MirrorArtifactRef(
                "OBSERVATION_SET", "orders-read", 4, "sha256:" + "b".repeat(64));
        ArtifactProvenance provenance = new ArtifactProvenance("",
                ArtifactProvenance.SourceType.RECORDED, List.of(sourceRef), "tenant-a",
                "MIRROR_REHEARSAL", Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-21T23:59:59Z"), 1200L,
                new ArtifactProvenance.Confidence(0.96, 0.94, 0.98, "wilson-v1"),
                List.of("rare-error-under-sampled"), "owner-a",
                Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-08-22T00:00:00Z"), "");
        CapabilityContract contract = new CapabilityContract("", SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(), List.of(), EffectContract.readOnly(List.of("resource:orders")),
                CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.DETERMINISTIC, "", true),
                null, CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, true, List.of("sg"), true),
                new CapabilityContract.SloContract(Duration.ofSeconds(3), 0.999, 150L, "orders-team"));
        return new CapabilitySnapshot("", "resource:orders.get", 4, "",
                CapabilitySnapshot.Kind.EXTERNAL,
                new CapabilitySnapshot.Scope("tenant-a", "org-a", "orders", "test", "sg"),
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                        "orders.get", sourceFingerprint), contract,
                new CapabilitySnapshot.RuntimeBinding("HTTP_RESOURCE", "orders.get@4",
                        "sha256:" + "c".repeat(64), true, List.of()), List.of(),
                new CapabilitySnapshot.Ownership("owner-a", "orders-team", "pager-orders"),
                CapabilitySnapshot.Lifecycle.ACTIVE, provenance,
                Instant.parse("2026-07-22T00:00:00Z"));
    }

    private static CapabilitySnapshot composedRoot(CapabilitySnapshot child) {
        return new CapabilitySnapshot("", "graph:ordersView", 4, "",
                CapabilitySnapshot.Kind.COMPOSED, child.scope(),
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                        "ordersView", "sha256:" + "d".repeat(64)), child.contract(), child.runtime(),
                List.of(new CapabilitySnapshot.Dependency("loadOrders",
                        CapabilityClosureIntegrity.reference(child), true, List.of())),
                child.ownership(), child.lifecycle(), child.provenance(), child.createdAt());
    }
}
