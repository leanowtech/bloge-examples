package com.leanowtech.bloge.gateway.capabilitystudio;

import com.leanowtech.bloge.gateway.ResourceGatewayApplication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v149.fetch.Fetch;
import org.openqa.selenium.devtools.v149.fetch.model.HeaderEntry;
import org.openqa.selenium.devtools.v149.fetch.model.RequestPattern;
import org.openqa.selenium.devtools.v149.fetch.model.RequestStage;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-browser acceptance for Capability Studio GP-01 through GP-10. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "gateway.capability-studio.demo.enabled=true",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=browser-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=browser-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=browser-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=browser-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=browser-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:capability-studio-browser;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:capability-studio-browser-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
@Timeout(60)
class CapabilityStudioBrowserAcceptanceTest {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(12);
    private static final Path MAC_CHROME_BINARY = Path.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
    private static final Path CHROMEDRIVER_CACHE = Path.of(
            System.getProperty("user.home"), ".cache", "selenium", "chromedriver");

    @LocalServerPort
    private int port;

    private WebDriver driver;
    private ChromeDriverService driverService;

    @AfterEach
    void closeBrowser() {
        if (driver != null) {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
                // The process-owned service is stopped below even if the protocol close fails.
            } finally {
                driver = null;
            }
        }
        if (driverService != null) {
            driverService.stop();
            driverService = null;
        }
    }

    @Test
    void gp01ToGp03RemainTruthfulAndSelectableInChineseDesktop() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1440, 900);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=zh-CN"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("取消费用争议处理", "业务接口契约", "暂不可验收", "设计已就绪，待补运行证据")
                .doesNotContain("Next action", "NO_GO", "ACCEPTED", "METADATA_READY_RUNTIME_EVIDENCE_PENDING");
        assertThat(driver.findElements(By.cssSelector(".capability-count-tile strong")))
                .extracting(WebElement::getText)
                .containsExactly("4", "1", "1", "9");
        assertThat(driver.findElements(By.cssSelector("details[open]"))).isEmpty();

        List<WebElement> taskButtons = driver.findElements(
                By.cssSelector(".capability-sidebar .capability-task-button"));
        taskButtons.get(2).click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.cssSelector("[data-testid='capability-contract'] h3"), "取消责任判定"));
        assertThat(taskButtons.get(2).getAttribute("class")).contains("active");

        driver.findElement(By.cssSelector("[data-testid='capability-task-scenarios']")).click();
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".capability-scenario-list-item"), 9));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-scenario-details']")));
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-scenarios']")).getText())
                .contains("业务验证分母", "9 条 case", "质量摘要", "Owner 覆盖", "100%", "已阻断");
        driver.findElement(By.xpath(
                "//button[contains(@class,'capability-scenario-list-item')][contains(.,'补偿历史超时')]"))
                .click();
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-scenario-details']")).getText())
                .contains("补偿历史超时", "业务目标", "预期 / Oracle", "隔离运行依赖", "超时");
        assertNoPageOverflow();
        capture("capability-studio-gp01-gp03-zh-1440.png");
    }

    @Test
    void gp01ResponsiveLayoutHasNoPageOverflowAtCompactAndMobileViewports() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1024, 768);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=zh-CN"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        assertNoPageOverflow();
        capture("capability-studio-gp01-zh-1024.png");

        setViewport(390, 844);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("capability-task-select")));
        new Select(driver.findElement(By.id("capability-task-select")))
                .selectByValue("scenarios");
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".capability-scenario-list-item"), 9));
        assertThat(driver.findElement(By.cssSelector(".capability-scenario-dataset-header")).getText())
                .contains("业务验证分母", "9 条 case");
        assertThat(driver.findElement(By.cssSelector(".capability-scenario-quality")).getText())
                .contains("质量摘要", "Owner 覆盖", "100%");
        assertThat(driver.findElement(By.cssSelector(".capability-sidebar")).isDisplayed())
                .isFalse();
        assertNoPageOverflow();
        capture("capability-studio-gp03-zh-390.png");
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'start'});",
                driver.findElement(By.cssSelector(".capability-scenario-quality")));
        capture("capability-studio-gp03-quality-zh-390.png");
    }

    @Test
    void gp04SavesOnlyTheTutorialBranchAndPassesIsolatedPreflight() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1440, 900);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=zh-CN"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        driver.findElement(By.cssSelector("[data-testid='capability-task-tutorial']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-tutorial-branch']")));

        WebElement duration = driver.findElement(By.cssSelector(
                "input[aria-label='超时持续毫秒数']"));
        duration.clear();
        duration.sendKeys("1200");
        driver.findElement(By.cssSelector(".capability-editor-actions button")).click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-preflight-success']")));
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("隔离预检通过", "标准基线未改变", "未解析依赖", "真实接口调用", "已禁止")
                .doesNotContain("NO_GO", "NOT_RUN", "METADATA_READY_RUNTIME_EVIDENCE_PENDING");
        assertNoPageOverflow();
        capture("capability-studio-gp04-zh-1440.png");
    }

    @Test
    void gp05AndGp06RenderTheSameTraceAcrossPermissionsAndResponsiveViewports()
            throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1440, 900);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=zh-CN"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        driver.findElement(By.cssSelector("[data-testid='capability-task-feature']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-feature-rehearsal']")));
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".feature-dag-node"), 6));

        Select cases = new Select(driver.findElement(By.id("feature-rehearsal-case")));
        assertThat(cases.getOptions()).hasSize(9);
        assertThat(cases.getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("case-compensation-history-timeout");
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("运行状态", "通过", "真实调用", "隔离 Fixture 控制", "历史补偿查询", "超时")
                .doesNotContain("DEMO-ORDER-20260818-001");
        assertThat(driver.findElements(By.cssSelector(".feature-dag-edge-label"))).hasSize(5);
        assertThat(driver.findElement(By.cssSelector(
                "[data-node-id='compensationHistoryLookup']")).getText())
                .contains("历史补偿查询", "替身运行");
        assertThat(driver.findElement(By.cssSelector(
                ".capability-segmented-control button:first-child")).getAttribute("aria-pressed"))
                .isEqualTo("true");
        assertDesktopDagFitsAndEdgesAlign();
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        capture("capability-studio-gp05-gp06-structure-zh-1440.png");

        driver.findElement(By.xpath(
                "//div[contains(@class,'capability-segmented-control')]//button[contains(.,'受控数据')]"))
                .click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.tagName("body"), "DEMO-ORDER-20260818-001"));
        assertThat(driver.findElement(By.cssSelector(
                ".capability-segmented-control button:last-child")).getAttribute("aria-pressed"))
                .isEqualTo("true");
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("DEMO-ORDER-20260818-001", "真实调用", "0");
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        capture("capability-studio-gp06-payload-zh-1440.png");
        setViewport(1440, 1100);
        ((JavascriptExecutor) driver).executeScript("""
                document.querySelector('.capability-feature-dag-section')
                  .scrollIntoView({block: 'start'});
                """);
        capture("capability-studio-gp05-gp06-dag-payload-zh-1440.png");
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0)");

        setViewport(1024, 768);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-feature-rehearsal']")));
        assertDesktopDagFitsAndEdgesAlign();
        assertNoPageOverflow();
        capture("capability-studio-gp05-gp06-payload-zh-1024.png");

        setViewport(390, 844);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("capability-task-select")));
        assertThat(driver.findElement(By.cssSelector(".capability-sidebar")).isDisplayed()).isFalse();
        assertThat(new Select(driver.findElement(By.id("feature-rehearsal-case"))).getOptions())
                .hasSize(9);
        Number internalOverflow = (Number) ((JavascriptExecutor) driver).executeScript("""
                const dag = document.querySelector('.capability-feature-dag');
                return Math.max(0, dag.scrollWidth - dag.clientWidth);
                """);
        assertThat(internalOverflow.doubleValue()).isGreaterThan(0);
        assertNoPageOverflow();
        capture("capability-studio-gp05-gp06-payload-zh-390.png");
    }

    @Test
    void gp05AndGp06EnglishPermissionSwitchIsKeyboardReachable() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1024, 768);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=en"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        driver.findElement(By.cssSelector("[data-testid='capability-task-feature']")).click();
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".feature-dag-node"), 6));
        WebElement payload = tabUntilActive(By.cssSelector(
                ".capability-segmented-control button:last-child"));
        payload.sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.tagName("body"), "DEMO-ORDER-20260818-001"));

        assertThat(driver.findElement(By.tagName("html")).getAttribute("lang")).isEqualTo("en");
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("Feature processing DAG", "Data flow inspection",
                        "Isolated fixture control", "Real calls")
                .doesNotContain("NO_GO", "METADATA_READY_RUNTIME_EVIDENCE_PENDING");
        assertThat(payload.getAttribute("aria-pressed")).isEqualTo("true");
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        capture("capability-studio-gp05-gp06-payload-en-1024.png");
    }

    @Test
    void gp07AndGp08RunTheGovernedToolBaselineAndKeepReleaseClosedAcrossViewports()
            throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1440, 1100);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        driver.get(url("/capabilities/?lang=zh-CN"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        driver.findElement(By.cssSelector("[data-testid='capability-task-tool']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-tool']")));
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("业务正确性验证", "固定场景", "重复轮次", "预期检查", "真实接口")
                .contains("运行 9 × 3 受治理验证", "结果只标记为开发验证，不自动通过发布门禁")
                .doesNotContain("NO_GO", "DEVELOPMENT_TEST_OWNED");

        driver.findElement(By.cssSelector("[data-testid='run-governed-baseline']")).click();
        wait.until(webDriver -> !webDriver.findElements(By.cssSelector(
                        "[data-testid='governed-baseline-result']")).isEmpty()
                || !webDriver.findElements(By.cssSelector(
                        ".capability-governed-error")).isEmpty());
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid='governed-baseline-result']")))
                .withFailMessage("Governed browser result was not established:%n%s",
                        driver.findElement(By.tagName("body")).getText())
                .hasSize(1);

        WebElement result = driver.findElement(By.cssSelector(
                "[data-testid='governed-baseline-result']"));
        assertThat(result.getText())
                .contains("27 项业务检查全部通过", "仍不可验收", "9 / 9", "27 / 27")
                .contains("结果稳定", "当前结果尚未绑定不可变的发布候选构建", "尚缺部署级断网与出口观测", "负责人尚未签署");
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table tbody tr"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table .capability-oracle-cell"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table .capability-evidence-matrix-button"))).hasSize(27);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-rounds > div")))
                .hasSize(3)
                .extracting(WebElement::getText)
                .allSatisfy(text -> assertThat(text).contains("9 / 9", "通过"));
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-result details[open]"))).isEmpty();
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'start'});", result);
        capture("capability-studio-gp07-gp08-governed-tool-zh-1440.png");

        setViewport(390, 844);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("capability-task-select")));
        assertThat(new Select(driver.findElement(By.id("capability-task-select")))
                .getFirstSelectedOption().getAttribute("value")).isEqualTo("tool");
        assertThat(driver.findElement(By.cssSelector(".capability-sidebar")).isDisplayed()).isFalse();
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table tbody tr"))).hasSize(9);
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'start'});", result);
        capture("capability-studio-gp07-gp08-governed-tool-zh-390.png");
    }

    @Test
    void gp07AndGp08EnglishUsAt1024RunTheGovernedToolBaseline() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1024, 768);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        driver.get(url("/capabilities/?lang=en-US&task=tool"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-tool']")));
        assertThat(driver.findElement(By.tagName("html")).getAttribute("lang")).isEqualTo("en");
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("Business correctness verification", "Fixed scenarios", "Rounds",
                        "Expected checks", "Real APIs", "Run governed 9 x 3 verification")
                .doesNotContain("NO_GO", "DEVELOPMENT_TEST_OWNED", "RG.CAPABILITY_STUDIO.");
        driver.findElement(By.cssSelector("[data-testid='run-governed-baseline']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='governed-baseline-result']")));

        WebElement result = driver.findElement(By.cssSelector(
                "[data-testid='governed-baseline-result']"));
        assertThat(result.getText())
                .contains("All 27 business checks passed", "Still not accepted", "9 / 9", "27 / 27",
                        "Stable result", "What still blocks release acceptance")
                .doesNotContain("NO_GO", "DEVELOPMENT_TEST_OWNED");
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table tbody tr"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table .capability-oracle-cell"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-case-table .capability-evidence-matrix-button"))).hasSize(27);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-governed-rounds > div"))).hasSize(3);
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'start'});", result);
        capture("capability-studio-gp07-gp08-governed-tool-en-1024.png");
    }

    @Test
    void gp09ExposesFalsifiableQualityAdmissionAndCaseImpactAcrossViewports()
            throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1440, 1100);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=zh-CN&task=quality"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-quality-impact']")));
        WebElement quality = driver.findElement(By.cssSelector(
                "[data-testid='capability-quality-impact']"));
        assertThat(quality.getText())
                .contains("准入阻断", "缺少新鲜度证据", "没有使用中的 case")
                .contains("9 条 case", "条草稿", "条使用中", "条孤儿 case")
                .contains("当前视图不导出请求/响应内容", "不代表源数据已经完成语义脱敏");
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-impact-metrics strong")))
                .extracting(WebElement::getText)
                .containsExactly("100%", "100%", "100%", "100%", "100%");
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-counts strong")))
                .extracting(WebElement::getText)
                .containsExactly("9", "0", "0");
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-case-item"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-graph-node"))).hasSize(37);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-edge"))).hasSize(81);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-graph-node.selected"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-edge.selected"))).hasSize(8);

        WebElement timeoutCase = driver.findElements(By.cssSelector(
                ".capability-quality-case-item")).get(2);
        timeoutCase.click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.cssSelector("[data-testid='capability-quality-case-details'] h4"),
                "补偿历史超时"));
        assertThat(timeoutCase.getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-graph-node.selected"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-edge.selected"))).hasSize(8);
        assertNoBrowserErrorState();
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        capture("capability-studio-gp09-quality-admission-zh-1440.png");
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'start'});",
                driver.findElement(By.cssSelector(
                        "[data-testid='capability-quality-case-details']")));
        capture("capability-studio-gp09-case-impact-zh-1440.png");

        setViewport(390, 844);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("capability-task-select")));
        assertThat(new Select(driver.findElement(By.id("capability-task-select")))
                .getFirstSelectedOption().getAttribute("value")).isEqualTo("quality");
        assertThat(driver.findElement(By.cssSelector(".capability-sidebar")).isDisplayed()).isFalse();
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-case-item"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-graph-node"))).hasSize(37);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-edge"))).hasSize(81);
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        capture("capability-studio-gp09-quality-admission-zh-390.png");
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'start'});",
                driver.findElement(By.cssSelector(
                        "[data-testid='capability-quality-case-details']")));
        capture("capability-studio-gp09-case-impact-zh-390.png");
    }

    @Test
    void gp09EnglishUsAt1024SupportsKeyboardQualityCaseImpactPath() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1024, 768);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=en-US"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        WebElement qualityTask = tabUntilActive(By.cssSelector(
                "[data-testid='capability-task-quality']"));
        assertThat(driver.switchTo().activeElement()).isEqualTo(qualityTask);
        qualityTask.sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-quality-impact']")));
        assertThat(driver.findElement(By.tagName("html")).getAttribute("lang")).isEqualTo("en");
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("Quality & impact", "Five quality dimensions", "Explicit blockers",
                        "9 cases", "Draft", "Active", "Orphan cases")
                .doesNotContain("NO_GO", "RG.CAPABILITY_STUDIO.");

        String initialImpact = selectedQualityNodeIds();
        WebElement firstCase = tabUntilActive(By.cssSelector(
                ".capability-quality-case-item"));
        assertThat(driver.switchTo().activeElement()).isEqualTo(firstCase);
        firstCase.sendKeys(Keys.TAB);
        WebElement secondCase = driver.switchTo().activeElement();
        assertThat(secondCase.getAttribute("class")).contains("capability-quality-case-item");
        String secondCaseName = secondCase.findElement(By.cssSelector("strong")).getText();
        secondCase.sendKeys(Keys.ENTER);
        wait.until(webDriver -> "true".equals(webDriver.findElements(
                By.cssSelector(".capability-quality-case-item")).get(1).getAttribute("aria-pressed")));
        wait.until(webDriver -> !selectedQualityNodeIds().equals(initialImpact));

        assertThat(secondCase.getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='capability-quality-case-details'] h4")).getText())
                .isEqualTo(secondCaseName);
        assertThat(selectedQualityNodeIds()).isNotEqualTo(initialImpact);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-graph-node.selected"))).hasSize(9);
        assertThat(driver.findElements(By.cssSelector(
                ".capability-quality-edge.selected"))).hasSize(8);
        assertNoBrowserErrorState();
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        capture("capability-studio-gp09-quality-keyboard-en-1024.png");
    }

    @Test
    void gp09RealApiFailureAt1024ShowsRecoverableQualityState() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1024, 768);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=en-US"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        DevTools devTools = enableQualityApi503();
        try {
            driver.findElement(By.cssSelector("[data-testid='capability-task-quality']")).click();
            wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("[data-testid='capability-quality-impact-error']")));
            WebElement error = driver.findElement(By.cssSelector(
                    "[data-testid='capability-quality-impact-error']"));
            assertThat(error.getText())
                    .contains("Quality & impact is unavailable", "What happened", "Impact",
                            "How to continue", "Retry quality & impact")
                    .doesNotContain("Request failed: 404", "RG.CAPABILITY_STUDIO.");
            assertThat(driver.findElements(By.cssSelector(
                    "[data-testid='capability-quality-impact']"))).isEmpty();
            assertNoPageOverflow();
            assertNoInternalStatusLeakage();
            assertNoSeriousOrCriticalAxeViolations();
            capture("capability-studio-gp09-quality-error-en-1024.png");

            devTools.send(Fetch.disable());
            error.findElement(By.cssSelector("button")).click();
            wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("[data-testid='capability-quality-impact']")));
            assertNoBrowserErrorState();
            assertThat(driver.findElements(By.cssSelector(
                    ".capability-quality-case-item"))).hasSize(9);
            assertThat(driver.findElements(By.cssSelector(
                    ".capability-quality-graph-node"))).hasSize(37);
            assertNoPageOverflow();
            assertNoSeriousOrCriticalAxeViolations();
            capture("capability-studio-gp09-quality-recovered-en-1024.png");
        } finally {
            devTools.send(Fetch.disable());
            devTools.close();
        }
    }

    @Test
    void gp10ReplaysExactTimeoutEvidenceAcrossToolGraphRefreshAndReturn() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1440, 1100);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        driver.get(url("/capabilities/?lang=zh-CN"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        driver.findElement(By.cssSelector("[data-testid='capability-task-tool']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-tool']")));
        driver.findElement(By.cssSelector("[data-testid='run-governed-baseline']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='governed-baseline-result']")));
        assertNoBrowserErrorState();

        // Select a named business case and an explicit round. The child runId is discovered
        // from the rendered matrix, so this test does not depend on allocation order.
        By timeoutRound = By.cssSelector(
                "[data-testid='governed-evidence-case-compensation-history-timeout-1']");
        wait.until(ExpectedConditions.elementToBeClickable(timeoutRound)).click();
        wait.until(webDriver -> !webDriver.findElements(By.cssSelector(
                        "[data-testid='governed-run-evidence-panel'], "
                                + "[data-testid='governed-run-evidence-error']")).isEmpty());
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid='governed-run-evidence-panel']")))
                .withFailMessage("GP-10 exact evidence was not established:%nURL: %s%n%s",
                        driver.getCurrentUrl(), driver.findElement(By.tagName("body")).getText())
                .hasSize(1);

        WebElement evidence = driver.findElement(By.cssSelector(
                "[data-testid='governed-run-evidence-panel']"));
        String runId = queryParam("runId");
        String scenarioId = queryParam("scenarioId");
        assertThat(queryParam("task")).isEqualTo("tool");
        assertThat(runId).isNotBlank();
        assertThat(scenarioId).isEqualTo("case-compensation-history-timeout");
        assertThat(evidence.getText())
                .contains("精确运行证据", "按原运行读取，没有重新执行", runId,
                        "补偿历史超时")
                .doesNotContain("404", "Request failed");
        assertNoBrowserErrorState();
        assertNoInternalStatusLeakage();
        capture("capability-studio-gp10-exact-evidence-zh-1440.png");

        driver.findElement(By.cssSelector(
                "[data-testid='governed-run-evidence-panel'] .capability-exact-actions button"))
                .click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-feature-rehearsal']")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".feature-dag-node.feature-node-focus")));

        String nodeId = queryParam("nodeId");
        assertThat(queryParam("task")).isEqualTo("feature");
        assertThat(queryParam("runId")).isEqualTo(runId);
        assertThat(queryParam("scenarioId")).isEqualTo(scenarioId);
        assertThat(nodeId).isNotBlank();
        WebElement focusNode = driver.findElement(By.cssSelector(
                ".feature-dag-node.feature-node-focus"));
        assertThat(focusNode.getAttribute("data-node-id")).isEqualTo(nodeId);
        assertThat(focusNode.isDisplayed()).isTrue();
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-feature-rehearsal']"))
                .getText())
                .contains("精确证据 · 只读", "当前图来自原 governed run 的 exact evidence，没有重新执行",
                        "本页读取原 run 的只读 exact evidence，未重跑")
                .doesNotContain("404", "Request failed");
        assertNoBrowserErrorState();
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        capture("capability-studio-gp10-exact-dag-zh-1440.png");

        driver.navigate().refresh();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-feature-rehearsal']")));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.cssSelector("[data-testid='capability-feature-rehearsal']"), "未重跑"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".feature-dag-node.feature-node-focus")));
        assertThat(queryParam("task")).isEqualTo("feature");
        assertThat(queryParam("runId")).isEqualTo(runId);
        assertThat(queryParam("scenarioId")).isEqualTo(scenarioId);
        assertThat(queryParam("nodeId")).isEqualTo(nodeId);
        assertThat(driver.findElement(By.cssSelector(".feature-dag-node.feature-node-focus"))
                .getAttribute("data-node-id")).isEqualTo(nodeId);
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-feature-rehearsal']"))
                .getText())
                .contains("未重跑", "只读")
                .doesNotContain("404", "Request failed");
        assertNoBrowserErrorState();

        driver.findElement(By.cssSelector(
                "[data-testid='capability-feature-rehearsal'] .capability-return-tool"))
                .click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='governed-run-evidence-panel']")));
        assertThat(queryParam("task")).isEqualTo("tool");
        assertThat(queryParam("runId")).isEqualTo(runId);
        assertThat(queryParam("scenarioId")).isEqualTo(scenarioId);
        assertThat(queryParam("nodeId")).isEqualTo(nodeId);
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='governed-run-evidence-panel']")).getText())
                .contains("精确运行证据", "按原运行读取，没有重新执行", runId)
                .doesNotContain("404", "Request failed");
        assertNoBrowserErrorState();
        assertNoInternalStatusLeakage();
        capture("capability-studio-gp10-exact-evidence-return-zh-1440.png");
    }

    @Test
    void gp10EnglishUsAt1024ReplaysExactEvidenceAndGraphContext() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1024, 768);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        driver.get(url("/capabilities/?lang=en-US&task=tool"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-tool']")));
        driver.findElement(By.cssSelector("[data-testid='run-governed-baseline']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='governed-baseline-result']")));
        WebElement timeoutRound = driver.findElement(By.cssSelector(
                "[data-testid='governed-evidence-case-compensation-history-timeout-1']"));
        timeoutRound.click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='governed-run-evidence-panel']")));

        String runId = queryParam("runId");
        String scenarioId = queryParam("scenarioId");
        assertThat(queryParam("task")).isEqualTo("tool");
        assertThat(runId).isNotBlank();
        assertThat(scenarioId).isEqualTo("case-compensation-history-timeout");
        assertThat(driver.findElement(By.cssSelector(
                "[data-testid='governed-run-evidence-panel']")).getText())
                .contains("Exact run evidence", "Read from the original run; nothing was re-executed.",
                        runId, "case-compensation-history-timeout")
                .doesNotContain("404", "Request failed");
        assertNoBrowserErrorState();
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        capture("capability-studio-gp10-exact-evidence-en-1024.png");

        driver.findElement(By.cssSelector(
                "[data-testid='governed-run-evidence-panel'] .capability-exact-actions button"))
                .click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-feature-rehearsal']")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".feature-dag-node.feature-node-focus")));
        String nodeId = queryParam("nodeId");
        assertThat(queryParam("task")).isEqualTo("feature");
        assertThat(queryParam("runId")).isEqualTo(runId);
        assertThat(queryParam("scenarioId")).isEqualTo(scenarioId);
        assertThat(nodeId).isNotBlank();
        assertThat(driver.findElement(By.cssSelector(".feature-dag-node.feature-node-focus"))
                .getAttribute("data-node-id")).isEqualTo(nodeId);
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-feature-rehearsal']"))
                .getText())
                .contains("EXACT EVIDENCE · READ-ONLY", "original governed run exact evidence",
                        "nothing was re-executed", "Feature processing DAG")
                .doesNotContain("404", "Request failed");
        assertDesktopDagFitsAndEdgesAlign();
        assertNoBrowserErrorState();
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        capture("capability-studio-gp10-exact-dag-en-1024.png");
        assertNoSeriousOrCriticalAxeViolations();
    }

    @Test
    void englishNfr02CoversOverviewDatasetAndTutorialAcrossThreeViewports() throws IOException {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1440, 900);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=en"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));
        assertThat(driver.findElement(By.tagName("html")).getAttribute("lang")).isEqualTo("en");
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("Capability Studio", "Overview", "Acceptance and readiness")
                .doesNotContain("NO_GO", "METADATA_READY_RUNTIME_EVIDENCE_PENDING", "RG.CAPABILITY_STUDIO.");
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();

        driver.findElement(By.cssSelector("[data-testid='capability-task-scenarios']")).click();
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".capability-scenario-list-item"), 9));
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-scenarios']")).getText())
                .contains("Business validation denominator", "9 cases", "Quality summary", "Owner coverage", "100%");
        WebElement secondCase = driver.findElements(By.cssSelector(".capability-scenario-list-item")).get(1);
        String secondCaseName = secondCase.getText().split("\\n")[0];
        secondCase.click();
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-scenario-details']")).getText())
                .contains(secondCaseName, "Business goal", "Expected / Oracle", "Isolated runtime controls");
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        assertNoSeriousOrCriticalAxeViolations();
        capture("capability-studio-gp01-gp03-en-1440.png");

        driver.findElement(By.cssSelector("[data-testid='capability-task-tutorial']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-tutorial-branch']")));
        assertThat(driver.findElement(By.tagName("body")).getText())
                .contains("Rehearse a compensation-history timeout", "isolated branch")
                .doesNotContain("NO_GO", "METADATA_READY_RUNTIME_EVIDENCE_PENDING", "RG.CAPABILITY_STUDIO.");
        assertNoInternalStatusLeakage();
        assertNoSeriousOrCriticalAxeViolations();

        setViewport(1024, 768);
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-tutorial-branch']")));
        assertNoPageOverflow();
        assertNoInternalStatusLeakage();
        capture("capability-studio-gp01-gp03-en-1024.png");

        setViewport(390, 844);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("capability-task-select")));
        new Select(driver.findElement(By.id("capability-task-select"))).selectByValue("scenarios");
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".capability-scenario-list-item"), 9));
        assertThat(driver.findElement(By.cssSelector(".capability-scenario-dataset-header")).getText())
                .contains("Business validation denominator", "9 cases");
        assertThat(driver.findElement(By.cssSelector(".capability-scenario-quality")).getText())
                .contains("Quality summary", "Owner coverage", "100%");
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-scenario-details']")).getText())
                .contains("Business goal", "Expected / Oracle");
        assertNoInternalStatusLeakage();
        assertNoPageOverflow();
        capture("capability-studio-gp01-gp03-en-390.png");
    }

    @Test
    void englishNfr02SupportsKeyboardNavigationIntoDatasetAndCaseDetails() {
        assumeFrontendBundlePresent();
        driver = newChromeDriverOrSkip(1440, 900);
        WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        driver.get(url("/capabilities/?lang=en"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-overview']")));

        WebElement scenarioTask = tabUntilActive(By.cssSelector("[data-testid='capability-task-scenarios']"));
        assertThat(scenarioTask.getAttribute("data-testid")).isEqualTo("capability-task-scenarios");
        scenarioTask.sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".capability-scenario-list-item"), 9));

        String initialDetails = driver.findElement(By.cssSelector("[data-testid='capability-scenario-details']")).getText();
        WebElement targetCase = tabUntilActive(By.cssSelector(
                ".capability-scenario-list-item:nth-of-type(2)"));
        String targetCaseText = targetCase.getText();
        assertThat(driver.switchTo().activeElement().getText()).isEqualTo(targetCaseText);
        targetCase.sendKeys(Keys.SPACE);
        assertThat(driver.switchTo().activeElement().getAttribute("aria-pressed")).isEqualTo("true");
        wait.until(webDriver -> !webDriver.findElement(
                By.cssSelector("[data-testid='capability-scenario-details']")).getText().equals(initialDetails));
        assertThat(driver.findElement(By.cssSelector("[data-testid='capability-scenario-details']")).getText())
                .contains(targetCaseText.split("\\n")[0]);

        setViewport(390, 844);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("capability-task-select")));
        WebElement taskSelect = tabUntilActive(By.id("capability-task-select"));
        assertThat(driver.switchTo().activeElement().getAttribute("id")).isEqualTo("capability-task-select");
        taskSelect.sendKeys(Keys.HOME, Keys.ARROW_DOWN, Keys.ARROW_DOWN, Keys.ENTER);
        wait.until(ExpectedConditions.numberOfElementsToBe(
                By.cssSelector(".capability-scenario-list-item"), 9));
        assertThat(driver.switchTo().activeElement().getAttribute("id")).isEqualTo("capability-task-select");
        assertNoPageOverflow();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void assumeFrontendBundlePresent() {
        Assumptions.assumeTrue(
                new ClassPathResource("static/capabilities/index.html").exists(),
                "Capability Studio browser acceptance runs with Maven -Pfrontend");
    }

    private WebDriver newChromeDriverOrSkip(int width, int height) {
        Path executable = chromeDriverExecutableOrSkip();
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--no-sandbox",
                "--window-size=" + width + "," + height);
        if (Files.isExecutable(MAC_CHROME_BINARY)) {
            options.setBinary(MAC_CHROME_BINARY.toString());
        }
        driverService = new ChromeDriverService.Builder()
                .usingDriverExecutable(executable.toFile())
                .usingAnyFreePort()
                .build();
        try {
            ChromeDriver chromeDriver = new ChromeDriver(driverService, options);
            setViewport(chromeDriver, width, height);
            return chromeDriver;
        } catch (RuntimeException failure) {
            driverService.stop();
            driverService = null;
            Assumptions.abort("Chrome/WebDriver session is unavailable");
            return null;
        }
    }

    private void setViewport(int width, int height) {
        setViewport(driver, width, height);
    }

    private void setViewport(WebDriver browser, int width, int height) {
        assertThat(browser).as("browser for exact viewport emulation").isInstanceOf(ChromeDriver.class);
        ChromeDriver chrome = (ChromeDriver) browser;
        chrome.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
                "width", width,
                "height", height,
                "deviceScaleFactor", 1,
                "mobile", false));
        JavascriptExecutor javascript = (JavascriptExecutor) browser;
        Number actualWidth = (Number) javascript.executeScript("return window.innerWidth;");
        Number actualHeight = (Number) javascript.executeScript("return window.innerHeight;");
        assertThat(actualWidth.intValue()).as("exact viewport width").isEqualTo(width);
        assertThat(actualHeight.intValue()).as("exact viewport height").isEqualTo(height);
    }

    private Path chromeDriverExecutableOrSkip() {
        String configured = System.getProperty("webdriver.chrome.driver", "").trim();
        if (!configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return Path.of(configured);
        }
        if (Files.isDirectory(CHROMEDRIVER_CACHE)) {
            try (var paths = Files.find(CHROMEDRIVER_CACHE, 4, (path, attributes) ->
                    attributes.isRegularFile()
                            && "chromedriver".equals(path.getFileName().toString())
                            && Files.isExecutable(path))) {
                Path cached = paths.sorted(Comparator.reverseOrder()).findFirst().orElse(null);
                if (cached != null) {
                    return cached;
                }
            } catch (IOException ignored) {
                // The assumption below reports the bounded reason without local filesystem detail.
            }
        }
        Assumptions.abort("ChromeDriver is unavailable for real-browser acceptance");
        return null;
    }

    private void assertNoPageOverflow() {
        Number overflow = (Number) ((JavascriptExecutor) driver).executeScript("""
                const root = document.scrollingElement || document.documentElement;
                return Math.max(0, root.scrollWidth - window.innerWidth);
                """);
        assertThat(overflow.doubleValue()).isLessThanOrEqualTo(2.0);
    }

    private void assertNoBrowserErrorState() {
        assertThat(driver.findElements(By.cssSelector(
                "[data-testid='capability-load-error'], [data-testid='governed-run-evidence-error'], "
                        + ".capability-governed-error, .capability-feature-error, [role='alert']")))
                .as("Capability Studio browser error state")
                .isEmpty();
        assertThat(driver.findElement(By.tagName("body")).getText())
                .doesNotContain("404", "Request failed", "RG.CAPABILITY_STUDIO.");
    }

    private String queryParam(String name) {
        return (String) ((JavascriptExecutor) driver).executeScript(
                "return new URL(window.location.href).searchParams.get(arguments[0]);", name);
    }

    private String selectedQualityNodeIds() {
        return (String) ((JavascriptExecutor) driver).executeScript("""
                return [...document.querySelectorAll('.capability-quality-graph-node.selected')]
                  .map(node => node.dataset.nodeId)
                  .sort()
                  .join('|');
                """);
    }

    private DevTools enableQualityApi503() {
        assertThat(driver).as("Chrome DevTools browser").isInstanceOf(ChromeDriver.class);
        DevTools devTools = ((ChromeDriver) driver).getDevTools();
        devTools.createSession();
        devTools.addListener(Fetch.requestPaused(), event -> {
            String body = Base64.getEncoder().encodeToString("""
                    {"code":"RG.CAPABILITY_STUDIO.QUALITY_IMPACT_TEMPORARILY_UNAVAILABLE",
                     "whatHappened":"The quality and impact projection service is temporarily unavailable.",
                     "impact":"The quality projection was not loaded or changed.",
                     "recoveryAction":"Retry quality and impact."}
                    """.getBytes(StandardCharsets.UTF_8));
            devTools.send(Fetch.fulfillRequest(
                    event.getRequestId(), 503,
                    Optional.of(List.of(new HeaderEntry("Content-Type", "application/json"))),
                    Optional.empty(), Optional.of(body), Optional.of("Service Unavailable")));
        });
        devTools.send(Fetch.enable(Optional.of(List.of(new RequestPattern(
                Optional.of("http://localhost:" + port
                        + "/api/capability-studio/scenario-dataset/quality-impact*"),
                Optional.empty(), Optional.of(RequestStage.REQUEST)))), Optional.empty()));
        return devTools;
    }

    private void assertNoInternalStatusLeakage() {
        String text = driver.findElement(By.tagName("body")).getText();
        assertThat(text)
                .doesNotContain("NO_GO", "METADATA_READY_RUNTIME_EVIDENCE_PENDING", "CONTRACT_READY_RUNTIME_PENDING",
                        "DAG_CONTRACT_READY_RUNTIME_PENDING", "RG.CAPABILITY_STUDIO.");
        assertThat(text).doesNotMatch(".*\\b[A-Z][A-Z0-9_]{15,}\\b.*");
    }

    @SuppressWarnings("unchecked")
    private void assertNoSeriousOrCriticalAxeViolations() throws IOException {
        Path axeSource = Path.of(System.getProperty("user.dir"),
                "src/main/frontend/node_modules/axe-core/axe.min.js");
        if (!Files.isRegularFile(axeSource)) {
            axeSource = Path.of(System.getProperty("user.dir"),
                    "resource-gateway-examples/src/main/frontend/node_modules/axe-core/axe.min.js");
        }
        assertThat(axeSource).as("real-browser axe-core source").isRegularFile();
        JavascriptExecutor javascript = (JavascriptExecutor) driver;
        javascript.executeScript(Files.readString(axeSource));
        Map<String, Object> report = (Map<String, Object>) javascript.executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                window.axe.run(document).then(result => done({
                  error: '',
                  violations: result.violations
                    .filter(item => item.impact === 'serious' || item.impact === 'critical')
                    .map(item => ({
                      id: item.id,
                      impact: item.impact,
                      targets: item.nodes.map(node => node.target)
                    }))
                })).catch(error => done({error: String(error), violations: []}));
                """);
        assertThat(report.get("error")).as("axe execution error").isEqualTo("");
        assertThat((List<Map<String, Object>>) report.get("violations"))
                .as("serious or critical real-browser axe violations")
                .isEmpty();
    }

    private WebElement tabUntilActive(By locator) {
        for (int attempt = 0; attempt < 80; attempt++) {
            WebElement active = driver.switchTo().activeElement();
            if (!driver.findElements(locator).stream().noneMatch(candidate -> {
                try {
                    return candidate.equals(active);
                } catch (RuntimeException ignored) {
                    return false;
                }
            })) {
                return active;
            }
            active.sendKeys(Keys.TAB);
        }
        throw new AssertionError("TAB did not reach " + locator);
    }

    private void assertDesktopDagFitsAndEdgesAlign() {
        Number overflow = (Number) ((JavascriptExecutor) driver).executeScript("""
                const dag = document.querySelector('.capability-feature-dag');
                return Math.max(0, dag.scrollWidth - dag.clientWidth);
                """);
        assertThat(overflow.doubleValue())
                .as("desktop DAG horizontal overflow")
                .isLessThanOrEqualTo(1.0d);

        Number maximumCenterDelta = (Number) ((JavascriptExecutor) driver).executeScript("""
                const nodes = [...document.querySelectorAll(
                  '.feature-dag-inputs .feature-dag-node')];
                const edges = [...document.querySelectorAll(
                  '.feature-dag-inputs + .feature-dag-edge-column .feature-dag-edge-label')];
                return Math.max(...nodes.map((node, index) => {
                  const nodeBox = node.getBoundingClientRect();
                  const edgeBox = edges[index].getBoundingClientRect();
                  return Math.abs(
                    (nodeBox.top + nodeBox.height / 2) -
                    (edgeBox.top + edgeBox.height / 2));
                }));
                """);
        assertThat(maximumCenterDelta.doubleValue())
                .as("source node and edge center alignment")
                .isLessThanOrEqualTo(1.0d);
    }

    private void capture(String fileName) throws IOException {
        String output = System.getProperty("resourceGateway.visualQaOutputDir", "").trim();
        if (output.isBlank()) {
            return;
        }
        Path target = Path.of(output).resolve(fileName);
        Files.createDirectories(target.getParent());
        Files.write(target, ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
    }
}
