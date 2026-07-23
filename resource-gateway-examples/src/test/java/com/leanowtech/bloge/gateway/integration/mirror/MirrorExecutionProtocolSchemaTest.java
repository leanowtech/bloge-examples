package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorExecutionProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemasCloseEverySerializedCommandAndSummaryField() throws Exception {
        MirrorExecutionRequest request = new MirrorExecutionRequest("", "request-1", "plan-1",
                MirrorPersistenceTestFixtures.fingerprint('1'), Map.of("customerId", "C-1"));
        MirrorExecutionRequest stateful = new MirrorExecutionRequest(
                MirrorExecutionRequest.STATEFUL_SCHEMA_VERSION,
                "request-2", "plan-1",
                MirrorPersistenceTestFixtures.fingerprint('1'),
                Map.of("customerId", "C-1"),
                new MirrorSessionRunBinding(
                        "refund-session-1",
                        MirrorPersistenceTestFixtures.fingerprint('9')));
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(mapper,
                MirrorPersistenceTestFixtures.scope("org-a"), "plan-1", '2');
        MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.evidence(mapper,
                new InMemoryVisualEvidenceSigner(), plan, "run-1", '3');
        MirrorRunSummary summary = MirrorRunSummary.from(bundle);
        JsonNode requestSchema = schema("mirror-execution-request-v1.schema.json");
        JsonNode statefulRequestSchema = schema(
                "mirror-execution-request-v2.schema.json");
        JsonNode summarySchema = schema("mirror-run-summary-v1.schema.json");

        assertProperties(mapper.valueToTree(request), requestSchema.path("properties"));
        assertProperties(
                mapper.valueToTree(stateful),
                statefulRequestSchema.path("properties"));
        assertProperties(
                mapper.valueToTree(stateful.sessionBinding()),
                statefulRequestSchema.at(
                        "/$defs/sessionBinding/properties"));
        assertProperties(mapper.valueToTree(summary), summarySchema.path("properties"));
        assertProperties(mapper.valueToTree(summary.scope()),
                summarySchema.at("/$defs/scope/properties"));
        assertThat(requestSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(statefulRequestSchema.path(
                "additionalProperties").asBoolean()).isFalse();
        assertThat(statefulRequestSchema.at(
                "/$defs/sessionBinding/additionalProperties")
                .asBoolean()).isFalse();
        assertThat(summarySchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(summarySchema.at("/$defs/scope/additionalProperties").asBoolean()).isFalse();
        assertThat(requestSchema.at("/properties/context/additionalProperties").asBoolean())
                .isTrue();
    }

    @Test
    void runSummarySchemaCannotCarryBusinessOrFixturePayloads() throws Exception {
        JsonNode properties = schema("mirror-run-summary-v1.schema.json").path("properties");

        assertThat(properties.has("input")).isFalse();
        assertThat(properties.has("output")).isFalse();
        assertThat(properties.has("context")).isFalse();
        assertThat(properties.has("fixtureBundle")).isFalse();
        assertThat(properties.has("replayPayloads")).isFalse();
        assertThat(properties.has("requestContextFingerprint")).isTrue();
        assertThat(properties.has("evidenceBundleFingerprint")).isTrue();
    }

    @Test
    void javaSummaryRejectsSemanticContradictionsThatJsonSchemaCannotExpress() {
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(mapper,
                MirrorPersistenceTestFixtures.scope("org-a"), "plan-1", '4');
        MirrorRunSummary valid = MirrorRunSummary.from(MirrorPersistenceTestFixtures.evidence(
                mapper, new InMemoryVisualEvidenceSigner(), plan, "run-2", '5'));

        assertThatThrownBy(() -> new MirrorRunSummary(valid.schemaVersion(), valid.runId(),
                valid.requestId(), valid.planId(), valid.planFingerprint(),
                valid.requestContextFingerprint(), valid.scope(), valid.status(),
                valid.evidenceClass(), valid.startedAt(), valid.completedAt(),
                valid.durationMs() + 1, valid.nodeTraceCount(), valid.edgeTraceCount(),
                valid.resolutionCount(), valid.evidenceBundleFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MirrorRunSummary(valid.schemaVersion(), valid.runId(),
                valid.requestId(), valid.planId(), "not-a-fingerprint",
                valid.requestContextFingerprint(), valid.scope(), valid.status(),
                valid.evidenceClass(), valid.startedAt(), valid.completedAt(),
                valid.durationMs(), valid.nodeTraceCount(), valid.edgeTraceCount(),
                valid.resolutionCount(), valid.evidenceBundleFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
        CapabilitySnapshot.Scope incompleteScope = new CapabilitySnapshot.Scope(
                valid.scope().tenantId(), valid.scope().organizationId(), "",
                valid.scope().environmentId(), "");
        assertThatThrownBy(() -> new MirrorRunSummary(valid.schemaVersion(), valid.runId(),
                valid.requestId(), valid.planId(), valid.planFingerprint(),
                valid.requestContextFingerprint(), incompleteScope, valid.status(),
                valid.evidenceClass(), valid.startedAt(), valid.completedAt(),
                valid.durationMs(), valid.nodeTraceCount(), valid.edgeTraceCount(),
                valid.resolutionCount(), valid.evidenceBundleFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
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
}
