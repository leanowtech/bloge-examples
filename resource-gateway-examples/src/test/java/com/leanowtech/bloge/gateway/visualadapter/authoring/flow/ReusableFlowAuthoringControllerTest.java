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
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishResult;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowStoredDraft;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void dslMediaTypeProjectsAndSavesThroughTheSameCanonicalModule() throws Exception {
        ReusableFlowModule module = mock(ReusableFlowModule.class);
        when(module.save(any(AuthoringScope.class), any(String.class), any(String.class),
                any(ReusableFlowPrecondition.class), any(String.class), any(ReusableFlowCommand.class)))
                .thenReturn(saved());
        ReusableFlowDslCommand dslCommand = new ReusableFlowDslCommand(ReusableFlowDslCommand.SCHEMA_VERSION,
                "Customer profile tool",
                ReusableFlowCommand.Kind.TOOL, "Returns one customer profile.",
                new ReusableFlowDslCommand.Source("customer-profile.bloge", """
                        graph customerProfile {
                          input { customerId: String }
                          output { customerId: String }
                          node profile : "resource:customer-profile" {
                            input { customerId = ctx.customerId }
                          }
                        }
                        """),
                Map.of("resource:customer-profile", new ReusableFlowCommand.ComposableRef.ApiResource(
                        "customer-profile", 1, "sha256:" + "a".repeat(64))));

        mvc(module).perform(put("/api/authoring/flows/customer-tool")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "flow-dsl-create")
                        .contentType(MediaType.valueOf("application/vnd.bloge.reusable-flow-dsl+json"))
                        .content(JSON.writeValueAsString(dslCommand)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"flow-etag\""));

        ArgumentCaptor<ReusableFlowCommand> command = ArgumentCaptor.forClass(ReusableFlowCommand.class);
        verify(module).save(any(), org.mockito.ArgumentMatchers.eq("author"),
                org.mockito.ArgumentMatchers.eq("customer-tool"), any(ReusableFlowPrecondition.class),
                org.mockito.ArgumentMatchers.eq("flow-dsl-create"), command.capture());
        assertThat(command.getValue().flow().graph().nodes()).singleElement()
                .satisfies(node -> {
                    assertThat(node.nodeId()).isEqualTo("profile");
                    assertThat(node.use()).isEqualTo(dslCommand.dependencyPins().get(
                            "resource:customer-profile"));
                });
    }

    @Test
    void dslMediaTypeRejectsSchemaInvalidWireBeforeTheCanonicalModule() throws Exception {
        ReusableFlowModule module = mock(ReusableFlowModule.class);
        when(module.save(any(AuthoringScope.class), any(String.class), any(String.class),
                any(ReusableFlowPrecondition.class), any(String.class), any(ReusableFlowCommand.class)))
                .thenReturn(saved());
        String invalid = """
                {
                  "displayName": "Customer profile tool",
                  "kind": "TOOL",
                  "description": "Returns one customer profile.",
                  "source": {"dsl": "graph empty { output { value: String } }"},
                  "dependencyPins": {"bad key!": {"kind": "API_RESOURCE", "resourceId": "profile",
                    "revision": 1, "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}
                }
                """;

        mvc(module).perform(put("/api/authoring/flows/customer-tool")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "flow-invalid-dsl")
                        .contentType(MediaType.valueOf("application/vnd.bloge.reusable-flow-dsl+json"))
                        .content(invalid))
                .andExpect(status().isBadRequest());

        verify(module, never()).save(any(AuthoringScope.class), any(String.class), any(String.class),
                any(ReusableFlowPrecondition.class), any(String.class), any(ReusableFlowCommand.class));
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

    @Test
    void publishUsesTrustedScopeAndReturnsExactImmutableCoordinate() throws Exception {
        ReusableFlowModule module = mock(ReusableFlowModule.class);
        ReusableFlowSaveResult saved = saved();
        ReusableFlowVersion version = new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION,
                "publication-tool", 1, "sha256:" + "d".repeat(64),
                new ReusableFlowVersion.Source(saved.draft().draftId(), 1, saved.draft().fingerprint()),
                saved.draft().flowId(), saved.draft().displayName(), saved.draft().kind(),
                saved.draft().description(), saved.draft().contract(), saved.draft().graph(),
                Instant.parse("2026-09-01T00:00:00Z"), "author", ReusableFlowVersion.Status.PUBLISHED);
        ReusableFlowPublishCommand command = new ReusableFlowPublishCommand(null, saved.draft().subject());
        ReusableFlowPublishReceipt receipt = new ReusableFlowPublishReceipt(null, saved.draft().subject(),
                version.subject(), ReusableFlowPublishReceipt.Catalog.AVAILABLE);
        when(module.publish(any(), any(), any(), any(), any())).thenReturn(
                new ReusableFlowPublishResult(version, receipt, false));

        mvc(module).perform(post("/api/authoring/flows/customer-tool:publish")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("Idempotency-Key", "publish-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(command)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.source.draftId").value("draft-1"))
                .andExpect(jsonPath("$.version.publicationId").value("publication-tool"))
                .andExpect(jsonPath("$.catalog").value("AVAILABLE"));

        verify(module).publish(org.mockito.ArgumentMatchers.eq(
                        new AuthoringScope("tenant-a", "project-a", "test")),
                org.mockito.ArgumentMatchers.eq("author"),
                org.mockito.ArgumentMatchers.eq("customer-tool"),
                org.mockito.ArgumentMatchers.eq("publish-1"),
                org.mockito.ArgumentMatchers.eq(command));
    }

    @Test
    void latestVersionReadUsesTrustedScopeAndReturnsImmutableAuthority() throws Exception {
        ReusableFlowModule module = mock(ReusableFlowModule.class);
        ReusableFlowSaveResult saved = saved();
        ReusableFlowVersion version = new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION,
                "publication-tool", 2, "sha256:" + "d".repeat(64),
                new ReusableFlowVersion.Source(saved.draft().draftId(), 1, saved.draft().fingerprint()),
                saved.draft().flowId(), saved.draft().displayName(), saved.draft().kind(),
                saved.draft().description(), saved.draft().contract(), saved.draft().graph(),
                Instant.parse("2026-09-01T00:00:00Z"), "author", ReusableFlowVersion.Status.PUBLISHED);
        when(module.findLatestVersion(any(), org.mockito.ArgumentMatchers.eq("customer-tool")))
                .thenReturn(Optional.of(version));

        mvc(module).perform(get("/api/authoring/flows/customer-tool/versions/latest")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.publicationId").value("publication-tool"))
                .andExpect(jsonPath("$.revision").value(2));

        verify(module).findLatestVersion(
                org.mockito.ArgumentMatchers.eq(new AuthoringScope("tenant-a", "project-a", "test")),
                org.mockito.ArgumentMatchers.eq("customer-tool"));
    }

    private static MockMvc mvc(ReusableFlowModule module) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", Set.of("API_RESOURCE_AUTHORING"), Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false), new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ReusableFlowAuthoringController(
                        module, authenticator, JSON, dslProjector()))
                .setControllerAdvice(new ReusableFlowAuthoringProblemHandler()).build();
    }

    private static ReusableFlowDslProjector dslProjector() {
        VisualOperatorCatalog emptyCatalog = new VisualOperatorCatalog() {
            @Override public List<OperatorDefinition> list(OperatorCatalogQuery query) { return List.of(); }
            @Override public Optional<OperatorDefinition> find(String operatorRef) { return Optional.empty(); }
        };
        return new ReusableFlowDslProjector(
                new DslImportService(emptyCatalog, new OperatorLibraryValidator()));
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
