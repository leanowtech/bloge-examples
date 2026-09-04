package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** Reads one downstream system after a governed WRITE and projects a comparable business effect. */
public interface ReconciliationAdapter {
    /** @return stable adapter reference declared by the Instruction contract */
    String adapterRef();

    /** @return exact downstream system owned by this adapter */
    String downstreamSystem();

    /** Reads the effect back; implementations must not log inputs or credentials. */
    ObservedEffect observe(String reconciliationKeyValue, JsonNode caseInputs);

    /** Structured observed effect used only for comparison before payload-free evidence projection. */
    record ObservedEffect(String reconciliationKey, Map<String, Object> effect) {
        /** Freezes observed fields and requires a non-empty reconciliation coordinate. */
        public ObservedEffect {
            reconciliationKey = reconciliationKey == null ? "" : reconciliationKey.trim();
            effect = effect == null ? Map.of() : Map.copyOf(effect);
            if (reconciliationKey.isBlank()) {
                throw new IllegalArgumentException("Reconciliation key is required");
            }
        }
    }
}
