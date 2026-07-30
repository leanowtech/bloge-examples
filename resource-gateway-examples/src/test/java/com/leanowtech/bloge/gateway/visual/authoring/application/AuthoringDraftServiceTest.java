package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringDraftServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ObjectMapper yaml = new YAMLMapper().findAndRegisterModules();
    private final InMemoryAuthoringDraftRepository drafts = new InMemoryAuthoringDraftRepository();
    private final InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
    private AuthoringDraftService service;

    @BeforeEach
    void setUp() {
        AuthoringCompiler compiler = new AuthoringCompiler(mapper, new OperatorLibraryValidator());
        service = new AuthoringDraftService(
                drafts,
                new AuthoringPreviewService(compiler, libraries, mapper),
                libraries,
                mapper
        );
    }

    @Test
    void decoratesPreviewWithExactStoredDraftIdentity() throws Exception {
        AuthoringDraft stored = service.save(
                "support-library",
                0,
                "quick",
                document("support-library", "1.0.0"),
                "alice"
        );

        AuthoringCompileResult preview = service.preview(stored.draftId(), stored.revision());

        assertThat(preview.draftId()).isEqualTo("support-library");
        assertThat(preview.authoringRevision()).isEqualTo(1);
        assertThat(preview.importable()).isTrue();
        assertThat(preview.diff().baseRevision()).isZero();
    }

    @Test
    void commitsOnlyTheExactAuthoritativePreview() throws Exception {
        AuthoringDraft stored = service.save(
                "support-library",
                0,
                "quick",
                document("support-library", "1.0.0"),
                "alice"
        );
        AuthoringCompileResult preview = service.preview(stored.draftId(), stored.revision());

        AuthoringCommitResult committed = service.commit(
                stored.draftId(),
                stored.revision(),
                exactCommit(preview, preview.diff().baseRevision())
        );

        assertThat(committed.targetRevision()).isEqualTo(1);
        assertThat(committed.library()).isEqualTo(preview.canonicalLibrary());
        assertThat(libraries.find("support-library")).contains(preview.canonicalLibrary());
        assertThat(libraries.revisions("support-library")).hasSize(1);
        assertThat(libraries.revisions("support-library").getFirst().revisionMetadata().actor())
                .isEqualTo("alice");
    }

    @Test
    void rejectsStaleDraftAndTamperedCanonicalFingerprint() throws Exception {
        AuthoringDraft first = service.save(
                "support-library",
                0,
                "quick",
                document("support-library", "1.0.0"),
                "alice"
        );
        AuthoringCompileResult preview = service.preview(first.draftId(), first.revision());
        service.save(
                first.draftId(),
                first.revision(),
                "quick",
                document("support-library", "1.0.1"),
                "bob"
        );

        assertThatThrownBy(() -> service.commit(
                first.draftId(),
                first.revision(),
                exactCommit(preview, 0)
        )).isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                assertThat(exception.problem().code()).isEqualTo("RG.AUTHORING.DRAFT_REVISION_STALE"));

        AuthoringDraft independent = service.save(
                "other-library",
                0,
                "quick",
                document("other-library", "1.0.0"),
                "alice"
        );
        AuthoringCompileResult independentPreview =
                service.preview(independent.draftId(), independent.revision());
        AuthoringDraftService.CommitRequest tampered = new AuthoringDraftService.CommitRequest(
                independentPreview.authoringFingerprint(),
                independentPreview.compileFingerprint(),
                independentPreview.catalogFingerprint(),
                "sha256:tampered",
                0,
                "alice",
                "test"
        );

        assertThatThrownBy(() -> service.commit(
                independent.draftId(),
                independent.revision(),
                tampered
        )).isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                assertThat(exception.problem().code()).isEqualTo("RG.AUTHORING.CANONICAL_DRIFT"));
    }

    @Test
    void rejectsCatalogDriftBetweenPreviewAndCommit() throws Exception {
        AuthoringDraft stored = service.save(
                "support-library",
                0,
                "quick",
                document("support-library", "1.0.0"),
                "alice"
        );
        AuthoringCompileResult preview = service.preview(stored.draftId(), stored.revision());
        AuthoringCompileResult other = new AuthoringCompiler(mapper, new OperatorLibraryValidator())
                .compile(document("catalog-change", "1.0.0"));
        libraries.upsert(other.canonicalLibrary());

        assertThatThrownBy(() -> service.commit(
                stored.draftId(),
                stored.revision(),
                exactCommit(preview, 0)
        )).isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                assertThat(exception.problem().code()).isEqualTo("RG.AUTHORING.CATALOG_DRIFT"));
    }

    @Test
    void refusesRawSecretsBeforePersistence() throws Exception {
        VisualLibraryAuthoringDocument secret = yaml.readValue("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: unsafe-library, owner: support-team}
                operators:
                  support:unsafe:
                    input: {value: string}
                    output: {value: string}
                    runtime:
                      password: clear-text-secret
                """, VisualLibraryAuthoringDocument.class);

        assertThatThrownBy(() -> service.save("unsafe-library", 0, "quick", secret, "alice"))
                .isInstanceOfSatisfying(AuthoringLifecycleException.class, exception ->
                        assertThat(exception.problem().code())
                                .isEqualTo("RG.AUTHORING.RAW_SECRET_FORBIDDEN"));
        assertThat(drafts.all()).isEmpty();
    }

    private AuthoringDraftService.CommitRequest exactCommit(AuthoringCompileResult preview,
                                                            long targetRevision) {
        return new AuthoringDraftService.CommitRequest(
                preview.authoringFingerprint(),
                preview.compileFingerprint(),
                preview.catalogFingerprint(),
                preview.canonicalFingerprint(),
                targetRevision,
                "alice",
                "Reviewed in workbench"
        );
    }

    private VisualLibraryAuthoringDocument document(String libraryId, String version) throws Exception {
        return yaml.readValue("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library:
                  id: %s
                  version: %s
                  owner: support-team
                operators:
                  %s:echo:
                    input: {value: string}
                    output: {value: string}
                """.formatted(libraryId, version, libraryId), VisualLibraryAuthoringDocument.class);
    }

    private static final class InMemoryAuthoringDraftRepository implements AuthoringDraftRepository {
        private final Map<String, AuthoringDraft> current = new LinkedHashMap<>();
        private final Map<String, List<AuthoringDraft>> history = new LinkedHashMap<>();

        @Override
        public Collection<AuthoringDraft> all() {
            return List.copyOf(current.values());
        }

        @Override
        public Optional<AuthoringDraft> find(String draftId) {
            return Optional.ofNullable(current.get(draftId));
        }

        @Override
        public List<AuthoringDraft> revisions(String draftId) {
            return history.getOrDefault(draftId, List.of()).reversed();
        }

        @Override
        public synchronized Optional<AuthoringDraft> saveIfRevision(long expectedRevision,
                                                                   AuthoringDraft candidate,
                                                                   String actor) {
            AuthoringDraft existing = current.get(candidate.draftId());
            if ((existing == null && expectedRevision != 0)
                    || (existing != null && existing.revision() != expectedRevision)) {
                return Optional.empty();
            }
            java.time.Instant now = java.time.Instant.now();
            AuthoringDraft stored = candidate.withStorageIdentity(
                    candidate.draftId(),
                    expectedRevision + 1,
                    "sha256:draft-" + (expectedRevision + 1),
                    existing == null ? now : existing.createdAt(),
                    now,
                    actor
            );
            current.put(stored.draftId(), stored);
            List<AuthoringDraft> revisions =
                    new java.util.ArrayList<>(history.getOrDefault(stored.draftId(), List.of()));
            revisions.add(stored);
            history.put(stored.draftId(), List.copyOf(revisions));
            return Optional.of(stored);
        }
    }
}
