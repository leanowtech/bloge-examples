package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the public MCP tool catalog promised by the RG 1.4 design. */
class McpToolCatalogTest {
    private static final Pattern QUOTED_TOOL = Pattern.compile("[\\\"`]((?:rg\\.)[a-zA-Z0-9_.]+)[\\\"`]");

    @Test
    void exposesTheCompleteFiveStageCatalogWithHonestImpactLevels() {
        McpToolCatalog catalog = new McpToolCatalog();

        assertThat(catalog.all()).hasSize(50);
        assertThat(catalog.require("rg.capability.list").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.library.overview.get").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.capability.search").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.entity.list").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.entity.get").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.dsl.reference.get").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.library.upsert").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.require("rg.oracle.propose").impact()).isEqualTo(McpToolImpact.PROPOSE);
        assertThat(catalog.require("rg.simulate").impact()).isEqualTo(McpToolImpact.EXECUTE);
        assertThat(catalog.require("rg.tool.publish").impact()).isEqualTo(McpToolImpact.GOVERNED_WRITE);
        assertThat(catalog.require("rg.fixture.provide").impact()).isEqualTo(McpToolImpact.GOVERNED_WRITE);
        assertThat(catalog.require("rg.resource.declare").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.require("rg.feature.define").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.require("rg.feature.handoff").impact()).isEqualTo(McpToolImpact.PROPOSE);
        assertThat(catalog.require("rg.feature.evaluate").impact()).isEqualTo(McpToolImpact.EXECUTE);
        assertThat(catalog.require("rg.scenario.define").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.require("rg.instruction.define").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.require("rg.solution.compose").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.require("rg.solution.getContract").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.solution.invoke").impact())
                .isEqualTo(McpToolImpact.RUNTIME_EXECUTE);
        assertThat(catalog.require("rg.solution.invoke").impact().annotations())
                .containsEntry("destructiveHint", true)
                .containsEntry("idempotentHint", true)
                .containsEntry("openWorldHint", true);
        assertThat(catalog.require("rg.scenario.test").impact()).isEqualTo(McpToolImpact.EXECUTE);
        assertThat(catalog.require("rg.solution.baseline").impact()).isEqualTo(McpToolImpact.EXECUTE);
        assertThat(catalog.require("rg.solution.commit").impact()).isEqualTo(McpToolImpact.PROPOSE);
        assertThat(catalog.require("rg.engineering.handoff").impact()).isEqualTo(McpToolImpact.PROPOSE);
        assertThat(catalog.require("rg.solution.readiness").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.solution.performance").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.solution.publish").impact()).isEqualTo(McpToolImpact.GOVERNED_WRITE);
        assertThat(catalog.all()).extracting(McpToolDefinition::name).doesNotHaveDuplicates();
        assertThat(catalog.all()).extracting(definition -> definition.impact().operation())
                .doesNotContain(IntegrationOperation.AGENT_TDD_ATTEST,
                        IntegrationOperation.AGENT_TDD_WRITE_EXEC,
                        IntegrationOperation.AGENT_TDD_FEATURE_ENG);
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

        McpToolDefinition invoke = catalog.require("rg.solution.invoke");
        assertThat(((List<?>) invoke.inputSchema().get("required")).stream()
                .map(Object::toString).toList())
                .contains("solutionRef", "inputs", "idempotencyKey");
        assertThat(stringKeys(properties(invoke.outputSchema(), "data")))
                .contains("result", "reasoning", "publicationId",
                        "implementationFingerprint", "executionStatus");
        McpToolDefinition overview = catalog.require("rg.library.overview.get");
        assertThat(stringKeys((Map<?, ?>) overview.inputSchema().get("properties")))
                .containsExactly("includeSamples");
        assertThat(stringKeys(dataProperties(overview)))
                .containsExactlyInAnyOrder(
                        "buildingBlocks", "worldModel", "samples", "snapshotFingerprint");
        assertThat(stringKeys(dataProperties(catalog.require("rg.entity.list"))))
                .containsExactlyInAnyOrder("entities", "nextCursor", "snapshotFingerprint");
        assertThat(stringKeys(dataProperties(catalog.require("rg.entity.get"))))
                .doesNotContain("bindingRef", "dsl", "loweredDraft", "urlTemplate");
        assertThat(stringKeys(dataProperties(catalog.require("rg.feature.handoff"))))
                .containsExactlyInAnyOrder("ticketId", "featureName", "requiredOutput",
                        "requiredInputs", "evaluationKind", "businessSemantics", "status",
                        "acceptanceRef", "revision");
        assertThat(stringKeys(dataProperties(catalog.require("rg.instruction.define"))))
                .contains("instructionId", "businessSemantics", "effect");
        assertThat(stringKeys(dataProperties(catalog.require("rg.solution.performance"))))
                .contains("signalFingerprint", "totalInvocations", "policyGaps");
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
                "remainingLimitations", "evidenceRef", "honestVerdict", "attestation");
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

    @Test
    void catalogCoversDispatcherCodexConfigurationRunbookAndCertificationScript() throws IOException {
        McpToolCatalog catalog = new McpToolCatalog();
        Set<String> names = catalog.all().stream()
                .map(McpToolDefinition::name)
                .collect(Collectors.toUnmodifiableSet());
        Path root = repositoryRoot();

        Set<String> configured = quotedToolNames(Files.readString(root.resolve(".codex/config.toml")));
        Set<String> dispatched = quotedToolNames(Files.readString(root.resolve(
                "resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/agenttdd/"
                        + "ResourceGatewayAgentTddTools.java")));
        Set<String> scripted = quotedToolNames(Files.readString(root.resolve(
                "scripts/certify-agent-tdd-codex.sh")));

        assertThat(names).containsAll(configured);
        assertThat(dispatched).containsAll(names);
        assertThat(scripted).allSatisfy(value -> assertThat(value)
                .matches("rg\\.agentTddCertificationInstance\\.v1|" +
                        names.stream().map(Pattern::quote).collect(Collectors.joining("|"))));
        assertThat(Files.readString(root.resolve("docs/resource-gateway-agent-tdd-mcp.md")))
                .contains("\"rg.library.overview.get\"");
    }

    private static Set<String> quotedToolNames(String source) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = QUOTED_TOOL.matcher(source);
        while (matcher.find()) names.add(matcher.group(1));
        return Set.copyOf(names);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve(".codex/config.toml"))) return current;
        Path parent = current.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve(".codex/config.toml"))) return parent;
        throw new IllegalStateException("Repository root with .codex/config.toml was not found");
    }

    @Test
    void publishesAStrictPayloadFreeDslReferenceContract() {
        McpToolDefinition reference = new McpToolCatalog().require("rg.dsl.reference.get");

        assertThat(stringKeys((Map<?, ?>) reference.inputSchema().get("properties")))
                .containsExactlyInAnyOrder("libraryRefs", "topics", "operatorRefs", "includeExamples");
        assertThat(((List<?>) reference.inputSchema().get("required")).stream().map(Object::toString).toList())
                .containsExactly("libraryRefs");

        Map<?, ?> data = dataProperties(reference);
        assertThat(stringKeys(data)).containsExactlyInAnyOrder(
                "schemaVersion", "languageVersion", "compilerProfile", "supportedRootKinds",
                "referenceVersion", "authoringContextFingerprint", "topics", "operators",
                "functions", "examples", "limits");
        Map<?, ?> operator = (Map<?, ?>) ((Map<?, ?>) data.get("operators")).get("items");
        assertThat(stringKeys((Map<?, ?>) operator.get("properties"))).containsExactlyInAnyOrder(
                "operatorRef", "archetype", "effect", "inputs", "outputs", "configSchema",
                "contractFingerprint", "bindingState");
        assertThat(stringKeys((Map<?, ?>) operator.get("properties")))
                .doesNotContain("urlTemplate", "description", "examples", "diagnostics", "lowering");
        Map<?, ?> example = (Map<?, ?>) ((Map<?, ?>) data.get("examples")).get("items");
        assertThat(stringKeys((Map<?, ?>) example.get("properties"))).containsExactlyInAnyOrder(
                "exampleId", "intent", "source", "assertions", "exampleFingerprint");
        assertThat(((List<?>) example.get("required")).stream().map(Object::toString).toList())
                .contains("exampleFingerprint");
    }

    @Test
    void requiresContextAndReceiptAndAdvertisesOnlySafeAuthoringDiagnostics() {
        McpToolCatalog catalog = new McpToolCatalog();
        McpToolDefinition preview = catalog.require("rg.dsl.preview");
        McpToolDefinition compose = catalog.require("rg.tool.compose");

        assertThat(((List<?>) preview.inputSchema().get("required")).stream().map(Object::toString).toList())
                .contains("source", "libraryRefs", "authoringContextFingerprint");
        assertThat(((List<?>) compose.inputSchema().get("required")).stream().map(Object::toString).toList())
                .contains("graph", "libraryRefs", "authoringContextFingerprint",
                        "authoringReceiptFingerprint");
        Map<?, ?> previewData = dataProperties(preview);
        assertThat(stringKeys(previewData)).contains(
                "authoringContext", "stages", "technicalAcceptance", "projection", "roundTrip",
                "authoringDiagnostics", "diagnosticSummary", "nextAction",
                "authoringReceiptFingerprint");
        Map<?, ?> diagnostic = (Map<?, ?>) ((Map<?, ?>) previewData.get("authoringDiagnostics")).get("items");
        assertThat(stringKeys((Map<?, ?>) diagnostic.get("properties"))).containsExactlyInAnyOrder(
                "level", "phase", "code", "target", "span", "safeSummary", "expectedKinds",
                "referenceRefs", "fixHints", "resolutionClass", "blocking", "retryable",
                "diagnosticFingerprint");
        assertThat(stringKeys((Map<?, ?>) diagnostic.get("properties")))
                .doesNotContain("message", "metadata", "source", "generatedDsl");
        Map<?, ?> projection = (Map<?, ?>) previewData.get("projection");
        assertThat(projection.get("additionalProperties")).isEqualTo(false);
    }

    @Test
    void publishesStrictFourEntityAuthoringSchemas() {
        McpToolCatalog catalog = new McpToolCatalog();

        assertThat(stringKeys((Map<?, ?>) catalog.require("rg.feature.define")
                .inputSchema().get("properties")))
                .containsExactlyInAnyOrder("journeyRef", "expectedJourneyRevision",
                        "featureYaml", "idempotencyKey");
        assertThat(stringKeys((Map<?, ?>) catalog.require("rg.scenario.define")
                .inputSchema().get("properties")))
                .containsExactlyInAnyOrder("journeyRef", "expectedJourneyRevision",
                        "scenarioYaml", "libraryRefs", "idempotencyKey");
        assertThat(stringKeys(dataProperties(catalog.require("rg.instruction.define"))))
                .contains("instructionId", "effect", "reasoningRequired", "writeGovernance",
                        "speccing", "contractFingerprint", "honestVerdict");
        assertThat(stringKeys(dataProperties(catalog.require("rg.solution.compose"))))
                .contains("solutionRef", "inputContract", "scenarioTreeValid",
                        "pureFunctionProjection", "precompiled", "graphNodeCount",
                        "authoringContextFingerprint");
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
