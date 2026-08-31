package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.AuthoringRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** Thin authenticated adapter for private Fixture Set discovery and reads. */
@RestController
@RequestMapping("/api/authoring/fixture-sets")
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public final class ApiFixtureSetAuthoringController {
    private final ApiFixtureSetAuthoringFacade facade;
    private final IntegrationRequestAuthenticator authenticator;

    /** Creates the adapter over the private Fixture read module. */
    public ApiFixtureSetAuthoringController(ApiFixtureSetAuthoringFacade facade,
                                            IntegrationRequestAuthenticator authenticator) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /** Returns the current or one exact private Fixture revision. */
    @GetMapping(path = "/{fixtureSetId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FixtureSetView> read(@PathVariable String fixtureSetId,
                                               @RequestParam(required = false) String revision,
                                               @RequestHeader HttpHeaders headers,
                                               HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(headers, request);
        return response(facade.read(trustedScope(context), fixtureSetId,
                revision == null ? null : positiveRevision(revision, context.correlationId())));
    }

    /** Returns payload-free summaries for one exact immutable subject. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<FixtureSetSummary>> list(
            @RequestParam String subjectKind,
            @RequestParam String subjectId,
            @RequestParam String subjectRevision,
            @RequestParam String subjectFingerprint,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(headers, request);
        FixtureSubjectRef subject = subject(subjectKind, subjectId,
                positiveRevision(subjectRevision, context.correlationId()), subjectFingerprint,
                context.correlationId());
        return response(facade.list(trustedScope(context), subject));
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_FIXTURE_SET_READ);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        return context;
    }

    private static FixtureSubjectRef subject(String kind, String id, int revision, String fingerprint,
                                              String correlationId) {
        try {
            return switch (kind) {
                case "API_RESOURCE" -> new FixtureSubjectRef.ApiResource(id, revision, fingerprint);
                case "FLOW_DRAFT" -> new FixtureSubjectRef.FlowDraft(id, revision, fingerprint);
                case "FLOW_VERSION" -> new FixtureSubjectRef.FlowVersion(id, revision, fingerprint);
                default -> throw new IllegalArgumentException("unsupported subject kind");
            };
        } catch (RuntimeException failure) {
            throw invalid(correlationId);
        }
    }

    private static int positiveRevision(String value, String correlationId) {
        try {
            int revision = Integer.parseInt(value);
            if (revision < 1) throw new NumberFormatException("revision must be positive");
            return revision;
        } catch (RuntimeException failure) {
            throw invalid(correlationId);
        }
    }

    private static AuthoringScope trustedScope(IntegrationRequestContext context) {
        try {
            return new AuthoringScope(context.tenantId(), context.projectId(), context.environmentId());
        } catch (IllegalArgumentException failure) {
            throw invalid(context.correlationId());
        }
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    private static IntegrationProblemException invalid(String correlationId) {
        return new IntegrationProblemException(new IntegrationProblem(
                IntegrationProblem.SCHEMA_VERSION, "urn:bloge:problem:bad-authoring-request",
                "Fixture Set request is invalid", 400, "RG.AUTHORING.FIXTURE_SET.REQUEST_INVALID",
                false, correlationId, java.util.Map.of()));
    }
}
