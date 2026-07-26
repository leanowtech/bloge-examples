package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationApplicationService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAssessmentAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAssessmentRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationBundle;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationCompletenessAssessment;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationDisposition;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationDispositionAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest;
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
 * Protected strict transport for selected populations, legal dispositions, and completeness.
 *
 * <p>The route is physically absent from production. Every write authenticates the independent
 * authority role before body decoding. Reads derive complete enterprise scope from trusted
 * identity and never accept scope from caller-controlled parameters.</p>
 */
@RestController
@RequestMapping("/api/mirror/outcome-selected-populations")
@Profile("!production & (test | staging)")
@ConditionalOnBean(
        AuthoritativeOutcomeSelectedPopulationApplicationService
                .class)
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class
AuthoritativeOutcomeSelectedPopulationController {
    private final AuthoritativeOutcomeSelectedPopulationApplicationService
            service;
    private final IntegrationRequestAuthenticator authenticator;
    private final AuthoritativeOutcomeSelectedPopulationRequestDecoder
            decoder;

    /**
     * Creates the protected selected-population transport.
     *
     * @param service governed application boundary
     * @param authenticator trusted workload identity boundary
     * @param decoder strict post-authentication command decoder
     */
    public AuthoritativeOutcomeSelectedPopulationController(
            AuthoritativeOutcomeSelectedPopulationApplicationService
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

    /** Verifies, signs, and appends one complete immutable selected population. */
    @PostMapping
    public ResponseEntity<
            IntegrationEnvelope<
                    AuthoritativeOutcomeSelectedPopulationAdmission>>
    ingestPopulation(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_POPULATION_INGEST);
        AuthoritativeOutcomeSelectedPopulationAdmission
                admission = service.ingestPopulation(
                decoder.decodePopulation(
                        request, identity),
                identity);
        return ResponseEntity.created(
                        URI.create(
                                "/api/mirror/outcome-selected-populations/"
                                        + admission.population()
                                        .manifest()
                                        .populationId()
                                        + "/revisions/"
                                        + admission.population()
                                        .manifest()
                                        .revision()))
                .body(IntegrationEnvelope.of(
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ADMISSION",
                        admission.schemaVersion(),
                        admission));
    }

    /** Reads one exact complete population revision after authority reverification. */
    @GetMapping("/{populationId}/revisions/{revision}")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationBundle>
    findPopulation(
            @PathVariable String populationId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                readIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationBundle
                population = service.findPopulation(
                populationId, revision, identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_BUNDLE",
                population.schemaVersion(),
                population);
    }

    /** Reads the current complete population revision after authority reverification. */
    @GetMapping("/{populationId}/latest")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationBundle>
    findLatestPopulation(
            @PathVariable String populationId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                readIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationBundle
                population = service.findLatestPopulation(
                populationId, identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_BUNDLE",
                population.schemaVersion(),
                population);
    }

    /** Verifies, signs, and appends one independently authorized legal disposition. */
    @PostMapping("/{populationId}/dispositions")
    public ResponseEntity<
            IntegrationEnvelope<
                    AuthoritativeOutcomeSelectedPopulationDispositionAdmission>>
    ingestDisposition(
            @PathVariable String populationId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_DISPOSITION_INGEST);
        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                command = decoder.decodeDisposition(
                request, identity);
        AuthoritativeOutcomeSelectedPopulationDispositionAdmission
                admission = service.ingestDisposition(
                populationId,
                command,
                identity);
        return ResponseEntity.created(
                        URI.create(
                                "/api/mirror/outcome-selected-populations/"
                                        + populationId
                                        + "/dispositions/"
                                        + admission.disposition()
                                        .dispositionId()
                                        + "/revisions/"
                                        + admission.disposition()
                                        .revision()))
                .body(IntegrationEnvelope.of(
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION_ADMISSION",
                        admission.schemaVersion(),
                        admission));
    }

    /** Reads one exact legal-disposition revision after deletion-authority reverification. */
    @GetMapping(
            "/{populationId}/dispositions/{dispositionId}/revisions/{revision}")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationDisposition>
    findDisposition(
            @PathVariable String populationId,
            @PathVariable String dispositionId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                readIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationDisposition
                disposition = service.findDisposition(
                populationId,
                dispositionId,
                revision,
                identity);
        return IntegrationEnvelope.of(
                AuthoritativeOutcomeSelectedPopulationDisposition
                        .ARTIFACT_KIND,
                disposition.schemaVersion(),
                disposition);
    }

    /** Projects and appends one coherent current-head completeness assessment. */
    @PostMapping("/{populationId}/assessments")
    public ResponseEntity<
            IntegrationEnvelope<
                    AuthoritativeOutcomeSelectedPopulationAssessmentAdmission>>
    assess(
            @PathVariable String populationId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_POPULATION_ASSESS);
        AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                command = decoder.decodeAssessment(
                request, identity);
        AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                admission = service.assess(
                populationId,
                command,
                identity);
        return ResponseEntity.created(
                        URI.create(
                                "/api/mirror/outcome-selected-populations/"
                                        + populationId
                                        + "/assessments/"
                                        + admission.assessment()
                                        .assessmentId()
                                        + "/revisions/"
                                        + admission.assessment()
                                        .revision()))
                .body(IntegrationEnvelope.of(
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ASSESSMENT_ADMISSION",
                        admission.schemaVersion(),
                        admission));
    }

    /** Reads one signed completeness assessment and its exact population root. */
    @GetMapping(
            "/{populationId}/assessments/{assessmentId}/revisions/{revision}")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment>
    findAssessment(
            @PathVariable String populationId,
            @PathVariable String assessmentId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                readIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment = service.findAssessment(
                populationId,
                assessmentId,
                revision,
                identity);
        return IntegrationEnvelope.of(
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        .ARTIFACT_KIND,
                assessment.schemaVersion(),
                assessment);
    }

    /** Reads one content-addressed suffix of the historical assessment source closure. */
    @GetMapping(
            "/{populationId}/assessments/{assessmentId}/revisions/{revision}/sources")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage>
    assessmentSources(
            @PathVariable String populationId,
            @PathVariable String assessmentId,
            @PathVariable long revision,
            @RequestParam(defaultValue = "0")
            long afterGlobalOrdinal,
            @RequestParam(defaultValue = "100")
            int limit,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                readIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                page = service.assessmentSources(
                populationId,
                assessmentId,
                revision,
                afterGlobalOrdinal,
                limit,
                identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ASSESSMENT_SOURCE_PAGE",
                page.schemaVersion(),
                page);
    }

    private IntegrationRequestContext readIdentity(
            HttpHeaders headers) {
        return authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_READ);
    }
}
