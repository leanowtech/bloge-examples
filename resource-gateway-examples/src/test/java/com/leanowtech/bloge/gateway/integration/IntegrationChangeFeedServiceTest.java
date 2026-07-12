package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.catalog.DatabaseOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.draft.DatabaseGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.testing.DatabaseVisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationChangeFeedServiceTest {
    private DatabaseIntegrationChangeEventOutbox outbox;
    private DatabaseGraphDraftRepository drafts;
    private DatabaseOperatorLibraryRegistry libraries;
    private DatabaseVisualOperatorContractTestSuiteRepository suites;
    private IntegrationChangeFeedService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        VisualEvidenceSigner signer = new DatabaseVisualEvidenceSigner(jdbc);

        outbox = new DatabaseIntegrationChangeEventOutbox(jdbc, objectMapper);
        outbox.init();
        drafts = new DatabaseGraphDraftRepository(jdbc, objectMapper, outbox);
        libraries = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper, outbox);
        suites = new DatabaseVisualOperatorContractTestSuiteRepository(jdbc, objectMapper, outbox);
        DatabaseVisualGraphRunRepository runs = new DatabaseVisualGraphRunRepository(jdbc, objectMapper, signer,
                outbox);
        DatabaseGovernanceGateResultRepository gates = new DatabaseGovernanceGateResultRepository(jdbc,
                objectMapper);
        ReflectionTestUtils.invokeMethod(drafts, "init");
        ReflectionTestUtils.invokeMethod(libraries, "init");
        ReflectionTestUtils.invokeMethod(suites, "init");
        ReflectionTestUtils.invokeMethod(runs, "init");
        gates.init();
        service = new IntegrationChangeFeedService(outbox, objectMapper, signer, jdbc, libraries, suites);
    }

    @Test
    void paginatesOneBoundedWindowWithoutDuplicatesAndPicksUpLateEventsFromCheckpoint() {
        outbox.append(event("GRAPH_DRAFT_CREATED", "tenant-a", "prod", "draft-a", 1));
        outbox.append(event("GRAPH_DRAFT_CREATED", "tenant-b", "prod", "draft-b", 1));
        outbox.append(event("OPERATOR_LIBRARY_CREATED", "*", "*", "shared-risk", 1));
        outbox.append(event("GRAPH_DRAFT_UPDATED", "tenant-a", "prod", "draft-a", 2));

        IntegrationChangeFeed first = service.events("", 1, context("tenant-a", "prod")).payload();
        outbox.append(event("GRAPH_DRAFT_UPDATED", "tenant-a", "prod", "draft-a", 3));

        IntegrationChangeFeed second = service.events(first.nextCursor(), 1, context("tenant-a", "prod")).payload();
        IntegrationChangeFeed repeatedSecond = service.events(first.nextCursor(), 1,
                context("tenant-a", "prod")).payload();
        IntegrationChangeFeed third = service.events(second.nextCursor(), 1, context("tenant-a", "prod")).payload();

        assertThat(second).isEqualTo(repeatedSecond);
        assertThat(List.of(first, second, third).stream()
                .flatMap(page -> page.events().stream())
                .map(event -> event.aggregate().sequence()))
                .containsExactly(1L, 1L, 2L);
        assertThat(third.hasMore()).isFalse();

        IntegrationChangeFeed late = service.events(third.checkpointCursor(), 10,
                context("tenant-a", "prod")).payload();
        assertThat(late.events()).singleElement()
                .satisfies(event -> assertThat(event.aggregate().sequence()).isEqualTo(3));
        assertThat(late.hasMore()).isFalse();
    }

    @Test
    void reconciliationIsScopeSafeAndItsCheckpointClosesTheEventSnapshotBoundary() {
        GraphDraft tenantDraft = drafts.save(draft("draft-a", "tenant-a", "prod"));
        drafts.save(draft("draft-b", "tenant-b", "prod"));
        libraries.upsert(new OperatorLibrary("", "shared-risk", "Shared Risk", "1.0.0", "platform",
                "ACTIVE", List.of(), List.of()));
        suites.save(new VisualOperatorContractTestSuite("suite-risk", "Risk suite", "", List.of("regression"),
                new VisualOperatorContractTestSuiteRequest("risk:score", List.of())));

        IntegrationReconciliationSnapshot first = service.reconciliation(context("tenant-a", "prod")).payload();
        IntegrationReconciliationSnapshot repeated = service.reconciliation(context("tenant-a", "prod")).payload();

        assertThat(first.assets()).extracting(IntegrationAssetSnapshot::kind)
                .containsExactly("CONTRACT_TEST_SUITE", "GRAPH_CONTRACT", "GRAPH_DRAFT", "OPERATOR_LIBRARY");
        assertThat(first.assets()).extracting(IntegrationAssetSnapshot::id)
                .contains("draft-a", "shared-risk", "suite-risk")
                .doesNotContain("draft-b");
        assertThat(first.rollingFingerprint()).isEqualTo(repeated.rollingFingerprint());
        assertThat(service.events(first.checkpointCursor(), 20, context("tenant-a", "prod")).payload().events())
                .isEmpty();

        GraphDraft updated = drafts.save(tenantDraft);
        IntegrationChangeFeed afterSnapshot = service.events(first.checkpointCursor(), 20,
                context("tenant-a", "prod")).payload();

        assertThat(updated.revision()).isEqualTo(2);
        assertThat(afterSnapshot.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("GRAPH_DRAFT_UPDATED");
                    assertThat(event.aggregate().sequence()).isEqualTo(2);
                });
    }

    @Test
    void immutablePayloadReferencesResolveExactLibraryAndSuiteRevisions() {
        OperatorLibrary v1 = libraries.upsert(new OperatorLibrary("", "shared-risk", "Shared Risk", "1.0.0",
                "platform", "ACTIVE", List.of(), List.of()));
        libraries.upsert(new OperatorLibrary("", "shared-risk", "Shared Risk", "2.0.0",
                "platform", "ACTIVE", List.of(), List.of()));
        VisualOperatorContractTestSuite suiteV1 = suites.save(new VisualOperatorContractTestSuite(
                "suite-risk", "Risk suite v1", "", List.of(),
                new VisualOperatorContractTestSuiteRequest("risk:score", List.of())));
        suites.save(new VisualOperatorContractTestSuite("suite-risk", "Risk suite v2", "", List.of(),
                new VisualOperatorContractTestSuiteRequest("risk:score", List.of())));

        assertThat(service.operatorLibrary("shared-risk", 1, context("tenant-a", "prod")).payload())
                .isEqualTo(v1);
        assertThat(service.testSuite("suite-risk", 1, context("tenant-a", "prod")).payload())
                .isEqualTo(suiteV1);
    }

    @Test
    void rejectsWrongPurposeAndUnboundedPageSizes() {
        IntegrationRequestContext wrongPurpose = new IntegrationRequestContext("tenant-a", "org", "project",
                "prod", "region", "WORKLOAD", "actor", "", "PAYLOAD_REPLAY", "corr-purpose");

        assertThatThrownBy(() -> service.events("", 10, wrongPurpose))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.INTEGRATION.PURPOSE_NOT_ALLOWED"));
        assertThatThrownBy(() -> service.events("", 501, context("tenant-a", "prod")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.INTEGRATION.EVENT_LIMIT_INVALID"));
    }

    private static GraphDraft draft(String id, String tenant, String environment) {
        return new GraphDraft("", id, 0, "knowledgePolicy", tenant, "knowledge", environment, "", null,
                List.of(), List.of(), Map.of(), new GraphDraft.OutputSelection("", ""));
    }

    private static IntegrationChangeEvent event(String type,
                                                String tenant,
                                                String environment,
                                                String id,
                                                long sequence) {
        String kind = type.startsWith("OPERATOR") ? "OPERATOR_LIBRARY" : "GRAPH_DRAFT";
        return IntegrationChangeEvent.pending(type, tenant, "knowledge", environment,
                new IntegrationChangeEvent.Aggregate(kind, id, sequence, "sha256:" + id + sequence),
                "/api/integration/assets/" + id, "trace-1");
    }

    private static IntegrationRequestContext context(String tenant, String environment) {
        return new IntegrationRequestContext(tenant, "org", "project", environment, "region", "WORKLOAD",
                "aneke-sync", "", "CHANGE_SYNC", "corr-sync");
    }
}
