package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.integration.FailingIntegrationChangeEventOutbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for H2-backed visual operator library persistence.
 */
class DatabaseOperatorLibraryRegistryTest {

    private DatabaseOperatorLibraryRegistry registry;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        registry = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        registry.init();
    }

    @Test
    void upsertThenFind() {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");

        registry.upsert(library);

        assertThat(registry.find("risk-policy")).contains(library);
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void assetAndChangeEventRollBackTogetherWhenOutboxAppendFails() {
        DatabaseOperatorLibraryRegistry failing = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper,
                new FailingIntegrationChangeEventOutbox());
        failing.init();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                ignored -> failing.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated outbox failure");

        assertThat(failing.find("risk-policy")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_operator_libraries", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_operator_library_revisions", Long.class))
                .isZero();
    }

    @Test
    void persistenceSurvivesReInit() {
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-policy")).isPresent();
        assertThat(reloaded.operators(false))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:eligibility");
    }

    @Test
    void initBackfillsRevisionForLegacyCurrentLibraryWithoutHistory() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        jdbc.update("""
                        MERGE INTO visual_operator_libraries (library_id, library_json)
                        KEY (library_id)
                        VALUES (?, ?)
                        """,
                library.libraryId(),
                objectMapper.writeValueAsString(library));

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-policy")).contains(library);
        assertThat(reloaded.revisions("risk-policy"))
                .extracting(OperatorLibraryRevision::revision, OperatorLibraryRevision::action)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, OperatorLibraryRevision.ACTION_CREATE));
    }

    @Test
    void revisionsPersistCreateReplaceAndDeleteSnapshots() {
        OperatorLibrary created = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replaced = libraryWithVersion(created, "1.1.0");

        registry.upsert(created);
        registry.upsert(replaced);
        registry.delete("risk-policy");

        assertThat(registry.find("risk-policy")).isEmpty();
        assertThat(registry.revisions("risk-policy"))
                .extracting(OperatorLibraryRevision::revision, OperatorLibraryRevision::action)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, OperatorLibraryRevision.ACTION_DELETE),
                        org.assertj.core.groups.Tuple.tuple(2L, OperatorLibraryRevision.ACTION_REPLACE),
                        org.assertj.core.groups.Tuple.tuple(1L, OperatorLibraryRevision.ACTION_CREATE)
                );
        assertThat(registry.findRevision("risk-policy", 2))
                .map(revision -> revision.library().version())
                .contains("1.1.0");

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-policy")).isEmpty();
        assertThat(reloaded.revisions("risk-policy"))
                .extracting(OperatorLibraryRevision::revision, OperatorLibraryRevision::action)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, OperatorLibraryRevision.ACTION_DELETE),
                        org.assertj.core.groups.Tuple.tuple(2L, OperatorLibraryRevision.ACTION_REPLACE),
                        org.assertj.core.groups.Tuple.tuple(1L, OperatorLibraryRevision.ACTION_CREATE)
                );
    }

    @Test
    void revisionMetadataPersistsAcrossRegistryActions() {
        OperatorLibrary created = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replaced = libraryWithVersion(created, "1.1.0");

        registry.upsert(created, OperatorLibraryRevision.RevisionMetadata.of(
                "alice", "catalog-admin", "Imported initial risk policy schema.", "initial onboarding"));
        registry.upsert(replaced, OperatorLibraryRevision.RevisionMetadata.of(
                "bob", "catalog-admin", "Added compatible risk policy schema fields.", "model update"));
        registry.delete("risk-policy", OperatorLibraryRevision.RevisionMetadata.of(
                "carol", "catalog-admin", "Deleted stale risk policy library.", "tenant cleanup"));

        assertThat(registry.revisions("risk-policy"))
                .extracting(revision -> revision.revisionMetadata().actor(),
                        revision -> revision.revisionMetadata().changeSource(),
                        revision -> revision.revisionMetadata().changeSummary(),
                        revision -> revision.revisionMetadata().reason())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("carol", "catalog-admin",
                                "Deleted stale risk policy library.", "tenant cleanup"),
                        org.assertj.core.groups.Tuple.tuple("bob", "catalog-admin",
                                "Added compatible risk policy schema fields.", "model update"),
                        org.assertj.core.groups.Tuple.tuple("alice", "catalog-admin",
                                "Imported initial risk policy schema.", "initial onboarding")
                );

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.revisions("risk-policy"))
                .extracting(revision -> revision.revisionMetadata().actor(),
                        revision -> revision.revisionMetadata().changeSource(),
                        revision -> revision.revisionMetadata().changeSummary())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("carol", "catalog-admin",
                                "Deleted stale risk policy library."),
                        org.assertj.core.groups.Tuple.tuple("bob", "catalog-admin",
                                "Added compatible risk policy schema fields."),
                        org.assertj.core.groups.Tuple.tuple("alice", "catalog-admin",
                                "Imported initial risk policy schema.")
                );
    }

    @Test
    void restorePersistsNewRevisionWithSourcePointer() {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithVersion(
                VisualCatalogTestSupport.eligibilityLibrary("string"), "2.0.0");

        registry.upsert(original);
        registry.upsert(replacement);
        registry.restore(registry.findRevision("risk-policy", 1).orElseThrow());

        assertThat(registry.find("risk-policy"))
                .map(OperatorLibrary::version)
                .contains("1.0.0");
        assertThat(registry.revisions("risk-policy"))
                .extracting(OperatorLibraryRevision::revision,
                        OperatorLibraryRevision::action,
                        OperatorLibraryRevision::restoredFromRevision)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, OperatorLibraryRevision.ACTION_RESTORE, 1L),
                        org.assertj.core.groups.Tuple.tuple(2L, OperatorLibraryRevision.ACTION_REPLACE, null),
                        org.assertj.core.groups.Tuple.tuple(1L, OperatorLibraryRevision.ACTION_CREATE, null)
                );

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-policy"))
                .map(OperatorLibrary::version)
                .contains("1.0.0");
        assertThat(reloaded.revisions("risk-policy"))
                .extracting(OperatorLibraryRevision::revision,
                        OperatorLibraryRevision::action,
                        OperatorLibraryRevision::restoredFromRevision)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, OperatorLibraryRevision.ACTION_RESTORE, 1L),
                        org.assertj.core.groups.Tuple.tuple(2L, OperatorLibraryRevision.ACTION_REPLACE, null),
                        org.assertj.core.groups.Tuple.tuple(1L, OperatorLibraryRevision.ACTION_CREATE, null)
                );
    }

    @Test
    void operatorsSkipNullEntriesFromPersistedLibrary() {
        registry.upsert(libraryWithNullEntry("risk-policy", VisualCatalogTestSupport.eligibilityOperator("integer")));

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.operators(false))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:eligibility");
    }

    @Test
    void operatorsSkipEntriesWithNullPortsFromPersistedLibrary() {
        registry.upsert(libraryWithNullPortEntry());

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.operators(false))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("risk:malformedPorts");
    }

    @Test
    void duplicateOperatorRefAcrossLibrariesIsRejected() {
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));
        OperatorLibrary duplicate = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy-copy",
                "Copy",
                "1.0.0",
                "",
                "ACTIVE",
                java.util.List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        assertThatThrownBy(() -> registry.upsert(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already provided");
    }

    @Test
    void incompatibleCallableNameAcrossLibrariesIsRejected() {
        registry.upsert(functionLibrary("risk-functions", function("risk.normalize", "risk", "integer")));
        OperatorLibrary incompatible = functionLibrary(
                "shared-functions",
                function("risk.normalize", "shared", "string")
        );

        assertThatThrownBy(() -> registry.upsert(incompatible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risk.normalize")
                .hasMessageContaining("risk-functions");
        assertThat(registry.find("shared-functions")).isEmpty();
    }

    @Test
    void incompatibleCallableNameFromSystemDefaultsIsRejected() {
        OperatorLibrary incompatible = functionLibrary(
                "custom-coalesce",
                function("coalesce", "custom", "string")
        );

        assertThatThrownBy(() -> registry.upsert(incompatible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coalesce")
                .hasMessageContaining("builtin");
        assertThat(registry.find("custom-coalesce")).isEmpty();
    }

    @Test
    void compatibleCallableNameAcrossLibrariesSurvivesReload() {
        registry.upsert(functionLibrary("risk-functions", function("risk.normalize", "risk", "integer")));
        registry.upsert(functionLibrary("shared-functions", function("risk.normalize", "shared", "integer")));

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.all())
                .extracting(OperatorLibrary::libraryId)
                .containsExactlyInAnyOrder("risk-functions", "shared-functions");
    }

    @Test
    void duplicateCheckIgnoresNullOperatorEntries() {
        registry.upsert(libraryWithNullEntry("risk-policy", VisualCatalogTestSupport.eligibilityOperator("integer")));
        OperatorLibrary numeric = libraryWithNullEntry("numeric-policy",
                VisualCatalogTestSupport.numericPassOperator());

        registry.upsert(numeric);

        assertThat(registry.operators(false))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:numericPass", "risk:eligibility");
    }

    @Test
    void deleteRemovesLibrary() {
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));

        registry.delete("risk-policy");

        assertThat(registry.find("risk-policy")).isEmpty();
    }

    private static OperatorLibrary libraryWithNullEntry(String libraryId, OperatorDefinition operator) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                libraryId,
                "1.0.0",
                "risk-team",
                "ACTIVE",
                java.util.Arrays.asList(null, operator)
        );
    }

    private static OperatorLibrary libraryWithVersion(OperatorLibrary library, String version) {
        return new OperatorLibrary(
                library.schemaVersion(),
                library.libraryId(),
                library.displayName(),
                version,
                library.owner(),
                library.status(),
                library.operators()
        );
    }

    private static OperatorLibrary libraryWithNullPortEntry() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition malformed = new OperatorDefinition(
                base.schemaVersion(),
                "risk:malformedPorts",
                base.operatorVersion(),
                base.display(),
                base.source(),
                new OperatorDefinition.Ports(java.util.Arrays.asList(null, base.ports().inputs().getFirst()),
                        base.ports().outputs()),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "malformed-ports",
                "Malformed ports",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                java.util.List.of(malformed)
        );
    }

    private static OperatorLibrary functionLibrary(String libraryId,
                                                    OperatorLibrary.BuiltInFunction function) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                libraryId,
                "1.0.0",
                "risk-team",
                "ACTIVE",
                java.util.List.of(function),
                java.util.List.of()
        );
    }

    private static OperatorLibrary.BuiltInFunction function(String name,
                                                            String namespace,
                                                            String returnType) {
        return new OperatorLibrary.BuiltInFunction(
                name,
                namespace,
                name,
                "",
                "risk",
                java.util.List.of(new OperatorLibrary.Signature(
                        name + "(value)",
                        "",
                        java.util.List.of(new OperatorLibrary.Parameter(
                                "value", "any", null, false, false, "")),
                        new OperatorLibrary.ReturnValue(returnType, null, "")
                )),
                java.util.List.of()
        );
    }
}
