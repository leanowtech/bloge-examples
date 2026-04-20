package com.leanowtech.bloge.gateway.interceptor;

import com.leanowtech.bloge.core.spi.OperatorInterceptor;
import com.leanowtech.bloge.core.spi.OperatorInvocation;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link OperatorInterceptor} that caches successful {@link HttpResourceOutput}
 * results in an in-memory TTL-based cache.
 *
 * <h3>Why the cache sits first in the interceptor chain</h3>
 * <p>This interceptor is ordered with the <em>highest</em> precedence so that a cache hit short-circuits
 * the entire downstream chain — including rate-limiter and circuit-breaker checks.
 * This means a cached response never consumes a rate-limit token and never touches
 * the circuit breaker, which is the desired behavior: cached results represent
 * previously successful calls that should be free.
 *
 * <h3>Cache key</h3>
 * <p>The key is {@code nodeId + ':' + SHA-256(input.toString())}. This ensures
 * identical calls from the same graph node produce cache hits while different
 * nodes or different inputs do not collide.
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>In-memory only — not shared across instances.</li>
 *   <li>No maximum size; in a production system you would add eviction.</li>
 *   <li>TTL is uniform; per-resource TTL would require descriptor metadata.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ResponseCacheInterceptor implements OperatorInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ResponseCacheInterceptor.class);

    private final Duration ttl;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Creates a cache interceptor with the default TTL of 60 seconds.
     */
    public ResponseCacheInterceptor() {
        this(Duration.ofSeconds(60));
    }

    /**
     * Creates a cache interceptor with a custom TTL.
     *
     * @param ttl how long cached entries remain valid
     */
    public ResponseCacheInterceptor(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Intercepts operator invocations to serve cached results when available.
     *
     * <p>Only caches successful {@link HttpResourceOutput} responses. Non-resource
     * operators pass through unintercepted.
     *
     * @param invocation the operator invocation context
     * @return the cached or freshly computed result
     * @throws Exception if the downstream operator fails
     */
    @Override
    public Object intercept(OperatorInvocation invocation) throws Exception {
        if (!(invocation.input() instanceof HttpResourceInput input)) {
            return invocation.proceed();
        }

        String nodeId = invocation.nodeId();
        String cacheKey = nodeId + ":" + sha256(input.toString());

        // Check cache
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            log.debug("Cache hit for key '{}'", cacheKey);
            return entry.value();
        }

        // Cache miss — proceed
        Object result = invocation.proceed();

        // Only cache successful responses
        if (result instanceof HttpResourceOutput output && output.success()) {
            cache.put(cacheKey, new CacheEntry(output, Instant.now().plus(ttl)));
            log.debug("Cached result for key '{}' (TTL={}s)", cacheKey, ttl.toSeconds());
        }

        return result;
    }

    /**
     * Returns the current number of entries in the cache (for testing/monitoring).
     *
     * @return cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Evicts all expired entries from the cache. Not called automatically — intended
     * for periodic cleanup via a scheduled task or admin endpoint.
     *
     * @return the number of evicted entries
     */
    public int evictExpired() {
        int[] count = {0};
        cache.entrySet().removeIf(e -> {
            boolean expired = e.getValue().isExpired();
            if (expired) count[0]++;
            return expired;
        });
        return count[0];
    }

    // ── Internal types ──────────────────────────────────────────────────

    private record CacheEntry(HttpResourceOutput value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
