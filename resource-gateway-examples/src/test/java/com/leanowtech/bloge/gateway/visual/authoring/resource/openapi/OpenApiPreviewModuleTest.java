package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImporter;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public-seam tests for side-effect-free OpenAPI Resource preview. */
class OpenApiPreviewModuleTest {
    private final ApiResourceDecisions decisions = new ApiResourceDecisions();
    private final OpenApiPreviewModule module = new OpenApiPreviewModule(
            new OpenApiResourceDesignContractImporter(), new JsonSchemaSampleGenerator(),
            new ObjectMapper(), decisions);

    @Test
    void previewsInlineYamlAsAValidSaveableResourceCommand() {
        OpenApiPreview preview = module.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Inline(simpleApi()),
                List.of("getCustomer")));

        assertThat(preview.schemaVersion()).isEqualTo(OpenApiPreview.SCHEMA_VERSION);
        assertThat(preview.discoveryId()).startsWith("preview-");
        assertThat(preview.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.operationId()).isEqualTo("getCustomer");
            assertThat(operation.method()).isEqualTo("GET");
            assertThat(operation.path()).isEqualTo("/customers/{customerId}");
            assertThat(operation.suggestedResource().operation().bindings())
                    .extracting(binding -> binding.from() + "->" + binding.to().location()
                            + ":" + binding.to().name())
                    .containsExactly("$.customerId->PATH:customerId", "$.verbose->QUERY:verbose");
            assertThat(operation.suggestedResource().examples()).singleElement()
                    .satisfies(example -> {
                        assertThat(example.name()).isEqualTo("openapi-example");
                        assertThat(example.input()).hasToString("{\"customerId\":\"string\",\"verbose\":false}");
                        assertThat(example.output()).hasToString("{\"name\":\"string\",\"active\":false}");
                    });
            decisions.validateForAuthoring(operation.suggestedResource());
        });
    }

    @Test
    void filtersRequestedOperationsWithoutPersistingAnything() {
        OpenApiPreview preview = module.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Inline(simpleApi()),
                List.of("getCustomer")));

        assertThat(preview.operations()).extracting(OpenApiPreview.Operation::operationId)
                .containsExactly("getCustomer");
    }

    @Test
    void defaultPreviewSkipsOperationsThatCannotBeImported() {
        OpenApiPreview preview = module.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Inline(simpleApi() + """
                        \n  /legacy:
                            patch:
                              operationId: patchLegacy
                              responses:
                                '200':
                                  description: legacy text
                                  content:
                                    text/plain:
                                      schema: { type: string }
                        """),
                List.of()));

        assertThat(preview.operations()).extracting(OpenApiPreview.Operation::operationId)
                .containsExactly("getCustomer");
    }

    @Test
    void rejectsRemoteFetchUntilAnAuthenticatedEgressAdapterExists() {
        assertThatThrownBy(() -> module.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Remote("https://api.example.test/openapi.yaml", null),
                List.of()), identity()))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.CAPABILITY_UNAVAILABLE);
    }

    @Test
    void previewsRemoteDocumentThroughOneTrustedGovernedEgressRequest() {
        AtomicReference<RemoteOpenApiDocumentGateway.Request> observed = new AtomicReference<>();
        RemoteOpenApiDocumentGateway gateway = request -> {
            observed.set(request);
            return new RemoteOpenApiDocumentGateway.Document(
                    "application/yaml", simpleApi().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        };
        OpenApiPreviewModule remoteModule = new OpenApiPreviewModule(
                new OpenApiResourceDesignContractImporter(), new JsonSchemaSampleGenerator(),
                new ObjectMapper(), decisions, gateway);
        OpenApiPreviewIdentity identity = new OpenApiPreviewIdentity(
                new AuthoringScope("tenant-a", "project-a", "test"), "author-a",
                "API_RESOURCE_AUTHORING");

        OpenApiPreview preview = remoteModule.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Remote(
                        "https://api.example.test/contracts/openapi.yaml", "customer-api"),
                List.of("getCustomer")), identity);

        assertThat(preview.operations()).extracting(OpenApiPreview.Operation::operationId)
                .containsExactly("getCustomer");
        assertThat(observed.get()).isEqualTo(new RemoteOpenApiDocumentGateway.Request(
                identity, URI.create("https://api.example.test/contracts/openapi.yaml"), "customer-api",
                10 * 1024 * 1024, Duration.ofSeconds(15)));
    }

    @Test
    void remotePreviewRequiresTrustedIdentityAndSafeHttpsSourceBeforeEgress() {
        AtomicReference<RemoteOpenApiDocumentGateway.Request> observed = new AtomicReference<>();
        OpenApiPreviewModule remoteModule = new OpenApiPreviewModule(
                new OpenApiResourceDesignContractImporter(), new JsonSchemaSampleGenerator(),
                new ObjectMapper(), decisions, request -> {
                    observed.set(request);
                    throw new AssertionError("egress must not be called");
                });
        OpenApiPreviewCommand missingIdentity = new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Remote("https://api.example.test/openapi.yaml", null), List.of());

        assertThatThrownBy(() -> remoteModule.preview(missingIdentity))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.VALIDATION);
        assertThatThrownBy(() -> remoteModule.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Remote("https://token@api.example.test/openapi.yaml", null),
                List.of()), identity()))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.VALIDATION);
        assertThatThrownBy(() -> remoteModule.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Remote(
                        "https://api.example.test/openapi.yaml?access_token=secret", null),
                List.of()), identity()))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.VALIDATION);
        assertThat(observed).hasValue(null);
    }

    @Test
    void remotePreviewRejectsUnsafeGatewayOutputWithoutParsingIt() {
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        OpenApiPreviewCommand command = new OpenApiPreviewCommand(OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Remote("https://api.example.test/openapi.yaml", null), List.of());

        assertThatThrownBy(() -> remoteModule(request ->
                new RemoteOpenApiDocumentGateway.Document("text/html", simpleApi().getBytes())).preview(
                command, identity()))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.REMOTE_FETCH_FAILED);
        assertThatThrownBy(() -> remoteModule(request ->
                new RemoteOpenApiDocumentGateway.Document("application/json", oversized)).preview(
                command, identity()))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.REMOTE_FETCH_FAILED);
        assertThatThrownBy(() -> remoteModule(request ->
                new RemoteOpenApiDocumentGateway.Document("application/yaml",
                        new byte[]{(byte) 0xc3, (byte) 0x28})).preview(command, identity()))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.REMOTE_FETCH_FAILED);
    }

    @Test
    void remoteGatewayPayloadIsDefensiveAndUnexpectedErrorsStayPayloadFree() {
        byte[] original = simpleApi().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RemoteOpenApiDocumentGateway.Document document =
                new RemoteOpenApiDocumentGateway.Document("application/yaml", original);
        original[0] = 'x';
        byte[] exposed = document.bytes();
        exposed[0] = 'y';

        assertThat(new String(document.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("openapi:");
        assertThat(document.toString()).doesNotContain("Customer API", "openapi:");

        assertThatThrownBy(() -> remoteModule(request -> {
            throw new IllegalStateException("https://secret.example.test/openapi.yaml?token=secret");
        }).preview(new OpenApiPreviewCommand(OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Remote("https://api.example.test/openapi.yaml", null),
                List.of()), identity()))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .hasMessage("Remote OpenAPI document could not be read safely.")
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.REMOTE_FETCH_FAILED);
    }

    @Test
    void rejectsMalformedDocumentsAndUnknownRequestedOperations() {
        assertThatThrownBy(() -> module.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Inline("openapi: 3.0.3\npaths: ["),
                List.of())))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.VALIDATION);

        assertThatThrownBy(() -> module.preview(new OpenApiPreviewCommand(
                OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Inline(simpleApi()),
                List.of("missingOperation"))))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.VALIDATION);
    }

    private static String simpleApi() {
        return """
                openapi: 3.0.3
                info:
                  title: Customer API
                  version: 1.0.0
                servers:
                  - url: https://api.example.test
                paths:
                  /customers/{customerId}:
                    get:
                      operationId: getCustomer
                      summary: Get customer
                      parameters:
                        - in: path
                          name: customerId
                          required: true
                          schema: { type: string }
                        - in: query
                          name: verbose
                          required: false
                          schema: { type: boolean }
                      responses:
                        '200':
                          description: Customer
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [name, active]
                                properties:
                                  name: { type: string }
                                  active: { type: boolean }
                """;
    }

    private OpenApiPreviewModule remoteModule(RemoteOpenApiDocumentGateway gateway) {
        return new OpenApiPreviewModule(new OpenApiResourceDesignContractImporter(),
                new JsonSchemaSampleGenerator(), new ObjectMapper(), decisions, gateway);
    }

    private static OpenApiPreviewIdentity identity() {
        return new OpenApiPreviewIdentity(new AuthoringScope("tenant-a", "project-a", "test"),
                "author-a", "API_RESOURCE_AUTHORING");
    }
}
