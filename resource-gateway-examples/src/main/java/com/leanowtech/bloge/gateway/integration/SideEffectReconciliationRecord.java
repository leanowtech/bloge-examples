package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Signed append-only statement that refines one UNKNOWN_COMMIT attempt. */
public record SideEffectReconciliationRecord(
        String schemaVersion,
        String reconciliationId,
        String requestId,
        String requestFingerprint,
        BaseEvidence baseEvidence,
        Target target,
        Resolution resolution,
        Actor actor,
        Chain chain,
        String recordFingerprint,
        VisualRunEvidenceSeal evidenceSeal
) {
    public static final String SCHEMA_VERSION =
            "toolStudio.resourceGateway.sideEffectReconciliationRecord.v1";

    public SideEffectReconciliationRecord {
        schemaVersion = normalize(schemaVersion).isBlank() ? SCHEMA_VERSION : normalize(schemaVersion);
        reconciliationId = normalize(reconciliationId).isBlank()
                ? "reconciliation-" + UUID.randomUUID() : normalize(reconciliationId);
        requestId = normalize(requestId);
        requestFingerprint = normalize(requestFingerprint);
        baseEvidence = baseEvidence == null ? BaseEvidence.empty() : baseEvidence;
        target = target == null ? Target.empty() : target;
        resolution = resolution == null ? Resolution.unknown() : resolution;
        actor = actor == null ? Actor.empty() : actor;
        chain = chain == null ? Chain.first() : chain;
        String computed = fingerprint(reconciliationId, requestId, requestFingerprint, baseEvidence,
                target, resolution, actor, chain);
        recordFingerprint = normalize(recordFingerprint).isBlank() ? computed : normalize(recordFingerprint);
        evidenceSeal = evidenceSeal == null ? VisualRunEvidenceSeal.unsigned() : evidenceSeal;
    }

    public static SideEffectReconciliationRecord create(String requestId,
                                                        String requestFingerprint,
                                                        BaseEvidence baseEvidence,
                                                        Target target,
                                                        Resolution resolution,
                                                        Actor actor,
                                                        Chain chain) {
        return new SideEffectReconciliationRecord("", "", requestId, requestFingerprint, baseEvidence,
                target, resolution, actor, chain, "", VisualRunEvidenceSeal.unsigned());
    }

    public SideEffectReconciliationRecord withEvidenceSeal(VisualRunEvidenceSeal seal) {
        return new SideEffectReconciliationRecord(schemaVersion, reconciliationId, requestId,
                requestFingerprint, baseEvidence, target, resolution, actor, chain, recordFingerprint, seal);
    }

    public boolean fingerprintVerified() {
        return recordFingerprint.equals(fingerprint(reconciliationId, requestId, requestFingerprint,
                baseEvidence, target, resolution, actor, chain));
    }

    public VisualEvidenceSigner.Verification verify(VisualEvidenceSigner signer) {
        if (!fingerprintVerified()) {
            return new VisualEvidenceSigner.Verification(false, "INVALID", "record fingerprint mismatch");
        }
        return signer == null
                ? VisualEvidenceSigner.Verification.unavailable("evidence signer unavailable")
                : signer.verify(evidenceSeal, recordFingerprint);
    }

    private static String fingerprint(String reconciliationId,
                                      String requestId,
                                      String requestFingerprint,
                                      BaseEvidence baseEvidence,
                                      Target target,
                                      Resolution resolution,
                                      Actor actor,
                                      Chain chain) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("reconciliationId", reconciliationId);
        material.put("requestId", requestId);
        material.put("requestFingerprint", requestFingerprint);
        material.put("baseEvidence", baseEvidence);
        material.put("target", target);
        material.put("resolution", resolution);
        material.put("actor", actor);
        material.put("chain", chain);
        return VisualBundleFingerprint.fromMaterial(material);
    }

    public record BaseEvidence(String runId, String evidenceId, String evidenceFingerprint,
                               String tenantId, String namespace, String environmentId) {
        public BaseEvidence {
            runId = normalize(runId);
            evidenceId = normalize(evidenceId);
            evidenceFingerprint = normalize(evidenceFingerprint);
            tenantId = normalize(tenantId);
            namespace = normalize(namespace);
            environmentId = normalize(environmentId);
        }

        static BaseEvidence empty() {
            return new BaseEvidence("", "", "", "", "", "");
        }
    }

    public record Target(String nodeId, String attemptId, String attemptFingerprint,
                         String operationRef, String idempotencyKeyFingerprint,
                         String reconcilerRef, String reconciliationLookupRef) {
        public Target {
            nodeId = normalize(nodeId);
            attemptId = normalize(attemptId);
            attemptFingerprint = normalize(attemptFingerprint);
            operationRef = normalize(operationRef);
            idempotencyKeyFingerprint = normalize(idempotencyKeyFingerprint);
            reconcilerRef = normalize(reconcilerRef);
            reconciliationLookupRef = normalize(reconciliationLookupRef);
        }

        static Target empty() {
            return new Target("", "", "", "", "", "", "");
        }
    }

    public record Resolution(String outcome, RunEvidenceBundle.SideEffectReceipt receipt,
                             String reasonCode, Instant observedAt) {
        public Resolution {
            outcome = normalize(outcome).toUpperCase(Locale.ROOT);
            if (!Set.of("COMMITTED", "NOT_COMMITTED", "UNKNOWN").contains(outcome)) {
                throw new IllegalArgumentException("Unsupported reconciliation outcome: " + outcome);
            }
            if ("COMMITTED".equals(outcome) && receipt == null) {
                throw new IllegalArgumentException("COMMITTED reconciliation requires a receipt");
            }
            reasonCode = normalize(reasonCode).toUpperCase(Locale.ROOT);
            observedAt = observedAt == null ? Instant.EPOCH : observedAt;
        }

        static Resolution unknown() {
            return new Resolution("UNKNOWN", null, "NOT_RECONCILED", Instant.EPOCH);
        }
    }

    public record Actor(String actorType, String actorId, String delegatedBy, String correlationId) {
        public Actor {
            actorType = normalize(actorType).toUpperCase(Locale.ROOT);
            actorId = normalize(actorId);
            delegatedBy = normalize(delegatedBy);
            correlationId = normalize(correlationId);
        }

        static Actor empty() {
            return new Actor("", "", "", "");
        }
    }

    public record Chain(long sequence, String previousFingerprint) {
        public Chain {
            sequence = Math.max(1, sequence);
            previousFingerprint = normalize(previousFingerprint);
        }

        static Chain first() {
            return new Chain(1, "");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
