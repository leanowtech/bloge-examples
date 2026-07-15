package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.integration.IntegrationCapabilities;
import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** Full application proof for profile-gated testing control-plane assembly. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "gateway.seed-descriptors=false",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION,TEST_FIXTURE_READ,TEST_FIXTURE_WRITE",
                "spring.datasource.url=jdbc:h2:mem:testing-app-main;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:testing-app-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class TestRuntimeApplicationIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void realApplicationAdvertisesAndServesTheProfileGatedTargetProtocol() throws Exception {
        assertThat(context.getBeansOfType(TestExecutionController.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestRunRepository.class)).hasSize(1);

        var capabilities = restTemplate.exchange("/api/integration/capabilities", HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<IntegrationEnvelope<IntegrationCapabilities>>() { });
        assertThat(capabilities.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capabilities.getBody()).isNotNull();
        assertThat(capabilities.getBody().payload().testability().executionEndpointEnabled()).isTrue();
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/graphs/{graphName}"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("bloge-aneke-demo-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        var target = restTemplate.exchange("/api/testing/targets/graphs/loanDecisionPolicy",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(target.getStatusCode())
                .withFailMessage("target discovery failed: %s", target.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(target.getBody()).isNotNull();
        TestGraphTargetDescriptor descriptor = objectMapper.treeToValue(
                target.getBody(), TestGraphTargetDescriptor.class);
        assertThat(descriptor.target().id()).isEqualTo("loanDecisionPolicy");
        assertThat(descriptor.target().fingerprint()).startsWith("sha256:");
        assertThat(descriptor.contract().inputSchema().schema()).isNotEmpty();
        assertThat(descriptor.certificationEligible()).isTrue();
    }
}
