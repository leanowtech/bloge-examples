package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.time.Clock;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Coordinates metadata-gated capture, redaction, review, and CAS promotion of World drafts. */
public final class WorldDraftCandidateService {
    public static final String PURPOSE = "WORLD_DRAFT_CAPTURE";
    private final WorldDraftSourceAuthority sourceAuthority;
    private final WorldDraftCandidateRepository repository;
    private final WorldDraftRedactor redactor;
    private final WorldDraftRedactedPayloadVault vault;
    private final WorldDraftMaterializer materializer;
    private final WorldDraftApprovalAuthority approvalAuthority;
    private final WorldDraftPublicationAuthority publicationAuthority;
    private final WorldDraftAssetRepository assetRepository;
    private final WorldDraftAuthorityReceiptRepository receiptRepository;
    private final WorldDraftPromotionTransaction promotionTransaction;
    private final Clock clock;

    /** Minimum authenticated purpose and tenant context used for both source phases. */
    public record Access(String tenantId, String purpose, String actorId, String correlationId) {
        public Access {
            tenantId = WorldDraftCandidateGuards.text(tenantId, 255);
            purpose = WorldDraftCandidateGuards.text(purpose, 128);
            actorId = WorldDraftCandidateGuards.text(actorId, 255);
            correlationId = WorldDraftCandidateGuards.text(correlationId, 255);
            if (!PURPOSE.equals(purpose)) throw WorldDraftCandidateGuards.invalid();
        }
    }

    public WorldDraftCandidateService(WorldDraftSourceAuthority sourceAuthority,
                                      WorldDraftCandidateRepository repository,
                                      WorldDraftRedactor redactor,
                                      WorldDraftRedactedPayloadVault vault,
                                      WorldDraftMaterializer materializer, Clock clock) {
        this(sourceAuthority, repository, redactor, vault, materializer,
                ServerOwnedWorldDraftAuthorities.approval(clock), ServerOwnedWorldDraftAuthorities.publication(),
                new InMemoryWorldDraftAssetRepository(), new InMemoryWorldDraftAuthorityReceiptRepository(), null, clock);
    }

    public WorldDraftCandidateService(WorldDraftSourceAuthority sourceAuthority,
                                      WorldDraftCandidateRepository repository,
                                      WorldDraftRedactor redactor,
                                      WorldDraftRedactedPayloadVault vault,
                                      WorldDraftMaterializer materializer,
                                      WorldDraftApprovalAuthority approvalAuthority,
                                      WorldDraftPublicationAuthority publicationAuthority,
                                      Clock clock) {
        this(sourceAuthority, repository, redactor, vault, materializer, approvalAuthority,
                publicationAuthority, new InMemoryWorldDraftAssetRepository(),
                new InMemoryWorldDraftAuthorityReceiptRepository(), null, clock);
    }

    public WorldDraftCandidateService(WorldDraftSourceAuthority sourceAuthority,
                                      WorldDraftCandidateRepository repository,
                                      WorldDraftRedactor redactor,
                                      WorldDraftRedactedPayloadVault vault,
                                      WorldDraftMaterializer materializer,
                                      WorldDraftApprovalAuthority approvalAuthority,
                                      WorldDraftPublicationAuthority publicationAuthority,
                                      WorldDraftAssetRepository assetRepository,
                                      WorldDraftAuthorityReceiptRepository receiptRepository,
                                      Clock clock) {
        this(sourceAuthority, repository, redactor, vault, materializer, approvalAuthority, publicationAuthority,
                assetRepository, receiptRepository, null, clock);
    }

    public WorldDraftCandidateService(WorldDraftSourceAuthority sourceAuthority,
                                      WorldDraftCandidateRepository repository,
                                      WorldDraftRedactor redactor,
                                      WorldDraftRedactedPayloadVault vault,
                                      WorldDraftMaterializer materializer,
                                      WorldDraftApprovalAuthority approvalAuthority,
                                      WorldDraftPublicationAuthority publicationAuthority,
                                      WorldDraftAssetRepository assetRepository,
                                      WorldDraftAuthorityReceiptRepository receiptRepository,
                                      WorldDraftPromotionTransaction promotionTransaction,
                                      Clock clock) {
        if (sourceAuthority == null || repository == null || redactor == null
                || vault == null || materializer == null || approvalAuthority == null
                || publicationAuthority == null || assetRepository == null || receiptRepository == null
                || clock == null) throw WorldDraftCandidateGuards.invalid();
        this.sourceAuthority = sourceAuthority; this.repository = repository;
        this.redactor = redactor; this.vault = vault; this.materializer = materializer;
        this.approvalAuthority = approvalAuthority; this.publicationAuthority = publicationAuthority;
        this.assetRepository = assetRepository; this.receiptRepository = receiptRepository;
        this.promotionTransaction = promotionTransaction == null
                ? new InMemoryWorldDraftPromotionTransaction(repository,
                requireInMemoryAssets(assetRepository), requireInMemoryReceipts(receiptRepository), publicationAuthority,
                vault)
                : promotionTransaction;
        this.clock = clock;
    }

    public WorldDraftCandidateService(WorldDraftSourceAuthority sourceAuthority,
                                      WorldDraftCandidateRepository repository,
                                      WorldDraftRedactor redactor,
                                      WorldDraftMaterializer materializer, Clock clock) {
        this(sourceAuthority, repository, redactor, new InMemoryWorldDraftRedactedPayloadVault(), materializer, clock);
    }

    public WorldDraftCandidateService(WorldDraftSourceAuthority sourceAuthority,
                                      WorldDraftCandidateRepository repository,
                                      WorldDraftMaterializer materializer, Clock clock) {
        this(sourceAuthority, repository, WorldDraftRedactor.schemaGuided(), materializer, clock);
    }

    /** Reads metadata first; source payload is not retained after this method returns. */
    public WorldDraftCandidate capture(String candidateId, Access access, WorldDraftSourceRef source) {
        if (source == null || access == null || !access.tenantId().equals(source.tenantId()))
            throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        WorldDraftSourceAuthority.SourceMetadata metadata = WorldDraftCandidateGuards.inspect(
                sourceAuthority, source, access);
        WorldDraftCandidateGuards.metadata(metadata, source, access, clock);
        WorldDraftSourceAuthority.SourcePayload payload = WorldDraftCandidateGuards.read(
                sourceAuthority, metadata, access);
        if (!metadata.requestFingerprint().equals(payload.requestFingerprint())
                || !metadata.responseFingerprint().equals(payload.responseFingerprint())) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        WorldDraftCandidate candidate = new WorldDraftCandidate(candidateId, 1, WorldDraftState.CAPTURED,
                access.tenantId(), source, metadata.metadataFingerprint(), metadata.schemaFingerprint(),
                metadata.redactionPolicyFingerprint(), metadata.requestFingerprint(), metadata.responseFingerprint(),
                null, "", WorldDraftRedactionReport.notProcessed(), "", "");
        try { return repository.create(candidate); }
        catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.CAS_CONFLICT); }
    }

    /** Re-reads the authorized source, redacts it, then discards the raw value. */
    public WorldDraftCandidate redact(String id, long expected, Access access,
                                      WorldDraftRedactionPolicy policy) {
        return update(id, expected, access, current -> {
            WorldDraftCandidateGuards.require(current, WorldDraftState.CAPTURED, WorldDraftState.REDACTION_REQUIRED);
            if (access == null || !current.tenantId().equals(access.tenantId())) throw WorldDraftCandidateGuards.fail(
                    WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
            var metadata = WorldDraftCandidateGuards.inspect(sourceAuthority, current.source(), access);
            WorldDraftCandidateGuards.metadata(metadata, current.source(), access, clock);
            if (!metadata.metadataFingerprint().equals(current.sourceMetadataFingerprint()) || policy == null
                    || !policy.fingerprint().equals(metadata.redactionPolicyFingerprint())) throw WorldDraftCandidateGuards.fail(
                    WorldDraftCandidateException.Code.SOURCE_POLICY_DENIED);
            var payload = WorldDraftCandidateGuards.read(sourceAuthority, metadata, access);
            if (!metadata.requestFingerprint().equals(payload.requestFingerprint())
                    || !metadata.responseFingerprint().equals(payload.responseFingerprint())) throw WorldDraftCandidateGuards.fail(
                    WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
            try {
                var result = redactor.redact(payload, metadata, policy);
                if (!result.report().schemaValid()
                        || !VisualSchemaValidator.validateValue(metadata.requestSchema(), result.payload().request(), "/request").isEmpty()
                        || !VisualSchemaValidator.validateValue(metadata.responseSchema(), result.payload().response(), "/response").isEmpty()) {
                    throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.REDACTION_REQUIRED);
                }
                long targetRevision = Math.addExact(current.revision(), 1);
                WorldDraftRedactedPayloadRef ref = WorldDraftRedactedPayloadRef.of(
                        current.tenantId(), current.candidateId(), targetRevision, result.payload());
                WorldDraftRedactedPayloadVault.StoredPayload stored = vault.put(ref, result.payload(), access);
                if (!ref.equals(stored.ref())) throw WorldDraftCandidateGuards.fail(
                        WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
                return current.next(WorldDraftState.REDACTION_REQUIRED, "", "", ref,
                        result.report().fingerprint(), result.report());
            } catch (WorldDraftCandidateException failure) { throw failure; }
            catch (RuntimeException failure) { throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.REDACTION_REQUIRED); }
        });
    }

    public WorldDraftCandidate markReviewReady(String id, long expected, Access access) {
        return update(id, expected, access, current -> {
            WorldDraftCandidateGuards.require(current, WorldDraftState.REDACTION_REQUIRED);
            if (!current.redactionReport().safe()) throw WorldDraftCandidateGuards.fail(
                    WorldDraftCandidateException.Code.REDACTION_REQUIRED);
            return current.next(WorldDraftState.REVIEW_READY, "", "", current.redactedPayloadRef(),
                    current.redactionReportFingerprint(), current.redactionReport());
        });
    }

    public WorldDraftCandidate approve(String id, long expected, Access access) {
        return update(id, expected, access, current -> {
            WorldDraftCandidateGuards.require(current, WorldDraftState.REVIEW_READY);
            WorldDraftApproval approval = issueApproval(current, access);
            if (!approval.candidateId().equals(current.candidateId())
                    || approval.candidateRevision() != current.revision()
                    || !approval.sourceFingerprint().equals(current.source().fingerprint())
                    || !approval.schemaFingerprint().equals(current.schemaFingerprint())
                    || !approval.redactionPolicyFingerprint().equals(current.redactionPolicyFingerprint())
                    || approval.approvedAt().isAfter(clock.instant())) throw WorldDraftCandidateGuards.fail(
                    WorldDraftCandidateException.Code.APPROVAL_INVALID);
            storeApproval(approval, access);
            return current.next(WorldDraftState.APPROVED, approval.fingerprint(), "",
                    current.redactedPayloadRef(), current.redactionReportFingerprint(), current.redactionReport());
        });
    }

    public WorldDraftMaterializer.MaterializedDraft materialize(String id, long expected, Access access,
                                                                 ResourceWorldModel baseWorld) {
        WorldDraftCandidate current = findRequired(id, access);
        if (current.revision() != expected) throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.CAS_CONFLICT);
        WorldDraftCandidateGuards.require(current, WorldDraftState.APPROVED);
        if (access == null || baseWorld == null || !current.tenantId().equals(access.tenantId())
                || !current.tenantId().equals(baseWorld.tenantId())) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        var metadata = WorldDraftCandidateGuards.inspect(sourceAuthority, current.source(), access);
        WorldDraftCandidateGuards.metadata(metadata, current.source(), access, clock);
        if (!metadata.metadataFingerprint().equals(current.sourceMetadataFingerprint())) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.APPROVAL_STALE);
        if (current.redactedPayloadRef() == null) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        WorldDraftApproval persistedApproval = receiptRepository.findApproval(current.tenantId(),
                current.candidateId(), current.approvalFingerprint(), access).orElseThrow(() ->
                WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.APPROVAL_STALE));
        if (!persistedApproval.fingerprint().equals(current.approvalFingerprint())
                || persistedApproval.candidateRevision() >= current.revision()
                || !persistedApproval.sourceFingerprint().equals(current.source().fingerprint())
                || !persistedApproval.schemaFingerprint().equals(current.schemaFingerprint())
                || !persistedApproval.redactionPolicyFingerprint().equals(current.redactionPolicyFingerprint())) {
            throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.APPROVAL_STALE);
        }
        WorldDraftRedactedPayloadVault.StoredPayload redacted;
        try {
            redacted = vault.read(current.redactedPayloadRef(), access).orElseThrow(() ->
                    WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID));
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.MATERIALIZATION_INVALID); }
        if (!current.redactedPayloadRef().equals(redacted.ref())) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        WorldDraftMaterializer.MaterializedDraft draft;
        try { draft = materializer.materialize(new WorldDraftMaterializer.MaterializationRequest(
                current, baseWorld, access, redacted)); }
        catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID); }
        if (draft == null || draft.published() || draft.candidate() != current
                || !draft.provenance().matches(current, draft.rule())
                || draft.rule().redactedPayloadRef() == null
                || !draft.rule().redactedPayloadRef().equals(current.redactedPayloadRef())
                || !draft.rule().requestSchemaFingerprint().equals(current.schemaFingerprint())
                || !draft.rule().inputFingerprint().equals(redacted.ref().requestFingerprint())
                || !draft.rule().responseFingerprint().equals(redacted.ref().responseFingerprint())
                || !draft.worldModel().tenantId().equals(current.tenantId())
                || !draft.worldModel().worldModelId().equals(baseWorld.worldModelId())
                || baseWorld.revision() == Long.MAX_VALUE
                || draft.worldModel().revision() != baseWorld.revision() + 1
                || draft.scenario().stream().anyMatch(scenario -> !scenario.expect().isEmpty()
                || !scenario.tenantId().equals(current.tenantId())
                || !scenario.world().worldModelId().equals(draft.worldModel().worldModelId())
                || scenario.world().revision() != draft.worldModel().revision()
                || !scenario.world().fingerprint().equals(draft.worldModel().fingerprint())
                || !scenario.context().isEmpty() || !scenario.stateInit().isEmpty())) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        WorldDraftCandidate replacement = current.next(WorldDraftState.MATERIALIZED_DRAFT,
                current.approvalFingerprint(), draft.rule().fingerprint(), current.redactedPayloadRef(),
                current.redactionReportFingerprint(), current.redactionReport());
        WorldDraftAssetRepository.StoredAsset storedAsset;
        try {
            storedAsset = assetRepository.saveDraft(draft, access);
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) {
            throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        }
        if (storedAsset == null || storedAsset.published()
                || !storedAsset.candidateId().equals(current.candidateId())
                || !storedAsset.materializationFingerprint().equals(replacement.materializationFingerprint())) {
            throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        }
        if (!repository.compareAndSet(current, replacement)) throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.CAS_CONFLICT);
        return draft;
    }

    public WorldDraftCandidate publish(String id, long expected, Access access) {
        WorldDraftCandidate current = findRequired(id, access);
        if (current.revision() != expected) throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.CAS_CONFLICT);
        WorldDraftCandidateGuards.require(current, WorldDraftState.MATERIALIZED_DRAFT);
        verifyCurrentMetadata(current, access);
        try {
            return promotionTransaction.promote(current, access);
        } catch (WorldDraftCandidateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        }
    }

    public Optional<WorldDraftCandidate> find(String id, Access access) {
        if (access == null) return Optional.empty();
        return repository.find(access.tenantId(), id);
    }

    private WorldDraftCandidate update(String id, long expected, Access access,
                                       UnaryOperator<WorldDraftCandidate> change) {
        WorldDraftCandidate current = findRequired(id, access);
        if (current.revision() != expected) throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.CAS_CONFLICT);
        WorldDraftCandidate replacement = change.apply(current);
        if (replacement == null || !repository.compareAndSet(current, replacement)) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.CAS_CONFLICT);
        return replacement;
    }
    private WorldDraftCandidate findRequired(String id, Access access) {
        if (access == null) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        return repository.find(access.tenantId(), id).orElseThrow(() -> WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.CANDIDATE_NOT_FOUND));
    }

    private WorldDraftApproval issueApproval(WorldDraftCandidate candidate, Access access) {
        try {
            WorldDraftApproval receipt = approvalAuthority.issue(candidate, access);
            if (receipt == null) throw WorldDraftCandidateGuards.invalid();
            return receipt;
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.APPROVAL_INVALID); }
    }

    private void storeApproval(WorldDraftApproval receipt, Access access) {
        try {
            receiptRepository.saveApproval(receipt, access);
            if (receiptRepository.findApproval(access.tenantId(), receipt.candidateId(),
                    receipt.fingerprint(), access).isEmpty()) {
                throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.APPROVAL_INVALID);
            }
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) {
            throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.APPROVAL_INVALID);
        }
    }

    private static InMemoryWorldDraftAssetRepository requireInMemoryAssets(WorldDraftAssetRepository repository) {
        if (repository instanceof InMemoryWorldDraftAssetRepository inMemory) return inMemory;
        throw WorldDraftCandidateGuards.invalid();
    }

    private static InMemoryWorldDraftAuthorityReceiptRepository requireInMemoryReceipts(
            WorldDraftAuthorityReceiptRepository repository) {
        if (repository instanceof InMemoryWorldDraftAuthorityReceiptRepository inMemory) return inMemory;
        throw WorldDraftCandidateGuards.invalid();
    }

    private void verifyCurrentMetadata(WorldDraftCandidate candidate, Access access) {
        if (access == null || !candidate.tenantId().equals(access.tenantId())) throw WorldDraftCandidateGuards.fail(
                WorldDraftCandidateException.Code.PUBLICATION_INVALID);
        var metadata = WorldDraftCandidateGuards.inspect(sourceAuthority, candidate.source(), access);
        WorldDraftCandidateGuards.metadata(metadata, candidate.source(), access, clock);
        if (!metadata.metadataFingerprint().equals(candidate.sourceMetadataFingerprint())
                || !metadata.schemaFingerprint().equals(candidate.schemaFingerprint())
                || !metadata.redactionPolicyFingerprint().equals(candidate.redactionPolicyFingerprint())) {
            throw WorldDraftCandidateGuards.fail(WorldDraftCandidateException.Code.APPROVAL_STALE);
        }
    }
}
