package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;

/** Enterprise policy port for canonical Case approval. */
@FunctionalInterface
public interface ScenarioReviewAuthorizer {

    ReviewDecision authorize(
            EnterpriseScope scope,
            ScenarioDraftSetV2 draftSet,
            ScenarioDraftV2 scenario,
            PrincipalRef actor);

    static ScenarioReviewAuthorizer denyAll() {
        return (scope, draftSet, scenario, actor) -> ReviewDecision.denied();
    }

    record ReviewDecision(boolean allowed, boolean independentReviewRequired) {
        public static ReviewDecision denied() {
            return new ReviewDecision(false, true);
        }

        public static ReviewDecision governed() {
            return new ReviewDecision(true, true);
        }
    }
}
