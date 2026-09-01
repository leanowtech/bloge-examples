package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureReviewVerificationRequest;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFailure;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureSetReviewMaterialGate;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureShareIdentity;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Correctness catalog adapter for one reviewer-owned whole-Fixture activation command. */
public final class CorrectnessFixtureSetReviewMaterialGate
        implements FixtureSetReviewMaterialGate {
    private final FixtureCatalogService catalog;
    private final FixtureAssetRepository fixtures;
    private final ObjectMapper mapper;

    /** Creates the gate over the authoritative correctness catalog and fixture repository. */
    public CorrectnessFixtureSetReviewMaterialGate(
            FixtureCatalogService catalog, FixtureAssetRepository fixtures, ObjectMapper mapper) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public List<FixtureSetCommand.Material.FixtureAsset> reviewAndActivate(
            Request request, FixtureShareIdentity reviewer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reviewer, "reviewer");
        EnterpriseScope scope = scope(reviewer);
        PrincipalRef actor = actor(reviewer);
        List<FixtureSetCommand.Material.FixtureAsset> active = new ArrayList<>();
        try {
            for (int index = 0; index < request.proposedAssets().size(); index++) {
                var proposed = request.proposedAssets().get(index);
                requireExactProposed(scope, proposed);
                String approvalKey = CorrectnessProtocolFingerprint.derivedFingerprint(mapper, Map.of(
                        "fixtureReview", request.idempotencyKey(),
                        "reviewRequestId", request.reviewRequestId(),
                        "fixtureAssetId", proposed.fixtureAssetId(), "index", index));
                active.add(resumeAsset(scope, actor, proposed, request, approvalKey));
            }
            return List.copyOf(active);
        } catch (ApiFixtureSetAuthoringFailure failure) {
            throw failure;
        } catch (FixtureCatalogCommandException failure) {
            throw new ApiFixtureSetAuthoringFailure(
                    ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new ApiFixtureSetAuthoringFailure(
                    ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        }
    }

    private FixtureSetCommand.Material.FixtureAsset resumeAsset(
            EnterpriseScope scope, PrincipalRef actor,
            FixtureSetCommand.Material.FixtureAsset proposed, Request request,
            String approvalKey) {
        StoredFixtureAsset head = fixtures.findHead(scope, proposed.fixtureAssetId())
                .orElseThrow(CorrectnessFixtureSetReviewMaterialGate::integrity);
        if (head.descriptor().lifecycle() == FixtureLifecycle.ACTIVE) {
            requireExactStage(head, proposed, proposed.revision() + 3L, FixtureLifecycle.ACTIVE);
            return activeRef(head, proposed);
        }
        StoredFixtureAsset approved;
        if (head.descriptor().lifecycle() == FixtureLifecycle.APPROVED) {
            requireExactStage(head, proposed, proposed.revision() + 2L, FixtureLifecycle.APPROVED);
            approved = head;
        } else {
            StoredFixtureAsset verified;
            if (head.descriptor().revision() == proposed.revision()) {
                requireExactStage(head, proposed, proposed.revision(), FixtureLifecycle.PROPOSED);
                verified = catalog.verifyForApproval(scope, proposed.fixtureAssetId(),
                        proposed.revision(), new FixtureReviewVerificationRequest(
                                request.attestations().redactionReviewed(),
                                request.attestations().redactionVerified(),
                                request.attestations().comment()), actor);
            } else {
                requireExactStage(head, proposed, proposed.revision() + 1L, FixtureLifecycle.PROPOSED);
                if (!head.descriptor().redaction().reviewed()
                        || !head.descriptor().quality().schemaValid()
                        || !head.descriptor().quality().redactionVerified()) {
                    throw integrity();
                }
                verified = head;
            }
            approved = catalog.approveIdempotently(scope, proposed.fixtureAssetId(),
                    verified.descriptor().revision(), request.attestations().comment(),
                    actor, approvalKey).stored();
            requireExactStage(approved, proposed, proposed.revision() + 2L,
                    FixtureLifecycle.APPROVED);
        }
        StoredFixtureAsset activated = catalog.activate(scope, proposed.fixtureAssetId(),
                approved.descriptor().revision(), actor);
        requireExactStage(activated, proposed, proposed.revision() + 3L, FixtureLifecycle.ACTIVE);
        return activeRef(activated, proposed);
    }

    private void requireExactProposed(
            EnterpriseScope scope, FixtureSetCommand.Material.FixtureAsset proposed) {
        StoredFixtureAsset source = fixtures.findRevision(
                        scope, proposed.fixtureAssetId(), proposed.revision())
                .orElseThrow(CorrectnessFixtureSetReviewMaterialGate::integrity);
        requireExactStage(source, proposed, proposed.revision(), FixtureLifecycle.PROPOSED);
    }

    private static void requireExactStage(
            StoredFixtureAsset stored, FixtureSetCommand.Material.FixtureAsset proposed,
            long revision, FixtureLifecycle lifecycle) {
        if (stored.descriptor().revision() != revision
                || stored.descriptor().lifecycle() != lifecycle
                || !stored.descriptor().schemaRef().fingerprint()
                .equals(proposed.schemaFingerprint())) {
            throw integrity();
        }
    }

    private static FixtureSetCommand.Material.FixtureAsset activeRef(
            StoredFixtureAsset active, FixtureSetCommand.Material.FixtureAsset proposed) {
        return new FixtureSetCommand.Material.FixtureAsset(
                proposed.fixtureAssetId(), Math.toIntExact(active.descriptor().revision()),
                proposed.schemaFingerprint());
    }

    private static ApiFixtureSetAuthoringFailure integrity() {
        return new ApiFixtureSetAuthoringFailure(ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
    }

    private static EnterpriseScope scope(FixtureShareIdentity identity) {
        return new EnterpriseScope(identity.scope().tenantId(), identity.organizationId(),
                identity.scope().projectId(), identity.scope().environmentId(), identity.region());
    }

    private static PrincipalRef actor(FixtureShareIdentity identity) {
        PrincipalKind kind = switch (identity.actorType().toUpperCase(java.util.Locale.ROOT)) {
            case "USER", "HUMAN" -> PrincipalKind.USER;
            case "TEAM" -> PrincipalKind.TEAM;
            case "SERVICE", "WORKLOAD" -> PrincipalKind.SERVICE;
            default -> throw new IllegalArgumentException("Fixture review actor type is invalid");
        };
        return new PrincipalRef(identity.actorId(), kind, "");
    }
}
