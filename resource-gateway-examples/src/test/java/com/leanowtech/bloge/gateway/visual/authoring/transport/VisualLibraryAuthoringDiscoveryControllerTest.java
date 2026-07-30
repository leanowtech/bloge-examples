package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.AuthoringDocumentProjector;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.AuthoringFactProjectionService;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.CoreFrameworkFunctionInventoryProvider;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.FrameworkFunctionInventory;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.RuntimeParityService;
import com.leanowtech.bloge.gateway.visual.catalog.AsyncApiOperatorLibraryImporter;
import com.leanowtech.bloge.gateway.visual.catalog.CapabilityCatalogVisualAdapter;
import com.leanowtech.bloge.gateway.visual.catalog.JavaOperatorInventoryProjector;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImporter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VisualLibraryAuthoringDiscoveryControllerTest {

    private ObjectMapper mapper;
    private MockMvc mockMvc;
    private AuthoringFactProjectionService service;
    private VisualLibraryAuthoringDiscoveryController controller;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        OperatorLibrary emptyLibrary = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "empty-discovery-catalog",
                "Empty discovery catalog",
                "1.0.0",
                "test",
                OperatorLibrary.STATUS_ACTIVE,
                List.of());
        DslImportService dsl = new DslImportService(
                VisualCatalogTestSupport.catalogWithLibrary(emptyLibrary),
                new OperatorLibraryValidator());
        RuntimeParityService parity = new RuntimeParityService(
                JavaOperatorInventoryProjector.forRegistry(null),
                new FrameworkFunctionInventory(
                        List.of(new CoreFrameworkFunctionInventoryProvider())));
        service = new AuthoringFactProjectionService(
                new CapabilityCatalogVisualAdapter(),
                new AsyncApiOperatorLibraryImporter(),
                new OpenApiResourceDesignContractImporter(),
                new ResourceVirtualOperatorProjector(),
                dsl,
                parity,
                new AuthoringDocumentProjector(mapper),
                mapper);
        controller = new VisualLibraryAuthoringDiscoveryController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exposesRuntimeFactsWithoutInventingCoreFunctionSignatures() throws Exception {
        mockMvc.perform(get("/admin/visual-operator-library-authoring/discovery/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value("bloge.visualAuthoringFactProjection.v1"))
                .andExpect(jsonPath("$.sourceKind").value("RUNTIME_INVENTORY"))
                .andExpect(jsonPath("$.sourceFingerprint").isNotEmpty())
                .andExpect(jsonPath("$.projectionFingerprint").isNotEmpty())
                .andExpect(jsonPath("$.summary.functionFactCount").isNumber())
                .andExpect(jsonPath("$.summary.runtimeReady").value(false))
                .andExpect(jsonPath("$.authoringDocument").doesNotExist())
                .andExpect(jsonPath("$.reviewItems[?(@.code == 'RG.AUTHORING.RUNTIME_SIGNATURES_REQUIRED')]")
                        .exists());
    }

    @Test
    void adaptsCapabilityCatalogAndPreservesRuntimeUncertainty() throws Exception {
        Map<String, Object> request = Map.of(
                "sourceId", "support-capabilities.yaml",
                "catalog", Map.of(
                        "schemaVersion", "bloge.capabilityCatalog.v1",
                        "catalogId", "support-capabilities",
                        "displayName", "Support capabilities",
                        "blogeVersion", "1.0.0",
                        "functions", List.of(Map.of(
                                "name", "support.normalize",
                                "signatures", List.of(Map.of(
                                        "label", "support.normalize(value)",
                                        "parameters", List.of(Map.of(
                                                "name", "value",
                                                "type", "string")),
                                        "returns", Map.of("type", "string")))))));

        mockMvc.perform(post("/admin/visual-operator-library-authoring/discovery/capability-catalog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceKind").value("CAPABILITY_CATALOG"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.summary.functionFactCount").value(1))
                .andExpect(jsonPath("$.runtimeParity[0].state").value("DOCUMENTED_ONLY"))
                .andExpect(jsonPath("$.authoringDocument.library.id").value("support-capabilities"))
                .andExpect(jsonPath("$.authoringDocument.functions['support.normalize']").exists());
    }

    @Test
    void adaptsAsyncApiAndOpenApiIntoTheSameProjectionProtocol() throws Exception {
        Map<String, Object> asyncRequest = Map.of(
                "libraryId", "support-events",
                "owner", "support-platform",
                "asyncApiText", """
                        asyncapi: '2.6.0'
                        info:
                          title: Support Events
                          version: 1.0.0
                        channels:
                          support.ticket.created:
                            subscribe:
                              operationId: receiveTicketCreated
                              message:
                                name: TicketCreated
                                payload:
                                  type: object
                                  properties:
                                    ticketId: {type: string}
                                  required: [ticketId]
                        """);

        mockMvc.perform(post("/admin/visual-operator-library-authoring/discovery/asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(asyncRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceKind").value("ASYNC_API"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.summary.operatorFactCount").value(1))
                .andExpect(jsonPath("$.authoringDocument.operators").isNotEmpty());

        Map<String, Object> openApiRequest = Map.of(
                "resourceId", "support.getTicket",
                "operationId", "getTicket",
                "openApiText", """
                        openapi: 3.0.3
                        info:
                          title: Support API
                          version: 1.0.0
                        servers:
                          - url: https://support.example.test
                        paths:
                          /tickets/{ticketId}:
                            get:
                              operationId: getTicket
                              parameters:
                                - in: path
                                  name: ticketId
                                  required: true
                                  schema: {type: string}
                              responses:
                                '200':
                                  description: Ticket
                                  content:
                                    application/json:
                                      schema:
                                        type: object
                                        properties:
                                          ticketId: {type: string}
                                          status: {type: string}
                                        required: [ticketId, status]
                        """);

        mockMvc.perform(post("/admin/visual-operator-library-authoring/discovery/openapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(openApiRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceKind").value("OPEN_API"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.summary.operatorFactCount").value(1))
                .andExpect(jsonPath("$.authoringDocument.operators['resource:support.getTicket']")
                        .exists());
    }

    @Test
    void projectsDslTopologyEvenWhenReusableContractsAreMissing() throws Exception {
        Map<String, Object> request = Map.of(
                "sourceId", "support-normalize.bloge",
                "dsl", """
                        graph supportNormalize {
                          node classify : "support:classify-ticket" {
                            input {
                              ticketId = ctx.ticketId
                            }
                          }
                          transform response {
                            value = coalesce(classify.output.route, "unknown")
                          }
                        }
                        """,
                "mode", "preview");

        mockMvc.perform(post("/admin/visual-operator-library-authoring/discovery/dsl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceKind").value("DSL"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.summary.graphFactCount").value(1))
                .andExpect(jsonPath("$.facts[?(@.assetKind == 'GRAPH')]").exists())
                .andExpect(jsonPath("$.runtimeParity[?(@.assetRef == 'coalesce')].state")
                        .value("RUNTIME_DISCOVERED"))
                .andExpect(jsonPath(
                                "$.reviewItems[?(@.code == 'RG.AUTHORING.DISCOVERY_DSL_TOPOLOGY_ONLY')]")
                        .exists())
                .andExpect(jsonPath("$.authoringDocument").doesNotExist());
    }

    @Test
    void rejectsOversizedSynchronousSourcesWithAStableProblem() {
        assertThatThrownBy(() -> service.capabilityCatalog(
                "oversized.json",
                Map.of("padding", "x".repeat(
                        AuthoringFactProjectionService.MAXIMUM_SOURCE_BYTES))))
                .isInstanceOf(AuthoringFactProjectionService.SourceLimitExceededException.class)
                .hasMessageContaining("10485760");

        var response = controller.sourceLimitExceeded(
                new AuthoringFactProjectionService.SourceLimitExceededException(
                        AuthoringFactProjectionService.MAXIMUM_SOURCE_BYTES));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("RG.AUTHORING.DISCOVERY_SOURCE_LIMIT_EXCEEDED");
    }
}
