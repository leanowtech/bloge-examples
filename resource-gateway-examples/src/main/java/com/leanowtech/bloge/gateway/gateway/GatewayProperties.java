package com.leanowtech.bloge.gateway.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration properties for the gateway graph/controller layer.
 *
 * <p>Bound to the {@code gateway} prefix in {@code application.yml}. The primary
 * use case is the {@code baseUrl} property that the descriptor bootstrap uses as
 * the root for all seeded resource descriptors, making it trivial to redirect
 * traffic to WireMock in integration tests.
 *
 * <h3>Example configuration</h3>
 * <pre>{@code
 * gateway:
 *   base-url: http://localhost:${server.port}/demo-upstream
 *   seed-descriptors: true
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /**
     * Base URL used by the descriptor bootstrap when constructing seeded resource
     * endpoint URLs. Defaults to the application's built-in demo upstream; override
     * with a WireMock or real upstream base URL when needed.
     */
    private String baseUrl = "http://localhost:8080/demo-upstream";

    /**
     * Whether to automatically seed or refresh the built-in resource descriptors on
     * application startup. Defaults to {@code true}. Set to {@code false} when
     * descriptors are managed manually via the admin REST API.
     */
    private boolean seedDescriptors = true;

    /**
     * Returns the base URL for seeded resource descriptors.
     *
     * @return the base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Sets the base URL for seeded resource descriptors.
     *
     * @param baseUrl the base URL
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Returns whether descriptor seeding is enabled on startup.
     *
     * @return {@code true} if seeding is enabled
     */
    public boolean isSeedDescriptors() {
        return seedDescriptors;
    }

    /**
     * Sets whether descriptor seeding is enabled on startup.
     *
     * @param seedDescriptors {@code true} to enable seeding
     */
    public void setSeedDescriptors(boolean seedDescriptors) {
        this.seedDescriptors = seedDescriptors;
    }
}
