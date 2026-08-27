package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;

/** Applies the World fidelity drift state machine without mutating historical reports. */
public final class WorldFidelityDriftService {
    public static final String APPROVAL_PURPOSE = "WORLD_FIDELITY_ACCEPT_DIVERGENCE";
    private final WorldFidelityDriftRepository repository;

    public WorldFidelityDriftService(WorldFidelityDriftRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public WorldFidelityDriftRepository.DriftAnnotation observe(String tenantId, WorldFidelityReport report) {
        Objects.requireNonNull(report, "report");
        repository.append(tenantId, report);
        WorldFidelityDriftRepository.DriftAnnotation previous = repository.current(tenantId, report.targetFingerprint()).orElse(null);
        WorldFidelityDriftRepository.DriftState next = previous == null
                ? report.outcome() == WorldFidelityReport.Outcome.EQUIVALENT
                ? WorldFidelityDriftRepository.DriftState.CURRENT
                : WorldFidelityDriftRepository.DriftState.SUSPECTED
                : previous.state();
        if (previous != null && previous.state() == WorldFidelityDriftRepository.DriftState.CURRENT
                && report.outcome() != WorldFidelityReport.Outcome.EQUIVALENT) {
            next = WorldFidelityDriftRepository.DriftState.SUSPECTED;
        }
        WorldFidelityDriftRepository.DriftAnnotation annotation = annotation(next, report);
        WorldFidelityDriftRepository.DriftState expected = previous == null ? null : previous.state();
        if (!repository.compareAndSet(tenantId, report.targetFingerprint(), expected, annotation)) {
            throw new WorldFidelityException(WorldFidelityException.Code.DRIFT_CAS_CONFLICT);
        }
        return annotation;
    }

    public WorldFidelityDriftRepository.DriftAnnotation transition(String tenantId, String targetFingerprint,
                                                                     WorldFidelityDriftRepository.DriftState expected,
                                                                     WorldFidelityDriftRepository.DriftState next) {
        WorldFidelityDriftRepository.DriftAnnotation current = repository.current(tenantId, targetFingerprint)
                .orElseThrow(() -> new WorldFidelityException(WorldFidelityException.Code.DRIFT_TRANSITION_INVALID));
        if (current.state() != expected || !allowed(expected, next)) {
            throw new WorldFidelityException(WorldFidelityException.Code.DRIFT_TRANSITION_INVALID);
        }
        WorldFidelityDriftRepository.DriftAnnotation replacement = new WorldFidelityDriftRepository.DriftAnnotation(
                next, current.reportFingerprint(), current.targetFingerprint(), current.contractFingerprint(),
                current.worldSliceFingerprint(), current.implementationFingerprint(), current.sampleSetFingerprint());
        if (!repository.compareAndSet(tenantId, targetFingerprint, expected, replacement)) {
            throw new WorldFidelityException(WorldFidelityException.Code.DRIFT_CAS_CONFLICT);
        }
        return replacement;
    }

    public WorldFidelityDriftRepository.DriftAnnotation acceptDivergence(String tenantId, String targetFingerprint,
                                                                          WorldFidelityApprovalReceipt receipt,
                                                                          WorldFidelityApprovalAuthority authority) {
        WorldFidelityDriftRepository.DriftAnnotation current = repository.current(tenantId, targetFingerprint)
                .orElseThrow(() -> new WorldFidelityException(WorldFidelityException.Code.APPROVAL_INVALID));
        if (current.state() != WorldFidelityDriftRepository.DriftState.CONFIRMED
                || receipt == null || authority == null
                || !tenantId.equals(receipt.tenantId()) || !targetFingerprint.equals(receipt.targetFingerprint())
                || !current.reportFingerprint().equals(receipt.reportFingerprint())
                || !APPROVAL_PURPOSE.equals(receipt.purpose())
                || !authority.verify(tenantId, targetFingerprint, receipt)) {
            throw new WorldFidelityException(WorldFidelityException.Code.APPROVAL_INVALID);
        }
        WorldFidelityDriftRepository.DriftAnnotation replacement = new WorldFidelityDriftRepository.DriftAnnotation(
                WorldFidelityDriftRepository.DriftState.ACCEPTED_DIVERGENCE, current.reportFingerprint(),
                current.targetFingerprint(), current.contractFingerprint(), current.worldSliceFingerprint(),
                current.implementationFingerprint(), current.sampleSetFingerprint());
        if (!repository.compareAndSetAndConsumeReceipt(tenantId, targetFingerprint,
                WorldFidelityDriftRepository.DriftState.CONFIRMED, replacement, receipt.fingerprint())) {
            throw new WorldFidelityException(WorldFidelityException.Code.DRIFT_CAS_CONFLICT);
        }
        return replacement;
    }

    public boolean publicationAllowed(String tenantId, String targetFingerprint) {
        return repository.current(tenantId, targetFingerprint)
                .map(value -> value.state() == WorldFidelityDriftRepository.DriftState.CURRENT
                        || value.state() == WorldFidelityDriftRepository.DriftState.ACCEPTED_DIVERGENCE)
                .orElse(false);
    }

    public EvidenceCeiling evidenceCeiling(String tenantId, String targetFingerprint) {
        return repository.current(tenantId, targetFingerprint)
                .map(value -> value.state() == WorldFidelityDriftRepository.DriftState.CURRENT
                        || value.state() == WorldFidelityDriftRepository.DriftState.ACCEPTED_DIVERGENCE
                        ? EvidenceCeiling.CERTIFIABLE : EvidenceCeiling.EXPLORATORY)
                .orElse(EvidenceCeiling.UNKNOWN);
    }

    private static boolean allowed(WorldFidelityDriftRepository.DriftState from,
                                   WorldFidelityDriftRepository.DriftState to) {
        return from == WorldFidelityDriftRepository.DriftState.CURRENT
                && to == WorldFidelityDriftRepository.DriftState.SUSPECTED
                || from == WorldFidelityDriftRepository.DriftState.SUSPECTED
                && to == WorldFidelityDriftRepository.DriftState.CONFIRMED
                || from == WorldFidelityDriftRepository.DriftState.CONFIRMED
                && to == WorldFidelityDriftRepository.DriftState.REMEDIATING
                || from == WorldFidelityDriftRepository.DriftState.REMEDIATING
                && to == WorldFidelityDriftRepository.DriftState.CURRENT
                || from == WorldFidelityDriftRepository.DriftState.CONFIRMED
                && to == WorldFidelityDriftRepository.DriftState.ACCEPTED_DIVERGENCE;
    }

    private static WorldFidelityDriftRepository.DriftAnnotation annotation(
            WorldFidelityDriftRepository.DriftState state, WorldFidelityReport report) {
        return new WorldFidelityDriftRepository.DriftAnnotation(state, report.reportFingerprint(),
                report.targetFingerprint(), report.contractFingerprint(), report.worldSliceFingerprint(),
                report.implementationFingerprint(), report.sampleSetFingerprint());
    }

    public enum EvidenceCeiling { CERTIFIABLE, EXPLORATORY, UNKNOWN }

    public record WorldFidelityApprovalReceipt(String receiptId, String tenantId, String targetFingerprint,
                                               String reportFingerprint, String actorId, String purpose,
                                               String fingerprint) {
        public WorldFidelityApprovalReceipt {
            receiptId = WorldFidelityRequest.text(receiptId, 256);
            tenantId = WorldFidelityRequest.text(tenantId, 256);
            targetFingerprint = WorldFidelityRunner.fingerprint(targetFingerprint);
            reportFingerprint = WorldFidelityRunner.fingerprint(reportFingerprint);
            actorId = WorldFidelityRequest.text(actorId, 256);
            purpose = WorldFidelityRequest.text(purpose, 128);
            fingerprint = WorldFidelityRunner.fingerprint(fingerprint);
            String derived = ProtocolFingerprint.ofText(receiptId + "\u0000" + tenantId + "\u0000"
                    + targetFingerprint + "\u0000" + reportFingerprint + "\u0000" + actorId + "\u0000" + purpose);
            if (!derived.equals(fingerprint)) {
                throw new WorldFidelityException(WorldFidelityException.Code.APPROVAL_INVALID);
            }
        }
    }

    @FunctionalInterface
    public interface WorldFidelityApprovalAuthority {
        boolean verify(String tenantId, String targetFingerprint, WorldFidelityApprovalReceipt receipt);
    }
}
