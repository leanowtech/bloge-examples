package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.List;
import java.util.Set;

final class BusinessMirrorAuthoringFixtures {
    static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");

    private BusinessMirrorAuthoringFixtures() {
    }

    static DomainCapabilityPackageDraft draft(String packageId, long revision, String assumption) {
        return draft(SCOPE, packageId, revision, assumption);
    }

    static DomainCapabilityPackageDraft draft(
            CapabilitySnapshot.Scope scope, String packageId, long revision, String assumption) {
        return new DomainCapabilityPackageDraft("", packageId, revision, scope,
                new DomainCapabilityPackageDraft.BusinessDefinition(
                        "ride-cancellation", null, "", "", "",
                        DomainCapabilityPackageDraft.RiskClass.HIGH,
                        "cancellation-owner", List.of()),
                null, List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(),
                List.of(assumption), null, provenance(scope),
                DomainCapabilityPackageDraft.Lifecycle.DRAFT);
    }

    static IntegrationRequestContext identity() {
        return identity(SCOPE, "alice", "correlation-1");
    }

    static IntegrationRequestContext identity(
            CapabilitySnapshot.Scope scope, String actor, String correlation) {
        return new IntegrationRequestContext(scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), "WORKLOAD", actor, "",
                "BUSINESS_MIRROR_AUTHORING", correlation, Set.of("business-mirror-authors"),
                "CONFIDENTIAL", "");
    }

    private static ArtifactProvenance provenance(CapabilitySnapshot.Scope scope) {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                scope.tenantId(), "business-mirror-authoring-test", null, null, null, null,
                List.of(), "", null, null, "");
    }
}
