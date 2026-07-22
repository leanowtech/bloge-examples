package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemasMatchEverySerializedProtocolField() throws Exception {
        CapabilitySnapshot snapshot = CapabilitySnapshotIntegrity.seal(mapper, snapshot());
        JsonNode snapshotValue = mapper.valueToTree(snapshot);
        JsonNode contractValue = mapper.valueToTree(snapshot.contract());
        JsonNode effectValue = mapper.valueToTree(snapshot.contract().effect());
        JsonNode provenanceValue = mapper.valueToTree(snapshot.provenance());
        JsonNode snapshotSchema = schema("capability-snapshot-v1.schema.json");
        JsonNode contractSchema = schema("capability-contract-v1.schema.json");
        JsonNode effectSchema = schema("effect-contract-v1.schema.json");
        JsonNode provenanceSchema = schema("artifact-provenance-v1.schema.json");
        JsonNode transitionSchema = schema("capability-lifecycle-transition-v1.schema.json");
        JsonNode transitionValue = mapper.valueToTree(new CapabilityLifecycleTransitionRequest("", 4,
                CapabilitySnapshot.Lifecycle.REVIEWED, Instant.parse("2026-08-22T00:00:00Z"), ""));

        assertProperties(snapshotValue, snapshotSchema.path("properties"));
        assertProperties(contractValue, contractSchema.path("properties"));
        assertProperties(effectValue, effectSchema.path("properties"));
        assertProperties(provenanceValue, provenanceSchema.path("properties"));
        assertProperties(transitionValue, transitionSchema.path("properties"));
        assertProperties(snapshotValue.path("source"), snapshotSchema.at("/$defs/source/properties"));
        assertProperties(snapshotValue.path("scope"), snapshotSchema.at("/$defs/scope/properties"));
        assertProperties(snapshotValue.path("runtime"), snapshotSchema.at("/$defs/runtime/properties"));
        assertProperties(snapshotValue.path("ownership"), snapshotSchema.at("/$defs/ownership/properties"));
        assertProperties(contractValue.path("idempotency"), contractSchema.at("/$defs/idempotency/properties"));
        assertProperties(contractValue.path("compatibility"), contractSchema.at("/$defs/compatibility/properties"));
        assertProperties(contractValue.path("security"), contractSchema.at("/$defs/security/properties"));
        assertProperties(contractValue.path("slo"), contractSchema.at("/$defs/slo/properties"));
        assertThat(snapshotSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(contractSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(effectSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(provenanceSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(transitionSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(snapshotValue.at("/contract/slo/timeout").asText()).isEqualTo("PT3S");
    }

    @Test
    void schemasFreezeAllLifecycleRiskAndSourceEnums() throws Exception {
        JsonNode snapshot = schema("capability-snapshot-v1.schema.json");
        JsonNode effect = schema("effect-contract-v1.schema.json");
        JsonNode provenance = schema("artifact-provenance-v1.schema.json");

        assertThat(snapshot.at("/properties/lifecycle/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("DRAFT", "REVIEWED", "ACTIVE", "DEPRECATED", "STALE", "REVOKED");
        assertThat(effect.at("/properties/mode/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("READ_ONLY", "VIRTUAL_MUTATION", "EXTERNAL_MUTATION", "MIXED", "UNKNOWN");
        assertThat(provenance.at("/properties/sourceType/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("OWNER", "RECORDED", "INFERRED", "SYNTHESIZED");
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
}
