package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ComponentFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveIntent;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredStandaloneFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ComponentSimulationAuthorityV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ExactFixtureSubjectRefV2;

import java.util.Objects;
import java.util.Optional;

/** Deep application module for independently authored Operator and Function Fixture Sets. */
public final class ComponentFixtureSetModule {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ComponentSimulationAuthorityV2 components;
    private final StandaloneFixtureSetStore store;
    private final ComponentFixtureSetMaterializer materializer;

    public ComponentFixtureSetModule(
            ComponentSimulationAuthorityV2 components, StandaloneFixtureSetStore store,
            ComponentFixtureSetMaterializer materializer) {
        this.components = Objects.requireNonNull(components, "components");
        this.store = Objects.requireNonNull(store, "store");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
    }

    /** Resolves exact component authority before consuming one CAS/idempotency coordinate. */
    public StandaloneFixtureSetSaveResult save(
            AuthoringScope scope, String actorId, String fixtureSetId,
            FixtureSetPrecondition precondition, String idempotencyKey, FixtureSetCommand command) {
        if (scope == null || precondition == null || command == null) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        try {
            ExactFixtureSubjectRefV2 exact = ExactFixtureSubjectRefV2.from(command.subject());
            if (!(exact instanceof ExactFixtureSubjectRefV2.OperatorVersion
                    || exact instanceof ExactFixtureSubjectRefV2.BuiltinFunctionVersion)) {
                throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
            }
            ComponentSimulationAuthorityV2.ComponentContract contract = components.resolve(scope, exact)
                    .orElseThrow(() -> failure(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND));
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
                expected = ExpectedRevision.match(
                        prior.get().stored().generated().view().revision());
                revision = Math.addExact(
                        prior.get().stored().generated().view().revision(), 1);
            }
            return store.save(new StandaloneFixtureSetSaveIntent(
                    scope, actorId, fixtureSetId, expected, idempotencyKey,
                    AuthoringFingerprints.of(JSON.valueToTree(command)),
                    materializer.generate(fixtureSetId, revision, command.subject(), contract, command)));
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
        }
    }

    private static ApiFixtureSetAuthoringFailure failure(ApiFixtureSetAuthoringFailure.Code code) {
        return new ApiFixtureSetAuthoringFailure(code);
    }
}
