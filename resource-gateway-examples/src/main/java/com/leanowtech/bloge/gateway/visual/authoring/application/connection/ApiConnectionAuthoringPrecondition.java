package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

/**
 * Current-head precondition accepted by the standalone Connection facade.
 * Strong ETag shape is checked by the facade before a command claim; the
 * value object intentionally does not throw so transport callers receive the
 * facade's closed validation failure.
 */
public sealed interface ApiConnectionAuthoringPrecondition
        permits ApiConnectionAuthoringPrecondition.Create, ApiConnectionAuthoringPrecondition.MatchStrongEtag {
    /** Create only; an existing Connection is a CAS conflict. */
    record Create() implements ApiConnectionAuthoringPrecondition { }

    /** Update against one exact historical strong ETag. */
    record MatchStrongEtag(String strongEtag) implements ApiConnectionAuthoringPrecondition { }

    /** @return create precondition */
    static ApiConnectionAuthoringPrecondition create() { return new Create(); }

    /** @param strongEtag exact strong ETag @return update precondition */
    static ApiConnectionAuthoringPrecondition matchStrongEtag(String strongEtag) {
        return new MatchStrongEtag(strongEtag);
    }
}
