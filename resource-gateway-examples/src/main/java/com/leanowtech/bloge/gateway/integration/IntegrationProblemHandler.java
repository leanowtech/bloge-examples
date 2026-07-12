package com.leanowtech.bloge.gateway.integration;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps integration service failures to the stable problem contract.
 */
@RestControllerAdvice(assignableTypes = ToolStudioIntegrationController.class)
public class IntegrationProblemHandler {

    @ExceptionHandler(IntegrationProblemException.class)
    public ResponseEntity<IntegrationProblem> handle(IntegrationProblemException failure) {
        IntegrationProblem problem = failure.problem();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status());
        if (problem.status() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"resource-gateway-integration\"");
        }
        return response.body(problem);
    }
}
