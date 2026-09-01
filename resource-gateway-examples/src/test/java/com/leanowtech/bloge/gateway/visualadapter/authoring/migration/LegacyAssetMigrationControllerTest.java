package com.leanowtech.bloge.gateway.visualadapter.authoring.migration;

import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyApiResourceReauthorPreview;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationFailure;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationModule;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyAssetMigrationControllerTest {

    @Test
    void returnsOneAuthenticatedTransportRedactedResourcePreview() throws Exception {
        LegacyAssetMigrationModule module = mock(LegacyAssetMigrationModule.class);
        when(module.previewResource("customer.get")).thenReturn(preview());

        mvc(module).perform(get("/api/authoring/migrations/legacy-assets/resources/customer.get:preview")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.source.resourceId").value("customer.get"))
                .andExpect(jsonPath("$.suggestedResource.operation.path").value("/customers/{customerId}"))
                .andExpect(jsonPath("$.suggestedResource.examples[0].input.customerId").value("customer-1"))
                .andExpect(jsonPath("$.connectionId").doesNotExist())
                .andExpect(jsonPath("$.urlTemplate").doesNotExist())
                .andExpect(jsonPath("$.defaultHeaders").doesNotExist())
                .andExpect(jsonPath("$.authStrategy").doesNotExist());

        verify(module).previewResource("customer.get");
    }

    @Test
    void reportsAnUnrepresentableLegacyResourceWithoutLeakingItsShape() throws Exception {
        LegacyAssetMigrationModule module = mock(LegacyAssetMigrationModule.class);
        when(module.previewResource("unsafe.get")).thenThrow(
                new LegacyAssetMigrationFailure(LegacyAssetMigrationFailure.Code.NEEDS_REPAIR));

        mvc(module).perform(get("/api/authoring/migrations/legacy-assets/resources/unsafe.get:preview")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.LEGACY_MIGRATION.NEEDS_REPAIR"))
                .andExpect(jsonPath("$.urlTemplate").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void returnsOnlyTheTrustedScopesPayloadFreeInventory() throws Exception {
        LegacyAssetMigrationModule module = mock(LegacyAssetMigrationModule.class);
        AuthoringScope scope = new AuthoringScope("tenant-a", "project-a", "test");
        when(module.inventory(scope)).thenReturn(inventory());

        mvc(module).perform(get("/api/authoring/migrations/legacy-assets")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.summary.needsRepair").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("orders.list"))
                .andExpect(jsonPath("$.items[0].reasonCodes[0]").value("DESIGN_CONTRACT_MISSING"))
                .andExpect(jsonPath("$.items[0].urlTemplate").doesNotExist())
                .andExpect(jsonPath("$.items[0].fixturePayload").doesNotExist());

        verify(module).inventory(scope);
    }

    @Test
    void rejectsAnUnauthenticatedInventoryReadBeforeModuleAccess() throws Exception {
        LegacyAssetMigrationModule module = mock(LegacyAssetMigrationModule.class);

        mvc(module).perform(get("/api/authoring/migrations/legacy-assets")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(module, never()).inventory(org.mockito.ArgumentMatchers.any());
    }

    private static MockMvc mvc(LegacyAssetMigrationModule module) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", Set.of("API_RESOURCE_AUTHORING"), Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false), new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new LegacyAssetMigrationController(module, authenticator))
                .setControllerAdvice(new LegacyAssetMigrationProblemHandler()).build();
    }

    private static LegacyAssetMigrationInventory inventory() {
        LegacyAssetMigrationInventory.Item item = new LegacyAssetMigrationInventory.Item(
                LegacyAssetMigrationInventory.Kind.API_RESOURCE, "orders.list", 0, "Orders",
                LegacyAssetMigrationInventory.Status.NEEDS_REPAIR, 0,
                List.of("DESIGN_CONTRACT_MISSING"), new LegacyAssetMigrationInventory.Action(
                        LegacyAssetMigrationInventory.ActionKind.REPAIR_SOURCE, "/capabilities/"));
        return new LegacyAssetMigrationInventory(null,
                new LegacyAssetMigrationInventory.Summary(1, 0, 1, 0), List.of(item));
    }

    private static LegacyApiResourceReauthorPreview preview() {
        SchemaEnvelope input = SchemaEnvelope.object(Map.of("customerId", Map.of("type", "string")),
                List.of("customerId"));
        SchemaEnvelope output = SchemaEnvelope.object(Map.of("name", Map.of("type", "string")), List.of("name"));
        ApiResourceCommand command = new ApiResourceCommand("Customer", null,
                new ApiResourceCommand.Operation("GET", "/customers/{customerId}", List.of(
                        new ApiResourceCommand.Binding("$.customerId",
                                new ApiResourceCommand.Location("PATH", "customerId")))),
                new ApiResourceCommand.Contract(input, output),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.readOnly(), List.of(new ApiResourceCommand.Example(
                "legacy-example", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("customerId", "customer-1"),
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("name", "Ada"))));
        return new LegacyApiResourceReauthorPreview(null,
                new LegacyApiResourceReauthorPreview.Source("API_RESOURCE", "customer.get", 0), command,
                List.of(new LegacyApiResourceReauthorPreview.Diagnostic("CONNECTION_SELECTION_REQUIRED",
                        "Choose a committed Connection before saving this Resource.")));
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

        @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }

        @Override public List<IntegrationAccessAuditRecord> recent(int limit) { return List.copyOf(records); }
    }
}
