package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Trusted source adapter that derives proposed obligations from exact upstream snapshots. */
@FunctionalInterface
public interface CoverageDerivationSource {

    DerivationSnapshot derive(EnterpriseScope scope, ExactTargetRef requestedTarget);

    record DerivationSnapshot(
            EnterpriseScope scope,
            ExactTargetRef target,
            List<ExactSourceSnapshotRef> sources,
            List<CoverageObligation> proposedObligations
    ) {
        public DerivationSnapshot {
            if (scope == null || target == null) {
                throw new IllegalArgumentException("Derivation scope and exact target are required");
            }
            sources = sources == null ? List.of() : sources.stream().distinct()
                    .sorted(Comparator.comparing(ExactSourceSnapshotRef::kind)
                            .thenComparing(ExactSourceSnapshotRef::id)
                            .thenComparingLong(ExactSourceSnapshotRef::revision))
                    .toList();
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("Derivation requires exact source snapshots");
            }
            if (sources.size() > 1000) {
                throw new IllegalArgumentException("Derivation source snapshot limit is 1000");
            }
            proposedObligations = proposedObligations == null ? List.of()
                    : proposedObligations.stream()
                            .sorted(Comparator.comparing(CoverageObligation::obligationId))
                            .toList();
            if (proposedObligations.size() > 10_000) {
                throw new IllegalArgumentException("Derived obligation limit is 10000");
            }
            if (proposedObligations.stream().anyMatch(
                    value -> value.lifecycle() != ObligationLifecycle.PROPOSED)) {
                throw new IllegalArgumentException(
                        "Derived obligations must remain PROPOSED for human review");
            }
            if (new HashSet<>(proposedObligations.stream()
                    .map(CoverageObligation::obligationId).toList()).size()
                    != proposedObligations.size()) {
                throw new IllegalArgumentException("Derived obligation ids must be unique");
            }
        }
    }
}
