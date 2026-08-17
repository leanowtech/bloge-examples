package com.leanowtech.bloge.gateway.visual.reference;

import com.leanowtech.bloge.gateway.visualadapter.reference.ReferenceCandidateController;
import com.leanowtech.bloge.gateway.visualadapter.reference.ReferenceCandidateProblemHandler;

import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReferenceCandidateControllerTest {

    @Test
    void authenticatedSearchUsesTrustedScopeAndReturnsMetadataOnly() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        MockMvc mvc = mvc(provider, Set.of("CORRECTNESS_READ"));

        mvc.perform(get("/api/visual/reference-candidates")
                        .header("Authorization", "Bearer candidate-token")
                        .header("X-Purpose", "CORRECTNESS_READ")
                        .param("kind", "GRAPH")
                        .param("query", "refund"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(Page.SCHEMA_VERSION))
                .andExpect(jsonPath("$.items[0].displayName").value("Refund graph"))
                .andExpect(jsonPath("$.items[0].scope.organizationId").value("org-a"))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.items[0].schema").doesNotExist());

        assertThat(provider.lastSearch.scope()).isEqualTo(scope());
    }

    @Test
    void unauthorizedPurposeIsRejectedBeforeCatalogAccess() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        MockMvc mvc = mvc(provider, Set.of("BUSINESS_MIRROR_AUTHORING"));

        mvc.perform(get("/api/visual/reference-candidates")
                        .header("Authorization", "Bearer candidate-token")
                        .header("X-Purpose", "CORRECTNESS_READ"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        assertThat(provider.lastSearch).isNull();
    }

    @Test
    void invalidBoundsUseAStableProblemCode() throws Exception {
        MockMvc mvc = mvc(new CapturingProvider(), Set.of("CORRECTNESS_READ"));

        mvc.perform(get("/api/visual/reference-candidates")
                        .header("Authorization", "Bearer candidate-token")
                        .header("X-Purpose", "CORRECTNESS_READ")
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.REFERENCE.REQUEST_INVALID"));
    }

    @Test
    void exactResolveRebuildsScopeFromTheAuthenticatedIdentity() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        MockMvc mvc = mvc(provider, Set.of("BUSINESS_MIRROR_AUTHORING"));

        mvc.perform(post("/api/visual/reference-candidates:resolve")
                        .header("Authorization", "Bearer candidate-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":"bloge.referenceResolveCommand.v1",
                                 "kind":"GRAPH","id":"refund","revision":7,
                                 "fingerprint":"sha256:refund","intendedUse":"EDIT_TOPOLOGY"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.candidate.id").value("refund"));

        assertThat(provider.lastResolve.scope()).isEqualTo(scope());
        assertThat(provider.lastResolve.intendedUse()).isEqualTo("EDIT_TOPOLOGY");
    }

    @Test
    void malformedResolveCommandUsesTheStableReferenceProblem() throws Exception {
        MockMvc mvc = mvc(new CapturingProvider(), Set.of("CORRECTNESS_READ"));

        mvc.perform(post("/api/visual/reference-candidates:resolve")
                        .header("Authorization", "Bearer candidate-token")
                        .header("X-Purpose", "CORRECTNESS_READ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":\"unknown\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.REFERENCE.REQUEST_INVALID"));
    }

    private static MockMvc mvc(ReferenceCandidateProvider provider, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "candidate-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", purposes, Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("candidate-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ReferenceCandidateController(
                        new ReferenceCandidateService(provider), authenticator))
                .setControllerAdvice(
                        new IntegrationProblemHandler(), new ReferenceCandidateProblemHandler())
                .build();
    }

    private static ReferenceScope scope() {
        return new ReferenceScope("tenant-a", "org-a", "project-a", "test", "local");
    }

    private static ReferenceCandidate candidate() {
        return new ReferenceCandidate(
                ReferenceCandidate.SCHEMA_VERSION, "GRAPH", "refund", "Refund graph",
                "Routes refund eligibility.", 7, "sha256:refund",
                "resource-gateway://graph-drafts", scope(), ReferenceCandidate.Lifecycle.ACTIVE,
                new ReferenceCandidate.Owner("service-team", "Service Team"), List.of("refund"),
                ReferenceCandidate.Compatibility.COMPATIBLE, "");
    }

    private static final class CapturingProvider implements ReferenceCandidateProvider {
        private SearchRequest lastSearch;
        private ResolveRequest lastResolve;

        @Override
        public ProviderSnapshot snapshot(SearchRequest request) {
            lastSearch = request;
            return new ProviderSnapshot(1, List.of(candidate()));
        }

        @Override
        public ProviderResolution resolve(ResolveRequest request) {
            lastResolve = request;
            return new ProviderResolution(ResolveResult.Status.RESOLVED, candidate());
        }
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.copyOf(records);
        }
    }
}
