package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFailure;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiFixtureSetAuthoringControllerTest {
    private static final FixtureSubjectRef SUBJECT = new FixtureSubjectRef.ApiResource(
            "customer.get", 1, "sha256:" + "a".repeat(64));

    @Test
    void readsOneExactRevisionUnderTrustedScope() throws Exception {
        ApiFixtureSetAuthoringFacade facade = mock(ApiFixtureSetAuthoringFacade.class);
        when(facade.read(any(), any(), any())).thenReturn(view());

        mvc(facade).perform(get("/api/authoring/fixture-sets/customer.get:r1?revision=1")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.fixtureSetId").value("customer.get:r1"))
                .andExpect(jsonPath("$.cases[0].input.customerId").value("customer-1"));

        verify(facade).read(new AuthoringScope("tenant-a", "project-a", "test"),
                "customer.get:r1", 1);
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

    private static MockMvc mvc(ApiFixtureSetAuthoringFacade facade) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", Set.of("API_RESOURCE_AUTHORING"), Instant.MAX, true,
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
