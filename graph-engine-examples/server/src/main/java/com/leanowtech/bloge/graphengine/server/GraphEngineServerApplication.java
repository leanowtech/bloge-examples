package com.leanowtech.bloge.graphengine.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;

/**
 * Standalone Spring Boot entry point for the graph-engine control-plane server.
 *
 * <p>The server relies on Boot auto-configuration rather than component scanning
 * so the same controller and service wiring works both as an embedded library
 * and as a standalone application. The BLOGE runtime and graph-engine modules
 * already manage their own Flyway execution, so the default Boot Flyway
 * auto-configuration is disabled here to avoid duplicate migration discovery.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
public class GraphEngineServerApplication {

    /**
     * Boots the graph-engine HTTP server.
     *
     * @param args application arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(GraphEngineServerApplication.class, args);
    }
}
