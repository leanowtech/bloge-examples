package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetReviewResult;
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
    private final ComponentFixtureSetModule componentWriter;
    private final ReusableFlowFixtureShareModule shareModule;
    private final ReusableFlowFixtureReviewModule reviewModule;

    /** Creates the read module over one authority store. */
    public ApiFixtureSetAuthoringFacade(FixtureSetAuthorityReader store) {
        this(store, null, null, null, null);
    }

    /** Creates the complete read/write module when standalone Flow Fixture authoring is available. */
    public ApiFixtureSetAuthoringFacade(FixtureSetAuthorityReader store,
                                        ReusableFlowFixtureModule writer) {
        this(store, writer, null, null, null);
    }

    /** Creates the complete read/write/share module. */
    public ApiFixtureSetAuthoringFacade(FixtureSetAuthorityReader store,
                                        ReusableFlowFixtureModule writer,
                                        ReusableFlowFixtureShareModule shareModule) {
        this(store, writer, null, shareModule, null);
    }

    /** Creates the complete read/write/share/review module. */
    public ApiFixtureSetAuthoringFacade(FixtureSetAuthorityReader store,
                                        ReusableFlowFixtureModule writer,
                                        ReusableFlowFixtureShareModule shareModule,
                                        ReusableFlowFixtureReviewModule reviewModule) {
        this(store, writer, null, shareModule, reviewModule);
    }

    /** Creates the complete Flow/component read/write/share/review module. */
    public ApiFixtureSetAuthoringFacade(FixtureSetAuthorityReader store,
                                        ReusableFlowFixtureModule writer,
                                        ComponentFixtureSetModule componentWriter,
                                        ReusableFlowFixtureShareModule shareModule,
                                        ReusableFlowFixtureReviewModule reviewModule) {
        this.store = Objects.requireNonNull(store, "store");
        this.writer = writer;
        this.componentWriter = componentWriter;
        this.shareModule = shareModule;
        this.reviewModule = reviewModule;
    }

    /** Creates or updates one independently authored whole-flow Fixture Set. */
    public StandaloneFixtureSetSaveResult save(
            AuthoringScope scope, String actorId, String fixtureSetId,
            FixtureSetPrecondition precondition, String idempotencyKey,
            com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand command) {
        if (command != null && (command.subject() instanceof FixtureSubjectRef.OperatorVersion
                || command.subject() instanceof FixtureSubjectRef.BuiltinFunctionVersion)) {
            if (componentWriter == null) {
                throw failure(ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
            }
            return componentWriter.save(
                    scope, actorId, fixtureSetId, precondition, idempotencyKey, command);
        }
        if (writer == null) throw failure(ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        return writer.save(scope, actorId, fixtureSetId, precondition, idempotencyKey, command);
    }

    /** Derives one protected, independently reviewed Fixture revision. */
    public com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence
            .StandaloneFixtureSetShareResult share(
            FixtureShareIdentity identity,
            String fixtureSetId, String sourceStrongEtag, String idempotencyKey,
            com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand command) {
        if (shareModule == null) throw failure(ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        return shareModule.share(identity, fixtureSetId, sourceStrongEtag, idempotencyKey, command);
    }

    /** Completes one independent protected-material review. */
    public StandaloneFixtureSetReviewResult review(
            FixtureShareIdentity reviewer, String fixtureSetId, String sourceStrongEtag,
            String idempotencyKey, FixtureReviewCommand command) {
        if (reviewModule == null) throw failure(ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        return reviewModule.review(reviewer, fixtureSetId, sourceStrongEtag, idempotencyKey, command);
    }

    /** Returns the current or one exact immutable private Fixture revision. */
    public ApiFixtureSetAuthoringRead read(AuthoringScope scope, String fixtureSetId, Integer revision) {
        requireScope(scope);
        if (fixtureSetId == null || !IDENTIFIER.matcher(fixtureSetId).matches()
                || revision != null && revision < 1) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        try {
            Optional<StoredFixtureSet> stored = revision == null
                    ? store.findHead(scope, fixtureSetId)
                    : store.findRevision(scope, fixtureSetId, revision);
            StoredFixtureSet authority = stored.orElseThrow(
                    () -> failure(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND));
            return new ApiFixtureSetAuthoringRead(
                    authority.generated().view(), authority.strongEtag());
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
