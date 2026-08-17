package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-browser acceptance skeleton for Capability Studio GP-01 through GP-04. */
@SpringBootTest(
        classes = {
                ResourceGatewayApplication.class,
                CapabilityStudioBrowserAcceptanceTest.BrowserFixtureConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "spring.datasource.url=jdbc:h2:mem:capability-studio-browser;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
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

    @TestConfiguration(proxyBeanMethods = false)
    static class BrowserFixtureConfiguration {
        @Bean
        CapabilityStudioGoldenDemoPack browserGoldenDemoPack(ObjectMapper mapper) {
            return new CapabilityStudioGoldenDemoPackLoader().load(mapper);
        }

        @Bean
        CapabilityStudioTutorialBranchRepository browserTutorialBranchRepository(
                org.springframework.jdbc.core.JdbcTemplate jdbc) {
            return new CapabilityStudioTutorialBranchRepository(jdbc);
        }

        @Bean
        CapabilityStudioTutorialBranchAuthority browserTutorialBranchAuthority(
                CapabilityStudioTutorialBranchRepository repository,
                CapabilityStudioGoldenDemoPack pack,
                ObjectMapper mapper,
                org.springframework.transaction.PlatformTransactionManager transactionManager) {
            return new CapabilityStudioTutorialBranchAuthority(
                    repository, pack, mapper,
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager));
        }

        @Bean
        CapabilityStudioDemoController browserCapabilityStudioDemoController(
                CapabilityStudioGoldenDemoPack pack,
                CapabilityStudioTutorialBranchAuthority authority) {
            return new CapabilityStudioDemoController(pack, authority);
        }
    }

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
                .contains("补偿历史超时", "业务目标", "预期 / Oracle", "依赖表现", "超时");
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

        driver.manage().window().setSize(new Dimension(390, 844));
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
                .contains(secondCaseName, "Business goal", "Expected / Oracle", "Dependency behavior");
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

        driver.manage().window().setSize(new Dimension(1024, 768));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='capability-tutorial-branch']")));
        assertNoPageOverflow();
        assertNoInternalStatusLeakage();
        capture("capability-studio-gp01-gp03-en-1024.png");

        driver.manage().window().setSize(new Dimension(390, 844));
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

        driver.manage().window().setSize(new Dimension(390, 844));
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
            return new ChromeDriver(driverService, options);
        } catch (RuntimeException failure) {
            driverService.stop();
            driverService = null;
            Assumptions.abort("Chrome/WebDriver session is unavailable");
            return null;
        }
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
