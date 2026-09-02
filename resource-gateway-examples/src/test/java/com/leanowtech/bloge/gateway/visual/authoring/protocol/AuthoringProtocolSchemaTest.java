package com.leanowtech.bloge.gateway.visual.authoring.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceSaveCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCheckCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCheckResult;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.DefaultFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommandV2;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreview;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRequest;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRun;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ExactFixtureSubjectRefV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationCommandV2;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImporter;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemDetail;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** Contract tests for every frozen authoring wire schema and its golden examples. */
class AuthoringProtocolSchemaTest {

    private static final Path SCHEMA_ROOT = Path.of("..", "docs", "schemas", "resource-gateway-authoring");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> FAMILIES = Map.ofEntries(
            Map.entry("connection", "connection-command-v1.schema.json"),
            Map.entry("check-command", "connection-check-command-v1.schema.json"),
            Map.entry("check-result", "connection-check-result-v1.schema.json"),
            Map.entry("api-resource", "api-resource-command-v1.schema.json"),
            Map.entry("api-resource-receipt", "api-resource-receipt-v1.schema.json"),
            Map.entry("api-resource-spec", "api-resource-spec-v1.schema.json"),
            Map.entry("connection-view", "connection-view-v1.schema.json"),
            Map.entry("openapi", "openapi-preview-v1.schema.json"),
            Map.entry("openapi-preview-command", "openapi-preview-command-v1.schema.json"),
            Map.entry("openapi-preview-view", "openapi-preview-view-v1.schema.json"),
            Map.entry("reusable-flow", "reusable-flow-command-v1.schema.json"),
            Map.entry("reusable-flow-draft", "reusable-flow-draft-v1.schema.json"),
            Map.entry("reusable-flow-receipt", "reusable-flow-receipt-v1.schema.json"),
            Map.entry("reusable-flow-version", "reusable-flow-version-v1.schema.json"),
            Map.entry("reusable-flow-publish-command", "reusable-flow-publish-command-v1.schema.json"),
            Map.entry("reusable-flow-publish-receipt", "reusable-flow-publish-receipt-v1.schema.json"),
            Map.entry("fixture-set", "fixture-set-command-v1.schema.json"),
            Map.entry("fixture-set-v2", "fixture-set-command-v2.schema.json"),
            Map.entry("fixture-set-receipt", "fixture-set-receipt-v1.schema.json"),
            Map.entry("fixture-set-summary-collection", "fixture-set-summary-collection-v1.schema.json"),
            Map.entry("fixture-share-command", "fixture-share-command-v1.schema.json"),
            Map.entry("fixture-share-receipt", "fixture-share-receipt-v1.schema.json"),
            Map.entry("fixture-review-command", "fixture-review-command-v1.schema.json"),
            Map.entry("fixture-review-receipt", "fixture-review-receipt-v1.schema.json"),
            Map.entry("fixture-summary", "fixture-set-summary-v1.schema.json"),
            Map.entry("fixture-view", "fixture-set-view-v1.schema.json"),
            Map.entry("legacy-migration-inventory", "legacy-migration-inventory-v1.schema.json"),
            Map.entry("legacy-migration-assessment", "legacy-migration-assessment-v1.schema.json"),
            Map.entry("legacy-fixture-preview", "legacy-fixture-preview-v1.schema.json"),
            Map.entry("legacy-flow-preview", "legacy-flow-preview-v1.schema.json"),
            Map.entry("legacy-resource-preview", "legacy-resource-preview-v1.schema.json"),
            Map.entry("simulation-request", "simulation-request-v1.schema.json"),
            Map.entry("simulation-command-v2", "simulation-command-v2.schema.json"),
            Map.entry("simulation-run", "simulation-run-v1.schema.json"),
            Map.entry("problem-detail", "problem-detail-v1.schema.json"));

    @Test
    void callerDirectedCommandsRoundTripAgainstFrozenV2Schemas() throws Exception {
        String fingerprint = "sha256:" + "a".repeat(64);
        var fixtureReference = new SimulationCommandV2.ExactFixtureSetRef(
                "lookup-fixtures", 4, "sha256:" + "b".repeat(64));
        var function = new ExactFixtureSubjectRefV2.BuiltinFunctionVersion(
                "bloge-builtins", 7, "lookup", "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64));
        var simulation = new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION, function,
                new SimulationCommandV2.Input.CaseInput(fixtureReference, "known-customer"),
                new SimulationCommandV2.FixturePlan.Bindings(SimulationCommandV2.Unmatched.BLOCK,
                        List.of(new SimulationCommandV2.FixtureBinding(
                                new SimulationCommandV2.FixtureTarget.CallSite(
                                        List.of("risk-tool"), "lookup-customer"),
                                new SimulationCommandV2.FixtureSelection.AutoMatch(fixtureReference)))),
                SimulationCommandV2.ExecutionPolicy.denyAll());
        Path simulationSchema = SCHEMA_ROOT.resolve("simulation-command-v2.schema.json");
        JsonNode simulationWire = MAPPER.valueToTree(simulation);
        assertThat(validationErrors(read(simulationSchema), simulationWire, simulationSchema)).isEmpty();
        assertThat(MAPPER.treeToValue(simulationWire, SimulationCommandV2.class)).isEqualTo(simulation);
        assertThat(simulation.toString()).doesNotContain("known-customer", "lookup-customer");

        ObjectNode driverInput = MAPPER.createObjectNode().put("customerLevel", "VIP");
        var fixture = new FixtureSetCommandV2(FixtureSetCommandV2.SCHEMA_VERSION,
                "Risk operator fixtures", new ExactFixtureSubjectRefV2.OperatorVersion(
                "risk-library", 12, "risk.score", fingerprint),
                List.of(new FixtureSetCommandV2.Case("vip", "VIP", driverInput,
                        new FixtureSetCommand.Condition("vip", List.of(
                                new FixtureSetCommand.Predicate.Eq(
                                        "$.customerLevel", MAPPER.getNodeFactory().textNode("VIP")))),
                        List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                                        MAPPER.createObjectNode().put("decision", "APPROVE"))),
                                FixtureSetCommand.Fidelity.OUTPUT_LEVEL)),
                        new FixtureSetCommand.Expect(
                                MAPPER.createObjectNode().put("decision", "APPROVE")))));
        Path fixtureSchema = SCHEMA_ROOT.resolve("fixture-set-command-v2.schema.json");
        JsonNode fixtureWire = MAPPER.valueToTree(fixture);
        assertThat(validationErrors(read(fixtureSchema), fixtureWire, fixtureSchema)).isEmpty();
        assertThat(MAPPER.treeToValue(fixtureWire, FixtureSetCommandV2.class)).isEqualTo(fixture);
        driverInput.put("credential", "must-not-leak");
        assertThat(fixture.cases().getFirst().driverInput().has("credential")).isFalse();
        assertThat(fixture.toString()).doesNotContain("VIP", "APPROVE", "must-not-leak");
    }

    @Test
    void openApiPreviewCommandAndGeneratedViewRoundTripAgainstFrozenSchemas() throws Exception {
        OpenApiPreviewCommand command = new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Inline("""
                        openapi: 3.0.3
                        info: { title: Profile, version: 1.0.0 }
                        paths:
                          /profile:
                            get:
                              operationId: getProfile
                              responses:
                                '200':
                                  description: Profile
                                  content:
                                    application/json:
                                      schema:
                                        type: object
                                        properties: { name: { type: string } }
                                        required: [name]
                        """),
                List.of("getProfile"));
        Path commandPath = SCHEMA_ROOT.resolve("openapi-preview-command-v1.schema.json");
        JsonNode commandWire = MAPPER.valueToTree(command);
        assertThat(validationErrors(read(commandPath), commandWire, commandPath)).isEmpty();
        assertThat(MAPPER.treeToValue(commandWire, OpenApiPreviewCommand.class).source())
                .isInstanceOf(OpenApiPreviewCommand.Inline.class);

        OpenApiPreview preview = new OpenApiPreviewModule(
                new OpenApiResourceDesignContractImporter(), new JsonSchemaSampleGenerator(),
                MAPPER, new ApiResourceDecisions(MAPPER)).preview(command);
        Path viewPath = SCHEMA_ROOT.resolve("openapi-preview-view-v1.schema.json");
        JsonNode viewWire = MAPPER.valueToTree(preview);
        assertThat(validationErrors(read(viewPath), viewWire, viewPath)).isEmpty();
        assertThat(MAPPER.treeToValue(viewWire, OpenApiPreview.class)).isEqualTo(preview);
    }

    @Test
    void allAuthoringSchemasAreDraft202012StrictAndReferencesResolve() throws Exception {
        List<Path> schemas = Files.list(SCHEMA_ROOT)
                .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                .sorted()
                .toList();
        assertThat(schemas).isNotEmpty();
        assertThat(FAMILIES.values())
                .as("every top-level wire schema has an explicit golden family")
                .containsExactlyInAnyOrderElementsOf(schemas.stream()
                        .map(path -> path.getFileName().toString())
                        .filter(name -> !name.equals("common-v1.schema.json"))
                        .toList());
        for (Path schemaPath : schemas) {
            JsonNode schema = read(schemaPath);
            assertThat(schema.path("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(schema.path("$id").asText()).isNotBlank();
            assertStrictObjects(schema, schemaPath + " root");
            assertReferencesResolve(schema, schemaPath);
        }
    }

    @Test
    void goldenExamplesCoverMinimalCompleteAndKeyInvalidSemantics() throws Exception {
        for (Map.Entry<String, String> family : FAMILIES.entrySet()) {
            Path schemaPath = SCHEMA_ROOT.resolve(family.getValue());
            JsonNode schema = read(schemaPath);
            Path examples = SCHEMA_ROOT.resolve("examples");
            List<Path> familyExamples = Files.list(examples)
                    .filter(path -> isGoldenForFamily(path, family.getKey()))
                    .sorted()
                    .toList();
            assertThat(familyExamples).as("goldens for %s", family.getKey()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(familyExamples.stream().anyMatch(path -> path.getFileName().toString().contains("minimal"))).as("minimal %s", family.getKey()).isTrue();
            assertThat(familyExamples.stream().anyMatch(path -> path.getFileName().toString().contains("complete"))).as("complete %s", family.getKey()).isTrue();
            assertThat(familyExamples.stream().anyMatch(path -> path.getFileName().toString().contains("invalid"))).as("invalid %s", family.getKey()).isTrue();
            for (Path examplePath : familyExamples) {
                boolean expectedValid = !examplePath.getFileName().toString().contains("invalid");
                List<String> errors = new ArrayList<>();
                validate(schema, read(examplePath), schemaPath, "", errors);
                if (expectedValid) {
                    assertThat(errors).as(examplePath.toString()).isEmpty();
                } else {
                    assertThat(errors).as(examplePath.toString()).isNotEmpty();
                }
            }
        }
    }

    private static boolean isGoldenForFamily(Path path, String family) {
        String name = path.getFileName().toString();
        String prefix = family + "-";
        if (!name.startsWith(prefix)) {
            return false;
        }
        String qualifier = name.substring(prefix.length());
        return qualifier.startsWith("minimal")
                || qualifier.startsWith("complete")
                || qualifier.startsWith("invalid");
    }

    @Test
    void exactCoordinatesAndSecurityBoundariesAreEncoded() throws Exception {
        JsonNode common = read(SCHEMA_ROOT.resolve("common-v1.schema.json"));
        assertThat(common.at("/$defs/fingerprint/pattern").asText()).isEqualTo("^sha256:[0-9a-f]{64}$");
        assertThat(common.at("/$defs/revision/minimum").asInt()).isEqualTo(1);

        JsonNode connectionView = read(SCHEMA_ROOT.resolve("connection-view-v1.schema.json"));
        assertThat(connectionView.at("/$defs/secretWrite").isMissingNode()).isTrue();
        assertThat(connectionView.at("/properties/auth").toString())
                .doesNotContain("token", "password", "value", "ref", "headerName");
        JsonNode simulation = read(SCHEMA_ROOT.resolve("simulation-request-v1.schema.json"));
        assertThat(simulation.at("/properties/source/oneOf").size()).isEqualTo(2);
        assertThat(simulation.at("/$defs/policy/properties/externalWrites/properties/kind/const").asText())
                .isEqualTo("DENY");
        JsonNode summary = read(SCHEMA_ROOT.resolve("fixture-set-summary-v1.schema.json"));
        assertThat(summary.at("/properties/subject/$ref").asText())
                .isEqualTo("common-v1.schema.json#/$defs/exactSubjectRef");
        assertThat(summary.toString()).doesNotContain("input", "material", "fixtureAssetId", "replayId", "credential");
    }

    @Test
    void connectionCheckKindsAndPayloadFreeEvidenceRoundTripAgainstSchemas() throws Exception {
        Path commandPath = SCHEMA_ROOT.resolve("connection-check-command-v1.schema.json");
        Path resultPath = SCHEMA_ROOT.resolve("connection-check-result-v1.schema.json");
        ApiConnectionCheckCommand network = ApiConnectionCheckCommand.networkOnly();
        ApiConnectionCheckCommand safeRead = ApiConnectionCheckCommand.safeRead(
                new ApiResourceSpec.ResourceRef("API_RESOURCE", "customer.get-profile", 3,
                        "sha256:" + "a".repeat(64)),
                MAPPER.createObjectNode().put("customerId", "customer-1001"), "Verify read-only access");
        for (ApiConnectionCheckCommand command : List.of(network, safeRead)) {
            JsonNode wire = MAPPER.valueToTree(command);
            assertThat(validationErrors(read(commandPath), wire, commandPath)).as(wire.toString()).isEmpty();
            assertThat(MAPPER.treeToValue(wire, ApiConnectionCheckCommand.class).kind())
                    .isEqualTo(command.kind());
        }
        assertThat(safeRead.toString()).doesNotContain("customer-1001");

        ApiConnectionCheckResult result = new ApiConnectionCheckResult(
                ApiConnectionCheckResult.SCHEMA_VERSION, "customer-api", 3, "NETWORK_ONLY",
                ApiConnectionCheckResult.Status.REACHABLE, java.time.Instant.parse("2026-08-31T08:00:00Z"), 8,
                List.of(new ApiConnectionCheckResult.Stage("EGRESS_POLICY", "PASSED", "ALLOWLIST_MATCH"),
                        new ApiConnectionCheckResult.Stage("DNS", "PASSED", "RESOLVED"),
                        new ApiConnectionCheckResult.Stage("TLS", "PASSED", "HANDSHAKE_OK"),
                        new ApiConnectionCheckResult.Stage("CONNECT", "PASSED", "CONNECTED")),
                new ApiConnectionCheckResult.Audit("decision-01", "sha256:" + "b".repeat(64)));
        ObjectMapper temporalMapper = MAPPER.copy().findAndRegisterModules();
        JsonNode wire = temporalMapper.valueToTree(result);
        assertThat(validationErrors(read(resultPath), wire, resultPath)).as(wire.toString()).isEmpty();
        assertThat(wire.toString()).doesNotContain("payload", "token", "password", "secret");
        assertThat(temporalMapper.treeToValue(wire, ApiConnectionCheckResult.class)).isEqualTo(result);
        assertThatThrownBy(() -> new ApiConnectionCheckResult(
                ApiConnectionCheckResult.SCHEMA_VERSION, "customer-api", 3, "NETWORK_ONLY",
                ApiConnectionCheckResult.Status.REACHABLE, java.time.Instant.parse("2026-08-31T08:00:00Z"), 8,
                List.of(new ApiConnectionCheckResult.Stage(
                        "EGRESS_POLICY", "BLOCKED", "DESTINATION_NOT_ALLOWED")),
                new ApiConnectionCheckResult.Audit("decision-02", "sha256:" + "c".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretReferencesAndSensitiveHeadersAreValidatedCaseInsensitively() throws Exception {
        Path commonPath = SCHEMA_ROOT.resolve("common-v1.schema.json");
        JsonNode common = read(commonPath);
        JsonNode headers = common.at("/$defs/safeHeaderMap");
        assertThat(validationErrors(headers, MAPPER.createObjectNode().put("Accept", "application/json"), commonPath))
                .as("ordinary headers remain legal").isEmpty();
        for (String name : List.of("Authorization", "authorization", "COOKIE", "proxy-authorization", "proxy-authenticate",
                "set-cookie", "Host", "content-length", "Connection", "keep-alive", "TE", "Trailer",
                "transfer-encoding", "Upgrade", "Forwarded", "X-Forwarded-", "X-Forwarded-For", "x-forwarded-host")) {
            assertThat(validationErrors(headers, MAPPER.createObjectNode().put(name, "blocked"), commonPath))
                .as("sensitive header %s is blocked", name).isNotEmpty();
        }

        JsonNode connection = read(SCHEMA_ROOT.resolve("connection-command-v1.schema.json"));
        JsonNode apiResource = read(SCHEMA_ROOT.resolve("api-resource-command-v1.schema.json"));
        for (String name : List.of("authorization", "HOST", "x-forwarded-for")) {
            ObjectNode apiKey = MAPPER.createObjectNode()
                    .put("schemaVersion", "bloge.apiConnectionCommand.v1")
                    .put("displayName", "Orders")
                    .put("baseUrl", "https://orders.example.com");
            apiKey.set("auth", MAPPER.createObjectNode().put("kind", "API_KEY").put("headerName", name)
                    .set("value", MAPPER.createObjectNode().put("mode", "SECRET_REF").put("ref", "vault://orders/key")));
            assertThat(validationErrors(connection, apiKey, SCHEMA_ROOT.resolve("connection-command-v1.schema.json")))
                    .as("API key header %s is blocked", name).isNotEmpty();

            ObjectNode binding = MAPPER.createObjectNode()
                    .put("from", "$.token")
                    .set("to", MAPPER.createObjectNode().put("location", "HEADER").put("name", name));
            assertThat(validationErrors(apiResource.at("/$defs/operation/properties/bindings/items"), binding,
                    SCHEMA_ROOT.resolve("api-resource-command-v1.schema.json")))
                    .as("API resource header binding %s is blocked", name).isNotEmpty();
        }

        JsonNode secretRef = common.at("/$defs/secretRef");
        for (String value : List.of("vault://team/orders/key", "vault://tenant_1/api-key")) {
            assertThat(validationErrors(secretRef, MAPPER.valueToTree(value), commonPath))
                    .as("secret ref %s", value).isEmpty();
        }
        for (String value : List.of("", "   ", "orders-key")) {
            assertThat(validationErrors(secretRef, MAPPER.valueToTree(value), commonPath))
                    .as("unsafe secret ref %s", value).isNotEmpty();
        }
    }

    @Test
    void apiResourcePolymorphicKindsRoundTripAndValidateAgainstWireSchema() throws Exception {
        Path schemaPath = SCHEMA_ROOT.resolve("api-resource-command-v1.schema.json");
        JsonNode resourceSchema = read(schemaPath).at("/$defs/resourceCommand");
        List<ApiResourceCommand.Success> successes = List.of(
                new ApiResourceCommand.HttpStatus(List.of(200)),
                new ApiResourceCommand.BodyMatch("$.status", List.of(MAPPER.valueToTree("CREATED"))));
        List<ApiResourceCommand.Effect> effects = List.of(
                ApiResourceCommand.Effect.readOnly(),
                ApiResourceCommand.Effect.fixtureOnlyWrite(),
                new ApiResourceCommand.Effect.ManagedWrite("X-Request-Id",
                        new ApiResourceCommand.Effect.Receipt("$.id", "$.status",
                                List.of(MAPPER.valueToTree("SUCCEEDED")), List.of(MAPPER.valueToTree("FAILED"))),
                        new ApiResourceCommand.Effect.Reconciliation(
                                new ApiResourceSpec.ResourceRef("API_RESOURCE", "orders.lookup", 3,
                                        "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"),
                                "$.receiptId")));
        for (ApiResourceCommand.Success success : successes) {
            for (ApiResourceCommand.Effect effect : effects) {
                String method = effect instanceof ApiResourceCommand.Effect.ReadOnly ? "GET" : "POST";
                ApiResourceCommand command = command(method, success, effect);
                JsonNode wire = MAPPER.valueToTree(command);
                assertThat(wire.at("/response/success/kind").asText()).isEqualTo(
                        success instanceof ApiResourceCommand.HttpStatus ? "HTTP_STATUS" : "BODY_MATCH");
                assertThat(wire.at("/effect/kind").asText()).isEqualTo(effectKind(effect));
                assertThat(validationErrors(resourceSchema, wire, schemaPath)).as(wire.toString()).isEmpty();
                ApiResourceCommand roundTrip = MAPPER.treeToValue(wire, ApiResourceCommand.class);
                assertThat(roundTrip.response().success()).isEqualTo(success);
                assertThat(roundTrip.effect()).isEqualTo(effect);
            }
        }
    }

    @Test
    void reusableFlowKindsAndMappingsRoundTripAgainstFrozenWireSchema() throws Exception {
        String resourceFingerprint = "sha256:" + "a".repeat(64);
        String flowFingerprint = "sha256:" + "b".repeat(64);
        SchemaEnvelope flowInput = SchemaEnvelope.object(Map.of(
                "customerId", Map.of("type", "string")), List.of("customerId"));
        SchemaEnvelope decisionOutput = SchemaEnvelope.object(Map.of(
                "eligible", Map.of("type", "boolean")), List.of("eligible"));
        ReusableFlowCommand command = new ReusableFlowCommand(ReusableFlowCommand.SCHEMA_VERSION,
                new ReusableFlowCommand.Flow("Eligibility solution", ReusableFlowCommand.Kind.SOLUTION,
                        "Compose a profile Resource and a published decision Flow.",
                        new ReusableFlowCommand.Contract(flowInput, decisionOutput),
                        new ReusableFlowCommand.Graph(List.of(
                                new ReusableFlowCommand.Node("profile", "Customer profile",
                                        new ReusableFlowCommand.ComposableRef.ApiResource(
                                                "customer.profile", 3, resourceFingerprint),
                                        List.of(
                                                new ReusableFlowCommand.Input("$.customerId",
                                                        new ReusableFlowCommand.MappingSource.FlowInput(
                                                                "$.customerId")),
                                                new ReusableFlowCommand.Input("$.region",
                                                        new ReusableFlowCommand.MappingSource.Constant(
                                                                MAPPER.valueToTree("APAC"))))),
                                new ReusableFlowCommand.Node("decision", "Eligibility decision",
                                        new ReusableFlowCommand.ComposableRef.FlowVersion(
                                                "eligibility-v2", 2, flowFingerprint),
                                        List.of(new ReusableFlowCommand.Input("$.profile",
                                                new ReusableFlowCommand.MappingSource.NodeOutput(
                                                        "profile", "$"))))),
                                new ReusableFlowCommand.Output("decision", "$")),
                        new ReusableFlowCommand.Layout(Map.of(
                                "profile", new ReusableFlowCommand.Position(80, 120),
                                "decision", new ReusableFlowCommand.Position(380, 120)))));

        Path schemaPath = SCHEMA_ROOT.resolve("reusable-flow-command-v1.schema.json");
        JsonNode wire = MAPPER.valueToTree(command);
        assertThat(validationErrors(read(schemaPath), wire, schemaPath)).as(wire.toString()).isEmpty();
        assertThat(wire.at("/flow/graph/nodes/0/use/kind").asText()).isEqualTo("API_RESOURCE");
        assertThat(wire.at("/flow/graph/nodes/1/use/kind").asText()).isEqualTo("FLOW_VERSION");
        assertThat(wire.at("/flow/graph/nodes/0/inputs/0/from/kind").asText()).isEqualTo("FLOW_INPUT");
        assertThat(wire.at("/flow/graph/nodes/0/inputs/1/from/kind").asText()).isEqualTo("CONSTANT");
        assertThat(wire.at("/flow/graph/nodes/1/inputs/0/from/kind").asText()).isEqualTo("NODE_OUTPUT");
        assertThat(MAPPER.treeToValue(wire, ReusableFlowCommand.class)).isEqualTo(command);

        flowInput.schema().put("type", "string");
        assertThat(command.flow().contract().input().schema()).containsEntry("type", "object");

        ReusableFlowDraft draft = new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION,
                "eligibility", "draft-eligibility", 3, flowFingerprint,
                command.flow().displayName(), command.flow().kind(), command.flow().description(),
                command.flow().contract(), command.flow().graph(), command.flow().layout(),
                ReusableFlowDraft.Status.DRAFT);
        Path draftSchema = SCHEMA_ROOT.resolve("reusable-flow-draft-v1.schema.json");
        JsonNode draftWire = MAPPER.valueToTree(draft);
        assertThat(validationErrors(read(draftSchema), draftWire, draftSchema)).isEmpty();
        assertThat(MAPPER.treeToValue(draftWire, ReusableFlowDraft.class)).isEqualTo(draft);

        ReusableFlowSaveReceipt receipt = new ReusableFlowSaveReceipt(
                ReusableFlowSaveReceipt.SCHEMA_VERSION, draft.flowId(), draft.subject(),
                ReusableFlowSaveReceipt.Validation.VALID);
        Path receiptSchema = SCHEMA_ROOT.resolve("reusable-flow-receipt-v1.schema.json");
        JsonNode receiptWire = MAPPER.valueToTree(receipt);
        assertThat(validationErrors(read(receiptSchema), receiptWire, receiptSchema)).isEmpty();
        assertThat(MAPPER.treeToValue(receiptWire, ReusableFlowSaveReceipt.class)).isEqualTo(receipt);

        ReusableFlowPublishCommand publishCommand = new ReusableFlowPublishCommand(null, draft.subject());
        ReusableFlowVersion version = new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION,
                "publication-eligibility", 1, "sha256:" + "c".repeat(64),
                new ReusableFlowVersion.Source(draft.draftId(), draft.revision(), draft.fingerprint()),
                draft.flowId(), draft.displayName(), draft.kind(), draft.description(), draft.contract(),
                draft.graph(), Instant.parse("2026-09-01T00:00:00Z"), "alice",
                ReusableFlowVersion.Status.PUBLISHED);
        ReusableFlowPublishReceipt publishReceipt = new ReusableFlowPublishReceipt(null,
                draft.subject(), version.subject(), ReusableFlowPublishReceipt.Catalog.AVAILABLE);
        ObjectMapper temporal = MAPPER.copy().findAndRegisterModules();
        for (Object value : List.of(publishCommand, publishReceipt, version)) {
            String schemaName = value instanceof ReusableFlowPublishCommand
                    ? "reusable-flow-publish-command-v1.schema.json"
                    : value instanceof ReusableFlowPublishReceipt
                    ? "reusable-flow-publish-receipt-v1.schema.json"
                    : "reusable-flow-version-v1.schema.json";
            Path path = SCHEMA_ROOT.resolve(schemaName);
            JsonNode valueWire = temporal.valueToTree(value);
            assertThat(validationErrors(read(path), valueWire, path)).as(valueWire.toString()).isEmpty();
            assertThat(temporal.treeToValue(valueWire, value.getClass())).isEqualTo(value);
        }
    }

    @Test
    void absentOptionalApiResourceFieldsAreOmittedAndSpecsRoundTripAgainstSchemas() throws Exception {
        Path commandPath = SCHEMA_ROOT.resolve("api-resource-command-v1.schema.json");
        Path specPath = SCHEMA_ROOT.resolve("api-resource-spec-v1.schema.json");
        JsonNode commandSchema = read(commandPath).at("/$defs/resourceCommand");
        JsonNode specSchema = read(specPath);

        ApiResourceCommand minimalCommand = minimalCommand();
        JsonNode minimalWire = MAPPER.valueToTree(minimalCommand);
        assertThat(minimalWire.has("description")).isFalse();
        assertThat(minimalWire.at("/response").has("outputPath")).isFalse();
        assertThat(validationErrors(commandSchema, minimalWire, commandPath)).isEmpty();
        assertThat(MAPPER.treeToValue(minimalWire, ApiResourceCommand.class)).isEqualTo(minimalCommand);

        ApiResourceCommand completeCommand = command("POST",
                new ApiResourceCommand.BodyMatch("$.status", List.of(MAPPER.valueToTree("CREATED"))),
                new ApiResourceCommand.Effect.ManagedWrite("X-Request-Id",
                        new ApiResourceCommand.Effect.Receipt("$.id", "$.status",
                                List.of(MAPPER.valueToTree("SUCCEEDED")), List.of(MAPPER.valueToTree("FAILED"))),
                        new ApiResourceCommand.Effect.Reconciliation(
                                new ApiResourceSpec.ResourceRef("API_RESOURCE", "orders.lookup", 3,
                                        "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"),
                                "$.receiptId")));
        ApiResourceSpec minimalSpec = spec("customer.get-profile", minimalCommand);
        ApiResourceSpec completeSpec = spec("orders.create", completeCommand);
        assertThat(MAPPER.valueToTree(minimalSpec).has("description")).isFalse();
        for (ApiResourceSpec spec : List.of(minimalSpec, completeSpec)) {
            JsonNode wire = MAPPER.valueToTree(spec);
            assertThat(validationErrors(specSchema, wire, specPath)).as(wire.toString()).isEmpty();
            assertThat(MAPPER.treeToValue(wire, ApiResourceSpec.class)).isEqualTo(spec);
        }
        assertThat(MAPPER.valueToTree(completeSpec).at("/effect/reconciliation/resource/kind").asText())
                .isEqualTo("API_RESOURCE");
    }

    @Test
    void authoringProblemDetailRoundTripsAndOmitsAbsentRecoveryPaths() throws Exception {
        Path schemaPath = SCHEMA_ROOT.resolve("problem-detail-v1.schema.json");
        ApiResourceAuthoringProblemDetail problem = new ApiResourceAuthoringProblemDetail(
                "urn:bloge:problem:authoring-service-unavailable", "Persistence unavailable", 503,
                "The API Resource persistence authority is temporarily unavailable.",
                "RG.AUTHORING.API_RESOURCE.PERSISTENCE_FAILED", "corr-01", List.of(),
                List.of(new ApiResourceAuthoringProblemDetail.RecoveryAction("RETRY", null)));

        JsonNode wire = MAPPER.valueToTree(problem);
        assertThat(wire.at("/recoveryActions/0").has("path")).isFalse();
        assertThat(validationErrors(read(schemaPath), wire, schemaPath)).isEmpty();
        assertThat(MAPPER.treeToValue(wire, ApiResourceAuthoringProblemDetail.class)).isEqualTo(problem);
    }

    @Test
    void compoundApiResourceSaveCommandRoundTripsAgainstTheWireAuthority() throws Exception {
        Path schemaPath = SCHEMA_ROOT.resolve("api-resource-command-v1.schema.json");
        ApiResourceSaveCommand command = new ApiResourceSaveCommand(
                ApiResourceSaveCommand.SCHEMA_VERSION,
                ApiResourceSaveCommand.Connection.existing("customer-service"),
                minimalCommand(), ApiResourceSaveCommand.DefaultFixture.none());

        JsonNode wire = MAPPER.valueToTree(command);
        assertThat(wire.at("/connection/mode").asText()).isEqualTo("EXISTING");
        assertThat(wire.at("/defaultFixture/kind").asText()).isEqualTo("NONE");
        assertThat(validationErrors(read(schemaPath), wire, schemaPath)).isEmpty();
        assertThat(MAPPER.treeToValue(wire, ApiResourceSaveCommand.class)).isEqualTo(command);
    }

    private static ApiResourceCommand command(String method, ApiResourceCommand.Success success,
                                              ApiResourceCommand.Effect effect) {
        Map<String, Object> schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id")).schema();
        return new ApiResourceCommand("Orders", "Wire round trip",
                new ApiResourceCommand.Operation(method, "/orders", List.of()),
                new ApiResourceCommand.Contract(new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema)),
                new ApiResourceCommand.Response(success, "$.data"), effect,
                List.of(new ApiResourceCommand.Example("happy", MAPPER.createObjectNode().put("id", "o-1"),
                        MAPPER.createObjectNode().put("id", "o-1"))));
    }

    private static ApiResourceCommand minimalCommand() {
        Map<String, Object> schema = SchemaEnvelope.object(Map.of(), List.of()).schema();
        return new ApiResourceCommand("Orders", null,
                new ApiResourceCommand.Operation("POST", "/orders", List.of()),
                new ApiResourceCommand.Contract(new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema)),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                new ApiResourceCommand.Effect.ManagedWrite("X-Request-Id",
                        new ApiResourceCommand.Effect.Receipt("$.id", "$.status",
                                List.of(MAPPER.valueToTree("SUCCEEDED")), List.of(MAPPER.valueToTree("FAILED"))), null),
                List.of(new ApiResourceCommand.Example("happy", MAPPER.createObjectNode(), MAPPER.createObjectNode())));
    }

    private static ApiResourceSpec spec(String resourceId, ApiResourceCommand command) {
        return new ApiResourceSpec(ApiResourceSpec.SCHEMA_VERSION, resourceId, 1,
                "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                command.displayName(), command.description(), "orders-connection", command.operation(),
                command.contract(), command.response(), command.effect(), command.examples(), ApiResourceSpec.DRAFT);
    }

    private static String effectKind(ApiResourceCommand.Effect effect) {
        return switch (effect) {
            case ApiResourceCommand.Effect.ReadOnly ignored -> "READ_ONLY";
            case ApiResourceCommand.Effect.FixtureOnlyWrite ignored -> "FIXTURE_ONLY_WRITE";
            case ApiResourceCommand.Effect.ManagedWrite ignored -> "MANAGED_WRITE";
        };
    }

    @Test
    void apiResourceReceiptUsesNamedCaseReferencesAndExamplesRemainUnique() throws Exception {
        Path receiptPath = SCHEMA_ROOT.resolve("api-resource-receipt-v1.schema.json");
        JsonNode receipt = read(receiptPath);
        JsonNode valid = read(SCHEMA_ROOT.resolve("examples/api-resource-receipt-complete.json"));
        assertThat(validationErrors(receipt, valid, receiptPath)).isEmpty();
        ObjectNode withoutDefaultFixture = (ObjectNode) valid.deepCopy();
        withoutDefaultFixture.remove("defaultFixture");
        assertThat(validationErrors(receipt, withoutDefaultFixture, receiptPath)).isEmpty();

        ObjectNode oldShape = (ObjectNode) valid.deepCopy();
        oldShape.with("defaultFixture").set("caseIds", MAPPER.createArrayNode().add("created"));
        assertThat(validationErrors(receipt, oldShape, receiptPath)).as("receipt must expose named cases").isNotEmpty();

        JsonNode command = read(SCHEMA_ROOT.resolve("examples/api-resource-complete.json"));
        List<String> names = new ArrayList<>();
        command.at("/resource/examples").forEach(example -> names.add(example.path("name").asText()));
        assertThat(names).doesNotHaveDuplicates();
        assertThat(command.at("/defaultFixture/exampleNames")).allMatch(name -> names.contains(name.asText()));
        assertThat(exampleSelectionsAreValid(command)).isTrue();

        ObjectNode duplicateExamplesCommand = (ObjectNode) command.deepCopy();
        duplicateExamplesCommand.with("resource").withArray("examples")
                .add(duplicateExamplesCommand.at("/resource/examples/0").deepCopy());
        assertThat(exampleSelectionsAreValid(duplicateExamplesCommand)).isFalse();

        ObjectNode duplicateCommand = (ObjectNode) command.deepCopy();
        duplicateCommand.with("defaultFixture").withArray("exampleNames").add(names.get(0));
        assertThat(exampleSelectionsAreValid(duplicateCommand)).isFalse();

        ObjectNode unknownCommand = (ObjectNode) command.deepCopy();
        unknownCommand.with("defaultFixture").withArray("exampleNames").add("unknown-example");
        assertThat(exampleSelectionsAreValid(unknownCommand)).isFalse();
    }

    @Test
    void fixtureStatusSummaryAndViewUseOneGovernedVocabulary() throws Exception {
        Set<String> expected = Set.of("PRIVATE_DRAFT", "SHARING_PENDING", "TEAM_AVAILABLE", "STALE", "REVOKED");
        JsonNode common = read(SCHEMA_ROOT.resolve("common-v1.schema.json"));
        assertThat(common.at("/$defs/fixtureStatus/enum")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrderElementsOf(expected);
        for (String schemaName : List.of("fixture-set-summary-v1.schema.json", "fixture-set-view-v1.schema.json")) {
            JsonNode schema = read(SCHEMA_ROOT.resolve(schemaName));
            assertThat(schema.at("/properties/status/$ref").asText())
                    .isEqualTo("common-v1.schema.json#/$defs/fixtureStatus");
        }
        JsonNode receipt = read(SCHEMA_ROOT.resolve("fixture-set-receipt-v1.schema.json"));
        assertThat(receipt.at("/properties/status/const").asText()).isEqualTo("PRIVATE_DRAFT");
        assertThat(read(SCHEMA_ROOT.resolve("examples/fixture-view-complete.json"))).isNotNull();
    }

    @Test
    void generatedDefaultFixtureRoundTripsAgainstCommandViewSummaryAndReceiptSchemas() throws Exception {
        ApiResourceSpec resource = spec("orders", minimalCommand());
        GeneratedDefaultFixture generated = new DefaultFixtureSetMaterializer().generate(resource,
                new ApiResourceSaveCommand.DefaultFixture.FromExamples("Default orders", List.of("happy")));
        FixtureSetView view = generated.view();
        FixtureSetCommand command = new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION,
                view.displayName(), view.subject(), view.cases());

        Path commandPath = SCHEMA_ROOT.resolve("fixture-set-command-v1.schema.json");
        Path viewPath = SCHEMA_ROOT.resolve("fixture-set-view-v1.schema.json");
        Path summaryPath = SCHEMA_ROOT.resolve("fixture-set-summary-v1.schema.json");
        Path receiptPath = SCHEMA_ROOT.resolve("fixture-set-receipt-v1.schema.json");
        JsonNode commandWire = MAPPER.valueToTree(command);
        JsonNode viewWire = MAPPER.valueToTree(view);
        JsonNode summaryWire = MAPPER.valueToTree(generated.summary());
        JsonNode receiptWire = MAPPER.valueToTree(generated.receipt());

        assertThat(validationErrors(read(commandPath), commandWire, commandPath)).isEmpty();
        assertThat(validationErrors(read(viewPath), viewWire, viewPath)).isEmpty();
        assertThat(validationErrors(read(summaryPath), summaryWire, summaryPath)).isEmpty();
        assertThat(validationErrors(read(receiptPath), receiptWire, receiptPath)).isEmpty();
        assertThat(MAPPER.treeToValue(commandWire, FixtureSetCommand.class)).isEqualTo(command);
        assertThat(MAPPER.treeToValue(viewWire, FixtureSetView.class)).isEqualTo(view);
        assertThat(MAPPER.treeToValue(summaryWire, FixtureSetSummary.class)).isEqualTo(generated.summary());
        assertThat(MAPPER.treeToValue(receiptWire, FixtureSetSaveReceipt.class)).isEqualTo(generated.receipt());
        assertThat(summaryWire.toString()).doesNotContain("input", "material", "output");
    }

    @Test
    void fixtureShareCommandAndPayloadFreeReceiptRoundTripAgainstFrozenSchemas() throws Exception {
        FixtureShareCommand command = new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source("orders:r1", 1,
                        "sha256:" + "a".repeat(64), 1),
                new FixtureShareCommand.Policy("CONFIDENTIAL", 30,
                        new FixtureShareCommand.Redaction(
                                "default-v1", List.of("/customer/email", "/customer/phone"))));
        FixtureShareReceipt receipt = new FixtureShareReceipt(FixtureShareReceipt.SCHEMA_VERSION,
                "orders:r1", 1, 2, "sha256:" + "b".repeat(64),
                FixtureSetView.Status.SHARING_PENDING, 2, "review-orders-r2");
        Path commandPath = SCHEMA_ROOT.resolve("fixture-share-command-v1.schema.json");
        Path receiptPath = SCHEMA_ROOT.resolve("fixture-share-receipt-v1.schema.json");
        JsonNode commandWire = MAPPER.valueToTree(command);
        JsonNode receiptWire = MAPPER.valueToTree(receipt);

        assertThat(validationErrors(read(commandPath), commandWire, commandPath)).isEmpty();
        assertThat(validationErrors(read(receiptPath), receiptWire, receiptPath)).isEmpty();
        assertThat(MAPPER.treeToValue(commandWire, FixtureShareCommand.class)).isEqualTo(command);
        assertThat(MAPPER.treeToValue(receiptWire, FixtureShareReceipt.class)).isEqualTo(receipt);
        assertThat(receiptWire.toString())
                .doesNotContain("cases", "input", "output", "fixtureAssetId", "materialRef");
        assertThatThrownBy(() -> new FixtureShareCommand.Redaction(
                "default-v1", List.of("$.customer.email")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fixtureReviewCommandAndPayloadFreeReceiptRoundTripAgainstFrozenSchemas() throws Exception {
        FixtureReviewCommand command = new FixtureReviewCommand(FixtureReviewCommand.SCHEMA_VERSION,
                new FixtureReviewCommand.Source("review-orders-r2", "orders:r1", 2,
                        "sha256:" + "b".repeat(64), 2),
                new FixtureReviewCommand.Attestations(
                        true, true, true, "Independent review completed"));
        FixtureReviewReceipt receipt = new FixtureReviewReceipt(FixtureReviewReceipt.SCHEMA_VERSION,
                "review-orders-r2", "orders:r1", 2, 3, "sha256:" + "c".repeat(64),
                FixtureSetView.Status.TEAM_AVAILABLE, 3, 2);
        Path commandPath = SCHEMA_ROOT.resolve("fixture-review-command-v1.schema.json");
        Path receiptPath = SCHEMA_ROOT.resolve("fixture-review-receipt-v1.schema.json");
        JsonNode commandWire = MAPPER.valueToTree(command);
        JsonNode receiptWire = MAPPER.valueToTree(receipt);

        assertThat(validationErrors(read(commandPath), commandWire, commandPath)).isEmpty();
        assertThat(validationErrors(read(receiptPath), receiptWire, receiptPath)).isEmpty();
        assertThat(MAPPER.treeToValue(commandWire, FixtureReviewCommand.class)).isEqualTo(command);
        assertThat(MAPPER.treeToValue(receiptWire, FixtureReviewReceipt.class)).isEqualTo(receipt);
        assertThat(receiptWire.toString())
                .doesNotContain("cases", "input", "output", "fixtureAssetId", "materialRef");
    }

    @Test
    void simulationRunEgressIsAnEvidenceUnionWithoutSensitivePayload() throws Exception {
        Path schemaPath = SCHEMA_ROOT.resolve("simulation-run-v1.schema.json");
        JsonNode schema = read(schemaPath);
        ObjectNode allowed = MAPPER.createObjectNode().put("decision", "ALLOWED_READ").put("attempted", true);
        allowed.set("resource", MAPPER.createObjectNode().put("kind", "API_RESOURCE").put("resourceId", "customer.get-profile")
                .put("revision", 1).put("fingerprint", "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"));
        allowed.set("connection", MAPPER.createObjectNode().put("connectionId", "customer-service").put("revision", 1));
        allowed.put("authorizationDecisionId", "authz-01").put("outcome", "SUCCEEDED");
        assertThat(validationErrors(schema.at("/$defs/node/properties/egress"), allowed, schemaPath)).isEmpty();
        ObjectNode denied = MAPPER.createObjectNode().put("decision", "DENIED").put("attempted", false);
        denied.set("resource", allowed.get("resource"));
        denied.put("authorizationDecisionId", "authz-01").put("reasonCode", "POLICY_DENIED");
        assertThat(validationErrors(schema.at("/$defs/node/properties/egress"), denied, schemaPath)).isEmpty();
        ObjectNode fixture = MAPPER.createObjectNode().put("decision", "FIXTURE").put("attempted", false);
        assertThat(validationErrors(schema.at("/$defs/node/properties/egress"), fixture, schemaPath)).isEmpty();
        JsonNode notAttempted = MAPPER.createObjectNode().put("decision", "NOT_ATTEMPTED").put("attempted", false)
                .put("reasonCode", "NO_FIXTURE");
        assertThat(validationErrors(schema.at("/$defs/node/properties/egress"), notAttempted, schemaPath)).isEmpty();
        ObjectNode sensitive = fixture.deepCopy().put("payload", "secret");
        assertThat(validationErrors(schema.at("/$defs/node/properties/egress"), sensitive, schemaPath))
                .as("egress must not carry payload").isNotEmpty();
    }

    @Test
    void simulationRequestAndRunRoundTripAgainstFrozenWireSchemas() throws Exception {
        ObjectMapper wireMapper = MAPPER.copy().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SimulationRequest request = SimulationRequest.fixtureCase("orders:r1", 1, "happy");
        var subject = new com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef.ApiResource(
                "orders", 1, "sha256:" + "e".repeat(64));
        SimulationRun run = new SimulationRun(SimulationRun.SCHEMA_VERSION, "sim-1",
                SimulationRun.Status.SUCCEEDED, subject,
                new SimulationRun.FixtureCase("orders:r1", 1, "happy"),
                MAPPER.createObjectNode().put("id", "one"),
                List.of(new SimulationRun.Node("orders", SimulationRun.NodeStatus.COMPLETED,
                        SimulationRun.Execution.MOCKED, SimulationRun.FixtureSource.INLINE,
                        SimulationRun.Fidelity.OUTPUT_LEVEL, SimulationRun.Egress.fixture())),
                new SimulationRun.Verdicts(SimulationRun.ExecutionVerdict.SIMULATED_ONLY,
                        SimulationRun.Verdict.PASSED, SimulationRun.Verdict.NOT_CHECKED,
                        SimulationRun.Verdict.NOT_CHECKED), List.of(),
                Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"));
        Path requestPath = SCHEMA_ROOT.resolve("simulation-request-v1.schema.json");
        Path runPath = SCHEMA_ROOT.resolve("simulation-run-v1.schema.json");
        JsonNode requestWire = wireMapper.valueToTree(request);
        JsonNode runWire = wireMapper.valueToTree(run);

        assertThat(validationErrors(read(requestPath), requestWire, requestPath)).isEmpty();
        assertThat(validationErrors(read(runPath), runWire, runPath)).isEmpty();
        assertThat(wireMapper.treeToValue(requestWire, SimulationRequest.class)).isEqualTo(request);
        assertThat(wireMapper.treeToValue(runWire, SimulationRun.class)).isEqualTo(run);
        assertThat(run.toString()).doesNotContain("one");

        var flowSubject = new com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef.FlowVersion(
                "eligibility-v1", 1, "sha256:" + "b".repeat(64));
        SimulationRun wholeFlow = new SimulationRun(SimulationRun.SCHEMA_VERSION, "sim-flow",
                SimulationRun.Status.SUCCEEDED, flowSubject,
                new SimulationRun.FixtureCase("eligibility-cases", 1, "approved"),
                MAPPER.createObjectNode().put("eligible", true), List.of(),
                new SimulationRun.Verdicts(SimulationRun.ExecutionVerdict.SIMULATED_ONLY,
                        SimulationRun.Verdict.PASSED, SimulationRun.Verdict.PASSED,
                        SimulationRun.Verdict.NOT_CHECKED), List.of(),
                Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"));
        JsonNode wholeFlowWire = wireMapper.valueToTree(wholeFlow);
        assertThat(validationErrors(read(runPath), wholeFlowWire, runPath)).isEmpty();
        assertThat(wireMapper.treeToValue(wholeFlowWire, SimulationRun.class)).isEqualTo(wholeFlow);
        assertThat(wholeFlow.nodes()).isEmpty();
    }

    private static List<String> validationErrors(JsonNode schema, JsonNode value, Path owner) throws IOException {
        List<String> errors = new ArrayList<>();
        validate(schema, value, owner, "", errors);
        return errors;
    }

    private static boolean exampleSelectionsAreValid(JsonNode command) {
        List<String> definedNames = new ArrayList<>();
        command.at("/resource/examples").forEach(example -> definedNames.add(example.path("name").asText()));
        List<String> selections = new ArrayList<>();
        command.at("/defaultFixture/exampleNames").forEach(name -> selections.add(name.asText()));
        return definedNames.size() == definedNames.stream().distinct().count()
                && selections.size() == selections.stream().distinct().count()
                && selections.stream().allMatch(definedNames::contains);
    }

    private static void assertStrictObjects(JsonNode node, String location) {
        if (node.isObject()) {
            if (node.path("type").asText().equals("object") && node.has("properties")) {
                assertThat(node.path("additionalProperties").isBoolean() && !node.path("additionalProperties").asBoolean())
                        .as(location).isTrue();
            }
            node.fields().forEachRemaining(entry -> assertStrictObjects(entry.getValue(), location + "/" + entry.getKey()));
        } else if (node.isArray()) {
            node.forEach(child -> assertStrictObjects(child, location));
        }
    }

    private static void assertReferencesResolve(JsonNode node, Path owner) throws Exception {
        if (node.isObject()) {
            if (node.has("$ref")) {
                String ref = node.get("$ref").asText();
                if (!ref.startsWith("#")) {
                    String file = ref.split("#", 2)[0];
                    Path target = owner.getParent().resolve(file).normalize();
                    assertThat(Files.exists(target)).as("%s from %s", ref, owner).isTrue();
                    if (ref.contains("#")) {
                        JsonNode targetSchema = read(target);
                        pointer(targetSchema, ref.substring(ref.indexOf('#')));
                    }
                } else {
                    pointer(read(owner), ref);
                }
            }
            node.fields().forEachRemaining(entry -> {
                try {
                    assertReferencesResolve(entry.getValue(), owner);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertReferencesResolve(child, owner);
            }
        }
    }

    private static JsonNode pointer(JsonNode root, String fragment) {
        JsonNode selected = root.at(fragment.startsWith("#") ? fragment.substring(1) : fragment);
        assertThat(selected.isMissingNode()).as("unresolved JSON pointer %s", fragment).isFalse();
        return selected;
    }

    private static void validate(JsonNode schema, JsonNode value, Path owner, String path, List<String> errors)
            throws IOException {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            Path target = owner;
            String fragment = ref;
            if (!ref.startsWith("#")) {
                String[] parts = ref.split("#", 2);
                target = owner.getParent().resolve(parts[0]).normalize();
                fragment = parts.length == 2 ? "#" + parts[1] : "";
            }
            validate(fragment.isEmpty() ? read(target) : pointer(read(target), fragment), value, target, path, errors);
            return;
        }
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode branch : schema.get("oneOf")) {
                List<String> branchErrors = new ArrayList<>();
                validate(branch, value, owner, path, branchErrors);
                if (branchErrors.isEmpty()) matches++;
            }
            if (matches != 1) errors.add(path + " oneOf matched " + matches);
            return;
        }
        if (schema.has("anyOf")) {
            boolean match = false;
            for (JsonNode branch : schema.get("anyOf")) {
                List<String> branchErrors = new ArrayList<>();
                validate(branch, value, owner, path, branchErrors);
                match |= branchErrors.isEmpty();
            }
            if (!match) errors.add(path + " anyOf did not match");
            return;
        }
        if (schema.has("not")) {
            List<String> prohibitedErrors = new ArrayList<>();
            validate(schema.get("not"), value, owner, path, prohibitedErrors);
            if (prohibitedErrors.isEmpty()) errors.add(path + " not");
        }
        if (schema.has("const") && !schema.get("const").equals(value)) errors.add(path + " const");
        if (schema.has("enum") && !contains(schema.get("enum"), value)) errors.add(path + " enum");
        String type = schema.path("type").asText("");
        if (!type.isEmpty() && !matchesType(type, value)) {
            errors.add(path + " type " + type);
            return;
        }
        if (schema.has("minLength") && value.isTextual() && value.textValue().length() < schema.get("minLength").asInt())
            errors.add(path + " minLength");
        if (schema.has("pattern") && value.isTextual() && !Pattern.compile(schema.get("pattern").asText()).matcher(value.textValue()).find())
            errors.add(path + " pattern");
        if (schema.has("minimum") && value.isNumber() && value.asDouble() < schema.get("minimum").asDouble())
            errors.add(path + " minimum");
        if (value.isObject()) {
            for (String required : names(schema.get("required"))) if (!value.has(required)) errors.add(path + "/" + required + " required");
            if (schema.has("propertyNames")) {
                Iterator<String> names = value.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    validate(schema.get("propertyNames"), MAPPER.valueToTree(name), owner, path + "/" + name, errors);
                }
            }
            JsonNode properties = schema.path("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode property = properties.get(field.getKey());
                if (property != null) validate(property, field.getValue(), owner, path + "/" + field.getKey(), errors);
                else {
                    JsonNode patternProperties = schema.path("patternProperties");
                    boolean matched = false;
                    if (patternProperties.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> patterns = patternProperties.fields();
                        while (patterns.hasNext()) {
                            Map.Entry<String, JsonNode> pattern = patterns.next();
                            if (field.getKey().matches(pattern.getKey())) {
                                matched = true;
                                validate(pattern.getValue(), field.getValue(), owner, path + "/" + field.getKey(), errors);
                            }
                        }
                    }
                    if (!matched && schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean())
                        errors.add(path + "/" + field.getKey() + " additionalProperties");
                }
            }
        }
        if (value.isArray() && schema.has("items")) {
            for (int i = 0; i < value.size(); i++) validate(schema.get("items"), value.get(i), owner, path + "/" + i, errors);
        }
        if (value.isArray() && schema.path("uniqueItems").asBoolean(false)) {
            for (int i = 0; i < value.size(); i++) {
                for (int j = i + 1; j < value.size(); j++) {
                    if (value.get(i).equals(value.get(j))) errors.add(path + " uniqueItems");
                }
            }
        }
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (node != null && node.isArray()) node.forEach(value -> names.add(value.asText()));
        return names;
    }

    private static boolean contains(JsonNode values, JsonNode expected) {
        for (JsonNode value : values) if (value.equals(expected)) return true;
        return false;
    }

    private static JsonNode read(Path path) throws IOException {
        return MAPPER.readTree(Files.readString(path));
    }
}
