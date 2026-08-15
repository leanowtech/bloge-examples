package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.envelope;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.noStore;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.problem;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.requireCoordinate;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.requireIdempotencyKey;

/** Authenticated payload-free preview adapter for one exact compilation coordinate. */
@RestController
@ConditionalOnBean(CorrectnessCompilationService.class)
@RequestMapping("/api/visual/correctness-publications:compile-preview")
public final class CorrectnessCompilationController {

    private final CorrectnessCompilationService compilation;
    private final IntegrationRequestAuthenticator authenticator;

    public CorrectnessCompilationController(
            CorrectnessCompilationService compilation,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.compilation = compilation;
        this.authenticator = authenticator;
    }

    @PostMapping
    public ResponseEntity<CorrectnessApiEnvelope<CorrectnessCompilationReport>> compilePreview(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) CompilationCoordinate coordinate
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_COMPILATION_PREVIEW);
        try {
            requireCoordinate(coordinate);
            requireIdempotencyKey(headers);
            return noStore(
                    envelope(
                            identity, "CORRECTNESS_COMPILATION_V1",
                            compilation.compile(coordinate, identity)));
        } catch (CorrectnessCompilationException failure) {
            throw problem(
                    failure.status(), failure.code(), failure.getMessage(),
                    failure.retryable(), identity);
        } catch (CorrectnessPublicationException failure) {
            throw problem(
                    failure.status(), failure.code(), failure.getMessage(),
                    failure.retryable(), identity);
        }
    }
}
