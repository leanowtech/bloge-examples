package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Deep read module for private Fixture revisions and exact-subject discovery. */
public final class ApiFixtureSetAuthoringFacade {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final FixtureSetAuthorityReader store;
    private final ReusableFlowFixtureModule writer;

    /** Creates the read module over one authority store. */
    public ApiFixtureSetAuthoringFacade(FixtureSetAuthorityReader store) {
        this(store, null);
    }

    /** Creates the complete read/write module when standalone Flow Fixture authoring is available. */
    public ApiFixtureSetAuthoringFacade(FixtureSetAuthorityReader store,
                                        ReusableFlowFixtureModule writer) {
        this.store = Objects.requireNonNull(store, "store");
        this.writer = writer;
    }

    /** Creates or updates one independently authored whole-flow Fixture Set. */
    public StandaloneFixtureSetSaveResult save(
            AuthoringScope scope, String actorId, String fixtureSetId,
            FixtureSetPrecondition precondition, String idempotencyKey,
            com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand command) {
        if (writer == null) throw failure(ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        return writer.save(scope, actorId, fixtureSetId, precondition, idempotencyKey, command);
    }

    /** Returns the current or one exact immutable private Fixture revision. */
    public FixtureSetView read(AuthoringScope scope, String fixtureSetId, Integer revision) {
        requireScope(scope);
        if (fixtureSetId == null || !IDENTIFIER.matcher(fixtureSetId).matches()
                || revision != null && revision < 1) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        try {
            Optional<StoredFixtureSet> stored = revision == null
                    ? store.findHead(scope, fixtureSetId)
                    : store.findRevision(scope, fixtureSetId, revision);
            return stored.orElseThrow(() -> failure(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND))
                    .generated().view();
        } catch (ApiFixtureSetAuthoringFailure failure) {
            throw failure;
        } catch (ApiFixtureSetCommitStoreException | StandaloneFixtureSetStoreException failure) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }
    }

    /** Lists metadata-only current Fixture summaries for one exact immutable subject. */
    public List<FixtureSetSummary> list(AuthoringScope scope, FixtureSubjectRef subject) {
        requireScope(scope);
        if (subject == null) throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        try {
            return List.copyOf(store.listSummariesBySubject(scope, subject));
        } catch (ApiFixtureSetCommitStoreException | StandaloneFixtureSetStoreException failure) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }
    }

    private static void requireScope(AuthoringScope scope) {
        if (scope == null) throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
    }

    private static ApiFixtureSetAuthoringFailure failure(ApiFixtureSetAuthoringFailure.Code code) {
        return new ApiFixtureSetAuthoringFailure(code);
    }
}
