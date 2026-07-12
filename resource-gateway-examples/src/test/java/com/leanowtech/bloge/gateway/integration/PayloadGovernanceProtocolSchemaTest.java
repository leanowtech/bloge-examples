package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.ConfiguredVisualPayloadGovernancePolicy;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualRunPayloadRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadGovernanceProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void payloadReplayRetentionAndEvidenceSchemasMatchSerializedContracts() throws Exception {
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        var policy = new ConfiguredVisualPayloadGovernancePolicy("policy-1", "3", "CONFIDENTIAL",
                Set.of("reviewers"), Map.of("CONFIDENTIAL", Duration.ofDays(7)));
        var payloads = new InMemoryVisualRunPayloadRepository(policy, signer);
        var runs = new InMemoryVisualGraphRunRepository(signer, payloads);
        VisualGraphRunRecord created = runs.create(record("run-schema"));
        var access = payloads.access(created.runId(), created.createdAt());
        VisualGraphRunRecord hydrated = runs.find(created.runId()).orElseThrow().withPayload(access.payload());
        PayloadReplayBundle replay = PayloadReplayBundle.from(hydrated, access.status());
        PayloadRetentionView retention = new PayloadRetentionView("", access.status(),
                payloads.events(created.runId()));
        RunEvidenceBundle evidence = RunEvidenceBundle.from(runs.find(created.runId()).orElseThrow(), signer,
                access.status());

        JsonNode replaySchema = schema("payload-replay-bundle-v2.schema.json");
        JsonNode retentionSchema = schema("payload-retention-view-v1.schema.json");
        JsonNode evidenceSchema = schema("run-evidence-bundle-v7.schema.json");
        JsonNode commandSchema = schema("payload-lifecycle-command-v1.schema.json");
        JsonNode sweepSchema = schema("payload-retention-sweep-result-v1.schema.json");
        JsonNode replayJson = mapper.valueToTree(replay);
        JsonNode retentionJson = mapper.valueToTree(retention);
        JsonNode evidenceJson = mapper.valueToTree(evidence);

        assertProperties(replayJson, replaySchema.path("properties"));
        assertProperties(replayJson.path("payloadPolicy"), replaySchema.at("/$defs/payloadPolicy/properties"));
        assertProperties(replayJson.path("redaction"), replaySchema.at("/$defs/redaction/properties"));
        assertProperties(replayJson.path("retention"), replaySchema.at("/$defs/status/properties"));
        assertProperties(replayJson.at("/retention/descriptor"), replaySchema.at("/$defs/descriptor/properties"));
        assertProperties(replayJson.at("/retention/latestEvent"), replaySchema.at("/$defs/event/properties"));
        assertProperties(replayJson.at("/retention/latestEvent/evidenceSeal"),
                replaySchema.at("/$defs/seal/properties"));
        assertProperties(retentionJson, retentionSchema.path("properties"));
        assertProperties(evidenceJson.path("retention"), evidenceSchema.at("/$defs/retention/properties"));
        assertThat(evidenceJson.at("/retention/payloadFingerprint").asText()).startsWith("sha256:");
        assertProperties(mapper.valueToTree(new PayloadLifecycleCommand("", "request-1", "hold-1", "reason")),
                commandSchema.path("properties"));
        assertProperties(mapper.valueToTree(new PayloadRetentionSweepResult("", Instant.EPOCH, 2)),
                sweepSchema.path("properties"));
    }

    @Test
    void capabilitiesAdvertiseGovernedPayloadLifecycleWithoutClaimingLiveReplay() {
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        var policy = new ConfiguredVisualPayloadGovernancePolicy("policy-1", "3", "CONFIDENTIAL", Set.of(),
                Map.of("CONFIDENTIAL", Duration.ofDays(7)));
        IntegrationCapabilities capabilities = IntegrationCapabilities.current(signer.descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, policy.descriptor());

        assertThat(capabilities.supportedObjects().get("payloadReplay"))
                .containsExactly(PayloadReplayBundle.SCHEMA_VERSION_V1, PayloadReplayBundle.SCHEMA_VERSION);
        assertThat(capabilities.supportedObjects().get("runEvidence"))
                .contains(RunEvidenceBundle.SCHEMA_VERSION_V6, RunEvidenceBundle.SCHEMA_VERSION);
        assertThat(capabilities.features())
                .containsEntry("detachedPayloadVault", true)
                .containsEntry("payloadClassificationPolicy", true)
                .containsEntry("selectivePayloadRetention", true)
                .containsEntry("payloadLegalHold", true)
                .containsEntry("signedPayloadLifecycle", true)
                .containsEntry("replayExternalSideEffects", false);
        assertThat(capabilities.endpoints()).extracting(endpoint -> endpoint.method() + " " + endpoint.path())
                .contains("GET /api/integration/runs/{runId}/payload-retention",
                        "POST /api/integration/runs/{runId}/payload-retention/holds",
                        "POST /api/integration/runs/{runId}/payload-retention/purge",
                        "POST /api/integration/payload-retention/purge-expired");
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "tool-studio-resource-gateway", file)));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static VisualGraphRunRecord record(String runId) {
        GraphDraft draft = new GraphDraft("", "draft-schema", 1, "visualPolicy", "tenant-a", "local", "prod",
                "", SchemaEnvelope.opaque(), List.of(), List.of(), Map.of(),
                new GraphDraft.OutputSelection("response", ""));
        VisualGraphRunResponse response = new VisualGraphRunResponse(true, true, true, "visualPolicy", "response",
                Map.of("decision", "approved"), Map.of("response", Map.of("decision", "approved")),
                Map.of("response", "COMPLETED"), 10, Map.of("response", 5L), List.of(), List.of(), null, null,
                "graph visualPolicy {}");
        return VisualGraphRunRecord.storedDraft(draft, Map.of("customerId", "customer-42"), response)
                .withIdentity(runId, null);
    }
}
