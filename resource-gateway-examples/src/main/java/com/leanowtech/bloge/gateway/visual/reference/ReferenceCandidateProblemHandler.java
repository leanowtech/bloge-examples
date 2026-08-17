package com.leanowtech.bloge.gateway.visual.reference;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** Stable request-boundary errors for malformed candidate commands and query parameters. */
@RestControllerAdvice(assignableTypes = {
        ReferenceCandidateController.class,
        CorrectnessDefinitionCandidateController.class
})
public class ReferenceCandidateProblemHandler {

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<IntegrationProblem> invalidRequest(Exception failure) {
        IntegrationProblem problem = IntegrationProblem.badRequest(
                "RG.REFERENCE.REQUEST_INVALID",
                "The reference candidate request is malformed or incomplete.",
                "",
                Map.of("failureType", failure.getClass().getSimpleName()));
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(problem);
    }
}
