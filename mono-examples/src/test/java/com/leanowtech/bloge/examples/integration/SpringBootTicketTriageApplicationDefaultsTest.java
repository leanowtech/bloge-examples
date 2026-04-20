package com.leanowtech.bloge.examples.integration;

import com.leanowtech.bloge.examples.integration.spring.SpringBootTicketTriageApplication;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBootTicketTriageApplicationDefaultsTest {

    @Test
    void documentedExecJavaLaunchUsesExampleSpecificDefaults() {
        Map<String, Object> defaults = SpringBootTicketTriageApplication.defaultProperties();

        assertEquals("bloge-spring-boot-example", defaults.get("spring.application.name"));
        assertEquals("classpath:bloge/integration/spring/", defaults.get("spring.bloge.dsl-locations"));
        assertEquals("5s", defaults.get("spring.bloge.default-timeout"));
        assertEquals("false", defaults.get("spring.bloge.observability.tracing.enabled"));
        assertEquals("true", defaults.get("bloge.examples.spring.auto-exit"));
        assertEquals("1s", defaults.get("bloge.examples.spring.auto-exit-delay"));
        assertEquals("health,info,bloge", defaults.get("management.endpoints.web.exposure.include"));
        assertEquals("always", defaults.get("management.endpoint.health.show-details"));
    }

    @Test
    void autoExitUsesExpectedDefaultsAndOverrides() {
        MockEnvironment defaults = new MockEnvironment();
        assertFalse(SpringBootTicketTriageApplication.autoExitEnabled(defaults));
        assertEquals(Duration.ofSeconds(1), SpringBootTicketTriageApplication.autoExitDelay(defaults));

        MockEnvironment override = new MockEnvironment()
                .withProperty("bloge.examples.spring.auto-exit", "true")
                .withProperty("bloge.examples.spring.auto-exit-delay", "250ms");
        assertTrue(SpringBootTicketTriageApplication.autoExitEnabled(override));
        assertEquals(Duration.ofMillis(250), SpringBootTicketTriageApplication.autoExitDelay(override));
    }
}
