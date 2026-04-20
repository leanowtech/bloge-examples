package com.leanowtech.bloge.graphengine.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring Boot properties for the graph-engine HTTP server module.
 */
@ConfigurationProperties(prefix = "spring.bloge.graph-engine.server")
public class GraphEngineServerProperties {

    private boolean migrateSchema = true;
    private String defaultEnvironment = "production";
    private int maxSseConnectionsPerTenant = 10;
    private final CompileCacheProperties compileCache = new CompileCacheProperties();

    /**
     * Returns whether the server should apply the product-layer {@code ge_*}
     * schema migrations at startup.
     *
     * @return {@code true} when graph-engine schema migration is enabled
     */
    public boolean isMigrateSchema() {
        return migrateSchema;
    }

    /**
     * Sets whether the server should apply the product-layer schema migrations at startup.
     *
     * @param migrateSchema startup migration flag
     */
    public void setMigrateSchema(boolean migrateSchema) {
        this.migrateSchema = migrateSchema;
    }

    /**
     * Returns the default deployment environment used for instance starts when
     * callers do not pin an environment explicitly.
     *
     * @return default environment label
     */
    public String getDefaultEnvironment() {
        return defaultEnvironment;
    }

    /**
     * Sets the default deployment environment used for instance starts.
     *
     * @param defaultEnvironment default environment label
     */
    public void setDefaultEnvironment(String defaultEnvironment) {
        this.defaultEnvironment = defaultEnvironment == null || defaultEnvironment.isBlank()
                ? "production"
                : defaultEnvironment;
    }

    /**
     * Returns the maximum concurrent SSE subscriptions allowed per tenant scope.
     *
     * @return positive per-tenant SSE connection limit
     */
    public int getMaxSseConnectionsPerTenant() {
        return maxSseConnectionsPerTenant;
    }

    /**
     * Sets the maximum concurrent SSE subscriptions allowed per tenant scope.
     *
     * @param maxSseConnectionsPerTenant positive per-tenant SSE connection limit
     */
    public void setMaxSseConnectionsPerTenant(int maxSseConnectionsPerTenant) {
        this.maxSseConnectionsPerTenant = maxSseConnectionsPerTenant < 1 ? 10 : maxSseConnectionsPerTenant;
    }

    /**
     * Returns the compile-result cache settings used by the embedded service facade.
     *
     * @return compile-cache settings
     */
    public CompileCacheProperties getCompileCache() {
        return compileCache;
    }

    /**
     * Nested Spring Boot properties for the in-process version compile cache.
     */
    public static final class CompileCacheProperties {
        private boolean enabled = true;
        private long maxSize = 1_000;
        private Duration ttl = Duration.ofMinutes(60);

        /**
         * Returns whether version compile-result caching is enabled.
         *
         * @return {@code true} when compile caching is enabled
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Enables or disables version compile-result caching.
         *
         * @param enabled compile-cache enabled flag
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns the maximum number of cached compile results to retain.
         *
         * @return positive cache entry bound
         */
        public long getMaxSize() {
            return maxSize;
        }

        /**
         * Sets the maximum number of cached compile results to retain.
         *
         * @param maxSize positive cache entry bound
         */
        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize < 1 ? 1_000 : maxSize;
        }

        /**
         * Returns the idle TTL applied to cached compile results.
         *
         * @return positive cache TTL
         */
        public Duration getTtl() {
            return ttl;
        }

        /**
         * Sets the idle TTL applied to cached compile results.
         *
         * @param ttl positive cache TTL
         */
        public void setTtl(Duration ttl) {
            this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(60) : ttl;
        }
    }
}
