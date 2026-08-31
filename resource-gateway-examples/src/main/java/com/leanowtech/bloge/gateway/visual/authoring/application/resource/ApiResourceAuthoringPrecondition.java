package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

/** Opaque HTTP precondition translated before entering Resource persistence. */
public sealed interface ApiResourceAuthoringPrecondition
        permits ApiResourceAuthoringPrecondition.Create,
                ApiResourceAuthoringPrecondition.MatchStrongEtag {
    /** Creates a Resource only when no committed head exists. */
    record Create() implements ApiResourceAuthoringPrecondition { }

    /** Updates from the committed revision identified by an opaque strong ETag. */
    record MatchStrongEtag(String strongEtag) implements ApiResourceAuthoringPrecondition { }

    /** @return create-only precondition */
    static ApiResourceAuthoringPrecondition create() { return new Create(); }

    /** @return exact historical strong-ETag precondition */
    static ApiResourceAuthoringPrecondition matchStrongEtag(String strongEtag) {
        return new MatchStrongEtag(strongEtag);
    }
}
