package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentLifecyclePage;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;

/**
 * Protected strict transport for continuous selected-population completeness projection.
 *
 * <p>The route is physically absent from production. Registration authenticates the dedicated
 * governance operation before bounded strict decoding. Reads derive complete enterprise scope
 * only from trusted identity and return effective readiness at a database observation time.</p>
 */
@RestController
@RequestMapping("/api/mirror/outcome-continuous-assessments")
@Profile("!production & (test | staging)")
@ConditionalOnBean(
        AuthoritativeOutcomeContinuousAssessmentService.class)
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class
AuthoritativeOutcomeContinuousAssessmentController {
    private final AuthoritativeOutcomeContinuousAssessmentService
            service;
    private final IntegrationRequestAuthenticator authenticator;
    private final AuthoritativeOutcomeSelectedPopulationRequestDecoder
            decoder;

    /**
     * Creates the protected continuous-assessment transport.
     *
     * @param service audited product boundary
     * @param authenticator trusted workload identity boundary
     * @param decoder strict post-authentication command decoder
     */
    public AuthoritativeOutcomeContinuousAssessmentController(
            AuthoritativeOutcomeContinuousAssessmentService
                    service,
            IntegrationRequestAuthenticator authenticator,
            AuthoritativeOutcomeSelectedPopulationRequestDecoder
                    decoder) {
        this.service = Objects.requireNonNull(
                service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(
                decoder, "decoder");
    }

    /** Registers or exactly replays one immutable continuous projection intent. */
    @PostMapping
    public ResponseEntity<
            IntegrationEnvelope<
                    AuthoritativeOutcomeContinuousAssessmentAdmission>>
    register(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGISTER);
        AuthoritativeOutcomeContinuousAssessmentRequest command =
                decoder.decodeContinuousAssessment(
                        request, identity);
        AuthoritativeOutcomeContinuousAssessmentAdmission
                admission = service.register(
                command, identity);
        return ResponseEntity.created(
                        URI.create(
                                "/api/mirror/outcome-continuous-assessments/"
                                        + admission.status()
                                        .projection()
                                        .projectionId()))
                .body(IntegrationEnvelope.of(
                        "AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_ADMISSION",
                        admission.schemaVersion(),
                        admission));
    }

    /** Reads one database-observed effective projection status. */
    @GetMapping("/{projectionId}")
    public IntegrationEnvelope<
            AuthoritativeOutcomeContinuousAssessmentStatus>
    find(
            @PathVariable String projectionId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_READ);
        AuthoritativeOutcomeContinuousAssessmentStatus status =
                service.find(
                        projectionId, identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_STATUS",
                status.schemaVersion(),
                status);
    }

    /** Reads one bounded hash-chained projection lifecycle page. */
    @GetMapping("/{projectionId}/lifecycle")
    public IntegrationEnvelope<
            AuthoritativeOutcomeContinuousAssessmentLifecyclePage>
    lifecycle(
            @PathVariable String projectionId,
            @RequestParam(defaultValue = "0")
            long afterOrdinal,
            @RequestParam(defaultValue = "100")
            int limit,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_READ);
        AuthoritativeOutcomeContinuousAssessmentLifecyclePage page =
                service.lifecycle(
                projectionId,
                afterOrdinal,
                limit,
                identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_PAGE",
                page.schemaVersion(),
                page);
    }
}
