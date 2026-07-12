package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.List;

/**
 * Structured, audit-oriented execution semantics captured for one graph node.
 *
 * <p>Facts distinguish engine observations from topology-derived explanations. Consumers must not
 * treat a derived cause as an engine event, or an absent event as proof that a policy did not run.</p>
 */
public record VisualNodeExecutionFact(
        String status,
        String reasonCode,
        String observationSource,
        List<String> causedByNodeIds,
        Retry retry,
        Timeout timeout,
        Fallback fallback,
        String sideEffectOutcome,
        List<VisualSideEffectAttempt> sideEffectAttempts,
        List<Event> events
) {
    public VisualNodeExecutionFact {
        status = normalize(status, "UNKNOWN");
        reasonCode = normalize(reasonCode, "STATUS_NOT_CAPTURED");
        observationSource = normalize(observationSource, "NOT_CAPTURED");
        causedByNodeIds = causedByNodeIds == null ? List.of() : List.copyOf(causedByNodeIds);
        retry = retry == null ? Retry.unknown() : retry;
        timeout = timeout == null ? Timeout.unknown() : timeout;
        fallback = fallback == null ? Fallback.unknown() : fallback;
        sideEffectOutcome = normalize(sideEffectOutcome, "NOT_CAPTURED");
        sideEffectAttempts = sideEffectAttempts == null ? List.of() : List.copyOf(sideEffectAttempts);
        events = events == null ? List.of() : List.copyOf(events);
    }

    /** Backward-compatible constructor for facts captured before structured side-effect attempts. */
    public VisualNodeExecutionFact(String status, String reasonCode, String observationSource,
                                   List<String> causedByNodeIds, Retry retry, Timeout timeout,
                                   Fallback fallback, String sideEffectOutcome, List<Event> events) {
        this(status, reasonCode, observationSource, causedByNodeIds, retry, timeout, fallback,
                sideEffectOutcome, List.of(), events);
    }

    public static VisualNodeExecutionFact unknown() {
        return new VisualNodeExecutionFact("UNKNOWN", "STATUS_NOT_CAPTURED", "NOT_CAPTURED", List.of(),
                Retry.unknown(), Timeout.unknown(), Fallback.unknown(), "NOT_CAPTURED", List.of(), List.of());
    }

    public record Retry(int configuredMaxAttempts, int observedAttempts, boolean exhausted,
                        String lastErrorType) {
        public Retry {
            configuredMaxAttempts = Math.max(0, configuredMaxAttempts);
            observedAttempts = Math.max(0, observedAttempts);
            lastErrorType = lastErrorType == null ? "" : lastErrorType;
        }

        static Retry unknown() {
            return new Retry(0, 0, false, "");
        }
    }

    public record Timeout(boolean configured, long configuredTimeoutMs, boolean observed) {
        public Timeout {
            configuredTimeoutMs = Math.max(0, configuredTimeoutMs);
        }

        static Timeout unknown() {
            return new Timeout(false, 0, false);
        }
    }

    public record Fallback(boolean configured, boolean used, String strategy, String originalErrorType) {
        public Fallback {
            strategy = normalize(strategy, configured ? "CONFIGURED" : "NONE");
            originalErrorType = originalErrorType == null ? "" : originalErrorType;
        }

        static Fallback unknown() {
            return new Fallback(false, false, "NOT_CAPTURED", "");
        }
    }

    /** Low-cardinality resilience event. Payloads and exception messages are intentionally excluded. */
    public record Event(int sequence, String type, Instant observedAt, int attempt, String errorType) {
        public Event {
            sequence = Math.max(0, sequence);
            type = normalize(type, "UNKNOWN");
            observedAt = observedAt == null ? Instant.EPOCH : observedAt;
            attempt = Math.max(0, attempt);
            errorType = errorType == null ? "" : errorType;
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
