package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureMaterial;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureReceipt;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.SaveRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authenticated exact-draft fixture persistence and exact-revision materialization surface.
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/admin/visual-operator-library-authoring")
public final class VisualLibraryAuthoringFixtureController {

    private final AuthoringFixtureService fixtures;
    private final IntegrationRequestAuthenticator authenticator;
    private final AuthoringFixtureRequestDecoder decoder;

    @Autowired
    public VisualLibraryAuthoringFixtureController(
            AuthoringFixtureService fixtures,
            IntegrationRequestAuthenticator authenticator) {
        this(fixtures, authenticator, new AuthoringFixtureRequestDecoder());
    }

    VisualLibraryAuthoringFixtureController(
            AuthoringFixtureService fixtures,
            IntegrationRequestAuthenticator authenticator,
            AuthoringFixtureRequestDecoder decoder) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    @PostMapping("/drafts/{draftId}/fixtures")
    public ResponseEntity<FixtureReceipt> save(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] source) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.TEST_FIXTURE_WRITE);
        long draftRevision = expectedRevision(
                headers.getFirst(HttpHeaders.IF_MATCH), draftId);
        AuthoringFixtureRequestDecoder.DecodeResult decoded =
                decoder.decode(source);
        if (!decoded.successful()) {
            AuthoringFixtureRequestDecoder.DecodeFailure decodeFailure =
                    decoded.failure();
            throw failure(
                    decodeFailure.status(),
                    decodeFailure.code(),
                    decodeFailure.message(),
                    draftId,
                    draftRevision,
                    decodeFailure.authoringPath());
        }
        SaveRequest request = decoded.request();
        FixtureReceipt receipt = fixtures.save(
                draftId, draftRevision, request, identity);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(receipt.revision()))
                .body(receipt);
    }

    @GetMapping("/fixtures/{fixtureId}")
    public ResponseEntity<FixtureMaterial> find(
            @PathVariable String fixtureId,
            @RequestParam long revision,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.TEST_FIXTURE_READ);
        FixtureMaterial material = fixtures.find(
                fixtureId, revision, identity);
        return ResponseEntity.ok()
                .eTag(etag(material.fixture().revision()))
                .body(material);
    }

    @ExceptionHandler(AuthoringLifecycleException.class)
    public ResponseEntity<AuthoringProblem> lifecycleFailure(
            AuthoringLifecycleException exception) {
        AuthoringProblem problem = exception.problem();
        return ResponseEntity.status(problem.status()).body(problem);
    }

    @ExceptionHandler(IntegrationProblemException.class)
    public ResponseEntity<IntegrationProblem> integrationFailure(
            IntegrationProblemException exception) {
        IntegrationProblem problem = exception.problem();
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(problem.status());
        if (problem.status() == 401) {
            response.header(
                    HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer realm=\"resource-gateway-testing\"");
        }
        return response.body(problem);
    }

    private static long expectedRevision(String ifMatch, String draftId) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw failure(
                    428,
                    "RG.AUTHORING.IF_MATCH_REQUIRED",
                    "If-Match with the last observed draft revision is required.",
                    draftId,
                    0,
                    "/revision");
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2
                && value.startsWith("\"")
                && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            long revision = Long.parseLong(value);
            if (revision <= 0) {
                throw new NumberFormatException(
                        "non-positive draft revision");
            }
            return revision;
        } catch (NumberFormatException exception) {
            throw failure(
                    400,
                    "RG.AUTHORING.IF_MATCH_INVALID",
                    "If-Match must contain one positive numeric draft revision.",
                    draftId,
                    0,
                    "/revision");
        }
    }

    private static String etag(long revision) {
        return "\"" + revision + "\"";
    }

    private static AuthoringLifecycleException failure(
            int status,
            String code,
            String message,
            String draftId,
            long revision,
            String path) {
        AuthoringDiagnostic diagnostic = AuthoringDiagnostic.compiler(
                "ERROR", code, message, path, -1, Map.of());
        return new AuthoringLifecycleException(AuthoringProblem.of(
                code,
                message,
                status,
                draftId,
                revision,
                List.of(diagnostic)));
    }
}
