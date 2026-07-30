package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lifecycle boundary for autosaved authoring sources and exact preview-fenced catalog commits.
 */
public class AuthoringDraftService {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}");
    private static final Set<String> SOURCE_MODES = Set.of(
            AuthoringDraft.SOURCE_MODE_QUICK,
            AuthoringDraft.SOURCE_MODE_CANONICAL
    );

    private final AuthoringDraftRepository drafts;
    private final AuthoringPreviewService previews;
    private final OperatorLibraryRegistry libraries;
    private final ObjectMapper objectMapper;

    public AuthoringDraftService(AuthoringDraftRepository drafts,
                                 AuthoringPreviewService previews,
                                 OperatorLibraryRegistry libraries,
                                 ObjectMapper objectMapper) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Collection<AuthoringDraft> all() {
        return drafts.all();
    }

    public AuthoringDraft find(String draftId) {
        String id = requireId(draftId);
        return drafts.find(id).orElseThrow(() -> failure(
                404,
                "RG.AUTHORING.DRAFT_NOT_FOUND",
                "Visual library authoring draft was not found.",
                id,
                0,
                "/"
        ));
    }

    public List<AuthoringDraft> revisions(String draftId) {
        return drafts.revisions(requireId(draftId));
    }

    public AuthoringDraft save(String draftId,
                               long expectedRevision,
                               String sourceMode,
                               VisualLibraryAuthoringDocument document,
                               String actor) {
        String id = requireId(draftId);
        if (expectedRevision < 0 || document == null) {
            throw failure(
                    400,
                    "RG.AUTHORING.DRAFT_REQUEST_INVALID",
                    "A document and non-negative expected revision are required.",
                    id,
                    Math.max(0, expectedRevision),
                    "/"
            );
        }
        String mode = normalized(sourceMode, AuthoringDraft.SOURCE_MODE_QUICK).toUpperCase(Locale.ROOT);
        if (!SOURCE_MODES.contains(mode)) {
            throw failure(
                    400,
                    "RG.AUTHORING.SOURCE_MODE_UNSUPPORTED",
                    "sourceMode must be QUICK or CANONICAL.",
                    id,
                    expectedRevision,
                    "/sourceMode"
            );
        }
        List<com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic> secrets =
                VisualSecretGuard.detectRawSecrets(
                        objectMapper.convertValue(document, Object.class),
                        "/document"
                );
        if (!secrets.isEmpty()) {
            throw failure(
                    400,
                    "RG.AUTHORING.RAW_SECRET_FORBIDDEN",
                    "Raw secret material cannot be stored in a library authoring draft.",
                    id,
                    expectedRevision,
                    secrets.getFirst().target()
            );
        }
        AuthoringDraft candidate = AuthoringDraft.unsaved(id, mode, document);
        return drafts.saveIfRevision(
                        expectedRevision,
                        candidate,
                        normalized(actor, "visual-library-workbench"))
                .orElseThrow(() -> stale(id, expectedRevision));
    }

    public AuthoringCompileResult preview(String draftId, long expectedRevision) {
        AuthoringDraft draft = exactDraft(draftId, expectedRevision);
        return previews.preview(draft.document())
                .withDraftContext(draft.draftId(), draft.revision());
    }

    @Transactional
    public synchronized AuthoringCommitResult commit(String draftId,
                                                     long expectedRevision,
                                                     CommitRequest request) {
        AuthoringDraft draft = exactDraft(draftId, expectedRevision);
        if (request == null) {
            throw failure(
                    400,
                    "RG.AUTHORING.COMMIT_REQUEST_REQUIRED",
                    "Commit request is required.",
                    draft.draftId(),
                    draft.revision(),
                    "/"
            );
        }
        AuthoringCompileResult preview = previews.preview(draft.document())
                .withDraftContext(draft.draftId(), draft.revision());
        requireMatch(
                request.authoringFingerprint(),
                preview.authoringFingerprint(),
                "RG.AUTHORING.STALE_PREVIEW",
                "Authoring source changed after the submitted preview.",
                draft,
                "/authoringFingerprint"
        );
        requireMatch(
                request.compileFingerprint(),
                preview.compileFingerprint(),
                "RG.AUTHORING.COMPILER_DRIFT",
                "Compiler or grammar changed after the submitted preview.",
                draft,
                "/compileFingerprint"
        );
        requireMatch(
                request.catalogFingerprint(),
                preview.catalogFingerprint(),
                "RG.AUTHORING.CATALOG_DRIFT",
                "The target catalog changed after the submitted preview.",
                draft,
                "/catalogFingerprint"
        );
        requireMatch(
                request.canonicalFingerprint(),
                preview.canonicalFingerprint(),
                "RG.AUTHORING.CANONICAL_DRIFT",
                "Canonical output changed after the submitted preview.",
                draft,
                "/canonicalFingerprint"
        );
        if (!preview.importable() || preview.canonicalLibrary() == null) {
            throw failure(
                    409,
                    "RG.AUTHORING.COMMIT_NOT_READY",
                    "The current authoritative preview is not importable.",
                    draft.draftId(),
                    draft.revision(),
                    "/"
            );
        }

        OperatorLibrary library = preview.canonicalLibrary();
        long currentTargetRevision = currentTargetRevision(library.libraryId());
        if (request.targetRevision() != currentTargetRevision) {
            throw failure(
                    409,
                    "RG.AUTHORING.TARGET_REVISION_STALE",
                    "The target operator library revision changed after preview.",
                    draft.draftId(),
                    draft.revision(),
                    "/targetRevision"
            );
        }
        String actor = normalized(request.actor(), "visual-library-workbench");
        libraries.upsert(library, OperatorLibraryRevision.RevisionMetadata.of(
                actor,
                "visual-library-workbench",
                "Committed authoring draft %s@%d.".formatted(draft.draftId(), draft.revision()),
                normalized(request.reason(), "Validated visual authoring commit.")
        ));
        return new AuthoringCommitResult(
                AuthoringCommitResult.SCHEMA_VERSION,
                draft.draftId(),
                draft.revision(),
                preview.authoringFingerprint(),
                preview.canonicalFingerprint(),
                preview.catalogFingerprint(),
                currentTargetRevision + 1,
                library,
                preview,
                Instant.now(),
                actor
        );
    }

    private AuthoringDraft exactDraft(String draftId, long expectedRevision) {
        AuthoringDraft draft = find(draftId);
        if (expectedRevision <= 0 || draft.revision() != expectedRevision) {
            throw stale(draft.draftId(), expectedRevision);
        }
        return draft;
    }

    private long currentTargetRevision(String libraryId) {
        return libraries.revisions(libraryId).stream()
                .mapToLong(OperatorLibraryRevision::revision)
                .max()
                .orElse(0);
    }

    private void requireMatch(String submitted,
                              String actual,
                              String code,
                              String message,
                              AuthoringDraft draft,
                              String path) {
        if (!normalized(submitted, "").equals(actual)) {
            throw failure(409, code, message, draft.draftId(), draft.revision(), path);
        }
    }

    private static String requireId(String draftId) {
        String id = normalized(draftId, "");
        if (!SAFE_ID.matcher(id).matches()) {
            throw failure(
                    400,
                    "RG.AUTHORING.DRAFT_ID_INVALID",
                    "draftId must be 1-255 safe identifier characters.",
                    id,
                    0,
                    "/draftId"
            );
        }
        return id;
    }

    private static AuthoringLifecycleException stale(String draftId, long expectedRevision) {
        long revision = Math.max(0, expectedRevision);
        return failure(
                412,
                "RG.AUTHORING.DRAFT_REVISION_STALE",
                "The authoring draft changed in another session; reload before saving or committing.",
                draftId,
                revision,
                "/revision"
        );
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

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record CommitRequest(
            String authoringFingerprint,
            String compileFingerprint,
            String catalogFingerprint,
            String canonicalFingerprint,
            long targetRevision,
            String actor,
            String reason
    ) {
        public CommitRequest {
            authoringFingerprint = normalized(authoringFingerprint, "");
            compileFingerprint = normalized(compileFingerprint, "");
            catalogFingerprint = normalized(catalogFingerprint, "");
            canonicalFingerprint = normalized(canonicalFingerprint, "");
            targetRevision = Math.max(0, targetRevision);
            actor = normalized(actor, "");
            reason = normalized(reason, "");
        }
    }
}
