package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.server.rest.dto.ErrorResponse;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceErrorCode;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceException;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Central HTTP error mapping for the graph-engine REST API.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maps structured service-layer failures to stable HTTP responses.
     *
     * @param exception service exception
     * @param request current HTTP request
     * @return error response
     */
    @ExceptionHandler(GraphEngineServiceException.class)
    public ResponseEntity<ErrorResponse> handleServiceException(GraphEngineServiceException exception,
                                                                HttpServletRequest request) {
        HttpStatus status = switch (exception.errorCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case INVALID_STATE, DUPLICATE_BUSINESS_KEY, CONFLICT -> HttpStatus.CONFLICT;
            case RUNTIME_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNSUPPORTED_EXECUTION_MODE -> HttpStatus.NOT_IMPLEMENTED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
        };
        return errorResponse(status, exception.errorCode().name(), exception.getMessage(), request, List.of());
    }

    /**
     * Maps structured metadata-store failures to HTTP responses.
     *
     * @param exception store exception
     * @param request current HTTP request
     * @return error response
     */
    @ExceptionHandler(GraphEngineStoreException.class)
    public ResponseEntity<ErrorResponse> handleStoreException(GraphEngineStoreException exception,
                                                              HttpServletRequest request) {
        HttpStatus status = switch (exception.errorCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE, VERSION_CONFLICT, INVALID_STATE_TRANSITION -> HttpStatus.CONFLICT;
            case TENANT_MISMATCH -> HttpStatus.FORBIDDEN;
        };
        return errorResponse(status, exception.errorCode().name(), exception.getMessage(), request, List.of());
    }

    /**
     * Maps request-body bean-validation failures to {@code 400 Bad Request}.
     *
     * @param exception validation exception
     * @param request current HTTP request
     * @return error response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                      HttpServletRequest request) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        return errorResponse(HttpStatus.BAD_REQUEST, GraphEngineServiceErrorCode.VALIDATION_FAILED.name(),
                "Request validation failed", request, details);
    }

    /**
     * Maps constraint-violation failures on query parameters and path variables.
     *
     * @param exception validation exception
     * @param request current HTTP request
     * @return error response
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception,
                                                                   HttpServletRequest request) {
        List<String> details = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
        return errorResponse(HttpStatus.BAD_REQUEST, GraphEngineServiceErrorCode.VALIDATION_FAILED.name(),
                "Request validation failed", request, details);
    }

    /**
     * Maps request-shape and JSON decoding errors to {@code 400 Bad Request}.
     *
     * @param exception decoding exception
     * @param request current HTTP request
     * @return error response
     */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception exception,
                                                          HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, GraphEngineServiceErrorCode.VALIDATION_FAILED.name(),
                exception.getMessage(), request, List.of());
    }

    private ResponseEntity<ErrorResponse> errorResponse(HttpStatus status,
                                                        String errorCode,
                                                        String message,
                                                        HttpServletRequest request,
                                                        List<String> details) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                errorCode,
                message == null || message.isBlank() ? status.getReasonPhrase() : message,
                status.value(),
                Instant.now(),
                request.getRequestURI(),
                details
        ));
    }

    private String formatFieldError(FieldError fieldError) {
        String defaultMessage = fieldError.getDefaultMessage();
        return fieldError.getField() + ": "
                + (defaultMessage == null || defaultMessage.isBlank() ? "invalid value" : defaultMessage);
    }
}
