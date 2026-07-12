package com.leanowtech.bloge.gateway.integration;

import org.springframework.http.ResponseEntity;
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
        return ResponseEntity.status(problem.status()).body(problem);
    }
}
