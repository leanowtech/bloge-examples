package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the public MCP tool catalog promised by the RG 1.4 design. */
class McpToolCatalogTest {

    @Test
    void exposesTheCompleteFiveStageCatalogWithHonestImpactLevels() {
        McpToolCatalog catalog = new McpToolCatalog();

        assertThat(catalog.all()).hasSize(26);
        assertThat(catalog.require("rg.capability.list").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.library.upsert").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.require("rg.oracle.propose").impact()).isEqualTo(McpToolImpact.PROPOSE);
        assertThat(catalog.require("rg.simulate").impact()).isEqualTo(McpToolImpact.EXECUTE);
        assertThat(catalog.require("rg.tool.publish").impact()).isEqualTo(McpToolImpact.GOVERNED_WRITE);
        assertThat(catalog.require("rg.fixture.provide").impact()).isEqualTo(McpToolImpact.GOVERNED_WRITE);
        assertThat(catalog.require("rg.resource.declare").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.all()).extracting(McpToolDefinition::name).doesNotHaveDuplicates();
    }

    @Test
    void publishesConcreteSchemasInsteadOfUnboundedPlaceholderObjects() {
        McpToolCatalog catalog = new McpToolCatalog();

        McpToolDefinition simulate = catalog.require("rg.simulate");
        assertThat(simulate.inputSchema()).containsEntry("type", "object");
        assertThat(simulate.inputSchema()).containsKey("properties");
        Map<?, ?> properties = (Map<?, ?>) simulate.inputSchema().get("properties");
        assertThat(properties.containsKey("toolRef")).isTrue();
        assertThat(properties.containsKey("libraryRefs")).isTrue();
        assertThat(properties.containsKey("cases")).isTrue();
        assertThat(simulate.outputSchema()).containsKey("properties");

        Map<?, ?> behaviorProperties = properties(catalog.require(
                "rg.scenario.setDependencyBehavior").inputSchema(), "behavior");
        assertThat(((Map<?, ?>) behaviorProperties.get("value")).containsKey("type"))
                .as("dependency values may be scalar, array, object or null")
                .isFalse();
        Map<?, ?> libraryData = properties(catalog.require("rg.library.upsert").outputSchema(), "data");
        Map<?, ?> functions = (Map<?, ?>) libraryData.get("functions");
        assertThat(((Map<?, ?>) functions.get("items")).get("type")).isEqualTo("object");
    }

    @Test
    void schemasDescribeStrictAuthoringAndEveryExecutionEnvelopeField() {
        McpToolCatalog catalog = new McpToolCatalog();

        Map<?, ?> instruction = properties(catalog.require("rg.tool.setInstruction").inputSchema(), "instruction");
        assertThat(stringKeys(instruction)).contains(
                "name", "title", "description", "whenToUse", "inputs", "outputs", "errors");
        Map<?, ?> instructionSchema = schemaProperty(
                catalog.require("rg.tool.setInstruction").inputSchema(), "instruction");
        assertThat(((List<?>) instructionSchema.get("required")).stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder(
                "name", "title", "description", "whenToUse", "inputs", "outputs", "errors");

        Map<?, ?> baseline = dataProperties(catalog.require("rg.tool.baseline"));
        assertThat(stringKeys(baseline)).contains(
                "status", "caseSetRef", "rounds", "businessFingerprintStable",
                "remainingLimitations", "evidenceRef", "honestVerdict");
        assertThat(((Map<?, ?>) baseline.get("rounds")).get("type")).isEqualTo("array");

        Map<?, ?> behavior = dataProperties(catalog.require("rg.scenario.setDependencyBehavior"));
        assertThat(((Map<?, ?>) behavior.get("behavior")).get("type")).isEqualTo("object");
        Map<?, ?> row = (Map<?, ?>) ((Map<?, ?>) dataProperties(
                catalog.require("rg.scenario.listCases")).get("rows")).get("items");
        assertThat(stringKeys((Map<?, ?>) row.get("properties"))).contains(
                "lifecycle", "qualityState", "sourceRunRef", "enumeration");
        Map<?, ?> enumeration = (Map<?, ?>) ((Map<?, ?>) row.get("properties")).get("enumeration");
        assertThat(stringKeys((Map<?, ?>) enumeration.get("properties"))).containsExactlyInAnyOrder(
                "enumerationMode", "enumerationRule", "boundaryInput", "reason");
        assertThat(stringKeys(dataProperties(catalog.require("rg.contract.get")))).contains(
                "bindingRef", "sourceKind", "runtimeState");
        Map<?, ?> provideInput = (Map<?, ?>) catalog.require("rg.fixture.provide")
                .inputSchema().get("properties");
        assertThat(stringKeys(provideInput)).containsExactlyInAnyOrder(
                "operatorRef", "outputPort", "sampleValue", "category", "retentionDays",
                "redactPaths", "idempotencyKey");
        assertThat(stringKeys(dataProperties(catalog.require("rg.fixture.provide")))).contains(
                "fixtureId", "scope", "schemaRef", "sourceKind", "lineageRef");
        Map<?, ?> declareInput = (Map<?, ?>) catalog.require("rg.resource.declare")
                .inputSchema().get("properties");
        assertThat(stringKeys(declareInput)).containsExactlyInAnyOrder(
                "resourceId", "method", "urlTemplate", "payloadSchema", "idempotencyKey");
        assertThat(stringKeys(dataProperties(catalog.require("rg.resource.declare")))).containsExactlyInAnyOrder(
                "resourceId", "registered", "host", "method", "contractId");
    }

    @Test
    void everyAdvertisedInputAndOutputSchemaIsValidAndCasesHaveOneExactSource() {
        McpToolCatalog catalog = new McpToolCatalog();

        catalog.all().forEach(definition -> {
            assertThat(VisualSchemaValidator.validateSchema(
                    definition.inputSchema(), "/input/" + definition.name()))
                    .as(definition.name() + " input schema").isEmpty();
            assertThat(VisualSchemaValidator.validateSchema(
                    definition.outputSchema(), "/output/" + definition.name()))
                    .as(definition.name() + " output schema").isEmpty();
        });
        Map<?, ?> cases = schemaProperty(catalog.require("rg.simulate").inputSchema(), "cases");
        assertThat(cases.keySet().stream().map(Object::toString).toList())
                .contains("oneOf").doesNotContain("properties", "required");
        assertThat((List<?>) cases.get("oneOf")).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> properties(Map<String, Object> schema, String property) {
        Map<String, Object> selected = (Map<String, Object>) schemaProperty(schema, property);
        return (Map<?, ?>) selected.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> schemaProperty(Map<String, Object> schema, String property) {
        Map<String, Object> values = (Map<String, Object>) schema.get("properties");
        return (Map<?, ?>) values.get(property);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> dataProperties(McpToolDefinition definition) {
        Map<String, Object> envelope = definition.outputSchema();
        Map<String, Object> envelopeProperties = (Map<String, Object>) envelope.get("properties");
        Map<String, Object> data = (Map<String, Object>) envelopeProperties.get("data");
        return (Map<?, ?>) data.get("properties");
    }

    private static List<String> stringKeys(Map<?, ?> values) {
        return values.keySet().stream().map(Object::toString).toList();
    }
}
