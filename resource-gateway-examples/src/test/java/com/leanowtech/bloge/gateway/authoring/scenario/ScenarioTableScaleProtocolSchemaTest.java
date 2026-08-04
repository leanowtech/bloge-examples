package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Freezes strict machine contracts for enterprise-scale Matrix reads and writes. */
class ScenarioTableScaleProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void schemasMatchRecordsAndKeepSourceClosureAndPayloadBoundariesExplicit() throws Exception {
        ScenarioTablePageQuery query = new ScenarioTablePageQuery(
                ScenarioTablePageQuery.SCHEMA_VERSION, 7,
                ScenarioValidationServiceTest.fingerprint('a'), "loan",
                List.of(ScenarioDraftSet.CaseType.BOUNDARY),
                ScenarioTablePageQuery.SortField.NAME,
                ScenarioTablePageQuery.SortDirection.ASC, "", 100);
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "case-a", "Case A", "", ScenarioDraftSet.CaseType.BOUNDARY,
                List.of(), ScenarioDraftSet.Given.empty(), List.of(), ScenarioDraftSet.Then.empty());
        ScenarioTablePage page = new ScenarioTablePage(
                "", "loan-scenarios", 7,
                ScenarioValidationServiceTest.fingerprint('a'),
                ScenarioValidationServiceTest.fingerprint('b'), 1,
                List.of(new ScenarioTablePage.Row(
                        0, ScenarioValidationServiceTest.fingerprint('c'), scenario)), "");
        ScenarioBulkEditCommand command = new ScenarioBulkEditCommand(
                ScenarioBulkEditCommand.SCHEMA_VERSION, "bulk-a", 7,
                ScenarioValidationServiceTest.fingerprint('a'),
                ScenarioBulkEditCommand.Atomicity.ALL_OR_NOTHING,
                List.of(new ScenarioBulkEditCommand.CellEdit(
                        "case-a", ScenarioValidationServiceTest.fingerprint('c'),
                        ScenarioBulkEditCommand.Field.NAME, "",
                        ScenarioBulkEditCommand.Operation.SET, "Renamed")));
        ScenarioBulkEditResult result = new ScenarioBulkEditResult(
                "", "bulk-a", "loan-scenarios", 7,
                ScenarioValidationServiceTest.fingerprint('a'), 8,
                ScenarioValidationServiceTest.fingerprint('d'), 1,
                List.of("case-a"), Instant.parse("2026-08-05T00:00:00Z"), "author-a");

        JsonNode querySchema = schema("bloge-scenario-table-page-query-v1.schema.json");
        JsonNode pageSchema = schema("bloge-scenario-table-page-v1.schema.json");
        JsonNode commandSchema = schema("bloge-scenario-bulk-edit-command-v1.schema.json");
        JsonNode resultSchema = schema("bloge-scenario-bulk-edit-result-v1.schema.json");

        assertProperties(mapper.valueToTree(query), querySchema.path("properties"));
        assertProperties(mapper.valueToTree(page), pageSchema.path("properties"));
        assertProperties(mapper.valueToTree(page.rows().getFirst()),
                pageSchema.at("/$defs/row/properties"));
        assertProperties(mapper.valueToTree(command), commandSchema.path("properties"));
        assertProperties(mapper.valueToTree(command.edits().getFirst()),
                commandSchema.at("/$defs/edit/properties"));
        assertProperties(mapper.valueToTree(result), resultSchema.path("properties"));

        assertThat(querySchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(pageSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(commandSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(resultSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(querySchema.at("/properties/limit/maximum").asInt()).isEqualTo(200);
        assertThat(pageSchema.at("/properties/rows/maxItems").asInt()).isEqualTo(200);
        assertThat(commandSchema.at("/properties/atomicity/const").asText())
                .isEqualTo("ALL_OR_NOTHING");
        assertThat(commandSchema.at("/$defs/edit/required"))
                .extracting(JsonNode::asText)
                .contains("expectedCaseFingerprint", "operation", "value");
        assertThat(resultSchema.path("properties").fieldNames())
                .toIterable()
                .doesNotContain("scenarios", "input", "output", "payload", "value");
        assertThat(schema("bloge-scenario-draft-set-v1.schema.json")
                .at("/properties/scenarios/maxItems").asInt()).isEqualTo(10_000);
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas", file)));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
