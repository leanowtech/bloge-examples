package com.leanowtech.bloge.gateway.visual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
    private static final Path MAC_CHROME_BINARY = Path.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    );

    @Autowired
    private WritableResourceRegistry resourceRegistry;

    @LocalServerPort
    private int port;

    private WebDriver driver;

    @BeforeEach
    void seedDemoDescriptorsForRandomPort() {
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

        click(wait, By.id("save-resource-contract"));
        waitForText(wait,
                By.id("resource-contract-status-message"),
                "Saved loan-applicant-service.getProfile");

        importSampleOperatorLibrary(wait);

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

        click(wait, By.id("run-scenario"));
        waitForText(wait, By.id("visual-check-status"), "Run completed.");
        waitForText(wait, By.id("visual-diagnostics"), "visual.graph.unreachableNode");
        waitForText(wait, By.id("output"), "\"success\": true");
        waitForText(wait, By.id("output"), "\"output\": false");

        click(wait, By.id("publish-visual-draft"));
        waitForText(wait, By.id("visual-check-status"), "Published");
        waitForText(wait, By.id("publication-status"), "Published");

        click(wait, By.id("run-publication"));
        waitForText(wait, By.id("publication-status"), "Ran");
        waitForText(wait, By.id("output"), "\"publicationRun\"");
        waitForText(wait, By.id("output"), "\"success\": true");
        waitForText(wait, By.id("output"), "\"output\": false");
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
        waitForText(wait, By.id("connection-status"), "Type mismatch");
        waitForText(wait, By.id("connection-status"), "string cannot feed integer");
        assertThat(driver.findElements(By.cssSelector("#diagram path.edge")).size()).isEqualTo(edgeCount);
        assertThat(valueOf(By.id("composer-dsl")))
                .doesNotContain("loanPolicy.output.decision >= 700")
                .contains("ctx.score >= 700");
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

    private WebDriver newChromeDriverOrSkip() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--no-sandbox",
                "--window-size=1440,1100"
        );
        if (Files.isExecutable(MAC_CHROME_BINARY)) {
            options.setBinary(MAC_CHROME_BINARY.toString());
        }
        try {
            return new ChromeDriver(options);
        } catch (WebDriverException ex) {
            Assumptions.abort("Chrome/WebDriver is unavailable: " + ex.getMessage());
            return null;
        }
    }

    private void click(WebDriverWait wait, By locator) {
        WebElement element = wait.until(ExpectedConditions.elementToBeClickable(locator));
        scrollIntoView(element);
        element.click();
    }

    private void importSampleOperatorLibrary(WebDriverWait wait) {
        click(wait, By.id("import-library"));
        waitForAnyText(wait, By.id("library-status"), "Imported risk-policy", "Replaced risk-policy");
    }

    private void importOperatorLibrary(WebDriverWait wait, OperatorLibrary library) throws JsonProcessingException {
        WebElement editor = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("operator-library-json")));
        setControlValue(editor, OBJECT_MAPPER.writeValueAsString(library));
        click(wait, By.id("import-library"));
        waitForAnyText(wait, By.id("library-status"),
                "Imported " + library.libraryId(),
                "Replaced " + library.libraryId());
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
