package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringRead;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetStrongEtag;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveResult;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Thin authenticated adapter for private Fixture Set discovery and reads. */
@RestController
@RequestMapping("/api/authoring/fixture-sets")
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public final class ApiFixtureSetAuthoringController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");
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
        ApiFixtureSetAuthoringRead result = facade.read(trustedScope(context), fixtureSetId,
                revision == null ? null : positiveRevision(revision, context.correlationId()));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache");
        if (result.strongEtag() != null) response.header(HttpHeaders.ETAG, result.strongEtag());
        return response.body(result.view());
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

    /** Creates or updates one whole-flow Fixture Set under an opaque strong precondition. */
    @PutMapping(path = "/{fixtureSetId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FixtureSetSaveReceipt> save(
            @PathVariable String fixtureSetId,
            @RequestHeader HttpHeaders headers,
            @RequestBody FixtureSetCommand command,
            HttpServletRequest request) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.AUTHORING_FIXTURE_SET_WRITE, request);
        StandaloneFixtureSetSaveResult result = facade.save(
                trustedScope(context), context.actorId(), fixtureSetId,
                precondition(headers, context.correlationId()),
                idempotencyKey(headers, context.correlationId()), command);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.ETAG, result.strongEtag())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.receipt());
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, HttpServletRequest request) {
        return authenticate(headers, IntegrationOperation.AUTHORING_FIXTURE_SET_READ, request);
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, IntegrationOperation operation,
                                                     HttpServletRequest request) {
        IntegrationRequestContext context = authenticator.authenticate(headers, operation);
        request.setAttribute(AuthoringRequestAttributes.CORRELATION_ID, context.correlationId());
        return context;
    }

    private static FixtureSetPrecondition precondition(HttpHeaders headers, String correlationId) {
        String ifNoneMatch = headers.getFirst(HttpHeaders.IF_NONE_MATCH);
        String ifMatch = headers.getFirst(HttpHeaders.IF_MATCH);
        if ("*".equals(ifNoneMatch) && ifMatch == null) return FixtureSetPrecondition.create();
        if (ifNoneMatch == null && headers.get(HttpHeaders.IF_MATCH) != null
                && headers.get(HttpHeaders.IF_MATCH).size() == 1
                && FixtureSetStrongEtag.isValid(ifMatch)) {
            return FixtureSetPrecondition.match(ifMatch);
        }
        throw invalid(correlationId);
    }

    private static String idempotencyKey(HttpHeaders headers, String correlationId) {
        List<String> values = headers.get("Idempotency-Key");
        if (values == null || values.size() != 1 || values.getFirst().length() > 160
                || !IDEMPOTENCY_KEY.matcher(values.getFirst()).matches()) {
            throw invalid(correlationId);
        }
        return values.getFirst();
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
