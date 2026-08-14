package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeSourceProtocolSchemaTest {
    private static final String PAGE_SCHEMA =
            "authoritative-outcome-source-page-v1.schema.json";
    private static final String COMMAND_SCHEMA =
            "authoritative-outcome-connector-control-command-v1.schema.json";
    private static final String CHECKPOINT_SCHEMA =
            "authoritative-outcome-source-checkpoint-v1.schema.json";
    private static final String PAGE_FIXTURE =
            "authoritative-outcome-source-page-live-v1.fixture.json";
    private static final String COMMAND_FIXTURE =
            "authoritative-outcome-source-command-backfill-v1.fixture.json";
    private static final String CHECKPOINT_FIXTURE =
            "authoritative-outcome-source-checkpoint-live-v1.fixture.json";
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemasExactlyMatchServerProtocols() throws Exception {
        JsonNode page = mapper.valueToTree(page());
        JsonNode pageSchema = read(PAGE_SCHEMA);
        assertExact(page, pageSchema);
        assertExact(page.path("watermark"), pageSchema.at("/$defs/watermark"));
        assertExact(page.path("entries").get(0), pageSchema.at("/$defs/entry"));

        JsonNode command = mapper.valueToTree(command());
        JsonNode commandSchema = read(COMMAND_SCHEMA);
        assertExact(command, commandSchema);
        assertExact(command.path("eventTimeRange"), commandSchema.at("/$defs/eventTimeRange"));

        JsonNode checkpoint = mapper.valueToTree(checkpoint());
        JsonNode checkpointSchema = read(CHECKPOINT_SCHEMA);
        assertExact(checkpoint, checkpointSchema);
        assertExact(checkpoint.path("key"), checkpointSchema.at("/$defs/streamKey"));
    }

    @Test
    void publicSourceProtocolsCannotRepresentPayloadCredentialsOrRawCursors()
            throws Exception {
        String source = Files.readString(path(PAGE_SCHEMA))
                + Files.readString(path(COMMAND_SCHEMA))
                + Files.readString(path(CHECKPOINT_SCHEMA));
        for (String forbidden : Set.of(
                "requestPayload", "responsePayload", "businessPayload", "customerId",
                "credential", "secret", "token", "password", "endpointUri",
                "rawCursor", "cursorValue", "stackTrace")) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void fixedFixturesAreProducedByCurrentServerTypes() throws Exception {
        assertThat(firstDifference(
                mapper.valueToTree(page()), read(PAGE_FIXTURE), "$"))
                .isEmpty();
        assertThat(firstDifference(
                mapper.valueToTree(command()), read(COMMAND_FIXTURE), "$"))
                .isEmpty();
        assertThat(firstDifference(
                mapper.valueToTree(checkpoint()), read(CHECKPOINT_FIXTURE), "$"))
                .isEmpty();
    }

    private AuthoritativeOutcomeSourcePage page() {
        AuthoritativeOutcomeSourcePage addressed =
                AuthoritativeOutcomeSourceTestFixtures.livePage(mapper);
        return addressed.withSourceSeal(
                AuthoritativeOutcomeSourceTestFixtures.signedSeal(
                        addressed.pageFingerprint()));
    }

    private AuthoritativeOutcomeConnectorControlCommand command() {
        return AuthoritativeOutcomeSourceTestFixtures.backfill(mapper);
    }

    private AuthoritativeOutcomeSourceCheckpointRepository.Snapshot checkpoint() {
        var registration = AuthoritativeOutcomeSourceTestFixtures.liveRegistration();
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        return new AuthoritativeOutcomeSourceCheckpointRepository.Snapshot(
                AuthoritativeOutcomeSourceCheckpointRepository.SNAPSHOT_SCHEMA_VERSION,
                registration.key(), null, registration.baselinePageFingerprint(),
                registration.baselineCursorRef(), 0,
                registration.baselinePageFingerprint(), registration.baselineCursorRef(),
                null, Instant.EPOCH,
                AuthoritativeOutcomeSourceCheckpointRepository.Status.ACTIVE,
                "", 0, 0, now, 0, Instant.EPOCH, "", now, now);
    }

    private JsonNode read(String name) throws Exception {
        return mapper.readTree(Files.readString(path(name)));
    }

    private static void assertExact(JsonNode value, JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(fieldNames(schema.path("properties")));
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(textValues(schema.path("required")));
    }

    private static Path path(String name) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", name);
        return Files.exists(moduleRelative.getParent())
                ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", name);
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(JsonNode value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        value.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static String firstDifference(
            JsonNode expected, JsonNode actual, String path) {
        if (expected.getNodeType() != actual.getNodeType()) {
            return path + " type";
        }
        if (expected.isObject()) {
            java.util.Set<String> names = new java.util.TreeSet<>();
            expected.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!expected.has(name) || !actual.has(name)) {
                    return path + "." + name + " missing";
                }
                String difference = firstDifference(
                        expected.get(name), actual.get(name), path + "." + name);
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                return path + " size";
            }
            for (int index = 0; index < expected.size(); index++) {
                String difference = firstDifference(
                        expected.get(index), actual.get(index), path + "[" + index + "]");
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0
                    ? "" : path + " expected=" + expected + " actual=" + actual;
        }
        return expected.equals(actual) ? ""
                : path + " expected=" + expected + " actual=" + actual;
    }
}
