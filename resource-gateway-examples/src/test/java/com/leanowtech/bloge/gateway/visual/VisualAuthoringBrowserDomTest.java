package com.leanowtech.bloge.gateway.visual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real browser regression coverage for the visual composer.
 */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "spring.datasource.url=jdbc:h2:mem:visual-authoring-browser-dom;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class VisualAuthoringBrowserDomTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(12);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final Path MAC_CHROME_BINARY = Path.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    );
    private static final Path SELENIUM_CHROMEDRIVER_CACHE = Path.of(
            System.getProperty("user.home"), ".cache", "selenium", "chromedriver"
    );

    @Autowired
    private WritableResourceRegistry resourceRegistry;

    @Autowired
    private OperatorLibraryRegistry operatorLibraryRegistry;

    @Autowired
    private GraphDraftRepository graphDraftRepository;

    @LocalServerPort
    private int port;

    private static String chromeWebDriverUnavailableReason;

    private WebDriver driver;

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

        GatewayProperties properties = new GatewayProperties();
        properties.setBaseUrl("http://localhost:" + port + "/demo-upstream");
        properties.setSeedDescriptors(true);
        new ResourceDescriptorBootstrap(resourceRegistry, properties).seedDescriptors();
    }

    @AfterEach
    void closeBrowser() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Test
    void composerSupportsOpenApiSaveLibraryImportSchemaConnectionRunAndPublishInRealBrowser() {
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/examples/gateway");

        waitForComposer(wait);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-palette")));

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
        waitForText(wait, By.id("draft-dependencies"), "Rebase");

        click(wait, By.cssSelector("#diagram [data-node-id='riskEligibility']"));
        setControlValue(driver.findElement(By.cssSelector("[data-binding-expression][data-binding-path='score']")),
                "ctx.changedScore");
        waitForValue(wait, By.id("composer-dsl"), "ctx.changedScore");
        waitForText(wait, By.id("draft-dependencies"), "save or reload local changes before rebasing");
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

        click(wait, By.cssSelector("#draft-dependencies [data-draft-dependency-rebase='riskEligibility']"));
        waitForText(wait, By.id("draft-status"), "Rebased riskEligibility operator fingerprint");
        waitForText(wait, By.id("draft-dependencies"), "fingerprint current");
        wait.until(ignored -> driver.findElements(By.cssSelector(
                "#draft-dependencies [data-draft-dependency-rebase='riskEligibility']"
        )).isEmpty());

        click(wait, By.cssSelector("#draft-dependencies [data-draft-dependency-node='riskEligibility']"));
        waitForText(wait, By.id("selected-operator-editor"), "riskEligibility");
        waitForText(wait, By.id("selected-operator-editor"), "Snapshot current");
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
        Select asyncApiSelect = new Select(
                wait.until(ExpectedConditions.elementToBeClickable(By.id("asyncapi-operation-select")))
        );
        asyncApiSelect.selectByValue("0");
        asyncApiSelect.selectByValue("1");
        waitForText(wait, By.id("asyncapi-operation-summary"), "2 AsyncAPI operations selected");

        click(wait, By.id("project-asyncapi-library"));
        waitForText(wait, By.id("library-status"), "Projected AsyncAPI into risk-events-operators");
        waitForText(wait, By.id("library-profile"), "runtime-blocked");
        assertThat(valueOf(By.id("operator-library-json")))
                .contains("\"schemaVersion\": \"bloge.visualOperatorLibrary.v1\"")
                .contains("\"libraryId\": \"risk-events-operators\"")
                .contains("\"CreditDecision\"")
                .contains("\"RiskCommand\"")
                .contains("\"kind\": \"webhook\"")
                .contains("\"kind\": \"message-handler\"")
                .contains("\"mode\": \"message-handler\"")
                .doesNotContain("RiskAudit");

        click(wait, By.id("import-library"));
        waitForAnyText(wait, By.id("library-status"),
                "Imported risk-events-operators",
                "Replaced risk-events-operators");
        waitForText(wait, By.id("operator-palette"), "CreditDecision");
        waitForText(wait, By.id("operator-palette"), "RiskCommand");
        assertThat(driver.findElement(By.id("operator-palette")).getText())
                .doesNotContain("RiskAudit");
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
        setControlValue(driver.findElement(By.cssSelector("input[data-config-field='payload.score']")), "720");
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
        List<String> scoreSourceOptions = driver.findElements(By.cssSelector(
                        "[data-binding-source][data-binding-path='score'] option"))
                .stream()
                .map(WebElement::getText)
                .toList();
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
        assertThat(valueOf(By.id("publish-artifact-kind"))).isEqualTo("DESIGN");
        assertThat(driver.findElement(By.cssSelector(
                "#publish-artifact-kind option[value='EXECUTABLE']"
        )).getAttribute("disabled")).isNotNull();
        assertThat(driver.findElement(By.id("compile-visual-draft")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("run-scenario")).isEnabled()).isFalse();

        click(wait, By.id("save-draft"));
        waitForText(wait, By.id("draft-status"), "Saved");
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
        List<String> scoreSourceOptions = driver.findElements(By.cssSelector(
                        "[data-binding-source][data-binding-path='score'] option"))
                .stream()
                .map(WebElement::getText)
                .toList();
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
        List<String> scoreSourceOptions = driver.findElements(By.cssSelector(
                        "[data-binding-source][data-binding-path='score'] option"))
                .stream()
                .map(WebElement::getText)
                .toList();
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
        try {
            return new ChromeDriver(options);
        } catch (WebDriverException ex) {
            chromeWebDriverUnavailableReason = "Chrome/WebDriver is unavailable: " + ex.getMessage();
            Assumptions.abort(chromeWebDriverUnavailableReason);
            return null;
        }
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
        WebElement element = wait.until(ExpectedConditions.elementToBeClickable(locator));
        scrollIntoView(element);
        element.click();
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

    private void importOperatorLibrary(WebDriverWait wait, OperatorLibrary library) throws JsonProcessingException {
        WebElement editor = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-library-json")));
        setControlValue(editor, OBJECT_MAPPER.writeValueAsString(library));
        click(wait, By.id("import-library"));
        waitForAnyText(wait, By.id("library-status"),
                "Imported " + library.libraryId(),
                "Replaced " + library.libraryId(),
                "Review warnings");
        if (textOf(By.id("library-status")).contains("Review warnings")) {
            click(wait, By.id("import-library"));
        }
        waitForAnyText(wait, By.id("library-status"),
                "Imported " + library.libraryId(),
                "Replaced " + library.libraryId());
    }

    private void importYamlOperatorLibrary(WebDriverWait wait, OperatorLibrary library) throws JsonProcessingException {
        WebElement editor = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-library-json")));
        setControlValue(editor, YAML_MAPPER.writeValueAsString(library));
        click(wait, By.id("validate-library"));
        waitForText(wait, By.id("library-status"), "Operator library is valid.");
        waitForText(wait, By.id("library-profile"), library.libraryId());

        click(wait, By.id("import-library"));
        waitForAnyText(wait, By.id("library-status"),
                "Imported " + library.libraryId(),
                "Replaced " + library.libraryId(),
                "Review warnings");
        if (textOf(By.id("library-status")).contains("Review warnings")) {
            click(wait, By.id("import-library"));
        }
        waitForAnyText(wait, By.id("library-status"),
                "Imported " + library.libraryId(),
                "Replaced " + library.libraryId());
        waitForText(wait, By.id("library-profile"), "1 operators");
        waitForText(wait, By.id("library-profile"), "2 required");
        waitForText(wait, By.id("library-profile"), "Eligibility");
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
        new Actions(driver)
                .moveToElement(source)
                .clickAndHold()
                .pause(Duration.ofMillis(150))
                .moveByOffset(
                        (int) Math.round(targetCenter[0] - sourceCenter[0]),
                        (int) Math.round(targetCenter[1] - sourceCenter[1])
                )
                .pause(Duration.ofMillis(150))
                .release()
                .perform();
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
        wait.until(ignored -> driver.findElement(locator)
                .findElements(By.cssSelector("option[value=\"" + value + "\"]"))
                .size() > 0);
        WebElement element = wait.until(ExpectedConditions.elementToBeClickable(locator));
        scrollIntoView(element);
        new Select(element).selectByValue(value);
    }

    private void waitForValue(WebDriverWait wait, By locator, String expected) {
        try {
            wait.until(ignored -> valueOf(locator).contains(expected));
        } catch (TimeoutException ex) {
            throw new AssertionError("Expected value '%s' was not present in %s. Actual='%s', output='%s'"
                    .formatted(expected, locator, valueOf(locator), textOf(By.id("output"))), ex);
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

    private String valueOf(By locator) {
        WebElement element = driver.findElement(locator);
        return String.valueOf(element.getAttribute("value"));
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

    private void scrollIntoView(WebElement element) {
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'center', inline: 'nearest'});",
                element
        );
    }
}
