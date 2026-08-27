package com.leanowtech.bloge.gateway.visual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioContractProjection;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSetAuthoringService;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioImportMaterializationRequest;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioImportMaterializationResult;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioImportMaterializationService;
import com.leanowtech.bloge.gateway.authoring.scenario.StoredScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.workspace.WorkspaceForkCommand;
import com.leanowtech.bloge.gateway.authoring.workspace.WorkspaceForkReceipt;
import com.leanowtech.bloge.gateway.authoring.workspace.WorkspaceForkService;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractBootstrap;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ElementNotInteractableException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real browser regression coverage for the visual composer.
 */
@SpringBootTest(
        classes = {
                ResourceGatewayApplication.class,
                VisualAuthoringBrowserDomTest
                        .RehearsalBrowserFixtureConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "spring.datasource.url=jdbc:h2:mem:visual-authoring-browser-dom;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
@Timeout(90)
class VisualAuthoringBrowserDomTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration WEBDRIVER_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final Path MAC_CHROME_BINARY = Path.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    );
    private static final Path SELENIUM_CHROMEDRIVER_CACHE = Path.of(
            System.getProperty("user.home"), ".cache", "selenium", "chromedriver"
    );
    private static final String REHEARSAL_BROWSER_JOB_ID =
            "scenario-batch-" + "b".repeat(64);
    private static final String REHEARSAL_BROWSER_FINGERPRINT =
            "sha256:" + "a".repeat(64);

    @TestConfiguration(proxyBeanMethods = false)
    static class RehearsalBrowserFixtureConfiguration {
        /** Supplies a deterministic signed-workbook projection only to this real-browser test. */
        @Bean
        RehearsalBrowserFixtureController
        rehearsalBrowserFixtureController() {
            return new RehearsalBrowserFixtureController();
        }

        /** Supplies an exact read-only Operator Contract without enabling mutable profile routes. */
        @Bean
        OperatorScenarioBrowserFixtureController
        operatorScenarioBrowserFixtureController() {
            return new OperatorScenarioBrowserFixtureController();
        }

        /** Exposes the real materializer under a deterministic browser-test identity. */
        @Bean
        ScenarioImportBrowserFixtureController scenarioImportBrowserFixtureController(
                ScenarioImportMaterializationService service) {
            return new ScenarioImportBrowserFixtureController(service);
        }

        /** Exposes the authoritative saved-Graph Contract projection in the default test profile. */
        @Bean
        GraphScenarioBrowserFixtureController graphScenarioBrowserFixtureController(
                ScenarioDraftSetAuthoringService service,
                GraphDraftRepository graphDrafts) {
            return new GraphScenarioBrowserFixtureController(service, graphDrafts);
        }

        /** Exposes the real aggregate fork in the default-profile packaged-browser harness. */
        @Bean
        WorkspaceForkBrowserFixtureController workspaceForkBrowserFixtureController(
                WorkspaceForkService service) {
            return new WorkspaceForkBrowserFixtureController(service);
        }

        /** Exposes exact Scenario reads for assets created by the aggregate fork. */
        @Bean
        ScenarioDraftSetBrowserFixtureController scenarioDraftSetBrowserFixtureController(
                ScenarioDraftSetAuthoringService service) {
            return new ScenarioDraftSetBrowserFixtureController(service);
        }
    }

    /** Test-only transport adapter around the same atomic Workspace fork service used in test. */
    @RestController
    @RequestMapping("/api/authoring/workspace-forks")
    static final class WorkspaceForkBrowserFixtureController {
        private final WorkspaceForkService service;

        WorkspaceForkBrowserFixtureController(WorkspaceForkService service) {
            this.service = service;
        }

        @PostMapping
        WorkspaceForkReceipt fork(
                @RequestHeader("Idempotency-Key") String idempotencyKey,
                @RequestBody WorkspaceForkCommand command,
                @RequestHeader HttpHeaders ignoredHeaders) {
            return service.fork(
                    idempotencyKey,
                    command,
                    browserAuthoringIdentity("TEST_SUITE_WRITE", "browser-workspace-fork"));
        }
    }

    /** Test-only exact-scope read adapter for the Scenario asset returned by a Workspace fork. */
    @RestController
    @RequestMapping("/api/visual/scenario-draft-sets")
    static final class ScenarioDraftSetBrowserFixtureController {
        private final ScenarioDraftSetAuthoringService service;

        ScenarioDraftSetBrowserFixtureController(ScenarioDraftSetAuthoringService service) {
            this.service = service;
        }

        @GetMapping("/{scenarioDraftSetId}")
        StoredScenarioDraftSet find(@PathVariable String scenarioDraftSetId) {
            return service.find(
                    scenarioDraftSetId,
                    browserAuthoringIdentity("TEST_SUITE_READ", "browser-scenario-read"));
        }
    }

    private static IntegrationRequestContext browserAuthoringIdentity(
            String purpose,
            String requestId) {
        return new IntegrationRequestContext(
                "tenant-a", "browser-organization", "browser-project", "test", "local",
                "HUMAN", "browser-author", "", purpose, requestId,
                java.util.Set.of(), "RESTRICTED", "");
    }

    /** Test-profile adapter around the real saved Graph Contract projection service. */
    @RestController
    @RequestMapping("/api/visual/scenario-draft-sets/targets/graphs")
    static final class GraphScenarioBrowserFixtureController {
        private final ScenarioDraftSetAuthoringService service;
        private final GraphDraftRepository graphDrafts;

        GraphScenarioBrowserFixtureController(
                ScenarioDraftSetAuthoringService service,
                GraphDraftRepository graphDrafts) {
            this.service = service;
            this.graphDrafts = graphDrafts;
        }

        /** Projects the exact retained revision without introducing a second Contract algorithm. */
        @GetMapping("/{draftId}/contract")
        ScenarioContractProjection contract(@PathVariable String draftId) {
            var graph = graphDrafts.find(draftId).orElseThrow();
            IntegrationRequestContext identity = new IntegrationRequestContext(
                    graph.tenantId(), "browser-organization", "browser-project",
                    graph.environment(), "local",
                    "HUMAN", "browser-author", "", "TEST_SUITE_READ",
                    "browser-graph-contract", java.util.Set.of(), "RESTRICTED", "");
            return service.projectGraphContract(draftId, identity);
        }
    }

    /** Test-only transport adapter; authorization itself remains covered by controller tests. */
    @RestController
    @RequestMapping("/api/visual/scenario-imports")
    static final class ScenarioImportBrowserFixtureController {
        private final ScenarioImportMaterializationService service;

        ScenarioImportBrowserFixtureController(ScenarioImportMaterializationService service) {
            this.service = service;
        }

        /** Materializes with a trusted identity derived from the exact requested enterprise scope. */
        @PostMapping("/materialize")
        ResponseEntity<?> materialize(
                @RequestBody ScenarioImportMaterializationRequest request) {
            ScenarioDraftSet.EnterpriseScope scope = request.draftSet().scope();
            IntegrationRequestContext identity = new IntegrationRequestContext(
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(),
                    "HUMAN", "browser-author", "", "TEST_SUITE_WRITE",
                    "browser-scenario-import", java.util.Set.of(), "RESTRICTED", "");
            try {
                return ResponseEntity.ok(service.materialize(request, identity));
            } catch (IntegrationProblemException failure) {
                return ResponseEntity.status(failure.problem().status()).body(failure.problem());
            }
        }
    }

    /**
     * Test-only Mirror read surface used to exercise the packaged React workbench in real Chrome.
     *
     * <p>Mirror mutation routes are deliberately absent: the browser must render the reviewed workflow
     * in its fail-closed identity state without acquiring a test-only authorization bypass.</p>
     */
    @RestController
    @RequestMapping("/api/mirror")
    static final class RehearsalBrowserFixtureController {
        /** Returns one exact-scope blocked terminal batch. */
        @GetMapping("/rehearsal-jobs")
        Map<String, Object> jobs() {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put(
                    "schemaVersion",
                    "resourceGateway.scenarioRehearsalBatchJob.v2");
            job.put("jobId", REHEARSAL_BROWSER_JOB_ID);
            job.put("requestId", "browser-rehearsal-request");
            job.put(
                    "requestFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            job.put(
                    "manifestFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            job.put("scope", scope());
            job.put("status", "PARTIAL");
            job.put("failureMode", "CONTINUE");
            job.put("priority", "NORMAL");
            job.put("maximumItemAttempts", 2);
            job.put("summary", summary());
            job.put("deadlineAt", "2026-07-26T11:00:00Z");
            job.put("failureCode", "");
            job.put("createdAt", "2026-07-26T10:00:00Z");
            job.put("updatedAt", "2026-07-26T10:02:00Z");
            job.put("completedAt", "2026-07-26T10:02:00Z");
            job.put(
                    "recordFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            Map<String, Object> page =
                    new LinkedHashMap<>();
            page.put(
                    "schemaVersion",
                    "resourceGateway.scenarioRehearsalBatchJobPage.v1");
            page.put("scope", scope());
            page.put("jobs", List.of(job));
            page.put("nextCursor", null);
            return envelope(
                    "SCENARIO_REHEARSAL_BATCH_JOB_PAGE",
                    "resourceGateway.scenarioRehearsalBatchJobPage.v1",
                    page);
        }

        /** Returns one blocked root-sealed workbook for the fixture batch. */
        @GetMapping(
                "/rehearsal-jobs/{jobId}/workbook-seed")
        Map<String, Object> workbook(
                @PathVariable String jobId) {
            assertThat(jobId)
                    .as("browser fixture job")
                    .isEqualTo(REHEARSAL_BROWSER_JOB_ID);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entryIndex", 0);
            entry.put("entryId", "entry-timeout");
            entry.put(
                    "compiledPlanRef",
                    artifact(
                            "COMPILED_REHEARSAL_PLAN",
                            "customer-profile"));
            entry.put("childRequestId", "browser-child-request");
            entry.put(
                    "expectedRunId",
                    "scenario-run-" + "c".repeat(64));
            entry.put("status", "FAILED");
            entry.put("attemptCount", 2);
            entry.put(
                    "runId",
                    "scenario-run-" + "c".repeat(64));
            entry.put(
                    "childEvidenceBundleFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            entry.put("childWorkbookSeedFingerprint", "");
            entry.put("failureCode", "TARGET_TIMEOUT");
            entry.put("childWorkbook", null);

            Map<String, Object> workbook =
                    new LinkedHashMap<>();
            workbook.put(
                    "schemaVersion",
                    "resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1");
            workbook.put(
                    "seedFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            workbook.put("scope", scope());
            workbook.put("jobId", REHEARSAL_BROWSER_JOB_ID);
            workbook.put(
                    "requestId",
                    "browser-rehearsal-request");
            workbook.put(
                    "requestFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            workbook.put(
                    "manifestFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            workbook.put(
                    "terminalJobFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            workbook.put(
                    "evidenceBundleFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            workbook.put(
                    "evidenceIndexFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            workbook.put("evidenceKeyId", "browser-key");
            workbook.put(
                    "workbookSeal",
                    Map.of(
                            "keyId", "browser-key",
                            "algorithm", "Ed25519",
                            "materialFingerprint",
                            REHEARSAL_BROWSER_FINGERPRINT,
                            "signature", "base64:browser-signature"));
            workbook.put(
                    "retentionProof",
                    Map.of(
                            "eventFingerprint",
                            REHEARSAL_BROWSER_FINGERPRINT,
                            "retainUntil",
                            "2033-07-26T10:00:00Z"));
            workbook.put("status", "PARTIAL");
            workbook.put("summary", summary());
            workbook.put("entries", List.of(entry));
            workbook.put("gateReady", false);
            workbook.put(
                    "blockers",
                    List.of("BATCH_ITEM_EXECUTION_FAILED"));
            return envelope(
                    "SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED",
                    "resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1",
                    workbook);
        }

        private static Map<String, Object> envelope(
                String kind,
                String version,
                Object payload) {
            Map<String, Object> envelope =
                    new LinkedHashMap<>();
            envelope.put(
                    "protocol",
                    "ToolStudioResourceGatewayProtocol");
            envelope.put("protocolVersion", "1.0.0");
            envelope.put("resourceGatewayVersion", "1.0.0");
            envelope.put(
                    "schemaVersion",
                    "toolStudio.integrationEnvelope.v1");
            envelope.put(
                    "producedAt",
                    "2026-07-26T10:03:00Z");
            envelope.put("payloadKind", kind);
            envelope.put("payloadSchemaVersion", version);
            envelope.put(
                    "payloadFingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
            envelope.put("payload", payload);
            return envelope;
        }

        private static Map<String, Object> scope() {
            return Map.of(
                    "tenantId", "tenant-a",
                    "organizationId", "knowledge-governance",
                    "projectId", "tool-studio",
                    "environmentId", "test",
                    "region", "sg");
        }

        private static Map<String, Object> summary() {
            return Map.of(
                    "totalItems", 1,
                    "completedItems", 1,
                    "passedItems", 0,
                    "failedItems", 1,
                    "indeterminateItems", 0,
                    "cancelledItems", 0);
        }

        private static Map<String, Object> artifact(
                String kind,
                String id) {
            return Map.of(
                    "kind", kind,
                    "id", id,
                    "revision", 1,
                    "fingerprint",
                    REHEARSAL_BROWSER_FINGERPRINT);
        }
    }

    /** Test-only Contract projection; Scenario reads deliberately remain real 404 responses. */
    @RestController
    @RequestMapping("/api/visual/scenario-draft-sets/targets/operators")
    static final class OperatorScenarioBrowserFixtureController {
        private static final String TARGET_FINGERPRINT =
                "sha256:" + "d".repeat(64);
        private static final String CONTRACT_FINGERPRINT =
                "sha256:" + "e".repeat(64);

        /** Returns a complete schema-backed Contract for the selected catalog Operator. */
        @GetMapping("/{operatorRef}/contract")
        ScenarioContractProjection contract(@PathVariable String operatorRef) {
            SchemaEnvelope input = SchemaEnvelope.object(
                    Map.of("params", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "userId", Map.of("type", "string", "examples", List.of("user-1001"))
                            ),
                            "required", List.of("userId"),
                            "additionalProperties", false
                    )),
                    List.of("params")
            );
            SchemaEnvelope output = SchemaEnvelope.object(
                    Map.of("payload", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "displayName", Map.of(
                                            "type", "string",
                                            "examples", List.of("Ada Lovelace")
                                    ),
                                    "tier", Map.of(
                                            "type", "string",
                                            "examples", List.of("gold")
                                    )
                            ),
                            "required", List.of("displayName", "tier"),
                            "additionalProperties", false
                    )),
                    List.of("payload")
            );
            ContractDraft contract = new ContractDraft(
                    ContractDraft.SCHEMA_VERSION,
                    new ContractDraft.Target(
                            ContractDraft.TargetKind.OPERATOR,
                            operatorRef,
                            0,
                            TARGET_FINGERPRINT
                    ),
                    input,
                    output,
                    List.of(),
                    new ContractDraft.ExecutionSemantics(
                            ContractDraft.Effect.READ,
                            "NOT_APPLICABLE",
                            false,
                            false,
                            null
                    ),
                    List.of(),
                    ContractDraft.CompatibilityPolicy.strict(),
                    Map.of(),
                    ContractDraft.Source.INFERRED,
                    ContractDraft.Confidence.EXACT
            );
            return new ScenarioContractProjection(
                    ScenarioContractProjection.SCHEMA_VERSION,
                    new ScenarioDraftSet.EnterpriseScope(
                            "tenant-a",
                            "knowledge-governance",
                            "tool-studio",
                            "test",
                            "local"
                    ),
                    contract,
                    CONTRACT_FINGERPRINT
            );
        }
    }

    @Autowired
    private WritableResourceRegistry resourceRegistry;

    @Autowired
    private OperatorLibraryRegistry operatorLibraryRegistry;

    @Autowired
    private GraphDraftRepository graphDraftRepository;

    @Autowired
    private ResourceDesignContractRegistry resourceDesignContractRegistry;

    @LocalServerPort
    private int port;

    private static String chromeWebDriverUnavailableReason;

    private WebDriver driver;
    private ChromeDriverService driverService;
    private ScopedProcessTree driverProcessTree;

    @BeforeEach
    void seedDemoDescriptorsForRandomPort() {
        graphDraftRepository.all().stream()
                .map(draft -> draft.draftId())
                .toList()
                .forEach(graphDraftRepository::delete);
        operatorLibraryRegistry.all().stream()
                .map(OperatorLibrary::libraryId)
                .toList()
                .forEach(operatorLibraryRegistry::delete);
        resourceRegistry.all().stream()
                .map(descriptor -> descriptor.resourceId())
                .toList()
                .forEach(resourceRegistry::deregister);
        resourceDesignContractRegistry.all().stream()
                .map(contract -> contract.resourceId())
                .toList()
                .forEach(resourceDesignContractRegistry::deleteByResourceId);

        GatewayProperties properties = new GatewayProperties();
        properties.setBaseUrl("http://localhost:" + port + "/demo-upstream");
        properties.setSeedDescriptors(true);
        new ResourceDescriptorBootstrap(resourceRegistry, properties).seedDescriptors();
        new ResourceDesignContractBootstrap(resourceDesignContractRegistry).seedContracts();
    }

    @AfterEach
    void closeBrowser() {
        WebDriver browser = driver;
        ChromeDriverService service = driverService;
        ScopedProcessTree processTree = driverProcessTree;
        driver = null;
        driverService = null;
        driverProcessTree = null;
        if (browser != null) {
            BoundedBrowserSessionCloser.close(
                    WEBDRIVER_TIMEOUT,
                    () -> {
                        browser.quit();
                        if (processTree != null) {
                            processTree.verifyTerminated();
                        }
                    },
                    processTree != null
                            ? processTree::terminate
                            : service == null ? () -> { } : service::stop);
        }
    }

    /**
     * Exercises the dense legacy authoring surface through its explicit rollback coordinate.
     *
     * <p>The default route is intentionally reserved for the task-oriented v2 acceptance test.
     * Keeping this older interaction inventory on {@code authorWorkspace=legacy} prevents a
     * default-shell migration from silently deleting coverage for legacy focus mode, drag/drop,
     * validation, navigation, and export behavior.</p>
     */
    @Test
    void reactAuthorCanvasLoadsPackagedBundleInRealBrowser() {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=legacy");

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".workspace")));
        assertThat(driver.getTitle()).contains("BLOGE Visual Canvas");
        waitForText(wait, By.cssSelector(".topbar"), "Author");
        waitForText(wait, By.id("operator-palette"), "OPERATORS");
        waitForText(wait, By.cssSelector("[data-testid='canvas-coach']"), "Add first operator");
        WebElement initialFlow = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='author-flow']")
        ));
        double standardFlowHeight = elementClientHeight(initialFlow);
        assertThat(standardFlowHeight)
                .as("standard author canvas flow height")
                .isGreaterThanOrEqualTo(560.0);

        WebElement focusToggle = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-testid='canvas-focus-toggle']")
        ));
        focusToggle.click();
        wait.until(ExpectedConditions.attributeToBe(By.cssSelector(".workspace"), "data-layout-mode", "focus"));
        assertThat(driver.findElement(By.id("operator-palette")).isDisplayed()).isFalse();
        double focusedFlowHeight = elementClientHeight(initialFlow);
        assertThat(focusedFlowHeight)
                .as("focused author canvas flow height")
                .isGreaterThanOrEqualTo(760.0)
                .isGreaterThan(standardFlowHeight + 100.0);
        assertNoHorizontalOverflow(wait, By.cssSelector(".workspace"));

        focusToggle.click();
        wait.until(ExpectedConditions.attributeToBe(By.cssSelector(".workspace"), "data-layout-mode", "standard"));

        WebElement firstOperator = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "[data-testid^='operator-button:']"
        )));

        String operatorRef = firstOperator.getAttribute("data-operator-ref");
        firstOperator.click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-testid^='canvas-node:']")));
        waitForText(wait, By.cssSelector(".toolbar"), "1 nodes");
        waitForText(wait, By.cssSelector(".toolbar"), "Output n1");
        waitForText(wait, By.cssSelector("[data-testid='canvas-navigator']"), "MAP");
        waitForText(wait, By.cssSelector("[data-testid='canvas-navigator']"), "1 nodes");
        waitForText(wait, By.cssSelector("[data-testid='canvas-zoom-readout']"), "%");
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("[data-testid='author-fit-all']"))).click();
        waitForText(wait, By.cssSelector("[data-testid^='canvas-node:']"), operatorRef);
        WebElement exportLink = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-testid='author-draft-export']")
        ));
        assertThat(exportLink.getAttribute("download")).isEqualTo("visualGraph-draft.json");
        String exportedDraftJson = URLDecoder.decode(
                exportLink.getAttribute("href").replace("data:application/json;charset=utf-8,", ""),
                StandardCharsets.UTF_8);
        assertThat(exportedDraftJson)
                .contains("\"schemaVersion\": \"bloge.visualGraphDraft.v1\"")
                .contains("\"operatorRef\": \"" + operatorRef + "\"")
                .contains("\"nodeId\": \"n1\"");
        driver.findElement(By.cssSelector("[data-testid='author-draft-validate']")).click();
        waitForText(wait, By.cssSelector("[data-testid='draft-validation-summary:state']"), "READINESS");
        waitForText(wait, By.cssSelector("[data-testid='draft-validation-summary:diagnostics']"), "DIAGNOSTICS");

        WebElement flow = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='author-flow']")
        ));
        WebElement draggableOperator = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid^='operator-button:']"
        )));
        dragReactOperatorToAuthorFlow(draggableOperator, flow, 420, 260);

        waitForText(wait, By.cssSelector(".toolbar"), "2 nodes");
        waitForText(wait, By.cssSelector(".toolbar"), "Output n2");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-testid='canvas-node:n2']")));
        waitForText(wait, By.cssSelector("[data-testid='canvas-node:n2']"), operatorRef);
        assertNoHorizontalOverflow(wait, By.cssSelector(".workspace"));
    }

    /**
     * Bounded 1.3.0 browser acceptance seam for the packaged application routes.
     *
     * <p>The server currently redirects {@code /} to {@code /capabilities/}; this deliberately
     * records that production boundary instead of claiming the root-only Launcher is reachable.
     * The real author bundle is then exercised with a spine-off deep link at both supported
     * viewport classes, including the horizontal-overflow gate.</p>
     */
    @Test
    void onePointThreeAcceptanceRecordsRootRedirectAndResponsiveAuthorBoundary() {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);

        driver.get("http://localhost:" + port + "/?spine=v1");
        wait.until(ignored -> driver.getCurrentUrl().contains("/capabilities/"));
        assertThat(driver.findElements(By.cssSelector("[data-testid='tool-spine-launcher']"))).isEmpty();

        driver.get("http://localhost:" + port
                + "/author/?authorWorkspace=v2&spine=off&toolId=browser-tool"
                + "&toolName=Browser%20Tool&stage=define");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".workspace")));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace"), "data-author-workspace-version", "v2"));
        assertThat(driver.findElements(By.cssSelector("[data-testid='tool-spine-launcher']"))).isEmpty();
        assertThat(driver.getCurrentUrl()).contains("spine=off").contains("toolId=browser-tool");

        setViewport(wait, 390, 980);
        assertPageNoHorizontalOverflow();
        setViewport(wait, 1280, 980);
        assertPageNoHorizontalOverflow();
    }

    /**
     * Proves task-oriented Author is the default, the named legacy coordinate remains a URL-only
     * rollback, and the desktop operator dialog keeps both actions on the title row. The latter
     * checks a shared vertical center line and horizontal clearance rather than equal top
     * coordinates because the kind badge intentionally has two text rows. The historical
     * three-column grid silently wrapped the close action.
     */
    @Test
    void authorWorkspaceVersionAndOperatorDialogHeadingRemainRollbackSafeInRealBrowser() {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/author/");

        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace"),
                "data-author-workspace-version",
                "v2"
        ));
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[aria-label='Close start dialog']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid^='operator-button:']"
        ))).click();
        WebElement operatorNode = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='canvas-node:n1']")
        ));
        dispatchDoubleClick(operatorNode);

        WebElement heading = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".operator-detail-heading")
        ));
        @SuppressWarnings("unchecked")
        List<Map<String, Number>> childBounds = (List<Map<String, Number>>)
                ((JavascriptExecutor) driver).executeScript("""
                        return [...arguments[0].children].map((child) => {
                          const rect = child.getBoundingClientRect();
                          return {
                            left: rect.left,
                            right: rect.right,
                            top: rect.top,
                            bottom: rect.bottom
                          };
                        });
                        """, heading);
        assertThat(childBounds).hasSize(4);
        double firstCenter = (
                childBounds.getFirst().get("top").doubleValue()
                        + childBounds.getFirst().get("bottom").doubleValue()
        ) / 2.0;
        assertThat(childBounds)
                .extracting(bounds -> (
                        bounds.get("top").doubleValue() + bounds.get("bottom").doubleValue()
                ) / 2.0)
                .allSatisfy(center -> assertThat(center)
                        .as("operator dialog heading child vertical center")
                        .isCloseTo(firstCenter,
                                org.assertj.core.data.Offset.offset(2.0)));
        for (int index = 1; index < childBounds.size(); index++) {
            assertThat(childBounds.get(index).get("left").doubleValue())
                    .as("operator dialog heading child %s follows its predecessor", index)
                    .isGreaterThanOrEqualTo(childBounds.get(index - 1).get("right").doubleValue());
        }

        driver.get("http://localhost:" + port + "/author/?authorWorkspace=legacy");
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace"),
                "data-author-workspace-version",
                "v1"
        ));
        assertThat(wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".topbar-link.active")
        )).getText()).isEqualTo("Legacy");

        driver.get("http://localhost:" + port + "/author/?authorWorkspace=experimental");
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace"),
                "data-author-workspace-version",
                "v1"
        ));
    }

    @Test
    void operatorScenarioLoadKeepsCompleteGeneratedExampleWhenNoRevisionExistsInRealBrowser() {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/author/");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[aria-label='Close start dialog']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='operator-button:resource:user-service.getProfile']"
        ))).click();
        WebElement operatorNode = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='canvas-node:n1']")
        ));
        dispatchDoubleClick(operatorNode);
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//button[normalize-space()='Contract & Scenarios']"
        ))).click();

        WebElement workspace = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='contract-workspace']")
        ));
        assertThat(workspace.getText())
                .doesNotContain("Request failed: 404")
                .doesNotContain("RG.SCENARIO.NOT_FOUND");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-mode:scenarios']"
        ))).click();
        waitForText(wait, By.cssSelector(".scenario-list"), "User profile executable case");
        waitForText(wait, By.cssSelector(".scenario-workbench"), "Given");
        wait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                By.cssSelector(".scenario-assertions > *"),
                0
        ));
    }

    /**
     * Protects the shared table-driven Matrix in packaged Chrome, including its exact-selection
     * run path and responsive behavior. Wide tables must scroll inside their own surface instead
     * of widening the page; light editing remains available through the Case view on mobile.
     */
    @Test
    void scenarioMatrixSelectsRunsAndRemainsUsableAcrossViewportsInRealBrowser() throws IOException {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        setViewport(wait, 1280, 800);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=v2");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-choice:examples']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-example:loan-policy-fallback']"
        ))).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-mode:scenarios']"
        ))).click();

        WebElement matrix = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='scenario-matrix']")
        ));
        assertThat(matrix.getText())
                .contains("Case")
                .contains("Given")
                .contains("Dependencies")
                .contains("Assertions")
                .contains("FRESHNESS");
        assertThat(driver.findElements(By.xpath(
                "//*[@data-testid='scenario-matrix']//tbody//td[normalize-space()='Passed']"
        ))).as("row verdicts never collapse four-axis proof into generic Passed").isEmpty();
        List<WebElement> rows = driver.findElements(By.cssSelector(
                "[data-testid^='scenario-matrix-row-']"
        ));
        assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
        assertNoHorizontalOverflow(wait, By.cssSelector(
                "[data-testid='contract-workspace']"
        ));
        assertPageNoHorizontalOverflow();
        captureVisualQa("scenario-matrix-1280.png");

        List<WebElement> rowSelectors = driver.findElements(By.cssSelector(
                "[data-testid^='scenario-matrix-row-'] .scenario-matrix-select input"
        ));
        assertThat(rowSelectors).hasSizeGreaterThanOrEqualTo(2);
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", rowSelectors.getFirst());
        wait.until(ExpectedConditions.elementSelectionStateToBe(
                By.cssSelector("[data-testid^='scenario-matrix-row-'] .scenario-matrix-select input"),
                true
        ));
        try {
            waitForText(wait, By.cssSelector(".scenario-matrix-bulkbar"), "1 selected");
        } catch (AssertionError ex) {
            throw new AssertionError(
                    "Matrix disappeared after selecting its first row. url='%s', body='%s'"
                            .formatted(driver.getCurrentUrl(), textOf(By.tagName("body"))),
                    ex);
        }
        WebElement secondRowSelector = driver.findElements(By.cssSelector(
                "[data-testid^='scenario-matrix-row-'] .scenario-matrix-select input"
        )).get(1);
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", secondRowSelector);
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "[data-testid^='scenario-matrix-row-'] .scenario-matrix-select input:checked"
        )).size() == 2);
        waitForText(wait, By.cssSelector(".scenario-matrix-bulkbar"), "2 selected");
        driver.findElement(By.cssSelector("[data-testid='scenario-run-selected']")).click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.cssSelector(".scenario-matrix-bulkbar"),
                "2 selected"
        ));
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "[data-testid^='scenario-matrix-row-'][data-verdict='warning']"
        )).size() >= 2);
        assertThat(textOf(By.cssSelector("[data-testid='scenario-matrix']")))
                .as("successful mock execution remains explicitly unproven until coverage is evaluated")
                .contains("Coverage not evaluated");
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='author-mode:scenarios']"
        )).getAttribute("aria-pressed"))
                .as("batch execution remains in Matrix instead of opening single-case Evidence")
                .isEqualTo("true");
        assertThat(driver.findElement(By.cssSelector(".workspace-v2"))
                .getAttribute("class"))
                .as("successful batch execution does not strand an empty Diagnostics drawer open")
                .doesNotContain("diagnostics-open");
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid^='scenario-matrix-row-'][data-verdict='neutral']"
        ))).hasSize(rows.size() - 2);

        setViewport(wait, 1024, 768);
        assertPageNoHorizontalOverflow();
        assertNoHorizontalOverflow(wait, By.cssSelector(
                "[data-testid='contract-workspace']"
        ));
        captureVisualQa("scenario-matrix-1024.png");

        setViewport(wait, 820, 900);
        assertPageNoHorizontalOverflow();
        assertThat(((JavascriptExecutor) driver).executeScript("""
                const matrix = document.querySelector('[data-testid="scenario-matrix"]');
                const toolbar = matrix.querySelector('.scenario-matrix-toolbar').getBoundingClientRect();
                const results = matrix.querySelector('[data-testid="scenario-mobile-results"]').getBoundingClientRect();
                const bulk = matrix.querySelector('.scenario-matrix-bulkbar').getBoundingClientRect();
                return toolbar.bottom <= results.top + 1 && results.bottom <= bulk.top + 1;
                """))
                .as("Matrix toolbar, mobile summaries, and bulk actions remain ordered at 820 pixels")
                .isEqualTo(true);
        captureVisualQa("scenario-matrix-820.png");

        setViewport(wait, 390, 844);
        assertPageNoHorizontalOverflow();
        WebElement mobileCandidate = driver.findElement(By.cssSelector(
                "[data-testid='scenario-matrix']"
        ));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block:'center'});",
                mobileCandidate
        );
        @SuppressWarnings("unchecked")
        Map<String, Number> mobileGeometry = (Map<String, Number>)
                ((JavascriptExecutor) driver).executeScript("""
                        const matrix = document.querySelector('[data-testid="scenario-matrix"]');
                        const results = matrix.querySelector('[data-testid="scenario-mobile-results"]');
                        const actions = [...matrix.querySelectorAll(
                          '.scenario-matrix-bulkbar button'
                        )].filter((button) => button.getClientRects().length > 0)
                          .map((button) => button.getBoundingClientRect());
                        return {
                          actionOutside: actions.filter((rect) =>
                            rect.left < 0 || rect.right > window.innerWidth
                          ).length,
                          actionOutsideVertical: actions.filter((rect) =>
                            rect.top < 0 || rect.bottom > window.innerHeight
                          ).length,
                          visibleResultsHeight: Math.min(
                            window.innerHeight,
                            results.getBoundingClientRect().bottom
                          ) - Math.max(0, results.getBoundingClientRect().top),
                          actionMinWidth: Math.min(...actions.map((rect) => rect.width)),
                          actionMinHeight: Math.min(...actions.map((rect) => rect.height))
                        };
                        """);
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='scenario-mobile-results']"
        )).getAttribute("data-first-viewport-count"))
                .as("mobile projects three bounded result summaries without a wide table")
                .isEqualTo("3");
        assertThat(mobileGeometry.get("actionOutside").intValue())
                .as("mobile bulk actions stay inside the viewport")
                .isZero();
        assertThat(mobileGeometry.get("actionOutsideVertical").intValue())
                .as("mobile bulk actions remain vertically reachable without page scrolling")
                .isZero();
        assertThat(mobileGeometry.get("visibleResultsHeight").doubleValue())
                .as("mobile retains a useful visible result viewport")
                .isGreaterThanOrEqualTo(150.0);
        assertThat(mobileGeometry.get("actionMinWidth").doubleValue())
                .as("mobile bulk actions retain a usable hit target")
                .isGreaterThanOrEqualTo(110.0);
        assertThat(mobileGeometry.get("actionMinHeight").doubleValue())
                .as("mobile bulk actions retain a usable hit target height")
                .isGreaterThanOrEqualTo(30.0);
        assertThat(driver.findElements(By.cssSelector(
                ".scenario-matrix-bulk-actions > button"
        ))).as("mobile exposes one primary run command and moves alternate scopes into a menu")
                .hasSize(1);
        assertThat(driver.findElement(By.cssSelector(
                ".scenario-run-scope-menu > summary"
        )).getAttribute("aria-label")).isEqualTo("More run scopes");
        captureVisualQa("scenario-matrix-390.png");

        WebElement firstRow = driver.findElements(By.cssSelector(
                ".scenario-mobile-result"
        )).getFirst();
        firstRow.findElement(By.cssSelector(".scenario-mobile-result-main")).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//button[normalize-space()='Edit full Case']"
        ))).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".scenario-workbench")
        ));
        assertThat(driver.findElement(By.cssSelector(
                ".scenario-view-switch button[aria-pressed='true']"
        )).getText()).isEqualTo("Case");
        assertPageNoHorizontalOverflow();
    }

    /**
     * Protects explainable Coverage projection and explicit candidate review in packaged Chrome.
     * Generation must remain opt-in, source-bound, bounded, and visibly short of a business oracle.
     */
    @Test
    void scenarioCoverageGeneratesReviewsAndAcceptsOneSourceBoundCandidateAcrossViewports()
            throws IOException {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        setViewport(wait, 1280, 800);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=v2");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-choice:examples']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-example:loan-policy-fallback']"
        ))).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-mode:scenarios']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//button[normalize-space()='Coverage']"
        ))).click();

        WebElement lens = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='coverage-lens']")
        ));
        assertThat(lens.getText())
                .contains("Case intent")
                .contains("Contract")
                .contains("DAG path")
                .contains("Dependency")
                .contains("Assertion")
                .contains("Evidence")
                .contains("No generated candidates")
                .doesNotContain("Overall score")
                .doesNotContain("Coverage percentage");
        assertThat(driver.findElements(By.cssSelector(".coverage-candidate-row"))).isEmpty();

        driver.findElement(By.xpath("//button[normalize-space()='Generate candidates']")).click();
        wait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                By.cssSelector(".coverage-candidate-row"),
                0
        ));
        WebElement firstCandidate = driver.findElements(By.cssSelector(
                ".coverage-candidate-row"
        )).getFirst();
        assertThat(firstCandidate.getText())
                .contains("NEEDS ORACLE")
                .contains("v1.0.0")
                .contains("named contribution");
        assertPageNoHorizontalOverflow();
        assertNoHorizontalOverflow(wait, By.cssSelector("[data-testid='contract-workspace']"));
        captureVisualQa("scenario-coverage-1280.png");

        setViewport(wait, 390, 844);
        assertPageNoHorizontalOverflow();
        @SuppressWarnings("unchecked")
        Map<String, Number> mobileGeometry = (Map<String, Number>)
                ((JavascriptExecutor) driver).executeScript("""
                        const lens = document.querySelector('[data-testid="coverage-lens"]');
                        const candidate = lens.querySelector('.coverage-candidate-row');
                        const buttons = [...candidate.querySelectorAll('button')]
                          .map((button) => button.getBoundingClientRect());
                        const rect = candidate.getBoundingClientRect();
                        return {
                          candidateOutside: rect.left < 0 || rect.right > window.innerWidth ? 1 : 0,
                          actionOutside: buttons.filter((button) =>
                            button.left < 0 || button.right > window.innerWidth
                          ).length,
                          actionMinHeight: Math.min(...buttons.map((button) => button.height))
                        };
                        """);
        assertThat(mobileGeometry.get("candidateOutside").intValue()).isZero();
        assertThat(mobileGeometry.get("actionOutside").intValue()).isZero();
        assertThat(mobileGeometry.get("actionMinHeight").doubleValue()).isGreaterThanOrEqualTo(28.0);
        captureVisualQa("scenario-coverage-390.png");

        WebElement accept = driver.findElements(By.cssSelector(
                ".coverage-candidate-row button.primary"
        )).getFirst();
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", accept);
        wait.until(ExpectedConditions.elementToBeClickable(accept)).click();
        waitForText(wait, By.cssSelector(".scenario-asset-notice"), "accepted as a Scenario draft");
        waitForText(wait, By.cssSelector(".coverage-candidate-stale"), "Source changed");
        assertThat(driver.findElements(By.cssSelector(
                ".coverage-candidate-row button.primary:not([disabled])"
        ))).isEmpty();

        driver.findElement(By.xpath("//button[normalize-space()='Case']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".scenario-workbench")));
        assertPageNoHorizontalOverflow();

        setViewport(wait, 1280, 800);
        wait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                By.cssSelector(".scenario-list-row"),
                2
        ));
        assertThat(driver.findElements(By.cssSelector(".scenario-list-row")))
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(driver.findElement(By.cssSelector(".scenario-list-row.selected")).getText()).isNotBlank();
        assertThat(driver.findElement(By.cssSelector(".scenario-editor")).getText())
                .contains("Unproven")
                .contains("no business assertion was evaluated")
                .doesNotContain("Run success is enough");
        assertPageNoHorizontalOverflow();
    }

    /**
     * Proves the complete governed import path in packaged Chrome. The source is inspected and
     * mapped in the browser, then independently re-parsed and materialized by the Java service.
     */
    @Test
    void scenarioImportMaterializesSampleThroughTheServerAndReturnsToMatrixAcrossViewports()
            throws IOException {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        setViewport(wait, 1280, 800);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=v2");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-choice:examples']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-example:loan-policy-fallback']"
        ))).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-mode:scenarios']"
        ))).click();
        WebElement blockedImport = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//button[normalize-space()='Import cases']")
        ));
        assertThat(blockedImport.isEnabled()).isFalse();
        assertThat(blockedImport.getAttribute("title")).contains("Save Graph");

        driver.findElement(By.cssSelector("[data-testid='author-mode:contract']")).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//button[normalize-space()='Save Graph']"
        ))).click();
        try {
            new WebDriverWait(driver, Duration.ofSeconds(30)).until(ExpectedConditions.attributeToBe(
                    By.cssSelector(".workspace-v2"), "data-draft-lifecycle", "saved"
            ));
        } catch (TimeoutException ex) {
            throw new AssertionError(
                    "Graph save did not become durable. lifecycle='%s', notice='%s'"
                            .formatted(
                                    driver.findElement(By.cssSelector(".workspace-v2"))
                                            .getAttribute("data-draft-lifecycle"),
                                    textOf(By.cssSelector(".scenario-asset-notice"))),
                    ex);
        }
        driver.findElement(By.cssSelector("[data-testid='author-mode:scenarios']")).click();

        List<WebElement> staleAlerts = driver.findElements(By.cssSelector(
                ".contract-stale-banner[role='alert']"
        ));
        if (!staleAlerts.isEmpty()) {
            staleAlerts.getFirst().findElement(By.xpath(
                    ".//button[normalize-space()='Review compatibility']"
            )).click();
            WebElement resolution;
            try {
                resolution = wait.until(ExpectedConditions.visibilityOfElementLocated(
                        By.cssSelector(".compatibility-resolution")
                ));
            } catch (TimeoutException ex) {
                throw new AssertionError(
                        "Compatibility review did not expose a resolution command. workbench='%s'"
                                .formatted(textOf(By.cssSelector(".compatibility-workbench"))),
                        ex);
            }
            List<WebElement> acknowledgements = resolution.findElements(
                    By.cssSelector("input[type='checkbox']")
            );
            if (!acknowledgements.isEmpty()) {
                acknowledgements.getFirst().click();
            }
            WebElement compatibilityResolution = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                    "//button[normalize-space()='Rebase local draft'"
                            + " or normalize-space()='Record review & rebase']"
            )));
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].click();", compatibilityResolution
            );
            try {
                wait.until(ExpectedConditions.invisibilityOfElementLocated(
                        By.cssSelector(".contract-stale-banner[role='alert']")
                ));
            } catch (TimeoutException ex) {
                throw new AssertionError(
                        "Compatibility resolution did not make the Scenario current. body='%s'"
                                .formatted(textOf(By.cssSelector("[data-testid='contract-workspace']"))),
                        ex);
            }
            driver.findElement(By.cssSelector("[data-testid='author-mode:scenarios']")).click();
        }

        try {
            wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                    "//button[normalize-space()='Import cases']"
            ))).click();
        } catch (TimeoutException ex) {
            throw new AssertionError(
                    "Scenario import remained blocked after compatibility resolution. body='%s'"
                            .formatted(textOf(By.cssSelector("[data-testid='contract-workspace']"))),
                    ex);
        }
        WebElement workbench = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='scenario-import-workbench']")
        ));
        workbench.findElement(By.xpath(".//button[normalize-space()='Load sample']")).click();
        workbench.findElement(By.xpath(".//button[normalize-space()='Inspect source']")).click();
        waitForText(wait, By.cssSelector(".scenario-import-metrics"), "Rows");
        waitForText(wait, By.cssSelector(".scenario-import-metrics"), "5");
        workbench.findElement(By.xpath(".//button[normalize-space()='Map columns']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".scenario-import-map-list")
        ));
        workbench.findElement(By.xpath(".//button[normalize-space()='Review plan']")).click();
        waitForText(wait, By.cssSelector(".scenario-import-review"), "5 rows");
        workbench.findElement(By.xpath(
                ".//button[normalize-space()='Materialize 5 cases']"
        )).click();
        try {
            waitForText(wait, By.cssSelector(".scenario-import-receipt"), "5 cases materialized");
        } catch (AssertionError ex) {
            throw new AssertionError(
                    "Scenario import did not materialize. error='%s'"
                            .formatted(textOf(By.cssSelector(".scenario-import-error"))),
                    ex);
        }
        assertThat(workbench.getText())
                .contains("0 rejected rows")
                .contains("scenario-import-")
                .doesNotContain("Request failed");
        assertPageNoHorizontalOverflow();
        captureVisualQa("scenario-import-receipt-1280.png");

        setViewport(wait, 390, 844);
        assertPageNoHorizontalOverflow();
        @SuppressWarnings("unchecked")
        Map<String, Number> geometry = (Map<String, Number>) ((JavascriptExecutor) driver)
                .executeScript("""
                        const panel = document.querySelector('[data-testid="scenario-import-workbench"]');
                        const actions = [...panel.querySelectorAll('.scenario-import-actions button')]
                          .map((button) => button.getBoundingClientRect());
                        const rect = panel.getBoundingClientRect();
                        return {
                          panelOutside: rect.left < 0 || rect.right > window.innerWidth ? 1 : 0,
                          actionOutside: actions.filter((item) =>
                            item.left < 0 || item.right > window.innerWidth
                          ).length,
                          minActionHeight: Math.min(...actions.map((item) => item.height))
                        };
                        """);
        assertThat(geometry.get("panelOutside").intValue()).isZero();
        assertThat(geometry.get("actionOutside").intValue()).isZero();
        assertThat(geometry.get("minActionHeight").doubleValue()).isGreaterThanOrEqualTo(30.0);
        captureVisualQa("scenario-import-receipt-390.png");

        workbench.findElement(By.xpath(".//button[normalize-space()='Done']")).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='scenario-import-workbench']")
        ));
        WebElement importedMatrix = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='scenario-matrix']")
        ));
        assertThat(importedMatrix.getAttribute("data-result-projection"))
                .isEqualTo("mobile-summary");
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid='scenario-mobile-results'] .scenario-mobile-result"
        ))).hasSizeGreaterThanOrEqualTo(7);
    }

    /**
     * Exercises the first complete task-oriented authoring slice in packaged Chrome.
     *
     * <p>The test protects behavior that component tests cannot prove: the command bar has one
     * visible primary action, legacy chrome is physically absent from layout, the canvas receives
     * at least 65% of the post-command-bar workspace, and a complete example can move from Start
     * through a real Scenario run into Evidence without node overlap or false DSL diagnostics. The
     * evidence view must open directly, without exposing the legacy raw test-table dialog. The
     * completed task is checked at the
     * supported 1024-pixel desktop floor and finally reduced to the 390 by 844 light-editing
     * viewport to prove that the primary action remains reachable without page-level horizontal
     * overflow.</p>
     */
    @Test
    void taskOrientedAuthorShellLoadsRunsAndReviewsWithoutCompetingChromeInRealBrowser() {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        setViewport(wait, 1280, 720);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=v2");

        WebElement workspace = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".workspace-v2")
        ));
        WebElement startDialog = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));
        assertThat(startDialog.getText())
                .contains("Load example")
                .contains("Import DSL")
                .contains("Create operator library")
                .contains("Blank graph");

        driver.findElement(By.cssSelector("[aria-label='Close start dialog']")).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));

        @SuppressWarnings("unchecked")
        Map<String, Number> shellMetrics = (Map<String, Number>)
                ((JavascriptExecutor) driver).executeScript("""
                        const visible = (element) => {
                          const rect = element.getBoundingClientRect();
                          const style = getComputedStyle(element);
                          return style.display !== 'none'
                            && style.visibility !== 'hidden'
                            && rect.width > 0
                            && rect.height > 0;
                        };
                        const workspace = document.querySelector('.workspace-v2');
                        const command = document.querySelector('.author-command-bar');
                        const canvas = document.querySelector('.workspace-v2 > .canvas');
                        const availableHeight = workspace.clientHeight - command.clientHeight;
                        return {
                          primaryCount: [...document.querySelectorAll(
                            '[data-testid="author-primary-action"]'
                          )].filter(visible).length,
                          commandCount: [...command.querySelectorAll('button, a')]
                            .filter(visible).length,
                          canvasAreaRatio: (canvas.clientWidth * canvas.clientHeight)
                            / (workspace.clientWidth * availableHeight),
                          hiddenLegacyChrome: [
                            '.journey-bar',
                            '.canvas-examples',
                            '.toolbar',
                            '.contract-rail'
                          ].filter((selector) => {
                            const element = document.querySelector(selector);
                            return element && !visible(element);
                          }).length
                        };
                        """);
        assertThat(shellMetrics.get("primaryCount").intValue())
                .as("visible primary author action")
                .isEqualTo(1);
        assertThat(shellMetrics.get("commandCount").intValue())
                .as("persistent command-bar controls")
                .isLessThanOrEqualTo(12);
        assertThat(shellMetrics.get("canvasAreaRatio").doubleValue())
                .as("canvas share of post-command-bar workspace")
                .isGreaterThanOrEqualTo(0.65);
        assertThat(shellMetrics.get("hiddenLegacyChrome").intValue())
                .as("legacy journey/examples/toolbar/contract rail removed from v2 layout")
                .isEqualTo(4);
        assertNoHorizontalOverflow(wait, By.cssSelector(".workspace-v2"));

        double canvasWidthBeforeCollapse = elementClientWidth(
                driver.findElement(By.cssSelector(".workspace-v2 > .canvas")));
        driver.findElement(By.cssSelector("[aria-label='Collapse operator palette']")).click();
        driver.findElement(By.cssSelector("[aria-label='Collapse context inspector']")).click();
        wait.until(ExpectedConditions.attributeContains(
                By.cssSelector(".workspace-v2"), "class", "palette-collapsed"));
        wait.until(ExpectedConditions.attributeContains(
                By.cssSelector(".workspace-v2"), "class", "inspector-collapsed"));
        assertThat(elementClientWidth(driver.findElement(By.cssSelector(".workspace-v2 > .canvas"))))
                .as("canvas expands when both context panels collapse")
                .isGreaterThan(canvasWidthBeforeCollapse + 300.0);
        driver.findElement(By.cssSelector("[aria-label='Expand operator palette']")).click();
        driver.findElement(By.cssSelector("[aria-label='Expand context inspector']")).click();

        WebElement paletteResizer = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[aria-label='Resize operator palette']")
        ));
        ((JavascriptExecutor) driver).executeScript("""
                const handle = arguments[0];
                const startX = handle.getBoundingClientRect().left + 3;
                handle.dispatchEvent(new PointerEvent('pointerdown', {
                  bubbles: true,
                  cancelable: true,
                  pointerId: 7,
                  pointerType: 'mouse',
                  clientX: startX
                }));
                window.dispatchEvent(new PointerEvent('pointermove', {
                  bubbles: true,
                  pointerId: 7,
                  pointerType: 'mouse',
                  clientX: startX + 52
                }));
                window.dispatchEvent(new PointerEvent('pointerup', {
                  bubbles: true,
                  pointerId: 7,
                  pointerType: 'mouse',
                  clientX: startX + 52
                }));
                """, paletteResizer);
        String resizedPaletteTrack = String.valueOf(((JavascriptExecutor) driver).executeScript(
                "return getComputedStyle(arguments[0]).getPropertyValue('--author-palette-track');",
                workspace));
        assertThat(Double.parseDouble(resizedPaletteTrack.replace("px", "").trim()))
                .as("drag-resized palette width")
                .isBetween(220.0, 360.0)
                .isGreaterThan(220.0);

        driver.findElement(By.cssSelector(".author-secondary-actions button:first-child")).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-choice:examples']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-example:loan-policy-fallback']"
        ))).click();

        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".react-flow__node"),
                5
        ));
        waitForText(wait, By.cssSelector("[data-testid='author-primary-action']"), "Run & Compare");
        waitForText(wait, By.cssSelector("[data-testid='author-context-inspector']"), "Decision response");
        assertThat(driver.getCurrentUrl())
                .contains("authorMode=compose")
                .contains("nodeId=n5");

        Number nodeOverlapCount = (Number) ((JavascriptExecutor) driver).executeScript("""
                const nodes = [...document.querySelectorAll('.react-flow__node')]
                  .map((node) => node.getBoundingClientRect());
                let overlaps = 0;
                for (let left = 0; left < nodes.length; left += 1) {
                  for (let right = left + 1; right < nodes.length; right += 1) {
                    const width = Math.max(
                      0,
                      Math.min(nodes[left].right, nodes[right].right)
                        - Math.max(nodes[left].left, nodes[right].left)
                    );
                    const height = Math.max(
                      0,
                      Math.min(nodes[left].bottom, nodes[right].bottom)
                        - Math.max(nodes[left].top, nodes[right].top)
                    );
                    if (width * height > 1) {
                      overlaps += 1;
                    }
                  }
                }
                return overlaps;
                """);
        assertThat(nodeOverlapCount.intValue())
                .as("loaded example node-node overlap")
                .isZero();

        WebElement decisionTableNode = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='canvas-node:n4']")
        ));
        dispatchDoubleClick(decisionTableNode);
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='operator-detail-dialog']"),
                "data-default-tab",
                "rules"
        ));
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='operator-editor-pane:rules']"
        )).isDisplayed()).isTrue();
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='decision-table-editor']"
        )).isDisplayed()).isTrue();
        driver.findElement(By.cssSelector("[aria-label='Close operator details']")).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='operator-detail-dialog']")
        ));

        WebElement transformNode = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='canvas-node:n5']")
        ));
        dispatchDoubleClick(transformNode);
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='operator-detail-dialog']"),
                "data-default-tab",
                "mapping"
        ));
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='operator-editor-pane:mapping']"
        )).isDisplayed()).isTrue();
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='transform-assignment-editor']"
        )).isDisplayed()).isTrue();
        driver.findElement(By.cssSelector("[data-testid='operator-detail-apply']")).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='operator-detail-dialog']")
        ));

        WebElement primaryAction = driver.findElement(
                By.cssSelector("[data-testid='author-primary-action']")
        );
        wait.until(ignored -> primaryAction.isEnabled());
        primaryAction.click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"),
                "data-author-mode",
                "evidence"
        ));
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='author-surface-command-handoff']"
        )).isDisplayed()).as("Evidence task surface owns its commands").isFalse();
        assertThat(workspace.getAttribute("data-author-mode")).isEqualTo("evidence");
        assertThat(driver.getCurrentUrl())
                .contains("authorMode=evidence")
                .contains("target=graph")
                .contains("workspaceView=evidence")
                .contains("nodeId=n5");
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='scenario-evidence']"
        )).getText())
                .contains("Review required")
                .contains("paths to a trusted result")
                .contains("1 assertion passed");
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid='test-suite-dialog']"
        ))).isEmpty();
        driver.findElement(By.cssSelector("[data-testid='author-mode:compose']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-author-mode", "compose"
        ));
        WebElement diagnosticsToggle = driver.findElement(By.cssSelector(
                "[data-testid='author-diagnostics-drawer'] .author-diagnostics-toggle"
        ));
        if (!"true".equals(diagnosticsToggle.getAttribute("aria-expanded"))) {
            diagnosticsToggle.click();
        }
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(
                        "[data-testid='author-diagnostics-drawer'] .author-diagnostics-toggle"),
                "aria-expanded",
                "true"
        ));
        waitForText(wait, By.cssSelector("[data-testid='author-diagnostics-drawer']"), "No diagnostics");
        assertThat(textOf(By.cssSelector("[data-testid='author-diagnostics-drawer']")))
                .doesNotContain("bloge.dsl");
        assertNoHorizontalOverflow(wait, By.cssSelector(".workspace-v2"));

        diagnosticsToggle.click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(
                        "[data-testid='author-diagnostics-drawer'] .author-diagnostics-toggle"),
                "aria-expanded",
                "false"
        ));
        setViewport(wait, 1024, 768);
        assertNoHorizontalOverflow(wait, By.cssSelector(".workspace-v2"));
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='author-primary-action']")).getText()).isEqualTo("Review result");
        driver.findElement(By.cssSelector("[data-testid='author-primary-action']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='contract-workspace']")
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-author-mode", "evidence"
        ));
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='author-surface-command-handoff']"
        )).isDisplayed()).as("Evidence task surface owns its commands").isFalse();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='scenario-evidence']")
        ));
        assertNoHorizontalOverflow(wait, By.cssSelector("[data-testid='contract-workspace']"));
        driver.findElement(By.cssSelector("[data-testid='author-mode:compose']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-author-mode", "compose"
        ));

        setViewport(wait, 390, 844);
        assertNoHorizontalOverflow(wait, By.cssSelector(".workspace-v2"));
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='author-primary-action']")).isDisplayed())
                .as("mobile-degraded primary author action")
                .isTrue();
        assertThat(elementClientHeight(driver.findElement(
                By.cssSelector(".workspace-v2 > .canvas"))))
                .as("mobile-degraded canvas height")
                .isGreaterThanOrEqualTo(420.0);
        Number mobilePrimaryCount = (Number) ((JavascriptExecutor) driver).executeScript("""
                return [...document.querySelectorAll(
                  '[data-testid="author-primary-action"]'
                )].filter((element) => {
                  const rect = element.getBoundingClientRect();
                  const style = getComputedStyle(element);
                  return style.display !== 'none'
                    && style.visibility !== 'hidden'
                    && rect.width > 0
                    && rect.height > 0;
                }).length;
                """);
        assertThat(mobilePrimaryCount.intValue())
                .as("mobile-degraded visible primary action count")
                .isEqualTo(1);
    }

    /**
     * Proves that evidence is bound to the exact authoring and execution coordinates in Chrome.
     *
     * <p>An input edit must retain the prior result for comparison while marking execution and
     * assertions stale, blocking promotion, and offering one explicit rerun action. The rerun must
     * consume the edited Scenario Given input, produce a different execution-request fingerprint, and only
     * then restore current evidence. This guards against the dangerous UX failure where a green
     * result survives an authoring change or a rerun silently ignores the value the author edited.</p>
     */
    @Test
    void taskWorkspaceRetainsStaleEvidenceUntilTheEditedInputIsRerunInRealBrowser() {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        setViewport(wait, 1024, 768);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=v2");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-choice:examples']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-example:loan-policy-fallback']"
        ))).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));

        WebElement workspace = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".workspace-v2")
        ));
        WebElement primaryAction = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-testid='author-primary-action']")
        ));
        primaryAction.click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"),
                "data-evidence-freshness",
                "current"
        ));
        WebElement firstEvidence = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='scenario-evidence']")
        ));
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//summary[normalize-space()='Technical coordinates']"
        ))).click();
        assertThat(firstEvidence.getText())
                .contains("Draft fingerprint")
                .contains("Scenario fingerprint")
                .contains("Dependency closure")
                .contains("Execution request");
        String firstRequestFingerprint = driver.findElement(By.xpath(
                "//dt[normalize-space()='Execution request']/following-sibling::dd/code"
        )).getText();
        assertThat(firstRequestFingerprint).startsWith("sha256:");

        driver.findElement(By.cssSelector("[data-testid='author-mode:scenarios']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='author-surface:scenarios']")
        ));
        driver.findElement(By.xpath(
                "//*[@data-testid='contract-workspace']//button[normalize-space()='Case']"
        )).click();
        WebElement applicantId = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-testid='contract-workspace'] input[aria-label='applicantId']")
        ));
        applicantId.clear();
        applicantId.sendKeys("applicant-1002");
        driver.findElement(By.cssSelector("[data-testid='author-mode:compose']")).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-surface:scenarios']")
        ));

        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"),
                "data-evidence-freshness",
                "stale"
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"),
                "data-promotion-lifecycle",
                "blocked"
        ));
        assertThat(textOf(By.cssSelector("[data-testid='author-command-bar']")))
                .contains("Evidence")
                .contains("STALE")
                .contains("Gate")
                .contains("BLOCKED");
        waitForText(
                wait,
                By.cssSelector("[data-testid='author-primary-action']"),
                "Rerun & Compare"
        );

        driver.findElement(By.cssSelector("[data-testid='author-primary-action']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"),
                "data-evidence-freshness",
                "current"
        ));
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//summary[normalize-space()='Technical coordinates']"
        ))).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='scenario-evidence-coordinate']")
        ));
        String rerunRequestFingerprint = driver.findElement(By.xpath(
                "//dt[normalize-space()='Execution request']/following-sibling::dd/code"
        )).getText();
        assertThat(rerunRequestFingerprint)
                .startsWith("sha256:")
                .isNotEqualTo(firstRequestFingerprint);
        assertThat(workspace.getAttribute("data-promotion-lifecycle"))
                .as("promotion remains fail-closed until Contract and Governance are current")
                .isEqualTo("not_evaluated");
        assertNoHorizontalOverflow(wait, By.cssSelector(
                "[data-testid='contract-workspace']"
        ));
    }

    /**
     * Protects the task-oriented canvas, constrained layout review, and compact shell in Chrome.
     *
     * <p>The journey pins an authored node, opens Auto Layout as an atomic preview, and proves that
     * the quality report is based on the same topology the author can apply. The preview must close
     * both compact drawers, freeze operator insertion, keep every rendered node inside the graph
     * viewport, report zero node and edge-label collisions, and preserve Scenario currentness after
     * a presentation-only position change. The same session then checks the central Contract at
     * 1024 pixels, mutually exclusive drawers and an unobstructed Scenario run action at 820 pixels,
     * and the 390-pixel review-first Evidence region. These assertions catch browser geometry,
     * modal-retirement, and media-query regressions that DOM component tests cannot establish.</p>
     */
    @Test
    void taskWorkspacePreviewsCollisionFreeLayoutAndUsesCompactDrawersInRealBrowser() {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        setViewport(wait, 1024, 768);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=v2");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-choice:examples']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-example:loan-policy-fallback']"
        ))).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));

        WebElement workspace = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".workspace-v2")
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-compact-workspace", "true"
        ));
        assertThat(((JavascriptExecutor) driver).executeScript("""
                const graph = document.querySelector('.react-flow');
                const navigator = document.querySelector('.canvas-task-navigator');
                return {
                  navigatorInsideGraph: graph.contains(navigator),
                  minimapCount: document.querySelectorAll('.react-flow__minimap').length,
                  horizontalOverflow:
                    document.documentElement.scrollWidth - document.documentElement.clientWidth
                };
                """))
                .as("task navigator is outside graph rendering and small graphs have one overview")
                .isEqualTo(Map.of(
                        "navigatorInsideGraph", false,
                        "minimapCount", 0L,
                        "horizontalOverflow", 0L
                ));

        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-readability", "pass"
        ));
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid='canvas-edge-label']"), 4
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='author-flow']"),
                "data-canvas-viewport-settled",
                "true"
        ));
        assertThat(workspace.getAttribute("data-canvas-visible-field-labels")).isEqualTo("12");
        Map<String, Number> inspectGeometry = canvasReadabilityGeometry();
        assertThat(inspectGeometry)
                .as("1024 Inspect keeps all field semantics readable without visual collisions")
                .containsEntry("outsideNodes", 0L)
                .containsEntry("outsideLabels", 0L)
                .containsEntry("nodeLabelCollisions", 0L)
                .containsEntry("labelLabelCollisions", 0L);
        assertThat(inspectGeometry.get("effectiveTitleFontPx").doubleValue())
                .isGreaterThanOrEqualTo(12.0);

        driver.findElement(By.cssSelector("[data-testid='canvas-task-mode:overview']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-task-mode", "overview"
        ));
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid='canvas-edge-label']"), 0
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='author-flow']"),
                "data-canvas-viewport-settled",
                "true"
        ));
        wait.until(ignored -> canvasReadabilityGeometry().get("outsideNodes").intValue() == 0);
        assertThat(workspace.getAttribute("data-canvas-visible-field-labels")).isEqualTo("0");

        driver.findElement(By.cssSelector(".react-flow__node[data-id='n2']")).click();
        driver.findElement(By.cssSelector("[data-testid='canvas-task-mode:focus']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-task-mode", "focus"
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-readability", "pass"
        ));
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid='canvas-edge-label']"), 3
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='author-flow']"),
                "data-canvas-viewport-settled",
                "true"
        ));
        assertThat(workspace.getAttribute("data-canvas-visible-field-labels")).isEqualTo("10");
        assertThat(canvasReadabilityGeometry())
                .as("Focus keeps only the complete selected closure and remains collision-free")
                .containsEntry("outsideNodes", 0L)
                .containsEntry("outsideLabels", 0L)
                .containsEntry("nodeLabelCollisions", 0L)
                .containsEntry("labelLabelCollisions", 0L);

        driver.findElement(By.cssSelector("[data-testid='canvas-task-mode:inspect']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-task-mode", "inspect"
        ));
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid='canvas-edge-label']"), 4
        ));
        assertThat(workspace.getAttribute("data-canvas-visible-field-labels")).isEqualTo("12");

        setViewport(wait, 1280, 768);
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='navigator-fit-all']"
        ))).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-readability", "pass"
        ));
        Map<String, Number> wideGeometry = canvasReadabilityGeometry();
        assertThat(wideGeometry)
                .as("1280 Fit is not less readable than the 1024 compact workspace")
                .containsEntry("outsideNodes", 0L)
                .containsEntry("outsideLabels", 0L)
                .containsEntry("nodeLabelCollisions", 0L)
                .containsEntry("labelLabelCollisions", 0L);
        assertThat(wideGeometry.get("effectiveTitleFontPx").doubleValue())
                .isGreaterThanOrEqualTo(12.0);
        setViewport(wait, 1024, 768);
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-compact-workspace", "true"
        ));
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='navigator-fit-all']"
        ))).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-readability", "pass"
        ));

        driver.findElement(By.cssSelector("[data-testid='navigator-pin-node']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='navigator-pin-node']"), "aria-pressed", "true"
        ));
        WebElement primaryCreditNode = driver.findElement(By.cssSelector(
                ".react-flow__node[data-id='n2']"
        ));
        String primaryCreditPosition = primaryCreditNode.getAttribute("style");
        new Actions(driver)
                .moveToElement(primaryCreditNode)
                .clickAndHold()
                .moveByOffset(0, 120)
                .release()
                .perform();
        wait.until(ignored -> !primaryCreditPosition.equals(
                primaryCreditNode.getAttribute("style")
        ));
        driver.findElement(By.xpath("//button[normalize-space()='Auto layout']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-layout-preview", "active"
        ));

        WebElement quality = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='canvas-layout-review']")
        ));
        assertThat(quality.getText())
                .contains("Node overlaps 0")
                .contains("label collisions 0")
                .contains("pinned nodes 1");
        assertThat(elementClientWidth(driver.findElement(By.cssSelector("aside.palette"))))
                .as("palette closes before geometry review")
                .isZero();
        assertThat(elementClientWidth(driver.findElement(By.cssSelector("aside.inspector"))))
                .as("inspector closes before geometry review")
                .isZero();
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='operator-button:bloge:transform']"
        )).isEnabled())
                .as("topology insertion is frozen during layout review")
                .isFalse();
        wait.until(ignored -> (
                canvasReadabilityGeometry().get("outsideNodes").intValue() == 0
        ));

        @SuppressWarnings("unchecked")
        Map<String, Number> geometry = (Map<String, Number>)
                ((JavascriptExecutor) driver).executeScript("""
                        const flow = document.querySelector('.flow').getBoundingClientRect();
                        const nodes = [...document.querySelectorAll('.react-flow__node')]
                          .map((element) => ({
                            id: element.dataset.id,
                            rect: element.getBoundingClientRect()
                          }));
                        let overlapPairs = 0;
                        for (let left = 0; left < nodes.length; left += 1) {
                          for (let right = left + 1; right < nodes.length; right += 1) {
                            const a = nodes[left].rect;
                            const b = nodes[right].rect;
                            if (a.left < b.right && a.right > b.left
                                && a.top < b.bottom && a.bottom > b.top) {
                              overlapPairs += 1;
                            }
                          }
                        }
                        return {
                          outsideNodes: nodes.filter(({rect}) =>
                            rect.left < flow.left || rect.right > flow.right
                            || rect.top < flow.top || rect.bottom > flow.bottom
                          ).length,
                          overlapPairs
                        };
                        """);
        assertThat(geometry.get("outsideNodes").intValue())
                .as("Auto Fit includes every preview node after the quality strip resizes Canvas")
                .isZero();
        assertThat(geometry.get("overlapPairs").intValue())
                .as("rendered operator cards do not overlap")
                .isZero();

        WebElement applyLayout = driver.findElement(By.cssSelector("[data-testid='layout-apply']"));
        if (applyLayout.isEnabled()) {
            applyLayout.click();
        } else {
            assertThat(driver.findElements(By.cssSelector("[data-testid='layout-regressions'] li")))
                    .as("a regressive candidate names the reasons before override")
                    .isNotEmpty();
            driver.findElement(By.cssSelector(
                    "[data-testid='layout-override-review'] > summary"
            )).click();
            driver.findElement(By.cssSelector("[data-testid='layout-override']")).click();
        }
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-layout-preview", "inactive"
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canonical-scenario-ready", "true"
        ));
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='author-primary-action']"
        )).isEnabled())
                .as("visual coordinates do not invalidate executable Scenario semantics")
                .isTrue();

        driver.findElement(By.cssSelector("[data-testid='author-mode:contract']")).click();
        WebElement contractSurface = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='author-surface:contract']")
        ));
        assertThat(contractSurface.getAttribute("data-target-kind")).isEqualTo("graph");
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid='contract-workspace'][role='dialog']"))).isEmpty();
        assertThat(driver.findElements(By.cssSelector(".contract-tabs button")))
                .extracting(WebElement::getText)
                .containsExactly("Contract details", "Compatibility");
        waitForText(wait, By.cssSelector("[aria-label='Contract lineage']"), "TARGET FINGERPRINT");
        assertNoHorizontalOverflow(wait, By.cssSelector(
                "[data-testid='contract-workspace']"
        ));
        driver.findElement(By.cssSelector("[data-testid='author-mode:compose']")).click();
        wait.until(ExpectedConditions.invisibilityOf(contractSurface));

        setViewport(wait, 820, 900);
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-compact-workspace", "true"
        ));
        driver.findElement(By.cssSelector("[data-testid='canvas-task-mode:overview']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-task-mode", "overview"
        ));
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid='canvas-edge-label']"), 0
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='author-flow']"),
                "data-canvas-viewport-settled",
                "true"
        ));
        wait.until(ignored -> canvasReadabilityGeometry().get("outsideNodes").intValue() == 0);
        assertThat(workspace.getAttribute("data-canvas-visible-field-labels")).isEqualTo("0");
        assertThat(canvasReadabilityGeometry())
                .as("820 Overview deliberately removes field detail and keeps the graph in bounds")
                .containsEntry("outsideNodes", 0L)
                .containsEntry("outsideLabels", 0L)
                .containsEntry("nodeLabelCollisions", 0L)
                .containsEntry("labelLabelCollisions", 0L);
        driver.findElement(By.cssSelector(".react-flow__node[data-id='n2']")).click();
        driver.findElement(By.cssSelector("[data-testid='canvas-task-mode:inspect']")).click();
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-task-mode", "inspect"
        ));
        driver.findElement(By.cssSelector("[data-testid='compact-open-palette']")).click();
        wait.until(ignored -> elementClientWidth(
                driver.findElement(By.cssSelector("aside.palette"))) > 0);
        assertThat(elementClientWidth(driver.findElement(By.cssSelector("aside.inspector"))))
                .isZero();
        driver.findElement(By.cssSelector("[aria-label='Collapse operator palette']")).click();
        driver.findElement(By.cssSelector("[data-testid='compact-open-inspector']")).click();
        wait.until(ignored -> elementClientWidth(
                driver.findElement(By.cssSelector("aside.inspector"))) > 0);
        assertThat(elementClientWidth(driver.findElement(By.cssSelector("aside.palette"))))
                .isZero();
        driver.findElement(By.cssSelector("[aria-label='Collapse context inspector']")).click();

        driver.findElement(By.cssSelector("[data-testid='author-mode:scenarios']")).click();
        WebElement scenarioSurface = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='author-surface:scenarios']")
        ));
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid='contract-workspace'][role='dialog']"))).isEmpty();
        assertThat(driver.findElements(By.cssSelector(".contract-tabs"))).isEmpty();
        WebElement topologyLauncher = driver.findElement(By.cssSelector(
                "[data-testid='author-context-rail-launcher']"
        ));
        assertThat(topologyLauncher.isDisplayed()).isTrue();
        String scenarioCoordinate = driver.getCurrentUrl();
        topologyLauncher.click();
        wait.until(ignored -> elementClientWidth(
                driver.findElement(By.cssSelector("aside.inspector"))) > 0);
        waitForText(wait, By.cssSelector("[data-testid='topology-context-rail']"),
                "Decision response");
        driver.findElement(By.cssSelector("[aria-label='Collapse context inspector']")).click();
        assertThat(driver.getCurrentUrl())
                .as("secondary topology drawer does not mutate the authoring coordinate")
                .isEqualTo(scenarioCoordinate);

        List<WebElement> desktopOpenActions = driver.findElements(By.cssSelector(
                "[data-testid^='scenario-matrix-row-'] button[data-focus-action='open']"
        ));
        if (desktopOpenActions.isEmpty()) {
            driver.findElements(By.cssSelector(
                    "[data-testid='scenario-mobile-results'] .scenario-mobile-result-main"
            )).getFirst().click();
            wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                    "//button[normalize-space()='Edit full Case']"
            ))).click();
        } else {
            desktopOpenActions.getFirst().click();
        }
        WebElement runButton = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "[data-testid='scenario-run'], .scenario-mobile-run-command"
        )));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block:'end'});", runButton);
        wait.until(ExpectedConditions.visibilityOf(runButton));
        @SuppressWarnings("unchecked")
        Map<String, Number> runActionGeometry = (Map<String, Number>)
                ((JavascriptExecutor) driver).executeScript("""
                        const run = arguments[0].getBoundingClientRect();
                        const app = document.querySelector('.app').getBoundingClientRect();
                        const workspace = document.querySelector('.workspace-v2').getBoundingClientRect();
                        const canvas = document.querySelector('.workspace-v2 > .canvas').getBoundingClientRect();
                        const surface = document.querySelector('.author-central-surface').getBoundingClientRect();
                        const body = document.querySelector('.contract-workspace-body');
                        const bodyRect = body.getBoundingClientRect();
                        const footer = document.querySelector(
                          '.scenario-run-bar, .scenario-mobile-run-summary'
                        ).getBoundingClientRect();
                        const diagnostics = document.querySelector(
                          '[data-testid="author-diagnostics-drawer"]'
                        ).getBoundingClientRect();
                        return {
                          viewportHeight: window.innerHeight,
                          pageScrollY: window.scrollY,
                          appBottom: app.bottom,
                          workspaceBottom: workspace.bottom,
                          canvasBottom: canvas.bottom,
                          canvasPaddingBottom: Number.parseFloat(
                            window.getComputedStyle(document.querySelector(
                              '.workspace-v2 > .canvas'
                            )).paddingBottom
                          ),
                          surfaceBottom: surface.bottom,
                          bodyBottom: bodyRect.bottom,
                          bodyScrollTop: body.scrollTop,
                          bodyScrollHeight: body.scrollHeight,
                          bodyClientHeight: body.clientHeight,
                          footerTop: footer.top,
                          footerBottom: footer.bottom,
                          runBottom: run.bottom,
                          diagnosticsTop: diagnostics.top
                        };
                        """, runButton);
        assertThat(runActionGeometry.get("runBottom").doubleValue())
                .as(
                        "Scenario run action remains above the collapsed Diagnostics drawer: %s",
                        runActionGeometry
                )
                .isLessThanOrEqualTo(
                        runActionGeometry.get("diagnosticsTop").doubleValue());
        assertNoHorizontalOverflow(wait, By.cssSelector(
                "[data-testid='contract-workspace']"
        ));

        wait.until(ExpectedConditions.elementToBeClickable(runButton)).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='scenario-evidence']")
        ));
        setViewport(wait, 390, 844);
        WebElement evidence = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='contract-workspace']")
        ));
        waitForText(wait, By.cssSelector("[data-testid='scenario-evidence']"), "Execution");
        assertThat(evidence.getAttribute("role")).isEqualTo("region");
        assertThat(evidence.getAttribute("data-presentation")).isEqualTo("surface");
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid='contract-workspace'][role='dialog']"))).isEmpty();
        assertThat(driver.findElements(By.cssSelector(".author-central-surface"))).hasSize(1);
        assertNoHorizontalOverflow(wait, By.cssSelector("[data-testid='contract-workspace']"));

        @SuppressWarnings("unchecked")
        Map<String, Number> mobileHeader = (Map<String, Number>)
                ((JavascriptExecutor) driver).executeScript("""
                        const surface = arguments[0];
                        const title = surface.querySelector('h2').getBoundingClientRect();
                        const actions = surface.querySelector(
                          '.contract-workspace-header-actions'
                        ).getBoundingClientRect();
                        return {
                          titleBottom: title.bottom,
                          actionsTop: actions.top,
                          actionsRight: actions.right,
                          surfaceRight: surface.getBoundingClientRect().right
                        };
                        """, evidence);
        assertThat(mobileHeader.get("actionsTop").doubleValue())
                .as("mobile Evidence actions start below the graph title")
                .isGreaterThanOrEqualTo(mobileHeader.get("titleBottom").doubleValue());
        assertThat(mobileHeader.get("actionsRight").doubleValue())
                .as("scrollable action strip stays inside the Evidence surface")
                .isLessThanOrEqualTo(mobileHeader.get("surfaceRight").doubleValue());
        assertThat(workspace.getAttribute("data-canvas-task-mode")).isEqualTo("inspect");
    }

    /**
     * Verifies the packaged task workspace projects the effective Contract before editing sources.
     *
     * <p>The browser loads a real example and proves that the selected transform exposes seven
     * visible, deduplicated edge sources with upstream coordinates. Four semantic edge bundles
     * preserve all twelve field dependencies without a label per edge. It then binds one Graph
     * Input field and verifies the bundle count remains stable while the represented dependency
     * count drops to eleven, proving that the replaced edge does not survive in export or
     * execution.</p>
     */
    @Test
    void taskWorkspaceBindsSchemaGeneratedRunInputWithoutCompetingNodeSourceInRealBrowser()
            throws Exception {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        setViewport(wait, 1280, 720);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=v2");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[aria-label='Close start dialog']"
        ))).click();
        driver.findElement(By.cssSelector(".author-secondary-actions button:first-child")).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-choice:examples']"
        ))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='author-start-example:loan-policy-fallback']"
        ))).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='author-start-dialog']")
        ));
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid='canvas-edge-label']"),
                4
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-visible-field-labels", "12"
        ));

        openDataInspector(wait);
        waitForText(wait, By.cssSelector("[data-testid='graph-run-input-panel']"),
                "Run Input Values");
        waitForText(wait, By.cssSelector("[data-testid='run-input-readiness']"),
                "1 required, complete");
        waitForText(wait, By.cssSelector("[data-testid='effective-contract-panel']"),
                "Effective data contract");
        waitForText(wait, By.cssSelector("[data-testid='effective-contract-panel']"),
                "bound");
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid='effective-input-source-row']"),
                7
        ));
        assertThat(textOf(By.cssSelector("[data-testid='effective-input-sources']")))
                .contains("Fetch applicant.payload.applicantId")
                .contains("Policy decision.output.decision");

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid='graph-input-bind:applicantId']"
        ))).click();
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid='canvas-edge-label']"),
                4
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector(".workspace-v2"), "data-canvas-visible-field-labels", "11"
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='node-input-key:0']"),
                "value",
                "applicantId"
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='node-input-kind:0']"),
                "value",
                "contextPath"
        ));
        wait.until(ExpectedConditions.attributeToBe(
                By.cssSelector("[data-testid='node-input-context-path:0']"),
                "value",
                "applicantId"
        ));

        WebElement export = driver.findElement(By.cssSelector(
                ".author-command-bar a[download$='-draft.json']"
        ));
        String draftJson = URLDecoder.decode(
                export.getAttribute("href").replace("data:application/json;charset=utf-8,", ""),
                StandardCharsets.UTF_8);
        JsonNode draft = OBJECT_MAPPER.readTree(draftJson);
        JsonNode selectedNode = java.util.stream.StreamSupport.stream(
                        draft.path("nodes").spliterator(), false)
                .filter(node -> "n5".equals(node.path("id").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(selectedNode.path("inputs").path("applicantId").path("kind").asText())
                .isEqualTo("contextPath");
        assertThat(selectedNode.path("inputs").path("applicantId").path("path").asText())
                .isEqualTo("applicantId");
        assertThat(draft.path("edges").size()).isEqualTo(11);
        waitForText(wait, By.cssSelector("[data-testid='effective-input-sources']"),
                "ctx.applicantId");
        assertNoHorizontalOverflow(wait, By.cssSelector(".workspace-v2"));
    }

    /**
     * Projects capability closures from every built-in example using the legacy example shelf.
     *
     * <p>Closure semantics are independent of the surrounding shell. This test therefore names
     * the legacy coordinate that owns {@code canvas-example-load:*}; v2 example discovery and
     * execution are covered separately through the Start dialog acceptance journey.</p>
     */
    @Test
    void everyBuiltInCanvasExampleProjectsAStableCapabilityClosureInRealBrowser() throws Exception {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/author/?authorWorkspace=legacy");

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".workspace")));
        Map<String, ExampleClosureExpectation> examples = Map.of(
                "loan-policy-fallback", new ExampleClosureExpectation("loanPolicyFallbackExample", 4),
                "order-fulfillment-lane", new ExampleClosureExpectation("orderFulfillmentLaneExample", 3),
                "personalized-dashboard", new ExampleClosureExpectation("personalizedDashboardExample", 5));

        for (Map.Entry<String, ExampleClosureExpectation> entry : examples.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            String selector = "[data-testid='canvas-example-load:" + entry.getKey() + "']";
            wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(selector))).click();
            ExampleClosureExpectation expected = entry.getValue();
            WebElement export = wait.until(ignored -> {
                WebElement candidate = driver.findElement(By.cssSelector("[data-testid='author-draft-export']"));
                return (expected.graphName() + "-draft.json").equals(candidate.getAttribute("download"))
                        ? candidate : null;
            });
            String draftJson = URLDecoder.decode(
                    export.getAttribute("href").replace("data:application/json;charset=utf-8,", ""),
                    StandardCharsets.UTF_8);
            assertThat(OBJECT_MAPPER.readTree(draftJson).path("graphName").asText())
                    .isEqualTo(expected.graphName());

            JsonNode first = projectCapabilityClosure(draftJson);
            JsonNode second = projectCapabilityClosure(draftJson);
            assertThat(first.path("payloadKind").asText()).isEqualTo("CAPABILITY_CLOSURE");
            assertThat(first.path("payload").path("rootRef").path("id").asText())
                    .isEqualTo("graph:" + expected.graphName());
            assertThat(first.path("payload").path("snapshots").size()).isEqualTo(expected.snapshotCount());
            assertThat(first.path("payload").path("fingerprint").asText())
                    .matches("sha256:[a-f0-9]{64}")
                    .isEqualTo(second.path("payload").path("fingerprint").asText());
            JsonNode root = java.util.stream.StreamSupport.stream(
                            first.path("payload").path("snapshots").spliterator(), false)
                    .filter(snapshot -> ("graph:" + expected.graphName())
                            .equals(snapshot.path("capabilityId").asText()))
                    .findFirst().orElseThrow();
            assertThat(root.path("scope").path("tenantId").asText()).isEqualTo("tenant-a");
            assertThat(root.path("scope").path("projectId").asText()).isEqualTo("tool-studio");
            assertThat(root.path("lifecycle").asText()).isEqualTo("DRAFT");
        }
    }

    @Test
    void reactShowcaseLoadsPackagedScenarioParityInRealBrowser() {
        assumeReactShowcaseBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/showcase/");

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-testid='react-showcase']")));
        assertThat(driver.getTitle()).contains("BLOGE Visual Canvas");
        waitForText(wait, By.cssSelector(".topbar"), "Run examples");
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector("[data-testid^='showcase-scenario:']"), 7
        ));
        waitForText(wait, By.cssSelector("[data-testid='showcase-detail']"), "User Dashboard");
        waitForText(wait, By.cssSelector("[data-testid='showcase-diagram']"), "6 nodes");
        waitForText(wait, By.cssSelector("[data-testid='showcase-node-inspector']"), "fetchProfile");

        driver.findElement(By.cssSelector("[data-testid='showcase-scenario:loanDecisionPolicy']")).click();

        waitForText(wait, By.cssSelector("[data-testid='showcase-detail']"), "Loan Decision Policy");
        waitForText(wait, By.cssSelector("[data-testid='showcase-detail']"),
                "/api/gateway/loan-policy/{applicantId}?amount={amount}");
        waitForText(wait, By.cssSelector("[data-testid='showcase-diagram']"), "3 nodes");
        driver.findElement(By.cssSelector("[data-testid='showcase-diagram-node:loanPolicy']")).click();
        waitForText(wait, By.cssSelector("[data-testid='showcase-node-inspector']"), "Loan Policy Matrix");
        waitForText(wait, By.cssSelector("[data-testid='showcase-decision-table']"), "unique");
        driver.findElement(By.cssSelector("[data-testid='showcase-run-button']")).click();
        waitForText(wait, By.cssSelector("[data-testid='showcase-run-result']"), "HTTP 200");
        waitForText(wait, By.cssSelector("[data-testid='showcase-run-receipt']"), "request");
        waitForText(wait, By.cssSelector("[data-testid='showcase-run-receipt']"), "GET");
        waitForText(wait, By.cssSelector("[data-testid='showcase-run-receipt']"),
                "/api/gateway/loan-policy/prime?amount=450000");
        waitForText(wait, By.cssSelector("[data-testid='showcase-run-result']"), "R1");
        waitForText(wait, By.cssSelector("[data-testid='showcase-run-result']"), "approved");
        assertNoHorizontalOverflow(wait, By.cssSelector(".showcase"));
    }

    @Test
    void scenarioRehearsalWorkbenchLoadsAsASeparateOperationalRouteInRealBrowser() {
        assumeReactRehearsalsBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/rehearsals/");

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='rehearsal-workbench']")));
        assertThat(driver.getTitle()).contains("BLOGE Visual Canvas");
        waitForText(wait, By.cssSelector(".topbar"), "Rehearsals");
        assertThat(driver.findElement(By.cssSelector("a[href='/rehearsals/']"))
                .getAttribute("aria-current")).isEqualTo("page");
        waitForText(wait, By.cssSelector(".rehearsal-queue"), "Rehearsal batches");
        waitForText(
                wait,
                By.cssSelector("[data-testid='remediation-workflow']"),
                "Reviewed remediation");
        waitForText(
                wait,
                By.cssSelector("[data-testid='remediation-identities']"),
                "Not connected");
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='remediation-workflow'] "
                        + "button.primary-command")).isEnabled())
                .as("reviewed remediation fails closed without a host human identity")
                .isFalse();
        driver.findElement(By.xpath(
                "//button[normalize-space()='Replace plans']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='replacement-table']")));
        assertNoHorizontalOverflow(wait, By.cssSelector(".rehearsal-workbench"));
        assertThat(driver.findElement(By.cssSelector(
                ".batch-queue-row .status-label"))
                .getCssValue("white-space"))
                .isEqualTo("nowrap");

        driver.manage().window().setSize(new Dimension(1024, 768));
        assertNoHorizontalOverflow(wait, By.cssSelector(".rehearsal-workbench"));
        driver.manage().window().setSize(new Dimension(760, 820));
        assertNoHorizontalOverflow(wait, By.cssSelector(".rehearsal-workbench"));
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='replacement-table']"))
                .getCssValue("overflow-x"))
                .isIn("auto", "scroll");
        driver.manage().window().setSize(new Dimension(390, 844));
        assertNoHorizontalOverflow(wait, By.cssSelector(".rehearsal-workbench"));
        assertThat(driver.findElement(By.cssSelector(
                ".remediation-timeline"))
                .getCssValue("overflow-x"))
                .isIn("auto", "scroll");
    }

    @Test
    void composerSupportsOpenApiSaveLibraryImportSchemaConnectionRunAndPublishInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        waitForText(wait, By.id("composer-canvas-hud"), "customLoanPolicy");
        waitForText(wait, By.id("composer-canvas-hud"), "2 nodes");
        waitForText(wait, By.id("composer-canvas-hud"), "1 link");
        waitForText(wait, By.id("composer-canvas-hud"), "Output Response");
        waitForText(wait, By.id("composer-canvas-hud"), "Inputs bound");
        waitForText(wait, By.id("composer-canvas-hud"), "READY QUEUE CLEAR");
        waitForText(wait, By.id("composer-canvas-hud"), "Simulate");
        assertThat(driver.findElements(By.cssSelector("[data-composer-core-action='simulate']")))
                .hasSize(1);
        wait.until(ignored -> svgTextContent(".node-port-summary").contains("In 2")
                && svgTextContent(".node-port-summary").contains("Out 5"));
        wait.until(ignored -> svgTextContent(".canvas-port-label").contains("score")
                && svgTextContent(".canvas-port-label").contains("decision"));

        click(wait, By.id("preview-resource-contract"));
        waitForText(wait,
                By.id("resource-contract-status-message"),
                "Projected contract loan-applicant-service.getProfile");
        assertThat(valueOf(By.id("resource-contract-json")))
                .contains("\"resourceId\": \"loan-applicant-service.getProfile\"")
                .contains("\"requestSchema\"")
                .contains("\"responseSchema\"");
        assertThat(valueOf(By.id("resource-descriptor-json")))
                .contains("\"resourceId\": \"loan-applicant-service.getProfile\"")
                .contains("\"urlTemplate\": \"https://api.example.test/api/loan-applicants/{applicantId}\"")
                .contains("\"applicantId\": \"ctx.params.applicantId\"");

        click(wait, By.id("save-resource-contract"));
        waitForText(wait,
                By.id("resource-contract-status-message"),
                "Saved loan-applicant-service.getProfile");

        importSampleOperatorLibrary(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("library-history-id")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("library-revision-select")));
        wait.until(ExpectedConditions.elementToBeClickable(By.id("reload-library-revisions")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("preview-library-revision")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("restore-library-revision")));
        click(wait, By.id("reload-library-revisions"));
        waitForText(wait, By.id("library-status"), "Loaded ");
        waitForText(wait, By.id("library-status"), "revisions for risk-policy");
        wait.until(ExpectedConditions.elementToBeClickable(By.id("preview-library-revision")));
        wait.until(ExpectedConditions.elementToBeClickable(By.id("restore-library-revision")));
        click(wait, By.id("preview-library-revision"));
        waitForText(wait, By.id("library-status"), "Previewing risk-policy@");

        WebElement search = wait.until(ExpectedConditions.elementToBeClickable(By.id("operator-palette-search")));
        search.clear();
        search.sendKeys("Eligibility");
        WebElement eligibility = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-operator-type='risk:eligibility']")
        ));
        WebElement diagram = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("diagram")));
        int before = driver.findElements(By.cssSelector("#diagram [data-node-id]")).size();

        dragOperatorToCanvas(eligibility, diagram, 140, 120);
        wait.until(ignored -> driver.findElements(By.cssSelector("#diagram [data-node-id]")).size() > before);
        assertThat(driver.findElements(By.cssSelector("#diagram [data-node-id='riskEligibility']")).size())
                .isGreaterThanOrEqualTo(1);
        wait.until(ignored -> {
            String summary = svgTextContent("#diagram [data-node-id='riskEligibility'] .node-port-summary");
            return summary.contains("In ") && summary.contains("Out ");
        });
        wait.until(ignored -> svgTextContent("#diagram [data-node-id='riskEligibility'] .canvas-port-label")
                .contains("eligible"));

        dragConnection(
                wait,
                "#diagram [data-node-id='loanPolicy'] [data-port-role='source'][data-port='output'][data-path='maxTerm']",
                "#diagram [data-node-id='riskEligibility'] [data-port-role='target'][data-port='inputs'][data-path='score']"
        );
        waitForText(wait, By.id("connection-status"),
                "Connected loanPolicy.output.maxTerm -> riskEligibility.inputs.score");

        selectByValue(wait, By.id("graph-output-node"), "riskEligibility");
        selectByValue(wait, By.id("graph-output-path"), "eligible");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        waitForText(wait, By.id("draft-dependencies"), "Draft Dependencies");
        waitForText(wait, By.id("draft-dependencies"), "risk:eligibility");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint current");
        click(wait, By.cssSelector("#draft-dependencies [data-draft-dependency-node='riskEligibility']"));
        waitForText(wait, By.id("selected-operator-editor"), "riskEligibility");

        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        waitForText(wait, By.id("draft-status"), "Exported");
        click(wait, By.id("import-draft"));
        waitForText(wait, By.id("draft-status"), "Imported");
        waitForText(wait, By.id("output"), "\"importedDraft\"");

        click(wait, By.id("run-scenario"));
        waitForText(wait, By.id("visual-check-status"), "Run completed.");
        waitForText(wait, By.id("visual-diagnostics"), "visual.graph.unreachableNode");
        waitForText(wait, By.id("output"), "\"success\": true");
        waitForText(wait, By.id("output"), "\"output\": false");
        waitForText(wait, By.id("run-history-list"), "TRANSIENT_DRAFT");
        waitForText(wait, By.id("run-history-list"), "SUCCESS");
        waitForText(wait, By.id("run-history-list"), "customLoanPolicy");
        waitForText(wait, By.id("run-history-stats"), "100%");
        waitForText(wait, By.id("run-history-stats"), "P95");
        waitForText(wait, By.id("run-history-trend"), "LATEST");
        waitForText(wait, By.id("run-history-trend"), "SUCCESS");

        publishVisualDraft(wait);
        String publicationId = valueOf(By.id("publication-select"));
        assertThat(publicationId).isNotBlank();
        setControlValue(driver.findElement(By.id("operator-palette-search")), "");
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#operator-palette [data-operator-type='publication:" + publicationId + "']")
        ));

        click(wait, By.cssSelector("#diagram [data-node-id='riskEligibility']"));
        waitForText(wait, By.id("selected-operator-editor"), "Eligibility");
        waitForText(wait, By.id("selected-operator-editor"), "USAGE");
        waitForText(wait, By.id("selected-operator-editor"), "Snapshot current");
        WebElement rebaseButton = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#selected-operator-editor [data-rebase-operator-fingerprint='riskEligibility']")
        ));
        assertThat(rebaseButton.isEnabled()).isFalse();
        click(wait, By.cssSelector("#selected-operator-editor [data-operator-usage='risk:eligibility']"));
        waitForText(wait, By.id("selected-operator-editor"), "draft usage");
        waitForText(wait, By.id("selected-operator-editor"), "publication usage");
        waitForText(wait, By.id("selected-operator-editor"), "DRAFTS");
        waitForText(wait, By.id("selected-operator-editor"), "PUBLICATIONS");
        waitForText(wait, By.id("selected-operator-editor"), "CURRENT");

        click(wait, By.id("run-publication"));
        waitForText(wait, By.id("publication-status"), "Ran");
        waitForText(wait, By.id("output"), "\"publicationRun\"");
        waitForText(wait, By.id("output"), "\"success\": true");
        waitForText(wait, By.id("output"), "\"output\": false");
        waitForText(wait, By.id("run-history-list"), "PUBLICATION");

        selectByValue(wait, By.id("golden-assertion-mode"), "OUTPUT_EQUALS");
        WebElement assertionValue = wait.until(ExpectedConditions.elementToBeClickable(
                By.id("golden-assertion-value")
        ));
        assertionValue.clear();
        assertionValue.sendKeys("false");
        click(wait, By.id("save-golden-case"));
        waitForText(wait, By.id("publication-status"), "Saved golden");
        waitForText(wait, By.id("golden-case-select"), "customLoanPolicy golden");

        click(wait, By.id("run-golden-case"));
        waitForText(wait, By.id("publication-status"), "passed");
        waitForText(wait, By.id("output"), "\"goldenCaseRun\"");
        waitForText(wait, By.id("output"), "\"passed\": true");

        click(wait, By.id("run-golden-suite"));
        waitForText(wait, By.id("publication-status"), "Golden suite passed");
        waitForText(wait, By.id("output"), "\"goldenSuiteRun\"");
        waitForText(wait, By.id("output"), "\"totalCases\": 1");

        click(wait, By.id("certify-golden-suite"));
        waitForText(wait, By.id("publication-status"), "Certified");
        waitForText(wait, By.id("golden-certification-status"), "Promotion ready");
        waitForText(wait, By.id("output"), "\"goldenCertification\"");
        waitForText(wait, By.id("output"), "\"certified\": true");

        useConfirm(true);
        click(wait, By.id("delete-golden-case"));
        waitForText(wait, By.id("publication-status"), "Deleted golden");
        waitForText(wait, By.id("golden-case-select"), "No golden cases");
        waitForText(wait, By.id("golden-certification-status"), "Missing golden cases");
        waitForText(wait, By.id("output"), "\"deletedGoldenCase\"");

        selectByValue(wait, By.id("run-history-source"), "PUBLICATION");
        waitForText(wait, By.id("run-history-list"), "PUBLICATION");
        assertThat(textOf(By.id("run-history-list"))).doesNotContain("TRANSIENT_DRAFT");

        setViewport(wait, 390, 980);
        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("openapi-resource-json"))));
        waitForText(wait, By.id("resource-contract-status-message"),
                "Saved loan-applicant-service.getProfile");
        assertNoHorizontalOverflow(wait, By.cssSelector(".resource-contract-controls"));
        assertNoHorizontalOverflow(wait, By.cssSelector(".resource-contract-actions"));
        assertNoHorizontalOverflow(wait, By.id("openapi-resource-json"));
        assertNoHorizontalOverflow(wait, By.id("resource-contract-json"));
        assertNoHorizontalOverflow(wait, By.id("resource-descriptor-json"));
        assertNoHorizontalOverflow(wait, By.id("resource-contract-status-message"));

        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("draft-bundle-json"))));
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector(".draft-controls"));
        assertNoHorizontalOverflow(wait, By.cssSelector(".draft-revision-controls"));
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector(".draft-transfer-controls"));
        assertNoHorizontalOverflow(wait, By.id("draft-dependencies"));
        assertNoHorizontalOverflow(wait, By.id("draft-bundle-json"));
        assertNoHorizontalOverflow(wait, By.id("draft-status"));

        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("publication-bundle-json"))));
        assertNoHorizontalOverflow(wait, By.id("publication-bundle-json"));
        assertNoHorizontalOverflow(wait, By.id("golden-case-select"));
        assertNoHorizontalOverflow(wait, By.cssSelector(".golden-assertion-controls"));
        assertNoHorizontalOverflow(wait, By.id("golden-assertion-list"));
        assertNoHorizontalOverflow(wait, By.id("golden-certification-status"));
        assertNoHorizontalOverflow(wait, By.id("publication-status"));

        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("run-history-list"))));
        assertNoHorizontalOverflow(wait, By.cssSelector(".run-history-controls"));
        assertNoHorizontalOverflow(wait, By.id("run-history-stats"));
        assertNoHorizontalOverflow(wait, By.id("run-history-trend"));
        assertNoHorizontalOverflow(wait, By.id("run-history-node-stats"));
        assertNoHorizontalOverflow(wait, By.id("run-history-list"));
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector(".run-history-row"));
        assertPageNoHorizontalOverflow();
    }

    @Test
    void composerRebasesFingerprintDriftFromDraftDependencyPanelInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.eligibilityLibrary("integer"));
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        waitForText(wait, By.id("draft-dependencies"), "Draft Dependencies");
        waitForText(wait, By.id("draft-dependencies"), "risk:eligibility");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint current");
        assertThat(driver.findElements(By.cssSelector(
                "#draft-dependencies [data-draft-dependency-rebase='riskEligibility']"
        ))).isEmpty();

        importOperatorLibrary(wait, eligibilityLibraryWithAdditionalOutput());
        waitForText(wait, By.id("library-status"), "Replaced risk-policy");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint drifted");
        waitForText(wait, By.id("draft-dependencies"), "Schema Rebase Queue");
        waitForText(wait, By.id("draft-dependencies"), "Repair review");
        waitForText(wait, By.id("draft-dependencies"), "Rebase 1");
        waitForText(wait, By.id("draft-dependencies"), "Rebase");
        assertThat(driver.findElements(By.cssSelector(
                "#draft-dependencies [data-schema-rebase-decision]"
                        + "[data-schema-rebase-decision-node-id='riskEligibility']"
        ))).isNotEmpty();

        click(wait, By.cssSelector("#diagram [data-node-id='riskEligibility']"));
        setControlValue(wait, By.cssSelector("[data-binding-expression][data-binding-path='score']"),
                "ctx.changedScore");
        waitForValue(wait, By.id("composer-dsl"), "ctx.changedScore");
        waitForText(wait, By.id("draft-dependencies"), "save or reload local changes before rebasing");
        WebElement dirtyBulkRebaseButton = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#draft-dependencies [data-schema-rebase-bulk]")
        ));
        assertThat(dirtyBulkRebaseButton.isEnabled()).isFalse();
        WebElement dirtyDependencyRebaseButton = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#draft-dependencies [data-draft-dependency-rebase='riskEligibility']")
        ));
        assertThat(dirtyDependencyRebaseButton.isEnabled()).isFalse();
        WebElement dirtyInspectorRebaseButton = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#selected-operator-editor [data-rebase-operator-fingerprint='riskEligibility']")
        ));
        assertThat(dirtyInspectorRebaseButton.isEnabled()).isFalse();
        waitForText(wait, By.id("selected-operator-editor"), "save or reload local changes before rebasing");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint drifted");
        wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("#draft-dependencies [data-draft-dependency-rebase='riskEligibility']")
        ));
        wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("#draft-dependencies [data-schema-rebase-bulk]")
        ));

        click(wait, By.cssSelector("#draft-dependencies [data-schema-rebase-bulk]"));
        waitForText(wait, By.id("draft-status"), "Rebased riskEligibility operator fingerprint");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint current");
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "#draft-dependencies [data-draft-dependency-rebase='riskEligibility']"
        )).isEmpty());
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "#draft-dependencies [data-schema-rebase-decision]"
        )).isEmpty());

        click(wait, By.cssSelector("#draft-dependencies [data-draft-dependency-node='riskEligibility']"));
        waitForText(wait, By.id("selected-operator-editor"), "riskEligibility");
        waitForText(wait, By.id("selected-operator-editor"), "Snapshot current");
    }

    @Test
    void composerOverlaysSchemaDriftOnSelectedOperatorContractInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.eligibilityLibrary("number"));
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint current");

        importOperatorLibrary(wait, VisualCatalogTestSupport.eligibilityLibrary("integer"));
        waitForText(wait, By.id("draft-dependencies"), "fingerprint drifted");
        waitForText(wait, By.id("draft-dependencies"), "schema breaking");
        waitForText(wait, By.id("draft-dependencies"), "score");
        waitForText(wait, By.id("draft-dependencies"), "Schema Rebase Queue");
        waitForText(wait, By.id("draft-dependencies"), "Repair review");
        waitForText(wait, By.id("draft-dependencies"), "Rebase 1");
        waitForText(wait, By.id("draft-dependencies"), "input.inputs.score");
        assertThat(driver.findElements(By.cssSelector(
                "#draft-dependencies [data-schema-rebase-decision]"
                        + "[data-schema-rebase-decision-state='repair-review']"
                        + "[data-schema-rebase-decision-node-id='riskEligibility']"
        ))).isNotEmpty();

        click(wait, By.cssSelector("#diagram [data-node-id='riskEligibility']"));
        waitForText(wait, By.id("selected-operator-editor"), "Schema Drift");
        waitForText(wait, By.id("selected-operator-editor"), "Schema Rebase Queue");
        waitForText(wait, By.id("selected-operator-editor"), "Repair review");
        waitForText(wait, By.id("selected-operator-editor"), "input.inputs.score");
        waitForText(wait, By.id("selected-operator-editor"), "1 breaking drift");
        waitForText(wait, By.id("selected-operator-editor"), "score:");
        waitForText(wait, By.id("selected-operator-editor"), "target type integer requires integer-valued source");
        waitForText(wait, By.id("selected-operator-editor"), "number -> integer");
        waitForText(wait, By.id("selected-operator-editor"), "type: number -> integer");
        waitForText(wait, By.id("selected-operator-editor"), "Review bindings before rebase");
        waitForText(wait, By.id("selected-operator-editor"), "Schema Review");
        waitForText(wait, By.id("selected-operator-editor"), "Frozen schema");
        waitForText(wait, By.id("selected-operator-editor"), "Current schema");
        waitForText(wait, By.id("selected-operator-editor"), "\"type\": \"number\"");
        waitForText(wait, By.id("selected-operator-editor"), "\"type\": \"integer\"");
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-schema-drift-review-row]"
                        + "[data-schema-drift-review-path='score']"
        ))).isNotEmpty();
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-schema-rebase-decision]"
                        + "[data-schema-rebase-decision-node-id='riskEligibility']"
        ))).isNotEmpty();
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-schema-outline-row]"
                        + "[data-schema-outline-path='score'][data-schema-drift='error']"
        ))).isNotEmpty();
        Map<String, String> driftOutlineA11y = schemaOutlineA11yState("score");
        assertThat(driftOutlineA11y)
                .containsEntry("containerRole", "list")
                .containsEntry("rowRole", "listitem")
                .containsEntry("rowDrift", "error")
                .containsEntry("metaLive", "polite")
                .containsEntry("searchControlsContainer", "true");
        assertThat(driftOutlineA11y.get("rowId")).startsWith("schema-outline-");
        assertThat(driftOutlineA11y.get("rowDescribedBy")).contains(driftOutlineA11y.get("rowId") + "-drift-0");
        assertThat(driftOutlineA11y.get("driftLabels"))
                .contains("Schema drift · Breaking")
                .contains("number -> integer")
                .contains("type: number -> integer")
                .contains("Review bindings before rebase")
                .contains("target type integer requires integer-valued source");
        assertThat(Integer.parseInt(driftOutlineA11y.get("setsize"))).isGreaterThanOrEqualTo(1);

        By schemaSearch = By.cssSelector("#selected-operator-editor [data-schema-outline-search]");
        sendKeysThroughRerenderedFocusedInput(wait, schemaSearch, "breaking");
        waitForFocusedValue(wait, schemaSearch, "breaking");
        waitForText(wait, By.id("selected-operator-editor"), "score");
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-schema-outline-row][data-schema-outline-path='amount']"
        )).isEmpty());
        sendKeysThroughRerenderedFocusedInput(wait, schemaSearch, "frozen integer");
        waitForFocusedValue(wait, schemaSearch, "frozen integer");
        waitForText(wait, By.id("selected-operator-editor"), "score");
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-drift-summary]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-drift-review]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-drift-preview-side]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-rebase-queue]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-rebase-decision]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-outline-row][data-schema-drift]"));

        setViewport(wait, 390, 980);
        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("selected-operator-editor"))));
        waitForText(wait, By.id("selected-operator-editor"), "1 breaking drift");
        waitForFocusedValue(wait, schemaSearch, "frozen integer");
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-drift-summary]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-drift-review]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-drift-preview-side]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-rebase-queue]"));
        assertVisibleElementsNoHorizontalOverflow(wait,
                By.cssSelector("#selected-operator-editor [data-schema-rebase-decision]"));
        assertNoHorizontalOverflow(wait, By.id("selected-operator-editor"));
        assertPageNoHorizontalOverflow();
    }

    @Test
    void composerRecoversFromDependencyPanelRebaseRevisionConflictInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.eligibilityLibrary("integer"));
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint current");

        String draftId = valueOf(By.id("draft-select"));
        long savedRevision = currentDraftRevisionFromStatus();

        importOperatorLibrary(wait, eligibilityLibraryWithAdditionalOutput());
        waitForText(wait, By.id("draft-dependencies"), "fingerprint drifted");
        waitForText(wait, By.id("draft-dependencies"), "Rebase");

        patchDraftGraphNameInBrowser(draftId, savedRevision, "serverAdvancedPolicy");

        click(wait, By.cssSelector("#draft-dependencies [data-draft-dependency-rebase='riskEligibility']"));
        waitForText(wait, By.id("draft-status"), "Draft revision conflict");
        waitForText(wait, By.id("draft-status"), "Review the latest draft dependencies before rebasing.");
        waitForValue(wait, By.id("composer-dsl"), "graph serverAdvancedPolicy");
        waitForText(wait, By.id("draft-dependencies"), draftId + "@" + (savedRevision + 1));
        waitForText(wait, By.id("draft-dependencies"), "fingerprint drifted");
        waitForText(wait, By.id("draft-dependencies"), "Rebase");

        click(wait, By.cssSelector("#draft-dependencies [data-draft-dependency-rebase='riskEligibility']"));
        waitForText(wait, By.id("draft-status"), "Rebased riskEligibility operator fingerprint");
        waitForValue(wait, By.id("composer-dsl"), "graph serverAdvancedPolicy");
        waitForText(wait, By.id("draft-dependencies"), draftId + "@" + (savedRevision + 2));
        waitForText(wait, By.id("draft-dependencies"), "fingerprint current");
    }

    @Test
    void composerShowsCatalogMissingInDraftDependencyPanelWithoutRebaseActionInRealBrowser()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.eligibilityLibrary("integer"));
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint current");

        if (!driver.findElement(By.id("library-force")).isSelected()) {
            click(wait, By.id("library-force"));
        }
        useConfirm(true);
        click(wait, By.id("delete-library"));

        waitForText(wait, By.id("library-status"), "Deleted risk-policy");
        waitForText(wait, By.id("draft-dependencies"), "catalog missing");
        waitForText(wait, By.id("draft-dependencies"), "risk:eligibility");
        assertThat(driver.findElements(By.cssSelector(
                "#draft-dependencies [data-draft-dependency-rebase='riskEligibility']"
        ))).isEmpty();

        click(wait, By.cssSelector("#draft-dependencies [data-draft-dependency-node='riskEligibility']"));
        waitForText(wait, By.id("selected-operator-editor"), "Unavailable: Risk:eligibility");
        waitForText(wait, By.id("selected-operator-editor"), "Operator unavailable");
        waitForText(wait, By.id("selected-operator-editor"), "current missing");
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-rebase-operator-fingerprint='riskEligibility']"
        ))).isEmpty();
    }

    @Test
    void composerShowsScopeMismatchInDraftDependencyPanelWithoutRebaseActionInRealBrowser()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.eligibilityLibrary("integer",
                new OperatorDefinition.Policy(List.of("demo-tenant"), List.of("local"), List.of("prod"))));
        assertThat(driver.findElements(By.cssSelector(
                "#operator-palette [data-operator-type='risk:eligibility']"
        ))).isEmpty();

        setControlValue(driver.findElement(By.id("scope-environment")), "prod");
        click(wait, By.id("apply-scope"));
        waitForText(wait, By.id("scope-status"), "demo-tenant / local / prod");
        waitForText(wait, By.id("operator-palette"), "Eligibility");

        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);

        setControlValue(driver.findElement(By.id("scope-environment")), "browser");
        click(wait, By.id("apply-scope"));
        waitForText(wait, By.id("scope-status"), "demo-tenant / local / browser");
        waitForText(wait, By.id("selected-operator-editor"), "Operator unavailable");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        waitForText(wait, By.id("draft-dependencies"), "scope mismatch");
        waitForText(wait, By.id("draft-dependencies"), "scope environment 'browser' is not in [prod]");
        assertThat(driver.findElements(By.cssSelector(
                "#draft-dependencies [data-draft-dependency-rebase='riskEligibility']"
        ))).isEmpty();
    }

    @Test
    void composerImportsYamlOperatorLibraryAndUsesItOnCanvasInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        importYamlOperatorLibrary(wait, library);
        assertThat(valueOf(By.id("operator-library-json")))
                .contains("\"libraryId\": \"risk-policy\"")
                .contains("\"operatorRef\": \"risk:eligibility\"");

        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#diagram [data-node-id='riskEligibility'] "
                        + "[data-port-role='target'][data-port='inputs'][data-path='score']")
        ));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#diagram [data-node-id='riskEligibility'] "
                        + "[data-port-role='source'][data-port='output'][data-path='eligible']")
        ));
        click(wait, By.cssSelector("#diagram [data-node-id='riskEligibility']"));
        waitForText(wait, By.id("selected-operator-editor"), "Eligibility");
        waitForText(wait, By.id("selected-operator-editor"), "2 required");
        waitForText(wait, By.id("selected-operator-editor"), "object · 2 fields · 0 required");

        setViewport(wait, 390, 980);
        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("selected-operator-editor"))));
        waitForText(wait, By.id("selected-operator-editor"), "Eligibility");
        assertNoHorizontalOverflow(wait, By.id("operator-palette"));
        assertNoHorizontalOverflow(wait, By.cssSelector(".palette-controls"));
        assertNoHorizontalOverflow(wait, By.cssSelector(".diagram-panel"));
        assertNoHorizontalOverflow(wait, By.id("selected-operator-editor"));
        assertPageNoHorizontalOverflow();
    }

    @Test
    void composerShowsComplexImportedOperatorSchemaOutlineInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, complexSchemaOutlineLibrary());
        waitForText(wait, By.id("library-profile"), "risk-complex-schema");
        waitForText(wait, By.id("library-profile"), "Complex risk intake");

        dragOperatorToCanvas(wait, "Complex risk intake", "risk:complexIntake",
                "riskComplexIntake", 140, 120);
        click(wait, By.cssSelector("#diagram [data-node-id='riskComplexIntake']"));
        waitForText(wait, By.id("selected-operator-editor"), "Complex risk intake");
        waitForText(wait, By.id("selected-operator-editor"), "customer.profile.id");
        waitForText(wait, By.id("selected-operator-editor"), "pattern ^risk[A-Z].*");
        waitForText(wait, By.id("selected-operator-editor"), "additionalProperties *");
        waitForText(wait, By.id("selected-operator-editor"), "events.contains");
        waitForText(wait, By.id("selected-operator-editor"), "payload.oneOf[0]");
        waitForText(wait, By.id("selected-operator-editor"), "dependentRequired paymentMethod");
        waitForText(wait, By.id("selected-operator-editor"), "Showing first 24 of");
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-schema-outline-row][data-schema-outline-path='customer.profile.id']"
        ))).isNotEmpty();
        Map<String, String> outlineA11y = schemaOutlineA11yState("customer.profile.id");
        assertThat(outlineA11y)
                .containsEntry("containerRole", "list")
                .containsEntry("rowRole", "listitem")
                .containsEntry("metaLive", "polite")
                .containsEntry("searchControlsContainer", "true");
        assertThat(outlineA11y.get("rowId")).startsWith("schema-outline-");
        assertThat(outlineA11y.get("searchDescribedBy")).isEqualTo(outlineA11y.get("metaId"));
        assertThat(Integer.parseInt(outlineA11y.get("pos"))).isGreaterThanOrEqualTo(1);
        assertThat(Integer.parseInt(outlineA11y.get("setsize"))).isGreaterThanOrEqualTo(
                Integer.parseInt(outlineA11y.get("pos")));
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor [data-schema-outline]"));
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor [data-schema-outline-row]"));

        By schemaSearch = By.cssSelector("#selected-operator-editor [data-schema-outline-search]");
        sendKeysThroughRerenderedFocusedInput(wait, schemaSearch, "customer.payment.cardExpiry");
        waitForFocusedValue(wait, schemaSearch, "customer.payment.cardExpiry");
        waitForText(wait, By.id("selected-operator-editor"), "customer.payment.cardExpiry");
        waitForText(wait, By.id("selected-operator-editor"), "1/");
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-schema-outline-row][data-schema-outline-path='customer.payment.cardExpiry']"
        ))).isNotEmpty();
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-schema-outline-row][data-schema-outline-path='events.contains']"
        )).isEmpty());
        click(wait, By.cssSelector("#selected-operator-editor [data-schema-outline-search-clear]"));
        waitForValue(wait, schemaSearch, "");
        waitForFocusedValue(wait, schemaSearch, "");
        waitForText(wait, By.id("selected-operator-editor"), "events.contains");

        setViewport(wait, 390, 980);
        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("selected-operator-editor"))));
        waitForText(wait, By.id("selected-operator-editor"), "customer.profile.id");
        waitForText(wait, By.id("selected-operator-editor"), "pattern ^risk[A-Z].*");
        sendKeysThroughRerenderedFocusedInput(wait, schemaSearch, "payload.oneOf[1].autoScore");
        waitForFocusedValue(wait, schemaSearch, "payload.oneOf[1].autoScore");
        waitForText(wait, By.id("selected-operator-editor"), "payload.oneOf[1].autoScore");
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor [data-schema-outline-search-panel]"));
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor [data-schema-outline]"));
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor [data-schema-outline-row]"));
        assertNoHorizontalOverflow(wait, By.id("selected-operator-editor"));
        assertPageNoHorizontalOverflow();
    }

    @Test
    void composerShowsServerCandidateSchemaAndRuntimeDebtInConnectabilityPanelInRealBrowser()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, scoreReviewDesignOnlyLibrary());
        dragOperatorToCanvas(wait, "Score review", "risk:scoreReview", "riskScoreReview", 140, 120);

        click(wait, By.cssSelector("#diagram [data-node-id='loanPolicy']"));
        waitForText(wait, By.id("selected-operator-editor"), "CONNECTABILITY");
        waitForText(wait, By.id("selected-operator-editor"), "Server candidates synced");
        waitForText(wait, By.id("selected-operator-editor"), "Score review (riskScoreReview)");
        waitForText(wait, By.id("selected-operator-editor"), "any -> integer");
        waitForText(wait, By.id("selected-operator-editor"), "target runtime binding requirement");
        waitForText(wait, By.id("selected-operator-editor"), "Executable Lowering: 1");
        waitForText(wait, By.id("selected-operator-editor"), "risk-score-design library: 1");
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor .node-connectability-chip-detail"
        ))).isNotEmpty();
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node='riskScoreReview']"
                        + "[data-connect-target-port='inputs']"
                        + "[data-connect-target-path='score']"
        ))).isNotEmpty();

        By filterQuery = By.cssSelector("#selected-operator-editor [data-connectability-filter-query]");
        By filterStatus = By.cssSelector("#selected-operator-editor [data-connectability-filter-status]");
        sendKeysThroughRerenderedFocusedInput(wait, filterQuery, "runtime");
        waitForText(wait, By.id("selected-operator-editor"), "matches");
        waitForText(wait, By.id("selected-operator-editor"), "target runtime binding requirement");
        waitForFocusedValue(wait, filterQuery, "runtime");

        selectByValue(wait, filterStatus, "ready");
        waitForValue(wait, filterStatus, "ready");
        waitForText(wait, By.id("selected-operator-editor"), "Score review (riskScoreReview)");

        selectByValue(wait, filterStatus, "blocked");
        waitForValue(wait, filterStatus, "blocked");
        waitForText(wait, By.id("selected-operator-editor"), "No matching targets");
        assertThat(driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node='riskScoreReview']"
        ))).isEmpty();

        click(wait, By.cssSelector("#selected-operator-editor [data-connectability-filter-clear]"));
        waitForValue(wait, filterQuery, "");
        waitForFocusedValue(wait, filterQuery, "");
        waitForText(wait, By.id("selected-operator-editor"), "Score review (riskScoreReview)");
        waitForText(wait, By.id("selected-operator-editor"), "target runtime binding requirement");
    }

    @Test
    @Timeout(150)
    void composerConnectabilityHandlesLargeTargetWindowInRealBrowser()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, scoreReviewLargeConnectabilityLibrary());
        addScoreSourceAndReviewTargetsInBrowser(wait, 260);

        waitForText(wait, By.id("selected-operator-editor"), "CONNECTABILITY");
        waitForText(wait, By.id("selected-operator-editor"), "1 source ·");
        waitForConnectabilityServerReady(new WebDriverWait(driver, Duration.ofSeconds(30)),
                "riskScoreSource", 0);
        waitForText(wait, By.id("selected-operator-editor"), "partial server window 1-250 of 260");
        waitForText(wait, By.id("selected-operator-editor"), "local fallback beyond server window");
        waitForText(wait, By.id("selected-operator-editor"), "Window 1-250 of 260");
        waitForText(wait, By.id("selected-operator-editor"), "Showing first 24 of 260 ready targets");
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node^='riskScoreReview']"
        )).size() == 24);
        Map<String, String> firstA11yState = connectabilityVisibleTargetA11yState(0);
        assertThat(firstA11yState)
                .containsEntry("activeMatches", "true")
                .containsEntry("current", "true")
                .containsEntry("pos", "1")
                .containsEntry("setsize", "260")
                .containsEntry("containerRole", "group")
                .containsEntry("rowNextControlsContainer", "true");
        click(wait, By.cssSelector("#selected-operator-editor [data-connectability-row-window='next']"));
        waitForText(wait, By.id("selected-operator-editor"), "Showing 25-48 of 260 ready targets");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node='riskScoreReview25']"
        )));
        Map<String, String> secondWindowA11yState = connectabilityTargetA11yState("riskScoreReview25");
        assertThat(secondWindowA11yState)
                .containsEntry("activeMatches", "true")
                .containsEntry("pos", "25")
                .containsEntry("setsize", "260");
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node^='riskScoreReview']"
        )).size() == 24);
        WebElement lastVisibleRowTarget = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node='riskScoreReview48']"
        )));
        lastVisibleRowTarget.sendKeys(Keys.ARROW_RIGHT);
        waitForText(wait, By.id("selected-operator-editor"), "Showing 49-72 of 260 ready targets");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node='riskScoreReview49']"
        )));
        Map<String, String> keyboardWindowA11yState = connectabilityTargetA11yState("riskScoreReview49");
        assertThat(keyboardWindowA11yState)
                .containsEntry("activeMatches", "true")
                .containsEntry("pos", "49")
                .containsEntry("setsize", "260");
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-panel"));

        By filterQuery = By.cssSelector("#selected-operator-editor [data-connectability-filter-query]");
        By filterStatus = By.cssSelector("#selected-operator-editor [data-connectability-filter-status]");
        By filterSchema = By.cssSelector("#selected-operator-editor [data-connectability-filter-facet='schemaType']");
        By filterLowering = By.cssSelector("#selected-operator-editor [data-connectability-filter-facet='loweringMode']");
        sendKeysThroughRerenderedFocusedInput(wait, filterQuery, "riskScoreReview260");
        waitForConnectabilityServerReady(new WebDriverWait(driver, Duration.ofSeconds(30)),
                "riskScoreSource", 0, "riskScoreReview260");
        selectByValue(wait, filterStatus, "ready");
        waitForValue(wait, filterStatus, "ready");
        waitForConnectabilityServerReady(new WebDriverWait(driver, Duration.ofSeconds(30)),
                "riskScoreSource", 0, "riskScoreReview260", "ready");
        selectByValue(wait, filterSchema, "integer");
        waitForValue(wait, filterSchema, "integer");
        waitForConnectabilityServerReady(new WebDriverWait(driver, Duration.ofSeconds(30)),
                "riskScoreSource", 0, "riskScoreReview260", "ready", "schemaType=integer");
        selectByValue(wait, filterLowering, "transform");
        waitForValue(wait, filterLowering, "transform");
        waitForConnectabilityServerReady(new WebDriverWait(driver, Duration.ofSeconds(30)),
                "riskScoreSource", 0, "riskScoreReview260", "ready", "loweringMode=transform|schemaType=integer");
        waitForText(wait, By.id("selected-operator-editor"), "1/260 matching candidate");
        WebElement globalQueryLateTarget = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node='riskScoreReview260']"
        )));
        assertThat(globalQueryLateTarget.getText())
                .contains("Score review (riskScoreReview260)")
                .contains("integer -> integer");
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-panel"));

        click(wait, By.cssSelector("#selected-operator-editor [data-connectability-filter-clear]"));
        waitForValue(wait, filterQuery, "");
        waitForConnectabilityServerReady(new WebDriverWait(driver, Duration.ofSeconds(30)),
                "riskScoreSource", 0);

        click(wait, By.cssSelector("#selected-operator-editor [data-connectability-window='next']"));
        waitForConnectabilityServerReady(new WebDriverWait(driver, Duration.ofSeconds(30)),
                "riskScoreSource", 250);
        waitForText(wait, By.id("selected-operator-editor"), "Window 251-260 of 260");
        waitForText(wait, By.id("selected-operator-editor"), "partial server window 251-260 of 260");
        WebElement lateTarget = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node='riskScoreReview260']"
        )));
        assertThat(lateTarget.getText())
                .contains("Score review (riskScoreReview260)")
                .contains("integer -> integer");
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-panel"));

        setViewport(wait, 390, 980);
        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("selected-operator-editor"))));
        waitForText(wait, By.id("selected-operator-editor"), "CONNECTABILITY");
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-panel"));
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-filter-controls"));
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-targets"));
        assertNoHorizontalOverflow(wait, By.cssSelector(
                "#selected-operator-editor [data-connectability-action='connect']"
                        + "[data-connect-target-node='riskScoreReview260']"
        ));
        assertPageNoHorizontalOverflow();
    }

    @Test
    void composerConnectabilityWindowsLargeSourceHandleSetInRealBrowser()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, scoreReviewLargeSourceConnectabilityLibrary());
        addScoreMatrixSourceAndSingleReviewTargetInBrowser(wait);

        waitForText(wait, By.id("selected-operator-editor"), "CONNECTABILITY");
        waitForText(wait, By.id("selected-operator-editor"), "40 sources ·");
        waitForText(wait, By.id("selected-operator-editor"), "Showing first 8 of 40 source endpoints");
        waitForConnectabilityServerReady(new WebDriverWait(driver, Duration.ofSeconds(30)),
                "riskScoreMatrixSource", 0);
        wait.until(ignored -> connectabilityServerSourceKeys()
                .equals(expectedMetricSourceKeys(1, 8)));
        assertThat(connectabilitySourceRowLabels()).containsExactlyElementsOf(expectedMetricSourceLabels(1, 8));
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-panel"));

        click(wait, By.cssSelector("#selected-operator-editor [data-connectability-source-window='next']"));
        waitForText(wait, By.id("selected-operator-editor"), "Showing 9-16 of 40 source endpoints");
        wait.until(ignored -> connectabilityServerSourceKeys()
                .equals(expectedMetricSourceKeys(9, 16)));
        assertThat(connectabilitySourceRowLabels()).containsExactlyElementsOf(expectedMetricSourceLabels(9, 16));
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-panel"));

        click(wait, By.cssSelector("#selected-operator-editor [data-connectability-source-window='next']"));
        waitForText(wait, By.id("selected-operator-editor"), "Showing 17-24 of 40 source endpoints");
        wait.until(ignored -> connectabilityServerSourceKeys()
                .equals(expectedMetricSourceKeys(17, 24)));

        click(wait, By.cssSelector("#selected-operator-editor [data-connectability-source-window='next']"));
        waitForText(wait, By.id("selected-operator-editor"), "Showing 25-32 of 40 source endpoints");
        wait.until(ignored -> connectabilityServerSourceKeys()
                .equals(expectedMetricSourceKeys(25, 32)));

        click(wait, By.cssSelector("#selected-operator-editor [data-connectability-source-window='next']"));
        waitForText(wait, By.id("selected-operator-editor"), "Showing 33-40 of 40 source endpoints");
        wait.until(ignored -> connectabilityServerSourceKeys()
                .equals(expectedMetricSourceKeys(33, 40)));
        assertThat(connectabilitySourceRowLabels()).containsExactlyElementsOf(expectedMetricSourceLabels(33, 40));
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-panel"));

        setViewport(wait, 390, 980);
        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("selected-operator-editor"))));
        waitForText(wait, By.id("selected-operator-editor"), "Showing 33-40 of 40 source endpoints");
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-panel"));
        assertNoHorizontalOverflow(wait, By.cssSelector("#selected-operator-editor .node-connectability-filter-controls"));
        assertNoHorizontalOverflow(wait, By.cssSelector(
                "#selected-operator-editor .node-connectability-source-window-controls"
        ));
        assertVisibleElementsNoHorizontalOverflow(wait, By.cssSelector(
                "#selected-operator-editor .node-connectability-row"
        ));
        assertPageNoHorizontalOverflow();
    }

    @Test
    void composerValidatesExportedOperatorLibraryBundleInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importSampleOperatorLibrary(wait);
        click(wait, By.id("export-library"));
        waitForText(wait, By.id("library-status"), "Exported risk-policy@");
        waitForText(wait, By.id("library-status"), "sha256:");
        assertThat(valueOf(By.id("operator-library-json")))
                .contains("\"schemaVersion\": \"bloge.visualOperatorLibraryExport.v1\"")
                .contains("\"bundleFingerprint\": \"sha256:")
                .contains("\"libraryId\": \"risk-policy\"");
        int revisionCountBeforeValidate = operatorLibraryRegistry.revisions("risk-policy").size();

        click(wait, By.id("validate-library-bundle"));
        waitForText(wait, By.id("library-status"), "Bundle would replace risk-policy");
        waitForText(wait, By.id("library-status"), "sha256:");
        waitForText(wait, By.id("output"), "\"operatorLibraryBundleValidation\"");
        waitForText(wait, By.id("output"), "\"imported\": false");
        waitForText(wait, By.id("output"), "\"mutationAction\": \"REPLACE\"");
        waitForText(wait, By.id("output"), "\"targetDiff\"");
        waitForText(wait, By.id("library-bundle-diff"), "Bundle Target Diff");
        waitForText(wait, By.id("library-bundle-diff"), "No library or operator surface changes.");
        assertThat(operatorLibraryRegistry.find("risk-policy")).isPresent();
        assertThat(operatorLibraryRegistry.revisions("risk-policy"))
                .hasSize(revisionCountBeforeValidate);
    }

    @Test
    void composerProjectsAsyncApiIntoOperatorLibraryInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        WebElement editor = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-library-json")));
        setControlValue(editor, """
                asyncapi: '2.6.0'
                info:
                  title: Risk Events
                  version: 1.2.3
                  contact:
                    name: risk-platform
                channels:
                  /webhooks/credit-decision:
                    subscribe:
                      operationId: creditDecisionWebhook
                      x-bloge-source-kind: webhook
                      bindings:
                        http:
                          method: post
                      message:
                        name: CreditDecision
                        payload:
                          type: object
                          properties:
                            applicationId:
                              type: string
                            decision:
                              type: string
                          required:
                            - applicationId
                            - decision
                  risk.commands:
                    publish:
                      operationId: sendRiskCommand
                      message:
                        name: RiskCommand
                        headers:
                          type: object
                          properties:
                            tenantId:
                              type: string
                            traceId:
                              type: string
                          required:
                            - tenantId
                        payload:
                          type: object
                          properties:
                            commandId:
                              type: string
                            score:
                              type: integer
                          required:
                            - commandId
                  risk.audit:
                    subscribe:
                      operationId: riskAuditEvent
                      message:
                        name: RiskAudit
                        payload:
                          type: object
                          properties:
                            auditId:
                              type: string
                """);

        click(wait, By.id("discover-asyncapi-library"));
        waitForText(wait, By.id("library-status"), "Discovered 3 AsyncAPI operations");
        waitForText(wait, By.id("asyncapi-operation-select"), "headers object");
        Select asyncApiSelect = new Select(
                wait.until(ExpectedConditions.elementToBeClickable(By.id("asyncapi-operation-select")))
        );
        asyncApiSelect.selectByValue("0");
        asyncApiSelect.selectByValue("1");
        waitForText(wait, By.id("asyncapi-operation-summary"), "2 AsyncAPI operations selected");
        waitForText(wait, By.id("asyncapi-operation-summary"), "1 with headers");

        click(wait, By.id("project-asyncapi-library"));
        waitForText(wait, By.id("library-status"), "Projected AsyncAPI into risk-events-operators");
        waitForText(wait, By.id("library-status"), "Projected 2 of 3 AsyncAPI operations");
        waitForText(wait, By.id("asyncapi-projection-review"), "AsyncAPI Projection Review");
        waitForText(wait, By.id("asyncapi-projection-review"), "partial coverage");
        waitForText(wait, By.id("asyncapi-projection-review"), "omitted operation");
        waitForText(wait, By.id("asyncapi-projection-review"), "RiskAudit");
        waitForText(wait, By.id("library-profile"), "runtime-blocked");
        assertThat(valueOf(By.id("operator-library-json")))
                .contains("\"schemaVersion\": \"bloge.visualOperatorLibrary.v1\"")
                .contains("\"libraryId\": \"risk-events-operators\"")
                .contains("\"CreditDecision\"")
                .contains("\"RiskCommand\"")
                .contains("\"kind\": \"webhook\"")
                .contains("\"kind\": \"message-handler\"")
                .contains("\"mode\": \"message-handler\"")
                .contains("\"name\": \"headers\"")
                .contains("\"headersType\": \"object\"")
                .doesNotContain("RiskAudit");

        setViewport(wait, 390, 980);
        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-library-json"))));
        waitForText(wait, By.id("asyncapi-projection-review"), "AsyncAPI Projection Review");
        assertNoHorizontalOverflow(wait, By.cssSelector(".library-controls"));
        assertNoHorizontalOverflow(wait, By.id("asyncapi-operation-select"));
        assertNoHorizontalOverflow(wait, By.id("asyncapi-operation-summary"));
        assertNoHorizontalOverflow(wait, By.id("operator-library-json"));
        assertNoHorizontalOverflow(wait, By.id("library-profile"));
        assertNoHorizontalOverflow(wait, By.id("asyncapi-projection-review"));
        assertPageNoHorizontalOverflow();

        click(wait, By.id("import-library"));
        waitForAnyText(wait, By.id("library-status"),
                "Imported risk-events-operators",
                "Replaced risk-events-operators");
        waitForText(wait, By.id("operator-palette"), "CreditDecision");
        waitForText(wait, By.id("operator-palette"), "RiskCommand");
        assertThat(driver.findElement(By.id("operator-palette")).getText())
                .doesNotContain("RiskAudit");

        scrollIntoView(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette"))));
        assertNoHorizontalOverflow(wait, By.id("operator-palette"));
        assertPageNoHorizontalOverflow();
    }

    @Test
    void composerRejectsSchemaIncompatibleConnectionInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importSampleOperatorLibrary(wait);

        WebElement search = wait.until(ExpectedConditions.elementToBeClickable(By.id("operator-palette-search")));
        search.clear();
        search.sendKeys("Eligibility");
        WebElement eligibility = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-operator-type='risk:eligibility']")
        ));
        WebElement diagram = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("diagram")));
        dragOperatorToCanvas(eligibility, diagram, 140, 120);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#diagram [data-node-id='riskEligibility']")
        ));
        int edgeCount = driver.findElements(By.cssSelector("#diagram path.edge")).size();

        dragConnection(
                wait,
                "#diagram [data-node-id='loanPolicy'] [data-port-role='source'][data-port='output'][data-path='decision']",
                "#diagram [data-node-id='riskEligibility'] [data-port-role='target'][data-port='inputs'][data-path='score']"
        );
        waitForText(wait, By.id("connection-status"), "Cannot bind string output");
        waitForText(wait, By.id("connection-status"), "source type string cannot feed target type integer");
        assertThat(driver.findElements(By.cssSelector("#diagram path.edge")).size()).isEqualTo(edgeCount);
        assertThat(valueOf(By.id("composer-dsl")))
                .doesNotContain("loanPolicy.output.decision >= 700")
                .contains("ctx.score >= 700");
    }

    @Test
    void composerClearsTransformPolicyBindingInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        assertThat(valueOf(By.id("composer-dsl"))).contains("policy          = loanPolicy.output");

        click(wait, By.cssSelector("#diagram [data-node-id='response']"));
        waitForText(wait, By.id("selected-operator-editor"), "Transform");
        waitForValue(wait, By.cssSelector("[data-node-field='policyNode']"), "loanPolicy");

        setControlValue(driver.findElement(By.cssSelector("[data-node-field='policyNode']")), "");
        wait.until(ignored -> valueOf(By.cssSelector("[data-node-field='policyNode']")).isBlank());
        wait.until(ignored -> valueOf(By.id("composer-dsl")).contains("result = {}"));
        assertThat(valueOf(By.id("composer-dsl"))).doesNotContain("policy          = loanPolicy.output");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        assertThat(valueOf(By.id("draft-bundle-json")))
                .contains("\"result\": \"{}\"")
                .doesNotContain("\"policy\": \"loanPolicy.output\"");
    }

    @Test
    void composerDeletesNodeAndCleansDownstreamBindingsInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importSampleOperatorLibrary(wait);
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);

        dragConnection(
                wait,
                "#diagram [data-node-id='loanPolicy'] [data-port-role='source'][data-port='output'][data-path='maxTerm']",
                "#diagram [data-node-id='riskEligibility'] [data-port-role='target'][data-port='inputs'][data-path='score']"
        );
        waitForText(wait, By.id("connection-status"),
                "Connected loanPolicy.output.maxTerm -> riskEligibility.inputs.score");

        click(wait, By.cssSelector("#diagram [data-node-id='riskEligibility']"));
        waitForValue(wait, By.cssSelector("[data-binding-expression][data-binding-path='score']"),
                "loanPolicy.output.maxTerm");

        click(wait, By.cssSelector("#diagram [data-node-id='loanPolicy']"));
        waitForText(wait, By.id("selected-operator-editor"), "Decision Table");
        click(wait, By.id("delete-operator"));
        wait.until(ignored -> driver.findElements(By.cssSelector("#diagram [data-node-id='loanPolicy']")).isEmpty());

        click(wait, By.cssSelector("#diagram [data-node-id='riskEligibility']"));
        waitForValue(wait, By.cssSelector("[data-binding-expression][data-binding-path='score']"), "ctx.score");
        assertThat(valueOf(By.id("composer-dsl"))).doesNotContain("loanPolicy.output.maxTerm");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        assertThat(valueOf(By.id("draft-bundle-json")))
                .doesNotContain("loanPolicy.output.maxTerm")
                .doesNotContain("\"nodeId\": \"loanPolicy\"");
    }

    @Test
    void composerRendersServerValidationDiagnosticsInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importSampleOperatorLibrary(wait);

        WebElement search = wait.until(ExpectedConditions.elementToBeClickable(By.id("operator-palette-search")));
        search.clear();
        search.sendKeys("Eligibility");
        WebElement eligibility = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-operator-type='risk:eligibility']")
        ));
        WebElement diagram = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("diagram")));
        dragOperatorToCanvas(eligibility, diagram, 140, 120);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#diagram [data-node-id='riskEligibility']")
        ));

        click(wait, By.cssSelector("[data-clear-binding][data-binding-path='score']"));
        click(wait, By.cssSelector("[data-clear-binding][data-binding-path='amount']"));

        click(wait, By.id("validate-visual-draft"));
        waitForText(wait, By.id("visual-check-status"), "Visual graph has errors.");
        waitForText(wait, By.id("visual-diagnostics"), "visual.input.required");
        waitForText(wait, By.id("visual-diagnostics"), "score");
        waitForText(wait, By.id("visual-diagnostics"), "amount");
        waitForText(wait, By.id("composer-canvas-hud"), "READINESS QUEUE");
        waitForText(wait, By.id("composer-canvas-hud"), "Eligibility");
        waitForText(wait, By.id("composer-canvas-hud"), "validation diagnostic");
        waitForText(wait, By.id("output"), "\"valid\": false");
    }

    @Test
    void composerSupportsConfigDependencyAndRouteConnectionDragsInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.routeLibrary());
        importOperatorLibrary(wait, VisualCatalogTestSupport.configurablePolicyLibrary());

        dragOperatorToCanvas(wait, "Type route", "risk:typeRoute", "riskTypeRoute", 140, 120);
        dragOperatorToCanvas(wait, "Score facts", "risk:scoreFacts", "riskScoreFacts", 140, 120);
        dragOperatorToCanvas(wait, "Configurable policy", "risk:configurablePolicy",
                "riskConfigurablePolicy", 140, 120);

        dragConnection(
                wait,
                "#diagram [data-node-id='loanPolicy'] [data-port-role='source'][data-port='output'][data-path='decision']",
                "#diagram [data-node-id='riskTypeRoute'] [data-port-role='target'][data-port='inputs'][data-path='value']"
        );
        waitForText(wait, By.id("connection-status"),
                "Connected loanPolicy.output.decision -> riskTypeRoute.inputs.value");

        usePromptValue("approved");
        dragConnection(
                wait,
                "#diagram [data-node-id='riskTypeRoute'] [data-port-role='source'][data-port='route'][data-path='']",
                "#diagram [data-node-id='riskScoreFacts'] [data-port-role='target'][data-port='route'][data-path='']"
        );
        waitForText(wait, By.id("connection-status"),
                "Connected riskTypeRoute.route -> riskScoreFacts.route");

        dragConnection(
                wait,
                "#diagram [data-node-id='loanPolicy'] [data-port-role='source'][data-port='output'][data-path='maxTerm']",
                "#diagram [data-node-id='riskScoreFacts'] [data-port-role='target'][data-port='dependency'][data-path='']"
        );
        waitForText(wait, By.id("connection-status"),
                "Connected loanPolicy.output.maxTerm -> riskScoreFacts.dependency");

        dragConnection(
                wait,
                "#diagram [data-node-id='loanPolicy'] [data-port-role='source'][data-port='output'][data-path='maxTerm']",
                "#diagram [data-node-id='riskConfigurablePolicy'] [data-port-role='target'][data-port='config'][data-path='threshold']"
        );
        waitForText(wait, By.id("connection-status"),
                "Connected loanPolicy.output.maxTerm -> riskConfigurablePolicy.config.threshold");
        waitForValue(wait, By.cssSelector("[data-config-expression][data-config-field='threshold']"),
                "loanPolicy.output.maxTerm");
        waitForText(wait, By.id("selected-operator-editor"),
                "Config expression bound to loanPolicy.output.maxTerm.");

        selectByValue(wait, By.cssSelector("select[aria-label='Mode config']"), "strict");

        click(wait, By.id("validate-visual-draft"));
        waitForText(wait, By.id("visual-check-status"), "Valid visual graph.");

        assertThat(valueOf(By.id("composer-dsl")))
                .contains("branch on riskTypeRoute.output.value")
                .contains("\"approved\" -> riskScoreFacts")
                .contains("depends_on = [loanPolicy]");
    }

    @Test
    void composerPersistsConfigUnionBranchSelectionInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, configUnionPolicyLibrary());
        dragOperatorToCanvas(wait, "Config union policy", "risk:configUnionPolicy",
                "riskConfigUnionPolicy", 140, 120);

        click(wait, By.cssSelector("#diagram [data-node-id='riskConfigUnionPolicy']"));
        waitForText(wait, By.id("selected-operator-editor"), "Config union policy");

        selectByValue(wait, By.cssSelector("[data-config-union-branch][data-config-field='payload']"),
                "oneOf:0");
        waitForValue(wait, By.cssSelector("[data-config-union-branch][data-config-field='payload']"),
                "oneOf:0");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "input[data-config-field='payload.score']"
        )));
        setControlValue(wait, By.cssSelector("input[data-config-field='payload.score']"), "720");
        waitForValue(wait, By.cssSelector("input[data-config-field='payload.score']"), "720");

        selectByValue(wait, By.id("graph-output-node"), "riskConfigUnionPolicy");
        selectByValue(wait, By.id("graph-output-path"), "accepted");
        click(wait, By.id("validate-visual-draft"));
        waitForText(wait, By.id("visual-check-status"), "Valid visual graph.");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        String bundle = valueOf(By.id("draft-bundle-json"));
        assertThat(bundle)
                .contains("\"configUnionBranches\"")
                .contains("\"keyword\": \"oneOf\"")
                .contains("\"index\": 0")
                .contains("\"payload\"")
                .contains("\"score\": 720");

        click(wait, By.id("import-draft"));
        waitForText(wait, By.id("draft-status"), "Imported");
        click(wait, By.cssSelector("#diagram [data-node-id='riskConfigUnionPolicy']"));
        waitForValue(wait, By.cssSelector("[data-config-union-branch][data-config-field='payload']"),
                "oneOf:0");
        waitForValue(wait, By.cssSelector("input[data-config-field='payload.score']"), "720");
    }

    @Test
    void composerSupportsDraftRevisionPreviewRestoreAndDeleteInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        assertThat(valueOf(By.id("composer-dsl"))).contains("score >= 760");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "@1");

        WebElement firstScoreRule = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-rule-index='0'][data-rule-field='score']")
        ));
        setControlValue(firstScoreRule, "score >= 780");
        waitForValue(wait, By.cssSelector("[data-rule-index='0'][data-rule-field='score']"), "score >= 780");
        waitForValue(wait, By.id("composer-dsl"), "score >= 780");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "@2");

        click(wait, By.id("reload-revisions"));
        selectByValue(wait, By.id("draft-revision-select"), "1");
        click(wait, By.id("preview-revision"));
        waitForText(wait, By.id("draft-status"), "Previewing revision @1");
        waitForValue(wait, By.id("composer-dsl"), "score >= 760");
        assertThat(valueOf(By.id("composer-dsl"))).doesNotContain("score >= 780");

        click(wait, By.id("restore-revision"));
        waitForText(wait, By.id("draft-status"), "Restored @1 as @3");
        waitForValue(wait, By.id("composer-dsl"), "score >= 760");

        useConfirm(true);
        click(wait, By.id("delete-draft"));
        waitForText(wait, By.id("draft-status"), "Deleted");
        waitForText(wait, By.id("draft-status"), "history remains available for preview and restore");
        String deletedDraftId = valueOf(By.id("draft-select"));
        assertThat(deletedDraftId).isNotBlank();
        assertThat(textOf(By.id("draft-select"))).contains("deleted");
        assertThat(driver.findElement(By.id("load-draft")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("delete-draft")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("export-draft")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("reload-revisions")).isEnabled()).isTrue();
        assertThat(driver.findElement(By.id("preview-revision")).isEnabled()).isTrue();
        assertThat(driver.findElement(By.id("restore-revision")).isEnabled()).isTrue();
    }

    @Test
    void composerHidesDslUnsafeSchemaPathFieldsInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        setControlValue(driver.findElement(By.id("graph-input-schema")), """
                {
                  "type": "object",
                  "properties": {
                    "customer-id": { "type": "integer" },
                    "safeScore": { "type": "integer" }
                  },
                  "required": ["customer-id"]
                }
                """);
        waitForText(wait, By.id("graph-input-schema-diagnostics"), "visual.inputSchema.dslField.invalid");

        setControlValue(driver.findElement(By.id("graph-input-schema")), """
                {
                  "type": "object",
                  "properties": {
                    "safeScore": { "type": "integer" }
                  },
                  "additionalProperties": true
                }
                """);
        waitForText(wait, By.id("graph-input-schema-status"), "Graph input schema is valid");
        setControlValue(driver.findElement(By.id("composer-context")), """
                {
                  "safeScore": 720,
                  "customer-id": 730
                }
                """);

        importSampleOperatorLibrary(wait);
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        List<String> scoreSourceOptions = optionTexts(wait, By.cssSelector(
                "[data-binding-source][data-binding-path='score'] option"));
        assertThat(scoreSourceOptions).anyMatch(option -> option.contains("ctx.safeScore"));
        assertThat(scoreSourceOptions).noneMatch(option -> option.contains("ctx.customer-id"));

        importOperatorLibrary(wait, unsafeOutputLibrary());
        dragOperatorToCanvas(wait, "Unsafe facts", "risk:unsafeFacts", "riskUnsafeFacts", 140, 120);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#diagram [data-node-id='riskUnsafeFacts'] [data-port-role='source'][data-path='safeId']")
        ));
        assertThat(driver.findElements(By.cssSelector(
                "#diagram [data-node-id='riskUnsafeFacts'] [data-port-role='source'][data-path='customer-id']")))
                .isEmpty();
    }

    @Test
    void composerPublishesSchemaOnlyOperatorAsDesignArtifactInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#diagram [data-node-id='riskEligibility'] "
                        + "[data-port-role='target'][data-port='inputs'][data-path='score']")
        ));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#diagram [data-node-id='riskEligibility'] "
                        + "[data-port-role='source'][data-port='output'][data-path='eligible']")
        ));
        selectByValue(wait, By.id("graph-output-node"), "riskEligibility");
        selectByValue(wait, By.id("graph-output-path"), "eligible");

        click(wait, By.id("validate-visual-draft"));
        waitForText(wait, By.id("visual-check-status"), "DESIGN artifact");
        waitForText(wait, By.id("visual-readiness-panel"), "Design Artifact Path");
        waitForText(wait, By.id("visual-readiness-panel"), "Publish DESIGN after ackWarnings plus actor/reason.");
        waitForText(wait, By.id("visual-readiness-panel"), "ackWarnings + actor/reason");
        waitForText(wait, By.id("visual-readiness-panel"),
                "Graph is publishable, but publication requires warning acknowledgement and governance evidence.");
        assertThat(valueOf(By.id("publish-artifact-kind"))).isEqualTo("DESIGN");
        assertThat(driver.findElement(By.cssSelector(
                "#publish-artifact-kind option[value='EXECUTABLE']"
        )).getAttribute("disabled")).isNotNull();
        assertThat(driver.findElement(By.id("compile-visual-draft")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("run-scenario")).isEnabled()).isFalse();

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        waitForText(wait, By.id("draft-asset-summary"), "Draft Asset Index");
        waitForText(wait, By.id("draft-asset-summary"), "Design only");
        waitForText(wait, By.id("draft-asset-summary"), "Server-derived draft readiness is visible before loading a draft.");
        waitForText(wait, By.id("visual-asset-overview"), "Workspace Asset Overview");
        waitForText(wait, By.id("visual-asset-overview"), "Design-only drafts");
        waitForText(wait, By.id("visual-asset-overview"), "Action Queue");
        waitForText(wait, By.id("visual-asset-overview"), "suggested actions");
        List<WebElement> overviewActions = driver.findElements(
                By.cssSelector("#visual-asset-overview [data-visual-asset-action]"));
        assertThat(overviewActions).isNotEmpty();
        String actionTargetKind = overviewActions.getFirst()
                .getAttribute("data-visual-asset-action-target-kind");
        assertThat(actionTargetKind).isIn("draft", "publication", "operator");
        overviewActions.getFirst().click();
        waitForText(wait, By.id("visual-asset-overview"), "Current action");
        switch (actionTargetKind) {
            case "draft" -> waitForText(wait, By.id("draft-status"), "Opened overview action");
            case "publication" -> waitForText(wait, By.id("publication-status"), "Opened overview action");
            case "operator" -> waitForText(wait, By.id("visual-check-status"), "Opened overview action");
            default -> throw new AssertionError("Unsupported overview action target kind: " + actionTargetKind);
        }
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        assertThat(valueOf(By.id("draft-bundle-json")))
                .contains("\"risk:eligibility\"")
                .contains("\"operatorSnapshots\"")
                .contains("\"operatorFingerprints\"");

        publishVisualDraft(wait);
        String publicationId = valueOf(By.id("publication-select"));
        assertThat(publicationId).isNotBlank();
        waitForText(wait, By.id("publication-select"), "DESIGN");
        waitForText(wait, By.id("publication-asset-summary"), "Published Artifact Index");
        waitForText(wait, By.id("publication-asset-summary"), "DESIGN");
        waitForText(wait, By.id("publication-asset-summary"), "Design only");
        waitForText(wait, By.id("publication-asset-summary"), "Frozen publication readiness is visible before selecting an artifact.");
        waitForText(wait, By.id("visual-asset-overview"), "Design publications");
        assertThat(driver.findElement(By.id("run-publication")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("save-golden-case")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("run-golden-case")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("run-golden-suite")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("certify-golden-suite")).isEnabled()).isFalse();

        setControlValue(driver.findElement(By.id("operator-palette-search")), "");
        assertThat(driver.findElements(By.cssSelector(
                "#operator-palette [data-operator-type='publication:" + publicationId + "']")))
                .isEmpty();
    }

    @Test
    void composerExposesSchemaArrayIndexOutputPathsInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, arrayOutputLibrary());
        dragOperatorToCanvas(wait, "Array facts", "risk:arrayFacts", "riskArrayFacts", 140, 120);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#diagram [data-node-id='riskArrayFacts'] [data-port-role='source'][data-port='output'][data-path='items.0']"
        )));

        selectByValue(wait, By.id("graph-output-node"), "riskArrayFacts");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(
                "#graph-output-path option[value='items.0']"
        )));
        List<String> outputPathValues = driver.findElements(By.cssSelector("#graph-output-path option"))
                .stream()
                .map(option -> option.getAttribute("value"))
                .toList();

        assertThat(outputPathValues).contains("items", "items.0");

        importSampleOperatorLibrary(wait);
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        dragConnection(
                wait,
                "#diagram [data-node-id='riskArrayFacts'] [data-port-role='source'][data-port='output'][data-path='items.0']",
                "#diagram [data-node-id='riskEligibility'] [data-port-role='target'][data-port='inputs'][data-path='score']"
        );
        waitForText(wait, By.id("connection-status"),
                "Connected riskArrayFacts.output.items.0 -> riskEligibility.inputs.score");
        waitForValue(wait, By.id("composer-dsl"), "eligible = riskArrayFacts.output.items[0] >= 700");
        assertThat(valueOf(By.id("composer-dsl")))
                .doesNotContain("riskArrayFacts.output.items.0 >= 700");
    }

    @Test
    void composerDefaultsUnboundOptionalLoweringTemplateInputsInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, optionalLoweringInputLibrary());
        dragOperatorToCanvas(wait, "Optional boost", "risk:optionalBoost", "riskOptionalBoost", 140, 120);

        waitForValue(wait, By.id("composer-dsl"), "result = ctx.score + null");
        assertThat(valueOf(By.id("composer-dsl"))).doesNotContain("{{");
    }

    @Test
    void composerBindsDynamicAdditionalPropertyContextFieldsInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        setControlValue(driver.findElement(By.id("graph-input-schema")), """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": { "type": "integer" }
                }
                """);
        waitForText(wait, By.id("graph-input-schema-status"), "Graph input schema is valid");
        setControlValue(driver.findElement(By.id("composer-context")), """
                {
                  "dynamicScore": 720,
                  "dynamicAmount": 250000
                }
                """);

        importSampleOperatorLibrary(wait);
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-path='score']"),
                bindingSourceValue("__ctx", "ctx", "dynamicScore"));
        waitForText(wait, By.id("connection-status"), "Connected ctx.dynamicScore");
        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-path='amount']"),
                bindingSourceValue("__ctx", "ctx", "dynamicAmount"));
        waitForText(wait, By.id("connection-status"), "Connected ctx.dynamicAmount");

        waitForValue(wait, By.id("composer-dsl"),
                "eligible = ctx.dynamicScore >= 700 && ctx.dynamicAmount <= 300000");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        assertThat(valueOf(By.id("draft-bundle-json")))
                .contains("\"path\": \"dynamicScore\"")
                .contains("\"path\": \"dynamicAmount\"")
                .contains("\"targetPath\": \"score\"")
                .contains("\"targetPath\": \"amount\"");
    }

    @Test
    void composerBindsDynamicPatternPropertyContextFieldsInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        setControlValue(driver.findElement(By.id("graph-input-schema")), """
                {
                  "type": "object",
                  "properties": {},
                  "patternProperties": {
                    "^risk[A-Z].*": { "type": "integer" }
                  },
                  "additionalProperties": false
                }
                """);
        waitForText(wait, By.id("graph-input-schema-status"), "Graph input schema is valid");
        setControlValue(driver.findElement(By.id("composer-context")), """
                {
                  "riskScore": 720,
                  "riskAmount": 250000,
                  "ignored": 1
                }
                """);

        importSampleOperatorLibrary(wait);
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        List<String> scoreSourceOptions = optionTexts(wait, By.cssSelector(
                "[data-binding-source][data-binding-path='score'] option"));
        assertThat(scoreSourceOptions).anyMatch(option -> option.contains("ctx.riskScore"));
        assertThat(scoreSourceOptions).anyMatch(option -> option.contains("ctx.riskAmount"));
        assertThat(scoreSourceOptions).noneMatch(option -> option.contains("ctx.ignored"));

        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-path='score']"),
                bindingSourceValue("__ctx", "ctx", "riskScore"));
        waitForText(wait, By.id("connection-status"), "Connected ctx.riskScore");
        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-path='amount']"),
                bindingSourceValue("__ctx", "ctx", "riskAmount"));
        waitForText(wait, By.id("connection-status"), "Connected ctx.riskAmount");

        waitForValue(wait, By.id("composer-dsl"),
                "eligible = ctx.riskScore >= 700 && ctx.riskAmount <= 300000");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        assertThat(valueOf(By.id("draft-bundle-json")))
                .contains("\"path\": \"riskScore\"")
                .contains("\"path\": \"riskAmount\"")
                .contains("\"targetPath\": \"score\"")
                .contains("\"targetPath\": \"amount\"");
    }

    @Test
    void composerFiltersDynamicContextFieldsByPropertyNamesInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        setControlValue(driver.findElement(By.id("graph-input-schema")), """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": { "type": "integer" },
                  "propertyNames": { "pattern": "^risk[A-Z].*" }
                }
                """);
        waitForText(wait, By.id("graph-input-schema-status"), "Graph input schema is valid");
        setControlValue(driver.findElement(By.id("composer-context")), """
                {
                  "riskScore": 720,
                  "riskAmount": 250000,
                  "badScore": 720
                }
                """);

        importSampleOperatorLibrary(wait);
        dragOperatorToCanvas(wait, "Eligibility", "risk:eligibility", "riskEligibility", 140, 120);
        List<String> scoreSourceOptions = optionTexts(wait, By.cssSelector(
                "[data-binding-source][data-binding-path='score'] option"));
        assertThat(scoreSourceOptions).anyMatch(option -> option.contains("ctx.riskScore"));
        assertThat(scoreSourceOptions).anyMatch(option -> option.contains("ctx.riskAmount"));
        assertThat(scoreSourceOptions).noneMatch(option -> option.contains("ctx.badScore"));

        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-path='score']"),
                bindingSourceValue("__ctx", "ctx", "riskScore"));
        waitForText(wait, By.id("connection-status"), "Connected ctx.riskScore");
        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-path='amount']"),
                bindingSourceValue("__ctx", "ctx", "riskAmount"));
        waitForText(wait, By.id("connection-status"), "Connected ctx.riskAmount");

        waitForValue(wait, By.id("composer-dsl"),
                "eligible = ctx.riskScore >= 700 && ctx.riskAmount <= 300000");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        assertThat(valueOf(By.id("draft-bundle-json")))
                .contains("\"path\": \"riskScore\"")
                .contains("\"path\": \"riskAmount\"")
                .contains("\"targetPath\": \"score\"")
                .contains("\"targetPath\": \"amount\"")
                .doesNotContain("\"path\": \"badScore\"");
    }

    @Test
    void composerPreservesDuplicateInputPathPortsInRealBrowser() throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        setControlValue(
                driver.findElement(By.id("graph-input-schema")),
                OBJECT_MAPPER.writeValueAsString(customerOrderGraphInputSchema())
        );
        waitForText(wait, By.id("graph-input-schema-status"), "Graph input schema is valid");

        importOperatorLibrary(wait, VisualCatalogTestSupport.duplicateInputPathLibrary());
        dragOperatorToCanvas(wait, "Customer order merge", "risk:customerOrderMerge",
                "riskCustomerOrderMerge", 140, 120);

        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-port='customer'][data-binding-path='id']"),
                bindingSourceValue("__ctx", "ctx", "customer.id"));
        waitForText(wait, By.id("connection-status"), "Connected ctx.customer.id");

        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-port='order'][data-binding-path='id']"),
                bindingSourceValue("__ctx", "ctx", "orderId"));
        waitForText(wait, By.id("connection-status"), "Connected ctx.orderId");

        waitForValue(wait, By.id("composer-dsl"), "customerId = ctx.customer.id");
        waitForValue(wait, By.id("composer-dsl"), "orderId = ctx.orderId");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        String bundle = valueOf(By.id("draft-bundle-json"));
        assertThat(bundle)
                .contains("\"customer.id\"")
                .contains("\"order.id\"")
                .contains("\"targetPort\": \"customer\"")
                .contains("\"targetPort\": \"order\"")
                .contains("\"targetPath\": \"id\"");
    }

    @Test
    void composerAddsAndExportsDuplicateDynamicUnevaluatedInputPathsInRealBrowser()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        setControlValue(driver.findElement(By.id("graph-input-schema")), """
                {
                  "type": "object",
                  "properties": {},
                  "unevaluatedProperties": { "type": "integer" }
                }
                """);
        waitForText(wait, By.id("graph-input-schema-status"), "Graph input schema is valid");
        setControlValue(driver.findElement(By.id("composer-context")), """
                {
                  "dynamicScore": 720
                }
                """);

        importOperatorLibrary(wait, VisualCatalogTestSupport.dynamicUnevaluatedDuplicateInputLibrary());
        dragOperatorToCanvas(wait, "Dynamic map merge", "risk:dynamicMapMerge",
                "riskDynamicMapMerge", 140, 120);

        addDynamicInputBinding(wait, "primary", "dynamicScore");
        addDynamicInputBinding(wait, "secondary", "dynamicScore");

        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-port='primary'][data-binding-path='dynamicScore']"),
                bindingSourceValue("__ctx", "ctx", "dynamicScore"));
        waitForText(wait, By.id("connection-status"),
                "Connected ctx.dynamicScore -> riskDynamicMapMerge.primary.dynamicScore");
        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-port='secondary'][data-binding-path='dynamicScore']"),
                bindingSourceValue("__ctx", "ctx", "dynamicScore"));
        waitForText(wait, By.id("connection-status"),
                "Connected ctx.dynamicScore -> riskDynamicMapMerge.secondary.dynamicScore");

        waitForValue(wait, By.id("composer-dsl"), "primaryScore = ctx.dynamicScore");
        waitForValue(wait, By.id("composer-dsl"), "secondaryScore = ctx.dynamicScore");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        String bundle = valueOf(By.id("draft-bundle-json"));
        assertThat(bundle)
                .contains("\"primary.dynamicScore\"")
                .contains("\"secondary.dynamicScore\"")
                .contains("\"targetPort\": \"primary\"")
                .contains("\"targetPort\": \"secondary\"")
                .contains("\"targetPath\": \"dynamicScore\"");
    }

    @Test
    void composerAddsDynamicInputPathAlongsideDeclaredSchemaFieldsInRealBrowser()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));
        setControlValue(driver.findElement(By.id("graph-input-schema")), """
                {
                  "type": "object",
                  "properties": {
                    "fixedScore": { "type": "integer" }
                  },
                  "required": ["fixedScore"],
                  "unevaluatedProperties": { "type": "integer" }
                }
                """);
        waitForText(wait, By.id("graph-input-schema-status"), "Graph input schema is valid");
        setControlValue(driver.findElement(By.id("composer-context")), """
                {
                  "fixedScore": 700,
                  "dynamicScore": 720
                }
                """);

        importOperatorLibrary(wait, VisualCatalogTestSupport.mixedDeclaredDynamicInputLibrary());
        dragOperatorToCanvas(wait, "Mixed dynamic scorer", "risk:mixedDynamicScorer",
                "riskMixedDynamicScorer", 140, 120);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "[data-binding-source][data-binding-port='facts'][data-binding-path='fixedScore']"
        )));

        addDynamicInputBinding(wait, "facts", "dynamicScore");
        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-port='facts'][data-binding-path='dynamicScore']"),
                bindingSourceValue("__ctx", "ctx", "dynamicScore"));
        waitForText(wait, By.id("connection-status"),
                "Connected ctx.dynamicScore -> riskMixedDynamicScorer.facts.dynamicScore");

        waitForValue(wait, By.id("composer-dsl"), "fixedScore = ctx.fixedScore");
        waitForValue(wait, By.id("composer-dsl"), "dynamicScore = ctx.dynamicScore");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        String bundle = valueOf(By.id("draft-bundle-json"));
        assertThat(bundle)
                .contains("\"fixedScore\"")
                .contains("\"dynamicScore\"")
                .contains("\"targetPort\": \"facts\"")
                .contains("\"targetPath\": \"dynamicScore\"");
    }

    @Test
    void composerAddsBindsExportsAndRestoresDynamicUnevaluatedOutputPathInRealBrowser()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.dynamicUnevaluatedOutputLibrary());
        dragOperatorToCanvas(wait, "Dynamic facts", "risk:dynamicFacts",
                "riskDynamicFacts", 140, 120);
        dragOperatorToCanvas(wait, "Score sink", "risk:scoreSink",
                "riskScoreSink", 140, 120);

        click(wait, By.cssSelector("#diagram [data-node-id='riskDynamicFacts']"));
        waitForText(wait, By.id("selected-operator-editor"), "Dynamic facts");
        addDynamicOutputPath(wait, "riskDynamicFacts", "facts", "dynamicScore");

        click(wait, By.cssSelector("#diagram [data-node-id='riskScoreSink']"));
        waitForText(wait, By.id("selected-operator-editor"), "Score sink");
        selectByValue(wait,
                By.cssSelector("[data-binding-source][data-binding-port='inputs'][data-binding-path='score']"),
                bindingSourceValue("riskDynamicFacts", "facts", "dynamicScore"));
        waitForText(wait, By.id("connection-status"),
                "Connected riskDynamicFacts.facts.dynamicScore -> riskScoreSink.inputs.score");

        waitForValue(wait, By.id("composer-dsl"),
                "acceptedScore = riskDynamicFacts.output.facts.dynamicScore");

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
        click(wait, By.id("export-draft"));
        wait.until(ignored -> valueOf(By.id("draft-bundle-json"))
                .contains("\"schemaVersion\": \"bloge.visualGraphDraftExport.v1\""));
        String bundle = valueOf(By.id("draft-bundle-json"));
        assertThat(bundle)
                .contains("\"nodeId\": \"riskDynamicFacts\"")
                .contains("\"sourcePort\": \"facts\"")
                .contains("\"path\": \"dynamicScore\"")
                .contains("\"targetPath\": \"score\"");

        click(wait, By.id("import-draft"));
        waitForText(wait, By.id("draft-status"), "Imported");
        click(wait, By.cssSelector("#diagram [data-node-id='riskDynamicFacts']"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#diagram [data-node-id='riskDynamicFacts'] [data-port-role='source'][data-port='facts'][data-path='dynamicScore']"
        )));
        click(wait, By.cssSelector("#diagram [data-node-id='riskScoreSink']"));
        waitForValue(wait,
                By.cssSelector("[data-binding-expression][data-binding-port='inputs'][data-binding-path='score']"),
                "riskDynamicFacts.output.facts.dynamicScore");
    }

    @Test
    void composerRejectsDynamicRootObjectOutputWhenItCanCollideWithTargetOptionalProperty()
            throws JsonProcessingException {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

        importOperatorLibrary(wait, VisualCatalogTestSupport.dynamicOptionalCollisionLibrary());
        dragOperatorToCanvas(wait, "Dynamic string facts", "risk:dynamicStringFacts",
                "riskDynamicStringFacts", 140, 120);
        dragOperatorToCanvas(wait, "Optional score sink", "risk:optionalScoreSink",
                "riskOptionalScoreSink", 140, 120);

        click(wait, By.cssSelector("#diagram [data-node-id='riskOptionalScoreSink']"));
        waitForText(wait, By.id("selected-operator-editor"), "Optional score sink");
        By rootBindingSource = By.cssSelector(
                "[data-binding-source][data-binding-port='inputs'][data-binding-path='']");
        wait.until(ExpectedConditions.visibilityOfElementLocated(rootBindingSource));
        List<String> rootSourceOptions = optionTexts(wait, By.cssSelector(
                "[data-binding-source][data-binding-port='inputs'][data-binding-path=''] option"));
        assertThat(rootSourceOptions)
                .anySatisfy(option -> assertThat(option)
                        .contains("riskDynamicStringFacts.facts")
                        .contains("source type string cannot feed target type integer"));
        int edgeCount = driver.findElements(By.cssSelector("#diagram path.edge")).size();

        selectByValue(wait, rootBindingSource,
                bindingSourceValue("riskDynamicStringFacts", "facts", ""));

        waitForText(wait, By.id("connection-status"),
                "source type string cannot feed target type integer");
        assertThat(driver.findElements(By.cssSelector("#diagram path.edge")).size()).isEqualTo(edgeCount);
        wait.until(ignored -> valueOf(By.cssSelector(
                "[data-binding-expression][data-binding-port='inputs'][data-binding-path='']")).isBlank());
        assertThat(valueOf(By.id("composer-dsl")))
                .doesNotContain("riskDynamicStringFacts.output.facts");
    }

    private WebDriver newChromeDriverOrSkip() {
        if (chromeWebDriverUnavailableReason != null) {
            Assumptions.abort(chromeWebDriverUnavailableReason);
        }
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--no-sandbox",
                "--remote-debugging-pipe",
                "--window-size=1440,1100"
        );
        if (Files.isExecutable(MAC_CHROME_BINARY)) {
            options.setBinary(MAC_CHROME_BINARY.toString());
        }
        Path chromeDriver = chromeDriverExecutableOrSkip();
        System.setProperty("webdriver.chrome.driver", chromeDriver.toString());
        ChromeDriverService service = new ChromeDriverService.Builder()
                .usingDriverExecutable(chromeDriver.toFile())
                .usingAnyFreePort()
                .withTimeout(WEBDRIVER_TIMEOUT)
                .withSilent(true)
                .build();
        ChromeDriver browser = null;
        try {
            browser = BoundedBrowserSessionLauncher.launch(
                    WEBDRIVER_TIMEOUT,
                    () -> new ChromeDriver(service, options, webdriverClientConfig()),
                    service::stop,
                    VisualAuthoringBrowserDomTest::quitQuietly);
            browser.manage().timeouts()
                    .pageLoadTimeout(WEBDRIVER_TIMEOUT)
                    .scriptTimeout(WEBDRIVER_TIMEOUT)
                    .implicitlyWait(Duration.ZERO);
            driverProcessTree = ScopedProcessTree.captureUnique(
                    Path.of(service.getExecutable()),
                    "--port=" + service.getUrl().getPort());
            driverService = service;
            return browser;
        } catch (BoundedBrowserSessionLauncher.LaunchException ex) {
            chromeWebDriverUnavailableReason = "Chrome/WebDriver session launch is unavailable: "
                    + ex.disposition();
            Assumptions.abort(chromeWebDriverUnavailableReason);
            return null;
        } catch (WebDriverException | ScopedProcessTree.ScopeException ex) {
            BoundedBrowserSessionLauncher.cleanupAfterFailedLaunch(
                    browser, service::stop, VisualAuthoringBrowserDomTest::quitQuietly);
            chromeWebDriverUnavailableReason = "Chrome/WebDriver session setup is unavailable";
            Assumptions.abort(chromeWebDriverUnavailableReason);
            return null;
        }
    }

    private static void quitQuietly(WebDriver browser) {
        if (browser == null) {
            return;
        }
        try {
            browser.quit();
        } catch (WebDriverException ignored) {
            // The session has already been abandoned; setup diagnostics stay outside test output.
        }
    }

    private ClientConfig webdriverClientConfig() {
        return ClientConfig.defaultConfig()
                .connectionTimeout(WEBDRIVER_TIMEOUT)
                .readTimeout(WEBDRIVER_TIMEOUT)
                .wsTimeout(WEBDRIVER_TIMEOUT);
    }

    /**
     * The React author canvas is copied into target/classes only by the opt-in Maven frontend
     * profile. Default Java verification should stay offline, while -Pfrontend must prove the
     * packaged app really boots from Spring's /author/ static path.
     */
    private void assumeReactAuthorBundlePresent() {
        Assumptions.assumeTrue(
                new ClassPathResource("static/author/index.html").exists(),
                "React author bundle is built only when Maven runs with -Pfrontend"
        );
    }

    /**
     * The React showcase uses the same Vite bundle as /author/ but is copied to a second static
     * route by the opt-in frontend Maven profile.
     */
    private void assumeReactShowcaseBundlePresent() {
        Assumptions.assumeTrue(
                new ClassPathResource("static/showcase/index.html").exists(),
                "React showcase bundle is built only when Maven runs with -Pfrontend"
        );
    }

    /**
     * The Owner workbench shares the Vite bundle while retaining an independently addressable route.
     */
    private void assumeReactRehearsalsBundlePresent() {
        Assumptions.assumeTrue(
                new ClassPathResource("static/rehearsals/index.html").exists(),
                "React rehearsal bundle is built only when Maven runs with -Pfrontend"
        );
    }

    private Path chromeDriverExecutableOrSkip() {
        String configured = System.getProperty("webdriver.chrome.driver", "").trim();
        if (!configured.isBlank()) {
            Path path = Path.of(configured);
            if (Files.isExecutable(path)) {
                return path;
            }
            Assumptions.abort("Configured ChromeDriver is not executable: " + path);
        }
        Path cached = cachedChromeDriver();
        if (cached != null) {
            return cached;
        }
        Assumptions.abort("ChromeDriver executable is unavailable. Set -Dwebdriver.chrome.driver or pre-populate "
                + SELENIUM_CHROMEDRIVER_CACHE);
        return null;
    }

    private Path cachedChromeDriver() {
        if (!Files.isDirectory(SELENIUM_CHROMEDRIVER_CACHE)) {
            return null;
        }
        try (var paths = Files.find(SELENIUM_CHROMEDRIVER_CACHE, 4, (path, attrs) ->
                attrs.isRegularFile()
                        && "chromedriver".equals(path.getFileName().toString())
                        && Files.isExecutable(path))) {
            return paths.sorted(Comparator.reverseOrder()).findFirst().orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private void click(WebDriverWait wait, By locator) {
        wait.until(ignored -> {
            try {
                WebElement element = driver.findElement(locator);
                if (!element.isDisplayed() || !element.isEnabled()) {
                    return false;
                }
                scrollIntoView(element);
                element.click();
                return true;
            } catch (NoSuchElementException | StaleElementReferenceException ex) {
                return false;
            }
        });
    }

    private void importSampleOperatorLibrary(WebDriverWait wait) {
        try {
            importOperatorLibrary(wait, VisualCatalogTestSupport.eligibilityLibrary("integer"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize sample operator library", ex);
        }
        waitForText(wait, By.id("library-profile"), "risk-policy");
        waitForText(wait, By.id("library-profile"), "1 operators");
        waitForText(wait, By.id("library-profile"), "1 inputs");
        waitForText(wait, By.id("library-profile"), "1 outputs");
        waitForText(wait, By.id("library-profile"), "2 required");
        waitForText(wait, By.id("library-profile"), "0 config fields");
        waitForText(wait, By.id("library-profile"), "2 output fields");
        waitForText(wait, By.id("library-profile"), "Eligibility");
    }

    private static OperatorLibrary eligibilityLibraryWithAdditionalOutput() {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("score", Map.of("type", "integer"));
        inputProperties.put("amount", Map.of("type", "number"));

        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("eligible", Map.of("type", "boolean"));
        outputProperties.put("ruleId", Map.of("type", "string"));
        outputProperties.put("reviewCode", Map.of("type", "string"));

        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:eligibility",
                "1.1.0",
                new OperatorDefinition.Display("Eligibility",
                        "Evaluates a reusable eligibility predicate with a review code.",
                        List.of("risk", "policy")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("score", "amount")),
                                true,
                                "Eligibility inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Eligibility result."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "eligible", "{{input.score}} >= 700 && {{input.amount}} <= 300000",
                                "ruleId", "\"ELIGIBILITY_V2\"",
                                "reviewCode", "\"AUTO_REVIEW\""
                        )
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.1.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary complexSchemaOutlineLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:complexIntake",
                "1.0.0",
                new OperatorDefinition.Display("Complex risk intake",
                        "Captures nested, dynamic, union, and event-shaped risk intake contracts.",
                        List.of("risk", "schema", "design")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("request",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                                        complexRiskIntakeSchema()),
                                true,
                                "Complex intake request.")),
                        List.of(new OperatorDefinition.Port("decision",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                                        complexRiskDecisionSchema()),
                                true,
                                "Complex intake decision."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("design", "", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-complex-schema",
                "Risk complex schema operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static Map<String, Object> complexRiskIntakeSchema() {
        Map<String, Object> profileProperties = new LinkedHashMap<>();
        profileProperties.put("id", Map.of(
                "type", "string",
                "title", "Customer identifier",
                "description", "External customer id.",
                "examples", List.of("C-1001")
        ));
        profileProperties.put("email", Map.of("type", "string", "format", "email"));
        profileProperties.put("segment", Map.of("type", "string", "enum", List.of("PRIME", "STANDARD")));

        Map<String, Object> paymentProperties = new LinkedHashMap<>();
        paymentProperties.put("paymentMethod", Map.of("type", "string", "enum", List.of("CARD", "BANK")));
        paymentProperties.put("cardNumber", Map.of("type", "string", "pattern", "^[0-9]{12,19}$"));
        paymentProperties.put("cardExpiry", Map.of("type", "string", "pattern", "^[0-9]{2}/[0-9]{2}$"));

        Map<String, Object> customerProperties = new LinkedHashMap<>();
        customerProperties.put("profile", Map.of(
                "type", "object",
                "properties", profileProperties,
                "required", List.of("id", "email"),
                "additionalProperties", false
        ));
        customerProperties.put("payment", Map.of(
                "type", "object",
                "properties", paymentProperties,
                "required", List.of("paymentMethod"),
                "dependentRequired", Map.of("paymentMethod", List.of("cardNumber", "cardExpiry")),
                "additionalProperties", false
        ));
        customerProperties.put("attributes", Map.of(
                "type", "object",
                "patternProperties", Map.of("^risk[A-Z].*", Map.of("type", "integer", "minimum", 0)),
                "additionalProperties", Map.of("type", "string")
        ));

        Map<String, Object> eventProperties = new LinkedHashMap<>();
        eventProperties.put("type", Map.of("type", "string", "enum", List.of("KYC", "FRAUD", "LIMIT")));
        eventProperties.put("score", Map.of("type", "integer", "minimum", 0, "maximum", 1000));

        Map<String, Object> rootProperties = new LinkedHashMap<>();
        rootProperties.put("customer", Map.of(
                "type", "object",
                "properties", customerProperties,
                "required", List.of("profile", "payment"),
                "additionalProperties", false
        ));
        rootProperties.put("events", Map.of(
                "type", "array",
                "contains", Map.of(
                        "type", "object",
                        "properties", eventProperties,
                        "required", List.of("type"),
                        "additionalProperties", false
                ),
                "minContains", 1,
                "maxContains", 3
        ));
        rootProperties.put("payload", Map.of(
                "oneOf", List.of(
                        Map.of(
                                "type", "object",
                                "properties", Map.of("manualLane", Map.of("type", "string")),
                                "required", List.of("manualLane"),
                                "additionalProperties", false
                        ),
                        Map.of(
                                "type", "object",
                                "properties", Map.of("autoScore", Map.of("type", "integer")),
                                "required", List.of("autoScore"),
                                "additionalProperties", false
                        )
                )
        ));
        rootProperties.put("flags", Map.of("anyOf", List.of(
                Map.of("type", "array", "items", Map.of("type", "string")),
                Map.of("type", "null")
        )));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", rootProperties);
        schema.put("required", List.of("customer", "events", "payload"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> complexRiskDecisionSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("decision", Map.of(
                "anyOf", List.of(
                        Map.of("type", "string", "enum", List.of("APPROVE", "REVIEW", "DECLINE")),
                        Map.of("type", "integer")
                )
        ));
        properties.put("audit", Map.of(
                "type", "array",
                "prefixItems", List.of(
                        Map.of("type", "string"),
                        Map.of("type", "integer")
                ),
                "items", false
        ));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("decision"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static OperatorLibrary scoreReviewDesignOnlyLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-score-design",
                "Risk score design operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(scoreReviewOperatorDefinition())
        );
    }

    private static OperatorLibrary scoreReviewLargeConnectabilityLibrary() {
        OperatorDefinition source = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreSource",
                "1.0.0",
                new OperatorDefinition.Display("Score source",
                        "Provides one primitive score output for large connectability windows.",
                        List.of("risk", "design")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                                        Map.of("type", "integer")),
                                true,
                                "Score output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("design", "", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-score-large-connectability",
                "Risk score large connectability operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(source, scoreReviewTransformTargetOperatorDefinition())
        );
    }

    private static OperatorLibrary scoreReviewLargeSourceConnectabilityLibrary() {
        List<OperatorDefinition.Port> outputs = IntStream.rangeClosed(1, 40)
                .mapToObj(index -> new OperatorDefinition.Port("metric" + index,
                        new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                                Map.of("type", "integer")),
                        true,
                        "Score metric " + index + "."))
                .toList();
        OperatorDefinition source = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreMatrixSource",
                "1.0.0",
                new OperatorDefinition.Display("Score matrix source",
                        "Provides many primitive score outputs for source-handle connectability windows.",
                        List.of("risk", "design")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(List.of(), outputs),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("design", "", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-score-source-connectability",
                "Risk score source connectability operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(source, scoreReviewTransformTargetOperatorDefinition())
        );
    }

    private static String expectedMetricSourceKeys(int startInclusive, int endInclusive) {
        return IntStream.rangeClosed(startInclusive, endInclusive)
                .mapToObj(index -> "data:riskScoreMatrixSource:metric" + index + ":")
                .sorted()
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static List<String> expectedMetricSourceLabels(int startInclusive, int endInclusive) {
        return IntStream.rangeClosed(startInclusive, endInclusive)
                .mapToObj(index -> "riskScoreMatrixSource.metric" + index)
                .toList();
    }

    private static OperatorDefinition scoreReviewTransformTargetOperatorDefinition() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreReview",
                "1.0.0",
                new OperatorDefinition.Display("Score review",
                        "Reviews a risk score inside the visual transform runtime.",
                        List.of("risk", "transform")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(
                                        Map.of("score", Map.of("type", "integer")),
                                        List.of("score")),
                                true,
                                "Score review input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(
                                        Map.of("approved", Map.of("type", "boolean")),
                                        List.of()),
                                true,
                                "Score review result."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("approved", "true")
                )),
                List.of()
        );
    }

    private static OperatorDefinition scoreReviewOperatorDefinition() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreReview",
                "1.0.0",
                new OperatorDefinition.Display("Score review",
                        "Reviews a risk score through an externally implemented policy.",
                        List.of("risk", "design")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(
                                        Map.of("score", Map.of("type", "integer")),
                                        List.of("score")),
                                true,
                                "Score review input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(
                                        Map.of("approved", Map.of("type", "boolean")),
                                        List.of()),
                                true,
                                "Score review result."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("design", "", Map.of()),
                List.of()
        );
    }

    private void importOperatorLibrary(WebDriverWait wait, OperatorLibrary library) throws JsonProcessingException {
        WebElement editor = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-library-json")));
        setControlValue(editor, OBJECT_MAPPER.writeValueAsString(library));
        click(wait, By.id("import-library"));
        waitForImportedOperatorLibrary(wait, library.libraryId());
    }

    private void importYamlOperatorLibrary(WebDriverWait wait, OperatorLibrary library) throws JsonProcessingException {
        WebElement editor = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-library-json")));
        setControlValue(editor, YAML_MAPPER.writeValueAsString(library));
        click(wait, By.id("validate-library"));
        waitForText(wait, By.id("library-status"), "Operator library is valid.");
        waitForText(wait, By.id("library-profile"), library.libraryId());

        click(wait, By.id("import-library"));
        waitForImportedOperatorLibrary(wait, library.libraryId());
        waitForText(wait, By.id("library-profile"), "1 operators");
        waitForText(wait, By.id("library-profile"), "2 required");
        waitForText(wait, By.id("library-profile"), "Eligibility");
    }

    private void waitForImportedOperatorLibrary(WebDriverWait wait, String libraryId) {
        for (int attempt = 0; attempt < 3; attempt += 1) {
            waitForAnyText(wait, By.id("library-status"),
                    "Imported " + libraryId,
                    "Replaced " + libraryId,
                    "Review warnings");
            String status = textOf(By.id("library-status"));
            if (status.contains("Imported " + libraryId) || status.contains("Replaced " + libraryId)) {
                return;
            }
            runOperatorLibraryImportInBrowser();
        }
        waitForAnyText(wait, By.id("library-status"),
                "Imported " + libraryId,
                "Replaced " + libraryId);
    }

    private void runOperatorLibraryImportInBrowser() {
        Object result = ((JavascriptExecutor) driver).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                Promise.resolve(importOperatorLibrary())
                  .then(() => done(document.getElementById('library-status')?.textContent || ''))
                  .catch((error) => done(`ERROR: ${String(error?.message || error)}`));
                """);
        assertThat(String.valueOf(result)).doesNotStartWith("ERROR:");
    }

    private void addScoreSourceAndReviewTargetsInBrowser(WebDriverWait wait, int count) {
        Number added = (Number) ((JavascriptExecutor) driver).executeScript("""
                const targetCount = Number(arguments[0]);
                const sourceType = 'risk:scoreSource';
                const targetType = 'risk:scoreReview';
                if (!OPERATOR_TYPES[sourceType]) {
                  throw new Error(`Operator ${sourceType} is not registered in the browser catalog`);
                }
                if (!OPERATOR_TYPES[targetType]) {
                  throw new Error(`Operator ${targetType} is not registered in the browser catalog`);
                }
                const source = createBuilderNode(sourceType, 120, 160);
                state.builder.nodes = [source];
                for (let index = 0; index < targetCount; index += 1) {
                  const column = index % 20;
                  const row = Math.floor(index / 20);
                  const node = createBuilderNode(targetType, 520 + column * 240, 120 + row * 145);
                  state.builder.nodes.push(node);
                }
                state.builder.dependencyEdges = [];
                state.builder.routeEdges = [];
                state.builder.output = { nodeId: source.id, path: '' };
                state.builder.selectedId = source.id;
                state.selectedNodeId = source.id;
                state.canvasFocusedNodeId = source.id;
                state.nodeConnectabilityServer = null;
                clearNodeConnectabilityFilter();
                syncComposerFromBuilder({ render: false });
                renderInputForm();
                renderSelectedOperatorEditor();
                renderGraphOutputEditor();
                renderDiagram();
                return state.builder.nodes.length;
                """, count);
        assertThat(added.intValue()).isEqualTo(count + 1);
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "#diagram [data-node-id^='riskScoreReview']"
        )).size() >= count);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#diagram [data-node-id^='riskScoreSource']"
        )));
    }

    private void addScoreMatrixSourceAndSingleReviewTargetInBrowser(WebDriverWait wait) {
        ensureBrowserOperatorTypes(List.of("risk:scoreMatrixSource", "risk:scoreReview"));
        Number sourceCount = (Number) ((JavascriptExecutor) driver).executeScript("""
                const sourceType = 'risk:scoreMatrixSource';
                const targetType = 'risk:scoreReview';
                if (!OPERATOR_TYPES[sourceType]) {
                  throw new Error(`Operator ${sourceType} is not registered in the browser catalog`);
                }
                if (!OPERATOR_TYPES[targetType]) {
                  throw new Error(`Operator ${targetType} is not registered in the browser catalog`);
                }
                const source = createBuilderNode(sourceType, 120, 160);
                source.id = 'riskScoreMatrixSource';
                const target = createBuilderNode(targetType, 560, 160);
                target.id = 'riskScoreReview1';
                state.builder.nodes = [source, target];
                state.builder.dependencyEdges = [];
                state.builder.routeEdges = [];
                state.builder.output = { nodeId: source.id, path: 'metric1' };
                state.builder.selectedId = source.id;
                state.selectedNodeId = source.id;
                state.canvasFocusedNodeId = source.id;
                state.nodeConnectabilityServer = null;
                state.nodeConnectabilitySourceWindows = {};
                clearNodeConnectabilityFilter();
                syncComposerFromBuilder({ render: false });
                renderInputForm();
                renderSelectedOperatorEditor();
                renderGraphOutputEditor();
                renderDiagram();
                return sourceHandlesForNode(source).length;
                """);
        assertThat(sourceCount.intValue()).isEqualTo(40);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#diagram [data-node-id='riskScoreMatrixSource']"
        )));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#diagram [data-node-id='riskScoreReview1']"
        )));
    }

    private void ensureBrowserOperatorTypes(List<String> operatorRefs) {
        Object result = ((JavascriptExecutor) driver).executeAsyncScript("""
                const refs = arguments[0];
                const done = arguments[arguments.length - 1];
                const hasAllRefs = () => refs.every((ref) => Boolean(OPERATOR_TYPES[ref]));
                if (hasAllRefs()) {
                  done({ ready: true });
                  return;
                }
                Promise.all(refs.map((ref) => loadVisualOperatorDefinition(ref, {
                  paletteVisible: false,
                  render: false,
                  silent: true
                })))
                  .then(() => done({ ready: hasAllRefs(), missing: refs.filter((ref) => !OPERATOR_TYPES[ref]) }))
                  .catch((error) => done({
                    ready: false,
                    error: String(error?.message || error),
                    missing: refs.filter((ref) => !OPERATOR_TYPES[ref])
                  }));
                """, operatorRefs);
        assertThat(result)
                .as("browser catalog should expose operator refs %s", operatorRefs)
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result;
        assertThat(payload)
                .as("browser catalog readiness for operator refs %s", operatorRefs)
                .containsEntry("ready", true);
    }

    private void publishVisualDraft(WebDriverWait wait) {
        click(wait, By.id("publish-visual-draft"));
        waitForPublishAttempt(wait);
        if (textOf(By.id("publication-status")).contains("Review publish warnings")
                && textOf(By.id("visual-check-status")).contains("Visual graph was not published.")) {
            click(wait, By.id("publish-visual-draft"));
            waitForPublishAttempt(wait);
        }
        waitForText(wait, By.id("visual-check-status"), "Published");
        waitForText(wait, By.id("publication-status"), "Published");
    }

    private void waitForPublishAttempt(WebDriverWait wait) {
        waitForAnyText(wait, By.id("visual-check-status"),
                "Published",
                "Visual graph was not published.");
    }

    private static OperatorLibrary optionalLoweringInputLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:optionalBoost",
                "1.0.0",
                new OperatorDefinition.Display("Optional boost",
                        "Uses an optional lowering template input.",
                        List.of("risk", "template")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of(
                                        "score", Map.of("type", "integer"),
                                        "boost", Map.of("type", "integer")
                                ), List.of("score")),
                                true,
                                "Score input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "result", Map.of("type", "number")
                                ), List.of("result")),
                                true,
                                "Boosted score output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("result", "{{input.score}} + {{input.boost}}")
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-optional-lowering-input",
                "Risk optional lowering input operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static SchemaEnvelope customerOrderGraphInputSchema() {
        return SchemaEnvelope.object(Map.of(
                "customer", Map.of(
                        "type", "object",
                        "properties", Map.of("id", Map.of("type", "string")),
                        "required", List.of("id"),
                        "additionalProperties", false
                ),
                "orderId", Map.of("type", "string")
        ), List.of("customer", "orderId"));
    }

    private String bindingSourceValue(String nodeId, String port, String path) {
        return String.valueOf(((JavascriptExecutor) driver).executeScript(
                "return encodeURIComponent(JSON.stringify({nodeId: arguments[0], port: arguments[1], path: arguments[2]}));",
                nodeId,
                port,
                path
        ));
    }

    @SuppressWarnings("unchecked")
    private JsonNode projectCapabilityClosure(String draftJson) throws JsonProcessingException {
        Map<String, Object> result = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
                const draft = JSON.parse(arguments[0]);
                const done = arguments[arguments.length - 1];
                fetch('/api/integration/capability-closures/project', {
                  method: 'POST',
                  headers: {
                    'Authorization': 'Bearer bloge-aneke-demo-token',
                    'X-Purpose': 'CAPABILITY_PROJECTION',
                    'X-Correlation-Id': `canvas-example-${draft.graphName}`,
                    'Content-Type': 'application/json'
                  },
                  body: JSON.stringify({
                    schemaVersion: 'resourceGateway.capabilityClosureProjectionRequest.v1',
                    draft,
                    revision: 1,
                    createdAt: '2026-07-22T08:00:00Z',
                    classification: 'CONFIDENTIAL'
                  })
                })
                  .then(async (response) => done({ status: response.status, body: await response.text() }))
                  .catch((error) => done({ status: 0, body: error.message }));
                """, draftJson);
        assertThat(((Number) result.get("status")).intValue())
                .as(String.valueOf(result.get("body")))
                .isEqualTo(200);
        return OBJECT_MAPPER.readTree(String.valueOf(result.get("body")));
    }

    private record ExampleClosureExpectation(String graphName, int snapshotCount) {
    }

    private long currentDraftRevisionFromStatus() {
        String status = textOf(By.id("draft-status"));
        String revision = status.replaceFirst(".*@(\\d+).*", "$1");
        assertThat(revision).as(status).matches("\\d+");
        return Long.parseLong(revision);
    }

    @SuppressWarnings("unchecked")
    private void patchDraftGraphNameInBrowser(String draftId, long expectedRevision, String graphName) {
        Map<String, Object> result = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
                const draftId = arguments[0];
                const expectedRevision = arguments[1];
                const graphName = arguments[2];
                const done = arguments[arguments.length - 1];
                fetch(`/api/visual/drafts/${encodeURIComponent(draftId)}`, {
                  method: 'PATCH',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    expectedRevision,
                    actor: 'browser-test',
                    changeSource: 'concurrent-browser-test',
                    changeSummary: 'Simulated concurrent graph rename.',
                    patch: [{ op: 'replace', path: '/graphName', value: graphName }]
                  })
                })
                  .then(async (response) => done({ status: response.status, body: await response.text() }))
                  .catch((error) => done({ status: 0, body: error.message }));
                """, draftId, expectedRevision, graphName);
        assertThat(((Number) result.get("status")).intValue())
                .as(String.valueOf(result.get("body")))
                .isEqualTo(200);
    }

    private void addDynamicInputBinding(WebDriverWait wait, String port, String path) {
        selectByValue(wait, By.cssSelector("[data-dynamic-input-port]"), port);
        setControlValue(wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-dynamic-input-path]"))), path);
        click(wait, By.cssSelector("[data-add-dynamic-input]"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "[data-binding-source][data-binding-port='" + port + "'][data-binding-path='" + path + "']"
        )));
    }

    private void addDynamicOutputPath(WebDriverWait wait, String nodeId, String port, String path) {
        selectByValue(wait, By.cssSelector("[data-dynamic-output-port]"), port);
        setControlValue(wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-dynamic-output-path]"))), path);
        click(wait, By.cssSelector("[data-add-dynamic-output]"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "#diagram [data-node-id='" + nodeId + "'] [data-port-role='source'][data-port='"
                        + port + "'][data-path='" + path + "']"
        )));
    }

    private static OperatorLibrary unsafeOutputLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:unsafeFacts",
                "1.0.0",
                new OperatorDefinition.Display("Unsafe facts",
                        "Produces both DSL-safe and unsafe output field names.",
                        List.of("risk", "facts")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "safeId", Map.of("type", "string"),
                                        "customer-id", Map.of("type", "string")
                                ), List.of()),
                                true,
                                "Facts output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("design", "", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-unsafe-output",
                "Risk unsafe output operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary arrayOutputLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:arrayFacts",
                "1.0.0",
                new OperatorDefinition.Display("Array facts",
                        "Produces array output facts.",
                        List.of("risk", "array")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "items", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "integer"))
                                ), List.of()),
                                true,
                                "Array output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskArrayFacts", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-array-output",
                "Risk array output operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary configUnionPolicyLibrary() {
        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("payload", Map.of("oneOf", List.of(
                Map.of(
                        "type", "object",
                        "properties", Map.of("score", Map.of("type", "integer")),
                        "required", List.of("score"),
                        "additionalProperties", false
                ),
                Map.of(
                        "type", "object",
                        "properties", Map.of("decision", Map.of("type", "string")),
                        "required", List.of("decision"),
                        "additionalProperties", false
                )
        )));
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:configUnionPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Config union policy",
                        "Evaluates policy behavior controlled by union configSchema.",
                        List.of("risk", "config")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.object(configProperties, List.of("payload")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("accepted", "true")
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-config-union-policy",
                "Risk config union policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private void dragOperatorToCanvas(WebDriverWait wait, String searchText, String operatorType,
                                      String expectedNodeId, int xOffset, int yOffset) {
        WebElement search = wait.until(ExpectedConditions.elementToBeClickable(By.id("operator-palette-search")));
        search.clear();
        search.sendKeys(searchText);
        WebElement operator = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-operator-type='" + operatorType + "']")
        ));
        WebElement diagram = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("diagram")));
        dragOperatorToCanvas(operator, diagram, xOffset, yOffset);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#diagram [data-node-id='" + expectedNodeId + "']")
        ));
    }

    private void dragOperatorToCanvas(WebElement operator, WebElement diagram, int xOffset, int yOffset) {
        scrollIntoView(operator);
        new Actions(driver)
                .moveToElement(operator)
                .clickAndHold()
                .pause(Duration.ofMillis(150))
                .moveToElement(diagram, xOffset, yOffset)
                .pause(Duration.ofMillis(150))
                .release()
                .perform();
    }

    /**
     * Performs the browser-level HTML5 drag/drop used by the React author palette.
     * Selenium's pointer-only drag helper does not consistently populate {@code dataTransfer},
     * while the React handler reads the operator reference from that payload.
     */
    private void dragReactOperatorToAuthorFlow(WebElement operator, WebElement flow, int xOffset, int yOffset) {
        scrollIntoView(operator);
        ((JavascriptExecutor) driver).executeAsyncScript("""
                const operator = arguments[0];
                const flow = arguments[1];
                const xOffset = arguments[2];
                const yOffset = arguments[3];
                const done = arguments[4];
                const rect = flow.getBoundingClientRect();
                const x = rect.left + xOffset;
                const y = rect.top + yOffset;
                const dataTransfer = new DataTransfer();
                const eventInit = {
                  bubbles: true,
                  cancelable: true,
                  composed: true,
                  dataTransfer,
                  clientX: x,
                  clientY: y
                };
                operator.dispatchEvent(new DragEvent('dragstart', eventInit));
                flow.dispatchEvent(new DragEvent('dragover', eventInit));
                flow.dispatchEvent(new DragEvent('drop', eventInit));
                operator.dispatchEvent(new DragEvent('dragend', eventInit));
                done();
                """, operator, flow, xOffset, yOffset);
    }

    private void usePromptValue(String value) {
        ((JavascriptExecutor) driver).executeScript(
                "window.__blogePromptValues = [arguments[0]];"
                        + "window.prompt = function(message, initial) {"
                        + "  window.__blogeLastPrompt = { message: message, initial: initial };"
                        + "  return window.__blogePromptValues.shift();"
                        + "};",
                value
        );
    }

    private void useConfirm(boolean value) {
        ((JavascriptExecutor) driver).executeScript(
                "window.__blogeConfirmValue = arguments[0];"
                        + "window.confirm = function(message) {"
                        + "  window.__blogeLastConfirm = message;"
                        + "  return window.__blogeConfirmValue;"
                        + "};",
                value
        );
    }

    private void dragConnection(WebDriverWait wait, String sourceSelector, String targetSelector) {
        WebElement source = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(sourceSelector)));
        WebElement target = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(targetSelector)));
        scrollIntoView(target);
        source = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(sourceSelector)));
        target = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(targetSelector)));
        double[] sourceCenter = elementCenter(source);
        double[] targetCenter = elementCenter(target);
        ((JavascriptExecutor) driver).executeScript("""
                const source = arguments[0];
                const sx = arguments[1];
                const sy = arguments[2];
                const tx = arguments[3];
                const ty = arguments[4];
                const options = (x, y, buttons) => ({
                  bubbles: true,
                  cancelable: true,
                  composed: true,
                  pointerId: 1,
                  pointerType: 'mouse',
                  isPrimary: true,
                  button: 0,
                  buttons,
                  clientX: x,
                  clientY: y
                });
                source.dispatchEvent(new PointerEvent('pointerdown', options(sx, sy, 1)));
                document.dispatchEvent(new PointerEvent('pointermove', options(tx, ty, 1)));
                document.dispatchEvent(new PointerEvent('pointerup', options(tx, ty, 0)));
                """, source, sourceCenter[0], sourceCenter[1], targetCenter[0], targetCenter[1]);
    }

    private double[] elementCenter(WebElement element) {
        @SuppressWarnings("unchecked")
        List<Number> rect = (List<Number>) ((JavascriptExecutor) driver).executeScript(
                "const r = arguments[0].getBoundingClientRect();"
                        + "return [r.left + r.width / 2, r.top + r.height / 2];",
                element
        );
        return new double[] { rect.get(0).doubleValue(), rect.get(1).doubleValue() };
    }

    private void selectByValue(WebDriverWait wait, By locator, String value) {
        wait.until(ignored -> {
            try {
                WebElement element = driver.findElement(locator);
                if (!element.isDisplayed() || !element.isEnabled()) {
                    return false;
                }
                if (element.findElements(By.cssSelector("option[value=\"" + value + "\"]")).isEmpty()) {
                    return false;
                }
                scrollIntoView(element);
                new Select(element).selectByValue(value);
                return true;
            } catch (NoSuchElementException | StaleElementReferenceException ex) {
                return false;
            }
        });
    }

    private void waitForValue(WebDriverWait wait, By locator, String expected) {
        try {
            wait.until(ignored -> valueOf(locator).contains(expected));
        } catch (TimeoutException ex) {
            throw new AssertionError("Expected value '%s' was not present in %s. Actual='%s', output='%s'"
                    .formatted(expected, locator, valueOf(locator), textOf(By.id("output"))), ex);
        }
    }

    private void sendKeysThroughRerenderedFocusedInput(WebDriverWait wait, By locator, String value) {
        WebElement input = wait.until(ExpectedConditions.elementToBeClickable(locator));
        input.clear();
        input.click();
        waitForFocusedValue(wait, locator, "");
        StringBuilder typed = new StringBuilder();
        for (char character : value.toCharArray()) {
            String previous = typed.toString();
            String expected = previous + character;
            wait.until(ignored -> {
                try {
                    WebElement element = driver.findElement(locator);
                    if (!element.isDisplayed() || !element.isEnabled()) {
                        return false;
                    }
                    String currentValue = String.valueOf(element.getAttribute("value"));
                    if (expected.equals(currentValue)) {
                        return true;
                    }
                    if (!previous.equals(currentValue)) {
                        return false;
                    }
                    if (!element.equals(driver.switchTo().activeElement())) {
                        scrollIntoView(element);
                        element.click();
                    }
                    element.sendKeys(String.valueOf(character));
                    return true;
                } catch (NoSuchElementException | StaleElementReferenceException
                         | ElementNotInteractableException ex) {
                    return false;
                }
            });
            typed.append(character);
            waitForValue(wait, locator, expected);
            waitForFocusedValue(wait, locator, expected);
        }
    }

    private void waitForFocusedValue(WebDriverWait wait, By locator, String expected) {
        try {
            wait.until(ignored -> {
                try {
                    WebElement element = driver.findElement(locator);
                    WebElement activeElement = driver.switchTo().activeElement();
                    return element.equals(activeElement)
                            && expected.equals(String.valueOf(element.getAttribute("value")));
                } catch (NoSuchElementException | StaleElementReferenceException ex) {
                    return false;
                }
            });
        } catch (TimeoutException ex) {
            throw new AssertionError("Expected focused value '%s' was not present in %s. Actual='%s', output='%s'"
                    .formatted(expected, locator, valueOf(locator), textOf(By.id("output"))), ex);
        }
    }

    private void assertNoHorizontalOverflow(WebDriverWait wait, By locator) {
        WebElement element = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        Number overflow = (Number) ((JavascriptExecutor) driver).executeScript(
                "return Math.max(0, arguments[0].scrollWidth - arguments[0].clientWidth);",
                element
        );
        String diagnostics = String.valueOf(((JavascriptExecutor) driver).executeScript("""
                const root = arguments[0];
                const rootRect = root.getBoundingClientRect();
                return [...root.querySelectorAll('*')]
                  .map((candidate) => {
                    const rect = candidate.getBoundingClientRect();
                    const style = getComputedStyle(candidate);
                    return {
                      tag: candidate.tagName.toLowerCase(),
                      id: candidate.id || '',
                      className: typeof candidate.className === 'string' ? candidate.className : '',
                      rightOverflow: Math.max(0, Math.round((rect.right - rootRect.right) * 10) / 10),
                      ownOverflow: Math.max(0, candidate.scrollWidth - candidate.clientWidth),
                      width: Math.round(rect.width * 10) / 10,
                      minWidth: style.minWidth,
                      whiteSpace: style.whiteSpace,
                      boxSizing: style.boxSizing
                    };
                  })
                  .filter((candidate) => candidate.rightOverflow > 2 || candidate.ownOverflow > 2)
                  .sort((left, right) => Math.max(right.rightOverflow, right.ownOverflow)
                    - Math.max(left.rightOverflow, left.ownOverflow))
                  .slice(0, 10)
                  .map((candidate) => JSON.stringify(candidate))
                  .join(String.fromCharCode(10));
                """, element));
        assertThat(overflow.doubleValue())
                .as("Horizontal overflow for %s:%n%s", locator, diagnostics)
                .isLessThanOrEqualTo(2.0);
    }

    private double elementClientHeight(WebElement element) {
        Number height = (Number) ((JavascriptExecutor) driver).executeScript(
                "return arguments[0].getBoundingClientRect().height;",
                element
        );
        return height.doubleValue();
    }

    /**
     * Reads the rendered width in CSS pixels without Selenium's integer size rounding.
     *
     * @param element rendered browser element to measure
     * @return fractional CSS-pixel width reported by the browser layout engine
     */
    private double elementClientWidth(WebElement element) {
        Number width = (Number) ((JavascriptExecutor) driver).executeScript(
                "return arguments[0].getBoundingClientRect().width;",
                element
        );
        return width.doubleValue();
    }

    private void openDataInspector(WebDriverWait wait) {
        if (visibleElements(By.cssSelector("[data-testid='inspector-tab:data']")).isEmpty()) {
            WebElement opener = wait.until(ignored -> {
                for (By locator : List.of(
                        By.cssSelector("[data-testid='compact-open-inspector']"),
                        By.cssSelector("[aria-label='Expand context inspector']"),
                        By.cssSelector(".inspector-panel-toggle[aria-expanded='false']"))) {
                    List<WebElement> candidates = visibleElements(locator);
                    if (!candidates.isEmpty() && candidates.getFirst().isEnabled()) {
                        return candidates.getFirst();
                    }
                }
                return null;
            });
            opener.click();
        }
        wait.until(ignored -> visibleElements(By.cssSelector(
                "[data-testid='inspector-tab:data']"
        )).stream().filter(WebElement::isEnabled).findFirst().orElse(null)).click();
    }

    private List<WebElement> visibleElements(By locator) {
        return driver.findElements(locator).stream()
                .filter(element -> {
                    try {
                        return element.isDisplayed();
                    } catch (StaleElementReferenceException ignored) {
                        return false;
                    }
                })
                .toList();
    }

    private void dispatchDoubleClick(WebElement element) {
        ((JavascriptExecutor) driver).executeScript("""
                arguments[0].dispatchEvent(new MouseEvent('dblclick', {
                  bubbles: true,
                  cancelable: true,
                  view: window
                }));
                """, element);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Number> canvasReadabilityGeometry() {
        return (Map<String, Number>) ((JavascriptExecutor) driver).executeScript("""
                const flow = document.querySelector('[data-testid="author-flow"]')
                  .getBoundingClientRect();
                const nodes = [...document.querySelectorAll('.react-flow__node')]
                  .filter((element) => getComputedStyle(element).display !== 'none')
                  .map((element) => element.getBoundingClientRect());
                const labels = [...document.querySelectorAll(
                  '[data-testid="canvas-edge-label"]'
                )]
                  .filter((element) => getComputedStyle(element).display !== 'none')
                  .map((element) => element.getBoundingClientRect());
                const overlaps = (left, right, padding) =>
                  left.left < right.right - padding
                  && left.right > right.left + padding
                  && left.top < right.bottom - padding
                  && left.bottom > right.top + padding;
                let nodeLabelCollisions = 0;
                let labelLabelCollisions = 0;
                for (const label of labels) {
                  for (const node of nodes) {
                    if (overlaps(label, node, 2)) {
                      nodeLabelCollisions += 1;
                    }
                  }
                }
                for (let left = 0; left < labels.length; left += 1) {
                  for (let right = left + 1; right < labels.length; right += 1) {
                    if (overlaps(labels[left], labels[right], 1)) {
                      labelLabelCollisions += 1;
                    }
                  }
                }
                const outside = (rect) =>
                  rect.left < flow.left - 1 || rect.right > flow.right + 1
                  || rect.top < flow.top - 1 || rect.bottom > flow.bottom + 1;
                const workspace = document.querySelector('.workspace-v2');
                return {
                  outsideNodes: nodes.filter(outside).length,
                  outsideLabels: labels.filter(outside).length,
                  nodeLabelCollisions,
                  labelLabelCollisions,
                  effectiveTitleFontPx:
                    Number(workspace.dataset.canvasEffectiveTitlePx || 0),
                  visibleFieldLabels:
                    Number(workspace.dataset.canvasVisibleFieldLabels || 0)
                };
                """);
    }

    private void assertVisibleElementsNoHorizontalOverflow(WebDriverWait wait, By locator) {
        wait.until(ignored -> {
            List<WebElement> visibleElements = driver.findElements(locator).stream()
                    .filter(element -> {
                        try {
                            return element.isDisplayed();
                        } catch (StaleElementReferenceException ex) {
                            return false;
                        }
                    })
                    .toList();
            if (visibleElements.isEmpty()) {
                return false;
            }
            try {
                for (WebElement element : visibleElements) {
                    Number overflow = (Number) ((JavascriptExecutor) driver).executeScript(
                            "return Math.max(0, arguments[0].scrollWidth - arguments[0].clientWidth);",
                            element
                    );
                    assertThat(overflow.doubleValue())
                            .as("horizontal overflow for %s", locator)
                            .isLessThanOrEqualTo(2.0);
                }
                return true;
            } catch (StaleElementReferenceException ex) {
                return false;
            }
        });
    }

    private void assertPageNoHorizontalOverflow() {
        Number overflow = (Number) ((JavascriptExecutor) driver).executeScript("""
                const viewportWidth = window.innerWidth || document.documentElement.clientWidth;
                const scroller = document.scrollingElement || document.documentElement;
                return Math.max(
                  0,
                  scroller.scrollWidth - viewportWidth,
                  document.body ? document.body.scrollWidth - viewportWidth : 0
                );
                """);
        String diagnostics = String.valueOf(((JavascriptExecutor) driver).executeScript("""
                const viewportWidth = window.innerWidth || document.documentElement.clientWidth;
                const hasClippingAncestor = (element) => {
                  for (let parent = element.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
                    const style = window.getComputedStyle(parent);
                    if (!['visible', 'clip'].includes(style.overflowX)) {
                      return true;
                    }
                  }
                  return false;
                };
                const describe = (element, tagOverride) => {
                  const rect = element.getBoundingClientRect();
                  const style = window.getComputedStyle(element);
                  return {
                    tag: tagOverride || element.tagName.toLowerCase(),
                    id: element.id || '',
                    className: String(element.className || ''),
                    overflow: Math.max(0, rect.right + window.scrollX - viewportWidth),
                    left: rect.left + window.scrollX,
                    right: rect.right + window.scrollX,
                    width: rect.width,
                    minWidth: style.minWidth,
                    display: style.display,
                    gridTemplateColumns: style.gridTemplateColumns,
                    clipped: hasClippingAncestor(element)
                  };
                };
                const overflowElements = [...document.querySelectorAll('body *')]
                  .map((element) => {
                    return describe(element);
                  })
                  .filter((item) => item.overflow > 2 && !item.clipped)
                  .sort((left, right) => right.overflow - left.overflow)
                  .slice(0, 5);
                const draft = document.getElementById('draft-select');
                const layoutChain = [];
                for (let element = draft; element && element !== document.body; element = element.parentElement) {
                  layoutChain.push(describe(element, 'layout-chain'));
                }
                return JSON.stringify(overflowElements
                  .concat(layoutChain)
                  .concat([{
                    tag: 'viewport-metrics',
                    id: '',
                    className: '',
                    overflow: Math.max(
                      0,
                      (document.scrollingElement || document.documentElement).scrollWidth - viewportWidth,
                      document.body ? document.body.scrollWidth - viewportWidth : 0
                    ),
                    width: viewportWidth,
                    clipped: false,
                    scrollX: window.scrollX,
                    visualViewportWidth: window.visualViewport?.width || 0,
                    documentClientWidth: document.documentElement.clientWidth,
                    documentScrollWidth: document.documentElement.scrollWidth,
                    documentRectWidth: document.documentElement.getBoundingClientRect().width,
                    bodyClientWidth: document.body?.clientWidth || 0,
                    bodyScrollWidth: document.body?.scrollWidth || 0,
                    bodyRectWidth: document.body?.getBoundingClientRect().width || 0,
                    rootClientWidth: document.getElementById('root')?.clientWidth || 0,
                    rootScrollWidth: document.getElementById('root')?.scrollWidth || 0,
                    rootRectWidth: document.getElementById('root')?.getBoundingClientRect().width || 0
                  }]));
                """));
        assertThat(overflow.doubleValue())
                .as("page horizontal overflow diagnostics: %s", diagnostics)
                .isLessThanOrEqualTo(2.0);
    }

    private void setViewport(WebDriverWait wait, int width, int height) {
        driver.manage().window().setSize(new Dimension(width, height));
        if (driver instanceof ChromeDriver chromeDriver) {
            chromeDriver.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
                    "width", width,
                    "height", height,
                    "deviceScaleFactor", 1,
                    "mobile", false
            ));
        }
        wait.until(ignored -> {
            Number innerWidth = (Number) ((JavascriptExecutor) driver).executeScript("return window.innerWidth;");
            return innerWidth.doubleValue() <= width + 20;
        });
    }

    private void captureVisualQa(String fileName) throws IOException {
        String outputDirectory = System.getProperty("resourceGateway.visualQaOutputDir", "");
        if (outputDirectory.isBlank()) {
            return;
        }
        Path output = Path.of(outputDirectory).resolve(fileName);
        Files.createDirectories(output.getParent());
        Files.write(output, ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
    }

    private List<String> connectabilitySourceRowLabels() {
        @SuppressWarnings("unchecked")
        List<Object> values = (List<Object>) ((JavascriptExecutor) driver).executeScript("""
                return [...document.querySelectorAll('#selected-operator-editor .node-connectability-row strong')]
                  .map((element) => element.textContent.trim());
                """);
        return values.stream().map(String::valueOf).toList();
    }

    private Map<String, String> connectabilityTargetA11yState(String targetNodeId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) ((JavascriptExecutor) driver).executeScript("""
                const targetNodeId = arguments[0];
                const action = document.querySelector(
                  `#selected-operator-editor [data-connectability-action='connect'][data-connect-target-node='${targetNodeId}']`
                );
                if (!action) {
                  return { missing: 'true' };
                }
                action.focus({ preventScroll: true });
                const container = action.closest('[data-connectability-targets]');
                const rowNext = container?.querySelector('[data-connectability-row-window="next"]');
                return {
                  actionId: action.id || '',
                  active: container?.getAttribute('aria-activedescendant') || '',
                  activeMatches: String(Boolean(action.id) && container?.getAttribute('aria-activedescendant') === action.id),
                  current: action.getAttribute('aria-current') || '',
                  pos: action.getAttribute('aria-posinset') || '',
                  setsize: action.getAttribute('aria-setsize') || '',
                  containerId: container?.id || '',
                  containerRole: container?.getAttribute('role') || '',
                  rowNextControls: rowNext?.getAttribute('aria-controls') || '',
                  rowNextControlsContainer: String(Boolean(container?.id) && rowNext?.getAttribute('aria-controls') === container.id)
                };
                """, targetNodeId);
        return values.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.valueOf(entry.getValue())
                ));
    }

    private Map<String, String> connectabilityVisibleTargetA11yState(int index) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) ((JavascriptExecutor) driver).executeScript("""
                const index = Number(arguments[0]) || 0;
                const actions = [...document.querySelectorAll(
                  "#selected-operator-editor [data-connectability-action='connect'][data-connect-target-node^='riskScoreReview']"
                )];
                const action = actions[index];
                if (!action) {
                  return { missing: 'true', count: String(actions.length) };
                }
                action.focus({ preventScroll: true });
                const container = action.closest('[data-connectability-targets]');
                const rowNext = container?.querySelector('[data-connectability-row-window="next"]');
                return {
                  actionId: action.id || '',
                  targetNodeId: action.dataset.connectTargetNode || '',
                  active: container?.getAttribute('aria-activedescendant') || '',
                  activeMatches: String(Boolean(action.id) && container?.getAttribute('aria-activedescendant') === action.id),
                  current: action.getAttribute('aria-current') || '',
                  pos: action.getAttribute('aria-posinset') || '',
                  setsize: action.getAttribute('aria-setsize') || '',
                  containerId: container?.id || '',
                  containerRole: container?.getAttribute('role') || '',
                  rowNextControls: rowNext?.getAttribute('aria-controls') || '',
                  rowNextControlsContainer: String(Boolean(container?.id) && rowNext?.getAttribute('aria-controls') === container.id)
                };
                """, index);
        return values.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.valueOf(entry.getValue())
                ));
    }

    private String connectabilityServerSourceKeys() {
        return String.valueOf(((JavascriptExecutor) driver).executeScript("""
                return Object.keys(state.nodeConnectabilityServer?.resultsBySourceKey || {})
                  .sort()
                  .join('|');
                """));
    }

    private Map<String, String> schemaOutlineA11yState(String path) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) ((JavascriptExecutor) driver).executeScript("""
                const path = String(arguments[0] || '');
                const rows = [...document.querySelectorAll('#selected-operator-editor [data-schema-outline-row]')];
                const row = rows.find((candidate) => candidate.dataset.schemaOutlinePath === path);
                if (!row) {
                  return { missing: 'true' };
                }
                const container = row.closest('[data-schema-outline]');
                const search = document.querySelector('#selected-operator-editor [data-schema-outline-search]');
                const meta = document.querySelector('#selected-operator-editor [data-schema-outline-search-meta]');
                const describedIds = (row.getAttribute('aria-describedby') || '')
                  .split(/\\s+/)
                  .filter(Boolean);
                const driftLabels = describedIds
                  .map((id) => document.getElementById(id)?.textContent.trim() || '')
                  .filter(Boolean)
                  .join('|');
                return {
                  containerId: container?.id || '',
                  containerRole: container?.getAttribute('role') || '',
                  containerLabel: container?.getAttribute('aria-label') || '',
                  searchControls: search?.getAttribute('aria-controls') || '',
                  searchControlsContainer: String(Boolean(container?.id)
                    && (search?.getAttribute('aria-controls') || '').split(/\\s+/).includes(container.id)),
                  searchDescribedBy: search?.getAttribute('aria-describedby') || '',
                  metaId: meta?.id || '',
                  metaLive: meta?.getAttribute('aria-live') || '',
                  rowId: row.id || '',
                  rowRole: row.getAttribute('role') || '',
                  pos: row.getAttribute('aria-posinset') || '',
                  setsize: row.getAttribute('aria-setsize') || '',
                  rowDrift: row.dataset.schemaDrift || '',
                  rowDescribedBy: row.getAttribute('aria-describedby') || '',
                  driftLabels
                };
                """, path);
        return values.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.valueOf(entry.getValue())
                ));
    }

    private void waitForConnectabilityServerReady(WebDriverWait wait, String nodeId, int offset) {
        waitForConnectabilityServerReady(wait, nodeId, offset, "");
    }

    private void waitForConnectabilityServerReady(WebDriverWait wait, String nodeId, int offset, String query) {
        waitForConnectabilityServerReady(wait, nodeId, offset, query, "");
    }

    private void waitForConnectabilityServerReady(WebDriverWait wait,
                                                  String nodeId,
                                                  int offset,
                                                  String query,
                                                  String targetStatus) {
        waitForConnectabilityServerReady(wait, nodeId, offset, query, targetStatus, "");
    }

    private void waitForConnectabilityServerReady(WebDriverWait wait,
                                                  String nodeId,
                                                  int offset,
                                                  String query,
                                                  String targetStatus,
                                                  String facetFiltersKey) {
        try {
            wait.until(ignored -> Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript("""
                    const server = state.nodeConnectabilityServer;
                    return Boolean(server)
                      && server.nodeId === arguments[0]
                      && server.status === 'ready'
                      && Number(server.offset || 0) === Number(arguments[1])
                      && String(server.query || '').toLowerCase() === String(arguments[2] || '').toLowerCase()
                      && String(server.targetStatus || '').toLowerCase() === String(arguments[3] || '').toLowerCase()
                      && nodeConnectabilityServerFacetFiltersKey(server.facetFilters || {}) === String(arguments[4] || '');
                    """, nodeId, offset, query, targetStatus, facetFiltersKey)));
        } catch (TimeoutException ex) {
            Object diagnostics = ((JavascriptExecutor) driver).executeScript("""
                    try {
                      const resources = performance.getEntriesByType('resource')
                        .filter((entry) => String(entry.name || '').includes('/api/visual/connections/candidates'))
                        .map((entry) => ({
                          name: entry.name,
                          duration: entry.duration,
                          responseStart: entry.responseStart,
                          responseEnd: entry.responseEnd,
                          transferSize: entry.transferSize,
                          encodedBodySize: entry.encodedBodySize,
                          decodedBodySize: entry.decodedBodySize
                        }));
                      return JSON.stringify({
                        serverState: state.nodeConnectabilityServer || null,
                        resources
                      });
                    } catch (error) {
                      return `unavailable: ${error.message}`;
                    }
                    """);
            throw new AssertionError("Connectability server candidates did not become ready for node '%s' at offset %d query '%s' status '%s' facets '%s'. diagnostics=%s"
                    .formatted(nodeId, offset, query, targetStatus, facetFiltersKey, diagnostics), ex);
        }
    }

    private void waitForComposer(WebDriverWait wait) {
        try {
            wait.until(ignored -> "composer".equalsIgnoreCase(textOf(By.id("input-title"))));
        } catch (TimeoutException ex) {
            throw new AssertionError("Composer did not become active. title='%s', inputTitle='%s', output='%s'"
                    .formatted(textOf(By.id("scenario-title")), textOf(By.id("input-title")), textOf(By.id("output"))),
                    ex);
        }
    }

    private void waitForText(WebDriverWait wait, By locator, String expected) {
        try {
            wait.until(ignored -> textOf(locator).contains(expected));
        } catch (TimeoutException ex) {
            throw new AssertionError("Expected text '%s' was not present in %s. Actual='%s', output='%s'"
                    .formatted(expected, locator, textOf(locator), textOf(By.id("output"))), ex);
        }
    }

    private void waitForAnyText(WebDriverWait wait, By locator, String... expectedValues) {
        try {
            wait.until(ignored -> {
                String actual = textOf(locator);
                for (String expected : expectedValues) {
                    if (actual.contains(expected)) {
                        return true;
                    }
                }
                return false;
            });
        } catch (TimeoutException ex) {
            throw new AssertionError("None of the expected texts %s were present in %s. Actual='%s', output='%s'"
                    .formatted(List.of(expectedValues), locator, textOf(locator), textOf(By.id("output"))), ex);
        }
    }

    private String textOf(By locator) {
        try {
            return driver.findElement(locator).getText();
        } catch (RuntimeException ex) {
            return "<missing>";
        }
    }

    private String svgTextContent(String selector) {
        return String.valueOf(((JavascriptExecutor) driver).executeScript("""
                return Array.from(document.querySelectorAll(arguments[0]))
                    .map((element) => element.textContent || '')
                    .join('|');
                """, selector));
    }

    private List<String> optionTexts(WebDriverWait wait, By locator) {
        return wait.until(ignored -> {
            try {
                List<String> texts = driver.findElements(locator).stream()
                        .map(WebElement::getText)
                        .toList();
                return texts.isEmpty() ? null : texts;
            } catch (StaleElementReferenceException ex) {
                return null;
            }
        });
    }

    private String valueOf(By locator) {
        try {
            WebElement element = driver.findElement(locator);
            return String.valueOf(element.getAttribute("value"));
        } catch (RuntimeException ex) {
            return "<missing>";
        }
    }

    private void setControlValue(WebElement element, String value) {
        scrollIntoView(element);
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = arguments[1];"
                        + "arguments[0].dispatchEvent(new Event('input', { bubbles: true }));"
                        + "arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
                element,
                value
        );
    }

    private void setControlValue(WebDriverWait wait, By locator, String value) {
        wait.until(ignored -> {
            try {
                setControlValue(driver.findElement(locator), value);
                return true;
            } catch (NoSuchElementException | StaleElementReferenceException ex) {
                return false;
            }
        });
    }

    private void scrollIntoView(WebElement element) {
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'center', inline: 'nearest'});",
                element
        );
    }
}
