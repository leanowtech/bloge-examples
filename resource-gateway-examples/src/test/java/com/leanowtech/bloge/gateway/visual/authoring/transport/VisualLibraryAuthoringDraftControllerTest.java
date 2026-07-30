package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftRepository;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualLibraryAuthoringDraftControllerTest {

    @Test
    void requiresAndReturnsNumericEtags() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        AuthoringDraftService service = new AuthoringDraftService(
                new SingleDraftRepository(),
                new AuthoringPreviewService(
                        new AuthoringCompiler(mapper, new OperatorLibraryValidator()),
                        libraries,
                        mapper
                ),
                libraries,
                mapper
        );
        VisualLibraryAuthoringDraftController controller =
                new VisualLibraryAuthoringDraftController(service);
        VisualLibraryAuthoringDocument document = new YAMLMapper().findAndRegisterModules()
                .readValue("""
                        schemaVersion: bloge.visualLibraryAuthoring.v1
                        library: {id: support-library, owner: support-team}
                        operators:
                          support:echo:
                            input: {value: string}
                            output: {value: string}
                        """, VisualLibraryAuthoringDocument.class);

        assertThatThrownBy(() -> controller.save(
                "support-library",
                null,
                new VisualLibraryAuthoringDraftController.DraftSaveRequest("QUICK", document, "alice")
        )).isInstanceOfSatisfying(AuthoringLifecycleException.class, exception -> {
            assertThat(exception.problem().status()).isEqualTo(428);
            assertThat(exception.problem().code()).isEqualTo("RG.AUTHORING.IF_MATCH_REQUIRED");
        });

        var created = controller.save(
                "support-library",
                "\"0\"",
                new VisualLibraryAuthoringDraftController.DraftSaveRequest("QUICK", document, "alice")
        );
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getETag()).isEqualTo("\"1\"");

        var preview = controller.preview("support-library", "W/\"1\"");
        assertThat(preview.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(preview.getBody()).satisfies(result -> {
            assertThat(result.draftId()).isEqualTo("support-library");
            assertThat(result.authoringRevision()).isEqualTo(1);
        });
    }

    private static final class SingleDraftRepository implements AuthoringDraftRepository {
        private AuthoringDraft current;

        @Override
        public Collection<AuthoringDraft> all() {
            return current == null ? List.of() : List.of(current);
        }

        @Override
        public Optional<AuthoringDraft> find(String draftId) {
            return Optional.ofNullable(current).filter(draft -> draft.draftId().equals(draftId));
        }

        @Override
        public List<AuthoringDraft> revisions(String draftId) {
            return find(draftId).stream().toList();
        }

        @Override
        public Optional<AuthoringDraft> saveIfRevision(long expectedRevision,
                                                      AuthoringDraft candidate,
                                                      String actor) {
            long currentRevision = current == null ? 0 : current.revision();
            if (expectedRevision != currentRevision) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            current = candidate.withStorageIdentity(
                    candidate.draftId(),
                    expectedRevision + 1,
                    "sha256:test",
                    current == null ? now : current.createdAt(),
                    now,
                    actor
            );
            return Optional.of(current);
        }
    }
}
