package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public-seam tests for the authoritative API Resource projection compiler. */
class DefaultApiResourceProjectionCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void projectsEveryBindingAndHttpSuccessIntoAllReadyDocuments() throws Exception {
        ReadyApiResourceProjections projections = compiler()
                .compile(SCOPE, resource(new ApiResourceCommand.Effect.ReadOnly(),
                        new ApiResourceCommand.HttpStatus(List.of(200, 201))));

        ResourceDescriptor descriptor = JSON.treeToValue(projections.descriptor().body(), ResourceDescriptor.class);
        assertThat(descriptor.urlTemplate()).isEqualTo("https://api.example.test/customers/{customerId}");
        assertThat(descriptor.defaultHeaders()).containsEntry("X-Default", "one");
        assertThat(descriptor.parameterMapping().pathExpressions()).containsEntry("customerId", "ctx.params.customerId");
        assertThat(descriptor.parameterMapping().queryExpressions()).containsEntry("locale", "ctx.params.locale");
        assertThat(descriptor.parameterMapping().headerExpressions()).containsEntry("X-Trace", "ctx.params.traceId");
        assertThat(descriptor.parameterMapping().bodyExpression()).isEqualTo("ctx.params.payload");
        assertThat(descriptor.responseProtocol()).isEqualTo(new ResponseProtocol.StatusCodes(java.util.Set.of(200, 201)));
        assertThat(descriptor.payloadPath()).isEqualTo("data");

        assertReadyAndExactSubject(projections);
        assertThat(projections.designContract().body().get("requestSchema").get("schema").get("properties")
                .get("customerId")).isNotNull();
        assertThat(projections.designContract().body().get("examples").get("prime").get("output").get("score").asInt())
                .isEqualTo(720);
        assertThat(projections.operator().body().get("operatorRef").asText()).isEqualTo("resource:customer.get-profile");
        assertThat(projections.operator().body().get("source").get("urlTemplate").asText())
                .isEqualTo("https://api.example.test/customers/{customerId}");
        assertThat(projections.operator().body().get("state")).isNull();
    }

    @Test
    void mapsBodyMatchAndFixtureOnlyWriteToRuntimeAndOperatorSemantics() {
        DefaultApiResourceProjectionCompiler compiler = compiler();
        ReadyApiResourceProjections read = compiler.compile(SCOPE, resource(new ApiResourceCommand.Effect.ReadOnly(),
                new ApiResourceCommand.BodyMatch("$.ok", List.of(JSON.getNodeFactory().booleanNode(true)))));
        ResourceDescriptor readDescriptor = JSON.convertValue(read.descriptor().body(), ResourceDescriptor.class);
        assertThat(readDescriptor.responseProtocol()).isEqualTo(new ResponseProtocol.BodyFlag("ok"));
        assertThat(readDescriptor.externalWriteContract()).isNull();
        assertThat(read.operator().body().get("capabilities").get("effect").asText()).isEqualTo("READ_EXTERNAL");

        ResourceDescriptor falseMatch = JSON.convertValue(compiler.compile(SCOPE, resource(
                new ApiResourceCommand.Effect.ReadOnly(), new ApiResourceCommand.BodyMatch("$.ok", List.of(
                        JSON.getNodeFactory().booleanNode(false), JSON.getNodeFactory().booleanNode(true))))).descriptor().body(),
                ResourceDescriptor.class);
        assertThat(falseMatch.responseProtocol()).isEqualTo(new ResponseProtocol.BodyCode("ok",
                java.util.Set.of(false, true), ""));

        ReadyApiResourceProjections fixture = compiler.compile(SCOPE, resource(new ApiResourceCommand.Effect.FixtureOnlyWrite(),
                new ApiResourceCommand.HttpStatus(List.of(202))));
        assertThat(fixture.operator().body().get("capabilities").get("effect").asText()).isEqualTo("WRITE_EXTERNAL");
        JsonNode unmanagedContract = fixture.descriptor().body().get("externalWriteContract");
        assertThat((Object) unmanagedContract).isNotNull();
        assertThat(unmanagedContract.isNull()).isTrue();

        ApiResourceCommand.Effect managed = new ApiResourceCommand.Effect.ManagedWrite(
                "X-Idempotency-Key", new ApiResourceCommand.Effect.Receipt("$.receipt.id", "$.receipt.status",
                List.of(JSON.getNodeFactory().textNode("OK")), List.of(JSON.getNodeFactory().textNode("FAILED"))), null);
        assertThatThrownBy(() -> compiler.compile(SCOPE, resource(managed,
                new ApiResourceCommand.HttpStatus(List.of(201)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANAGED_WRITE projection is unsupported");
    }

    @Test
    void mapsRootAndNestedOutputPathsToPayloadExtractorSemantics() {
        ResourceDescriptor root = descriptorForOutputPath(null);
        ResourceDescriptor explicitRoot = descriptorForOutputPath("$");
        ResourceDescriptor nested = descriptorForOutputPath("$.data.profile");
        assertThat(root.payloadPath()).isNull();
        assertThat(explicitRoot.payloadPath()).isNull();
        assertThat(nested.payloadPath()).isEqualTo("data.profile");
        assertThat(new PayloadExtractor(JSON).extract(
                "{\"data\":{\"profile\":{\"id\":7}}}", nested.payloadPath()))
                .isEqualTo(Map.of("id", 7));
    }

    @Test
    void projectionBodiesAreDefensiveAndFingerprintsAreDerivedFromCanonicalBodies() {
        ReadyApiResourceProjections projections = compiler().compile(SCOPE,
                resource(new ApiResourceCommand.Effect.ReadOnly(), new ApiResourceCommand.HttpStatus(List.of(200))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) projections.operator().body()).put("operatorRef", "tampered");
        assertThat(projections.operator().body().get("operatorRef").asText()).isEqualTo("resource:customer.get-profile");
        assertThat(projections.descriptor().fingerprint()).isEqualTo(AuthoringFingerprints.of(projections.descriptor().body()));
        assertThat(projections.designContract().fingerprint()).isEqualTo(AuthoringFingerprints.of(projections.designContract().body()));
        assertThat(projections.operator().fingerprint()).isEqualTo(AuthoringFingerprints.of(projections.operator().body()));
    }

    @Test
    void rejectsApiKeyCollisionsInDefaultsAndResourceBindings() {
        assertThatThrownBy(() -> compilerWithConnection("X-Trace", Map.of("X-Default", "one"))
                .compile(SCOPE, resource(new ApiResourceCommand.Effect.ReadOnly(),
                        new ApiResourceCommand.HttpStatus(List.of(200)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("api-key");
        assertThatThrownBy(() -> compilerWithConnection("X-Trace", Map.of())
                .compile(SCOPE, resource(new ApiResourceCommand.Effect.ReadOnly(),
                        new ApiResourceCommand.HttpStatus(List.of(200)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("api-key");
    }

    @Test
    void rejectsInvalidIdentityPathsHeadersAndIncompleteContractsFailClosed() {
        DefaultApiResourceProjectionCompiler compiler = new DefaultApiResourceProjectionCompiler(
                (scope, connectionId) -> Optional.empty());
        assertThatThrownBy(() -> compiler.compile(SCOPE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compiler.compile(SCOPE, resourceAt("https://evil.example/x", new ApiResourceCommand.Effect.ReadOnly())))
                .isInstanceOf(IllegalArgumentException.class);
        ApiResourceCommand.Operation operation = new ApiResourceCommand.Operation("GET", "/x",
                List.of(new ApiResourceCommand.Binding("$.traceId", new ApiResourceCommand.Location("HEADER", "Authorization"))));
        assertThatThrownBy(() -> compiler.compile(SCOPE, resourceWithOperation(operation))).isInstanceOf(IllegalArgumentException.class);
        ApiResourceCommand.Operation missingInput = new ApiResourceCommand.Operation("GET", "/x",
                List.of(new ApiResourceCommand.Binding("$.missing", new ApiResourceCommand.Location("QUERY", "q"))));
        assertThatThrownBy(() -> compiler.compile(SCOPE, resourceWithOperation(missingInput))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultApiResourceProjectionCompiler((scope, connectionId) -> Optional.empty()).compile(SCOPE,
                resource(new ApiResourceCommand.Effect.ReadOnly(), new ApiResourceCommand.HttpStatus(List.of(200)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connection projection");
        assertThatThrownBy(() -> new ApiResourceConnectionProjectionResolver.ConnectionMetadata(
                "https://api.example.test", Map.of("Authorization", "secret"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> new ApiResourceConnectionProjectionResolver.ConnectionMetadata(
                "https://api.example.test?secret=1", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiResourceConnectionProjectionResolver.ConnectionMetadata(
                "https://api.example.test", Map.of("X-Forwarded-For", "x"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiResourceConnectionProjectionResolver.ConnectionMetadata(
                "https://api.example.test", Map.of("X-API-Key", "static-secret"), Duration.ofSeconds(1), "X-API-Key"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("api-key");
        assertThatThrownBy(() -> compiler().compile(SCOPE, resourceWithOutputPath("$.data..profile")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outputPath");
    }

    private static void assertReadyAndExactSubject(ReadyApiResourceProjections projections) {
        assertThat(projections.descriptor().state()).isEqualTo(ProjectionDocument.State.READY);
        assertThat(projections.designContract().state()).isEqualTo(ProjectionDocument.State.READY);
        assertThat(projections.operator().state()).isEqualTo(ProjectionDocument.State.READY);
        assertThat(projections.descriptor().kind()).isEqualTo(ProjectionDocument.Kind.DESCRIPTOR);
        assertThat(projections.designContract().kind()).isEqualTo(ProjectionDocument.Kind.DESIGN_CONTRACT);
        assertThat(projections.operator().kind()).isEqualTo(ProjectionDocument.Kind.OPERATOR);
        assertThat(projections.subject()).isEqualTo(new ApiResourceSpec.ResourceRef("API_RESOURCE",
                "customer.get-profile", 3, FINGERPRINT));
    }

    private static ApiResourceSpec resource(ApiResourceCommand.Effect effect, ApiResourceCommand.Success success) {
        return new ApiResourceSpec("bloge.apiResourceSpec.v1", "customer.get-profile", 3, FINGERPRINT,
                "Get customer profile", "profile", "customer-service",
                new ApiResourceCommand.Operation("GET", "/customers/{customerId}", List.of(
                        new ApiResourceCommand.Binding("$.customerId", new ApiResourceCommand.Location("PATH", "customerId")),
                        new ApiResourceCommand.Binding("$.locale", new ApiResourceCommand.Location("QUERY", "locale")),
                        new ApiResourceCommand.Binding("$.traceId", new ApiResourceCommand.Location("HEADER", "X-Trace")),
                        new ApiResourceCommand.Binding("$.payload", new ApiResourceCommand.Location("BODY", "body")))),
                new ApiResourceCommand.Contract(SchemaEnvelope.object(Map.of(
                        "customerId", Map.of("type", "string"), "locale", Map.of("type", "string"),
                        "traceId", Map.of("type", "string"), "payload", Map.of("type", "object")), List.of("customerId")),
                        SchemaEnvelope.object(Map.of("data", Map.of("type", "object")), List.of())),
                new ApiResourceCommand.Response(success, "$.data"), effect, List.of(new ApiResourceCommand.Example(
                        "prime", JSON.createObjectNode().put("customerId", "customer-1"),
                        JSON.createObjectNode().put("score", 720))), "DRAFT");
    }

    private static DefaultApiResourceProjectionCompiler compiler() {
        return compilerWithConnection("", Map.of("X-Default", "one"));
    }

    private static DefaultApiResourceProjectionCompiler compilerWithConnection(String apiKeyHeader,
                                                                                 Map<String, String> defaults) {
        return new DefaultApiResourceProjectionCompiler((scope, connectionId) -> Optional.of(
                new ApiResourceConnectionProjectionResolver.ConnectionMetadata(
                        "https://api.example.test", defaults, Duration.ofSeconds(10), apiKeyHeader)));
    }

    private static ApiResourceSpec resourceAt(String path, ApiResourceCommand.Effect effect) {
        ApiResourceSpec source = resource(effect, new ApiResourceCommand.HttpStatus(List.of(200)));
        return new ApiResourceSpec(source.schemaVersion(), source.resourceId(), source.revision(), source.fingerprint(),
                source.displayName(), source.description(), source.connectionId(),
                new ApiResourceCommand.Operation(source.operation().method(), path, source.operation().bindings()),
                source.contract(), source.response(), source.effect(), source.examples(), source.status());
    }

    private static ApiResourceSpec resourceWithOperation(ApiResourceCommand.Operation operation) {
        ApiResourceSpec source = resource(new ApiResourceCommand.Effect.ReadOnly(), new ApiResourceCommand.HttpStatus(List.of(200)));
        return new ApiResourceSpec(source.schemaVersion(), source.resourceId(), source.revision(), source.fingerprint(),
                source.displayName(), source.description(), source.connectionId(), operation, source.contract(),
                source.response(), source.effect(), source.examples(), source.status());
    }

    private static ResourceDescriptor descriptorForOutputPath(String outputPath) {
        return JSON.convertValue(compiler().compile(SCOPE, resourceWithOutputPath(outputPath)).descriptor().body(),
                ResourceDescriptor.class);
    }

    private static ApiResourceSpec resourceWithOutputPath(String outputPath) {
        ApiResourceSpec source = resource(new ApiResourceCommand.Effect.ReadOnly(),
                new ApiResourceCommand.HttpStatus(List.of(200)));
        return new ApiResourceSpec(source.schemaVersion(), source.resourceId(), source.revision(), source.fingerprint(),
                source.displayName(), source.description(), source.connectionId(), source.operation(), source.contract(),
                new ApiResourceCommand.Response(source.response().success(), outputPath), source.effect(),
                source.examples(), source.status());
    }
}
