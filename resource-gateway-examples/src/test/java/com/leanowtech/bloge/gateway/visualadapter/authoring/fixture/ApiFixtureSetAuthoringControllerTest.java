package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFailure;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringRead;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetShareResult;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

class ApiFixtureSetAuthoringControllerTest {
    private static final FixtureSubjectRef SUBJECT = new FixtureSubjectRef.ApiResource(
            "customer.get", 1, "sha256:" + "a".repeat(64));

    @Test
    void readsOneExactRevisionUnderTrustedScope() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);
        when(facade.read(any(), any(), any())).thenReturn(
                new ApiFixtureSetAuthoringRead(view(), "\"fixture-etag\""));

        mvc(facade).perform(get("/api/authoring/fixture-sets/customer.get:r1?revision=1")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", "\"fixture-etag\""))
                .andExpect(jsonPath("$.fixtureSetId").value("customer.get:r1"))
                .andExpect(jsonPath("$.cases[0].input.customerId").value("customer-1"));

        verify(facade).read(new AuthoringScope("tenant-a", "project-a", "test"),
                "customer.get:r1", 1);
    }

    @Test
    void parentGovernedFixtureReadOmitsIndependentEditValidator() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);
        when(facade.read(any(), any(), any())).thenReturn(new ApiFixtureSetAuthoringRead(view(), null));

        mvc(facade).perform(get("/api/authoring/fixture-sets/customer.get:r1")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"));
    }

    @Test
    void listsMetadataOnlySummariesForOneExactSubject() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);
        when(facade.list(any(), any())).thenReturn(List.of(summary()));

        mvc(facade).perform(get("/api/authoring/fixture-sets")
                        .queryParam("subjectKind", "API_RESOURCE")
                        .queryParam("subjectId", "customer.get")
                        .queryParam("subjectRevision", "1")
                        .queryParam("subjectFingerprint", "sha256:" + "a".repeat(64))
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].fixtureSetId").value("customer.get:r1"))
                .andExpect(jsonPath("$[0].cases[0].caseId").value("happy"))
                .andExpect(jsonPath("$[0].cases[0].input").doesNotExist())
                .andExpect(jsonPath("$[0].cases[0].output").doesNotExist());

        ArgumentCaptor<FixtureSubjectRef> subject = ArgumentCaptor.forClass(FixtureSubjectRef.class);
        verify(facade).list(eq(new AuthoringScope("tenant-a", "project-a", "test")), subject.capture());
        assertThat(subject.getValue()).isEqualTo(SUBJECT);
    }

    @Test
    void malformedSubjectIsRejectedBeforeFacadeAccess() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);

        mvc(facade).perform(get("/api/authoring/fixture-sets")
                        .queryParam("subjectKind", "API_RESOURCE")
                        .queryParam("subjectId", "customer.get")
                        .queryParam("subjectRevision", "0")
                        .queryParam("subjectFingerprint", "sha256:bad")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.FIXTURE_SET.REQUEST_INVALID"));

        verify(facade, never()).list(any(), any());
    }

    @Test
    void missingFixtureUsesUnifiedPayloadFreeProblem() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);
        when(facade.read(any(), any(), any())).thenThrow(
                new ApiFixtureSetAuthoringFailure(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND));

        mvc(facade).perform(get("/api/authoring/fixture-sets/missing")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.FIXTURE_SET.NOT_FOUND"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void unauthorizedRequestNeverReachesFacade() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);

        mvc(facade).perform(get("/api/authoring/fixture-sets/customer.get:r1"))
                .andExpect(status().isUnauthorized());

        verify(facade, never()).read(any(), any(), any());
    }

    @Test
    void savesWholeFlowFixtureWithTrustedIdentityStrongPreconditionAndReplayReceipt() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);
        var version = com.leanowtech.bloge.gateway.visual.authoring.fixture
                .WholeFlowFixtureMaterializerTest.version();
        var command = com.leanowtech.bloge.gateway.visual.authoring.fixture
                .WholeFlowFixtureMaterializerTest.command(version.subject(),
                        com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Target.subject(),
                        com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Behavior.returned(
                                com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Material
                                        .inline(com.leanowtech.bloge.gateway.visual.authoring.fixture
                                                .WholeFlowFixtureMaterializerTest.output())), null);
        GeneratedDefaultFixture generated = new WholeFlowFixtureMaterializer()
                .generate("eligibility-cases", version, command);
        when(facade.save(any(), any(), any(), any(), any(), any())).thenReturn(
                new StandaloneFixtureSetSaveResult(generated.view(), generated.receipt(), "\"etag-1\"", false));

        mvc(facade).perform(put("/api/authoring/fixture-sets/eligibility-cases")
                        .contentType("application/json")
                        .content(new ObjectMapper().writeValueAsBytes(command))
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "fixture-create-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"etag-1\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.fixtureSetId").value("eligibility-cases"));

        verify(facade).save(new AuthoringScope("tenant-a", "project-a", "test"), "author",
                "eligibility-cases", FixtureSetPrecondition.create(), "fixture-create-1", command);
    }

    @Test
    void acceptsTheExactFlowDraftSubjectReturnedByFlowSave() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);
        var version = com.leanowtech.bloge.gateway.visual.authoring.fixture
                .WholeFlowFixtureMaterializerTest.version();
        var draft = new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION,
                version.flowId(), version.source().draftId(), version.source().revision(),
                version.source().fingerprint(), version.displayName(), version.kind(),
                version.description(), version.contract(), version.graph(),
                new ReusableFlowCommand.Layout(Map.of("decision",
                        new ReusableFlowCommand.Position(0, 0))), ReusableFlowDraft.Status.DRAFT);
        var command = com.leanowtech.bloge.gateway.visual.authoring.fixture
                .WholeFlowFixtureMaterializerTest.command(draft.subject(),
                        FixtureSetCommand.Target.subject(), FixtureSetCommand.Behavior.returned(
                                FixtureSetCommand.Material
                                        .inline(com.leanowtech.bloge.gateway.visual.authoring.fixture
                                                .WholeFlowFixtureMaterializerTest.output())), null);
        GeneratedDefaultFixture generated = new WholeFlowFixtureMaterializer()
                .generate("draft-cases", 1, draft, command);
        when(facade.save(any(), any(), any(), any(), any(), any())).thenReturn(
                new StandaloneFixtureSetSaveResult(generated.view(), generated.receipt(),
                        "\"draft-fixture-etag\"", false));

        mvc(facade).perform(put("/api/authoring/fixture-sets/draft-cases")
                        .contentType("application/json")
                        .content(new ObjectMapper().writeValueAsBytes(command))
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "draft-fixture-create"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.kind").value("FLOW_DRAFT"))
                .andExpect(jsonPath("$.subject.draftId").value(draft.draftId()));

        verify(facade).save(new AuthoringScope("tenant-a", "project-a", "test"), "author",
                "draft-cases", FixtureSetPrecondition.create(), "draft-fixture-create", command);
    }

    @Test
    void invalidPreconditionIsRejectedBeforeFixtureMaterialIsHandled() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);

        mvc(facade).perform(put("/api/authoring/fixture-sets/eligibility-cases")
                        .contentType("application/json").content("{}")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-Match", "W/\"weak\"")
                        .header("Idempotency-Key", "fixture-update-1"))
                .andExpect(status().isBadRequest());

        verify(facade, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sharesOneExactPrivateRevisionUnderDedicatedMaterialPurpose() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);
        FixtureShareCommand command = shareCommand();
        FixtureSetView pending = pendingView();
        FixtureShareReceipt receipt = new FixtureShareReceipt(FixtureShareReceipt.SCHEMA_VERSION,
                pending.fixtureSetId(), 1, pending.revision(), pending.fingerprint(),
                pending.status(), pending.statusRevision(), "review-customer-2");
        when(facade.share(any(), any(), any(), any(), any())).thenReturn(
                new StandaloneFixtureSetShareResult(pending, receipt, "\"fixture-share-etag\"", false));

        mvc(facade).perform(post("/api/authoring/fixture-sets/customer.get:r1:share")
                        .contentType("application/json")
                        .content(new ObjectMapper().writeValueAsBytes(command))
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_FIXTURE_MATERIAL_WRITE")
                        .header("If-Match", "\"fixture-source-etag\"")
                        .header("Idempotency-Key", "share-customer-fixture"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", "\"fixture-share-etag\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.schemaVersion").value(FixtureShareReceipt.SCHEMA_VERSION))
                .andExpect(jsonPath("$.status").value("SHARING_PENDING"))
                .andExpect(jsonPath("$.reviewRequestId").value("review-customer-2"))
                .andExpect(jsonPath("$.cases").doesNotExist());

        ArgumentCaptor<com.leanowtech.bloge.gateway.visual.authoring.application.fixture
                .FixtureShareIdentity> identity = ArgumentCaptor.forClass(
                        com.leanowtech.bloge.gateway.visual.authoring.application.fixture
                                .FixtureShareIdentity.class);
        verify(facade).share(identity.capture(), eq("customer.get:r1"),
                eq("\"fixture-source-etag\""), eq("share-customer-fixture"), eq(command));
        assertThat(identity.getValue().scope().tenantId()).isEqualTo("tenant-a");
        assertThat(identity.getValue().scope().projectId()).isEqualTo("project-a");
        assertThat(identity.getValue().actorId()).isEqualTo("author");
    }

    @Test
    void shareRejectsWeakPreconditionBeforeFacadeAccess() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);

        mvc(facade).perform(post("/api/authoring/fixture-sets/customer.get:r1:share")
                        .contentType("application/json")
                        .content(new ObjectMapper().writeValueAsBytes(shareCommand()))
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_FIXTURE_MATERIAL_WRITE")
                        .header("If-Match", "W/\"weak\"")
                        .header("Idempotency-Key", "share-customer-fixture"))
                .andExpect(status().isBadRequest());

        verify(facade, never()).share(any(), any(), any(), any(), any());
    }

    @Test
    void ordinaryAuthoringPurposeCannotShareProtectedMaterial() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);

        mvc(facade).perform(post("/api/authoring/fixture-sets/customer.get:r1:share")
                        .contentType("application/json")
                        .content(new ObjectMapper().writeValueAsBytes(shareCommand()))
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-Match", "\"fixture-source-etag\"")
                        .header("Idempotency-Key", "share-customer-fixture"))
                .andExpect(status().isForbidden());

        verify(facade, never()).share(any(), any(), any(), any(), any());
    }

    private static MockMvc mvc(ApiFixtureSetAuthoringFacade facade) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", Set.of(
                        "API_RESOURCE_AUTHORING", "CORRECTNESS_FIXTURE_MATERIAL_WRITE"),
                Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ApiFixtureSetAuthoringController(facade, authenticator))
                .setControllerAdvice(new ApiResourceAuthoringProblemHandler())
                .build();
    }

    private static FixtureSetView view() {
        return new FixtureSetView(FixtureSetView.SCHEMA_VERSION, "customer.get:r1", 1,
                "sha256:" + "b".repeat(64), 1, "Customer defaults", SUBJECT,
                List.of(new com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Case(
                        "happy", "Happy", new ObjectMapper().createObjectNode()
                        .put("customerId", "customer-1"), List.of(), null)),
                FixtureSetView.Status.PRIVATE_DRAFT);
    }

    private static FixtureSetView pendingView() {
        FixtureSetView source = view();
        return new FixtureSetView(source.schemaVersion(), source.fixtureSetId(), 2,
                "sha256:" + "c".repeat(64), 2, source.displayName(), source.subject(),
                source.cases(), FixtureSetView.Status.SHARING_PENDING);
    }

    private static FixtureShareCommand shareCommand() {
        return new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source("customer.get:r1", 1,
                        "sha256:" + "b".repeat(64), 1),
                new FixtureShareCommand.Policy("CONFIDENTIAL", 30,
                        new FixtureShareCommand.Redaction("default-v1", List.of("/customer/email"))));
    }

    private static FixtureSetSummary summary() {
        return new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION, "customer.get:r1", 1,
                "sha256:" + "b".repeat(64), "Customer defaults", SUBJECT,
                List.of(new FixtureSetSummary.CaseSummary("happy", "Happy")),
                FixtureSetView.Status.PRIVATE_DRAFT, 1);
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

        @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }

        @Override public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.copyOf(records);
        }
    }
}
