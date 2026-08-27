package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-owned execution port; implementations may be real API or published World runtime. */
@FunctionalInterface
public interface WorldFidelityRunner {
    Execution run(Object canonicalRequest) throws Exception;

    record Execution(
            JsonNode response,
            String errorClass,
            int status,
            boolean retryable,
            List<StateTransition> transitions,
            long durationMillis
    ) {
        public Execution {
            response = response == null ? null : response.deepCopy();
            errorClass = errorClass == null ? "" : errorClass.trim();
            transitions = transitions == null ? List.of() : List.copyOf(transitions);
            if ((!errorClass.isEmpty() && !Set.of("TIMEOUT", "EXECUTION_FAILED", "NOT_FOUND", "VALIDATION", "UNKNOWN")
                    .contains(errorClass)) || status < 0 || durationMillis < 0
                    || transitions.stream().anyMatch(java.util.Objects::isNull)) {
                throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
            }
        }

        @Override
        public JsonNode response() {
            return response == null ? null : response.deepCopy();
        }

        @Override
        public String toString() {
            return "Execution{status=" + status + ", retryable=" + retryable
                    + ", transitions=" + transitions.size() + ", durationMillis=" + durationMillis + '}';
        }
    }

    record StateTransition(String path, String outcome, String stateFingerprint) {
        public StateTransition {
            path = text(path, "path");
            outcome = text(outcome, "outcome");
            stateFingerprint = fingerprint(stateFingerprint);
        }
    }

    static String text(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)
                || ("outcome".equals(field) && !value.matches("[A-Z][A-Z0-9_]{0,63}"))) {
            throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
        return value.trim();
    }

    static String fingerprint(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
        return value;
    }
}
