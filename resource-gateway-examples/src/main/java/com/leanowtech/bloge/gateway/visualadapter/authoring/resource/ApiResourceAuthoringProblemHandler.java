package com.leanowtech.bloge.gateway.visualadapter.authoring.resource;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Maps every API Resource transport failure to one Authoring Problem Detail shape. */
@RestControllerAdvice(assignableTypes = ApiResourceAuthoringController.class)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public final class ApiResourceAuthoringProblemHandler {
    private static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final Clock clock;

    /** Creates the production mapper using the system UTC clock for Retry-After. */
    public ApiResourceAuthoringProblemHandler() {
        this(Clock.systemUTC());
    }

    ApiResourceAuthoringProblemHandler(Clock clock) {
        this.clock = clock;
    }

    /** Converts trusted integration authentication and request-boundary failures. */
    @ExceptionHandler(IntegrationProblemException.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> integration(
            IntegrationProblemException failure, HttpServletRequest request) {
        IntegrationProblem source = failure.problem();
        ApiResourceAuthoringProblemDetail problem = new ApiResourceAuthoringProblemDetail(
                source.type(), source.title(), source.status(), source.title(), source.code(),
                correlation(source.correlationId(), request), List.of(),
                source.retryable() ? List.of(action("RETRY", null)) : List.of());
        Long retryAfter = source.details().get("retryAfterSeconds") instanceof Number seconds
                ? bounded(seconds.longValue()) : null;
        return response(problem, retryAfter, source.status() == 401);
    }

    /** Converts closed application failures without exposing payloads or persistence messages. */
    @ExceptionHandler(ApiResourceAuthoringFailure.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> application(
            ApiResourceAuthoringFailure failure, HttpServletRequest request) {
        Mapping mapping = mapping(failure.code());
        ApiResourceAuthoringProblemDetail problem = new ApiResourceAuthoringProblemDetail(
                mapping.type(), mapping.title(), mapping.status(), failure.getMessage(), mapping.code(),
                correlation("", request), List.of(), mapping.actions());
        return response(problem, retryAfter(failure.retryAt()), false);
    }

    /** Converts malformed JSON before it reaches the facade. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> malformed(
            HttpMessageNotReadableException failure, HttpServletRequest request) {
        ApiResourceAuthoringProblemDetail problem = new ApiResourceAuthoringProblemDetail(
                "urn:bloge:problem:bad-authoring-request", "API Resource request is invalid", 400,
                "The API Resource request body is malformed or incomplete.",
                "RG.AUTHORING.API_RESOURCE.REQUEST_INVALID", correlation("", request), List.of(),
                List.of(action("OPEN_FIELD", "/")));
        return response(problem, null, false);
    }

    /** Converts a missing or unsupported request content type to the same wire contract. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResourceAuthoringProblemDetail> unsupportedMedia(
            HttpMediaTypeNotSupportedException failure, HttpServletRequest request) {
        ApiResourceAuthoringProblemDetail problem = new ApiResourceAuthoringProblemDetail(
                "urn:bloge:problem:unsupported-authoring-media", "Content type is not supported", 415,
                "API Resource commands must use application/json.",
                "RG.AUTHORING.API_RESOURCE.CONTENT_TYPE_REQUIRED", correlation("", request), List.of(),
                List.of());
        return response(problem, null, false);
    }

    private ResponseEntity<ApiResourceAuthoringProblemDetail> response(
            ApiResourceAuthoringProblemDetail problem, Long retryAfter, boolean challenge) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status())
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache");
        if (challenge) response.header(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer realm=\"resource-gateway-authoring\"");
        if (retryAfter != null) response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        return response.body(problem);
    }

    private Long retryAfter(Instant retryAt) {
        if (retryAt == null) return null;
        long seconds = Math.max(1, Duration.between(clock.instant(), retryAt).toSeconds());
        return bounded(seconds);
    }

    private static Long bounded(long seconds) {
        return seconds >= 1 && seconds <= 3_600 ? seconds : null;
    }

    private static String correlation(String supplied, HttpServletRequest request) {
        if (validCorrelation(supplied)) return supplied;
        Object trusted = request.getAttribute(ApiResourceAuthoringController.CORRELATION_ATTRIBUTE);
        if (trusted instanceof String value && validCorrelation(value)) return value;
        String header = request.getHeader("X-Correlation-Id");
        return validCorrelation(header) ? header : UUID.randomUUID().toString();
    }

    private static boolean validCorrelation(String value) {
        return value != null && CORRELATION.matcher(value).matches();
    }

    private static ApiResourceAuthoringProblemDetail.RecoveryAction action(String kind, String path) {
        return new ApiResourceAuthoringProblemDetail.RecoveryAction(kind, path);
    }

    private static Mapping mapping(ApiResourceAuthoringFailure.Code code) {
        return switch (code) {
            case CAPABILITY_UNAVAILABLE -> mapping(424, "authoring-capability-unavailable",
                    "API Resource capability is unavailable", "CAPABILITY_UNAVAILABLE", List.of());
            case VALIDATION -> mapping(422, "authoring-validation", "API Resource cannot be saved",
                    "VALIDATION_FAILED", List.of(action("OPEN_FIELD", "/resource")));
            case CONNECTION_NOT_FOUND -> mapping(404, "authoring-resource-not-found",
                    "API Connection was not found", "CONNECTION_NOT_FOUND",
                    List.of(action("OPEN_LIST", "/api/authoring/connections")));
            case NOT_FOUND -> mapping(404, "authoring-resource-not-found", "API Resource was not found",
                    "NOT_FOUND", List.of(action("OPEN_LIST", "/api/authoring/resources")));
            case BUSY -> mapping(409, "authoring-conflict", "API Resource save is already in progress",
                    "BUSY", List.of(action("RETRY", null)));
            case LEASE_LOST -> mapping(409, "authoring-conflict", "API Resource save lease was lost",
                    "LEASE_LOST", List.of(action("RETRY", null)));
            case CONFLICT -> mapping(409, "authoring-conflict", "Idempotency key is already in use",
                    "IDEMPOTENCY_CONFLICT", List.of());
            case CAS_MISMATCH -> mapping(412, "authoring-precondition-failed",
                    "API Resource changed", "PRECONDITION_FAILED", List.of(action("RELOAD", null)));
            case CONNECTION_CHANGED -> mapping(412, "authoring-precondition-failed",
                    "API Connection changed", "CONNECTION_CHANGED", List.of(action("RELOAD", null)));
            case PROJECTION_INVALID -> mapping(422, "authoring-validation",
                    "API Resource projection is invalid", "PROJECTION_INVALID",
                    List.of(action("OPEN_FIELD", "/resource")));
            case INTEGRITY -> mapping(500, "authoring-integrity", "API Resource integrity check failed",
                    "INTEGRITY_FAILED", List.of());
            case PERSISTENCE -> mapping(503, "authoring-service-unavailable",
                    "API Resource persistence is unavailable", "PERSISTENCE_FAILED",
                    List.of(action("RETRY", null)));
        };
    }

    private static Mapping mapping(int status, String type, String title, String code,
                                   List<ApiResourceAuthoringProblemDetail.RecoveryAction> actions) {
        return new Mapping(status, "urn:bloge:problem:" + type, title,
                "RG.AUTHORING.API_RESOURCE." + code, actions);
    }

    private record Mapping(int status, String type, String title, String code,
                           List<ApiResourceAuthoringProblemDetail.RecoveryAction> actions) { }
}
