package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Human-reviewable diff created when exact target or derivation sources change. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageImpactProposal(
        String schemaVersion,
        String proposalFingerprint,
        EnterpriseScope scope,
        ExactAssetRef currentInventoryRef,
        ExactTargetRef currentTarget,
        ExactTargetRef proposedTarget,
        List<ExactSourceSnapshotRef> currentSources,
        List<ExactSourceSnapshotRef> proposedSources,
        List<ObligationChange> changes,
        boolean targetDrifted,
        boolean sourcesDrifted,
        Instant generatedAt
) {
    public static final String SCHEMA_VERSION = "bloge.coverageImpactProposal.v1";

    public CoverageImpactProposal {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Coverage impact proposal schemaVersion");
        }
        if (proposalFingerprint == null
                || !proposalFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact proposal fingerprint is required");
        }
        if (scope == null || currentInventoryRef == null || currentTarget == null
                || proposedTarget == null || generatedAt == null) {
            throw new IllegalArgumentException("Complete Coverage impact coordinates are required");
        }
        currentSources = sortedSources(currentSources);
        proposedSources = sortedSources(proposedSources);
        changes = changes == null ? List.of() : changes.stream()
                .sorted(Comparator.comparing(ObligationChange::obligationId))
                .toList();
    }

    public enum ChangeKind { ADDED, MODIFIED, REMOVAL_PROPOSED, UNCHANGED }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObligationChange(
            String obligationId,
            ChangeKind kind,
            String previousFingerprint,
            String proposedFingerprint,
            CoverageObligation previous,
            CoverageObligation proposed
    ) {
        public ObligationChange {
            obligationId = obligationId == null ? "" : obligationId.trim();
            if (obligationId.isEmpty() || kind == null) {
                throw new IllegalArgumentException("Complete obligation change is required");
            }
            previousFingerprint = normalizedFingerprint(previousFingerprint);
            proposedFingerprint = normalizedFingerprint(proposedFingerprint);
            if ((kind == ChangeKind.ADDED && (previous != null || proposed == null))
                    || (kind == ChangeKind.REMOVAL_PROPOSED
                            && (previous == null || proposed != null))
                    || ((kind == ChangeKind.MODIFIED || kind == ChangeKind.UNCHANGED)
                            && (previous == null || proposed == null))) {
                throw new IllegalArgumentException("Obligation change sides do not match its kind");
            }
        }
    }

    private static List<ExactSourceSnapshotRef> sortedSources(
            List<ExactSourceSnapshotRef> values
    ) {
        return values == null ? List.of() : values.stream().distinct()
                .sorted(Comparator.comparing(ExactSourceSnapshotRef::kind)
                        .thenComparing(ExactSourceSnapshotRef::id)
                        .thenComparingLong(ExactSourceSnapshotRef::revision))
                .toList();
    }

    private static String normalizedFingerprint(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && !normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Change fingerprint must be exact SHA-256");
        }
        return normalized;
    }
}
