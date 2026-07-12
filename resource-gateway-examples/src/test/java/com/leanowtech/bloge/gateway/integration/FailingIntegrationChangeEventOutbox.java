package com.leanowtech.bloge.gateway.integration;

import java.util.List;

/** Test double that fails exactly where an asset transaction appends its change fact. */
public final class FailingIntegrationChangeEventOutbox implements IntegrationChangeEventOutbox {
    @Override
    public IntegrationChangeEvent append(IntegrationChangeEvent event) {
        throw new IllegalStateException("simulated outbox failure");
    }

    @Override
    public List<IntegrationChangeEvent> read(long afterSequence,
                                             long throughSequence,
                                             String tenantId,
                                             String environmentId,
                                             int limit) {
        return List.of();
    }

    @Override
    public boolean hasAfter(long afterSequence,
                            long throughSequence,
                            String tenantId,
                            String environmentId) {
        return false;
    }

    @Override
    public long highWaterSequence() {
        return 0;
    }

    @Override
    public boolean available() {
        return true;
    }
}
