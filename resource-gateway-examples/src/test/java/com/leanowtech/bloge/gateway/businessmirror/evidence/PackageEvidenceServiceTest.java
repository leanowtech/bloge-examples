package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEvent;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageEvidenceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void consumesExactFactsThroughTheExistingFidelityPortAndCompletesLeaseAtomically() {
        PackageCompilationReceipt receipt = receipt();
        DomainFidelityInventory inventory = inventory();
        DomainFidelityProfile profile = PackageEvidenceFixtures.profile(mapper, inventory,
                DomainFidelityProfile.MeasurementOutcome.PASS, PackageEvidenceFixtures.NOW);
        PackageEvidenceRepository repository = mock(PackageEvidenceRepository.class);
        PackageCompilationFactRepository facts = mock(PackageCompilationFactRepository.class);
        DomainFidelityRepository fidelity = mock(DomainFidelityRepository.class);
        IntegrationChangeEventOutbox outbox = mock(IntegrationChangeEventOutbox.class);
        when(outbox.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PackageEvidenceRepository.ProjectionLease lease =
                new PackageEvidenceRepository.ProjectionLease(PackageEvidenceFixtures.SCOPE,
                        receipt.packageId(), receipt.compilationRevision(),
                        receipt.snapshot().fingerprint(), "worker-a", 1, 1,
                        PackageEvidenceFixtures.NOW.plusSeconds(120));
        when(facts.find(PackageEvidenceFixtures.SCOPE, receipt.packageId(), 7))
                .thenReturn(Optional.of(receipt));
        when(fidelity.findInventory(PackageEvidenceFixtures.SCOPE,
                inventory.inventoryId(), inventory.revision())).thenReturn(Optional.of(inventory));
        when(fidelity.findLatestProfile(PackageEvidenceFixtures.SCOPE, "ride-cancellation"))
                .thenReturn(Optional.of(profile));
        when(repository.reserveProjectionRevision(
                PackageEvidenceFixtures.SCOPE, receipt.packageId(), 7))
                .thenReturn(new PackageEvidenceRepository.ProjectionReservation(
                        4, PackageEvidenceFixtures.NOW));
        when(repository.append(any(), anyString())).thenAnswer(invocation -> {
            PackageEvidenceIndex index = invocation.getArgument(0);
            return new PackageEvidenceRepository.ProjectionResult(index.packageId(),
                    index.compilationRevision(), index.projectionRevision(),
                    index.indexFingerprint(), index.driftSignals().size(),
                    index.projectedAt(), false);
        });
        when(repository.complete(lease)).thenReturn(true);
        PackageEvidenceService service = service(repository, facts, fidelity, outbox);

        PackageEvidenceRepository.ProjectionResult result = service.consume(lease);

        assertThat(result.projectionRevision()).isEqualTo(4);
        ArgumentCaptor<PackageEvidenceIndex> indexes =
                ArgumentCaptor.forClass(PackageEvidenceIndex.class);
        verify(repository).append(indexes.capture(), anyString());
        assertThat(indexes.getValue().fidelity().state())
                .isEqualTo(PackageEvidenceIndex.FidelityState.CURRENT);
        assertThat(indexes.getValue().fidelity().dimensions()).hasSize(7);
        verify(repository).complete(lease);
        ArgumentCaptor<IntegrationChangeEvent> events =
                ArgumentCaptor.forClass(IntegrationChangeEvent.class);
        verify(outbox).append(events.capture());
        assertThat(events.getValue().eventType()).isEqualTo("PACKAGE_EVIDENCE_INDEX_CHANGED");
        assertThat(events.getValue().payloadRef()).contains("task=evidence");
    }

    @Test
    void keepsCompilationAdmissionPayloadFreeAndDefersAllProjectionReadsToTheWorker() {
        PackageEvidenceRepository repository = mock(PackageEvidenceRepository.class);
        PackageCompilationFactRepository facts = mock(PackageCompilationFactRepository.class);
        DomainFidelityRepository fidelity = mock(DomainFidelityRepository.class);
        IntegrationChangeEventOutbox outbox = mock(IntegrationChangeEventOutbox.class);
        PackageEvidenceService service = service(repository, facts, fidelity, outbox);
        PackageCompilationReceipt receipt = receipt();

        service.enqueue(PackageEvidenceFixtures.SCOPE, receipt);

        verify(repository).enqueue(PackageEvidenceFixtures.SCOPE, receipt);
        verify(fidelity, never()).findLatestProfile(any(), anyString());
        verify(outbox, never()).append(any());
    }

    @Test
    void portfolioPreservesLayerCountsFidelityVectorFreshnessAndOwnerTasksWithoutTotalScore()
            throws Exception {
        DomainFidelityInventory inventory = inventory();
        PackageEvidenceIndex index = PackageEvidenceProjector.project(receipt(),
                Optional.of(inventory), Optional.empty(), 1,
                PackageEvidenceFixtures.NOW, mapper);
        EvidenceOwnerTask task = EvidenceOwnerTask.open(index,
                index.driftSignals().getFirst(), "/business-mirror/?task=evidence", mapper);
        PackageEvidenceRepository repository = mock(PackageEvidenceRepository.class);
        when(repository.findCurrentByDomain(PackageEvidenceFixtures.SCOPE,
                "ride-cancellation", "", 100))
                .thenReturn(new PackageEvidenceRepository.CurrentPage(List.of(index), ""));
        when(repository.findTasks(PackageEvidenceFixtures.SCOPE, "ride-cancellation",
                index.packageId(), EvidenceOwnerTask.Status.OPEN, 500))
                .thenReturn(List.of(task));
        when(repository.findTasks(PackageEvidenceFixtures.SCOPE, "ride-cancellation",
                index.packageId(), EvidenceOwnerTask.Status.ACKNOWLEDGED, 500))
                .thenReturn(List.of());
        PackageEvidenceService service = service(repository,
                mock(PackageCompilationFactRepository.class),
                mock(DomainFidelityRepository.class), mock(IntegrationChangeEventOutbox.class));

        DomainEvidencePortfolio portfolio = service.portfolio(
                "ride-cancellation", "", 100, identity());

        portfolio.verify(mapper);
        assertThat(portfolio.packages()).singleElement().satisfies(view -> {
            assertThat(view.layers()).hasSize(5);
            assertThat(view.fidelity().dimensions()).isEmpty();
            assertThat(view.ownerTasks()).containsExactly(task);
            assertThat(view.deepLink()).contains("task=evidence");
        });
        assertThat(mapper.writeValueAsString(portfolio))
                .doesNotContain("overallScore", "totalScore", "maturityScore");
    }

    @Test
    void mapsTaskVersionConflictWithoutChangingTheTask() {
        PackageEvidenceRepository repository = mock(PackageEvidenceRepository.class);
        when(repository.transitionTask(any(), anyString(), anyLong(), any(),
                anyString(), any(), any()))
                .thenThrow(new PackageEvidenceRepository.TaskVersionConflictException());
        PackageEvidenceService service = service(repository,
                mock(PackageCompilationFactRepository.class),
                mock(DomainFidelityRepository.class), mock(IntegrationChangeEventOutbox.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.acknowledge("task-a", 1, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.BUSINESS_MIRROR.EVIDENCE_TASK_VERSION_CONFLICT"));
    }

    @Test
    void distinguishesMissingTaskFromAnInvalidTerminalTransition() {
        PackageEvidenceRepository repository = mock(PackageEvidenceRepository.class);
        when(repository.transitionTask(any(), anyString(), anyLong(), any(),
                anyString(), any(), any()))
                .thenThrow(new PackageEvidenceRepository.TaskNotFoundException())
                .thenThrow(new IllegalStateException("terminal"));
        PackageEvidenceService service = service(repository,
                mock(PackageCompilationFactRepository.class),
                mock(DomainFidelityRepository.class), mock(IntegrationChangeEventOutbox.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.acknowledge("missing", 1, identity()))
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().status()).isEqualTo(404));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.acknowledge("terminal", 1, identity()))
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.BUSINESS_MIRROR.EVIDENCE_TASK_STATE_CONFLICT"));
    }

    private PackageEvidenceService service(
            PackageEvidenceRepository repository,
            PackageCompilationFactRepository facts,
            DomainFidelityRepository fidelity,
            IntegrationChangeEventOutbox outbox) {
        return new PackageEvidenceService(repository, facts, fidelity, outbox, mapper,
                Clock.fixed(PackageEvidenceFixtures.NOW, ZoneOffset.UTC));
    }

    private DomainFidelityInventory inventory() {
        return PackageEvidenceFixtures.inventory(mapper, 'd',
                PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
    }

    private PackageCompilationReceipt receipt() {
        return PackageEvidenceFixtures.receiptWithInventory(mapper, inventory());
    }

    private IntegrationRequestContext identity() {
        return new IntegrationRequestContext(PackageEvidenceFixtures.SCOPE.tenantId(),
                PackageEvidenceFixtures.SCOPE.organizationId(),
                PackageEvidenceFixtures.SCOPE.projectId(),
                PackageEvidenceFixtures.SCOPE.environmentId(),
                PackageEvidenceFixtures.SCOPE.region(), "HUMAN", "cancellation-owner", "",
                "BUSINESS_MIRROR_AUTHORING", "correlation-evidence", Set.of(),
                "CONFIDENTIAL", "");
    }
}
