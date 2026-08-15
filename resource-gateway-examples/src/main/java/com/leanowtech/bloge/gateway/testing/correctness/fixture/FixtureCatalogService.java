package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.QualityProfile;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureReviewAuthorizer.ApprovalDecision;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;

import java.time.Clock;
import java.util.Objects;

/** Governs Fixture descriptor draft, review, activation, and revocation without decrypt capability. */
public final class FixtureCatalogService {

    private final FixtureAssetRepository fixtures;
    private final FixtureMaterialMetadataSource materials;
    private final FixtureSchemaSource schemas;
    private final FixtureReviewAuthorizer authorizer;
    private final Clock clock;

    public FixtureCatalogService(
            FixtureAssetRepository fixtures,
            FixtureMaterialMetadataSource materials,
            FixtureSchemaSource schemas,
            FixtureReviewAuthorizer authorizer) {
        this(fixtures, materials, schemas, authorizer, Clock.systemUTC());
    }

    public FixtureCatalogService(
            FixtureAssetRepository fixtures,
            FixtureMaterialMetadataSource materials,
            FixtureSchemaSource schemas,
            FixtureReviewAuthorizer authorizer,
            Clock clock) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.materials = materials == null ? FixtureMaterialMetadataSource.denyAll() : materials;
        this.schemas = schemas == null ? FixtureSchemaSource.denyAll() : schemas;
        this.authorizer = authorizer == null ? FixtureReviewAuthorizer.denyAll() : authorizer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredFixtureAsset saveDraft(
            long expectedRevision,
            FixtureAssetDescriptor candidate,
            PrincipalRef actor) {
        requireActor(actor);
        if (candidate == null || expectedRevision < 0
                || candidate.revision() != expectedRevision
                || candidate.lifecycle() != FixtureLifecycle.DRAFT) {
            throw failure("RG.CORRECTNESS.FIXTURE_DRAFT_INVALID",
                    "Fixture save requires a matching DRAFT descriptor revision");
        }
        StoredFixtureAsset current = fixtures.findHead(
                candidate.scope(), candidate.fixtureAssetId()).orElse(null);
        if (current != null
                && current.descriptor().lifecycle() != FixtureLifecycle.DRAFT
                && current.descriptor().lifecycle() != FixtureLifecycle.ACTIVE
                && current.descriptor().lifecycle() != FixtureLifecycle.STALE
                && current.descriptor().lifecycle() != FixtureLifecycle.EXPIRED) {
            throw failure("RG.CORRECTNESS.FIXTURE_TRANSITION_REQUIRED",
                    "A Fixture under review, approved, or revoked cannot be edited as a draft");
        }
        if (current != null && !current.descriptor().owner().id().equals(candidate.owner().id())) {
            throw failure("RG.CORRECTNESS.FIXTURE_OWNER_CHANGE_FORBIDDEN",
                    "Fixture ownership transfer requires a separate governance command");
        }
        Receipt receipt = requireExactClosure(candidate);
        FixtureAssetDescriptor normalized = normalized(candidate, receipt, FixtureLifecycle.DRAFT);
        return fixtures.saveIfRevision(expectedRevision, normalized, actor)
                .orElseThrow(FixtureCatalogService::conflict);
    }

    public StoredFixtureAsset submitForReview(
            EnterpriseScope scope,
            String fixtureAssetId,
            long expectedRevision,
            PrincipalRef actor) {
        return transition(scope, fixtureAssetId, expectedRevision, actor,
                FixtureLifecycle.DRAFT, FixtureLifecycle.PROPOSED, false);
    }

    public StoredFixtureAsset approve(
            EnterpriseScope scope,
            String fixtureAssetId,
            long expectedRevision,
            PrincipalRef actor) {
        requireActor(actor);
        StoredFixtureAsset current = requireHead(scope, fixtureAssetId, expectedRevision);
        if (current.descriptor().lifecycle() != FixtureLifecycle.PROPOSED) {
            throw failure("RG.CORRECTNESS.FIXTURE_TRANSITION_INVALID",
                    "Only a proposed Fixture can be approved");
        }
        ApprovalDecision decision = authorizer.authorize(scope, current.descriptor(), actor);
        if (decision == null || !decision.allowed()) {
            throw failure("RG.CORRECTNESS.FIXTURE_APPROVAL_FORBIDDEN",
                    "The actor is not authorized to approve this Fixture");
        }
        if (decision.independentReviewRequired()
                && current.descriptor().metadata().createdBy().id().equals(actor.id())) {
            throw failure("RG.CORRECTNESS.FOUR_EYES_REQUIRED",
                    "Fixture approval requires a reviewer other than its creator");
        }
        requireExactClosure(current.descriptor());
        if (!current.descriptor().redaction().reviewed()
                || !current.descriptor().quality().schemaValid()
                || !current.descriptor().quality().redactionVerified()) {
            throw failure("RG.CORRECTNESS.FIXTURE_REVIEW_INCOMPLETE",
                    "Fixture approval requires verified schema and reviewed redaction");
        }
        return persistTransition(current, FixtureLifecycle.APPROVED, actor);
    }

    public StoredFixtureAsset activate(
            EnterpriseScope scope,
            String fixtureAssetId,
            long expectedRevision,
            PrincipalRef actor) {
        return transition(scope, fixtureAssetId, expectedRevision, actor,
                FixtureLifecycle.APPROVED, FixtureLifecycle.ACTIVE, true);
    }

    public StoredFixtureAsset revoke(
            EnterpriseScope scope,
            String fixtureAssetId,
            long expectedRevision,
            PrincipalRef actor) {
        requireActor(actor);
        StoredFixtureAsset current = requireHead(scope, fixtureAssetId, expectedRevision);
        FixtureLifecycle lifecycle = current.descriptor().lifecycle();
        if (lifecycle != FixtureLifecycle.ACTIVE && lifecycle != FixtureLifecycle.APPROVED
                && lifecycle != FixtureLifecycle.PROPOSED) {
            throw failure("RG.CORRECTNESS.FIXTURE_TRANSITION_INVALID",
                    "Only a proposed, approved, or active Fixture can be revoked");
        }
        ApprovalDecision decision = authorizer.authorize(scope, current.descriptor(), actor);
        if (decision == null || !decision.allowed()) {
            throw failure("RG.CORRECTNESS.FIXTURE_REVOKE_FORBIDDEN",
                    "The actor is not authorized to revoke this Fixture");
        }
        return persistTransition(current, FixtureLifecycle.REVOKED, actor);
    }

    private StoredFixtureAsset transition(
            EnterpriseScope scope,
            String fixtureAssetId,
            long expectedRevision,
            PrincipalRef actor,
            FixtureLifecycle from,
            FixtureLifecycle to,
            boolean requireClosure) {
        requireActor(actor);
        StoredFixtureAsset current = requireHead(scope, fixtureAssetId, expectedRevision);
        if (current.descriptor().lifecycle() != from) {
            throw failure("RG.CORRECTNESS.FIXTURE_TRANSITION_INVALID",
                    "Fixture lifecycle transition is invalid");
        }
        if (requireClosure) requireExactClosure(current.descriptor());
        return persistTransition(current, to, actor);
    }

    private StoredFixtureAsset persistTransition(
            StoredFixtureAsset current,
            FixtureLifecycle lifecycle,
            PrincipalRef actor) {
        FixtureAssetDescriptor candidate = current.descriptor().withLifecycle(lifecycle);
        return fixtures.saveIfRevision(current.descriptor().revision(), candidate, actor)
                .orElseThrow(FixtureCatalogService::conflict);
    }

    private StoredFixtureAsset requireHead(
            EnterpriseScope scope, String fixtureAssetId, long expectedRevision) {
        requireCoordinate(scope, fixtureAssetId, expectedRevision);
        StoredFixtureAsset current = fixtures.findHead(scope, fixtureAssetId.trim())
                .orElseThrow(() -> failure("RG.CORRECTNESS.FIXTURE_NOT_FOUND",
                        "Fixture was not found in the authorized enterprise scope"));
        if (current.descriptor().revision() != expectedRevision) throw conflict();
        return current;
    }

    private Receipt requireExactClosure(FixtureAssetDescriptor descriptor) {
        if (!schemas.schemaIsCurrent(descriptor.scope(), descriptor.schemaRef())) {
            throw failure("RG.CORRECTNESS.FIXTURE_SCHEMA_DRIFT",
                    "Fixture schema reference is missing or no longer exact");
        }
        Receipt receipt = materials.findReceipt(descriptor.scope(), descriptor.materialRef())
                .orElseThrow(() -> failure("RG.CORRECTNESS.FIXTURE_MATERIAL_DRIFT",
                        "Fixture material reference is missing or no longer exact"));
        boolean matches = receipt.fixtureAssetId().equals(descriptor.fixtureAssetId())
                && receipt.materialRef().equals(descriptor.materialRef())
                && receipt.schemaRef().equals(descriptor.schemaRef())
                && receipt.source().equals(descriptor.source())
                && receipt.classification().equals(descriptor.classification())
                && receipt.retention().equals(descriptor.retention())
                && receipt.redaction().equals(descriptor.redaction());
        if (!matches) {
            throw failure("RG.CORRECTNESS.FIXTURE_MATERIAL_BINDING_MISMATCH",
                    "Fixture descriptor does not match the protected material receipt");
        }
        if (!receipt.retention().expiresAt().isAfter(clock.instant())) {
            throw failure("RG.CORRECTNESS.FIXTURE_MATERIAL_EXPIRED",
                    "Fixture material retention elapsed before catalog activation");
        }
        return receipt;
    }

    private static FixtureAssetDescriptor normalized(
            FixtureAssetDescriptor candidate,
            Receipt receipt,
            FixtureLifecycle lifecycle) {
        QualityProfile quality = new QualityProfile(
                true, receipt.redaction().reviewed(), 0, 0);
        return new FixtureAssetDescriptor(
                candidate.schemaVersion(), candidate.fixtureAssetId(), candidate.revision(),
                candidate.scope(), candidate.name(), receipt.source(), receipt.materialRef(),
                receipt.schemaRef(), candidate.variantKey(), lifecycle, receipt.classification(),
                candidate.owner(), receipt.redaction(), receipt.retention(), quality,
                candidate.tags(), candidate.metadata());
    }

    private static void requireCoordinate(
            EnterpriseScope scope, String fixtureAssetId, long expectedRevision) {
        if (scope == null || fixtureAssetId == null || fixtureAssetId.isBlank()
                || expectedRevision < 1) {
            throw failure("RG.CORRECTNESS.FIXTURE_COORDINATE_INVALID",
                    "Exact Fixture scope, id, and revision are required");
        }
    }

    private static void requireActor(PrincipalRef actor) {
        if (actor == null) {
            throw failure("RG.CORRECTNESS.ACTOR_REQUIRED",
                    "An authenticated Fixture command actor is required");
        }
    }

    private static FixtureCatalogCommandException conflict() {
        return failure("RG.CORRECTNESS.REVISION_CONFLICT",
                "Fixture changed; reload the exact head and retry");
    }

    private static FixtureCatalogCommandException failure(String code, String message) {
        return new FixtureCatalogCommandException(code, message);
    }
}
