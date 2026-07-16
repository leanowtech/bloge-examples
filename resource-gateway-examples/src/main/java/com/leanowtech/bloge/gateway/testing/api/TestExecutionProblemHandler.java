package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Maps test-control failures to the stable machine-readable integration problem contract. */
@RestControllerAdvice(assignableTypes = {
        TestExecutionController.class,
        DurableTestExecutionQueryController.class,
        DurableTestOwnerClaimController.class,
        DurableTestRecoveryHeartbeatController.class,
        DurableTestTerminalRecoveryController.class})
public class TestExecutionProblemHandler {

    @ExceptionHandler(IntegrationProblemException.class)
    public ResponseEntity<IntegrationProblem> handle(IntegrationProblemException failure) {
        IntegrationProblem problem = failure.problem();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status());
        if (problem.status() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"resource-gateway-testing\"");
        }
        return response.body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<IntegrationProblem> handleInvalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(IntegrationProblem.badRequest(
                "RG.TEST.REQUEST_INVALID", failure.getMessage(), "", java.util.Map.of()));
    }

    /** Returns a stable protocol error for malformed JSON and record-construction failures. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<IntegrationProblem> handleMalformed(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().body(IntegrationProblem.badRequest(
                "RG.TEST.REQUEST_MALFORMED", "Testing request JSON is malformed or cannot be decoded.",
                "", java.util.Map.of()));
    }
}
