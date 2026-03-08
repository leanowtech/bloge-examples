package com.leanowtech.bloge.examples.integration;

import com.leanowtech.bloge.examples.integration.spring.SpringBootTicketTriageApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Spring Boot example can execute a graph and expose BLOGE actuator endpoints.
 */
@SpringBootTest(
        classes = SpringBootTicketTriageApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.bloge.dsl-locations=classpath:bloge/integration/spring/",
                "management.endpoints.web.exposure.include=health,info,bloge",
                "spring.bloge.observability.tracing.enabled=false"
        }
)
class SpringBootTicketTriageApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void controllerExecutesStarterManagedGraph() {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.getForObject(
                "/api/bloge/tickets/triage?ticketId=T-42&message=Refund%20service%20has%20an%20urgent%20outage&customerTier=vip",
                Map.class
        );

        assertEquals("T-42", response.get("ticketId"));
        assertEquals("vip-escalation", response.get("queue"));
        assertEquals("vip-desk", response.get("owner"));
    }

    @Test
    void actuatorExposesGraphOverview() {
        restTemplate.getForObject(
                "/api/bloge/tickets/triage?ticketId=T-99&message=General%20question&customerTier=standard",
                Map.class
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> overview = restTemplate.getForObject("/actuator/bloge", Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = restTemplate.getForObject("/actuator/bloge/springTicketTriage", Map.class);

        assertTrue(((Number) overview.get("graphCount")).intValue() >= 1);
        assertEquals("springTicketTriage", detail.get("name"));
    }
}
