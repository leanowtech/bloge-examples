package com.leanowtech.bloge.gateway.solution.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.FeatureEvaluationAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Evaluates whether a seeded demo order is still inside the free-cancellation window. */
@Component
@ConditionalOnProperty(prefix = "gateway.agent-tdd.cancel-dispute-demo", name = "enabled",
        havingValue = "true")
public final class CancelWithinFreeBackend implements FeatureEvaluationAdapter {
    /** Versioned evaluation binding accepted by this adapter. */
    public static final String EVALUATION_REF = "demo:cancel-within-free-v1";
    private final CancelDisputeDemoLedger ledger;
    private final ObjectMapper mapper;

    /** Creates the deterministic DAG-equivalent adapter over seeded order facts. */
    public CancelWithinFreeBackend(CancelDisputeDemoLedger ledger, ObjectMapper mapper) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public FeatureContract.EvaluationKind kind() { return FeatureContract.EvaluationKind.DAG; }
    @Override public boolean supports(String evaluationRef) { return EVALUATION_REF.equals(evaluationRef); }

    /** Returns only the atomic boolean declared by the Feature contract. */
    @Override
    public JsonNode evaluate(FeatureContract feature, JsonNode inputs, IntegrationRequestContext identity) {
        return mapper.valueToTree(ledger.requireOrder(inputs.path("orderId").asText()).withinFreeWindow());
    }
}
