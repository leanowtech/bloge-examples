package com.leanowtech.bloge.gateway.visual.authoring.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Contract tests for the Slice 0 authoring wire schemas and golden examples. */
class AuthoringProtocolSchemaTest {

    private static final Path SCHEMA_ROOT = Path.of("..", "docs", "schemas", "resource-gateway-authoring");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> FAMILIES = Map.ofEntries(
            Map.entry("connection", "connection-command-v1.schema.json"),
            Map.entry("api-resource", "api-resource-command-v1.schema.json"),
            Map.entry("api-resource-receipt", "api-resource-receipt-v1.schema.json"),
            Map.entry("openapi", "openapi-preview-v1.schema.json"),
            Map.entry("reusable-flow", "reusable-flow-command-v1.schema.json"),
            Map.entry("reusable-flow-publish-command", "reusable-flow-publish-command-v1.schema.json"),
            Map.entry("reusable-flow-publish-receipt", "reusable-flow-publish-receipt-v1.schema.json"),
            Map.entry("fixture-set", "fixture-set-command-v1.schema.json"),
            Map.entry("fixture-share-command", "fixture-share-command-v1.schema.json"),
            Map.entry("fixture-share-receipt", "fixture-share-receipt-v1.schema.json"),
            Map.entry("fixture-summary", "fixture-set-summary-v1.schema.json"),
            Map.entry("fixture-view", "fixture-set-view-v1.schema.json"),
            Map.entry("simulation-request", "simulation-request-v1.schema.json"),
            Map.entry("simulation-run", "simulation-run-v1.schema.json"),
            Map.entry("problem-detail", "problem-detail-v1.schema.json"));

    @Test
    void allAuthoringSchemasAreDraft202012StrictAndReferencesResolve() throws Exception {
        List<Path> schemas = Files.list(SCHEMA_ROOT)
                .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                .sorted()
                .toList();
        assertThat(schemas).isNotEmpty();
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
                    .filter(path -> path.getFileName().toString().startsWith(family.getKey() + "-"))
                    .filter(path -> !family.getKey().equals("reusable-flow")
                            || !path.getFileName().toString().contains("publish"))
                    .filter(path -> !family.getKey().equals("api-resource")
                            || !path.getFileName().toString().contains("receipt"))
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
    void secretReferencesAndSensitiveHeadersAreValidatedCaseInsensitively() throws Exception {
        Path commonPath = SCHEMA_ROOT.resolve("common-v1.schema.json");
        JsonNode common = read(commonPath);
        JsonNode headers = common.at("/$defs/safeHeaderMap");
        assertThat(validationErrors(headers, MAPPER.createObjectNode().put("Accept", "application/json"), commonPath))
                .as("ordinary headers remain legal").isEmpty();
        for (String name : List.of("Authorization", "authorization", "COOKIE", "proxy-authorization", "proxy-authenticate",
                "set-cookie", "Host", "content-length", "Connection", "keep-alive", "TE", "Trailer",
                "transfer-encoding", "Upgrade", "Forwarded", "X-Forwarded-For", "x-forwarded-host")) {
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
