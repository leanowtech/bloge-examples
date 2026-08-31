package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowModule;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowStoredDraft;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReusableFlowAuthoringControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void createUsesTrustedScopeAndReturnsExactReceiptAndEtag() throws Exception {
        ReusableFlowModule module = mock(ReusableFlowModule.class);
        ReusableFlowSaveResult saved = saved();
        when(module.save(any(AuthoringScope.class), any(String.class), any(String.class),
                any(ReusableFlowPrecondition.class), any(String.class), any(ReusableFlowCommand.class)))
                .thenReturn(saved);

        mvc(module).perform(put("/api/authoring/flows/customer-tool")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "flow-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(command())))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"flow-etag\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.flowId").value("customer-tool"))
                .andExpect(jsonPath("$.validation").value("VALID"));

        ArgumentCaptor<AuthoringScope> scope = ArgumentCaptor.forClass(AuthoringScope.class);
        ArgumentCaptor<ReusableFlowPrecondition> precondition =
                ArgumentCaptor.forClass(ReusableFlowPrecondition.class);
        verify(module).save(scope.capture(), org.mockito.ArgumentMatchers.eq("author"),
                org.mockito.ArgumentMatchers.eq("customer-tool"), precondition.capture(),
                org.mockito.ArgumentMatchers.eq("flow-create"), any());
        assertThat(scope.getValue()).isEqualTo(new AuthoringScope("tenant-a", "project-a", "test"));
        assertThat(precondition.getValue()).isInstanceOf(ReusableFlowPrecondition.Create.class);
    }

    @Test
    void updateAndHistoricalReadUseStrongValidators() throws Exception {
        ReusableFlowModule module = mock(ReusableFlowModule.class);
        ReusableFlowSaveResult saved = saved();
        when(module.save(any(AuthoringScope.class), any(String.class), any(String.class),
                any(ReusableFlowPrecondition.class), any(String.class), any(ReusableFlowCommand.class)))
                .thenReturn(saved);
        when(module.findRevisionStored(any(), any(), anyInt()))
                .thenReturn(Optional.of(new ReusableFlowStoredDraft(
                        saved.draft(), saved.receipt(), saved.strongEtag())));

        mvc(module).perform(put("/api/authoring/flows/customer-tool")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-Match", "\"prior-etag\"")
                        .header("Idempotency-Key", "flow-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(command())))
                .andExpect(status().isOk());

        mvc(module).perform(get("/api/authoring/flows/customer-tool?revision=1")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"flow-etag\""))
                .andExpect(jsonPath("$.draftId").value("draft-1"));
    }

    @Test
    void missingPreconditionAndSelfAssertedScopeDriftAreRejectedBeforeModule() throws Exception {
        ReusableFlowModule module = mock(ReusableFlowModule.class);

        mvc(module).perform(put("/api/authoring/flows/customer-tool")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("Idempotency-Key", "flow-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(command())))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.REUSABLE_FLOW.PRECONDITION_REQUIRED"));

        mvc(module).perform(put("/api/authoring/flows/customer-tool")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("X-Tenant-Id", "attacker")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "flow-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(command())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH"));
        verify(module, never()).save(any(AuthoringScope.class), any(String.class), any(String.class),
                any(ReusableFlowPrecondition.class), any(String.class), any(ReusableFlowCommand.class));
    }

    private static MockMvc mvc(ReusableFlowModule module) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", Set.of("API_RESOURCE_AUTHORING"), Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false), new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ReusableFlowAuthoringController(module, authenticator, JSON))
                .setControllerAdvice(new ReusableFlowAuthoringProblemHandler()).build();
    }

    private static ReusableFlowSaveResult saved() {
        ReusableFlowCommand.Flow flow = command().flow();
        ReusableFlowDraft draft = new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION,
                "customer-tool", "draft-1", 1, "sha256:" + "c".repeat(64), flow.displayName(),
                flow.kind(), flow.description(), flow.contract(), flow.graph(), flow.layout(),
                ReusableFlowDraft.Status.DRAFT);
        ReusableFlowSaveReceipt receipt = new ReusableFlowSaveReceipt(
                ReusableFlowSaveReceipt.SCHEMA_VERSION, draft.flowId(), draft.subject(),
                ReusableFlowSaveReceipt.Validation.VALID);
        return new ReusableFlowSaveResult(draft, receipt, "\"flow-etag\"", false);
    }

    private static ReusableFlowCommand command() {
        String fingerprint = "sha256:" + "a".repeat(64);
        ReusableFlowCommand.Node node = new ReusableFlowCommand.Node("profile", "Customer profile",
                new ReusableFlowCommand.ComposableRef.ApiResource("customer.profile", 3, fingerprint),
                List.of(new ReusableFlowCommand.Input("$.customerId",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.customerId"))));
        SchemaEnvelope input = schema("customerId");
        SchemaEnvelope output = schema("tier");
        return new ReusableFlowCommand(ReusableFlowCommand.SCHEMA_VERSION,
                new ReusableFlowCommand.Flow("Customer tool", ReusableFlowCommand.Kind.TOOL,
                        "Reusable profile lookup", new ReusableFlowCommand.Contract(input, output),
                        new ReusableFlowCommand.Graph(List.of(node),
                                new ReusableFlowCommand.Output("profile", "$")),
                        new ReusableFlowCommand.Layout(Map.of("profile",
                                new ReusableFlowCommand.Position(0, 120)))));
    }

    private static SchemaEnvelope schema(String property) {
        return SchemaEnvelope.object(Map.of(property, Map.of("type", "string")), List.of(property));
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
