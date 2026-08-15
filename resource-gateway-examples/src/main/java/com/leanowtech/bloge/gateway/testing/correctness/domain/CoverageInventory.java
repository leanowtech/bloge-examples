package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.Waiver;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.mutableRevision;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.protocolVersion;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.sortedStrings;

/** Frozen, auditable denominator of behavior obligations for one exact target. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageInventory(
        String schemaVersion,
        String inventoryId,
        long revision,
        EnterpriseScope scope,
        ExactTargetRef target,
        InventoryLifecycle lifecycle,
        List<CoverageObligation> obligations,
        List<ExactSourceSnapshotRef> derivationSources,
        ReviewRecord freezeReview,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.coverageInventory.v1";

    public enum InventoryLifecycle { DRAFT, FROZEN, SUPERSEDED }
    public enum ObligationDimension { CONTRACT, PATH, POLICY, RISK, INCIDENT, BOUNDARY }
    public enum ObligationSource { AUTOMATED, BUSINESS, INCIDENT, MIGRATED }
    public enum ObligationLifecycle { PROPOSED, FROZEN, WAIVED, RETIRED }

    public CoverageInventory {
        schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
        inventoryId = required(inventoryId, "inventoryId");
        revision = mutableRevision(revision);
        scope = required(scope, "scope");
        target = required(target, "target");
        lifecycle = lifecycle == null ? InventoryLifecycle.DRAFT : lifecycle;
        obligations = obligations == null ? List.of() : obligations.stream()
                .sorted(Comparator.comparing(CoverageObligation::obligationId))
                .toList();
        if (new HashSet<>(obligations.stream().map(CoverageObligation::obligationId).toList())
                .size() != obligations.size()) {
            throw new IllegalArgumentException("Coverage obligation ids must be unique");
        }
        derivationSources = derivationSources == null ? List.of() : derivationSources.stream()
                .distinct()
                .sorted(Comparator.comparing(ExactSourceSnapshotRef::kind)
                        .thenComparing(ExactSourceSnapshotRef::id)
                        .thenComparingLong(ExactSourceSnapshotRef::revision))
                .toList();
        freezeReview = freezeReview == null ? ReviewRecord.pending() : freezeReview;
        metadata = required(metadata, "metadata");
        if (lifecycle == InventoryLifecycle.FROZEN) {
            if (obligations.isEmpty() || !freezeReview.approved()) {
                throw new IllegalArgumentException(
                        "Frozen Inventory requires obligations and an approved freeze review");
            }
            if (obligations.stream().anyMatch(obligation ->
                    obligation.lifecycle() == ObligationLifecycle.PROPOSED)) {
                throw new IllegalArgumentException(
                        "Frozen Inventory cannot contain proposed obligations");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverageObligation(
            String obligationId,
            ObligationDimension dimension,
            String title,
            String statement,
            RiskLevel risk,
            PrincipalRef owner,
            ObligationSource source,
            ObligationLifecycle lifecycle,
            Waiver waiver,
            List<String> tags
    ) {
        public CoverageObligation {
            obligationId = required(obligationId, "obligationId");
            dimension = required(dimension, "dimension");
            title = required(title, "title");
            statement = required(statement, "statement");
            risk = required(risk, "risk");
            owner = required(owner, "owner");
            source = required(source, "source");
            lifecycle = lifecycle == null ? ObligationLifecycle.PROPOSED : lifecycle;
            tags = sortedStrings(tags);
            if ((lifecycle == ObligationLifecycle.WAIVED) != (waiver != null)) {
                throw new IllegalArgumentException(
                        "Only a waived obligation may carry a complete waiver");
            }
        }
    }
}
