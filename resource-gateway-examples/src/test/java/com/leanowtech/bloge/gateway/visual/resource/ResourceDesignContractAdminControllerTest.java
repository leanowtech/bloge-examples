package com.leanowtech.bloge.gateway.visual.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

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
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new InMemoryResourceDesignContractRegistry();
        drafts = new InMemoryGraphDraftRepository();
        objectMapper = new ObjectMapper();
        ResourceDesignContractAdminController controller = new ResourceDesignContractAdminController(
                registry,
                new ResourceDesignContractValidator(),
                drafts
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
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(contract);
    }

    @Test
    void deleteForceBypassesStoredDraftReferenceGuard() throws Exception {
        registry.upsert(validContract(Map.of()));
        drafts.save(draftUsingResource("order-service.listOrders"));

        mockMvc.perform(delete("/admin/resource-design-contracts/order-service.listOrders")
                        .param("force", "true"))
                .andExpect(status().isNoContent());

        assertThat(registry.findByResourceId("order-service.listOrders")).isEmpty();
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

    private static SchemaEnvelope requestSchema() {
        return SchemaEnvelope.object(Map.of(
                "userId", Map.of("type", "string")
        ), List.of("userId"));
    }

    private static GraphDraft draftUsingResource(String resourceId) {
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
                Map.of("orders", "fingerprint")
        );
    }
}
