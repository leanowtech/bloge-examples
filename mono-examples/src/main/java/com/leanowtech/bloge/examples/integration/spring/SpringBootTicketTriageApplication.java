package com.leanowtech.bloge.examples.integration.spring;

import com.leanowtech.bloge.spring.autoconfigure.BlogeAutoConfiguration;
import com.leanowtech.bloge.spring.autoconfigure.BlogeEndpointAutoConfiguration;
import com.leanowtech.bloge.spring.autoconfigure.BlogeObservabilityAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

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
 * changing the main engine target level.
 *
 * <p>The direct {@code mvn exec:java -pl bloge-examples ...} launch path also applies the example's
 * Spring properties programmatically because the companion YAML lives under a non-default resource path
 * that Spring Boot does not auto-load by itself.</p>
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

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringBootTicketTriageApplication.class);
    private static final String AUTO_EXIT_PROPERTY = "bloge.examples.spring.auto-exit";
    private static final String AUTO_EXIT_DELAY_PROPERTY = "bloge.examples.spring.auto-exit-delay";

    public static void main(String[] args) {
        ConfigurableApplicationContext context = application().run(args);
        if (autoExitEnabled(context.getEnvironment())) {
            maybeAutoExit(context);
            return;
        }
        context.registerShutdownHook();
    }

    static SpringApplication application() {
        SpringApplication application = new SpringApplication(SpringBootTicketTriageApplication.class);
        application.setDefaultProperties(defaultProperties());
        application.setRegisterShutdownHook(false);
        return application;
    }

    public static boolean autoExitEnabled(Environment environment) {
        return environment.getProperty(AUTO_EXIT_PROPERTY, Boolean.class, false);
    }

    public static Duration autoExitDelay(Environment environment) {
        return DurationStyle.detectAndParse(environment.getProperty(AUTO_EXIT_DELAY_PROPERTY, "1s"));
    }

    static void maybeAutoExit(ConfigurableApplicationContext context) {
        Duration delay = autoExitDelay(context.getEnvironment());
        LOGGER.info(
                "Spring Boot example will auto-exit after {}. Pass --{}=false to keep the server running.",
                delay,
                AUTO_EXIT_PROPERTY
        );
        Thread autoExitThread = new Thread(() -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Spring Boot example auto-exit was interrupted before shutdown completed.");
                return;
            }
            // The documented exec:java launch runs inside Maven's JVM, so we need to exit that
            // process after Spring shuts down or the command will keep waiting on lingering threads.
            System.exit(SpringApplication.exit(context));
        }, "spring-example-auto-exit");
        autoExitThread.setDaemon(true);
        autoExitThread.start();
    }

    public static Map<String, Object> defaultProperties() {
        return Map.of(
                "spring.application.name", "bloge-spring-boot-example",
                "spring.bloge.dsl-locations", "classpath:bloge/integration/spring/",
                "spring.bloge.default-timeout", "5s",
                "spring.bloge.observability.tracing.enabled", "false",
                AUTO_EXIT_PROPERTY, "true",
                AUTO_EXIT_DELAY_PROPERTY, "1s",
                "management.endpoints.web.exposure.include", "health,info,bloge",
                "management.endpoint.health.show-details", "always"
        );
    }
}
