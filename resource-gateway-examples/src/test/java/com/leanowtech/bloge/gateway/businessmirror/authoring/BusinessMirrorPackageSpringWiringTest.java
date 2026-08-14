package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "gateway.seed-descriptors=false",
                "gateway.integration.identity.tenant-id=ride-hailing",
                "gateway.integration.identity.organization-id=customer-service",
                "gateway.integration.identity.project-id=cancellation",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.region=sg",
                "gateway.integration.identity.actor-id=alice",
                "gateway.integration.identity.allowed-purposes=BUSINESS_MIRROR_AUTHORING",
                "spring.datasource.url=jdbc:h2:mem:business-mirror-package-wiring;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
class BusinessMirrorPackageSpringWiringTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private DomainCapabilityPackageAuthoringService service;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void assemblesAuthenticatedApiWithATransactionalApplicationProxy() {
        assertThat(context.getBean(DomainCapabilityPackageController.class)).isNotNull();
        assertThat(context.getBean(DomainCapabilityPackageDraftRepository.class)).isNotNull();
        assertThat(context.getBean(DomainCapabilityPackageSaveReceiptRepository.class)).isNotNull();
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }

    @Test
    void authenticatesPersistsExactlyReplaysAndListsThroughTheHttpBoundary() throws Exception {
        String body = objectMapper.writeValueAsString(BusinessMirrorAuthoringFixtures.draft(
                "cancellation-fee-http-e2e", 0, "Appeal eligibility depends on cancellation facts"));
        String key = "bm-package-http-e2e:create:v1";

        String firstBody = mockMvc.perform(post("/api/business-mirror/packages")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.result.draft.revision").value(1))
                .andExpect(jsonPath("$.result.draft.packageId").value("cancellation-fee-http-e2e"))
                .andReturn().getResponse().getContentAsString();

        String replayBody = mockMvc.perform(post("/api/business-mirror/packages")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        JsonNode first = objectMapper.readTree(firstBody);
        assertThat(objectMapper.readTree(replayBody)).isEqualTo(first);

        mockMvc.perform(get("/api/business-mirror/packages?limit=25")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].draft.packageId").value("cancellation-fee-http-e2e"));
    }
}
