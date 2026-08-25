package com.leanowtech.bloge.gateway.testing.planning;

import java.util.UUID;

/** Supplies the unique identity assigned to each compiled execution plan. */
@FunctionalInterface
public interface PlanIdentitySource {

    /** @return the next plan identity, which must be nonblank */
    String nextPlanId();
}

/** System-boundary adapter for production plan identities. */
final class SystemPlanIdentitySource implements PlanIdentitySource {

    @Override
    public String nextPlanId() {
        return "plan-" + UUID.randomUUID();
    }
}
