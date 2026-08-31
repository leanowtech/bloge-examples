package com.leanowtech.bloge.gateway.visual.authoring.flow;

/** HTTP-neutral strong-validator precondition for one reusable Flow save. */
public sealed interface ReusableFlowPrecondition {
    /** Requires that no Flow head exists. */
    record Create() implements ReusableFlowPrecondition { }

    /** Requires the current head to derive from one exact committed strong ETag. */
    record MatchStrongEtag(String strongEtag) implements ReusableFlowPrecondition {
        public MatchStrongEtag {
            if (!ReusableFlowStrongEtag.isValid(strongEtag)) {
                throw new IllegalArgumentException("strong ETag is invalid");
            }
        }
    }

    static ReusableFlowPrecondition create() { return new Create(); }
    static ReusableFlowPrecondition matchStrongEtag(String value) { return new MatchStrongEtag(value); }

}
