package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;

/** Enterprise policy port for Business Oracle approval. */
@FunctionalInterface
public interface OracleReviewAuthorizer {

    ApprovalDecision authorize(
            EnterpriseScope scope,
            BusinessOracle oracle,
            PrincipalRef actor);

    static OracleReviewAuthorizer denyAll() {
        return (scope, oracle, actor) -> ApprovalDecision.denied();
    }

    record ApprovalDecision(boolean allowed, boolean independentReviewRequired) {
        public static ApprovalDecision denied() {
            return new ApprovalDecision(false, true);
        }

        public static ApprovalDecision ownerReview() {
            return new ApprovalDecision(true, true);
        }
    }
}
