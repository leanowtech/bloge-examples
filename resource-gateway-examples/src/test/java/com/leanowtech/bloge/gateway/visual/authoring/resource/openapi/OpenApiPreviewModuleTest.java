package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImporter;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                List.of())))
                .isInstanceOf(OpenApiPreviewFailure.class)
                .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                .isEqualTo(OpenApiPreviewFailure.Code.CAPABILITY_UNAVAILABLE);
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
}
