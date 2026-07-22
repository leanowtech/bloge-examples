package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Map;

/**
 * Stable fail-closed projection failure returned before a capability snapshot can be sealed.
 *
 * @param code machine-readable projection error code
 * @param message bounded operator-facing explanation
 * @param details non-payload diagnostic coordinates
 */
public record CapabilityProjectionException(
        String code,
        String message,
        Map<String, Object> details
) {
    /** Creates an immutable projection failure value. */
    public CapabilityProjectionException {
        code = required(code, "code");
        message = required(message, "message");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** Converts this value into the exception used to abort projection. */
    public Failure failure() {
        return new Failure(this);
    }

    /** Runtime exception that retains a stable projection failure value. */
    public static final class Failure extends IllegalArgumentException {
        private final CapabilityProjectionException problem;

        private Failure(CapabilityProjectionException problem) {
            super(problem.code() + ": " + problem.message());
            this.problem = problem;
        }

        /** @return stable projection failure value */
        public CapabilityProjectionException problem() {
            return problem;
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
