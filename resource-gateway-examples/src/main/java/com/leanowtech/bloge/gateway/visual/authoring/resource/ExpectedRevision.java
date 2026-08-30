package com.leanowtech.bloge.gateway.visual.authoring.resource;

/** Optimistic-concurrency expectation for one authoritative save. */
public sealed interface ExpectedRevision permits ExpectedRevision.Create, ExpectedRevision.Match {

    /** Create-only expectation; an existing resource is a conflict. */
    record Create() implements ExpectedRevision {
    }

    /** Match-only expectation for the current exact one-based revision. */
    record Match(long revision) implements ExpectedRevision {
        public Match {
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be at least 1");
            }
        }
    }

    /** @return create-only expectation */
    static ExpectedRevision create() {
        return new Create();
    }

    /** @param revision exact current revision @return match expectation */
    static ExpectedRevision match(long revision) {
        return new Match(revision);
    }

    /** Alias used by HTTP If-Match adapters. @param revision exact current revision @return expectation */
    static ExpectedRevision exact(long revision) {
        return match(revision);
    }
}
