package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import org.springframework.http.HttpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated Package evidence index, domain Portfolio, refresh, and owner-task transport. */
@RestController
@RequestMapping
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class PackageEvidenceController {
    private final PackageEvidenceService service;
    private final IntegrationRequestAuthenticator authenticator;

    public PackageEvidenceController(
            PackageEvidenceService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @GetMapping({
            "/api/business-mirror/domain-capability-packages/{packageId}/evidence-index",
            "/api/integration/domain-capability-packages/{packageId}/evidence-index"
    })
    public PackageEvidenceIndex evidenceIndex(
            @PathVariable String packageId,
            @RequestHeader HttpHeaders headers) {
        return service.findCurrent(packageId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_READ));
    }

    @GetMapping({
            "/api/business-mirror/domain-portfolios/{domainId}",
            "/api/integration/domain-portfolios/{domainId}"
    })
    public DomainEvidencePortfolio portfolio(
            @PathVariable String domainId,
            @RequestParam(defaultValue = "") String afterPackageId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.portfolio(domainId, afterPackageId, limit,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_READ));
    }

    @PostMapping(
            "/api/business-mirror/domain-capability-packages/{packageId}/evidence-index/refresh")
    public PackageEvidenceRepository.ProjectionResult refresh(
            @PathVariable String packageId,
            @RequestHeader HttpHeaders headers) {
        return service.refresh(packageId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_REFRESH));
    }

    @GetMapping("/api/business-mirror/evidence-owner-tasks")
    public List<EvidenceOwnerTask> tasks(
            @RequestParam(defaultValue = "") String domainId,
            @RequestParam(defaultValue = "") String packageId,
            @RequestParam(required = false) EvidenceOwnerTask.Status status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return service.tasks(domainId, packageId, status, limit,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_READ));
    }

    @PostMapping("/api/business-mirror/evidence-owner-tasks/{taskId}/acknowledge")
    public EvidenceOwnerTask acknowledge(
            @PathVariable String taskId,
            @RequestParam long expectedVersion,
            @RequestHeader HttpHeaders headers) {
        return service.acknowledge(taskId, expectedVersion,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_TASK_WRITE));
    }

    @PostMapping("/api/business-mirror/evidence-owner-tasks/{taskId}/resolve")
    public EvidenceOwnerTask resolve(
            @PathVariable String taskId,
            @RequestParam long expectedVersion,
            @RequestBody ResolutionRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.resolve(taskId, expectedVersion, request.resolutionEvidenceRef(),
                context(headers, IntegrationOperation.BUSINESS_MIRROR_EVIDENCE_TASK_WRITE));
    }

    public record ResolutionRequest(MirrorArtifactRef resolutionEvidenceRef) {
    }

    private IntegrationRequestContext context(
            HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
