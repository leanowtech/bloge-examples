package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.ScenarioOperatorTestSupport;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies bounded 10k Matrix query and conflict-safe atomic bulk editing. */
class ScenarioTableScaleAndConcurrencyTest {

    private static final int MAX_BYTES = 16 * 1_048_576;

    private ObjectMapper mapper;
    private DatabaseScenarioDraftSetRepository repository;
    private ScenarioDraftSetAuthoringService service;
    private ContractDraft contract;
    private GraphDraft graph;
    private ScenarioValidationService validation;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        repository = new DatabaseScenarioDraftSetRepository(jdbc, mapper);
        repository.init();
        InMemoryGraphDraftRepository graphs = new InMemoryGraphDraftRepository();
        graph = graphs.save(ScenarioValidationServiceTest.graphDraft());
        ContractDraftProjectionService contracts = new ContractDraftProjectionService();
        contract = contracts.project(graph, VisualBundleFingerprint.fromCanonicalValue(
                mapper, graph, MAX_BYTES));
        validation = new ScenarioValidationService(mapper);
        service = new ScenarioDraftSetAuthoringService(
                repository,
                graphs,
                ScenarioOperatorTestSupport.catalog(ScenarioOperatorTestSupport.operator()),
                contracts,
                validation,
                mapper);
    }

    @Test
    void queriesTenThousandCasesThroughStableBoundedCursors() {
        StoredScenarioDraftSet stored = repository.saveIfRevision(
                0, draftSet(10_000), "scale-author").orElseThrow();
        ScenarioTablePageQuery firstQuery = query(stored, "", "", 200,
                ScenarioTablePageQuery.SortField.CANONICAL,
                ScenarioTablePageQuery.SortDirection.ASC);

        ScenarioTablePage first = service.queryPage("loan-scenarios", firstQuery, identity());
        ScenarioTablePage second = service.queryPage("loan-scenarios",
                query(stored, "", first.nextCursor(), 200,
                        ScenarioTablePageQuery.SortField.CANONICAL,
                        ScenarioTablePageQuery.SortDirection.ASC), identity());
        ScenarioTablePage descending = service.queryPage("loan-scenarios",
                query(stored, "", "", 25,
                        ScenarioTablePageQuery.SortField.CANONICAL,
                        ScenarioTablePageQuery.SortDirection.DESC), identity());
        ScenarioTablePage filtered = service.queryPage("loan-scenarios",
                query(stored, "Applicant 00042", "", 25,
                        ScenarioTablePageQuery.SortField.NAME,
                        ScenarioTablePageQuery.SortDirection.ASC), identity());
        ScenarioTablePage boundaries = service.queryPage("loan-scenarios",
                new ScenarioTablePageQuery(
                        ScenarioTablePageQuery.SCHEMA_VERSION,
                        stored.revision(), stored.fingerprint(), "",
                        List.of(ScenarioDraftSet.CaseType.BOUNDARY),
                        ScenarioTablePageQuery.SortField.TYPE,
                        ScenarioTablePageQuery.SortDirection.ASC, "", 25), identity());
        ScenarioTablePage literalWildcard = service.queryPage("loan-scenarios",
                query(stored, "%_", "", 25,
                        ScenarioTablePageQuery.SortField.CANONICAL,
                        ScenarioTablePageQuery.SortDirection.ASC), identity());

        assertThat(first.totalMatching()).isEqualTo(10_000);
        assertThat(first.rows()).hasSize(200);
        assertThat(first.rows().getFirst().canonicalIndex()).isZero();
        assertThat(first.rows().getLast().canonicalIndex()).isEqualTo(199);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.rows().getFirst().canonicalIndex()).isEqualTo(200);
        assertThat(second.queryFingerprint()).isEqualTo(first.queryFingerprint());
        assertThat(descending.rows().getFirst().canonicalIndex()).isEqualTo(9_999);
        assertThat(filtered.totalMatching()).isEqualTo(1);
        assertThat(filtered.rows().getFirst().scenario().scenarioId()).isEqualTo("case-00042");
        assertThat(boundaries.totalMatching()).isEqualTo(5_000);
        assertThat(boundaries.rows()).allSatisfy(row ->
                assertThat(row.scenario().caseType()).isEqualTo(ScenarioDraftSet.CaseType.BOUNDARY));
        assertThat(literalWildcard.totalMatching()).isZero();
        assertThat(first.rows()).allSatisfy(row ->
                assertThat(row.caseFingerprint()).matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void enforcesTheTenThousandCaseAuthoringBoundary() {
        assertThat(validation.validate(draftSet(10_000), contract, graph).valid()).isTrue();
        ScenarioValidationReport tooLarge = validation.validate(draftSet(10_001), contract, graph);

        assertThat(tooLarge.valid()).isFalse();
        assertThat(tooLarge.diagnostics()).extracting("code")
                .contains("visual.scenario.scenarios.limitExceeded");
    }

    @Test
    void rejectsCursorReuseAcrossAnotherExactQuery() {
        StoredScenarioDraftSet stored = repository.saveIfRevision(
                0, draftSet(250), "scale-author").orElseThrow();
        ScenarioTablePage first = service.queryPage("loan-scenarios",
                query(stored, "", "", 50,
                        ScenarioTablePageQuery.SortField.CANONICAL,
                        ScenarioTablePageQuery.SortDirection.ASC), identity());

        assertThatThrownBy(() -> service.queryPage("loan-scenarios",
                query(stored, "Applicant", first.nextCursor(), 50,
                        ScenarioTablePageQuery.SortField.NAME,
                        ScenarioTablePageQuery.SortDirection.ASC), identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.TABLE_CURSOR_INVALID"));
    }

    @Test
    void commitsMultipleCellsAtomicallyAndReturnsPayloadFreeReceipt() {
        StoredScenarioDraftSet stored = service.save(
                "loan-scenarios", 0, draftSet(3), identity());
        ScenarioTablePage page = service.queryPage("loan-scenarios",
                query(stored, "", "", 10,
                        ScenarioTablePageQuery.SortField.CANONICAL,
                        ScenarioTablePageQuery.SortDirection.ASC), identity());
        ScenarioBulkEditCommand command = new ScenarioBulkEditCommand(
                ScenarioBulkEditCommand.SCHEMA_VERSION,
                "bulk-001",
                stored.revision(),
                stored.fingerprint(),
                ScenarioBulkEditCommand.Atomicity.ALL_OR_NOTHING,
                List.of(
                        edit(page.rows().get(0), ScenarioBulkEditCommand.Field.NAME,
                                "", ScenarioBulkEditCommand.Operation.SET, "Priority applicant"),
                        edit(page.rows().get(1), ScenarioBulkEditCommand.Field.GIVEN_PATH,
                                "/applicantId", ScenarioBulkEditCommand.Operation.SET, "A-UPDATED"),
                        edit(page.rows().get(2), ScenarioBulkEditCommand.Field.TAGS,
                                "", ScenarioBulkEditCommand.Operation.SET,
                                List.of("loan", "regression"))));

        ScenarioBulkEditResult result = service.bulkEdit("loan-scenarios", command, identity());
        StoredScenarioDraftSet updated = service.find("loan-scenarios", identity());

        assertThat(result.sourceRevision()).isEqualTo(1);
        assertThat(result.storedRevision()).isEqualTo(2);
        assertThat(result.touchedCells()).isEqualTo(3);
        assertThat(result.editedCaseIds())
                .containsExactly("case-00000", "case-00001", "case-00002");
        assertThat(mapper.valueToTree(result).toString())
                .doesNotContain("A-UPDATED", "Priority applicant", "regression");
        assertThat(updated.draftSet().scenarios().get(0).name()).isEqualTo("Priority applicant");
        assertThat(updated.draftSet().scenarios().get(1).given().input())
                .isEqualTo(Map.of("applicantId", "A-UPDATED"));
        assertThat(updated.draftSet().scenarios().get(2).tags())
                .containsExactly("loan", "regression");
    }

    @Test
    void staleBulkEditReturnsFingerprintDiffWithoutPayloads() {
        StoredScenarioDraftSet source = service.save(
                "loan-scenarios", 0, draftSet(2), identity());
        ScenarioTablePage page = service.queryPage("loan-scenarios",
                query(source, "", "", 10,
                        ScenarioTablePageQuery.SortField.CANONICAL,
                        ScenarioTablePageQuery.SortDirection.ASC), identity());
        ScenarioBulkEditCommand first = new ScenarioBulkEditCommand(
                ScenarioBulkEditCommand.SCHEMA_VERSION, "bulk-first",
                source.revision(), source.fingerprint(),
                ScenarioBulkEditCommand.Atomicity.ALL_OR_NOTHING,
                List.of(edit(page.rows().getFirst(), ScenarioBulkEditCommand.Field.NAME,
                        "", ScenarioBulkEditCommand.Operation.SET, "First author")));
        service.bulkEdit("loan-scenarios", first, identity());
        ScenarioBulkEditCommand stale = new ScenarioBulkEditCommand(
                ScenarioBulkEditCommand.SCHEMA_VERSION, "bulk-stale",
                source.revision(), source.fingerprint(),
                ScenarioBulkEditCommand.Atomicity.ALL_OR_NOTHING,
                List.of(
                        edit(page.rows().getFirst(), ScenarioBulkEditCommand.Field.NAME,
                                "", ScenarioBulkEditCommand.Operation.SET, "Second author"),
                        edit(page.rows().get(1), ScenarioBulkEditCommand.Field.NAME,
                                "", ScenarioBulkEditCommand.Operation.SET, "Unchanged row edit")));

        assertThatThrownBy(() -> service.bulkEdit("loan-scenarios", stale, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    var problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.code()).isEqualTo("RG.SCENARIO.BULK_SOURCE_CONFLICT");
                    assertThat(problem.retryable()).isTrue();
                    assertThat(problem.details()).containsEntry("currentRevision", 2L);
                    assertThat(problem.details().get("caseConflicts").toString())
                            .contains("case-00000", "CHANGED", "case-00001", "UNCHANGED")
                            .doesNotContain("First author", "Second author", "Unchanged row edit");
                });
        assertThat(service.find("loan-scenarios", identity()).draftSet()
                .scenarios().getFirst().name()).isEqualTo("First author");
    }

    @Test
    void invalidEditRollsBackTheWholeCommand() {
        StoredScenarioDraftSet stored = service.save(
                "loan-scenarios", 0, draftSet(2), identity());
        ScenarioTablePage page = service.queryPage("loan-scenarios",
                query(stored, "", "", 10,
                        ScenarioTablePageQuery.SortField.CANONICAL,
                        ScenarioTablePageQuery.SortDirection.ASC), identity());
        ScenarioBulkEditCommand command = new ScenarioBulkEditCommand(
                ScenarioBulkEditCommand.SCHEMA_VERSION, "bulk-invalid",
                stored.revision(), stored.fingerprint(),
                ScenarioBulkEditCommand.Atomicity.ALL_OR_NOTHING,
                List.of(
                        edit(page.rows().get(0), ScenarioBulkEditCommand.Field.NAME,
                                "", ScenarioBulkEditCommand.Operation.SET, "Must roll back"),
                        edit(page.rows().get(1), ScenarioBulkEditCommand.Field.GIVEN_PATH,
                                "/applicantId/value", ScenarioBulkEditCommand.Operation.SET, "invalid")));

        assertThatThrownBy(() -> service.bulkEdit("loan-scenarios", command, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.BULK_PATH_INVALID"));
        StoredScenarioDraftSet unchanged = service.find("loan-scenarios", identity());
        assertThat(unchanged.revision()).isEqualTo(1);
        assertThat(unchanged.draftSet().scenarios().getFirst().name())
                .isEqualTo("Applicant 00000");
    }

    private ScenarioDraftSet draftSet(int size) {
        List<ScenarioDraftSet.ScenarioDraft> scenarios = java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> new ScenarioDraftSet.ScenarioDraft(
                        "case-%05d".formatted(index),
                        "Applicant %05d".formatted(index),
                        "Deterministic scale corpus row.",
                        index % 2 == 0
                                ? ScenarioDraftSet.CaseType.GOLDEN
                                : ScenarioDraftSet.CaseType.BOUNDARY,
                        List.of(index % 2 == 0 ? "even" : "odd"),
                        new ScenarioDraftSet.Given(
                                Map.of("applicantId", "A-%05d".formatted(index)),
                                ScenarioDraftSet.ValueProvenance.GENERATED),
                        List.of(),
                        ScenarioDraftSet.Then.empty()))
                .toList();
        return new ScenarioDraftSet(
                "", "loan-scenarios", 0, scope(), contract.target(),
                contract.fingerprint(mapper), scenarios,
                new ScenarioDraftSet.Metadata(
                        "credit-platform", "INTERNAL", null, null,
                        Map.of("corpus", "deterministic-scale-v1")));
    }

    private static ScenarioTablePageQuery query(
            StoredScenarioDraftSet stored,
            String text,
            String cursor,
            int limit,
            ScenarioTablePageQuery.SortField field,
            ScenarioTablePageQuery.SortDirection direction) {
        return new ScenarioTablePageQuery(
                ScenarioTablePageQuery.SCHEMA_VERSION,
                stored.revision(), stored.fingerprint(), text, List.of(),
                field, direction, cursor, limit);
    }

    private static ScenarioBulkEditCommand.CellEdit edit(
            ScenarioTablePage.Row row,
            ScenarioBulkEditCommand.Field field,
            String path,
            ScenarioBulkEditCommand.Operation operation,
            Object value) {
        return new ScenarioBulkEditCommand.CellEdit(
                row.scenario().scenarioId(), row.caseFingerprint(), field, path, operation, value);
    }

    private static ScenarioDraftSet.EnterpriseScope scope() {
        return new ScenarioDraftSet.EnterpriseScope(
                "tenant-a", "org-a", "project-a", "test", "sg");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "author-a", "", "TEST_SUITE_WRITE", "correlation-a",
                java.util.Set.of(), "RESTRICTED", "");
    }
}
