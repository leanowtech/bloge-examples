package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringCommitResult;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    public VisualLibraryAuthoringDraftController(AuthoringDraftService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    @GetMapping
    public Collection<AuthoringDraft> list() {
        return service.all();
    }

    @GetMapping("/{draftId}")
    public ResponseEntity<AuthoringDraft> find(@PathVariable String draftId) {
        AuthoringDraft draft = service.find(draftId);
        return withEtag(draft, HttpStatus.OK);
    }

    @GetMapping("/{draftId}/revisions")
    public List<AuthoringDraft> revisions(@PathVariable String draftId) {
        return service.revisions(draftId);
    }

    @PutMapping("/{draftId}")
    public ResponseEntity<AuthoringDraft> save(
            @PathVariable String draftId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) DraftSaveRequest request) {
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
                draftId,
                expectedRevision,
                request.sourceMode(),
                request.document(),
                request.actor()
        );
        return withEtag(stored, expectedRevision == 0 ? HttpStatus.CREATED : HttpStatus.OK);
    }

    @PostMapping("/{draftId}/preview")
    public ResponseEntity<AuthoringCompileResult> preview(
            @PathVariable String draftId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        long expectedRevision = expectedRevision(ifMatch, draftId);
        AuthoringCompileResult result = service.preview(draftId, expectedRevision);
        return ResponseEntity.ok()
                .eTag(etag(expectedRevision))
                .body(result);
    }

    @PostMapping("/{draftId}/commit")
    public ResponseEntity<AuthoringCommitResult> commit(
            @PathVariable String draftId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) AuthoringDraftService.CommitRequest request) {
        long expectedRevision = expectedRevision(ifMatch, draftId);
        AuthoringCommitResult result = service.commit(draftId, expectedRevision, request);
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
