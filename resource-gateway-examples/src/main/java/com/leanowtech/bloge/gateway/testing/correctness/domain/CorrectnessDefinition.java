package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.Waiver;

import java.util.Comparator;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.mutableRevision;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.protocolVersion;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.sortedStrings;

/** Versioned business definition for the behavior one exact target must prove. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessDefinition(
        String schemaVersion,
        String definitionId,
        long revision,
        EnterpriseScope scope,
        ExactTargetRef target,
        String title,
        String businessIntent,
        List<String> successCriteria,
        RiskLevel riskLevel,
        PrincipalRef owner,
        List<ExactBasisRef> policyRefs,
        Waiver policyWaiver,
        ExactAssetRef activeInventoryRef,
        DefinitionLifecycle lifecycle,
        ReviewRecord review,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessDefinition.v1";

    public enum DefinitionLifecycle { DRAFT, REVIEWED, ACTIVE, SUPERSEDED }

    public CorrectnessDefinition {
        schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
        definitionId = required(definitionId, "definitionId");
        revision = mutableRevision(revision);
        scope = required(scope, "scope");
        target = required(target, "target");
        title = required(title, "title");
        businessIntent = required(businessIntent, "businessIntent");
        successCriteria = sortedStrings(successCriteria);
        if (successCriteria.isEmpty()) {
            throw new IllegalArgumentException("At least one success criterion is required");
        }
        riskLevel = required(riskLevel, "riskLevel");
        owner = required(owner, "owner");
        policyRefs = policyRefs == null ? List.of() : policyRefs.stream()
                .distinct()
                .sorted(Comparator.comparing(ExactBasisRef::kind)
                        .thenComparing(ExactBasisRef::id)
                        .thenComparingLong(ExactBasisRef::revision))
                .toList();
        lifecycle = lifecycle == null ? DefinitionLifecycle.DRAFT : lifecycle;
        review = review == null ? ReviewRecord.pending() : review;
        metadata = required(metadata, "metadata");
        if (lifecycle != DefinitionLifecycle.DRAFT
                && (activeInventoryRef == null || !review.approved())) {
            throw new IllegalArgumentException(
                    "Reviewed Definition requires an exact inventory and approved review");
        }
        if (lifecycle == DefinitionLifecycle.ACTIVE
                && policyRefs.isEmpty() && policyWaiver == null) {
            throw new IllegalArgumentException(
                    "Active Definition requires policy basis or an explicit waiver");
        }
    }
}
