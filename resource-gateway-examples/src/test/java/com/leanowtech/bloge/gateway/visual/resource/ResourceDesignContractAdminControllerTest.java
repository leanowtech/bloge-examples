package com.leanowtech.bloge.gateway.visual.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for resource design contract admin APIs.
 */
class ResourceDesignContractAdminControllerTest {

    private InMemoryResourceDesignContractRegistry registry;
    private InMemoryGraphDraftRepository drafts;
    private InMemoryVisualGraphPublicationRepository publications;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private ResourceDescriptor descriptor;
    private ResourceVirtualOperatorProjector projector;

    @BeforeEach
    void setUp() {
        registry = new InMemoryResourceDesignContractRegistry();
        drafts = new InMemoryGraphDraftRepository();
        publications = new InMemoryVisualGraphPublicationRepository();
        objectMapper = new ObjectMapper();
        descriptor = orderDescriptor();
        projector = new ResourceVirtualOperatorProjector();
        ResourceDesignContractAdminController controller = new ResourceDesignContractAdminController(
                registry,
                new ResourceDesignContractValidator(),
                new OpenApiResourceDesignContractImporter(),
                drafts,
                publications,
                resourceRegistry(descriptor),
                projector
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void validateReturnsDiagnosticsWithoutStoring() throws Exception {
        ResourceDesignContract invalid = invalidArrayContract();

        mockMvc.perform(post("/admin/resource-design-contracts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.schema.arrayItemsMissing"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromOpenApiReturnsContractDraftWithoutStoring() throws Exception {
        OpenApiResourceDesignContractImportRequest request = new OpenApiResourceDesignContractImportRequest(
                "order-service.listOrders",
                "listOrders",
                null,
                null,
                null,
                openApiOrderList()
        );

        mockMvc.perform(post("/admin/resource-design-contracts/from-openapi")
                        .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.resourceContract.openapi.descriptorDiff"))
                .andExpect(jsonPath("$.validation.impact.schemaVersion")
                        .value(ResourceDesignContractImpactReview.SCHEMA_VERSION))
                .andExpect(jsonPath("$.validation.impact.warningCount").value(1))
                .andExpect(jsonPath("$.validation.impact.resourceIds[0]").value("order-service.listOrders"))
                .andExpect(jsonPath("$.validation.impact.operatorRefs[0]")
                        .value("resource:order-service.listOrders"))
                .andExpect(jsonPath("$.contract.resourceId").value("order-service.listOrders"))
                .andExpect(jsonPath("$.contract.displayName").value("List orders"))
                .andExpect(jsonPath("$.contract.requestSchema.schema.required[0]").value("userId"))
                .andExpect(jsonPath("$.contract.responseSchema.schema.properties.items.items.properties.id.type")
                        .value("string"))
                .andExpect(jsonPath("$.descriptorSuggestion.resourceId").value("order-service.listOrders"))
                .andExpect(jsonPath("$.descriptorSuggestion.urlTemplate")
                        .value("https://api.example.test/v1/orders"))
                .andExpect(jsonPath("$.descriptorSuggestion.parameterMapping.queryExpressions.userId")
                        .value("ctx.params.userId"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void openApiOperationsDiscoversOperationsWithoutStoring() throws Exception {
        OpenApiResourceDesignContractImportRequest request = new OpenApiResourceDesignContractImportRequest(
                null,
                null,
                null,
                null,
                null,
                openApiOrderList()
        );

        mockMvc.perform(post("/admin/resource-design-contracts/from-openapi/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.operations[0].operationId").value("listOrders"))
                .andExpect(jsonPath("$.operations[0].path").value("/orders"))
                .andExpect(jsonPath("$.operations[0].method").value("GET"))
                .andExpect(jsonPath("$.operations[0].summary").value("List orders"))
                .andExpect(jsonPath("$.operations[0].tags[0]").value("order"))
                .andExpect(jsonPath("$.operations[0].hasRequestBody").value(false))
                .andExpect(jsonPath("$.operations[0].responseMediaTypes[0]").value("application/json"))
                .andExpect(jsonPath("$.operations[0].projectionLevel").value("READY"))
                .andExpect(jsonPath("$.operations[0].projectionMessage").value("Ready to project into a resource contract."));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromOpenApiRejectsUnresolvedSchemaReferencesBeforeReturningDraft() throws Exception {
        OpenApiResourceDesignContractImportRequest request = new OpenApiResourceDesignContractImportRequest(
                "order-service.listOrders",
                "listOrders",
                null,
                null,
                null,
                openApiWithUnresolvedResponseSchemaRef()
        );

        mockMvc.perform(post("/admin/resource-design-contracts/from-openapi/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.operations[0].projectionLevel").value("BLOCKED"))
                .andExpect(jsonPath("$.operations[0].projectionMessage")
                        .value(org.hamcrest.Matchers.containsString("MissingOrderList")));

        mockMvc.perform(post("/admin/resource-design-contracts/from-openapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.descriptorSuggestion").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.resourceContract.openapi.refUnresolved"))
                .andExpect(jsonPath("$.validation.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("MissingOrderList")));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromOpenApiWarnsWhenPreviewDiffersFromStoredContractAndDescriptor() throws Exception {
        registry.upsert(new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Legacy orders",
                "Old OpenAPI projection.",
                List.of("legacy"),
                SchemaEnvelope.object(Map.of(
                        "legacyUserId", Map.of("type", "string")
                ), List.of("legacyUserId")),
                SchemaEnvelope.object(Map.of(
                        "legacyItems", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                        )
                ), List.of()),
                Map.of("legacy", Map.of("legacyUserId", "u1")),
                ResourceDesignContract.STATUS_ACTIVE
        ));
        OpenApiResourceDesignContractImportRequest request = new OpenApiResourceDesignContractImportRequest(
                "order-service.listOrders",
                "listOrders",
                null,
                null,
                null,
                openApiOrderList()
        );

        mockMvc.perform(post("/admin/resource-design-contracts/from-openapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.diagnostics[*].code")
                        .value(org.hamcrest.Matchers.hasItems(
                                "visual.resourceContract.openapi.requestSchemaDiff",
                                "visual.resourceContract.openapi.responseSchemaDiff",
                                "visual.resourceContract.openapi.contractMetadataDiff",
                                "visual.resourceContract.openapi.descriptorDiff"
                        )))
                .andExpect(jsonPath("$.contract.resourceId").value("order-service.listOrders"));

        assertThat(registry.findByResourceId("order-service.listOrders")).isPresent();
    }

    @Test
    void fromOpenApiAcceptsYamlTextWithoutStoring() throws Exception {
        OpenApiResourceDesignContractImportRequest request = new OpenApiResourceDesignContractImportRequest(
                "order-service.listOrders",
                null,
                "/orders",
                "GET",
                null,
                null,
                openApiOrderListYaml()
        );

        mockMvc.perform(post("/admin/resource-design-contracts/from-openapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.contract.resourceId").value("order-service.listOrders"))
                .andExpect(jsonPath("$.contract.requestSchema.schema.properties.userId.type").value("string"))
                .andExpect(jsonPath("$.descriptorSuggestion.urlTemplate")
                        .value("https://api.example.test/v1/orders"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void upsertRejectsInvalidContract() throws Exception {
        ResourceDesignContract invalid = invalidArrayContract();

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.schema.arrayItemsMissing"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void upsertRejectsUnsupportedLifecycleStatus() throws Exception {
        ResourceDesignContract invalid = validContract(Map.of(), "ARCHIVED");

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.status.unsupported"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void upsertRejectsRawSecretExamples() throws Exception {
        ResourceDesignContract invalid = validContract(Map.of(
                "request", Map.of("token", "Bearer clear-text-token")
        ));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.secret.raw"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void upsertStoresValidContract() throws Exception {
        ResourceDesignContract valid = validContract(Map.of());

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value("order-service.listOrders"));

        mockMvc.perform(get("/admin/resource-design-contracts/order-service.listOrders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseSchema.schema.properties.items.items.type").value("object"));
    }

    @Test
    void upsertReturnsStructuredPersistenceFailureWithoutStoring() throws Exception {
        useFailingRegistry(FailingMutation.UPSERT);
        ResourceDesignContract valid = validContract(Map.of());

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.upsertPersistenceFailed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/contract"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.resourceId")
                        .value("order-service.listOrders"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.contractId").value("contract:orders"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.mutationAction").value("UPSERT"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.exception")
                        .value("IllegalStateException"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void upsertRejectsDisablingContractReferencedByStoredDraft() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract disabled = validContract(Map.of(), ResourceDesignContract.STATUS_DISABLED);
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.resourceContract.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Resource design contract for 'order-service.listOrders' cannot be disabled without force=true because draft 'draft-1@1' node 'orders' still uses operatorRef 'resource:order-service.listOrders'."))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void upsertForceBypassesDisabledContractReferenceGuard() throws Exception {
        ResourceDesignContract disabled = validContract(Map.of(), ResourceDesignContract.STATUS_DISABLED);
        registry.upsert(validContract(Map.of()));
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .param("force", "true")
                        .param("actor", "resource-admin")
                        .param("reason", "Stored-draft reference was reviewed before disabling the contract.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(disabled);
    }

    @Test
    void validateReportsDisablingContractReferencedByStoredDraft() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract disabled = validContract(Map.of(), ResourceDesignContract.STATUS_DISABLED);
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(post("/admin/resource-design-contracts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.resourceContract.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void validateForceBypassesDisabledContractImpactDiagnostic() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract disabled = validContract(Map.of(), ResourceDesignContract.STATUS_DISABLED);
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(post("/admin/resource-design-contracts/validate")
                        .param("force", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics").isEmpty());

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void upsertForceRequiresGovernanceEvidenceBeforeMutation() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract disabled = validContract(Map.of(), ResourceDesignContract.STATUS_DISABLED);
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .param("force", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[*].code")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("visual.resourceContract.governanceEvidenceMissing"))))
                .andExpect(jsonPath("$.diagnostics[*].target")
                        .value(org.hamcrest.Matchers.hasItems("/actor", "/reason")))
                .andExpect(jsonPath("$.diagnostics[0].metadata.requiredFor[0]").value("force"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.resourceId").value("order-service.listOrders"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void validateWarnsWhenDeprecatingContractReferencedByStoredDraft() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract deprecated = validContract(Map.of(), ResourceDesignContract.STATUS_DEPRECATED);
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(post("/admin/resource-design-contracts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.lifecycle.deprecated"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Resource design contract for 'order-service.listOrders' is being deprecated; draft 'draft-1@1' node 'orders' still uses operatorRef 'resource:order-service.listOrders'. Review migration before production promotion."))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.resourceId").value("order-service.listOrders"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.contractStatus").value("DEPRECATED"))
                .andExpect(jsonPath("$.impact.schemaVersion")
                        .value(ResourceDesignContractImpactReview.SCHEMA_VERSION))
                .andExpect(jsonPath("$.impact.warningCount").value(1))
                .andExpect(jsonPath("$.impact.resourceIds[0]").value("order-service.listOrders"))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("resource:order-service.listOrders"))
                .andExpect(jsonPath("$.impact.draftIds[0]").value("draft-1"))
                .andExpect(jsonPath("$.impact.draftTargets[0].draftId").value("draft-1"))
                .andExpect(jsonPath("$.impact.draftTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.codeCounts[0].code")
                        .value("visual.resourceContract.lifecycle.deprecated"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void validateWarnsWhenDeprecatingContractReferencedByPublishedArtifact() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract deprecated = validContract(Map.of(), ResourceDesignContract.STATUS_DEPRECATED);
        registry.upsert(original);
        publications.create(publicationUsingResource("order-service.listOrders"));

        mockMvc.perform(post("/admin/resource-design-contracts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.publicationLifecycleDeprecated"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Resource design contract for 'order-service.listOrders' is being deprecated while publication 'pub-1' node 'orders' was authored with operatorRef 'resource:order-service.listOrders'. Existing publication keeps its frozen DSL, but replay, recertification, or republishing should be reviewed."))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/publications/pub-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.publicationId").value("pub-1"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.resourceId").value("order-service.listOrders"))
                .andExpect(jsonPath("$.impact.warningCount").value(1))
                .andExpect(jsonPath("$.impact.publicationIds[0]").value("pub-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].publicationId").value("pub-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("resource:order-service.listOrders"))
                .andExpect(jsonPath("$.impact.codeCounts[0].code")
                        .value("visual.resourceContract.publicationLifecycleDeprecated"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void upsertRequiresWarningAcknowledgementBeforeDeprecatingUsedContract() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract deprecated = validContract(Map.of(), ResourceDesignContract.STATUS_DEPRECATED);
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.lifecycle.deprecated"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .param("ackWarnings", "true")
                        .param("actor", "resource-admin")
                        .param("reason", "Draft references were reviewed before deprecating the contract.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPRECATED"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(deprecated);
    }

    @Test
    void upsertRequiresGovernanceEvidenceWhenWarningsAreAcknowledged() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract deprecated = validContract(Map.of(), ResourceDesignContract.STATUS_DEPRECATED);
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .param("ackWarnings", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[*].code")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("visual.resourceContract.governanceEvidenceMissing"))))
                .andExpect(jsonPath("$.diagnostics[*].target")
                        .value(org.hamcrest.Matchers.hasItems("/actor", "/reason")))
                .andExpect(jsonPath("$.diagnostics[0].metadata.requiredFor[0]").value("ackWarnings"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.resourceId").value("order-service.listOrders"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void upsertRequiresWarningAcknowledgementBeforeDisablingPublishedContract() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract disabled = validContract(Map.of(), ResourceDesignContract.STATUS_DISABLED);
        registry.upsert(original);
        publications.create(publicationUsingResource("order-service.listOrders"));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.publicationDisabled"))
                .andExpect(jsonPath("$.impact.publicationIds[0]").value("pub-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].publicationId").value("pub-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].nodeIndex").value(0));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .param("ackWarnings", "true")
                        .param("actor", "resource-admin")
                        .param("reason", "Published artifact references were reviewed before disabling the contract.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(disabled);
    }

    @Test
    void validateWarnsWhenReplacingUsedContractWithDifferentFingerprint() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract replacement = contractWithCountResponse();
        String originalFingerprint = projector.project(descriptor, Optional.of(original)).fingerprint();
        String replacementFingerprint = projector.project(descriptor, Optional.of(replacement)).fingerprint();
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders", originalFingerprint));

        mockMvc.perform(post("/admin/resource-design-contracts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.operatorFingerprintDrift"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "saved fingerprint '" + originalFingerprint + "'")))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("'" + replacementFingerprint + "'")))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "changed surface: output port 'payload' schema changed")))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeRisk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeCategories[0]").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.resourceId").value("order-service.listOrders"))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].risk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].count").value(1))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("resource:order-service.listOrders"));

        assertThat(replacementFingerprint).isNotEqualTo(originalFingerprint);
        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void validateWarnsWhenReplacingPublishedContractWithDifferentFingerprint() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract replacement = contractWithCountResponse();
        String originalFingerprint = projector.project(descriptor, Optional.of(original)).fingerprint();
        String replacementFingerprint = projector.project(descriptor, Optional.of(replacement)).fingerprint();
        registry.upsert(original);
        publications.create(publicationUsingResource("order-service.listOrders", originalFingerprint));

        mockMvc.perform(post("/admin/resource-design-contracts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.publicationOperatorFingerprintDrift"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "publication 'pub-1' node 'orders' from frozen fingerprint '"
                                        + originalFingerprint + "'")))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("'" + replacementFingerprint + "'")))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/publications/pub-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.publicationId").value("pub-1"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeRisk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.impact.publicationIds[0]").value("pub-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].publicationId").value("pub-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].risk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("resource:order-service.listOrders"));

        assertThat(replacementFingerprint).isNotEqualTo(originalFingerprint);
        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void validateWarnsWhenReplacingUsedContractWithoutSavedFingerprint() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract replacement = contractWithCountResponse();
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders", Map.of()));

        mockMvc.perform(post("/admin/resource-design-contracts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.operatorFingerprintSnapshotMissing"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Resource design contract 'order-service.listOrders' changes operatorRef 'resource:order-service.listOrders' used by draft 'draft-1@1' node 'orders', but the draft has no saved operator fingerprint; review and resave the draft before execution."))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeRisk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].risk").value("BREAKING_SCHEMA"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void upsertRequiresWarningAcknowledgementBeforeStoringFingerprintDrift() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract replacement = contractWithCountResponse();
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders",
                projector.project(descriptor, Optional.of(original)).fingerprint()));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.operatorFingerprintDrift"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("changed surface: output port 'payload' schema changed")));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
    }

    @Test
    void upsertStoresFingerprintDriftWhenWarningsAcknowledged() throws Exception {
        ResourceDesignContract original = validContract(Map.of());
        ResourceDesignContract replacement = contractWithCountResponse();
        registry.upsert(original);
        drafts.save(draftUsingResource("order-service.listOrders",
                projector.project(descriptor, Optional.of(original)).fingerprint()));

        mockMvc.perform(put("/admin/resource-design-contracts/order-service.listOrders")
                        .param("ackWarnings", "true")
                        .param("actor", "resource-admin")
                        .param("reason", "Fingerprint drift was reviewed before replacing the contract.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value("order-service.listOrders"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(replacement);
    }

    @Test
    void upsertRejectsPathMismatchWithStructuredDiagnostic() throws Exception {
        ResourceDesignContract valid = validContract(Map.of());

        mockMvc.perform(put("/admin/resource-design-contracts/other-service.listOrders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.resourceContract.invalid"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void deleteRejectsContractReferencedByStoredDraft() throws Exception {
        ResourceDesignContract contract = validContract(Map.of());
        registry.upsert(contract);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(delete("/admin/resource-design-contracts/order-service.listOrders"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.resourceContract.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.impact.errorCount").value(1))
                .andExpect(jsonPath("$.impact.resourceIds[0]").value("order-service.listOrders"))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("resource:order-service.listOrders"))
                .andExpect(jsonPath("$.impact.draftTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.codeCounts[0].code").value("visual.resourceContract.inUse"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(contract);
    }

    @Test
    void deleteRejectsContractReferencedByPublishedArtifact() throws Exception {
        ResourceDesignContract contract = validContract(Map.of());
        registry.upsert(contract);
        publications.create(publicationUsingResource("order-service.listOrders"));

        mockMvc.perform(delete("/admin/resource-design-contracts/order-service.listOrders"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.publicationInUse"))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/publications/pub-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.publicationId").value("pub-1"))
                .andExpect(jsonPath("$.impact.errorCount").value(1))
                .andExpect(jsonPath("$.impact.publicationIds[0]").value("pub-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].publicationId").value("pub-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.resourceIds[0]").value("order-service.listOrders"))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("resource:order-service.listOrders"))
                .andExpect(jsonPath("$.impact.codeCounts[0].code")
                        .value("visual.resourceContract.publicationInUse"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(contract);
    }

    @Test
    void deleteForceBypassesStoredDraftReferenceGuard() throws Exception {
        registry.upsert(validContract(Map.of()));
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(delete("/admin/resource-design-contracts/order-service.listOrders")
                        .param("force", "true")
                        .param("actor", "resource-admin")
                        .param("reason", "Stored-draft reference was reviewed before deleting the contract."))
                .andExpect(status().isNoContent());

        assertThat(registry.findByResourceId("order-service.listOrders")).isEmpty();
    }

    @Test
    void deleteReturnsStructuredPersistenceFailureAndKeepsCurrentContract() throws Exception {
        useFailingRegistry(FailingMutation.DELETE);
        ResourceDesignContract contract = validContract(Map.of());
        ((FailingResourceDesignContractRegistry) registry).seed(contract);

        mockMvc.perform(delete("/admin/resource-design-contracts/order-service.listOrders"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.resourceContract.deletePersistenceFailed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/resourceId"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.resourceId")
                        .value("order-service.listOrders"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.contractId").value("contract:orders"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.mutationAction").value("DELETE"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.exception")
                        .value("IllegalStateException"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(contract);
    }

    @Test
    void deleteForceRequiresGovernanceEvidenceBeforeMutation() throws Exception {
        ResourceDesignContract contract = validContract(Map.of());
        registry.upsert(contract);
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(delete("/admin/resource-design-contracts/order-service.listOrders")
                        .param("force", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[*].code")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("visual.resourceContract.governanceEvidenceMissing"))))
                .andExpect(jsonPath("$.diagnostics[*].target")
                        .value(org.hamcrest.Matchers.hasItems("/actor", "/reason")))
                .andExpect(jsonPath("$.diagnostics[0].metadata.requiredFor[0]").value("force"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.resourceId").value("order-service.listOrders"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(contract);
    }

    private void useFailingRegistry(FailingMutation mutation) {
        registry = new FailingResourceDesignContractRegistry(mutation);
        ResourceDesignContractAdminController controller = new ResourceDesignContractAdminController(
                registry,
                new ResourceDesignContractValidator(),
                new OpenApiResourceDesignContractImporter(),
                drafts,
                publications,
                resourceRegistry(descriptor),
                projector
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static ResourceDesignContract invalidArrayContract() {
        return new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                requestSchema(),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of("type", "array")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );
    }

    private static ResourceDesignContract validContract(Map<String, Object> examples) {
        return validContract(examples, "ACTIVE");
    }

    private static ResourceDesignContract validContract(Map<String, Object> examples, String status) {
        return new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                requestSchema(),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object", "additionalProperties", true)
                        )
                ), List.of()),
                examples,
                status
        );
    }

    private static ResourceDesignContract contractWithCountResponse() {
        return new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                requestSchema(),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object", "additionalProperties", true)
                        ),
                        "count", Map.of("type", "integer")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );
    }

    private static SchemaEnvelope requestSchema() {
        return SchemaEnvelope.object(Map.of(
                "userId", Map.of("type", "string")
        ), List.of("userId"));
    }

    private static GraphDraft draftUsingResource(String resourceId) {
        return draftUsingResource(resourceId, "fingerprint");
    }

    private static GraphDraft draftUsingResource(String resourceId, String fingerprint) {
        return draftUsingResource(resourceId, Map.of("orders", fingerprint));
    }

    private static GraphDraft draftUsingResource(String resourceId, Map<String, String> fingerprints) {
        return new GraphDraft(
                "bloge.visualGraphDraft.v1",
                "draft-1",
                0,
                "resourceImpact",
                "demo-tenant",
                "local",
                "browser",
                "DRAFT",
                SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "orders",
                        "resource:" + resourceId,
                        "Orders",
                        Map.of(),
                        Map.of(),
                        new GraphDraft.Position(0, 0)
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("orders", ""),
                fingerprints
        );
    }

    private static VisualGraphPublication publicationUsingResource(String resourceId) {
        return publicationUsingResource(resourceId, "fingerprint");
    }

    private static VisualGraphPublication publicationUsingResource(String resourceId, String fingerprint) {
        GraphDraft draft = draftUsingResource(resourceId, fingerprint);
        return VisualGraphPublication.design(draft, List.of(), new VisualValidationResult(true, List.of()), null)
                .withIdentity("pub-1", null);
    }

    private static ResourceDescriptor orderDescriptor() {
        return new ResourceDescriptor(
                "order-service.listOrders",
                "http://test-host:1234/api/orders",
                "GET",
                Map.of(),
                null,
                Duration.ofSeconds(3),
                ParameterMapping.empty(),
                new ResponseProtocol.HttpStatus(),
                "data"
        );
    }

    private static Map<String, Object> openApiOrderList() {
        return Map.of(
                "openapi", "3.1.0",
                "servers", List.of(Map.of("url", "https://api.example.test/v1")),
                "paths", Map.of(
                        "/orders", Map.of(
                                "get", Map.of(
                                        "operationId", "listOrders",
                                        "summary", "List orders",
                                        "tags", List.of("order"),
                                        "parameters", List.of(Map.of(
                                                "name", "userId",
                                                "in", "query",
                                                "required", true,
                                                "schema", Map.of("type", "string")
                                        )),
                                        "responses", Map.of(
                                                "200", Map.of(
                                                        "description", "ok",
                                                        "content", Map.of(
                                                                "application/json", Map.of(
                                                                        "schema", Map.of(
                                                                                "type", "object",
                                                                                "properties", Map.of(
                                                                                        "items", Map.of(
                                                                                                "type", "array",
                                                                                                "items", Map.of(
                                                                                                        "$ref",
                                                                                                        "#/components/schemas/Order"
                                                                                                )
                                                                                        )
                                                                                ),
                                                                                "required", List.of("items")
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ),
                "components", Map.of(
                        "schemas", Map.of(
                                "Order", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string")
                                        ),
                                        "required", List.of("id"),
                                        "additionalProperties", false
                                )
                        )
                )
        );
    }

    private static Map<String, Object> openApiWithUnresolvedResponseSchemaRef() {
        return Map.of(
                "openapi", "3.1.0",
                "servers", List.of(Map.of("url", "https://api.example.test/v1")),
                "paths", Map.of(
                        "/orders", Map.of(
                                "get", Map.of(
                                        "operationId", "listOrders",
                                        "summary", "List orders",
                                        "responses", Map.of(
                                                "200", Map.of(
                                                        "description", "ok",
                                                        "content", Map.of(
                                                                "application/json", Map.of(
                                                                        "schema", Map.of(
                                                                                "$ref",
                                                                                "#/components/schemas/MissingOrderList"
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ),
                "components", Map.of(
                        "schemas", Map.of(
                                "Order", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string")
                                        )
                                )
                        )
                )
        );
    }

    private static String openApiOrderListYaml() {
        return """
                openapi: 3.1.0
                servers:
                  - url: https://api.example.test/v1
                paths:
                  /orders:
                    get:
                      operationId: listOrders
                      summary: List orders
                      tags:
                        - order
                      parameters:
                        - name: userId
                          in: query
                          required: true
                          schema:
                            type: string
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  items:
                                    type: array
                                    items:
                                      type: object
                                      properties:
                                        id:
                                          type: string
                """;
    }

    private static ResourceRegistry resourceRegistry(ResourceDescriptor descriptor) {
        return new ResourceRegistry() {
            @Override
            public ResourceDescriptor resolve(String resourceId) {
                if (descriptor.resourceId().equals(resourceId)) {
                    return descriptor;
                }
                throw new ResourceNotFoundException(resourceId);
            }

            @Override
            public boolean contains(String resourceId) {
                return descriptor.resourceId().equals(resourceId);
            }

            @Override
            public Collection<ResourceDescriptor> all() {
                return List.of(descriptor);
            }
        };
    }

    private enum FailingMutation {
        UPSERT,
        DELETE
    }

    private static final class FailingResourceDesignContractRegistry extends InMemoryResourceDesignContractRegistry {
        private final FailingMutation mutation;

        private FailingResourceDesignContractRegistry(FailingMutation mutation) {
            this.mutation = mutation;
        }

        private void seed(ResourceDesignContract contract) {
            super.upsert(contract);
        }

        @Override
        public ResourceDesignContract upsert(ResourceDesignContract contract) {
            if (mutation == FailingMutation.UPSERT) {
                throw new IllegalStateException("Injected resource contract upsert failure");
            }
            return super.upsert(contract);
        }

        @Override
        public void deleteByResourceId(String resourceId) {
            if (mutation == FailingMutation.DELETE) {
                throw new IllegalStateException("Injected resource contract delete failure");
            }
            super.deleteByResourceId(resourceId);
        }
    }
}
