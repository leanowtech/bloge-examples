package com.leanowtech.bloge.gateway.visualadapter.authoring.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFailure;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringRead;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringRequest;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringResult;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for managing one reusable, payload-free API Connection. */
class ApiConnectionAuthoringControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void createUsesTrustedScopeAndReturnsTheCommittedPayloadFreeView() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);
        when(facade.save(any())).thenReturn(new ApiConnectionAuthoringResult(view(), "\"connection-etag\"", false));

        mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "connection-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"connection-etag\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.connectionId").value("customer"))
                .andExpect(jsonPath("$.auth.kind").value("NONE"))
                .andExpect(jsonPath("$.auth.configured").value(false));

        ArgumentCaptor<ApiConnectionAuthoringRequest> request =
                ArgumentCaptor.forClass(ApiConnectionAuthoringRequest.class);
        verify(facade).save(request.capture());
        assertThat(request.getValue().scope()).isEqualTo(new AuthoringScope("tenant-a", "project-a", "test"));
        assertThat(request.getValue().actorId()).isEqualTo("author");
        assertThat(request.getValue().precondition()).isInstanceOf(ApiConnectionAuthoringPrecondition.Create.class);
    }

    @Test
    void getReturnsTheCurrentViewAndItsStrongEtag() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);
        when(facade.read(any(), any())).thenReturn(new ApiConnectionAuthoringRead(view(), "\"connection-etag\""));

        mvc(facade).perform(get("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"connection-etag\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.connectionId").value("customer"));

        verify(facade).read(new AuthoringScope("tenant-a", "project-a", "test"), "customer");
    }

    @Test
    void listReturnsPayloadFreeConnectionsFromTheTrustedScope() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);
        when(facade.list(any())).thenReturn(List.of(
                view("alpha", "Alpha API"), view("zeta", "Zeta API")));

        mvc(facade).perform(get("/api/authoring/connections")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].connectionId").value("alpha"))
                .andExpect(jsonPath("$[1].connectionId").value("zeta"))
                .andExpect(jsonPath("$[0].auth.configured").value(false))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist());

        verify(facade).list(new AuthoringScope("tenant-a", "project-a", "test"));
    }

    @Test
    void updateAcceptsOneStrongEtagAndMarksReplay() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);
        when(facade.save(any())).thenReturn(new ApiConnectionAuthoringResult(view(), "\"connection-etag\"", true));

        mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-Match", "\"prior-etag\"")
                        .header("Idempotency-Key", "connection-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand()))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        ArgumentCaptor<ApiConnectionAuthoringRequest> request =
                ArgumentCaptor.forClass(ApiConnectionAuthoringRequest.class);
        verify(facade).save(request.capture());
        assertThat(request.getValue().precondition())
                .isEqualTo(ApiConnectionAuthoringPrecondition.matchStrongEtag("\"prior-etag\""));
    }

    @Test
    void missingPreconditionIsRejectedBeforeFacadeAccess() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);

        mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("Idempotency-Key", "connection-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_CONNECTION.PRECONDITION_REQUIRED"));

        verify(facade, never()).save(any());
    }

    @Test
    void unknownFieldsAreRejectedBeforeFacadeAccess() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);

        mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "connection-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand().replace("\"defaults\"", "\"unexpected\":true,\"defaults\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_CONNECTION.REQUEST_INVALID"));

        verify(facade, never()).save(any());
    }

    @Test
    void trustedIdentityRejectsSelfAssertedScopeDrift() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);

        mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("X-Tenant-Id", "attacker")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "connection-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH"));

        verify(facade, never()).save(any());
    }

    @Test
    void missingCredentialIsRejectedBeforeFacadeAccess() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);

        mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "connection-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer realm=\"resource-gateway-authoring\""))
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));

        verify(facade, never()).save(any());
    }

    @Test
    void unauthorizedPurposeIsRejectedBeforeFacadeAccess() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);

        mvc(facade, Set.of("OTHER_PURPOSE"))
                .perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "connection-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verify(facade, never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void malformedPreconditionsAndKeysAreRejectedBeforeFacadeAccess(
            String ifMatch, String ifNoneMatch, String key) throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);
        var request = put("/api/authoring/connections/customer")
                .header("Authorization", "Bearer author-token")
                .header("X-Purpose", "API_RESOURCE_AUTHORING")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCommand());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);

        mvc(facade).perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_CONNECTION.REQUEST_INVALID"));

        verify(facade, never()).save(any());
    }

    @Test
    void malformedJsonUsesTheConnectionProblemContract() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);

        mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "connection-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_CONNECTION.REQUEST_INVALID"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.recoveryActions").isArray());

        verify(facade, never()).save(any());
    }

    @Test
    void missingContentTypeUsesTheConnectionProblemContract() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);

        mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "connection-create")
                        .content(validCommand()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code")
                        .value("RG.AUTHORING.API_CONNECTION.CONTENT_TYPE_REQUIRED"));

        verify(facade, never()).save(any());
    }

    @Test
    void unsupportedCredentialCapabilityIsAStable424WithoutCredentialEcho() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);
        when(facade.save(any())).thenThrow(new ApiConnectionAuthoringFailure(
                ApiConnectionAuthoringFailure.Code.CAPABILITY_UNAVAILABLE));
        String secret = "never-echo-this-token";

        String body = mvc(facade).perform(put("/api/authoring/connections/customer")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "connection-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCommand().replace("{\"kind\":\"NONE\"}",
                                "{\"kind\":\"BEARER\",\"token\":{\"mode\":\"VALUE\",\"value\":\""
                                        + secret + "\"}}")))
                .andExpect(status().isFailedDependency())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_CONNECTION.CAPABILITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("connection authentication capability is unavailable"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(secret);
    }

    @Test
    void missingConnectionUsesTheSameProblemContract() throws Exception {
        ApiConnectionAuthoringFacade facade = mock(ApiConnectionAuthoringFacade.class);
        when(facade.read(any(), any())).thenThrow(new ApiConnectionAuthoringFailure(
                ApiConnectionAuthoringFailure.Code.NOT_FOUND));

        mvc(facade).perform(get("/api/authoring/connections/missing")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.API_CONNECTION.NOT_FOUND"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.recoveryActions").isArray());
    }

    private static MockMvc mvc(ApiConnectionAuthoringFacade facade) {
        return mvc(facade, Set.of("API_RESOURCE_AUTHORING"));
    }

    private static MockMvc mvc(ApiConnectionAuthoringFacade facade, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", purposes, Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false), new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ApiConnectionAuthoringController(facade, authenticator, JSON))
                .setControllerAdvice(new ApiResourceAuthoringProblemHandler())
                .build();
    }

    private static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of("W/\"weak\"", null, "key"),
                Arguments.of("\"one\", \"two\"", null, "key"),
                Arguments.of("*", null, "key"),
                Arguments.of("\"etag\"", "*", "key"),
                Arguments.of(null, "\"not-star\"", "key"),
                Arguments.of(null, "*", "has spaces"));
    }

    private static ApiConnectionView view() {
        return view("customer", "Customer API");
    }

    private static ApiConnectionView view(String connectionId, String displayName) {
        return new ApiConnectionView(ApiConnectionView.SCHEMA_VERSION, connectionId, 1,
                displayName, "https://customer.example.com", new ApiConnectionView.Auth("NONE", false),
                new ApiConnectionCommand.Defaults(5000, Map.of("Accept", "application/json")));
    }

    private static String validCommand() {
        return """
                {
                  "schemaVersion":"bloge.apiConnectionCommand.v1",
                  "displayName":"Customer API",
                  "baseUrl":"https://customer.example.com",
                  "auth":{"kind":"NONE"},
                  "defaults":{"timeoutMs":5000,"headers":{"Accept":"application/json"}}
                }
                """;
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
