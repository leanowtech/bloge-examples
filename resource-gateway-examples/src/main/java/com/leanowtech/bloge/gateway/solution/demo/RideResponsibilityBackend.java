package com.leanowtech.bloge.gateway.solution.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.FeatureEvaluationAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Evaluates the deterministic responsibility-party Feature for the local demo catalog. */
@Component
@ConditionalOnProperty(prefix = "gateway.agent-tdd.cancel-dispute-demo", name = "enabled",
        havingValue = "true")
public final class RideResponsibilityBackend implements FeatureEvaluationAdapter {
    /** Versioned evaluation binding accepted by this adapter. */
    public static final String EVALUATION_REF = "demo:ride-responsibility-v1";
    private final CancelDisputeDemoLedger ledger;
    private final ObjectMapper mapper;

    /** Creates the adapter over seeded order facts. */
    public RideResponsibilityBackend(CancelDisputeDemoLedger ledger, ObjectMapper mapper) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public FeatureContract.EvaluationKind kind() { return FeatureContract.EvaluationKind.API; }
    @Override public boolean supports(String evaluationRef) { return EVALUATION_REF.equals(evaluationRef); }

    /** Returns one enum value and never returns the order record or another business payload. */
    @Override
    public JsonNode evaluate(FeatureContract feature, JsonNode inputs, IntegrationRequestContext identity) {
        return mapper.valueToTree(ledger.requireOrder(inputs.path("orderId").asText()).responsibilityParty());
    }
}
