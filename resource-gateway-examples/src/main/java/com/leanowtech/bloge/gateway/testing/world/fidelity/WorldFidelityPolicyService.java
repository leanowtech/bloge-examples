package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Applies current drift policy to historical evidence without rewriting that evidence. */
public final class WorldFidelityPolicyService {
    private final WorldFidelityDriftRepository repository;
    private final ObjectMapper mapper;

    public WorldFidelityPolicyService(WorldFidelityDriftRepository repository, ObjectMapper mapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public WorldFidelityPolicyDecision decide(String tenantId, String targetFingerprint,
                                              TestRunEvidence evidence) {
        targetFingerprint = WorldFidelityRunner.fingerprint(targetFingerprint);
        if (evidence == null || !targetFingerprint.equals(evidence.targetFingerprint())
                || !TestSemanticResultFingerprint.matches(mapper, evidence)) {
            throw WorldFidelityException.of(WorldFidelityException.Code.EVIDENCE_INVALID);
        }
        String evidenceFingerprint;
        try {
            evidenceFingerprint = ProtocolFingerprint.of(mapper, evidence);
        } catch (RuntimeException failure) {
            throw WorldFidelityException.of(WorldFidelityException.Code.EVIDENCE_INVALID);
        }
        boolean certifiableEvidence = evidence.status() == TestRunEvidence.Status.PASSED
                && evidence.evidenceClass() == TestRunEvidence.EvidenceClass.CERTIFIABLE;
        return decide(tenantId, targetFingerprint, evidenceFingerprint, certifiableEvidence);
    }

    public WorldFidelityPolicyDecision decide(String tenantId, String targetFingerprint,
                                              String evidenceFingerprint) {
        return decide(tenantId, targetFingerprint, evidenceFingerprint, true);
    }

    private WorldFidelityPolicyDecision decide(String tenantId, String targetFingerprint,
                                               String evidenceFingerprint, boolean certifiableEvidence) {
        tenantId = WorldFidelityRequest.text(tenantId, 512);
        targetFingerprint = WorldFidelityRunner.fingerprint(targetFingerprint);
        evidenceFingerprint = WorldFidelityRunner.fingerprint(evidenceFingerprint);
        WorldFidelityDriftRepository.DriftAnnotation annotation = repository.current(tenantId, targetFingerprint)
                .orElse(null);
        WorldFidelityDriftRepository.DriftState state = annotation == null ? null : annotation.state();
        boolean stable = state == WorldFidelityDriftRepository.DriftState.CURRENT
                || state == WorldFidelityDriftRepository.DriftState.ACCEPTED_DIVERGENCE;
        WorldFidelityDriftService.EvidenceCeiling ceiling = stable && certifiableEvidence
                ? WorldFidelityDriftService.EvidenceCeiling.CERTIFIABLE
                : stable ? WorldFidelityDriftService.EvidenceCeiling.EXPLORATORY
                : state == null ? WorldFidelityDriftService.EvidenceCeiling.UNKNOWN
                : WorldFidelityDriftService.EvidenceCeiling.EXPLORATORY;
        boolean publicationAllowed = stable && certifiableEvidence;
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("tenantId", tenantId);
        material.put("targetFingerprint", targetFingerprint);
        material.put("evidenceFingerprint", evidenceFingerprint);
        material.put("driftState", state == null ? "UNKNOWN" : state.name());
        material.put("evidenceCeiling", ceiling.name());
        material.put("publicationAllowed", publicationAllowed);
        return new WorldFidelityPolicyDecision(tenantId, targetFingerprint, evidenceFingerprint, state,
                ceiling, publicationAllowed, ProtocolFingerprint.of(mapper, material));
    }
}
