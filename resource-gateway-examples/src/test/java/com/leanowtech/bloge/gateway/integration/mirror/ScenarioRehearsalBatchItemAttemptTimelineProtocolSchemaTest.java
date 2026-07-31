package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalBatchItemAttemptTimelineProtocolSchemaTest {
    private static final String SCHEMA =
            "scenario-rehearsal-batch-item-attempt-timeline-v1.schema.json";
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemaExactlyMatchesThePayloadFreeProjection()
            throws Exception {
        ScenarioRehearsalBatchItemAttemptTimeline timeline =
                timeline();
        JsonNode json = mapper.valueToTree(timeline);
        JsonNode schema = mapper.readTree(
                Files.readString(schemaPath()));

        assertExact(json, schema);
        assertExact(
                json.path("attempts").get(0),
                schema.at("/$defs/attempt"));
        assertExact(
                json.path("authorTarget"),
                schema.at("/$defs/authorTarget"));
    }

    @Test
    void protocolCannotCarryPayloadCredentialOrWorkerIdentity()
            throws Exception {
        String source = Files.readString(schemaPath());

        for (String forbidden : Set.of(
                "requestPayload",
                "responsePayload",
                "businessPayload",
                "fixture",
                "credential",
                "secret",
                "token",
                "password",
                "leaseOwner",
                "workerIdentity",
                "exception",
                "stackTrace")) {
            assertThat(source)
                    .doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static ScenarioRehearsalBatchItemAttemptTimeline
    timeline() {
        Instant started =
                Instant.parse("2026-07-30T10:00:00Z");
        return new ScenarioRehearsalBatchItemAttemptTimeline(
                "",
                "job-001",
                0,
                3,
                1,
                2,
                started.plusSeconds(60),
                ScenarioRehearsalBatchPolicy.FailureMode.COLLECT_ALL,
                true,
                List.of(
                        new ScenarioRehearsalBatchItemAttemptTimeline.Attempt(
                                1,
                                ScenarioRehearsalBatchItemAttemptTimeline
                                        .Attempt.State.TERMINAL,
                                started,
                                started.plusSeconds(3),
                                "FAILED",
                                "RG.DEPENDENCY.TIMEOUT",
                                1,
                                2)),
                new ScenarioRehearsalBatchItemAttemptTimeline.AuthorTarget(
                        ScenarioRehearsalBatchItemAttemptTimeline.AuthorTarget
                                .Kind.GRAPH_DRAFT,
                        "answer-graph",
                        "Answer graph",
                        "answer-draft",
                        7,
                        "sha256:" + "a".repeat(64),
                        "grounding",
                        "golden-answer",
                        "run-001",
                        "Knowledge Answers",
                        "Scenario author"));
    }

    private static void assertExact(
            JsonNode value,
            JsonNode schema) {
        assertThat(schema.path(
                "additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(schema.path("properties")));
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(
                        textValues(schema.path("required")));
    }

    private static Path schemaPath() {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas",
                "resource-gateway-mirror", SCHEMA);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of(
                        "docs", "schemas",
                        "resource-gateway-mirror", SCHEMA);
    }

    private static LinkedHashSet<String> fieldNames(
            JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(
            JsonNode value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        value.forEach(item -> values.add(item.asText()));
        return values;
    }
}
