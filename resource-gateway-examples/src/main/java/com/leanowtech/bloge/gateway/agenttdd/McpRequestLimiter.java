package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/**
 * Bounded, per-identity MCP admission control applied before tool dispatch.
 *
 * <p>The limiter owns a common request bucket, a DSL reference bucket and a shared preview/gate
 * bucket. Preview and gate additionally share a concurrency semaphore. Identity keys are hashed
 * before storage so operational state does not retain tenant or actor labels.</p>
 */
@Component
public final class McpRequestLimiter {
    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_BUCKETS = 30_000;
    private static final int MAX_TRACKED_AUTHORING_IDENTITIES = 10_000;
    private final int commonPerWindow;
    private final int referencePerWindow;
    private final int authoringPerWindow;
    private final int authoringConcurrency;
    private final LongSupplier nanoTime;
    private final AgentTddAuthoringTelemetry telemetry;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> authoringPermits = new ConcurrentHashMap<>();

    /** Creates a limiter with inert telemetry for transport-neutral embedders. */
    public McpRequestLimiter(
            @Value("${gateway.agent-tdd.mcp-limits.common-per-minute:120}") int commonPerWindow,
            @Value("${gateway.agent-tdd.mcp-limits.reference-per-minute:60}") int referencePerWindow,
            @Value("${gateway.agent-tdd.mcp-limits.authoring-per-minute:30}") int authoringPerWindow,
            @Value("${gateway.agent-tdd.mcp-limits.authoring-concurrency:4}") int authoringConcurrency) {
        this(commonPerWindow, referencePerWindow, authoringPerWindow, authoringConcurrency,
                System::nanoTime, AgentTddAuthoringTelemetry.noop());
    }

    /** Creates the configured application limiter with payload-free rejection telemetry. */
    @Autowired
    public McpRequestLimiter(
            @Value("${gateway.agent-tdd.mcp-limits.common-per-minute:120}") int commonPerWindow,
            @Value("${gateway.agent-tdd.mcp-limits.reference-per-minute:60}") int referencePerWindow,
            @Value("${gateway.agent-tdd.mcp-limits.authoring-per-minute:30}") int authoringPerWindow,
            @Value("${gateway.agent-tdd.mcp-limits.authoring-concurrency:4}") int authoringConcurrency,
            AgentTddAuthoringTelemetry telemetry) {
        this(commonPerWindow, referencePerWindow, authoringPerWindow, authoringConcurrency,
                System::nanoTime, telemetry);
    }

    McpRequestLimiter(int commonPerWindow,
                      int referencePerWindow,
                      int authoringPerWindow,
                      int authoringConcurrency,
                      LongSupplier nanoTime) {
        this(commonPerWindow, referencePerWindow, authoringPerWindow, authoringConcurrency,
                nanoTime, AgentTddAuthoringTelemetry.noop());
    }

    McpRequestLimiter(int commonPerWindow,
                      int referencePerWindow,
                      int authoringPerWindow,
                      int authoringConcurrency,
                      LongSupplier nanoTime,
                      AgentTddAuthoringTelemetry telemetry) {
        this.commonPerWindow = positive(commonPerWindow, "commonPerWindow");
        this.referencePerWindow = positive(referencePerWindow, "referencePerWindow");
        this.authoringPerWindow = positive(authoringPerWindow, "authoringPerWindow");
        this.authoringConcurrency = positive(authoringConcurrency, "authoringConcurrency");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /** Returns conservative defaults for transport-focused unit tests and embedders. */
    static McpRequestLimiter defaults() {
        return new McpRequestLimiter(120, 60, 30, 4, System::nanoTime);
    }

    /**
     * Acquires all applicable rate and concurrency claims for one authenticated tool call.
     *
     * @param identity authenticated MCP identity
     * @param toolName canonical tool name
     * @return permit that must be closed after dispatch
     */
    Permit acquire(IntegrationRequestContext identity, String toolName) {
        Objects.requireNonNull(identity, "identity");
        String subject = subject(identity);
        requireRate(subject + "|common", commonPerWindow, toolName);
        if ("rg.dsl.reference.get".equals(toolName)) {
            requireRate(subject + "|reference", referencePerWindow, toolName);
            return Permit.NONE;
        }
        if ("rg.dsl.preview".equals(toolName) || "rg.gate.check".equals(toolName)) {
            requireRate(subject + "|authoring", authoringPerWindow, toolName);
            Semaphore semaphore;
            synchronized (authoringPermits) {
                semaphore = authoringPermits.get(subject);
                if (semaphore == null) {
                    if (authoringPermits.size() >= MAX_TRACKED_AUTHORING_IDENTITIES) {
                        telemetry.limitRejected(toolName, "concurrency_capacity");
                        throw new McpProtocolException(-32030, "MCP authoring concurrency capacity exceeded");
                    }
                    semaphore = new Semaphore(authoringConcurrency);
                    authoringPermits.put(subject, semaphore);
                }
                if (!semaphore.tryAcquire()) {
                    telemetry.limitRejected(toolName, "concurrency");
                    throw new McpProtocolException(-32030, "MCP authoring concurrency limit exceeded");
                }
            }
            Semaphore acquired = semaphore;
            return () -> releaseAuthoring(subject, acquired);
        }
        return Permit.NONE;
    }

    private void requireRate(String key, int maximum, String toolName) {
        long now = nanoTime.getAsLong();
        synchronized (windows) {
            Window window = windows.get(key);
            if (window == null) {
                if (windows.size() >= MAX_TRACKED_BUCKETS) {
                    windows.entrySet().removeIf(entry -> entry.getValue().expired(now));
                }
                if (windows.size() >= MAX_TRACKED_BUCKETS) {
                    telemetry.limitRejected(toolName, "rate_capacity");
                    throw new McpProtocolException(-32029, "MCP request rate-limit capacity exceeded");
                }
                window = new Window(now);
                windows.put(key, window);
            }
            if (!window.tryAcquire(now, maximum)) {
                telemetry.limitRejected(toolName, "rate");
                throw new McpProtocolException(-32029, "MCP request rate limit exceeded");
            }
        }
    }

    private void releaseAuthoring(String subject, Semaphore semaphore) {
        synchronized (authoringPermits) {
            semaphore.release();
            if (semaphore.availablePermits() == authoringConcurrency) {
                authoringPermits.remove(subject, semaphore);
            }
        }
    }

    private static String subject(IntegrationRequestContext identity) {
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "tenant", identity.tenantId(),
                "project", identity.projectId(),
                "environment", identity.environmentId(),
                "actorType", identity.actorType(),
                "actor", identity.actorId()));
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /** Closeable concurrency claim; rate-only requests receive a no-op permit. */
    @FunctionalInterface
    interface Permit extends AutoCloseable {
        Permit NONE = () -> { };
        @Override void close();
    }

    private static final class Window {
        private long startedAt;
        private int count;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }

        private synchronized boolean tryAcquire(long now, int maximum) {
            if (now - startedAt >= WINDOW.toNanos() || now < startedAt) {
                startedAt = now;
                count = 0;
            }
            if (count >= maximum) return false;
            count++;
            return true;
        }

        private synchronized boolean expired(long now) {
            return now < startedAt || now - startedAt >= WINDOW.toNanos();
        }
    }
}
