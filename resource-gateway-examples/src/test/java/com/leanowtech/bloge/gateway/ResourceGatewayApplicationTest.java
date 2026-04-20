package com.leanowtech.bloge.gateway;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "spring.datasource.url=jdbc:h2:mem:resource-gateway-startup;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class ResourceGatewayApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WritableResourceRegistry registry;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @BeforeEach
    void seedDemoDescriptors() {
        registry.all().stream()
                .map(descriptor -> descriptor.resourceId())
                .toList()
                .forEach(registry::deregister);

        var properties = new GatewayProperties();
        properties.setBaseUrl("http://localhost:" + port + "/demo-upstream");
        properties.setSeedDescriptors(true);
        new ResourceDescriptorBootstrap(registry, properties).seedDescriptors();
    }

    @Test
    void contextStarts() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.containsBeanDefinition("gatewayGraphRuntimeConfiguration")).isFalse();
    }

    @Test
    void starterAutoConfigurationProvidesGatewayRuntime() {
        GraphEngine graphEngine = applicationContext.getBean(GraphEngine.class);

        assertThat(applicationContext.getBean("blogeGraphs", List.class)).isNotEmpty();
        assertThat(graphEngineField(graphEngine, "inMemorySuspendTtl", Duration.class))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void builtInDemoUpstreamMakesReadmeGatewayExamplesSucceed() {
        var dashboard = restTemplate.getForEntity("/api/gateway/dashboard/u1", Map.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(dashboard.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) dashboard.getBody().get("data"))
                .containsKeys("profile", "orders", "recommendations", "wallet", "notifications");

        var product = restTemplate.getForEntity("/api/gateway/products/p1", Map.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(product.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) product.getBody().get("data"))
                .containsEntry("productType", "physical");

        var orders = restTemplate.getForEntity("/api/gateway/orders/u1/enriched", Map.class);
        assertThat(orders.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(orders.getBody()).containsEntry("success", true);

        var credit = restTemplate.getForEntity("/api/gateway/credit-score/u1", Map.class);
        assertThat(credit.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(credit.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) credit.getBody().get("data"))
                .containsEntry("provider", "primary");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Tenant-Id", "demo-tenant");
        headers.add("X-Namespace", "local");

        var executeRequest = new HttpEntity<>(Map.of(
                "resourceId", "user-service.getProfile",
                "params", Map.of("userId", "u1")
        ), headers);

        var execute = restTemplate.postForEntity("/api/gateway/resources/execute", executeRequest, Map.class);
        assertThat(execute.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(execute.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) execute.getBody().get("data"))
                .containsEntry("resourceId", "user-service.getProfile");
        assertThat((Map<String, Object>) ((Map<String, Object>) execute.getBody().get("data")).get("payload"))
                .containsEntry("name", "Alice");
    }

    private static <T> T graphEngineField(GraphEngine graphEngine, String fieldName, Class<T> fieldType) {
        try {
            Field field = GraphEngine.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return fieldType.cast(field.get(graphEngine));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to inspect GraphEngine field '" + fieldName + "'", exception);
        }
    }
}
