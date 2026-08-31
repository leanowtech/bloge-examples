package com.leanowtech.bloge.gateway.visualadapter.authoring.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFailure;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringRequest;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringResult;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for the first API Resource authoring entry point. */
class ApiResourceAuthoringControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void readUsesTrustedScopeAndReturnsExactResourceEtag() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);
        ApiResourceAuthoringResult result = result(false);
        when(facade.read(new AuthoringScope("tenant-a", "project-a", "test"), "profile", 2L))
                .thenReturn(result.stored());

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(get("/api/authoring/resources/profile?revision=2")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("X-Correlation-Id", "corr-01"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", "\"resource-etag\""))
                .andExpect(jsonPath("$.resourceId").value("profile"));

        verify(facade).read(new AuthoringScope("tenant-a", "project-a", "test"), "profile", 2L);
    }

    @Test
    void createUsesTrustedScopeAndReturnsCanonicalReceiptHeaders() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);
        ApiResourceAuthoringResult result = result(false);
        when(facade.save(any())).thenReturn(result);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(authoringPut()
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .header("X-Tenant-Id", "tenant-a")
                        .content(validCommand()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("ETag", "\"resource-etag\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.schemaVersion").value("bloge.apiResourceSaveReceipt.v1"))
                .andExpect(jsonPath("$.resource.resourceId").value("profile"));

        ArgumentCaptor<ApiResourceAuthoringRequest> request =
                ArgumentCaptor.forClass(ApiResourceAuthoringRequest.class);
        verify(facade).save(request.capture());
        assertThat(request.getValue().scope()).isEqualTo(new AuthoringScope("tenant-a", "project-a", "test"));
        assertThat(request.getValue().actorId()).isEqualTo("author");
        assertThat(request.getValue().resourceId()).isEqualTo("profile");
        assertThat(request.getValue().precondition()).isInstanceOf(ApiResourceAuthoringPrecondition.Create.class);
    }

    @Test
    void selfAssertedScopeDriftIsRejectedBeforeFacadeAccess() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(authoringPut()
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .header("X-Tenant-Id", "attacker")
                        .content(validCommand()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH"));

        verify(facade, never()).save(any());
    }

    @Test
    void updateAcceptsOneStrongEtagAndMarksExactReplay() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);
        ApiResourceAuthoringResult result = result(true);
        when(facade.save(any())).thenReturn(result);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(authoringPut()
                        .header("If-Match", "\"prior-etag\"")
                        .header("Idempotency-Key", "resource-update")
                        .content(validCommand()))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        ArgumentCaptor<ApiResourceAuthoringRequest> request =
                ArgumentCaptor.forClass(ApiResourceAuthoringRequest.class);
        verify(facade).save(request.capture());
        assertThat(request.getValue().precondition())
                .isEqualTo(ApiResourceAuthoringPrecondition.matchStrongEtag("\"prior-etag\""));
    }

    @Test
    void unauthorizedPurposeUsesTheAuthoringProblemContractBeforeFacadeAccess() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);

        mvc(facade, Set.of("OTHER_PURPOSE"))
                .perform(authoringPut()
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .content(validCommand()))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:bloge:problem:integration-authorization"))
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.recoveryActions").isArray())
                .andExpect(jsonPath("$.schemaVersion").doesNotExist());

        verify(facade, never()).save(any());
    }

    @Test
    void missingCredentialReturnsAuthoringChallengeBeforeFacadeAccess() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(put("/api/authoring/resources/profile")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer realm=\"resource-gateway-authoring\""))
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));

        verify(facade, never()).save(any());
    }

    @Test
    void missingPreconditionReturns428BeforeFacadeAccess() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(authoringPut()
                        .header("Idempotency-Key", "resource-create")
                        .content(validCommand()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_RESOURCE.PRECONDITION_REQUIRED"));

        verify(facade, never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void malformedPreconditionsAndKeysReturn400(String ifMatch, String ifNoneMatch, String key) throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);
        var request = authoringPut().header("Idempotency-Key", key).content(validCommand());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_RESOURCE.REQUEST_INVALID"));

        verify(facade, never()).save(any());
    }

    @Test
    void malformedJsonUsesTheSameAuthoringProblemContract() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(authoringPut()
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_RESOURCE.REQUEST_INVALID"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @ParameterizedTest
    @MethodSource("commandsWithUnknownFields")
    void unknownWireFieldsAreRejectedBeforeFacadeAccess(String body) throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(authoringPut()
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_RESOURCE.REQUEST_INVALID"));

        verify(facade, never()).save(any());
    }

    @Test
    void missingContentTypeUsesTheSameAuthoringProblemContract() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(put("/api/authoring/resources/profile")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .content(validCommand()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_RESOURCE.CONTENT_TYPE_REQUIRED"));

        verify(facade, never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("applicationFailures")
    void applicationFailuresUseStableStatuses(ApiResourceAuthoringFailure.Code code,
                                               int expectedStatus,
                                               String expectedCode) throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);
        when(facade.save(any())).thenThrow(new ApiResourceAuthoringFailure(code));

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(authoringPut()
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .content(validCommand()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    void busyFailureReturnsBoundedRetryAfter() throws Exception {
        ApiResourceAuthoringFacade facade = mock(ApiResourceAuthoringFacade.class);
        when(facade.save(any())).thenThrow(new ApiResourceAuthoringFailure(
                ApiResourceAuthoringFailure.Code.BUSY, NOW.plusSeconds(30)));

        mvc(facade, Set.of("API_RESOURCE_AUTHORING"))
                .perform(authoringPut()
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "resource-create")
                        .content(validCommand()))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_RESOURCE.BUSY"));
    }

    private static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of("W/\"weak\"", null, "key"),
                Arguments.of("\"one\", \"two\"", null, "key"),
                Arguments.of("*", null, "key"),
                Arguments.of("\"etag\"", "*", "key"),
                Arguments.of(null, "\"not-star\"", "key"),
                Arguments.of(null, "*", "has spaces")
        );
    }

    private static Stream<Arguments> applicationFailures() {
        return Stream.of(
                Arguments.of(ApiResourceAuthoringFailure.Code.VALIDATION, 422,
                        "RG.AUTHORING.API_RESOURCE.VALIDATION_FAILED"),
                Arguments.of(ApiResourceAuthoringFailure.Code.CAPABILITY_UNAVAILABLE, 424,
                        "RG.AUTHORING.API_RESOURCE.CAPABILITY_UNAVAILABLE"),
                Arguments.of(ApiResourceAuthoringFailure.Code.CONNECTION_NOT_FOUND, 404,
                        "RG.AUTHORING.API_RESOURCE.CONNECTION_NOT_FOUND"),
                Arguments.of(ApiResourceAuthoringFailure.Code.NOT_FOUND, 404,
                        "RG.AUTHORING.API_RESOURCE.NOT_FOUND"),
                Arguments.of(ApiResourceAuthoringFailure.Code.LEASE_LOST, 409,
                        "RG.AUTHORING.API_RESOURCE.LEASE_LOST"),
                Arguments.of(ApiResourceAuthoringFailure.Code.CONFLICT, 409,
                        "RG.AUTHORING.API_RESOURCE.IDEMPOTENCY_CONFLICT"),
                Arguments.of(ApiResourceAuthoringFailure.Code.CAS_MISMATCH, 412,
                        "RG.AUTHORING.API_RESOURCE.PRECONDITION_FAILED"),
                Arguments.of(ApiResourceAuthoringFailure.Code.CONNECTION_CHANGED, 412,
                        "RG.AUTHORING.API_RESOURCE.CONNECTION_CHANGED"),
                Arguments.of(ApiResourceAuthoringFailure.Code.PROJECTION_INVALID, 422,
                        "RG.AUTHORING.API_RESOURCE.PROJECTION_INVALID"),
                Arguments.of(ApiResourceAuthoringFailure.Code.INTEGRITY, 500,
                        "RG.AUTHORING.API_RESOURCE.INTEGRITY_FAILED"),
                Arguments.of(ApiResourceAuthoringFailure.Code.PERSISTENCE, 503,
                        "RG.AUTHORING.API_RESOURCE.PERSISTENCE_FAILED")
        );
    }

    private static Stream<String> commandsWithUnknownFields() {
        return Stream.of(
                validCommand().replace("\"defaultFixture\"", "\"unexpected\":true,\"defaultFixture\""),
                validCommand().replace("\"bindings\":[]", "\"bindings\":[],\"unexpected\":true")
        );
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authoringPut() {
        return put("/api/authoring/resources/profile")
                .header("Authorization", "Bearer author-token")
                .header("X-Purpose", "API_RESOURCE_AUTHORING")
                .header("X-Correlation-Id", "corr-01")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static MockMvc mvc(ApiResourceAuthoringFacade facade, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", purposes, Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ApiResourceAuthoringController(facade, authenticator, JSON))
                .setControllerAdvice(new ApiResourceAuthoringProblemHandler(
                        Clock.fixed(NOW, ZoneOffset.UTC)))
                .build();
    }

    private static ApiResourceAuthoringResult result(boolean replayed) {
        JsonNode body = JSON.createObjectNode()
                .put("schemaVersion", "bloge.apiResourceSaveReceipt.v1")
                .set("resource", JSON.createObjectNode().put("resourceId", "profile"));
        CommandReceipt receipt = mock(CommandReceipt.class);
        when(receipt.strongEtag()).thenReturn("\"resource-etag\"");
        when(receipt.body()).thenReturn(body);
        StoredApiResource stored = mock(StoredApiResource.class);
        when(stored.receipt()).thenReturn(receipt);
        when(stored.resource()).thenReturn(new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec(
                null, "profile", 2, "sha256:" + "a".repeat(64), "Customer profile", null,
                "customer", new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand.Operation(
                "GET", "/profile", List.of()),
                new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand.Contract(
                        new com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope("json-schema", "2020-12",
                                java.util.Map.of("type", "object", "properties", java.util.Map.of(),
                                        "required", List.of(), "additionalProperties", false)),
                        new com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope("json-schema", "2020-12",
                                java.util.Map.of("type", "object", "properties", java.util.Map.of(),
                                        "required", List.of(), "additionalProperties", false))),
                new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand.Response(
                        new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand.HttpStatus(
                                List.of(200)), null),
                com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand.Effect.readOnly(),
                List.of(new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand.Example(
                        "default", JSON.createObjectNode(), JSON.createObjectNode())), "DRAFT"));
        return new ApiResourceAuthoringResult(stored, replayed);
    }

    private static String validCommand() {
        return """
                {
                  "schemaVersion":"bloge.apiResourceSaveCommand.v1",
                  "connection":{"mode":"EXISTING","connectionId":"customer"},
                  "resource":{
                    "displayName":"Customer profile",
                    "operation":{"method":"GET","path":"/profile","bindings":[]},
                    "contract":{
                      "input":{"format":"json-schema","version":"2020-12","schema":{"type":"object"}},
                      "output":{"format":"json-schema","version":"2020-12","schema":{"type":"object"}}
                    },
                    "response":{"success":{"kind":"HTTP_STATUS","codes":[200]}},
                    "effect":{"kind":"READ_ONLY"},
                    "examples":[]
                  },
                  "defaultFixture":{"kind":"NONE"}
                }
                """;
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
