package com.leanowtech.bloge.gateway.testing.security;

import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves test deployment wiring does not turn visual simulation into a production boundary. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.testing.mirror.enabled=true",
                "gateway.seed-descriptors=false",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.region=region-a",
                "gateway.integration.identity.groups=resource-gateway-test-runtime-operators",
                "gateway.integration.identity.clearance=RESTRICTED",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION,TEST_TARGET_READ",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=integration-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=integration-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=integration-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=integration-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=integration-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:execution-control-boundary-non-production;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:execution-control-boundary-non-production-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
@ActiveProfiles("test")
class ExecutionControlBoundaryGuardNonProductionApplicationIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void testProfileVisualSimulationReachesMvcWithoutProductionGuard() {
        assertThat(applicationContext.getBeansOfType(VisualGraphSimulationController.class))
                .hasSize(1);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-BLOGE-Test-Inline", "test-profile-control-value");
        headers.set("X-Environment-Id", "production");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/visual/graphs/simulate",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"draft":null,"context":{"orderId":"O-1"},"outputNode":"","fixtures":{}}
                        """, headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull()
                .contains("Graph draft is required.")
                .doesNotContain("RG.PRODUCTION.");
    }
}
