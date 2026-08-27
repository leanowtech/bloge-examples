package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.*;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.*;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FixtureAssetCollectionControllerTest {
    private FixtureAssetCollectionService service;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service = mock(FixtureAssetCollectionService.class);
        var audit = mock(IntegrationAccessAuditRepository.class);
        when(audit.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var auth = new IntegrationRequestAuthenticator(new Resolver(), audit);
        mvc = MockMvcBuilders.standaloneSetup(new FixtureAssetCollectionController(service, auth))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    @Test void authenticatesAndReturnsNoStoreMetadataEnvelope() throws Exception {
        var summary = summary();
        when(service.list(any(), eq(true), eq(2), eq(1), eq("resource:profile"))).thenReturn(List.of(summary));
        mvc.perform(get("/api/visual/fixture-assets").queryParam("limit", "2").queryParam("offset", "1")
                        .queryParam("operatorRef", "resource:profile")
                        .header("Authorization", "Bearer token").header("X-Purpose", "CORRECTNESS_READ"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.capabilities[0]").value("FIXTURE_ASSET_COLLECTION_READ_V1"))
                .andExpect(jsonPath("$.data[0].fixtureAssetId").value("profile"))
                .andExpect(jsonPath("$.data[0].payload").doesNotExist());
        verify(service).list(scope(), true, 2, 1, "resource:profile");
    }

    @Test void rejectsInvalidBoundsBeforeCallingService() throws Exception {
        mvc.perform(get("/api/visual/fixture-assets").queryParam("limit", "101")
                        .header("Authorization", "Bearer token").header("X-Purpose", "CORRECTNESS_READ"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get("/api/visual/fixture-assets")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private static FixtureAssetCollectionService.FixtureAssetSummary summary() {
        return new FixtureAssetCollectionService.FixtureAssetSummary("profile", 1,
                new ExactAssetRef("FIXTURE_ASSET", "profile", 1, fp('a')),
                new ExactSchemaRef("profile", 1, fp('b')), FixtureLifecycle.ACTIVE,
                "Profile", "default", "INTERNAL", 2);
    }
    private static EnterpriseScope scope() { return new EnterpriseScope("t", "o", "p", "test", "sg"); }
    private static String fp(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
    private static final class Resolver implements IntegrationIdentityResolver {
        public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
            return credential.equals("token") ? Optional.of(new IntegrationWorkloadIdentity(
                    "id", "t", "o", "p", "test", "sg", "USER", "actor", "", Set.of("CORRECTNESS_READ"), Instant.MAX, true)) : Optional.empty();
        }
        public Descriptor descriptor() { return new Descriptor("TEST", "TEST", true, true, false); }
    }
}
