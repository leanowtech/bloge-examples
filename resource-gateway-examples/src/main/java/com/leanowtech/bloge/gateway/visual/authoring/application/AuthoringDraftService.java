package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.inference.SampleInferenceCandidateApplier;
import com.leanowtech.bloge.gateway.visual.authoring.inference.SampleInferenceRejectedException;
import com.leanowtech.bloge.gateway.visual.authoring.inference.SampleSchemaInferencer;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringConfirmation;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceApplyRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private static final Pattern SHA256 = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> SOURCE_MODES = Set.of(
            AuthoringDraft.SOURCE_MODE_QUICK,
            AuthoringDraft.SOURCE_MODE_CANONICAL
    );

    private final AuthoringDraftRepository drafts;
    private final AuthoringPreviewService previews;
    private final OperatorLibraryRegistry libraries;
    private final AuthoringCatalogOwnershipRepository catalogOwnership;
    private final ObjectMapper objectMapper;
    private final SampleSchemaInferencer sampleInferencer;
    private final SampleInferenceCandidateApplier sampleCandidateApplier;

    public AuthoringDraftService(AuthoringDraftRepository drafts,
                                 AuthoringPreviewService previews,
                                 OperatorLibraryRegistry libraries,
                                 AuthoringCatalogOwnershipRepository catalogOwnership,
                                 ObjectMapper objectMapper) {
        this(drafts, previews, libraries, catalogOwnership, objectMapper,
                new SampleSchemaInferencer(objectMapper),
                new SampleInferenceCandidateApplier());
    }

    public AuthoringDraftService(AuthoringDraftRepository drafts,
                                 AuthoringPreviewService previews,
                                 OperatorLibraryRegistry libraries,
                                 AuthoringCatalogOwnershipRepository catalogOwnership,
                                 ObjectMapper objectMapper,
                                 SampleSchemaInferencer sampleInferencer) {
        this(drafts, previews, libraries, catalogOwnership, objectMapper, sampleInferencer,
                new SampleInferenceCandidateApplier());
    }

    public AuthoringDraftService(AuthoringDraftRepository drafts,
                                 AuthoringPreviewService previews,
                                 OperatorLibraryRegistry libraries,
                                 AuthoringCatalogOwnershipRepository catalogOwnership,
                                 ObjectMapper objectMapper,
                                 SampleSchemaInferencer sampleInferencer,
                                 SampleInferenceCandidateApplier sampleCandidateApplier) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.catalogOwnership =
                Objects.requireNonNull(catalogOwnership, "catalogOwnership");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sampleInferencer = Objects.requireNonNull(sampleInferencer, "sampleInferencer");
        this.sampleCandidateApplier =
                Objects.requireNonNull(sampleCandidateApplier, "sampleCandidateApplier");
    }

    public Collection<AuthoringDraft> all(AuthoringScope scope) {
        return drafts.all(requireScope(scope));
    }

    public AuthoringDraft find(AuthoringScope scope, String draftId) {
        AuthoringScope requiredScope = requireScope(scope);
        String id = requireId(draftId);
        return drafts.find(requiredScope, id).orElseThrow(() -> failure(
                404,
                "RG.AUTHORING.DRAFT_NOT_FOUND",
                "Visual library authoring draft was not found in the authorized enterprise scope.",
                id,
                0,
                "/"
        ));
    }

    public List<AuthoringDraft> revisions(AuthoringScope scope, String draftId) {
        return drafts.revisions(requireScope(scope), requireId(draftId));
    }

    public AuthoringDraft save(AuthoringScope scope,
                               String draftId,
                               long expectedRevision,
                               String sourceMode,
                               VisualLibraryAuthoringDocument document,
                               String actor) {
        AuthoringScope requiredScope = requireScope(scope);
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
        requireSecretFree(id, expectedRevision, document);
        AuthoringDraft current = currentForSave(requiredScope, id, expectedRevision);
        EvidenceState retained = retainedEvidence(current, document);
        AuthoringDraft candidate = AuthoringDraft.unsaved(
                id,
                mode,
                document,
                retained.evidence(),
                retained.confirmations()
        );
        return drafts.saveIfRevision(
                        requiredScope,
                        expectedRevision,
                        candidate,
                        normalized(actor, "visual-library-workbench"))
                .orElseThrow(() -> stale(id, expectedRevision));
    }

    public AuthoringCompileResult preview(
            AuthoringScope scope,
            String draftId,
            long expectedRevision) {
        AuthoringDraft draft = exactDraft(requireScope(scope), draftId, expectedRevision);
        return previews.preview(draft.document())
                .withDraftContext(draft.draftId(), draft.revision());
    }

    /**
     * Infers observed facts for one exact operator port without modifying the draft or retaining payloads.
     */
    public SampleInferenceResult inferSamples(AuthoringScope scope,
                                              String draftId,
                                              long expectedRevision,
                                              SampleInferenceRequest request) {
        AuthoringDraft draft = exactDraft(requireScope(scope), draftId, expectedRevision);
        return inferSamples(draft, request);
    }

    /**
     * Replays inference and atomically promotes explicit decisions into one new draft revision.
     */
    @Transactional
    public synchronized AuthoringDraft applySampleInference(
            AuthoringScope scope,
            String draftId,
            long expectedRevision,
            SampleInferenceApplyRequest request,
            String actor) {
        AuthoringScope requiredScope = requireScope(scope);
        AuthoringDraft draft = exactDraft(requiredScope, draftId, expectedRevision);
        validateApplyRequest(draft, request);
        SampleInferenceResult inference = inferSamples(draft, request.inference());
        if (!fingerprintsEqual(
                request.evidenceFingerprint(),
                inference.evidenceFingerprint())) {
            throw failure(
                    409,
                    "RG.AUTHORING.INFERENCE_EVIDENCE_STALE",
                    "Samples, inference options, target, or inferencer changed after review.",
                    draft.draftId(),
                    draft.revision(),
                    "/evidenceFingerprint"
            );
        }

        SampleInferenceCandidateApplier.ApplyResult applied;
        try {
            applied = sampleCandidateApplier.apply(
                    inference,
                    request.decisions(),
                    normalized(actor, "visual-library-workbench")
            );
        } catch (SampleInferenceRejectedException exception) {
            throw inferenceFailure(draft, exception);
        }
        VisualLibraryAuthoringDocument updated = applyCandidate(
                draft.document(),
                inference.target(),
                applied
        );
        requireSecretFree(draft.draftId(), draft.revision(), updated);

        EvidenceState promoted = replaceTargetEvidence(
                draft,
                AuthoringEvidence.fromInference(
                        inference,
                        applied.candidate(),
                        applied.portName(),
                        applied.removePort()
                ),
                applied.confirmations()
        );
        AuthoringDraft candidate = AuthoringDraft.unsaved(
                draft.draftId(),
                draft.sourceMode(),
                updated,
                promoted.evidence(),
                promoted.confirmations()
        );
        return drafts.saveIfRevision(
                        requiredScope,
                        draft.revision(),
                        candidate,
                        normalized(actor, "visual-library-workbench"))
                .orElseThrow(() -> stale(draft.draftId(), draft.revision()));
    }

    private SampleInferenceResult inferSamples(AuthoringDraft draft,
                                               SampleInferenceRequest request) {
        if (request != null && request.target() != null
                && !draft.document().operators().containsKey(request.target().assetRef())) {
            throw failure(
                    404,
                    "RG.AUTHORING.INFERENCE_TARGET_NOT_FOUND",
                    "The targeted operator is not present in this exact draft revision.",
                    draft.draftId(),
                    draft.revision(),
                    "/target/assetRef"
            );
        }
        try {
            return sampleInferencer.infer(draft.draftId(), draft.revision(), request);
        } catch (SampleInferenceRejectedException exception) {
            throw inferenceFailure(draft, exception);
        }
    }

    @Transactional
    public synchronized AuthoringCommitResult commit(
            AuthoringScope scope,
            String draftId,
            long expectedRevision,
            CommitRequest request,
            String actorId) {
        AuthoringDraft draft = exactDraft(requireScope(scope), draftId, expectedRevision);
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
        String actor = normalized(actorId, "visual-library-workbench");
        requireCatalogOwnership(
                requireScope(scope),
                library.libraryId(),
                currentTargetRevision,
                actor,
                draft);
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

    private AuthoringDraft exactDraft(
            AuthoringScope scope,
            String draftId,
            long expectedRevision) {
        AuthoringDraft draft = find(scope, draftId);
        if (expectedRevision <= 0 || draft.revision() != expectedRevision) {
            throw stale(draft.draftId(), expectedRevision);
        }
        return draft;
    }

    private AuthoringDraft currentForSave(
            AuthoringScope scope,
            String draftId,
            long expectedRevision) {
        AuthoringDraft current = drafts.find(scope, draftId).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.revision() != expectedRevision)) {
            throw stale(draftId, expectedRevision);
        }
        return current;
    }

    private void validateApplyRequest(AuthoringDraft draft,
                                      SampleInferenceApplyRequest request) {
        if (request == null) {
            throw failure(
                    400,
                    "RG.AUTHORING.INFERENCE_APPLY_REQUEST_REQUIRED",
                    "Sample inference apply request is required.",
                    draft.draftId(),
                    draft.revision(),
                    "/"
            );
        }
        if (!SampleInferenceApplyRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw failure(
                    400,
                    "RG.AUTHORING.INFERENCE_APPLY_SCHEMA_UNSUPPORTED",
                    "schemaVersion must be "
                            + SampleInferenceApplyRequest.SCHEMA_VERSION + ".",
                    draft.draftId(),
                    draft.revision(),
                    "/schemaVersion"
            );
        }
        if (request.inference() == null) {
            throw failure(
                    400,
                    "RG.AUTHORING.INFERENCE_REQUEST_REQUIRED",
                    "The exact inference request is required for server-side replay.",
                    draft.draftId(),
                    draft.revision(),
                    "/inference"
            );
        }
        if (!SHA256.matcher(request.evidenceFingerprint()).matches()) {
            throw failure(
                    400,
                    "RG.AUTHORING.INFERENCE_EVIDENCE_FINGERPRINT_INVALID",
                    "evidenceFingerprint must be a lowercase SHA-256 fingerprint.",
                    draft.draftId(),
                    draft.revision(),
                    "/evidenceFingerprint"
            );
        }
    }

    private EvidenceState retainedEvidence(
            AuthoringDraft current,
            VisualLibraryAuthoringDocument nextDocument) {
        if (current == null || current.evidence().isEmpty()) {
            return EvidenceState.empty();
        }
        List<AuthoringEvidence> evidence = current.evidence().stream()
                .filter(item -> evidenceStillApplies(item, nextDocument))
                .toList();
        Set<String> fingerprints = evidence.stream()
                .map(AuthoringEvidence::evidenceFingerprint)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<AuthoringConfirmation> confirmations = current.confirmations().stream()
                .filter(item -> fingerprints.contains(item.evidenceFingerprint()))
                .toList();
        return new EvidenceState(evidence, confirmations);
    }

    private static boolean evidenceStillApplies(
            AuthoringEvidence evidence,
            VisualLibraryAuthoringDocument document) {
        if (evidence == null || evidence.target() == null) {
            return false;
        }
        Map<String, com.fasterxml.jackson.databind.JsonNode> ports =
                ports(document, evidence.target());
        String base = basePortName(evidence.target().portName());
        if (evidence.targetRemoved()) {
            return !ports.containsKey(base) && !ports.containsKey(base + "?");
        }
        return !evidence.declaredPortName().isBlank()
                && ports.containsKey(evidence.declaredPortName())
                && Objects.equals(
                        ports.get(evidence.declaredPortName()),
                        evidence.declaredCandidate()
                );
    }

    private static EvidenceState replaceTargetEvidence(
            AuthoringDraft draft,
            AuthoringEvidence replacement,
            List<AuthoringConfirmation> replacementConfirmations) {
        String targetPath = replacement.target().authoringPath();
        Set<String> removedFingerprints = draft.evidence().stream()
                .filter(item -> item.target() != null
                        && item.target().authoringPath().equals(targetPath))
                .map(AuthoringEvidence::evidenceFingerprint)
                .collect(java.util.stream.Collectors.toSet());
        List<AuthoringEvidence> evidence = new ArrayList<>();
        draft.evidence().stream()
                .filter(item -> item.target() == null
                        || !item.target().authoringPath().equals(targetPath))
                .forEach(evidence::add);
        evidence.add(replacement);

        List<AuthoringConfirmation> confirmations = new ArrayList<>();
        draft.confirmations().stream()
                .filter(item -> !removedFingerprints.contains(item.evidenceFingerprint()))
                .forEach(confirmations::add);
        confirmations.addAll(replacementConfirmations);
        return new EvidenceState(List.copyOf(evidence), List.copyOf(confirmations));
    }

    private static VisualLibraryAuthoringDocument applyCandidate(
            VisualLibraryAuthoringDocument document,
            SampleInferenceRequest.Target target,
            SampleInferenceCandidateApplier.ApplyResult applied) {
        VisualLibraryAuthoringDocument.OperatorAuthoring operator =
                document.operators().get(target.assetRef());
        Map<String, com.fasterxml.jackson.databind.JsonNode> nextPorts =
                new LinkedHashMap<>(ports(document, target));
        String base = basePortName(target.portName());
        nextPorts.remove(base);
        nextPorts.remove(base + "?");
        if (!applied.removePort()) {
            nextPorts.put(applied.portName(), applied.candidate().deepCopy());
        }
        VisualLibraryAuthoringDocument.OperatorAuthoring nextOperator =
                replacePorts(operator, target.portDirection(), nextPorts);
        Map<String, VisualLibraryAuthoringDocument.OperatorAuthoring> operators =
                new LinkedHashMap<>(document.operators());
        operators.put(target.assetRef(), nextOperator);
        return new VisualLibraryAuthoringDocument(
                document.schemaVersion(),
                document.library(),
                document.defaults(),
                document.types(),
                operators,
                document.functions(),
                document.imports(),
                document.examples()
        );
    }

    private static VisualLibraryAuthoringDocument.OperatorAuthoring replacePorts(
            VisualLibraryAuthoringDocument.OperatorAuthoring operator,
            String direction,
            Map<String, com.fasterxml.jackson.databind.JsonNode> ports) {
        Map<String, com.fasterxml.jackson.databind.JsonNode> input =
                "INPUT".equals(direction) ? ports : operator.input();
        Map<String, com.fasterxml.jackson.databind.JsonNode> output =
                "OUTPUT".equals(direction) ? ports : operator.output();
        return new VisualLibraryAuthoringDocument.OperatorAuthoring(
                operator.name(),
                operator.description(),
                operator.archetype(),
                operator.version(),
                operator.tags(),
                input,
                output,
                operator.config(),
                operator.effect(),
                operator.idempotency(),
                operator.streaming(),
                operator.durable(),
                operator.requiresSecrets(),
                operator.runtime(),
                operator.tests()
        );
    }

    private static Map<String, com.fasterxml.jackson.databind.JsonNode> ports(
            VisualLibraryAuthoringDocument document,
            SampleInferenceRequest.Target target) {
        VisualLibraryAuthoringDocument.OperatorAuthoring operator =
                document.operators().get(target.assetRef());
        if (operator == null) {
            return Map.of();
        }
        return "OUTPUT".equals(target.portDirection())
                ? operator.output()
                : operator.input();
    }

    private void requireSecretFree(
            String draftId,
            long revision,
            VisualLibraryAuthoringDocument document) {
        Map<String, Object> operators = new LinkedHashMap<>();
        document.operators().forEach((operatorRef, operator) -> {
            if (operator != null && operator.runtime() != null
                    && !operator.runtime().isNull()) {
                operators.put(operatorRef, Map.of(
                        "runtime",
                        objectMapper.convertValue(operator.runtime(), Object.class)
                ));
            }
        });
        Map<String, Object> functions = new LinkedHashMap<>();
        document.functions().forEach((functionRef, function) -> {
            if (function != null && !function.examples().isEmpty()) {
                functions.put(functionRef, Map.of("examples", function.examples()));
            }
        });
        Map<String, Object> persistedValues = new LinkedHashMap<>();
        persistedValues.put("operators", operators);
        persistedValues.put("functions", functions);
        persistedValues.put(
                "examples",
                objectMapper.convertValue(document.examples(), Object.class)
        );
        List<com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic> secrets =
                VisualSecretGuard.detectRawSecrets(
                        persistedValues,
                        "/document"
                );
        if (!secrets.isEmpty()) {
            throw failure(
                    400,
                    "RG.AUTHORING.RAW_SECRET_FORBIDDEN",
                    "Raw secret material cannot be stored in a library authoring draft.",
                    draftId,
                    revision,
                    secrets.getFirst().target()
            );
        }
    }

    private static boolean fingerprintsEqual(String submitted, String actual) {
        return MessageDigest.isEqual(
                submitted.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static String basePortName(String value) {
        String normalized = normalized(value, "");
        return normalized.endsWith("?")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static AuthoringLifecycleException inferenceFailure(
            AuthoringDraft draft,
            SampleInferenceRejectedException exception) {
        return failure(
                exception.status(),
                exception.code(),
                exception.getMessage(),
                draft.draftId(),
                draft.revision(),
                exception.authoringPath()
        );
    }

    private long currentTargetRevision(String libraryId) {
        return libraries.revisions(libraryId).stream()
                .mapToLong(OperatorLibraryRevision::revision)
                .max()
                .orElse(0);
    }

    private void requireCatalogOwnership(
            AuthoringScope scope,
            String libraryId,
            long currentTargetRevision,
            String actor,
            AuthoringDraft draft) {
        var ownership = catalogOwnership.find(libraryId);
        if (ownership.isPresent()) {
            if (!ownership.get().scope().equals(scope)) {
                throw failure(
                        409,
                        "RG.AUTHORING.CATALOG_OWNERSHIP_CONFLICT",
                        "The target library id is owned by another enterprise scope.",
                        draft.draftId(),
                        draft.revision(),
                        "/document/library/id");
            }
            return;
        }
        if (currentTargetRevision > 0) {
            throw failure(
                    409,
                    "RG.AUTHORING.LEGACY_CATALOG_OWNERSHIP_REQUIRED",
                    "The existing target library has no enterprise ownership record; "
                            + "complete an explicit ownership migration before committing.",
                    draft.draftId(),
                    draft.revision(),
                    "/document/library/id");
        }
        try {
            catalogOwnership.claim(scope, libraryId, actor, Instant.now());
        } catch (AuthoringCatalogOwnershipConflictException conflict) {
            throw failure(
                    409,
                    "RG.AUTHORING.CATALOG_OWNERSHIP_CONFLICT",
                    "The target library id was concurrently claimed by another enterprise scope.",
                    draft.draftId(),
                    draft.revision(),
                    "/document/library/id");
        }
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

    private static AuthoringScope requireScope(AuthoringScope scope) {
        return Objects.requireNonNull(scope, "scope");
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

    private record EvidenceState(
            List<AuthoringEvidence> evidence,
            List<AuthoringConfirmation> confirmations
    ) {
        private static EvidenceState empty() {
            return new EvidenceState(List.of(), List.of());
        }
    }
}
