package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEvent;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessAssetImpactServiceTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");
    private static final Instant NOW = Instant.parse("2026-08-14T11:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsCurrentReportWithExactRefsAndWorkspaceDeepLinks() {
        BusinessAssetRef source = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.RESOURCE, "trip-api", 'a');
        BusinessAssetRef target = asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                BusinessAssetRef.Kind.SOLUTION, "refund-solution", 'b');
        BusinessAssetLink edge = link(source, target);
        var sourceImpact = new BusinessAssetImpactProjection.SourceImpact(source, List.of(
                new BusinessAssetImpactProjection.ImpactPath(
                        target, 1, 1, BusinessAssetLink.Risk.HIGH, List.of(edge))));
        BusinessAssetImpactRepository repository = new StubRepository(
                new BusinessAssetImpactRepository.ImpactQuery(List.of(
                        new BusinessAssetImpactRepository.StoredPackageImpact(
                                "refund-package", 7,
                                new MirrorArtifactRef("DOMAIN_CAPABILITY_PACKAGE",
                                        "refund-package", 7, fingerprint('c')),
                                ref("BUSINESS_ASSET_LINK_CLOSURE", "refund-links", 'd'),
                                List.of(sourceImpact))), "", List.of(), false, NOW));
        BusinessAssetImpactService service = service(repository, mock(IntegrationChangeEventOutbox.class));

        BusinessAssetImpactReport report = service.query(
                "resource", "trip-api", "customer-registry", "", 50, identity());

        report.verify(MAPPER);
        assertThat(report.status()).isEqualTo(BusinessAssetImpactReport.Status.CURRENT);
        assertThat(report.items()).singleElement().satisfies(item -> {
            assertThat(item.packageSnapshotRef().revision()).isEqualTo(7);
            assertThat(item.deepLink()).contains(
                    "packageId=refund-package", "compilationRevision=7", "task=capabilities");
            assertThat(item.matches().getFirst().deepLink()).contains(
                    "assetKind=RESOURCE", "assetId=trip-api", "assetAuthority=customer-registry");
            assertThat(item.matches().getFirst().paths().getFirst().deepLink())
                    .contains("assetKind=SOLUTION", "assetId=refund-solution");
        });
    }

    @Test
    void admitsCompilationThenEmitsImpactOnlyAfterTheOutboxProjectionCommits() {
        BusinessAssetImpactRepository repository = mock(BusinessAssetImpactRepository.class);
        PackageCompilationFactRepository facts = mock(PackageCompilationFactRepository.class);
        IntegrationChangeEventOutbox outbox = mock(IntegrationChangeEventOutbox.class);
        when(outbox.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PackageCompilationReceipt receipt = mock(PackageCompilationReceipt.class);
        DomainCapabilityPackageSnapshot snapshot = mock(DomainCapabilityPackageSnapshot.class);
        when(receipt.snapshot()).thenReturn(snapshot);
        when(receipt.packageId()).thenReturn("refund-package");
        when(receipt.compilationRevision()).thenReturn(7L);
        when(snapshot.fingerprint()).thenReturn(fingerprint('a'));
        when(repository.enqueue(SCOPE, receipt)).thenReturn(true);
        when(facts.find(SCOPE, "refund-package", 7)).thenReturn(Optional.of(receipt));
        when(repository.project(SCOPE, receipt)).thenReturn(projectionResult(false));
        BusinessAssetImpactRepository.ProjectionLease lease =
                new BusinessAssetImpactRepository.ProjectionLease(SCOPE, "refund-package", 7,
                        fingerprint('a'), "worker-a", 1, 1, NOW.plusSeconds(60));
        when(repository.complete(lease)).thenReturn(true);
        BusinessAssetImpactService service = service(repository, facts, outbox);

        service.enqueue(SCOPE, receipt);

        ArgumentCaptor<IntegrationChangeEvent> events =
                ArgumentCaptor.forClass(IntegrationChangeEvent.class);
        verify(outbox).append(events.capture());
        assertThat(events.getAllValues()).extracting(IntegrationChangeEvent::eventType)
                .containsExactly("DOMAIN_CAPABILITY_PACKAGE_SNAPSHOT_COMPILED");
        assertThat(events.getAllValues()).allMatch(IntegrationChangeEvent::fingerprintVerified);
        assertThat(events.getAllValues()).allMatch(event ->
                event.aggregate().sequence() == 7 && event.payloadRef().contains("refund-package"));

        clearInvocations(outbox);
        service.consume(lease);
        verify(outbox).append(events.capture());
        assertThat(events.getValue().eventType()).isEqualTo("BUSINESS_ASSET_IMPACT_CHANGED");
        verify(repository).complete(lease);

        clearInvocations(outbox);
        when(repository.enqueue(SCOPE, receipt)).thenReturn(false);
        service.enqueue(SCOPE, receipt);
        verify(outbox, never()).append(any());

        when(repository.project(SCOPE, receipt)).thenReturn(projectionResult(true));
        service.consume(lease);
        verify(outbox, never()).append(any());
    }

    private static BusinessAssetImpactService service(
            BusinessAssetImpactRepository repository, IntegrationChangeEventOutbox outbox) {
        return service(repository, mock(PackageCompilationFactRepository.class), outbox);
    }

    private static BusinessAssetImpactService service(
            BusinessAssetImpactRepository repository,
            PackageCompilationFactRepository facts,
            IntegrationChangeEventOutbox outbox) {
        return new BusinessAssetImpactService(repository, facts, outbox, MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BusinessAssetImpactRepository.ProjectionResult projectionResult(boolean replayed) {
        return new BusinessAssetImpactRepository.ProjectionResult(
                "refund-package", 7, fingerprint('a'), fingerprint('b'),
                2, 1, NOW, replayed);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(), "WORKLOAD", "alice", "",
                "BUSINESS_MIRROR_AUTHORING", "impact-correlation",
                Set.of("business-mirror-authors"), "CONFIDENTIAL", "");
    }

    private static BusinessAssetRef asset(
            BusinessAssetRef.Layer layer, BusinessAssetRef.Kind kind, String id, char value) {
        return new BusinessAssetRef(layer, kind, id, 1, fingerprint(value),
                "customer-registry", SCOPE);
    }

    private static BusinessAssetLink link(BusinessAssetRef source, BusinessAssetRef target) {
        return new BusinessAssetLink("", source, target, BusinessAssetLink.Relation.USES, "",
                BusinessAssetLink.Risk.HIGH, "refund-owner",
                new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                        SCOPE.tenantId(), "impact-test", null, null, null, null,
                        List.of(), "refund-owner", NOW.minusSeconds(3600), null, ""));
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record StubRepository(ImpactQuery query) implements BusinessAssetImpactRepository {
        @Override
        public ProjectionResult project(
                CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ImpactQuery query(
                CapabilitySnapshot.Scope scope,
                BusinessAssetSelector selector,
                String afterPackageId,
                int limit) {
            return query;
        }

        @Override
        public List<SnapshotCoordinate> staleSnapshots(
                CapabilitySnapshot.Scope scope, String afterPackageId, int limit) {
            return List.of();
        }
    }
}
