package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;

import java.util.Comparator;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.mutableRevision;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.protocolVersion;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.sortedStrings;

/** Business-owner authority describing what outcome is correct and why. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessOracle(
        String schemaVersion,
        String oracleId,
        long revision,
        EnterpriseScope scope,
        ExactTargetRef target,
        String statement,
        List<String> forbiddenOutcomes,
        List<ExactBasisRef> basisRefs,
        PrincipalRef owner,
        OracleLifecycle lifecycle,
        ReviewRecord approval,
        List<ExactAssetRef> assertionSetRefs,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.businessOracle.v1";

    public enum OracleLifecycle { PROPOSED, APPROVED, SUPERSEDED }

    public BusinessOracle {
        schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
        oracleId = required(oracleId, "oracleId");
        revision = mutableRevision(revision);
        scope = required(scope, "scope");
        target = required(target, "target");
        statement = required(statement, "statement");
        forbiddenOutcomes = sortedStrings(forbiddenOutcomes);
        basisRefs = basisRefs == null ? List.of() : basisRefs.stream()
                .distinct()
                .sorted(Comparator.comparing(ExactBasisRef::kind)
                        .thenComparing(ExactBasisRef::id)
                        .thenComparingLong(ExactBasisRef::revision))
                .toList();
        owner = required(owner, "owner");
        lifecycle = lifecycle == null ? OracleLifecycle.PROPOSED : lifecycle;
        approval = approval == null ? ReviewRecord.pending() : approval;
        assertionSetRefs = assertionSetRefs == null ? List.of() : assertionSetRefs.stream()
                .distinct()
                .sorted(Comparator.comparing(ExactAssetRef::kind)
                        .thenComparing(ExactAssetRef::id)
                        .thenComparingLong(ExactAssetRef::revision))
                .toList();
        metadata = required(metadata, "metadata");
        if (lifecycle == OracleLifecycle.APPROVED
                && (basisRefs.isEmpty() || !approval.approved())) {
            throw new IllegalArgumentException(
                    "Approved Oracle requires exact basis references and approval");
        }
    }

    /** Returns the server-owned persisted revision without changing business content. */
    public BusinessOracle persistedAs(long persistedRevision, AuditMetadata persistedMetadata) {
        if (persistedRevision < 1) {
            throw new IllegalArgumentException("Persisted Oracle revision must be positive");
        }
        return new BusinessOracle(
                schemaVersion, oracleId, persistedRevision, scope, target, statement,
                forbiddenOutcomes, basisRefs, owner, lifecycle, approval,
                assertionSetRefs, persistedMetadata);
    }
}
