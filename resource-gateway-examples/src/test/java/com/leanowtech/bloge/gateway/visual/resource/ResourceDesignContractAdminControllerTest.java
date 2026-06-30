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
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private ResourceDescriptor descriptor;
    private ResourceVirtualOperatorProjector projector;

    @BeforeEach
    void setUp() {
        registry = new InMemoryResourceDesignContractRegistry();
        drafts = new InMemoryGraphDraftRepository();
        objectMapper = new ObjectMapper();
        descriptor = orderDescriptor();
        projector = new ResourceVirtualOperatorProjector();
        ResourceDesignContractAdminController controller = new ResourceDesignContractAdminController(
                registry,
                new ResourceDesignContractValidator(),
                drafts,
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
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

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
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.findByResourceId("order-service.listOrders")).contains(original);
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
}
