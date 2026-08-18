package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in real-browser producer for the fixed 60-cell S0-AC-01 result artifact.
 *
 * <p>Run this class explicitly through {@code scripts/run-capability-studio-browser-matrix.sh}.
 * Its {@code IT} suffix keeps the expensive matrix out of the default Surefire include set.</p>
 */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "gateway.capability-studio.demo.enabled=true",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=browser-matrix-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=browser-matrix-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=browser-matrix-request-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=browser-matrix-request-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=browser-matrix-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:capability-studio-browser-matrix;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:capability-studio-browser-matrix-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
@Timeout(1800)
class CapabilityStudioBrowserMatrixProducerIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path MAC_CHROME_BINARY = Path.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
    private static final Path CHROMEDRIVER_CACHE = Path.of(
            System.getProperty("user.home"), ".cache", "selenium", "chromedriver");
    private static final String BASELINE_ID = "cancellation-fee-canonical-baseline";
    private static final String BASELINE_FINGERPRINT =
            "sha256:8abed67545bd784929162a5639bc91f587a50d195e0fcca9cab5f47b1cda9544";
    private static final List<String> MOBILE_TASKS = List.of(
            "overview", "contract", "scenarios", "quality", "tutorial", "feature", "tool");

    @LocalServerPort
    private int port;

    private ChromeDriver driver;
    private ChromeDriverService driverService;
    private Path evidenceDirectory;
    private Path axeSource;
    private final List<String> failures = new ArrayList<>();

    @Test
    void producesTheFixedCandidateBoundBrowserMatrix() throws Exception {
        Path repository = repositoryRoot();
        Path output = outputPath(repository);
        evidenceDirectory = output.getParent().resolve("browser-matrix-evidence");
        Files.createDirectories(evidenceDirectory);
        axeSource = repository.resolve(
                "resource-gateway-examples/src/main/frontend/node_modules/axe-core/axe.min.js");
        assertThat(axeSource).as("axe-core source for real browser observations").isRegularFile();

        Path driverExecutable = chromeDriverExecutable();
        String sourceCommit = propertyOr("capability.browser.matrix.source-commit",
                command(repository, "git", "rev-parse", "HEAD"));
        String sourceTreeStatus = propertyOr("capability.browser.matrix.source-tree-status",
                command(repository, "git", "status", "--porcelain=v1").isBlank()
                        ? "CLEAN" : "DIRTY");
        Path candidateArtifact = Path.of(propertyOr(
                "capability.browser.matrix.candidate-artifact",
                repository.resolve("resource-gateway-examples/target/"
                        + "bloge-examples-resource-gateway-1.0.0.jar").toString()));
        assertThat(candidateArtifact).as("candidate artifact bound to browser matrix").isRegularFile();
        String artifactFingerprint = CapabilityStudioBrowserMatrixArtifact.fingerprint(
                Files.readAllBytes(candidateArtifact));
        String buildRef = propertyOr("capability.browser.matrix.build-ref",
                "build:resource-gateway:" + sourceCommit.substring(0, 12));
        String candidateRevision = propertyOr(
                "capability.browser.matrix.candidate-revision", sourceCommit);
        String axeVersion = JSON.readTree(Files.readString(
                        axeSource.getParent().resolve("package.json")))
                .path("version").asText();
        String driverVersion = command(repository, driverExecutable.toString(), "--version")
                .replace("ChromeDriver ", "").split(" ")[0];
        String browserVersion = probeBrowserVersion(driverExecutable);

        ObjectNode environmentMaterial = JSON.createObjectNode()
                .put("profile", "test")
                .put("browserName", "chrome")
                .put("browserVersion", browserVersion)
                .put("driverVersion", driverVersion)
                .put("axeVersion", axeVersion)
                .put("os", System.getProperty("os.name") + "-" + System.getProperty("os.arch"))
                .put("java", System.getProperty("java.version"));
        CapabilityStudioBrowserMatrixArtifact.Environment environment =
                new CapabilityStudioBrowserMatrixArtifact.Environment(
                        CapabilityStudioBrowserMatrixArtifact.fingerprint(environmentMaterial),
                        "test", "chrome", browserVersion, driverVersion, axeVersion);
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        String resultId = "BMR-" + sourceCommit.substring(0, 12) + "-"
                + startedAt.toEpochSecond();
        CapabilityStudioBrowserMatrixArtifact artifact =
                new CapabilityStudioBrowserMatrixArtifact(
                        resultId,
                        1,
                        "v1",
                        new CapabilityStudioBrowserMatrixArtifact.Candidate(
                                buildRef,
                                candidateRevision,
                                artifactFingerprint,
                                sourceCommit,
                                sourceTreeStatus),
                        new CapabilityStudioBrowserMatrixArtifact.Baseline(
                                BASELINE_ID, 1, BASELINE_FINGERPRINT),
                        environment,
                        startedAt);

        try {
            for (String locale : CapabilityStudioBrowserMatrixArtifact.LOCALES) {
                for (CapabilityStudioBrowserMatrixArtifact.Viewport viewport
                        : CapabilityStudioBrowserMatrixArtifact.VIEWPORTS) {
                    openBrowser(driverExecutable, viewport);
                    try {
                        for (String goldenPath : CapabilityStudioBrowserMatrixArtifact.GOLDEN_PATHS) {
                            artifact.record(executeCell(goldenPath, locale, viewport));
                        }
                    } finally {
                        closeBrowser();
                    }
                }
            }
        } finally {
            closeBrowser();
        }

        ObjectNode result = artifact.build(OffsetDateTime.now(ZoneOffset.UTC));
        Files.createDirectories(output.getParent());
        Files.write(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        Path failureLog = output.resolveSibling("capability-studio-browser-matrix-failures.txt");
        if (failures.isEmpty()) {
            Files.deleteIfExists(failureLog);
        } else {
            Files.writeString(failureLog, String.join(System.lineSeparator(), failures));
        }

        assertThat(result.path("cells")).hasSize(60);
        assertThat(result.at("/summary/actualCellCount").asInt()).isEqualTo(60);
        assertThat(result.at("/summary/failedCellCount").asInt())
                .withFailMessage("Browser matrix produced failed cells; inspect %s", failureLog)
                .isZero();
        assertThat(result.at("/summary/incompleteCellCount").asInt()).isZero();
        if (Boolean.getBoolean("capability.browser.matrix.require-complete")) {
            assertThat(result.path("resultStatus").asText())
                    .withFailMessage("Candidate binding did not produce a COMPLETE matrix at %s", output)
                    .isEqualTo("COMPLETE");
        }
    }

    private CapabilityStudioBrowserMatrixArtifact.Observation executeCell(
            String goldenPath,
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport) throws IOException {
        KeyboardJourney keyboard = new KeyboardJourney();
        Throwable failure = null;
        try {
            setViewport(viewport);
            openDefault(locale);
            switch (goldenPath) {
                case "GP-01" -> gp01(locale, viewport, keyboard);
                case "GP-02" -> gp02(locale, viewport, keyboard);
                case "GP-03" -> gp03(locale, viewport, keyboard);
                case "GP-04" -> gp04(locale, viewport, keyboard);
                case "GP-05" -> gp05(locale, viewport, keyboard);
                case "GP-06" -> gp06(locale, viewport, keyboard);
                case "GP-07" -> gp07(locale, viewport, keyboard);
                case "GP-08" -> gp08(locale, viewport, keyboard);
                case "GP-09" -> gp09(locale, viewport, keyboard);
                case "GP-10" -> gp10(locale, viewport, keyboard);
                default -> throw new IllegalArgumentException("Unknown golden path " + goldenPath);
            }
        } catch (Throwable caught) {
            failure = caught;
        }

        BrowserAudit audit = audit();
        if (failure != null) {
            failures.add(goldenPath + ":" + locale + ":" + viewport.coordinate()
                    + " " + failure.getClass().getSimpleName() + " "
                    + String.valueOf(failure.getMessage()).replace('\n', ' '));
        }
        byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        String cellFile = goldenPath.toLowerCase(Locale.ROOT) + "-" + locale + "-"
                + viewport.coordinate() + ".png";
        Files.write(evidenceDirectory.resolve(cellFile), screenshot);
        CapabilityStudioBrowserMatrixArtifact.EvidenceRef evidence =
                new CapabilityStudioBrowserMatrixArtifact.EvidenceRef(
                        "artifact:browser-matrix-evidence/" + cellFile,
                        CapabilityStudioBrowserMatrixArtifact.fingerprint(screenshot));

        boolean passed = failure == null
                && audit.actualViewport().equals(viewport)
                && !audit.pageHorizontalOverflow()
                && audit.axeSerious() == 0
                && audit.axeCritical() == 0
                && audit.technicalIdCount() == 0
                && audit.rawJsonCount() == 0
                && audit.browserErrorCount() == 0
                && keyboard.completed();
        if (!passed && failure == null) {
            failures.add(goldenPath + ":" + locale + ":" + viewport.coordinate()
                    + " observation-failed"
                    + " actualViewport=" + audit.actualViewport().coordinate()
                    + " overflow=" + audit.pageHorizontalOverflow()
                    + " overflowElements=" + audit.overflowElements()
                    + " axeSerious=" + audit.axeSerious()
                    + " axeCritical=" + audit.axeCritical()
                    + " technicalIds=" + audit.technicalIdCount()
                    + " rawJsonEditors=" + audit.rawJsonCount()
                    + " browserErrors=" + audit.browserErrorCount()
                    + " keyboardCompleted=" + keyboard.completed()
                    + " focusLosses=" + keyboard.focusLosses());
        }
        int p0 = failure == null && audit.axeCritical() == 0 && audit.browserErrorCount() == 0
                ? 0 : 1;
        int p1 = failure == null
                && !audit.pageHorizontalOverflow()
                && audit.axeSerious() == 0
                && audit.technicalIdCount() == 0
                && audit.rawJsonCount() == 0
                && keyboard.completed()
                ? 0 : 1;
        return new CapabilityStudioBrowserMatrixArtifact.Observation(
                goldenPath,
                locale,
                viewport,
                audit.actualViewport(),
                passed ? "PASS" : "FAIL",
                audit.pageHorizontalOverflow(),
                audit.axeSerious(),
                audit.axeCritical(),
                audit.technicalIdCount(),
                audit.rawJsonCount(),
                keyboard.completed(),
                keyboard.steps(),
                keyboard.focusLosses(),
                List.of(evidence),
                p0,
                p1);
    }

    private void gp01(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("overview", viewport, keyboard);
        WebElement view = waitFor(By.cssSelector("[data-testid='capability-overview']"));
        assertThat(view.getText()).contains("4", "1", "9");
        assertThat(driver.findElements(By.cssSelector(".capability-count-tile strong")))
                .extracting(WebElement::getText)
                .containsExactly("4", "1", "1", "9");
    }

    private void gp02(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("contract", viewport, keyboard);
        WebElement view = waitFor(By.cssSelector("[data-testid='capability-contract']"));
        assertThat(view.getText()).contains(
                locale.equals("zh-CN") ? "输入信息" : "Inputs",
                locale.equals("zh-CN") ? "成功结果" : "Success result",
                locale.equals("zh-CN") ? "可预期错误" : "Expected errors",
                locale.equals("zh-CN") ? "副作用" : "Side effects");
        assertThat(view.findElements(By.cssSelector("details[open]"))).isEmpty();
    }

    private void gp03(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("scenarios", viewport, keyboard);
        browserWait().until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".capability-scenario-list-item"), 9));
        keyboard.activate(By.cssSelector(".capability-scenario-list-item:nth-of-type(2)"), Keys.SPACE);
        WebElement view = waitFor(By.cssSelector("[data-testid='capability-scenarios']"));
        assertThat(view.getText()).contains(
                locale.equals("zh-CN") ? "业务验证分母" : "Business validation denominator",
                locale.equals("zh-CN") ? "业务目标" : "Business goal",
                "100%");
    }

    private void gp04(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("tutorial", viewport, keyboard);
        waitFor(By.cssSelector("[data-testid='capability-tutorial-branch']"));
        By duration = By.cssSelector(".capability-duration-input input");
        WebElement input = keyboard.focus(duration);
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), "1200");
        keyboard.recordStep();
        keyboard.activate(By.cssSelector(".capability-editor-actions button"), Keys.ENTER);
        WebElement success = waitFor(By.cssSelector("[data-testid='capability-preflight-success']"));
        assertThat(success.getText()).contains("0");
        assertThat(success.getText()).contains(
                locale.equals("zh-CN") ? "真实接口调用" : "Real external calls");
    }

    private void gp05(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("feature", viewport, keyboard);
        browserWait().until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".feature-dag-node"), 6));
        WebElement cases = keyboard.focus(By.id("feature-rehearsal-case"));
        cases.sendKeys(Keys.HOME,
                Keys.ARROW_DOWN, Keys.ARROW_DOWN, Keys.ARROW_DOWN,
                Keys.ARROW_DOWN, Keys.ARROW_DOWN, Keys.ENTER);
        keyboard.recordStep();
        browserWait().until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".feature-dag-edge-label"), 5));
        assertThat(driver.findElements(By.cssSelector(".feature-dag-node"))).hasSize(6);
        assertThat(driver.findElements(By.cssSelector(".feature-dag-edge-label"))).hasSize(5);
    }

    private void gp06(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("feature", viewport, keyboard);
        browserWait().until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".feature-dag-node"), 6));
        keyboard.activate(By.cssSelector(
                ".capability-segmented-control button:last-child"), Keys.ENTER);
        browserWait().until(ExpectedConditions.textToBePresentInElementLocated(
                By.tagName("body"), "DEMO-ORDER-20260818-001"));
        WebElement view = driver.findElement(By.cssSelector(
                "[data-testid='capability-feature-rehearsal']"));
        assertThat(view.getText()).contains(
                locale.equals("zh-CN") ? "真实调用" : "Real calls",
                "0", "DEMO-ORDER-20260818-001");
    }

    private void gp07(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("tool", viewport, keyboard);
        WebElement view = waitFor(By.cssSelector("[data-testid='capability-tool']"));
        assertThat(view.getText()).contains(
                locale.equals("zh-CN") ? "业务正确性验证" : "Business correctness verification",
                locale.equals("zh-CN") ? "固定场景" : "Fixed scenarios",
                locale.equals("zh-CN") ? "真实接口" : "Real APIs");
        keyboard.focus(By.cssSelector("[data-testid='run-governed-baseline']"));
        keyboard.recordStep();
    }

    private void gp08(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("tool", viewport, keyboard);
        keyboard.activate(By.cssSelector("[data-testid='run-governed-baseline']"), Keys.ENTER);
        WebElement result = waitFor(By.cssSelector("[data-testid='governed-baseline-result']"));
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table tbody tr"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table .capability-evidence-matrix-button"))).hasSize(27);
        assertThat(result.getText()).contains("27 / 27", "9 / 9");
    }

    private void gp09(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("quality", viewport, keyboard);
        waitFor(By.cssSelector("[data-testid='capability-quality-impact']"));
        keyboard.activate(By.cssSelector(
                ".capability-quality-case-item:nth-of-type(2)"), Keys.ENTER);
        browserWait().until(webDriver -> "true".equals(webDriver.findElements(
                By.cssSelector(".capability-quality-case-item")).get(1).getAttribute("aria-pressed")));
        assertThat(driver.findElements(By.cssSelector(".capability-quality-case-item"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(".capability-quality-graph-node"))).hasSize(37);
        assertThat(driver.findElements(By.cssSelector(".capability-quality-edge"))).hasSize(81);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-graph-node.selected"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(".capability-quality-edge.selected"))).hasSize(8);
    }

    private void gp10(
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        openTask("tool", viewport, keyboard);
        keyboard.activate(By.cssSelector("[data-testid='run-governed-baseline']"), Keys.ENTER);
        waitFor(By.cssSelector("[data-testid='governed-baseline-result']"));
        keyboard.activate(By.cssSelector(
                "[data-testid='governed-evidence-case-compensation-history-timeout-1']"),
                Keys.ENTER);
        WebElement evidence = waitFor(By.cssSelector(
                "[data-testid='governed-run-evidence-panel']"));
        assertThat(evidence.getText()).contains(
                locale.equals("zh-CN") ? "按原运行读取，没有重新执行"
                        : "Read from the original run; nothing was re-executed.");
        keyboard.activate(By.cssSelector(
                "[data-testid='governed-run-evidence-panel'] "
                        + ".capability-exact-actions button"), Keys.ENTER);
        waitFor(By.cssSelector("[data-testid='capability-feature-rehearsal']"));
        waitFor(By.cssSelector(".feature-dag-node.feature-node-focus"));
        assertThat(driver.findElements(By.cssSelector(".feature-dag-node"))).hasSize(6);
        assertThat(driver.getCurrentUrl()).contains("task=feature", "runId=", "scenarioId=");
    }

    private void openTask(
            String task,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) {
        if (viewport.width() == 390) {
            int targetIndex = MOBILE_TASKS.indexOf(task);
            assertThat(targetIndex).as("known mobile task index").isNotNegative();
            if (targetIndex == 0) {
                keyboard.activate(By.id("capability-mobile-task-overview"), Keys.ARROW_RIGHT);
                browserWait().until(webDriver -> "true".equals(webDriver.findElement(
                        By.id("capability-mobile-task-contract")).getAttribute("aria-selected")));
                keyboard.activate(By.id("capability-mobile-task-contract"), Keys.ARROW_LEFT);
                browserWait().until(webDriver -> "true".equals(webDriver.findElement(
                        By.id("capability-mobile-task-overview")).getAttribute("aria-selected")));
                assertThat(driver.switchTo().activeElement().getAttribute("id"))
                        .isEqualTo("capability-mobile-task-overview");
                return;
            }
            for (int selectedIndex = 1; selectedIndex <= targetIndex; selectedIndex++) {
                String currentTask = MOBILE_TASKS.get(selectedIndex - 1);
                WebElement tab = keyboard.focus(By.id("capability-mobile-task-" + currentTask));
                assertThat(tab).isEqualTo(driver.switchTo().activeElement());
                tab.sendKeys(Keys.ARROW_RIGHT);
                keyboard.recordStep();
                String expectedTask = MOBILE_TASKS.get(selectedIndex);
                browserWait().until(webDriver -> "true".equals(webDriver.findElement(
                        By.id("capability-mobile-task-" + expectedTask))
                        .getAttribute("aria-selected")));
                assertThat(driver.switchTo().activeElement().getAttribute("id"))
                        .isEqualTo("capability-mobile-task-" + expectedTask);
            }
        } else if ("contract".equals(task)) {
            List<WebElement> buttons = driver.findElements(By.cssSelector(
                    ".capability-sidebar .capability-task-button"));
            keyboard.activate(buttons.get(1), Keys.ENTER);
        } else if ("overview".equals(task)) {
            List<WebElement> buttons = driver.findElements(By.cssSelector(
                    ".capability-sidebar .capability-task-button"));
            keyboard.activate(buttons.get(0), Keys.ENTER);
        } else {
            keyboard.activate(By.cssSelector("[data-testid='capability-task-" + task + "']"),
                    Keys.ENTER);
        }
    }

    private void openDefault(String locale) {
        driver.get("http://localhost:" + port + "/capabilities/?lang=" + locale);
        waitFor(By.cssSelector("[data-testid='capability-overview']"));
        assertThat(driver.findElement(By.tagName("html")).getAttribute("lang"))
                .isEqualTo(locale.equals("zh-CN") ? "zh-CN" : "en");
    }

    private BrowserAudit audit() throws IOException {
        JavascriptExecutor javascript = driver;
        @SuppressWarnings("unchecked")
        Map<String, Number> viewport = (Map<String, Number>) javascript.executeScript(
                "return {width: window.innerWidth, height: window.innerHeight};");
        Number overflow = (Number) javascript.executeScript("""
                const root = document.scrollingElement || document.documentElement;
                return Math.max(0, root.scrollWidth - window.innerWidth);
                """);
        @SuppressWarnings("unchecked")
        List<String> overflowElements = (List<String>) javascript.executeScript("""
                return [...document.querySelectorAll('body *')]
                  .filter(node => {
                    const style = getComputedStyle(node);
                    if (style.display === 'none' || style.visibility === 'hidden') return false;
                    const rect = node.getBoundingClientRect();
                    return rect.right > window.innerWidth + 2 || rect.left < -2;
                  })
                  .slice(0, 8)
                  .map(node => {
                    const id = node.id ? `#${node.id}` : '';
                    const classes = [...node.classList].slice(0, 3).map(name => `.${name}`).join('');
                    return `${node.tagName.toLowerCase()}${id}${classes}`;
                  });
                """);
        String body = driver.findElement(By.tagName("body")).getText();
        int technicalIds = countTechnicalIds(body);
        Number rawJson = (Number) javascript.executeScript("""
                return [...document.querySelectorAll(
                  'textarea:not([readonly]), [contenteditable="true"], '
                    + '[data-testid*="raw-json-editor"], [data-testid*="rawJsonEditor"]')]
                  .filter(node => {
                    const style = getComputedStyle(node);
                    const value = String(node.value ?? node.textContent ?? '').trim();
                    return style.display !== 'none' && style.visibility !== 'hidden'
                      && (value.startsWith('{') || value.startsWith('['));
                  }).length;
                """);
        int browserErrors = driver.findElements(By.cssSelector(
                "[data-testid='capability-load-error'], "
                        + "[data-testid='governed-run-evidence-error'], "
                        + "[data-testid='capability-quality-impact-error'], "
                        + ".capability-governed-error, .capability-feature-error, "
                        + ".capability-operation-error[role='alert']")).size();
        AxeCounts axe = axeCounts();
        return new BrowserAudit(
                new CapabilityStudioBrowserMatrixArtifact.Viewport(
                        viewport.get("width").intValue(), viewport.get("height").intValue()),
                overflow.doubleValue() > 2.0,
                axe.serious(),
                axe.critical(),
                technicalIds,
                rawJson.intValue(),
                browserErrors,
                List.copyOf(overflowElements));
    }

    @SuppressWarnings("unchecked")
    private AxeCounts axeCounts() throws IOException {
        driver.executeScript(Files.readString(axeSource));
        Map<String, Object> report = (Map<String, Object>) driver.executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                window.axe.run(document).then(result => done({
                  error: '',
                  serious: result.violations.filter(item => item.impact === 'serious').length,
                  critical: result.violations.filter(item => item.impact === 'critical').length
                })).catch(error => done({
                  error: String(error), serious: 0, critical: 1
                }));
                """);
        if (!String.valueOf(report.get("error")).isBlank()) {
            failures.add("axe " + report.get("error"));
        }
        return new AxeCounts(
                ((Number) report.get("serious")).intValue(),
                ((Number) report.get("critical")).intValue());
    }

    private int countTechnicalIds(String body) {
        int count = 0;
        for (String forbidden : List.of(
                "RG.CAPABILITY_STUDIO.",
                "METADATA_READY_RUNTIME_EVIDENCE_PENDING",
                "CONTRACT_READY_RUNTIME_PENDING",
                "DAG_CONTRACT_READY_RUNTIME_PENDING",
                "DEVELOPMENT_TEST_OWNED")) {
            int position = 0;
            while ((position = body.indexOf(forbidden, position)) >= 0) {
                count++;
                position += forbidden.length();
            }
        }
        return count;
    }

    private void openBrowser(
            Path executable,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport) {
        ChromeOptions options = chromeOptions(viewport);
        driverService = new ChromeDriverService.Builder()
                .usingDriverExecutable(executable.toFile())
                .usingAnyFreePort()
                .build();
        driver = new ChromeDriver(driverService, options);
        setViewport(viewport);
    }

    private String probeBrowserVersion(Path executable) {
        openBrowser(executable, CapabilityStudioBrowserMatrixArtifact.VIEWPORTS.get(0));
        try {
            return driver.getCapabilities().getBrowserVersion();
        } finally {
            closeBrowser();
        }
    }

    private ChromeOptions chromeOptions(CapabilityStudioBrowserMatrixArtifact.Viewport viewport) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--no-sandbox",
                "--window-size=" + viewport.width() + "," + viewport.height());
        if (Files.isExecutable(MAC_CHROME_BINARY)) {
            options.setBinary(MAC_CHROME_BINARY.toString());
        }
        return options;
    }

    private void closeBrowser() {
        if (driver != null) {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
                // The process-owned service is stopped below.
            } finally {
                driver = null;
            }
        }
        if (driverService != null) {
            driverService.stop();
            driverService = null;
        }
    }

    private void setViewport(CapabilityStudioBrowserMatrixArtifact.Viewport viewport) {
        driver.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
                "width", viewport.width(),
                "height", viewport.height(),
                "deviceScaleFactor", 1,
                "mobile", false));
        Number width = (Number) driver.executeScript("return window.innerWidth;");
        Number height = (Number) driver.executeScript("return window.innerHeight;");
        assertThat(width.intValue()).isEqualTo(viewport.width());
        assertThat(height.intValue()).isEqualTo(viewport.height());
    }

    private WebElement waitFor(By locator) {
        return browserWait().until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    private WebDriverWait browserWait() {
        return new WebDriverWait(driver, WAIT_TIMEOUT);
    }

    private Path chromeDriverExecutable() throws IOException {
        String configured = System.getProperty("webdriver.chrome.driver", "").trim();
        if (!configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return Path.of(configured);
        }
        try (var paths = Files.find(CHROMEDRIVER_CACHE, 4, (path, attributes) ->
                attributes.isRegularFile()
                        && "chromedriver".equals(path.getFileName().toString())
                        && Files.isExecutable(path))) {
            Path cached = paths.sorted(Comparator.reverseOrder()).findFirst().orElse(null);
            assertThat(cached).as("ChromeDriver for real browser matrix").isNotNull();
            return cached;
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("resource-gateway-examples"))) {
            return current;
        }
        if ("resource-gateway-examples".equals(current.getFileName().toString())) {
            return current.getParent();
        }
        throw new IllegalStateException("repository root cannot be resolved from " + current);
    }

    private Path outputPath(Path repository) {
        return Path.of(propertyOr(
                "capability.browser.matrix.output",
                repository.resolve("resource-gateway-examples/target/acceptance/"
                        + "capability-studio-browser-matrix-result-v1.json").toString()));
    }

    private String propertyOr(String name, String fallback) {
        String value = System.getProperty(name, "").trim();
        return value.isBlank() ? fallback : value;
    }

    private String command(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.waitFor() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
            }
            return output;
        } catch (IOException failure) {
            throw new IllegalStateException(String.join(" ", command) + " failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(String.join(" ", command) + " was interrupted", interrupted);
        }
    }

    private record BrowserAudit(
            CapabilityStudioBrowserMatrixArtifact.Viewport actualViewport,
            boolean pageHorizontalOverflow,
            int axeSerious,
            int axeCritical,
            int technicalIdCount,
            int rawJsonCount,
            int browserErrorCount,
            List<String> overflowElements) {
    }

    private record AxeCounts(int serious, int critical) {
    }

    private final class KeyboardJourney {
        private int steps;
        private int focusLosses;

        WebElement focus(By locator) {
            return focus(browserWait().until(ExpectedConditions.presenceOfElementLocated(locator)));
        }

        WebElement focus(WebElement target) {
            for (int attempt = 0; attempt < 120; attempt++) {
                WebElement active = driver.switchTo().activeElement();
                if (target.equals(active)) {
                    return target;
                }
                active.sendKeys(Keys.TAB);
            }
            focusLosses++;
            return target;
        }

        void activate(By locator, Keys key) {
            activate(focus(locator), key);
        }

        void activate(WebElement target, Keys key) {
            WebElement focused = focus(target);
            if (!focused.equals(driver.switchTo().activeElement())) {
                focusLosses++;
            }
            focused.sendKeys(key);
            steps++;
        }

        void recordStep() {
            steps++;
        }

        int steps() {
            return steps;
        }

        int focusLosses() {
            return focusLosses;
        }

        boolean completed() {
            return steps > 0 && focusLosses == 0;
        }
    }
}
