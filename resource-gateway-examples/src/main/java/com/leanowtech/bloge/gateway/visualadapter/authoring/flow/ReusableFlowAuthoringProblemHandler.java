package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure;
import com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

/** Maps reusable Flow failures to the shared payload-free Authoring Problem Detail. */
@RestControllerAdvice(assignableTypes = ReusableFlowAuthoringController.class)
@ConditionalOnProperty(prefix = "gateway.authoring.reusable-flow", name = "enabled", havingValue = "true")
public final class ReusableFlowAuthoringProblemHandler {
    @ExceptionHandler(IntegrationProblemException.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> integration(
            IntegrationProblemException failure, HttpServletRequest request) {
        IntegrationProblem source = failure.problem();
        ApiResourceAuthoringProblemDetail problem = problem(source.type(), source.title(), source.status(),
                source.code(), correlation(source.correlationId(), request));
        return response(problem, source.status() == 401);
    }

    @ExceptionHandler(ReusableFlowFailure.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> application(
            ReusableFlowFailure failure, HttpServletRequest request) {
        Mapping mapping = switch (failure.code()) {
            case DEPENDENCY_NOT_FOUND, NOT_FOUND -> new Mapping(404, "authoring-resource-not-found",
                    "Reusable Flow dependency was not found", "NOT_FOUND");
            case DEPENDENCY_DRIFT, CAS_MISMATCH -> new Mapping(412, "authoring-precondition-failed",
                    "Reusable Flow dependency changed", "PRECONDITION_FAILED");
            case CONFLICT, BUSY -> new Mapping(409, "authoring-conflict",
                    "Reusable Flow operation conflicts", failure.code().name());
            case INTEGRITY -> new Mapping(500, "authoring-integrity",
                    "Reusable Flow integrity check failed", "INTEGRITY_FAILED");
            case PERSISTENCE -> new Mapping(503, "authoring-service-unavailable",
                    "Reusable Flow persistence is unavailable", "PERSISTENCE_FAILED");
            default -> new Mapping(422, "authoring-validation",
                    "Reusable Flow operation is invalid", "VALIDATION_FAILED");
        };
        ApiResourceAuthoringProblemDetail problem = problem(
                "urn:bloge:problem:" + mapping.type(), mapping.title(), mapping.status(),
                "RG.AUTHORING.REUSABLE_FLOW." + mapping.code(), correlation("", request));
        return response(problem, false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> malformed(
            HttpMessageNotReadableException failure, HttpServletRequest request) {
        return response(problem("urn:bloge:problem:bad-authoring-request",
                "Reusable Flow request is invalid", 400, "RG.AUTHORING.REUSABLE_FLOW.REQUEST_INVALID",
                correlation("", request)), false);
    }

    private static ApiResourceAuthoringProblemDetail problem(
            String type, String title, int status, String code, String correlationId) {
        return new ApiResourceAuthoringProblemDetail(type, title, status, title, code,
                correlationId, List.of(), List.of());
    }

    private static ResponseEntity<ApiResourceAuthoringProblemDetail> response(
            ApiResourceAuthoringProblemDetail problem, boolean challenge) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status())
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache");
        if (challenge) response.header(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer realm=\"resource-gateway-authoring\"");
        return response.body(problem);
    }

    private static String correlation(String supplied, HttpServletRequest request) {
        if (supplied != null && supplied.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) return supplied;
        Object trusted = request.getAttribute(AuthoringRequestAttributes.CORRELATION_ID);
        if (trusted instanceof String value
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) return value;
        return UUID.randomUUID().toString();
    }

    private record Mapping(int status, String type, String title, String code) { }
}
