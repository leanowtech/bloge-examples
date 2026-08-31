package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveIntent;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredStandaloneFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Objects;
import java.util.Optional;

/** Deep application module for independently authored whole-flow Fixture Sets. */
public final class ReusableFlowFixtureModule {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ReusableFlowPublicationStore publications;
    private final StandaloneFixtureSetStore store;
    private final WholeFlowFixtureMaterializer materializer;

    public ReusableFlowFixtureModule(ReusableFlowPublicationStore publications,
                                     StandaloneFixtureSetStore store,
                                     WholeFlowFixtureMaterializer materializer) {
        this.publications = Objects.requireNonNull(publications, "publications");
        this.store = Objects.requireNonNull(store, "store");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
    }

    /** Resolves an immutable Flow Version, validates material, then atomically saves one revision. */
    public StandaloneFixtureSetSaveResult save(
            AuthoringScope scope, String actorId, String fixtureSetId,
            FixtureSetPrecondition precondition, String idempotencyKey, FixtureSetCommand command) {
        Objects.requireNonNull(precondition, "precondition");
        if (scope == null || command == null) throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        try {
            ReusableFlowVersion version = resolveVersion(scope, command);
            ExpectedRevision expected;
            int revision;
            if (precondition instanceof FixtureSetPrecondition.Create) {
                expected = ExpectedRevision.create();
                revision = 1;
            } else {
                String etag = ((FixtureSetPrecondition.MatchStrongEtag) precondition).strongEtag();
                Optional<StoredStandaloneFixtureSet> prior = store.findRevisionByStrongEtag(
                        scope, fixtureSetId, etag);
                if (prior.isEmpty()) {
                    ApiFixtureSetAuthoringFailure.Code code = store.findHead(scope, fixtureSetId).isEmpty()
                            ? ApiFixtureSetAuthoringFailure.Code.NOT_FOUND
                            : ApiFixtureSetAuthoringFailure.Code.CAS_MISMATCH;
                    throw failure(code);
                }
                int priorRevision = prior.get().stored().generated().view().revision();
                expected = ExpectedRevision.match(priorRevision);
                revision = Math.addExact(priorRevision, 1);
            }
            GeneratedDefaultFixture generated = materializer.generate(
                    fixtureSetId, revision, version, command);
            return store.save(new StandaloneFixtureSetSaveIntent(scope, actorId, fixtureSetId,
                    expected, idempotencyKey,
                    AuthoringFingerprints.of(JSON.valueToTree(command)), generated));
        } catch (ApiFixtureSetAuthoringFailure failure) {
            throw failure;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        } catch (StandaloneFixtureSetStoreException failure) {
            throw failure(switch (failure.code()) {
                case CAS_MISMATCH -> ApiFixtureSetAuthoringFailure.Code.CAS_MISMATCH;
                case CONFLICT -> ApiFixtureSetAuthoringFailure.Code.CONFLICT;
                case INTEGRITY -> ApiFixtureSetAuthoringFailure.Code.INTEGRITY;
                case PERSISTENCE -> ApiFixtureSetAuthoringFailure.Code.PERSISTENCE;
            });
        } catch (ReusableFlowFailure failure) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }
    }

    private ReusableFlowVersion resolveVersion(AuthoringScope scope, FixtureSetCommand command) {
        if (!(command.subject() instanceof com.leanowtech.bloge.gateway.visual.authoring.fixture
                .FixtureSubjectRef.FlowVersion subject)) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        ReusableFlowVersion version = publications.findVersion(
                        scope, subject.publicationId(), subject.revision())
                .orElseThrow(() -> failure(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND));
        if (!version.subject().equals(subject)) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }
        return version;
    }

    private static ApiFixtureSetAuthoringFailure failure(ApiFixtureSetAuthoringFailure.Code code) {
        return new ApiFixtureSetAuthoringFailure(code);
    }
}
