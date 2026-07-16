package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

/**
 * Verifies that a semantic test target is the current executable target of an exact graph draft.
 */
public interface SemanticGateTargetVerifier {

    /**
     * Compares one suite target with the graph or operator artifact represented by the draft.
     *
     * @param draft exact immutable draft revision under governance review
     * @param target exact semantic suite target
     * @return bounded result without leaking operator or resource details
     */
    Verification verify(GraphDraft draft, TestSuite.Target target);

    /** Stable target-binding result used by gate validation and freshness checks. */
    record Verification(boolean matched, String reason) {
        /** @return a successful exact binding */
        public static Verification accepted() {
            return new Verification(true, "MATCHED");
        }

        /** @param reason bounded failure reason */
        public static Verification rejected(String reason) {
            return new Verification(false, reason == null || reason.isBlank()
                    ? "TARGET_BINDING_INVALID" : reason);
        }
    }
}
