package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseAuthoringDraftRepositoryTest {

    private final ObjectMapper yaml = new YAMLMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private DatabaseAuthoringDraftRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        repository = new DatabaseAuthoringDraftRepository(
                new org.springframework.jdbc.core.JdbcTemplate(database),
                new ObjectMapper().findAndRegisterModules()
        );
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void storesExactRevisionsAndRejectsStaleWriters() throws Exception {
        AuthoringDraft first = repository.saveIfRevision(
                0,
                AuthoringDraft.unsaved("support-library", "quick", document("support-library", "1.0.0")),
                "alice"
        ).orElseThrow();

        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.fingerprint()).startsWith("sha256:");
        assertThat(first.createdAt()).isEqualTo(first.updatedAt());
        assertThat(repository.saveIfRevision(
                0,
                AuthoringDraft.unsaved("support-library", "quick", document("support-library", "1.0.1")),
                "bob"
        )).isEmpty();

        AuthoringDraft second = repository.saveIfRevision(
                1,
                AuthoringDraft.unsaved("support-library", "quick", document("support-library", "1.0.1")),
                "alice"
        ).orElseThrow();

        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.updatedAt()).isAfterOrEqualTo(first.updatedAt());
        assertThat(repository.find("support-library")).hasValueSatisfying(found -> {
            assertThat(found.draftId()).isEqualTo(second.draftId());
            assertThat(found.revision()).isEqualTo(second.revision());
            assertThat(found.fingerprint()).isEqualTo(second.fingerprint());
            assertThat(found.document().library().version()).isEqualTo("1.0.1");
        });
        assertThat(repository.all())
                .extracting(AuthoringDraft::draftId, AuthoringDraft::revision)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("support-library", 2L));
        assertThat(repository.revisions("support-library"))
                .extracting(AuthoringDraft::revision)
                .containsExactly(2L, 1L);
    }

    private VisualLibraryAuthoringDocument document(String libraryId, String version) throws Exception {
        return yaml.readValue("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library:
                  id: %s
                  version: %s
                  owner: support-team
                operators:
                  support:echo:
                    input: {value: string}
                    output: {value: string}
                """.formatted(libraryId, version), VisualLibraryAuthoringDocument.class);
    }
}
