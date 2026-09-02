package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareMaterialization;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetShareIntent;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetShareResult;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deep application module for private-to-governed Fixture Set derivation. */
public final class ReusableFlowFixtureShareModule {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final int MAX_RETENTION_DAYS = 365;
    private final StandaloneFixtureSetStore store;
    private final ReusableFlowPublicationStore publications;
    private final ReusableFlowDraftStore drafts;
    private final FixtureSetShareMaterialWriter materials;

    /** Creates a version-only share module; draft-owned Fixture sharing remains unavailable. */
    public ReusableFlowFixtureShareModule(
            StandaloneFixtureSetStore store,
            ReusableFlowPublicationStore publications,
            FixtureSetShareMaterialWriter materials) {
        this(store, publications, null, materials);
    }

    /** Creates one share module that can protect exact draft- or version-owned Fixture material. */
    public ReusableFlowFixtureShareModule(
            StandaloneFixtureSetStore store,
            ReusableFlowPublicationStore publications,
            ReusableFlowDraftStore drafts,
            FixtureSetShareMaterialWriter materials) {
        this.store = Objects.requireNonNull(store, "store");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.drafts = drafts;
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    /** Derives one immutable governed revision and creates its pending review request. */
    public StandaloneFixtureSetShareResult share(
            FixtureShareIdentity identity, String fixtureSetId, String sourceStrongEtag,
            String idempotencyKey, FixtureShareCommand command) {
        requireIdentity(identity);
        if (fixtureSetId == null || command == null
                || !fixtureSetId.equals(command.source().fixtureSetId())
                || command.policy().retentionDays() > MAX_RETENTION_DAYS) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        AuthoringScope scope;
        try {
            scope = identity.scope();
        } catch (IllegalArgumentException invalid) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        try {
            String requestFingerprint = AuthoringFingerprints.of(JSON.valueToTree(command));
            return store.share(new StandaloneFixtureSetShareIntent(
                    scope, identity.actorId(), fixtureSetId, sourceStrongEtag,
                    idempotencyKey, requestFingerprint, command),
                    (source, revision, statusRevision, reviewRequestId) -> derive(
                            identity, source, revision, statusRevision, reviewRequestId, command));
        } catch (ApiFixtureSetAuthoringFailure failure) {
            throw failure;
        } catch (StandaloneFixtureSetStoreException failure) {
            throw failure(switch (failure.code()) {
                case CAS_MISMATCH -> ApiFixtureSetAuthoringFailure.Code.CAS_MISMATCH;
                case CONFLICT -> ApiFixtureSetAuthoringFailure.Code.CONFLICT;
                case INTEGRITY -> ApiFixtureSetAuthoringFailure.Code.INTEGRITY;
                case PERSISTENCE -> ApiFixtureSetAuthoringFailure.Code.PERSISTENCE;
            });
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        } catch (RuntimeException failure) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }
    }

    private FixtureShareMaterialization derive(
            FixtureShareIdentity identity, StoredFixtureSet stored, int revision,
            int statusRevision, String reviewRequestId, FixtureShareCommand command) {
        FixtureSetView source = stored.generated().view();
        SchemaEnvelope outputSchema = outputSchema(stored.scope(), source.subject());
        List<FixtureSetCommand.Case> cases = source.cases().stream()
                .map(fixtureCase -> protectCase(identity, source, revision, reviewRequestId,
                        outputSchema, command.policy(), fixtureCase))
                .toList();
        String fingerprint = FixtureSetFingerprints.of(
                source.displayName(), source.subject(), cases);
        FixtureSetView derived = new FixtureSetView(FixtureSetView.SCHEMA_VERSION,
                source.fixtureSetId(), revision, fingerprint, statusRevision,
                source.displayName(), source.subject(), cases, FixtureSetView.Status.SHARING_PENDING);
        List<String> caseIds = cases.stream().map(FixtureSetCommand.Case::caseId).toList();
        FixtureSetSaveReceipt saveReceipt = new FixtureSetSaveReceipt(
                FixtureSetSaveReceipt.SCHEMA_VERSION, source.fixtureSetId(), revision, fingerprint,
                source.subject(), caseIds, derived.status(), statusRevision);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                source.fixtureSetId(), revision, fingerprint, source.displayName(), source.subject(),
                cases.stream().map(value -> new FixtureSetSummary.CaseSummary(
                        value.caseId(), value.name())).toList(), derived.status(), statusRevision);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(
                derived, saveReceipt, summary, cases.stream().map(value ->
                new GeneratedDefaultFixture.CaseMapping(value.caseId(), value.caseId())).toList());
        FixtureShareReceipt receipt = new FixtureShareReceipt(FixtureShareReceipt.SCHEMA_VERSION,
                source.fixtureSetId(), source.revision(), revision, fingerprint,
                derived.status(), statusRevision, reviewRequestId);
        return new FixtureShareMaterialization(generated, receipt);
    }

    private FixtureSetCommand.Case protectCase(
            FixtureShareIdentity identity, FixtureSetView source, int revision,
            String reviewRequestId, SchemaEnvelope outputSchema,
            FixtureShareCommand.Policy policy, FixtureSetCommand.Case fixtureCase) {
        if (fixtureCase.controls().size() != 1) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        FixtureSetCommand.Control control = fixtureCase.controls().getFirst();
        if (!(control.target() instanceof FixtureSetCommand.Target.Subject)
                || !(control.behavior() instanceof FixtureSetCommand.Behavior.Return returned)
                || !(returned.material() instanceof FixtureSetCommand.Material.Inline inline)
                || (control.fidelity() != null
                && control.fidelity() != FixtureSetCommand.Fidelity.OUTPUT_LEVEL)
                || fixtureCase.expect() == null
                || !fixtureCase.expect().output().equals(inline.value())) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        String assetId = governedAssetId(source, revision, fixtureCase.caseId());
        FixtureSetShareMaterialWriter.Result protectedMaterial = materials.write(
                new FixtureSetShareMaterialWriter.Request(
                        assetId, source, revision, reviewRequestId, fixtureCase.caseId(),
                        fixtureCase.name(), outputSchema, policy, inline.value()), identity);
        FixtureSetCommand.Control protectedControl = new FixtureSetCommand.Control(
                control.target(), FixtureSetCommand.Behavior.returned(protectedMaterial.material()),
                control.fidelity());
        return new FixtureSetCommand.Case(fixtureCase.caseId(), fixtureCase.name(),
                fixtureCase.input(), fixtureCase.when(), List.of(protectedControl),
                new FixtureSetCommand.Expect(protectedMaterial.safeOutput()));
    }

    /** Resolves the exact immutable contract named by the Fixture subject without reading material. */
    private SchemaEnvelope outputSchema(AuthoringScope scope, FixtureSubjectRef subject) {
        if (subject instanceof FixtureSubjectRef.FlowVersion versionSubject) {
            ReusableFlowVersion version = publications.findVersion(
                            scope, versionSubject.publicationId(), versionSubject.revision())
                    .orElseThrow(() -> failure(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND));
            if (!version.subject().equals(versionSubject)) {
                throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
            }
            return version.contract().output();
        }
        if (subject instanceof FixtureSubjectRef.FlowDraft draftSubject) {
            if (drafts == null) {
                throw failure(ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
            }
            ReusableFlowDraft draft = drafts.findDraftRevisionStored(
                            scope, draftSubject.draftId(), draftSubject.revision())
                    .map(stored -> stored.draft())
                    .orElseThrow(() -> failure(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND));
            if (!draft.subject().equals(draftSubject)) {
                throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
            }
            return draft.contract().output();
        }
        throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
    }

    private static String governedAssetId(
            FixtureSetView source, int revision, String caseId) {
        String fingerprint = AuthoringFingerprints.of(JSON.valueToTree(Map.of(
                "fixtureSetId", source.fixtureSetId(), "sourceRevision", source.revision(),
                "derivedRevision", revision, "caseId", caseId)));
        return "share-" + fingerprint.substring("sha256:".length(), 39);
    }

    private static void requireIdentity(FixtureShareIdentity identity) {
        if (identity == null) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
    }

    private static ApiFixtureSetAuthoringFailure failure(ApiFixtureSetAuthoringFailure.Code code) {
        return new ApiFixtureSetAuthoringFailure(code);
    }
}
