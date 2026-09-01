package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureSetReviewMaterialGate;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureShareIdentity;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectnessFixtureSetReviewMaterialGateTest {
    @Test
    void verifiesApprovesAndActivatesEachExactProtectedAsset() {
        FixtureCatalogService catalog = mock(FixtureCatalogService.class);
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        String schemaFingerprint = "sha256:" + "a".repeat(64);
        StoredFixtureAsset proposedStored = stored(2, FixtureLifecycle.PROPOSED, schemaFingerprint);
        StoredFixtureAsset verified = stored(3, FixtureLifecycle.PROPOSED, schemaFingerprint);
        StoredFixtureAsset approved = stored(4, FixtureLifecycle.APPROVED, schemaFingerprint);
        StoredFixtureAsset active = stored(5, FixtureLifecycle.ACTIVE, schemaFingerprint);
        FixtureCatalogService.ApprovalResult approval =
                new FixtureCatalogService.ApprovalResult(approved, false);
        when(catalog.verifyForApproval(any(), eq("asset-1"), eq(2L), any(), any()))
                .thenReturn(verified);
        when(catalog.approveIdempotently(any(), eq("asset-1"), eq(3L), any(), any(), any()))
                .thenReturn(approval);
        when(catalog.activate(any(), eq("asset-1"), eq(4L), any())).thenReturn(active);
        when(fixtures.findRevision(any(), eq("asset-1"), eq(2L)))
                .thenReturn(java.util.Optional.of(proposedStored));
        when(fixtures.findHead(any(), eq("asset-1")))
                .thenReturn(java.util.Optional.of(proposedStored));
        CorrectnessFixtureSetReviewMaterialGate gate = new CorrectnessFixtureSetReviewMaterialGate(
                catalog, fixtures, new ObjectMapper().findAndRegisterModules());
        var proposed = new FixtureSetCommand.Material.FixtureAsset(
                "asset-1", 2, schemaFingerprint);
        var request = new FixtureSetReviewMaterialGate.Request("review-1", List.of(proposed),
                new FixtureReviewCommand.Attestations(true, true, true, "Reviewed"), "review-key");

        assertThat(gate.reviewAndActivate(request, reviewer()))
                .containsExactly(new FixtureSetCommand.Material.FixtureAsset(
                        "asset-1", 5, schemaFingerprint));

        verify(catalog).verifyForApproval(any(), eq("asset-1"), eq(2L), any(), any());
        verify(catalog).approveIdempotently(any(), eq("asset-1"), eq(3L), eq("Reviewed"),
                any(), any());
        verify(catalog).activate(any(), eq("asset-1"), eq(4L), any());
    }

    @Test
    void resumesAfterOneAssetWasAlreadyActivated() {
        FixtureCatalogService catalog = mock(FixtureCatalogService.class);
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        String schemaFingerprint = "sha256:" + "b".repeat(64);
        var first = new FixtureSetCommand.Material.FixtureAsset("asset-1", 2, schemaFingerprint);
        var second = new FixtureSetCommand.Material.FixtureAsset("asset-2", 2, schemaFingerprint);
        StoredFixtureAsset proposed = stored(2, FixtureLifecycle.PROPOSED, schemaFingerprint);
        StoredFixtureAsset verified = stored(3, FixtureLifecycle.PROPOSED, schemaFingerprint);
        StoredFixtureAsset approved = stored(4, FixtureLifecycle.APPROVED, schemaFingerprint);
        StoredFixtureAsset active = stored(5, FixtureLifecycle.ACTIVE, schemaFingerprint);
        when(fixtures.findRevision(any(), any(), eq(2L)))
                .thenReturn(java.util.Optional.of(proposed));
        when(fixtures.findHead(any(), eq("asset-1")))
                .thenReturn(java.util.Optional.of(active));
        when(fixtures.findHead(any(), eq("asset-2")))
                .thenReturn(java.util.Optional.of(proposed));
        when(catalog.verifyForApproval(any(), eq("asset-2"), eq(2L), any(), any()))
                .thenReturn(verified);
        FixtureCatalogService.ApprovalResult approval =
                new FixtureCatalogService.ApprovalResult(approved, false);
        when(catalog.approveIdempotently(any(), eq("asset-2"), eq(3L), any(), any(), any()))
                .thenReturn(approval);
        when(catalog.activate(any(), eq("asset-2"), eq(4L), any())).thenReturn(active);
        CorrectnessFixtureSetReviewMaterialGate gate = new CorrectnessFixtureSetReviewMaterialGate(
                catalog, fixtures, new ObjectMapper().findAndRegisterModules());
        var request = new FixtureSetReviewMaterialGate.Request("review-1", List.of(first, second),
                new FixtureReviewCommand.Attestations(true, true, true, "Reviewed"), "review-key");

        assertThat(gate.reviewAndActivate(request, reviewer())).containsExactly(
                new FixtureSetCommand.Material.FixtureAsset("asset-1", 5, schemaFingerprint),
                new FixtureSetCommand.Material.FixtureAsset("asset-2", 5, schemaFingerprint));
        verify(catalog, never()).verifyForApproval(any(), eq("asset-1"), anyLong(), any(), any());
        verify(catalog).verifyForApproval(any(), eq("asset-2"), eq(2L), any(), any());
    }

    private static StoredFixtureAsset stored(
            long revision, FixtureLifecycle lifecycle, String schemaFingerprint) {
        FixtureAssetDescriptor descriptor = mock(FixtureAssetDescriptor.class);
        when(descriptor.revision()).thenReturn(revision);
        when(descriptor.lifecycle()).thenReturn(lifecycle);
        when(descriptor.schemaRef()).thenReturn(new ExactSchemaRef("schema-1", 1, schemaFingerprint));
        return new StoredFixtureAsset(StoredFixtureAsset.SCHEMA_VERSION,
                "sha256:" + Long.toHexString(revision).repeat(64).substring(0, 64), descriptor);
    }

    private static FixtureShareIdentity reviewer() {
        return new FixtureShareIdentity(
                new AuthoringScope("tenant", "project", "test"), "org", "local",
                "USER", "reviewer", "INTERNAL", "correlation-1");
    }
}
