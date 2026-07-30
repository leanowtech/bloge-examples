package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.AssetKind;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureMaterial;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureReceipt;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.SaveRequest;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.SourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VisualLibraryAuthoringFixtureControllerTest {

    @Test
    void saveRequiresVerifiedFixtureWritePurposeAndExactDraftRevision() throws Exception {
        AuthoringFixtureService service = mock(AuthoringFixtureService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_FIXTURE_WRITE"));

        mvc.perform(post("/admin/visual-operator-library-authoring/drafts/draft-a/fixtures")
                        .header("X-Purpose", "TEST_FIXTURE_WRITE")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));

        mvc.perform(post("/admin/visual-operator-library-authoring/drafts/draft-a/fixtures")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_WRITE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code")
                        .value("RG.AUTHORING.IF_MATCH_REQUIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void saveReturnsPayloadFreeReceiptAndPassesTrustedIdentity() throws Exception {
        AuthoringFixtureService service = mock(AuthoringFixtureService.class);
        FixtureReceipt receipt = receipt();
        when(service.save(eq("draft-a"), eq(7L), any(SaveRequest.class), any()))
                .thenReturn(receipt);
        MockMvc mvc = mvc(service, Set.of("TEST_FIXTURE_WRITE"));

        mvc.perform(post("/admin/visual-operator-library-authoring/drafts/draft-a/fixtures")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_WRITE")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.fixtureId").value("echo-golden"))
                .andExpect(jsonPath("$.payloadPersisted").value(true))
                .andExpect(jsonPath("$.payloadReturned").value(false))
                .andExpect(jsonPath("$.payload").doesNotExist());

        verify(service).save(
                eq("draft-a"),
                eq(7L),
                any(SaveRequest.class),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.tenantId().equals("tenant-a")
                                && identity.organizationId().equals("knowledge")
                                && identity.projectId().equals("support")
                                && identity.purpose().equals("TEST_FIXTURE_WRITE")));
    }

    @Test
    void exactReadRequiresReadPurposeAndReturnsAuthorizedMaterial() throws Exception {
        AuthoringFixtureService service = mock(AuthoringFixtureService.class);
        FixtureReceipt receipt = receipt();
        when(service.find(eq("echo-golden"), eq(1L), any()))
                .thenReturn(new FixtureMaterial(
                        FixtureMaterial.SCHEMA_VERSION,
                        receipt,
                        Map.of("inputs", Map.of("customerId", "demo-customer")),
                        true));
        MockMvc mvc = mvc(service, Set.of("TEST_FIXTURE_READ"));

        mvc.perform(get("/admin/visual-operator-library-authoring/fixtures/echo-golden")
                        .queryParam("revision", "1")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.fixture.fixtureId").value("echo-golden"))
                .andExpect(jsonPath("$.payload.inputs.customerId")
                        .value("demo-customer"))
                .andExpect(jsonPath("$.payloadReturned").value(true));
    }

    @Test
    void malformedAndOversizedCommandsFailBeforeTheFixtureService() throws Exception {
        AuthoringFixtureService service = mock(AuthoringFixtureService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_FIXTURE_WRITE"));

        mvc.perform(post("/admin/visual-operator-library-authoring/drafts/draft-a/fixtures")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_WRITE")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.AUTHORING.FIXTURE_PARSE_FAILED"));

        mvc.perform(post("/admin/visual-operator-library-authoring/drafts/draft-a/fixtures")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_WRITE")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schemaVersion": "bloge.visualAuthoringFixtureSaveRequest.v1",
                                  "fixtureId": "missing-classification",
                                  "expectedFixtureRevision": 0,
                                  "sourceKind": "SAMPLE",
                                  "assetKind": "OPERATOR",
                                  "assetRef": "demo:echo",
                                  "retentionDays": 7,
                                  "redactionPaths": [],
                                  "payload": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.AUTHORING.FIXTURE_REQUEST_INVALID"));

        mvc.perform(post("/admin/visual-operator-library-authoring/drafts/draft-a/fixtures")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_WRITE")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[
                                AuthoringFixtureRequestDecoder
                                        .MAXIMUM_REQUEST_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code")
                        .value("RG.AUTHORING.FIXTURE_REQUEST_LIMIT_EXCEEDED"));
        verifyNoInteractions(service);
    }

    private static MockMvc mvc(
            AuthoringFixtureService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "fixture-author",
                "tenant-a",
                "knowledge",
                "support",
                "test",
                "ap-southeast-1",
                "WORKLOAD",
                "author-a",
                "",
                purposes,
                Instant.MAX,
                true,
                Set.of("quality"),
                "CONFIDENTIAL",
                "",
                Instant.MAX);
        IntegrationRequestAuthenticator authenticator =
                new IntegrationRequestAuthenticator(
                        new StaticBearerIntegrationIdentityResolver(
                                "test-token", identity, false),
                        new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new VisualLibraryAuthoringFixtureController(
                                service, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static String saveRequest() {
        return """
                {
                  "schemaVersion": "bloge.visualAuthoringFixtureSaveRequest.v1",
                  "fixtureId": "echo-golden",
                  "expectedFixtureRevision": 0,
                  "sourceKind": "OPERATOR_TEST_CASE",
                  "assetKind": "OPERATOR",
                  "assetRef": "demo:echo",
                  "classification": "CONFIDENTIAL",
                  "retentionDays": 7,
                  "redactionPaths": ["/inputs/customer/email"],
                  "payload": {
                    "inputs": {"customerId": "demo-customer"}
                  }
                }
                """;
    }

    private static FixtureReceipt receipt() {
        Instant now = Instant.parse("2026-07-30T12:00:00Z");
        return new FixtureReceipt(
                FixtureReceipt.SCHEMA_VERSION,
                "tenant-a",
                "knowledge",
                "support",
                "test",
                "ap-southeast-1",
                "echo-golden",
                1,
                SourceKind.OPERATOR_TEST_CASE,
                AssetKind.OPERATOR,
                "demo:echo",
                "draft-a",
                7,
                fingerprint('a'),
                fingerprint('b'),
                fingerprint('c'),
                fingerprint('d'),
                "CONFIDENTIAL",
                "retention.v1",
                now.plusSeconds(86_400),
                "redaction.v1",
                List.of("/inputs/customer/email"),
                now,
                "author-a",
                true,
                false);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class RecordingAudit
            implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> values =
                new ArrayList<>();

        @Override
        public IntegrationAccessAuditRecord append(
                IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored =
                    record.withSequence(values.size() + 1L);
            values.add(stored);
            return stored;
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.copyOf(values);
        }
    }
}
