package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestAccessPort;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestAccessPort.Action;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionDraft;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraft;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.DraftGate;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceView;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceService;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestPrincipal;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Draft-revision-fenced ephemeral operator and function test endpoints.
 */
@RestController
@RequestMapping("/admin/visual-operator-library-authoring/drafts")
public final class VisualLibraryAuthoringTestController {

    private final AuthoringTestService tests;
    private final AuthoringTestEvidenceService evidence;
    private final AuthoringTestAccessPort access;

    public VisualLibraryAuthoringTestController(
            AuthoringTestService tests,
            AuthoringTestEvidenceService evidence,
            AuthoringTestAccessPort access) {
        this.tests = java.util.Objects.requireNonNull(tests, "tests");
        this.evidence = java.util.Objects.requireNonNull(evidence, "evidence");
        this.access = java.util.Objects.requireNonNull(access, "access");
    }

    @PostMapping("/{draftId}/tests/operators/draft")
    public ResponseEntity<OperatorDraft> draftOperator(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) OperatorDraftRequest request) {
        AuthoringTestPrincipal principal = access.authenticate(headers, Action.DRAFT_READ);
        String ifMatch = headers.getFirst(HttpHeaders.IF_MATCH);
        long revision = expectedRevision(ifMatch, draftId);
        return ResponseEntity.ok()
                .eTag(etag(revision))
                .body(tests.draftOperator(
                        draftId,
                        revision,
                        request,
                        principal));
    }

    @PostMapping("/{draftId}/tests/operators/run")
    public ResponseEntity<OperatorRunEvidence> runOperator(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) OperatorRunRequest request) {
        AuthoringTestPrincipal principal = access.authenticate(headers, Action.EXECUTE);
        long revision = expectedRevision(headers.getFirst(HttpHeaders.IF_MATCH), draftId);
        return ResponseEntity.ok()
                .eTag(etag(revision))
                .body(tests.runOperator(
                        draftId,
                        revision,
                        request,
                        principal));
    }

    @PostMapping("/{draftId}/tests/functions/draft")
    public ResponseEntity<FunctionDraft> draftFunction(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) FunctionDraftRequest request) {
        AuthoringTestPrincipal principal = access.authenticate(headers, Action.DRAFT_READ);
        String ifMatch = headers.getFirst(HttpHeaders.IF_MATCH);
        long revision = expectedRevision(ifMatch, draftId);
        return ResponseEntity.ok()
                .eTag(etag(revision))
                .body(tests.draftFunction(
                        draftId,
                        revision,
                        request,
                        principal));
    }

    @PostMapping("/{draftId}/tests/functions/run")
    public ResponseEntity<FunctionRunEvidence> runFunction(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) FunctionRunRequest request) {
        AuthoringTestPrincipal principal = access.authenticate(headers, Action.EXECUTE);
        long revision = expectedRevision(headers.getFirst(HttpHeaders.IF_MATCH), draftId);
        return ResponseEntity.ok()
                .eTag(etag(revision))
                .body(tests.runFunction(
                        draftId,
                        revision,
                        request,
                        principal));
    }

    @GetMapping("/{draftId}/tests/evidence/{runId}")
    public EvidenceView evidence(
            @PathVariable String draftId,
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        return evidence.find(
                draftId,
                runId,
                access.authenticate(headers, Action.EVIDENCE_READ));
    }

    @GetMapping("/{draftId}/tests/gate")
    public DraftGate gate(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers) {
        return evidence.gate(
                draftId,
                access.authenticate(headers, Action.GATE_READ));
    }

    @ExceptionHandler(AuthoringLifecycleException.class)
    public ResponseEntity<AuthoringProblem> lifecycleFailure(AuthoringLifecycleException exception) {
        AuthoringProblem problem = exception.problem();
        return ResponseEntity.status(problem.status()).body(problem);
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
                    "/revision");
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
                Map.of());
        return new AuthoringLifecycleException(AuthoringProblem.of(
                code,
                message,
                status,
                draftId,
                revision,
                List.of(diagnostic)));
    }
}
