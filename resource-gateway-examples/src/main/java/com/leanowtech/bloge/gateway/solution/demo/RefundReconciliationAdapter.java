package com.leanowtech.bloge.gateway.solution.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.solution.ReconciliationAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reads the local refund system after a controlled write. */
@Component
@ConditionalOnProperty(prefix = "gateway.agent-tdd.cancel-dispute-demo", name = "enabled",
        havingValue = "true")
public final class RefundReconciliationAdapter implements ReconciliationAdapter {
    private final CancelDisputeDemoLedger ledger;

    /** Creates the adapter over the local demo refund store. */
    public RefundReconciliationAdapter(CancelDisputeDemoLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override public String adapterRef() { return "recon:refund-v1"; }
    @Override public String downstreamSystem() { return "refund-service"; }

    /** Returns the exact observed effect keyed by the governed order coordinate. */
    @Override
    public ObservedEffect observe(String reconciliationKeyValue, JsonNode caseInputs) {
        return new ObservedEffect(reconciliationKeyValue, ledger.refund(reconciliationKeyValue));
    }
}
