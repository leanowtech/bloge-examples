package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringCommitResult;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftAccessPort;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftAccessPort.Action;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPrincipal;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceApplyRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.authoring.parse.SampleInferenceRequestDecoder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * ETag-fenced mutable draft and design-catalog commit endpoints for the visual library workbench.
 */
@RestController
@RequestMapping("/admin/visual-operator-library-authoring/drafts")
public final class VisualLibraryAuthoringDraftController {

    private final AuthoringDraftService service;
    private final SampleInferenceRequestDecoder sampleInferenceDecoder;
    private final AuthoringDraftAccessPort access;

    @Autowired
    public VisualLibraryAuthoringDraftController(
            AuthoringDraftService service,
            AuthoringDraftAccessPort access) {
        this(service, new SampleInferenceRequestDecoder(), access);
    }

    VisualLibraryAuthoringDraftController(AuthoringDraftService service,
                                          SampleInferenceRequestDecoder sampleInferenceDecoder,
                                          AuthoringDraftAccessPort access) {
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.sampleInferenceDecoder = java.util.Objects.requireNonNull(
                sampleInferenceDecoder, "sampleInferenceDecoder");
        this.access = java.util.Objects.requireNonNull(access, "access");
    }

    @GetMapping
    public Collection<AuthoringDraft> list(@RequestHeader HttpHeaders headers) {
        AuthoringPrincipal principal = access.authenticate(headers, Action.READ);
        return service.all(principal.requireScope());
    }

    @GetMapping("/{draftId}")
    public ResponseEntity<AuthoringDraft> find(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers) {
        AuthoringPrincipal principal = access.authenticate(headers, Action.READ);
        AuthoringDraft draft = service.find(principal.requireScope(), draftId);
        return withEtag(draft, HttpStatus.OK);
    }

    @GetMapping("/{draftId}/revisions")
    public List<AuthoringDraft> revisions(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers) {
        AuthoringPrincipal principal = access.authenticate(headers, Action.READ);
        return service.revisions(principal.requireScope(), draftId);
    }

    @PutMapping("/{draftId}")
    public ResponseEntity<AuthoringDraft> save(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) DraftSaveRequest request) {
        AuthoringPrincipal principal = access.authenticate(headers, Action.WRITE);
        String ifMatch = headers.getFirst(HttpHeaders.IF_MATCH);
        long expectedRevision = expectedRevision(ifMatch, draftId);
        if (request == null) {
            throw failure(
                    400,
                    "RG.AUTHORING.DRAFT_REQUEST_REQUIRED",
                    "Draft save request is required.",
                    draftId,
                    expectedRevision,
                    "/"
            );
        }
        AuthoringDraft stored = service.save(
                principal.requireScope(),
                draftId,
                expectedRevision,
                request.sourceMode(),
                request.document(),
                principal.actorId()
        );
        return withEtag(stored, expectedRevision == 0 ? HttpStatus.CREATED : HttpStatus.OK);
    }

    @PostMapping("/{draftId}/preview")
    public ResponseEntity<AuthoringCompileResult> preview(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers) {
        AuthoringPrincipal principal = access.authenticate(headers, Action.READ);
        String ifMatch = headers.getFirst(HttpHeaders.IF_MATCH);
        long expectedRevision = expectedRevision(ifMatch, draftId);
        AuthoringCompileResult result = service.preview(
                principal.requireScope(), draftId, expectedRevision);
        return ResponseEntity.ok()
                .eTag(etag(expectedRevision))
                .body(result);
    }

    @PostMapping(
            value = "/{draftId}/infer/samples",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<SampleInferenceResult> inferSamples(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] source) {
        AuthoringPrincipal principal = access.authenticate(headers, Action.READ);
        String ifMatch = headers.getFirst(HttpHeaders.IF_MATCH);
        long expectedRevision = expectedRevision(ifMatch, draftId);
        SampleInferenceRequestDecoder.DecodeResult decoded =
                sampleInferenceDecoder.decode(source);
        if (!decoded.successful()) {
            SampleInferenceRequestDecoder.DecodeFailure failure = decoded.failure();
            throw failure(
                    failure.status(),
                    failure.code(),
                    failure.message(),
                    draftId,
                    expectedRevision,
                    failure.authoringPath()
            );
        }
        SampleInferenceResult result = service.inferSamples(
                principal.requireScope(), draftId, expectedRevision, decoded.request());
        return ResponseEntity.ok()
                .eTag(etag(expectedRevision))
                .body(result);
    }

    @PostMapping(
            value = "/{draftId}/infer/samples/apply",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AuthoringDraft> applySampleInference(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] source) {
        AuthoringPrincipal principal = access.authenticate(headers, Action.WRITE);
        String ifMatch = headers.getFirst(HttpHeaders.IF_MATCH);
        long expectedRevision = expectedRevision(ifMatch, draftId);
        SampleInferenceRequestDecoder.ApplyDecodeResult decoded =
                sampleInferenceDecoder.decodeApply(source);
        if (!decoded.successful()) {
            SampleInferenceRequestDecoder.DecodeFailure failure = decoded.failure();
            throw failure(
                    failure.status(),
                    failure.code(),
                    failure.message(),
                    draftId,
                    expectedRevision,
                    failure.authoringPath()
            );
        }
        AuthoringDraft stored = service.applySampleInference(
                principal.requireScope(),
                draftId,
                expectedRevision,
                decoded.request(),
                principal.actorId()
        );
        return withEtag(stored, HttpStatus.OK);
    }

    @PostMapping("/{draftId}/commit")
    public ResponseEntity<AuthoringCommitResult> commit(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) AuthoringDraftService.CommitRequest request) {
        AuthoringPrincipal principal = access.authenticate(headers, Action.COMMIT);
        String ifMatch = headers.getFirst(HttpHeaders.IF_MATCH);
        long expectedRevision = expectedRevision(ifMatch, draftId);
        AuthoringCommitResult result = service.commit(
                principal.requireScope(),
                draftId,
                expectedRevision,
                request,
                principal.actorId());
        return ResponseEntity.ok()
                .eTag(etag(expectedRevision))
                .body(result);
    }

    @ExceptionHandler(AuthoringLifecycleException.class)
    public ResponseEntity<AuthoringProblem> lifecycleFailure(AuthoringLifecycleException exception) {
        AuthoringProblem problem = exception.problem();
        return ResponseEntity.status(problem.status()).body(problem);
    }

    private static ResponseEntity<AuthoringDraft> withEtag(AuthoringDraft draft,
                                                            HttpStatus status) {
        return ResponseEntity.status(status)
                .eTag(etag(draft.revision()))
                .body(draft);
    }

    private static long expectedRevision(String ifMatch, String draftId) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw failure(
                    428,
                    "RG.AUTHORING.IF_MATCH_REQUIRED",
                    "If-Match with the last observed draft revision is required.",
                    draftId,
                    0,
                    "/revision"
            );
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            long revision = Long.parseLong(value);
            if (revision < 0) {
                throw new NumberFormatException("negative revision");
            }
            return revision;
        } catch (NumberFormatException exception) {
            throw failure(
                    400,
                    "RG.AUTHORING.IF_MATCH_INVALID",
                    "If-Match must contain one non-negative numeric draft revision.",
                    draftId,
                    0,
                    "/revision"
            );
        }
    }

    private static String etag(long revision) {
        return "\"" + Math.max(0, revision) + "\"";
    }

    private static AuthoringLifecycleException failure(int status,
                                                       String code,
                                                       String message,
                                                       String draftId,
                                                       long revision,
                                                       String path) {
        AuthoringDiagnostic diagnostic = AuthoringDiagnostic.compiler(
                "ERROR",
                code,
                message,
                path,
                -1,
                Map.of()
        );
        return new AuthoringLifecycleException(AuthoringProblem.of(
                code,
                message,
                status,
                draftId,
                revision,
                List.of(diagnostic)
        ));
    }

    public record DraftSaveRequest(
            String sourceMode,
            VisualLibraryAuthoringDocument document,
            String actor
    ) {
    }
}
