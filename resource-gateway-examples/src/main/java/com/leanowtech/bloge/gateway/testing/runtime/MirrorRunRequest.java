package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;

import java.util.Objects;

/**
 * Authorized in-process request to execute one exact compiled mirror generation.
 *
 * <p>The business context is detached from the caller. Scope and purpose are supplied by the
 * authenticated adapter and must exactly match the sealed plan; they are not read from business
 * context keys.</p>
 *
 * @param requestId stable caller idempotency or correlation identity
 * @param compiledPlan exact self-contained mirror execution generation
 * @param context business input context copied for this run
 * @param authorizedScope scope derived from authenticated workload identity
 * @param authorizedPurpose purpose minted by the protected endpoint
 */
public record MirrorRunRequest(
        String requestId,
        CompiledMirrorPlan compiledPlan,
        GraphContext context,
        CapabilitySnapshot.Scope authorizedScope,
        String authorizedPurpose
) {
    /** Detaches business context and normalizes authenticated coordinates. */
    public MirrorRunRequest {
        requestId = required(requestId, "requestId");
        compiledPlan = Objects.requireNonNull(compiledPlan, "compiledPlan");
        context = context == null ? new GraphContext() : new GraphContext(context.asMap());
        authorizedScope = Objects.requireNonNull(authorizedScope, "authorizedScope");
        authorizedPurpose = required(authorizedPurpose, "authorizedPurpose");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
