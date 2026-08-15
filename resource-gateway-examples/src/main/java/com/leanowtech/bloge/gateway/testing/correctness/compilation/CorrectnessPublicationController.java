package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublicationAttempt;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.envelope;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.noStore;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.problem;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.requireCoordinate;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationHttpSupport.requireIdempotencyKey;

/** Authenticated recoverable publication HTTP adapter. */
@RestController
@ConditionalOnBean(CorrectnessPublicationService.class)
@RequestMapping("/api/visual")
public final class CorrectnessPublicationController {

    private final CorrectnessPublicationService publication;
    private final IntegrationRequestAuthenticator authenticator;

    public CorrectnessPublicationController(
            CorrectnessPublicationService publication,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.publication = publication;
        this.authenticator = authenticator;
    }

    @PostMapping("/correctness-publications")
    public ResponseEntity<CorrectnessApiEnvelope<CorrectnessPublicationRepository.CommitResult>>
            publish(
                    @RequestHeader HttpHeaders headers,
                    @RequestBody(required = false) CompilationCoordinate coordinate
            ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_PUBLICATION_WRITE);
        try {
            requireCoordinate(coordinate);
            var result = publication.publish(
                    coordinate, requireIdempotencyKey(headers), identity);
            return noStore(envelope(identity, "CORRECTNESS_PUBLICATION_V1", result));
        } catch (CorrectnessCompilationException failure) {
            throw problem(failure.status(), failure.code(), failure.getMessage(),
                    failure.retryable(), identity);
        } catch (CorrectnessPublicationException failure) {
            throw problem(failure.status(), failure.code(), failure.getMessage(),
                    failure.retryable(), identity);
        }
    }

    @GetMapping("/correctness-publications/{publicationId}")
    public ResponseEntity<CorrectnessApiEnvelope<StoredCorrectnessPublication>> publication(
            @PathVariable String publicationId,
            @RequestHeader HttpHeaders headers
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_PUBLICATION_READ);
        try {
            return noStore(envelope(
                    identity, "CORRECTNESS_PUBLICATION_V1",
                    publication.findPublication(publicationId, identity)));
        } catch (CorrectnessPublicationException failure) {
            throw problem(failure.status(), failure.code(), failure.getMessage(),
                    failure.retryable(), identity);
        }
    }

    @GetMapping("/correctness-publications/attempts/{attemptId}")
    public ResponseEntity<CorrectnessApiEnvelope<StoredCorrectnessPublicationAttempt>> attempt(
            @PathVariable String attemptId,
            @RequestHeader HttpHeaders headers
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_PUBLICATION_READ);
        try {
            return noStore(envelope(
                    identity, "CORRECTNESS_PUBLICATION_ATTEMPT_V1",
                    publication.findAttempt(attemptId, identity)));
        } catch (CorrectnessPublicationException failure) {
            throw problem(failure.status(), failure.code(), failure.getMessage(),
                    failure.retryable(), identity);
        }
    }

    @GetMapping("/correctness-publications/attempts/{attemptId}/history")
    public ResponseEntity<CorrectnessApiEnvelope<List<StoredCorrectnessPublicationAttempt>>>
            history(
                    @PathVariable String attemptId,
                    @RequestHeader HttpHeaders headers
            ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_PUBLICATION_READ);
        return noStore(envelope(
                identity, "CORRECTNESS_PUBLICATION_HISTORY_V1",
                publication.history(attemptId, identity)));
    }

}
