package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewMaterialization;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetReviewIntent;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetReviewResult;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deep application module for reviewer-owned pending-to-team Fixture activation. */
public final class ReusableFlowFixtureReviewModule {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final StandaloneFixtureSetStore store;
    private final FixtureSetReviewMaterialGate materialGate;

    public ReusableFlowFixtureReviewModule(
            StandaloneFixtureSetStore store, FixtureSetReviewMaterialGate materialGate) {
        this.store = Objects.requireNonNull(store, "store");
        this.materialGate = Objects.requireNonNull(materialGate, "materialGate");
    }

    /** Completes one exact independent review and returns its immutable TEAM_AVAILABLE revision. */
    public StandaloneFixtureSetReviewResult review(
            FixtureShareIdentity reviewer, String fixtureSetId, String sourceStrongEtag,
            String idempotencyKey, FixtureReviewCommand command) {
        if (reviewer == null || command == null || fixtureSetId == null
                || !fixtureSetId.equals(command.source().fixtureSetId())) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        }
        try {
            String fingerprint = AuthoringFingerprints.of(JSON.valueToTree(command));
            return store.review(new StandaloneFixtureSetReviewIntent(
                    reviewer.scope(), reviewer.actorId(), fixtureSetId, sourceStrongEtag,
                    idempotencyKey, fingerprint, command),
                    (source, revision, statusRevision) -> derive(
                            reviewer, source, revision, statusRevision, idempotencyKey, command));
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

    private FixtureReviewMaterialization derive(
            FixtureShareIdentity reviewer, StoredFixtureSet source, int revision,
            int statusRevision, String idempotencyKey, FixtureReviewCommand command) {
        List<FixtureSetCommand.Material.FixtureAsset> proposed = proposedAssets(source);
        List<FixtureSetCommand.Material.FixtureAsset> active = materialGate.reviewAndActivate(
                new FixtureSetReviewMaterialGate.Request(command.source().reviewRequestId(),
                        proposed, command.attestations(), idempotencyKey), reviewer);
        Map<String, FixtureSetCommand.Material.FixtureAsset> replacements = exactReplacements(
                proposed, active);
        List<FixtureSetCommand.Case> cases = source.generated().view().cases().stream()
                .map(value -> activateCase(value, replacements)).toList();
        FixtureSetView prior = source.generated().view();
        String fingerprint = FixtureSetFingerprints.of(prior.displayName(), prior.subject(), cases);
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION,
                prior.fixtureSetId(), revision, fingerprint, statusRevision,
                prior.displayName(), prior.subject(), cases, FixtureSetView.Status.TEAM_AVAILABLE);
        List<String> caseIds = cases.stream().map(FixtureSetCommand.Case::caseId).toList();
        FixtureSetSaveReceipt saveReceipt = new FixtureSetSaveReceipt(
                FixtureSetSaveReceipt.SCHEMA_VERSION, view.fixtureSetId(), revision, fingerprint,
                view.subject(), caseIds, view.status(), statusRevision);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                view.fixtureSetId(), revision, fingerprint, view.displayName(), view.subject(),
                cases.stream().map(value -> new FixtureSetSummary.CaseSummary(
                        value.caseId(), value.name())).toList(), view.status(), statusRevision);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(view, saveReceipt, summary,
                cases.stream().map(value -> new GeneratedDefaultFixture.CaseMapping(
                        value.caseId(), value.caseId())).toList());
        FixtureReviewReceipt receipt = new FixtureReviewReceipt(
                FixtureReviewReceipt.SCHEMA_VERSION, command.source().reviewRequestId(),
                view.fixtureSetId(), prior.revision(), revision, fingerprint, view.status(),
                statusRevision, active.size());
        return new FixtureReviewMaterialization(generated, receipt);
    }

    private static List<FixtureSetCommand.Material.FixtureAsset> proposedAssets(StoredFixtureSet source) {
        List<FixtureSetCommand.Material.FixtureAsset> assets = source.generated().view().cases().stream()
                .flatMap(value -> value.controls().stream())
                .map(FixtureSetCommand.Control::behavior)
                .filter(FixtureSetCommand.Behavior.Return.class::isInstance)
                .map(FixtureSetCommand.Behavior.Return.class::cast)
                .map(FixtureSetCommand.Behavior.Return::material)
                .filter(FixtureSetCommand.Material.FixtureAsset.class::isInstance)
                .map(FixtureSetCommand.Material.FixtureAsset.class::cast)
                .toList();
        if (assets.isEmpty()) throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        return assets;
    }

    private static Map<String, FixtureSetCommand.Material.FixtureAsset> exactReplacements(
            List<FixtureSetCommand.Material.FixtureAsset> proposed,
            List<FixtureSetCommand.Material.FixtureAsset> active) {
        if (active == null || active.size() != proposed.size()) {
            throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }
        Map<String, FixtureSetCommand.Material.FixtureAsset> values = new LinkedHashMap<>();
        for (int index = 0; index < proposed.size(); index++) {
            var before = proposed.get(index);
            var after = active.get(index);
            if (after == null || !after.fixtureAssetId().equals(before.fixtureAssetId())
                    || !after.schemaFingerprint().equals(before.schemaFingerprint())
                    || after.revision() <= before.revision()
                    || values.put(after.fixtureAssetId(), after) != null) {
                throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
            }
        }
        return Map.copyOf(values);
    }

    private static FixtureSetCommand.Case activateCase(
            FixtureSetCommand.Case fixtureCase,
            Map<String, FixtureSetCommand.Material.FixtureAsset> replacements) {
        List<FixtureSetCommand.Control> controls = fixtureCase.controls().stream().map(control -> {
            if (control.behavior() instanceof FixtureSetCommand.Behavior.Return returned
                    && returned.material() instanceof FixtureSetCommand.Material.FixtureAsset asset) {
                var active = replacements.get(asset.fixtureAssetId());
                if (active == null) throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
                return new FixtureSetCommand.Control(control.target(),
                        FixtureSetCommand.Behavior.returned(active), control.fidelity());
            }
            throw failure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }).toList();
        return new FixtureSetCommand.Case(fixtureCase.caseId(), fixtureCase.name(),
                fixtureCase.input(), fixtureCase.when(), controls, fixtureCase.expect());
    }

    private static ApiFixtureSetAuthoringFailure failure(ApiFixtureSetAuthoringFailure.Code code) {
        return new ApiFixtureSetAuthoringFailure(code);
    }
}
