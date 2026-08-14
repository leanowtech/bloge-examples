package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageEvidenceControllerTest {
    @Test
    void separatesReadMaintenanceAndOwnerTaskAuthorizationPurposes() {
        PackageEvidenceService service = mock(PackageEvidenceService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext readIdentity = mock(IntegrationRequestContext.class);
        IntegrationRequestContext maintenanceIdentity = mock(IntegrationRequestContext.class);
        IntegrationRequestContext ownerIdentity = mock(IntegrationRequestContext.class);
        PackageEvidenceIndex index = mock(PackageEvidenceIndex.class);
        DomainEvidencePortfolio portfolio = mock(DomainEvidencePortfolio.class);
        PackageEvidenceRepository.ProjectionResult refreshed =
                mock(PackageEvidenceRepository.ProjectionResult.class);
        EvidenceOwnerTask task = mock(EvidenceOwnerTask.class);
        MirrorArtifactRef resolution = new MirrorArtifactRef(
                "CORRECTNESS_EVIDENCE", "evidence-a", 1, "sha256:" + "a".repeat(64));
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(
                headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_READ))
                .thenReturn(readIdentity);
        when(authenticator.authenticate(
                headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_REFRESH))
                .thenReturn(maintenanceIdentity);
        when(authenticator.authenticate(
                headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_TASK_WRITE))
                .thenReturn(ownerIdentity);
        when(service.findCurrent("package-a", readIdentity)).thenReturn(index);
        when(service.portfolio("domain-a", "package-0", 25, readIdentity))
                .thenReturn(portfolio);
        when(service.refresh("package-a", maintenanceIdentity)).thenReturn(refreshed);
        when(service.tasks("domain-a", "package-a", EvidenceOwnerTask.Status.OPEN,
                25, readIdentity)).thenReturn(List.of(task));
        when(service.acknowledge("task-a", 1, ownerIdentity)).thenReturn(task);
        when(service.resolve("task-a", 2, resolution, ownerIdentity)).thenReturn(task);
        PackageEvidenceController controller =
                new PackageEvidenceController(service, authenticator);

        assertThat(controller.evidenceIndex("package-a", headers)).isSameAs(index);
        assertThat(controller.portfolio("domain-a", "package-0", 25, headers))
                .isSameAs(portfolio);
        assertThat(controller.refresh("package-a", headers)).isSameAs(refreshed);
        assertThat(controller.tasks("domain-a", "package-a", EvidenceOwnerTask.Status.OPEN,
                25, headers)).containsExactly(task);
        assertThat(controller.acknowledge("task-a", 1, headers)).isSameAs(task);
        assertThat(controller.resolve("task-a", 2,
                new PackageEvidenceController.ResolutionRequest(resolution), headers))
                .isSameAs(task);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_REFRESH);
        verify(authenticator, org.mockito.Mockito.times(2)).authenticate(
                headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_TASK_WRITE);
    }
}
