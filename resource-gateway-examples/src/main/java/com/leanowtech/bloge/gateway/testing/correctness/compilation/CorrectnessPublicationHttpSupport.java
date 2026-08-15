package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/** Shared transport policy for the separate compilation and publication adapters. */
final class CorrectnessPublicationHttpSupport {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private CorrectnessPublicationHttpSupport() {
    }

    static void requireCoordinate(CompilationCoordinate coordinate) {
        if (coordinate == null) {
            throw new CorrectnessPublicationException(
                    400, "RG.CORRECTNESS.COMPILATION_COORDINATE_REQUIRED",
                    "An exact correctness compilation coordinate is required", false);
        }
    }

    static String requireIdempotencyKey(HttpHeaders headers) {
        String value = headers == null ? "" : headers.getFirst(IDEMPOTENCY_KEY);
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 512) {
            throw new CorrectnessPublicationException(
                    400, "RG.CORRECTNESS.PUBLICATION_IDEMPOTENCY_KEY_INVALID",
                    "A bounded Idempotency-Key is required", false);
        }
        return normalized;
    }

    static <T> CorrectnessApiEnvelope<T> envelope(
            IntegrationRequestContext identity,
            String capability,
            T data
    ) {
        return CorrectnessApiEnvelope.of(
                identity.correlationId(), scope(identity), List.of(capability), data);
    }

    static <T> ResponseEntity<CorrectnessApiEnvelope<T>> noStore(
            CorrectnessApiEnvelope<T> body
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    static IntegrationProblemException problem(
            int status,
            String code,
            String message,
            boolean retryable,
            IntegrationRequestContext identity
    ) {
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:correctness-publication", message, status,
                code, retryable, identity.correlationId(), Map.of()));
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }
}
