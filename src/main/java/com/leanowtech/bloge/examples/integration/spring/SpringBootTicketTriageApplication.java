package com.leanowtech.bloge.examples.integration.spring;

import com.leanowtech.bloge.spring.autoconfigure.BlogeAutoConfiguration;
import com.leanowtech.bloge.spring.autoconfigure.BlogeEndpointAutoConfiguration;
import com.leanowtech.bloge.spring.autoconfigure.BlogeObservabilityAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Minimal Spring Boot application that demonstrates BLOGE starter integration end-to-end.
 *
 * <p>The example keeps the business flow intentionally small: Spring scans
 * {@link SpringTicketClassifierOperator} and {@link SpringReplyDraftOperator}, loads the DSL under
 * {@code classpath:bloge/integration/spring/}, and exposes the resulting graph through both a demo
 * HTTP endpoint and the starter's {@code /actuator/bloge} diagnostics.
 *
 * <p>The repository currently builds BLOGE itself to Java 25 bytecode, while Spring Boot 3.4.x still
 * ASM-scans auto-configuration candidates with Java 21-era class-file support. Excluding the starter's
 * metadata-discovered auto-configurations and importing them directly keeps the example runnable without
 * changing the main engine target level.</p>
 */
@SpringBootApplication(
        scanBasePackages = "com.leanowtech.bloge.examples.integration.spring",
        excludeName = {
                "com.leanowtech.bloge.spring.autoconfigure.BlogeAutoConfiguration",
                "com.leanowtech.bloge.spring.autoconfigure.SessionExecutorAutoConfiguration",
                "com.leanowtech.bloge.spring.autoconfigure.BlogeEndpointAutoConfiguration",
                "com.leanowtech.bloge.spring.autoconfigure.BlogeObservabilityAutoConfiguration"
        }
)
@Import({
        BlogeAutoConfiguration.class,
        BlogeEndpointAutoConfiguration.class,
        BlogeObservabilityAutoConfiguration.class
})
public class SpringBootTicketTriageApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootTicketTriageApplication.class, args);
    }
}
