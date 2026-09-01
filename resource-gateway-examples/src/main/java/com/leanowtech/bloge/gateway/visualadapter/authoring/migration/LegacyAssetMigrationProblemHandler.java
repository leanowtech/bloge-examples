package com.leanowtech.bloge.gateway.visualadapter.authoring.migration;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationFailure;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Stable no-store errors for the always-available, read-only legacy migration surface. */
@RestControllerAdvice(assignableTypes = LegacyAssetMigrationController.class)
public final class LegacyAssetMigrationProblemHandler {

    /** Preserves trusted authentication failures in the authoring Problem Detail family. */
    @ExceptionHandler(IntegrationProblemException.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> integration(
            IntegrationProblemException failure, HttpServletRequest request) {
        IntegrationProblem source = failure.problem();
        ApiResourceAuthoringProblemDetail problem = new ApiResourceAuthoringProblemDetail(
                source.type(), source.title(), source.status(), source.title(), source.code(),
                source.correlationId() == null || source.correlationId().isBlank()
                        ? correlation(request) : source.correlationId(), List.of(), source.retryable()
                ? List.of(new ApiResourceAuthoringProblemDetail.RecoveryAction("RETRY", null)) : List.of());
        Long retryAfter = source.details().get("retryAfterSeconds") instanceof Number seconds
                && seconds.longValue() >= 1 && seconds.longValue() <= 3_600 ? seconds.longValue() : null;
        return response(problem, source.status() == 401, retryAfter);
    }

    /** Converts preview eligibility failures without returning descriptor or contract content. */
    @ExceptionHandler(LegacyAssetMigrationFailure.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> migration(
            LegacyAssetMigrationFailure failure, HttpServletRequest request) {
        boolean missing = failure.code() == LegacyAssetMigrationFailure.Code.NOT_FOUND;
        ApiResourceAuthoringProblemDetail problem = new ApiResourceAuthoringProblemDetail(
                missing ? "urn:bloge:problem:authoring-resource-not-found"
                        : "urn:bloge:problem:authoring-validation",
                missing ? "Legacy Resource was not found" : "Legacy Resource requires repair",
                missing ? 404 : 422, failure.getMessage(),
                missing ? "RG.AUTHORING.LEGACY_MIGRATION.NOT_FOUND"
                        : "RG.AUTHORING.LEGACY_MIGRATION.NEEDS_REPAIR",
                correlation(request), List.of(), missing ? List.of() : List.of(
                new ApiResourceAuthoringProblemDetail.RecoveryAction(
                        "OPEN_LIST", "/workbench/?legacy=inventory")));
        return response(problem, false, null);
    }

    private static ResponseEntity<ApiResourceAuthoringProblemDetail> response(
            ApiResourceAuthoringProblemDetail problem, boolean challenge, Long retryAfter) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status())
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache");
        if (challenge) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"resource-gateway-authoring\"");
        }
        if (retryAfter != null) response.header(HttpHeaders.RETRY_AFTER, retryAfter.toString());
        return response.body(problem);
    }

    private static String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(
                com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes.CORRELATION_ID);
        return value == null ? "unknown" : value.toString();
    }
}
