package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCheckResult;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Governed egress port for explicit Connection checks.
 *
 * <p>The application layer supplies only trusted scope/actor, an exact
 * committed Connection coordinate, a validated base URI, and a bounded
 * timeout. Implementations own destination authorization, DNS rebinding
 * resistance, TLS policy, audit persistence and code-only failure mapping.
 * Credentials and API payloads are deliberately absent from this seam.</p>
 */
@FunctionalInterface
public interface ApiConnectionCheckGateway {
    /** Executes a network-only check through one governed egress authority. */
    Outcome networkOnly(Request request);

    /** @return fail-closed gateway used until a production egress provider is installed */
    static ApiConnectionCheckGateway unavailable() {
        return request -> { throw new ApiConnectionCheckGatewayException(
                ApiConnectionCheckGatewayException.Code.CAPABILITY_UNAVAILABLE); };
    }

    /** Payload-free exact request to the governed egress provider. */
    record Request(AuthoringScope scope, String actorId, String connectionId, int revision,
                   URI baseUri, Duration timeout) {
        public Request {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(timeout, "timeout");
            if (actorId == null || actorId.isBlank() || connectionId == null || connectionId.isBlank()
                    || revision < 1 || timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
                throw new IllegalArgumentException("connection check request is invalid");
            }
        }
    }

    /** Payload-free evidence produced by the governed egress provider. */
    record Outcome(ApiConnectionCheckResult.Status status, Instant checkedAt, long durationMs,
                   List<ApiConnectionCheckResult.Stage> stages, ApiConnectionCheckResult.Audit audit) {
        public Outcome {
            if (status == null || checkedAt == null || durationMs < 0 || durationMs > 120_000
                    || stages == null || stages.isEmpty() || stages.size() > 8 || audit == null) {
                throw new IllegalArgumentException("connection check outcome is invalid");
            }
            stages = List.copyOf(stages);
        }
    }
}
