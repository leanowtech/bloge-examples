package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;

import java.util.Optional;

/** Server-owned port that turns a payload-free candidate into an unpublished World draft. */
@FunctionalInterface
public interface WorldDraftMaterializer {
    MaterializedDraft materialize(MaterializationRequest request);

    record MaterializationRequest(WorldDraftCandidate candidate,
                                  ResourceWorldModel baseWorld,
                                  WorldDraftCandidateService.Access access,
                                  WorldDraftRedactedPayloadVault.StoredPayload redactedPayload) {
        public MaterializationRequest {
            if (candidate == null || baseWorld == null || access == null || redactedPayload == null
                    || candidate.redactedPayloadRef() == null
                    || !candidate.redactedPayloadRef().equals(redactedPayload.ref())) throw invalid();
        }

        Object redactedRequest() {
            if (redactedPayload == null) throw invalid();
            return redactedPayload.request();
        }

        Object redactedResponse() {
            if (redactedPayload == null) throw invalid();
            return redactedPayload.response();
        }
    }

    record MaterializedDraft(WorldDraftCandidate candidate,
                             ResourceWorldModel worldModel,
                             WorldDraftRule rule,
                             Optional<Scenario> scenario,
                             WorldDraftProvenance provenance,
                             boolean published) {
        public MaterializedDraft {
            if (candidate == null || worldModel == null || rule == null || scenario == null
                    || provenance == null || published) throw invalid();
            scenario = scenario.isEmpty() ? Optional.empty() : Optional.of(scenario.get());
        }

        public MaterializedDraft(WorldDraftCandidate candidate, ResourceWorldModel worldModel,
                                 WorldDraftRule rule, boolean published) {
            this(candidate, worldModel, rule, Optional.empty(), WorldDraftProvenance.of(candidate, rule), published);
        }

        public WorldDraftMaterializedProjection payloadFreeProjection() {
            return new WorldDraftMaterializedProjection(candidate.candidateId(), worldModel.tenantId(),
                    worldModel.fingerprint(), worldModel.revision(), rule.fingerprint(),
                    rule.fragment() == null ? "" : rule.fragment().fingerprint(), scenario.isPresent(), published);
        }

        /** Scenario context is server-owned payload and is never part of ordinary JSON output. */
        @JsonIgnore
        @Override public Optional<Scenario> scenario() { return scenario; }

        @Override public String toString() {
            return "MaterializedDraft[candidateId=" + candidate.candidateId() + ",worldFingerprint="
                    + worldModel.fingerprint() + ",ruleFingerprint=" + rule.fingerprint()
                    + ",scenarioPresent=" + scenario.isPresent() + ",published=" + published + "]";
        }
    }

    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
    }
}
