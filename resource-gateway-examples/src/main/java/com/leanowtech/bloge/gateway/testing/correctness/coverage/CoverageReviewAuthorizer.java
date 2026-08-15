package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;

/** Enterprise authorization port for the irreversible denominator-freeze command. */
@FunctionalInterface
public interface CoverageReviewAuthorizer {

    boolean mayFreeze(EnterpriseScope scope, CoverageInventory inventory, PrincipalRef actor);

    static CoverageReviewAuthorizer denyAll() {
        return (scope, inventory, actor) -> false;
    }
}
