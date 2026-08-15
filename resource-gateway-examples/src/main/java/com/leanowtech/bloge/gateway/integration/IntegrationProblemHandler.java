package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSetController;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioImportController;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioPublicationController;
import com.leanowtech.bloge.gateway.authoring.scenario.TableSuiteRunController;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageController;
import com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageController;
import com.leanowtech.bloge.gateway.businessmirror.transport.PackageCompilationController;
import com.leanowtech.bloge.gateway.visual.authoring.transport.VisualLibraryAuthoringDraftController;
import com.leanowtech.bloge.gateway.visual.authoring.transport.VisualLibraryAuthoringTestController;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceController;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageInventoryController;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps integration service failures to the stable problem contract.
 */
@RestControllerAdvice(assignableTypes = {
        ToolStudioIntegrationController.class,
        CapabilityClosureIntegrationController.class,
        MirrorIntegrationController.class,
        MirrorRunIntegrationController.class,
        MirrorSessionController.class,
        ScenarioRehearsalController.class,
        ScenarioRehearsalBatchController.class,
        MirrorDeploymentIsolationAuthorityPublicationController.class,
        MirrorDeploymentIsolationAttestationController.class,
        ReadOnlyShadowAuthorityKeySetController.class,
        ReadOnlyShadowSourceBindingController.class,
        ReadOnlyShadowSourceResolutionAttestationController.class,
        CapabilityObservationController.class,
        CapabilityCorpusGovernanceController.class,
        DomainFidelityController.class,
        ReadOnlyShadowJobController.class,
        ScenarioDraftSetController.class,
        ScenarioImportController.class,
        ScenarioPublicationController.class,
        TableSuiteRunController.class,
        VisualLibraryAuthoringDraftController.class,
        VisualLibraryAuthoringTestController.class,
        DomainCapabilityPackageController.class,
        PackageCompilationController.class,
        LegacyGraphPackageController.class,
        CorrectnessWorkspaceController.class,
        CoverageInventoryController.class
})
public class IntegrationProblemHandler {

    @ExceptionHandler(IntegrationProblemException.class)
    public ResponseEntity<IntegrationProblem> handle(IntegrationProblemException failure) {
        IntegrationProblem problem = failure.problem();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status());
        if (problem.status() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"resource-gateway-integration\"");
        }
        Object retryAfter = problem.details().get("retryAfterSeconds");
        if (problem.retryable() && retryAfter instanceof Number seconds
                && seconds.longValue() >= 1 && seconds.longValue() <= 3_600) {
            response.header(HttpHeaders.RETRY_AFTER,
                    Long.toString(seconds.longValue()));
        }
        return response.body(problem);
    }
}
