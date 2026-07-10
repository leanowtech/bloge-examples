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
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;

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
        classes = ResourceGatewayApplication.class,
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
    void reactAuthorCanvasLoadsPackagedBundleInRealBrowser() {
        assumeReactAuthorBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/author/");

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
                .isGreaterThanOrEqualTo(620.0);

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

    @Test
    void reactShowcaseLoadsPackagedScenarioParityInRealBrowser() {
        assumeReactShowcaseBundlePresent();
        driver = newChromeDriverOrSkip();
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get("http://localhost:" + port + "/showcase/");

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-testid='react-showcase']")));
        assertThat(driver.getTitle()).contains("BLOGE Visual Canvas");
        waitForText(wait, By.cssSelector(".topbar"), "Showcase");
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
        try {
            ChromeDriver browser = new ChromeDriver(service, options, webdriverClientConfig());
            browser.manage().timeouts()
                    .pageLoadTimeout(WEBDRIVER_TIMEOUT)
                    .scriptTimeout(WEBDRIVER_TIMEOUT)
                    .implicitlyWait(Duration.ZERO);
            return browser;
        } catch (WebDriverException ex) {
            service.stop();
            chromeWebDriverUnavailableReason = "Chrome/WebDriver is unavailable: " + ex.getMessage();
            Assumptions.abort(chromeWebDriverUnavailableReason);
            return null;
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
                    driver.switchTo().activeElement().sendKeys(String.valueOf(character));
                    return true;
                } catch (NoSuchElementException | StaleElementReferenceException ex) {
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
        assertThat(overflow.doubleValue()).isLessThanOrEqualTo(2.0);
    }

    private double elementClientHeight(WebElement element) {
        Number height = (Number) ((JavascriptExecutor) driver).executeScript(
                "return arguments[0].getBoundingClientRect().height;",
                element
        );
        return height.doubleValue();
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
                return JSON.stringify([...document.querySelectorAll('body *')]
                  .map((element) => {
                    const rect = element.getBoundingClientRect();
                    return {
                      tag: element.tagName.toLowerCase(),
                      id: element.id || '',
                      className: String(element.className || ''),
                      overflow: Math.max(0, rect.right - viewportWidth),
                      width: rect.width,
                      clipped: hasClippingAncestor(element)
                    };
                  })
                  .filter((item) => item.overflow > 2 && !item.clipped)
                  .sort((left, right) => right.overflow - left.overflow)
                  .slice(0, 5)
                  .concat([{
                    tag: 'metrics',
                    id: '',
                    className: '',
                    overflow: Math.max(
                      0,
                      (document.scrollingElement || document.documentElement).scrollWidth - viewportWidth,
                      document.body ? document.body.scrollWidth - viewportWidth : 0
                    ),
                    width: viewportWidth,
                    clipped: false
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
                    "mobile", true
            ));
        }
        wait.until(ignored -> {
            Number innerWidth = (Number) ((JavascriptExecutor) driver).executeScript("return window.innerWidth;");
            return innerWidth.doubleValue() <= width + 20;
        });
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
            Object serverState = ((JavascriptExecutor) driver).executeScript("""
                    try {
                      return JSON.stringify(state.nodeConnectabilityServer || null);
                    } catch (error) {
                      return `unavailable: ${error.message}`;
                    }
                    """);
            throw new AssertionError("Connectability server candidates did not become ready for node '%s' at offset %d query '%s' status '%s' facets '%s'. state=%s"
                    .formatted(nodeId, offset, query, targetStatus, facetFiltersKey, serverState), ex);
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
