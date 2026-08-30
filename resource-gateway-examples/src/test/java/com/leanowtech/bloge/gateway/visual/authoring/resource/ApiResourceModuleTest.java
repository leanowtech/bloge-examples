package com.leanowtech.bloge.gateway.visual.authoring.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public-seam behavior tests for the authoritative in-memory API Resource module. */
class ApiResourceModuleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void createAndGetReturnsExactApiResourceReferenceAtRevisionOne() throws Exception {
        ApiResourceModule module = new InMemoryApiResourceModule();

        ApiResourceSpec saved = module.save("customer.get-profile", "customer-service", validCommand(),
                ExpectedRevision.create());

        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.schemaVersion()).isEqualTo(ApiResourceSpec.SCHEMA_VERSION);
        assertThat(saved.connectionId()).isEqualTo("customer-service");
        assertThat(saved.status()).isEqualTo("DRAFT");
        assertThat(saved.ref()).isEqualTo(new ApiResourceSpec.ResourceRef(
                "API_RESOURCE", "customer.get-profile", 1, saved.fingerprint()));
        assertThat(module.get("customer.get-profile")).contains(saved);
    }

    @Test
    void matchingRevisionCreatesNewRevisionAndStaleCasDoesNotChangeState() throws Exception {
        ApiResourceModule module = new InMemoryApiResourceModule();
        ApiResourceSpec first = module.save("customer.get-profile", "customer-service", validCommand(),
                ExpectedRevision.create());

        ApiResourceSpec second = module.save("customer.get-profile", "customer-service",
                commandWithDescription("renamed"), ExpectedRevision.exact(1));

        assertThat(second.revision()).isEqualTo(2);
        assertThatThrownBy(() -> module.save("customer.get-profile", "customer-service",
                commandWithDescription("stale"), ExpectedRevision.exact(1)))
                .isInstanceOf(ApiResourceAuthoringException.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringException.Code.CAS_MISMATCH);
        assertThat(module.get("customer.get-profile")).contains(second);
        assertThat(module.get("customer.get-profile").orElseThrow().revision())
                .isGreaterThan(first.revision());
    }

    @Test
    void duplicateCreateAndMissingResourceUseStableDomainCodes() throws Exception {
        ApiResourceModule module = new InMemoryApiResourceModule();
        module.save("customer.get-profile", "customer-service", validCommand(), ExpectedRevision.create());

        assertThatThrownBy(() -> module.save("customer.get-profile", "customer-service", validCommand(),
                ExpectedRevision.create()))
                .isInstanceOf(ApiResourceAuthoringException.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringException.Code.ALREADY_EXISTS);
        assertThatThrownBy(() -> module.save("missing", "customer-service", validCommand(),
                ExpectedRevision.exact(1)))
                .isInstanceOf(ApiResourceAuthoringException.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringException.Code.NOT_FOUND);
    }

    @Test
    void equivalentObjectKeyOrdersHaveCanonicalFingerprintAndEachPutStillAdvancesRevision() throws Exception {
        ApiResourceModule module = new InMemoryApiResourceModule();
        ApiResourceSpec first = module.save("customer.get-profile", "customer-service", validCommand(),
                ExpectedRevision.create());
        ApiResourceSpec second = module.save("customer.get-profile", "customer-service",
                reorderedEquivalentCommand(), ExpectedRevision.exact(1));

        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
    }

    @Test
    void allNestedSnapshotsAreDefensiveCopies() throws Exception {
        ApiResourceModule module = new InMemoryApiResourceModule();
        ApiResourceCommand input = bodyMatchCommand();
        ApiResourceSpec saved = module.save("customer.get-profile", "customer-service", input,
                ExpectedRevision.create());
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved.examples().get(0).input())
                .put("id", "mutated");
        Map<String, Object> schema = saved.contract().input().schema();
        ((Map<String, Object>) schema.get("properties")).put("injected", Map.of("type", "string"));
        ApiResourceCommand.BodyMatch savedMatch = (ApiResourceCommand.BodyMatch) saved.response().success();
        ((com.fasterxml.jackson.databind.node.ObjectNode) savedMatch.values().get(0)).put("mutated", true);

        ApiResourceSpec readAgain = module.get("customer.get-profile").orElseThrow();
        assertThat(readAgain.examples().get(0).input().path("id").asText()).isEqualTo("customer-1");
        assertThat(readAgain.contract().input().hasProperty("injected")).isFalse();
        ApiResourceCommand.BodyMatch match = (ApiResourceCommand.BodyMatch) readAgain.response().success();
        assertThat(match.values().get(0).has("mutated")).isFalse();
        assertThat(readAgain.revision()).isEqualTo(1);
        assertThat(readAgain.fingerprint()).isEqualTo(saved.fingerprint());
    }

    @Test
    void validationFailsClosedForPathBindingSchemaExampleAndMethodEffectRules() throws Exception {
        ApiResourceModule module = new InMemoryApiResourceModule();
        assertValidation(module, "https://evil.example.com/profile", validCommand().withPath("https://evil.example.com/profile"));
        assertValidation(module, "missing binding input", validCommand().withBindings(List.of(
                new ApiResourceCommand.Binding("$.missing", new ApiResourceCommand.Location("QUERY", "id")))));
        assertValidation(module, "schema type", validCommand().withInputSchema(schemaWithPropertyType("array")));
        assertValidation(module, "example type", validCommand().withExamples(List.of(
                new ApiResourceCommand.Example("anonymous", JSON.createObjectNode().put("id", 1), object("id", "customer-1")))));
        assertValidation(module, "GET effect", validCommand().withEffect(ApiResourceCommand.Effect.FIXTURE_ONLY_WRITE));
        assertValidation(module, "POST effect", validCommand().withMethodEffect("POST", ApiResourceCommand.Effect.READ_ONLY));
        assertValidation(module, "managed receipt", validCommand().withMethodEffect("POST", new ApiResourceCommand.Effect.ManagedWrite(
                "Authorization", new ApiResourceCommand.Effect.Receipt("$.id", "$.status", List.of(object("x", "y")), List.of(object("x", "z"))), null)));
        assertValidation(module, "multiple body", validCommand().withBindings(List.of(
                new ApiResourceCommand.Binding("$.id", new ApiResourceCommand.Location("BODY", "id")),
                new ApiResourceCommand.Binding("$.id", new ApiResourceCommand.Location("BODY", "other")))));
        assertValidation(module, "identifier", validCommand(), "bad id");
        assertValidation(module, "path character", validCommand().withPath("/profile?bad"));
    }

    @Test
    void concurrentMatchOneHasExactlyOneWinnerAndEndsAtRevisionTwo() throws Exception {
        ApiResourceModule module = new InMemoryApiResourceModule();
        module.save("customer.get-profile", "customer-service", validCommand(), ExpectedRevision.create());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> concurrentSave(module, ready, start));
            var second = pool.submit(() -> concurrentSave(module, ready, start));
            ready.await();
            start.countDown();
            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes.stream().filter(ApiResourceSpec.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(ApiResourceAuthoringException.class::isInstance)
                    .map(ApiResourceAuthoringException.class::cast).map(ApiResourceAuthoringException::code))
                    .containsExactly(ApiResourceAuthoringException.Code.CAS_MISMATCH);
        } finally {
            pool.shutdownNow();
        }
        assertThat(module.get("customer.get-profile").orElseThrow().revision()).isEqualTo(2);
    }

    private Object concurrentSave(ApiResourceModule module, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return module.save("customer.get-profile", "customer-service", commandWithDescription("r2"),
                    ExpectedRevision.match(1));
        } catch (ApiResourceAuthoringException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @Test
    void duplicateExampleNamesAndCaseInsensitiveReservedHeadersAreRejected() throws Exception {
        ApiResourceModule module = new InMemoryApiResourceModule();
        ApiResourceCommand.Example example = validCommand().examples().get(0);
        assertValidation(module, "duplicate examples", validCommand().withExamples(List.of(example, example)));
        for (String header : List.of("Authorization", "COOKIE", "Host", "content-length", "X-Forwarded-For")) {
            assertValidation(module, header, validCommand().withBindings(List.of(
                    new ApiResourceCommand.Binding("$.id", new ApiResourceCommand.Location("HEADER", header)))));
        }
    }

    private void assertValidation(ApiResourceModule module, String label, ApiResourceCommand command) {
        assertValidation(module, label, command, "invalid-" + label.replace(' ', '-'));
    }

    private void assertValidation(ApiResourceModule module, String label, ApiResourceCommand command, String resourceId) {
        assertThatThrownBy(() -> module.save(resourceId, "customer-service", command,
                ExpectedRevision.create()))
                .as(label)
                .isInstanceOf(ApiResourceAuthoringException.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringException.Code.VALIDATION);
    }

    private ApiResourceCommand bodyMatchCommand() throws Exception {
        return new ApiResourceCommand("Customer profile", "Read a customer profile.",
                new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schemaWithPropertyType("string")),
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schemaWithPropertyType("string"))),
                new ApiResourceCommand.Response(new ApiResourceCommand.BodyMatch("$.status", List.of(object("state", "ok"))), "$.data"),
                ApiResourceCommand.Effect.READ_ONLY,
                List.of(new ApiResourceCommand.Example("anonymous", object("id", "customer-1"), object("id", "customer-1"))));
    }

    private ApiResourceCommand validCommand() throws Exception {
        return new ApiResourceCommand(
                "Customer profile", "Read a customer profile.",
                new ApiResourceCommand.Operation("GET", "/profile", List.of(
                        new ApiResourceCommand.Binding("$.id", new ApiResourceCommand.Location("PATH", "id")))),
                new ApiResourceCommand.Contract(
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schemaWithPropertyType("string")),
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schemaWithPropertyType("string"))),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), "$.data"),
                ApiResourceCommand.Effect.READ_ONLY,
                List.of(new ApiResourceCommand.Example("anonymous", object("id", "customer-1"),
                        object("id", "customer-1"))));
    }

    private ApiResourceCommand commandWithDescription(String description) {
        try {
            return validCommand().withDescription(description);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private ApiResourceCommand reorderedEquivalentCommand() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("required", List.of("id"));
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("type", "object");
        return validCommand().withSchemas(schema, schema);
    }

    private Map<String, Object> schemaWithPropertyType(String type) {
        return SchemaEnvelope.object(Map.of("id", Map.of("type", type)), List.of("id")).schema();
    }

    private JsonNode object(String key, String value) {
        return JSON.createObjectNode().put(key, value);
    }
}
