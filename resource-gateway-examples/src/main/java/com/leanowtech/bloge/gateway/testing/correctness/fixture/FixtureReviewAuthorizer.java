package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;

/** Enterprise policy port for Fixture Owner review. */
@FunctionalInterface
public interface FixtureReviewAuthorizer {

    ApprovalDecision authorize(
            EnterpriseScope scope,
            FixtureAssetDescriptor descriptor,
            PrincipalRef actor);

    static FixtureReviewAuthorizer denyAll() {
        return (scope, descriptor, actor) -> ApprovalDecision.denied();
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
