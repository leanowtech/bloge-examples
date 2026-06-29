package com.leanowtech.bloge.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the BLOGE Resource Gateway example application.
 *
 * <p>This Spring Boot application demonstrates a production-grade API resource gateway
 * built on the bloge graph engine. It showcases:
 * <ul>
 *   <li>Declarative DAG orchestration of multiple upstream API calls</li>
 *   <li>A generic {@code HttpResourceOperator} driven by descriptor-based configuration</li>
 *   <li>Multi-tenant rate limiting with two-level token buckets</li>
 *   <li>Provider-scoped circuit breaking with three-state machine</li>
 *   <li>Transparent response caching with TTL-based eviction</li>
 *   <li>SSE streaming aggregation for real-time graph execution feedback</li>
 *   <li>H2-backed descriptor persistence with an admin REST API</li>
 * </ul>
 *
 * <p>The example now uses {@code bloge-spring} starter auto-configuration directly.
 * It runs in the request-response engine mode while keeping the BLOGE durable
 * API types on the classpath for starter property binding compatibility.
 *
 * <p>It still sets {@code spring.classformat.ignore=true} defensively when scanning a mixed
 * preview-bytecode workspace so the standalone module can be launched directly with
 * {@code mvn spring-boot:run} on Java 25.
 *
 * <p>Start with {@code mvn spring-boot:run} or a test harness that uses this application class.
 */
@SpringBootApplication
public class ResourceGatewayApplication {

    static {
        System.setProperty("spring.classformat.ignore", "true");
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(ResourceGatewayApplication.class, args);
    }
}
