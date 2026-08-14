package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.CREATED_AT;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.MAPPER;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.SCOPE;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.businessDefinition;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.fingerprint;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.packageDraft;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.provenance;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainCapabilityPackageProtocolTest {
    @Test
    void allowsIncompleteDraftAndReportsStableBlockers() {
        DomainCapabilityPackageDraft draft = new DomainCapabilityPackageDraft("", "new-package", 0,
                SCOPE, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(),
                null, provenance(false), DomainCapabilityPackageDraft.Lifecycle.DRAFT);

        assertThat(draft.readinessBlockers()).contains(
                "BUSINESS_DOMAIN_MISSING", "PACKAGE_CONTRACT_MISSING",
                "EXECUTABLE_PROJECTION_MISSING", "SCENARIO_INVENTORY_MISSING",
                "HIGH_RISK_STATE_MODEL_MISSING", "HIGH_RISK_EFFECT_MODEL_MISSING");
    }

    @Test
    void rejectsPromotionOfAnIncompleteDraft() {
        assertThatThrownBy(() -> new DomainCapabilityPackageDraft("", "new-package", 0,
                SCOPE, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(),
                null, provenance(false), DomainCapabilityPackageDraft.Lifecycle.READY_FOR_REVIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("package is not ready");
    }

    @Test
    void admitsCompleteHighRiskPackageForReview() {
        DomainCapabilityPackageDraft draft = packageDraft(
                DomainCapabilityPackageDraft.Lifecycle.READY_FOR_REVIEW,
                DomainCapabilityPackageDraft.RiskClass.CRITICAL, provenance(false));

        assertThat(draft.readinessBlockers()).isEmpty();
        assertThat(draft.solutionRefs()).extracting(BusinessAssetRef::id)
                .containsExactly("cancellation-fee-solution");
    }

    @Test
    void submittedPackageRequiresOwnerApproval() {
        assertThatThrownBy(() -> packageDraft(DomainCapabilityPackageDraft.Lifecycle.SUBMITTED,
                DomainCapabilityPackageDraft.RiskClass.HIGH, provenance(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner approval");

        assertThat(packageDraft(DomainCapabilityPackageDraft.Lifecycle.SUBMITTED,
                DomainCapabilityPackageDraft.RiskClass.HIGH, provenance(true)).lifecycle())
                .isEqualTo(DomainCapabilityPackageDraft.Lifecycle.SUBMITTED);
    }

    @Test
    void rejectsMutableDependenciesInCompiledSnapshot() {
        assertThatThrownBy(() -> snapshot(List.of(
                ref("GRAPH_DRAFT", "mutable-graph", '1'))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutable authoring artifacts");
    }

    @Test
    void sealsAndDetectsTamperingOfCompiledSnapshot() {
        DomainCapabilityPackageSnapshot sealed = snapshot(List.of(
                ref("CAPABILITY", "trip-query", '2'))).seal(MAPPER);

        assertThat(sealed.artifactRef().kind()).isEqualTo("DOMAIN_CAPABILITY_PACKAGE");
        sealed.verify(MAPPER);

        DomainCapabilityPackageSnapshot tampered = new DomainCapabilityPackageSnapshot(
                sealed.schemaVersion(), sealed.packageId(), sealed.revision(), sealed.fingerprint(),
                sealed.scope(), sealed.sourceDraftRevision(), sealed.sourceDraftFingerprint(),
                sealed.businessDefinition(), sealed.packageContractRef(), sealed.capabilityClosureRef(),
                sealed.mirrorPlanRefs(), sealed.businessAssetLinkClosureRef(), sealed.readinessReportRef(),
                List.of(ref("CAPABILITY", "different", '3')), sealed.evidenceRefs(),
                sealed.compilerVersion(), sealed.policyGenerationRef(), sealed.provenance(),
                sealed.createdAt());

        assertThatThrownBy(() -> tampered.verify(MAPPER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    @Test
    void derivesReadinessStatusFromFindingsAndSealsTheResult() {
        PackageReadinessReport.Finding warning = new PackageReadinessReport.Finding(
                "finding-1", "owner_review", PackageReadinessReport.Severity.WARNING,
                PackageReadinessReport.Category.GOVERNANCE, "/businessDefinition/accountableOwner",
                null, "package.owner.review");
        PackageReadinessReport report = new PackageReadinessReport("", "report-1", 1, "", SCOPE,
                "cancellation-fee-resolution", 1, fingerprint('4'), null,
                List.of(warning), CREATED_AT).seal(MAPPER);

        assertThat(report.status()).isEqualTo(PackageReadinessReport.Status.REVIEW_REQUIRED);
        report.verify(MAPPER);
        assertThatThrownBy(() -> new PackageReadinessReport("", "report-2", 1, "", SCOPE,
                "cancellation-fee-resolution", 1, fingerprint('5'),
                PackageReadinessReport.Status.READY, List.of(warning), CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived");
    }

    private static DomainCapabilityPackageSnapshot snapshot(List<MirrorArtifactRef> dependencies) {
        return new DomainCapabilityPackageSnapshot("", "cancellation-fee-resolution", 1, "",
                SCOPE, 1, fingerprint('6'),
                businessDefinition(DomainCapabilityPackageDraft.RiskClass.HIGH),
                ref("CONTRACT", "cancellation-fee-package", '7'),
                ref("CAPABILITY_CLOSURE", "cancellation-fee-closure", '8'),
                List.of(ref("MIRROR_PLAN", "cancellation-fee-plan", '9')),
                ref("BUSINESS_ASSET_LINK_CLOSURE", "cancellation-asset-links", 'a'),
                ref("PACKAGE_READINESS_REPORT", "cancellation-readiness", 'b'),
                dependencies, List.of(ref("RUN_EVIDENCE_BUNDLE", "rehearsal-1", 'c')),
                "business-mirror-compiler-v1", ref("PACKAGE_COMPILATION_POLICY", "default", 'd'),
                provenance(true), CREATED_AT);
    }
}
