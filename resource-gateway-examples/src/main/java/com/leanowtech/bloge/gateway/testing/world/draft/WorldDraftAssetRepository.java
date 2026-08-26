package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;

import java.util.Optional;

/** Durable boundary for unpublished and published materialized World artifacts. */
public interface WorldDraftAssetRepository {
    StoredAsset saveDraft(WorldDraftMaterializer.MaterializedDraft draft,
                          WorldDraftCandidateService.Access access);

    Optional<StoredAsset> find(String tenantId, String candidateId,
                               String materializationFingerprint,
                               WorldDraftCandidateService.Access access);

    StoredAsset publish(StoredAsset asset, WorldDraftCandidate candidate,
                        WorldDraftPublicationReceipt receipt,
                        WorldDraftCandidateService.Access access);

    /** Immutable repository projection; values remain within the server-owned boundary. */
    record StoredAsset(String tenantId, String candidateId, long materializationRevision,
                       ResourceWorldModel worldModel, WorldDraftRule rule,
                       Optional<Scenario> scenario, WorldDraftProvenance provenance,
                       boolean published, String publicationReceiptFingerprint) {
        public StoredAsset {
            tenantId = text(tenantId);
            candidateId = text(candidateId);
            String assetTenant = tenantId;
            ResourceWorldModel assetWorld = worldModel;
            if (materializationRevision < 1 || worldModel == null || rule == null
                    || scenario == null || provenance == null || !assetTenant.equals(assetWorld.tenantId())
                    || !candidateId.equals(provenance.candidateId())
                    || !provenance.materializationFingerprint().equals(rule.fingerprint())
                    || rule.redactedPayloadRef() == null
                    || !assetTenant.equals(rule.redactedPayloadRef().tenantId())
                    || !candidateId.equals(rule.redactedPayloadRef().candidateId())
                    || rule.redactedPayloadRef().artifactRevision() > materializationRevision
                    || !rule.inputFingerprint().equals(rule.redactedPayloadRef().requestFingerprint())
                    || !rule.responseFingerprint().equals(rule.redactedPayloadRef().responseFingerprint())
                    || scenario.stream().anyMatch(value -> !value.context().isEmpty()
                    || !value.stateInit().isEmpty() || !value.expect().isEmpty()
                    || !assetTenant.equals(value.tenantId())
                    || !value.world().worldModelId().equals(assetWorld.worldModelId())
                    || value.world().revision() != assetWorld.revision()
                    || !value.world().fingerprint().equals(assetWorld.fingerprint()))
                    || (published && !fp(publicationReceiptFingerprint))) {
                throw invalid();
            }
            scenario = scenario.isEmpty() ? Optional.empty() : Optional.of(scenario.get());
            publicationReceiptFingerprint = publicationReceiptFingerprint == null
                    ? "" : publicationReceiptFingerprint;
        }

        public StoredAsset(WorldDraftMaterializer.MaterializedDraft draft) {
            this(draft == null ? null : draft.worldModel().tenantId(),
                    draft == null ? null : draft.candidate().candidateId(),
                    draft == null ? 0 : draft.candidate().revision(),
                    draft == null ? null : draft.worldModel(), draft == null ? null : draft.rule(),
                    draft == null ? null : draft.scenario(), draft == null ? null : draft.provenance(),
                    false, "");
        }

        public String materializationFingerprint() { return rule.fingerprint(); }

        public StoredAsset asPublished(String receiptFingerprint) {
            return new StoredAsset(tenantId, candidateId, materializationRevision, worldModel, rule,
                    scenario, provenance, true, receiptFingerprint);
        }

        private static String text(String value) {
            if (value == null || value.isBlank() || value.length() > 255
                    || value.chars().anyMatch(Character::isISOControl)) throw invalid();
            return value.trim();
        }
        private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
        private static WorldDraftCandidateException invalid() {
            return new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        }
    }
}
