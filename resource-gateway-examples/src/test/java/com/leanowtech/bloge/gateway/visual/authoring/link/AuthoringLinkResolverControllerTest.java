package com.leanowtech.bloge.gateway.visual.authoring.link;

import com.leanowtech.bloge.gateway.visualadapter.authoring.link.AuthoringLinkResolverController;
import com.leanowtech.bloge.gateway.visualadapter.authoring.link.AuthoringLinkResolverProblemHandler;
import com.leanowtech.bloge.gateway.visualadapter.authoring.link.AuthoringLinkResolverService;
import com.leanowtech.bloge.gateway.visualadapter.authoring.link.AuthoringLinkSourceAuthority;

import com.leanowtech.bloge.gateway.businessmirror.compilation.BuiltInGraphAssetAuthority;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthoringLinkResolverControllerTest {
    private static final String GRAPH_ID = "built-in:loanDecisionPolicy";
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void returnsVersionedReadOnlyDescriptorForExactSource() throws Exception {
        mvc(Set.of("BUSINESS_MIRROR_AUTHORING")).perform(post("/api/visual/authoring-links:resolve")
                        .header("Authorization", "Bearer link-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(GRAPH_ID, 1, FINGERPRINT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(AuthoringLinkDescriptor.SCHEMA_VERSION))
                .andExpect(jsonPath("$.resolution").value("READ_ONLY_SOURCE"))
                .andExpect(jsonPath("$.route.path").value("/author/"))
                .andExpect(jsonPath("$.route.workspace").value("v2"))
                .andExpect(jsonPath("$.route.authorMode").value("compose"))
                .andExpect(jsonPath("$.route.query.sourceId").value(GRAPH_ID))
                .andExpect(jsonPath("$.route.query['showcaseHref']").doesNotExist());
    }

    @Test
    void driftAndMissingSourceUseStableFailClosedProblems() throws Exception {
        MockMvc mvc = mvc(Set.of("BUSINESS_MIRROR_AUTHORING"));
        mvc.perform(post("/api/visual/authoring-links:resolve")
                        .header("Authorization", "Bearer link-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(GRAPH_ID, 2, FINGERPRINT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING_LINK.SOURCE_DRIFTED"));
        mvc.perform(post("/api/visual/authoring-links:resolve")
                        .header("Authorization", "Bearer link-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("built-in:missing", 1, FINGERPRINT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING_LINK.SOURCE_NOT_FOUND"));
    }

    @Test
    void wrongPurposeIsRejectedBeforeResolution() throws Exception {
        mvc(Set.of("CORRECTNESS_READ")).perform(post("/api/visual/authoring-links:resolve")
                        .header("Authorization", "Bearer link-token")
                        .header("X-Purpose", "CORRECTNESS_READ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(GRAPH_ID, 1, FINGERPRINT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
    }

    @Test
    void arbitraryReturnUrlIsRejectedWithStableRequestProblem() throws Exception {
        mvc(Set.of("BUSINESS_MIRROR_AUTHORING")).perform(post("/api/visual/authoring-links:resolve")
                        .header("Authorization", "Bearer link-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(GRAPH_ID, 1, FINGERPRINT)
                                .replace("\"route\":\"business-mirror\"",
                                        "\"route\":\"https://evil.example\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING_LINK.REQUEST_INVALID"));
    }

    @Test
    void arbitraryReturnUrlFieldIsNotAccepted() throws Exception {
        mvc(Set.of("BUSINESS_MIRROR_AUTHORING")).perform(post("/api/visual/authoring-links:resolve")
                        .header("Authorization", "Bearer link-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(GRAPH_ID, 1, FINGERPRINT)
                                .replace("\"intent\":\"EDIT_TOPOLOGY\",",
                                        "\"intent\":\"EDIT_TOPOLOGY\",\"returnUrl\":\"https://evil.example\",")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING_LINK.REQUEST_INVALID"));
    }

    private static MockMvc mvc(Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "link-client", "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "author", "", purposes, Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("link-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new AuthoringLinkResolverController(
                        new AuthoringLinkResolverService(sourceAuthority()), authenticator))
                .setControllerAdvice(new IntegrationProblemHandler(),
                        new AuthoringLinkResolverProblemHandler())
                .build();
    }

    private static AuthoringLinkSourceAuthority sourceAuthority() {
        CapabilitySnapshot.Scope scope = new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "project-a", "test", "sg");
        BuiltInGraphAssetAuthority.Snapshot source = new BuiltInGraphAssetAuthority.Snapshot(
                scope, "loanDecisionPolicy",
                ref("GRAPH_DRAFT", GRAPH_ID), ref("CONTRACT", GRAPH_ID + ":contract"),
                ref("CAPABILITY", GRAPH_ID), ref("CAPABILITY_CLOSURE", GRAPH_ID), List.of());
        return new AuthoringLinkSourceAuthority() {
            @Override
            public List<String> graphNames() {
                return List.of("loanDecisionPolicy");
            }

            @Override
            public BuiltInGraphAssetAuthority.Snapshot resolve(
                    CapabilitySnapshot.Scope requestedScope, String graphName) {
                return source;
            }
        };
    }

    private static String requestJson(String id, long revision, String fingerprint) {
        return """
                {"schemaVersion":"bloge.authoringLinkResolveRequest.v1",
                 "subjectRef":{"kind":"BUSINESS_MIRROR_LEGACY_GRAPH","id":"%s","revision":%d,"fingerprint":"%s"},
                 "intent":"EDIT_TOPOLOGY",
                 "returnCoordinate":{"route":"business-mirror","packageId":"legacy:loanDecisionPolicy","task":"capabilities","anchor":"graph:%s"}}
                """.formatted(id, revision, fingerprint, id);
    }

    private static MirrorArtifactRef ref(String kind, String id) {
        return new MirrorArtifactRef(kind, id, 1, FINGERPRINT);
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
