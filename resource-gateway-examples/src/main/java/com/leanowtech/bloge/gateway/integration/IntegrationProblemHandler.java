package com.leanowtech.bloge.gateway.integration;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
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
        ReadOnlyShadowJobController.class
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
