package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable polling and anti-entropy surface for Tool Studio synchronization. */
@Service
public class IntegrationChangeFeedService {
    private static final int MAX_PAGE_SIZE = 500;

    private final IntegrationChangeEventOutbox outbox;
    private final IntegrationEventCursorCodec cursorCodec;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OperatorLibraryRegistry operatorLibraries;
    private final VisualOperatorContractTestSuiteRepository testSuites;

    public IntegrationChangeFeedService(IntegrationChangeEventOutbox outbox,
                                        ObjectMapper objectMapper,
                                        VisualEvidenceSigner signer,
                                        JdbcTemplate jdbc,
                                        OperatorLibraryRegistry operatorLibraries,
                                        VisualOperatorContractTestSuiteRepository testSuites) {
        this.outbox = outbox;
        this.cursorCodec = new IntegrationEventCursorCodec(objectMapper, signer);
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.operatorLibraries = operatorLibraries;
        this.testSuites = testSuites;
    }

    public IntegrationEnvelope<IntegrationChangeFeed> events(String token,
                                                              int limit,
                                                              IntegrationRequestContext context) {
        requireChangeSync(context);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.EVENT_LIMIT_INVALID",
                    "Event page size must be between 1 and " + MAX_PAGE_SIZE + ".",
                    context.correlationId(), Map.of("limit", limit, "maximum", MAX_PAGE_SIZE)
            ));
        }
        IntegrationEventCursorCodec.CursorPayload window;
        if (token == null || token.isBlank()) {
            window = cursorCodec.issue(context.tenantId(), context.environmentId(), 0,
                    outbox.highWaterSequence());
        } else {
            window = cursorCodec.decode(token, context);
            if (window.afterSequence() >= window.throughSequence()) {
                long latest = outbox.highWaterSequence();
                if (latest > window.throughSequence()) {
                    window = cursorCodec.advance(window, window.afterSequence(), latest);
                }
            }
        }

        List<IntegrationChangeEvent> events = outbox.read(window.afterSequence(), window.throughSequence(),
                context.tenantId(), context.environmentId(), limit);
        long lastRead = events.isEmpty()
                ? window.afterSequence()
                : events.get(events.size() - 1).streamSequence();
        boolean hasMore = outbox.hasAfter(lastRead, window.throughSequence(), context.tenantId(),
                context.environmentId());
        long nextAfter = hasMore ? lastRead : window.throughSequence();
        IntegrationEventCursorCodec.CursorPayload next = cursorCodec.advance(window, nextAfter,
                window.throughSequence());
        IntegrationEventCursorCodec.CursorPayload checkpoint = cursorCodec.advance(window,
                window.throughSequence(), window.throughSequence());
        IntegrationChangeFeed feed = new IntegrationChangeFeed("", events, cursorCodec.encode(next),
                cursorCodec.encode(checkpoint), hasMore, events.size());
        return IntegrationEnvelope.of("INTEGRATION_CHANGE_FEED", IntegrationChangeFeed.SCHEMA_VERSION, feed);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IntegrationEnvelope<IntegrationReconciliationSnapshot> reconciliation(
            IntegrationRequestContext context) {
        requireChangeSync(context);

        // Reading the outbox first establishes the database snapshot boundary for all following asset reads.
        long highWater = outbox.highWaterSequence();
        List<IntegrationAssetSnapshot> assets = new ArrayList<>();
        Map<String, GraphDraft> scopedDrafts = new LinkedHashMap<>();

        jdbc.query("SELECT draft_json FROM visual_graph_drafts", rs -> {
            GraphDraft draft = read(rs.getString("draft_json"), GraphDraft.class, "graph draft");
            if (context.tenantId().equals(draft.tenantId())
                    && context.environmentId().equals(draft.environment())) {
                scopedDrafts.put(draft.draftId(), draft);
                String draftFingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                        "draft", draft.withNodeFixtures(Map.of())));
                assets.add(new IntegrationAssetSnapshot("GRAPH_DRAFT", draft.draftId(), draft.revision(),
                        draftFingerprint, draft.status(),
                        "/api/integration/drafts/" + draft.draftId() + "/export?revision=" + draft.revision()));
                String contractFingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                        "inputSchema", draft.inputSchema(), "outputSchema", draft.outputSchema()));
                assets.add(new IntegrationAssetSnapshot("GRAPH_CONTRACT", draft.draftId(), draft.revision(),
                        contractFingerprint, "ACTIVE",
                        "/api/integration/drafts/" + draft.draftId() + "/export?revision=" + draft.revision()));
            }
        });

        jdbc.query("""
                SELECT library_id, library_json,
                       (SELECT COALESCE(MAX(revision), 0)
                        FROM visual_operator_library_revisions r
                        WHERE r.library_id = l.library_id) AS revision
                FROM visual_operator_libraries l
                """, rs -> {
            OperatorLibrary library = read(rs.getString("library_json"), OperatorLibrary.class,
                    "operator library");
            long revision = rs.getLong("revision");
            assets.add(new IntegrationAssetSnapshot("OPERATOR_LIBRARY", library.libraryId(), revision,
                    VisualBundleFingerprint.fromMaterial(Map.of("operatorLibrary", library)), library.status(),
                    "/api/integration/operator-libraries/" + library.libraryId() + "?revision=" + revision));
        });

        jdbc.query("SELECT revision, suite_json FROM visual_operator_contract_test_suites", rs -> {
            VisualOperatorContractTestSuite suite = read(rs.getString("suite_json"),
                    VisualOperatorContractTestSuite.class, "operator contract-test suite");
            long revision = rs.getLong("revision");
            assets.add(new IntegrationAssetSnapshot("CONTRACT_TEST_SUITE", suite.suiteId(), revision,
                    VisualBundleFingerprint.fromMaterial(Map.of("contractTestSuite", suite)), "ACTIVE",
                    "/api/integration/operator-test-suites/" + suite.suiteId() + "?revision=" + revision));
        });

        jdbc.query("SELECT run_json FROM visual_graph_run_records", rs -> {
            VisualGraphRunRecord run = read(rs.getString("run_json"), VisualGraphRunRecord.class, "graph run");
            if (context.tenantId().equals(run.tenantId())
                    && context.environmentId().equals(run.environment())) {
                assets.add(new IntegrationAssetSnapshot("RUN", run.runId(), 1,
                        run.evidenceMaterialFingerprint(), run.success() ? "SUCCESS" : "FAILED",
                        "/api/integration/runs/" + run.runId() + "/evidence"));
            }
        });

        jdbc.query("SELECT result_json FROM governance_gate_results", rs -> {
            GovernanceGateResult result = read(rs.getString("result_json"), GovernanceGateResult.class,
                    "governance gate result");
            if (scopedDrafts.containsKey(result.target().draftId())) {
                assets.add(new IntegrationAssetSnapshot("GOVERNANCE_GATE_RESULT", result.gateResultId(),
                        result.target().revision(), result.resultFingerprint(), result.status(),
                        "/api/integration/drafts/" + result.target().draftId() + "/gate-result"));
            }
        });

        assets.sort(Comparator.comparing(IntegrationAssetSnapshot::kind)
                .thenComparing(IntegrationAssetSnapshot::id)
                .thenComparingLong(IntegrationAssetSnapshot::revision));
        Map<String, Integer> counts = new LinkedHashMap<>();
        assets.forEach(asset -> counts.merge(asset.kind(), 1, Integer::sum));
        String rollingFingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "tenantId", context.tenantId(),
                "environmentId", context.environmentId(),
                "assets", assets));
        IntegrationEventCursorCodec.CursorPayload checkpoint = cursorCodec.issue(context.tenantId(),
                context.environmentId(), highWater, highWater);
        IntegrationReconciliationSnapshot snapshot = new IntegrationReconciliationSnapshot("",
                context.tenantId(), context.environmentId(), Instant.now(), cursorCodec.encode(checkpoint), assets,
                counts, rollingFingerprint);
        return IntegrationEnvelope.of("INTEGRATION_RECONCILIATION_SNAPSHOT",
                IntegrationReconciliationSnapshot.SCHEMA_VERSION, snapshot);
    }

    public IntegrationEnvelope<OperatorLibrary> operatorLibrary(String libraryId,
                                                                 long revision,
                                                                 IntegrationRequestContext context) {
        requireChangeSync(context);
        OperatorLibrary library = revision > 0
                ? operatorLibraries.findRevision(libraryId, revision).map(value -> value.library()).orElse(null)
                : operatorLibraries.find(libraryId).orElse(null);
        if (library == null) {
            throw notFound("RG.INTEGRATION.OPERATOR_LIBRARY_NOT_FOUND", "Operator library", context);
        }
        return IntegrationEnvelope.of("OPERATOR_LIBRARY", library.schemaVersion(), library);
    }

    public IntegrationEnvelope<VisualOperatorContractTestSuite> testSuite(String suiteId,
                                                                           long revision,
                                                                           IntegrationRequestContext context) {
        requireChangeSync(context);
        VisualOperatorContractTestSuite suite = revision > 0
                ? testSuites.findRevision(suiteId, revision).orElse(null)
                : testSuites.find(suiteId).orElse(null);
        if (suite == null) {
            throw notFound("RG.INTEGRATION.CONTRACT_TEST_SUITE_NOT_FOUND", "Contract test suite", context);
        }
        return IntegrationEnvelope.of("CONTRACT_TEST_SUITE", suite.schemaVersion(), suite);
    }

    private <T> T read(String json, Class<T> type, String kind) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to build reconciliation snapshot from corrupt " + kind,
                    exception);
        }
    }

    private static void requireChangeSync(IntegrationRequestContext context) {
        context.requireComplete();
        if (!"CHANGE_SYNC".equals(context.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.PURPOSE_NOT_ALLOWED",
                    "Integration purpose is not allowed for this operation.",
                    context.correlationId(), Map.of("requiredPurpose", "CHANGE_SYNC")
            ));
        }
    }

    private static IntegrationProblemException notFound(String code,
                                                        String kind,
                                                        IntegrationRequestContext context) {
        return new IntegrationProblemException(IntegrationProblem.notFound(code,
                kind + " was not found in the integration authority.", context.correlationId(), Map.of()));
    }
}
