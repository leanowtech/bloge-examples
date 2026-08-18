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
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v149.fetch.Fetch;
import org.openqa.selenium.devtools.v149.fetch.model.HeaderEntry;
import org.openqa.selenium.devtools.v149.fetch.model.RequestPattern;
import org.openqa.selenium.devtools.v149.fetch.model.RequestStage;
import org.openqa.selenium.devtools.v149.network.Network;
import org.openqa.selenium.devtools.v149.network.model.ErrorReason;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real Chrome producer for the fixed 126-cell Capability Studio anomaly matrix. */
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
                "spring.datasource.url=jdbc:h2:mem:capability-studio-browser-anomaly;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:capability-studio-browser-anomaly-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
@Timeout(1800)
class CapabilityStudioBrowserAnomalyMatrixProducerIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String DEMO_AUTHORIZATION = "Bearer bloge-aneke-demo-token";
    private static final String DEMO_PURPOSE = "CAPABILITY_STUDIO_REHEARSAL";
    private static final Path MAC_CHROME_BINARY = Path.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
    private static final Path CHROMEDRIVER_CACHE = Path.of(
            System.getProperty("user.home"), ".cache", "selenium", "chromedriver");
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
    void producesTheFixedCandidateBoundBrowserAnomalyMatrix() throws Exception {
        Path repository = repositoryRoot();
        Path output = outputPath(repository);
        Path baseOutput = baseOutputPath(repository);
        JsonNode base = JSON.readTree(Files.readString(baseOutput));
        assertThat(base.path("resultStatus").asText()).isEqualTo("COMPLETE");
        assertThat(base.path("schemaVersion").asText())
                .isEqualTo("bloge.capabilityStudioBrowserMatrixResult.v1");
        assertThat(base.path("contractId").asText())
                .isEqualTo(CapabilityStudioBrowserAnomalyMatrixArtifact.CONTRACT_ID);
        assertThat(base.path("contractRevision").asText()).isNotBlank();
        assertThat(base.path("evidenceClosureFingerprint").asText())
                .matches("sha256:[a-f0-9]{64}");
        verifyBaseFingerprint(base);

        evidenceDirectory = output.getParent().resolve("browser-anomaly-evidence");
        Files.createDirectories(evidenceDirectory);
        axeSource = repository.resolve(
                "resource-gateway-examples/src/main/frontend/node_modules/axe-core/axe.min.js");
        assertThat(axeSource).as("axe-core source for real browser observations").isRegularFile();

        Path executable = chromeDriverExecutable();
        CapabilityStudioBrowserAnomalyMatrixArtifact.Candidate candidate = candidate(base);
        verifyCandidateArtifact(repository, candidate);
        CapabilityStudioBrowserAnomalyMatrixArtifact.Environment environment =
                environment(base, repository, executable);
        String sourceCommit = candidate.sourceCommit();
        assertThat(sourceCommit).matches("[a-f0-9]{7,64}");
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        CapabilityStudioBrowserAnomalyMatrixArtifact artifact = new CapabilityStudioBrowserAnomalyMatrixArtifact(
                "BAMR-" + sourceCommit.substring(0, Math.min(12, sourceCommit.length())) + "-"
                        + OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond(),
                1,
                base.path("contractRevision").asText(),
                candidate,
                baseline(base),
                environment,
                startedAt,
                new CapabilityStudioBrowserAnomalyMatrixArtifact.BaseMatrixRef(
                        "results/browser-matrix/" + base.path("resultId").asText(),
                        base.path("evidenceClosureFingerprint").asText()));

        Filter filter = Filter.read();
        if (requireComplete() && filter.active()) {
            throw new IllegalArgumentException("require-complete forbids anomaly development filters");
        }
        if (filter.active() && !developmentMode(repository)) {
            throw new IllegalArgumentException("anomaly filters are development-only");
        }
        for (String profile : List.of("ERROR", "OFFLINE", "CONFLICT")) {
            if (!filter.profiles().contains(profile)) continue;
            List<String> goldenPaths = "CONFLICT".equals(profile)
                    ? List.of("GP-04")
                    : CapabilityStudioBrowserAnomalyMatrixArtifact.GOLDEN_PATHS;
            for (String goldenPath : goldenPaths) {
                if (!filter.goldenPaths().contains(goldenPath)) continue;
                for (String locale : CapabilityStudioBrowserAnomalyMatrixArtifact.LOCALES) {
                    if (!filter.locales().contains(locale)) continue;
                    for (CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport
                            : CapabilityStudioBrowserAnomalyMatrixArtifact.VIEWPORTS) {
                        if (!filter.viewports().contains(viewport.coordinate())) continue;
                        openBrowser(executable, viewport);
                        try {
                            CapabilityStudioBrowserAnomalyMatrixArtifact.Observation observation =
                                    executeCell(profile, goldenPath, locale, viewport);
                            if (observation != null) artifact.record(observation);
                        } finally {
                            closeBrowser();
                        }
                    }
                }
            }
        }

        ObjectNode result = artifact.build(OffsetDateTime.now(ZoneOffset.UTC));
        Files.createDirectories(output.getParent());
        Files.write(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        Path failureLog = output.resolveSibling("capability-studio-browser-anomaly-matrix-failures.txt");
        if (failures.isEmpty()) Files.deleteIfExists(failureLog);
        else Files.writeString(failureLog, String.join(System.lineSeparator(), failures));
        assertThat(result.path("obligations")).hasSize(126);
        assertThat(result.at("/summary/expected").asInt()).isEqualTo(126);
        assertThat(result.at("/summary/actual").asInt()).isEqualTo(126);
        assertThat(result.at("/summary/failed").asInt())
                .withFailMessage("Browser anomaly failures; inspect %s", failureLog).isZero();
        if (requireComplete()) {
            assertThat(result.path("resultStatus").asText()).isEqualTo("COMPLETE");
            assertThat(result.at("/summary/passed").asInt()).isEqualTo(126);
            assertThat(result.at("/summary/notRun").asInt()).isZero();
            assertThat(result.at("/summary/failed").asInt()).isZero();
        }
    }

    private CapabilityStudioBrowserAnomalyMatrixArtifact.Observation executeCell(
            String profile, String goldenPath, String locale,
            CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport) throws Exception {
        String key = profile + "-" + goldenPath + "-" + locale + "-" + viewport.coordinate();
        resetEvidence(key);
        String route = targetRoute(goldenPath);
        KeyboardJourney keyboard = new KeyboardJourney();
        Injector injector = null;
        AtomicBoolean triggered = new AtomicBoolean(false);
        AtomicInteger conflictStatus = new AtomicInteger();
        boolean errorVisible = false;
        boolean businessSafe = false;
        boolean recoveryVisible = false;
        boolean localDraft = false;
        boolean serverRevision = false;
        boolean staleGreenAbsent = false;
        boolean staleEvidenceAbsent = false;
        boolean staleSuccessAbsent = false;
        boolean recoveryAttempted = false;
        boolean recovered = false;
        boolean staleErrorAbsent = false;
        BrowserAudit observedAudit = null;
        try {
            if ("CONFLICT".equals(profile)) {
                ConflictState state = executeConflictSetup(locale, viewport, keyboard);
                triggered.set(state.triggered());
                conflictStatus.set(state.status());
                localDraft = state.localDraft();
                serverRevision = state.serverRevision();
                errorVisible = state.errorVisible();
                businessSafe = state.businessSafe();
                recoveryVisible = state.recoveryVisible();
                staleGreenAbsent = driver.findElements(By.cssSelector(
                        "[data-testid='capability-preflight-success']")).isEmpty();
                staleEvidenceAbsent = driver.findElements(By.cssSelector(
                        "[data-testid='governed-run-evidence-panel']")).isEmpty();
                staleSuccessAbsent = staleGreenAbsent && driver.findElements(By.cssSelector(
                        "[data-testid='governed-baseline-result']")).isEmpty();
            } else {
                injector = installInjector(profile, route, triggered);
                if (isDemoPackRoute(route)) {
                    openDefaultWithInjectedFailure(locale);
                } else {
                    openDefault(locale);
                    runGoldenPath(goldenPath, locale, viewport, keyboard);
                }
                waitForError(goldenPath);
                assertThat(injector.headersValid())
                        .as("GP-10 exact evidence uses demo bearer and X-Purpose")
                        .isTrue();
                errorVisible = visibleError();
                String errorText = driver.findElement(By.tagName("body")).getText();
                businessSafe = safeBusinessError(errorText);
                recoveryVisible = recoveryActionVisible();
                staleGreenAbsent = driver.findElements(By.cssSelector(
                        "[data-testid='capability-preflight-success']")).isEmpty();
                staleEvidenceAbsent = driver.findElements(By.cssSelector(
                        "[data-testid='governed-run-evidence-panel']")).isEmpty();
                staleSuccessAbsent = staleGreenAbsent && (!"GP-08".equals(goldenPath)
                        || driver.findElements(By.cssSelector(
                        "[data-testid='governed-baseline-result']")).isEmpty());
                capture(key + "-error.png");
            }
            if (!triggered.get()) {
                failures.add(key + " pre-trigger failure");
                return null;
            }
            BrowserAudit errorAudit = audit();
            observedAudit = errorAudit;
            assertThat(errorAudit.actualViewport()).isEqualTo(viewport);
            assertThat(errorAudit.overflow()).isFalse();
            assertThat(errorAudit.axeSerious()).isZero();
            assertThat(errorAudit.axeCritical()).isZero();
            assertThat(errorAudit.technicalIds()).isZero();
            assertThat(errorAudit.rawJson()).isZero();
            if (injector != null) injector.disable();
            recoveryAttempted = true;
            recover(goldenPath, locale, viewport, keyboard);
            if ("CONFLICT".equals(profile)) {
                JsonNode latest = readTutorialBranch();
                serverRevision = serverRevision
                        && String.valueOf(latest.at("/behavior/durationMs").asLong())
                        .equals(driver.findElement(By.cssSelector(
                                ".capability-duration-input input")).getAttribute("value"));
            }
            staleGreenAbsent = staleGreenAbsent && driver.findElements(By.cssSelector(
                    "[data-testid='capability-preflight-success']")).isEmpty();
            BrowserAudit audit = audit();
            observedAudit = audit;
            capture(key + "-recovered.png");
            recovered = ready(goldenPath);
            staleErrorAbsent = driver.findElements(By.cssSelector(
                    ".capability-operation-error, .capability-error-state")).isEmpty();
            staleEvidenceAbsent = staleEvidenceAbsent && driver.findElements(By.cssSelector(
                    "[data-testid='governed-run-evidence-error']")).isEmpty();
            staleSuccessAbsent = staleSuccessAbsent && staleGreenAbsent;
            BrowserFacts facts = new BrowserFacts(
                    audit.actualViewport(), audit.overflow(), audit.axeSerious(), audit.axeCritical(),
                    audit.technicalIds(), audit.rawJson(), keyboard.completed(), keyboard.steps(),
                    keyboard.focusLosses(), errorVisible, businessSafe, recoveryVisible,
                    recoveryAttempted, recovered, localDraft, serverRevision, staleGreenAbsent,
                    staleErrorAbsent, staleEvidenceAbsent, staleSuccessAbsent,
                    audit.p0(), audit.p1());
            int status = "CONFLICT".equals(profile) ? conflictStatus.get()
                    : "ERROR".equals(profile) ? 503 : 0;
            CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger trigger = new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                    "ERROR".equals(profile) ? "CDP_FETCH_FULFILL"
                            : "OFFLINE".equals(profile) ? "CDP_FETCH_FAIL" : "REAL_HTTP_STALE_REVISION",
                    route,
                    "ERROR".equals(profile) ? "HTTP_5XX"
                            : "OFFLINE".equals(profile) ? "TRANSPORT_FAILURE" : "REVISION_CONFLICT",
                    "OFFLINE".equals(profile) ? null : status,
                    true);
            List<CapabilityStudioBrowserAnomalyMatrixArtifact.EvidenceRef> evidence = writeEvidence(
                    key, trigger);
            String state = profile;
            boolean pass = facts.pass(viewport, "CONFLICT".equals(profile));
            if (!pass) failures.add(key + "|OBSERVATION_GATE_FAILED");
            CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations browser = facts.toArtifact();
            return new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                    "BAM-" + state + "-" + goldenPath + "-" + locale + "-" + viewport.coordinate(),
                    state, goldenPath, locale, viewport, pass ? "PASS" : "FAIL", trigger,
                    state + "_FEEDBACK", "CONFLICT".equals(profile) ? "RETRY_OR_MERGE" : "RETRY",
                    browser, evidence);
        } catch (Throwable failure) {
            if (!triggered.get()) {
                failures.add(key + "|PRE_TRIGGER_NOT_RUN|" + failure.getClass().getSimpleName());
                return null;
            }
            if (injector != null) injector.disable();
            if (observedAudit == null) {
                try {
                    observedAudit = audit();
                } catch (Throwable ignored) {
                    observedAudit = null;
                }
            }
            boolean evidenceAvailable = hasEvidenceFile(key + "-error.png")
                    && hasEvidenceFile(key + "-recovered.png");
            if (observedAudit == null || !evidenceAvailable) {
                failures.add(key + "|TRIGGERED_FAILURE_NOT_RUN|"
                        + failure.getClass().getSimpleName());
                return null;
            }
            failures.add(key + "|TRIGGERED_FAILURE_FAIL|" + failure.getClass().getSimpleName());
            CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger trigger = new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                    "ERROR".equals(profile) ? "CDP_FETCH_FULFILL"
                            : "OFFLINE".equals(profile) ? "CDP_FETCH_FAIL" : "REAL_HTTP_STALE_REVISION",
                    route, "ERROR".equals(profile) ? "HTTP_5XX"
                            : "OFFLINE".equals(profile) ? "TRANSPORT_FAILURE" : "REVISION_CONFLICT",
                    "OFFLINE".equals(profile) ? null : "CONFLICT".equals(profile) ? 409 : 503, true);
            List<CapabilityStudioBrowserAnomalyMatrixArtifact.EvidenceRef> evidence = writeEvidence(key, trigger);
            String state = profile;
            BrowserFacts facts = new BrowserFacts(
                    observedAudit.actualViewport(), observedAudit.overflow(), observedAudit.axeSerious(),
                    observedAudit.axeCritical(), observedAudit.technicalIds(), observedAudit.rawJson(),
                    keyboard.completed(), keyboard.steps(), keyboard.focusLosses(), errorVisible,
                    businessSafe, recoveryVisible, recoveryAttempted, recovered, localDraft,
                    serverRevision, staleGreenAbsent, staleErrorAbsent, staleEvidenceAbsent,
                    staleSuccessAbsent, observedAudit.p0(), observedAudit.p1());
            return new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                    "BAM-" + state + "-" + goldenPath + "-" + locale + "-" + viewport.coordinate(),
                    state, goldenPath, locale, viewport, "FAIL", trigger,
                    state + "_FEEDBACK", "CONFLICT".equals(profile) ? "RETRY_OR_MERGE" : "RETRY",
                    facts.toArtifact(), evidence);
        } finally {
            if (injector != null) injector.close();
        }
    }

    private void runGoldenPath(String goldenPath, String locale,
                               CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport,
                               KeyboardJourney keyboard) {
        switch (goldenPath) {
            case "GP-01" -> openTask("overview", viewport, keyboard);
            case "GP-02" -> openTask("contract", viewport, keyboard);
            case "GP-03" -> openTask("scenarios", viewport, keyboard);
            case "GP-04" -> {
                openTask("tutorial", viewport, keyboard);
                waitFor(By.cssSelector("[data-testid='capability-tutorial-branch']"));
                editTutorial(keyboard, "1200");
                keyboard.activate(By.cssSelector(".capability-editor-actions button"), Keys.ENTER);
            }
            case "GP-05", "GP-06" -> openTask("feature", viewport, keyboard);
            case "GP-07" -> openTask("tool", viewport, keyboard);
            case "GP-08" -> { openTask("tool", viewport, keyboard); keyboard.activate(By.cssSelector("[data-testid='run-governed-baseline']"), Keys.ENTER); }
            case "GP-09" -> openTask("quality", viewport, keyboard);
            case "GP-10" -> { openTask("tool", viewport, keyboard); keyboard.activate(By.cssSelector("[data-testid='run-governed-baseline']"), Keys.ENTER); waitFor(By.cssSelector("[data-testid='governed-baseline-result']")); keyboard.activate(By.cssSelector("[data-testid='governed-evidence-case-compensation-history-timeout-1']"), Keys.ENTER); }
            default -> throw new IllegalArgumentException("unknown golden path " + goldenPath);
        }
    }

    private void recover(String goldenPath, String locale,
                          CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport,
                          KeyboardJourney keyboard) {
        if ("GP-04".equals(goldenPath)) {
            keyboard.activate(By.cssSelector("[data-testid='capability-tutorial-error'] button"), Keys.ENTER);
            waitFor(By.cssSelector("[data-testid='capability-tutorial-branch']"));
            return;
        }
        String error = switch (goldenPath) {
            case "GP-01", "GP-02", "GP-07" -> "[data-testid='capability-load-error'] button";
            case "GP-03" -> "[data-testid='capability-scenario-error'] button";
            case "GP-05", "GP-06" -> ".capability-feature-error button";
            case "GP-08" -> ".capability-governed-error button";
            case "GP-09" -> ".capability-quality-impact-error button";
            case "GP-10" -> "[data-testid='governed-run-evidence-error'] button";
            default -> throw new IllegalArgumentException("unknown recovery path " + goldenPath);
        };
        keyboard.activate(By.cssSelector(error), Keys.ENTER);
        if ("GP-02".equals(goldenPath) || "GP-07".equals(goldenPath)) {
            waitFor(By.cssSelector("[data-testid='capability-overview']"));
            openTask("GP-02".equals(goldenPath) ? "contract" : "tool", viewport, keyboard);
        }
        waitFor(readyLocator(goldenPath));
        if ("GP-06".equals(goldenPath)) {
            keyboard.activate(By.cssSelector(
                    ".capability-segmented-control button:last-child"), Keys.ENTER);
            new WebDriverWait(driver, WAIT_TIMEOUT).until(ExpectedConditions.textToBePresentInElementLocated(
                    By.tagName("body"), "DEMO-ORDER-20260818-001"));
        }
    }

    private ConflictState executeConflictSetup(
            String locale,
            CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport,
            KeyboardJourney keyboard) throws Exception {
        openDefault(locale);
        openTask("tutorial", viewport, keyboard);
        waitFor(By.cssSelector("[data-testid='capability-tutorial-branch']"));
        JsonNode before = readTutorialBranch();
        long revision = before.path("revision").asLong();
        long currentDuration = before.at("/behavior/durationMs").asLong();
        long localDuration = currentDuration >= 29_900 ? 29_900 : currentDuration + 100;
        long serverDuration = currentDuration >= 29_700 ? 29_700 : currentDuration + 300;
        String serverCondition = before.at("/behavior/condition").asText() + " (server advance)";
        editTutorial(keyboard, String.valueOf(localDuration));

        ObjectNode serverUpdate = JSON.createObjectNode()
                .put("condition", serverCondition)
                .put("behavior", "TIMEOUT")
                .put("durationMs", serverDuration)
                .put("expectedRevision", revision);
        HttpResponse<String> advance = HTTP.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port
                                + "/api/capability-studio/tutorial-branch/behaviors/compensation-history"))
                .header("Content-Type", "application/json")
                .header("Authorization", DEMO_AUTHORIZATION)
                .header("X-Purpose", DEMO_PURPOSE)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(serverUpdate)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(advance.statusCode()).isEqualTo(200);
        JsonNode advanced = JSON.readTree(advance.body());
        assertThat(advanced.path("revision").asLong()).isGreaterThan(revision);
        assertThat(advanced.at("/behavior/durationMs").asLong()).isEqualTo(serverDuration);

        AtomicBoolean putSeen = new AtomicBoolean();
        AtomicBoolean responseSeen = new AtomicBoolean();
        AtomicInteger observedStatus = new AtomicInteger();
        DevTools devTools = driver.getDevTools();
        devTools.createSession();
        devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));
        devTools.addListener(Network.requestWillBeSent(), event -> {
            if (sameRoute(event.getRequest().getUrl(), targetRoute("GP-04"))
                    && "PUT".equals(event.getRequest().getMethod())) {
                putSeen.set(true);
            }
        });
        devTools.addListener(Network.responseReceived(), event -> {
            if (sameRoute(event.getResponse().getUrl(), targetRoute("GP-04"))
                    && event.getResponse().getStatus().intValue() == 409 && putSeen.get()) {
                responseSeen.set(true);
                observedStatus.set(409);
            }
        });
        try {
            keyboard.activate(By.cssSelector(".capability-editor-actions button"), Keys.ENTER);
            waitFor(By.cssSelector("[data-testid='capability-tutorial-error']"));
            new WebDriverWait(driver, WAIT_TIMEOUT).until(ignored -> responseSeen.get());
            boolean localDraft = String.valueOf(localDuration).equals(driver.findElement(By.cssSelector(
                    ".capability-duration-input input")).getAttribute("value"));
            JsonNode preservedHead = readTutorialBranch();
            boolean serverPreserved = preservedHead.path("revision").asLong()
                    == advanced.path("revision").asLong()
                    && preservedHead.at("/behavior/durationMs").asLong() == serverDuration
                    && preservedHead.at("/behavior/condition").asText()
                    .equals(serverCondition);
            String errorText = driver.findElement(By.tagName("body")).getText();
            boolean recoveryVisible = recoveryActionVisible();
            capture("CONFLICT-GP-04-" + locale + "-" + viewport.coordinate() + "-error.png");
            return new ConflictState(responseSeen.get(), observedStatus.get(), localDraft,
                    serverPreserved, visibleError(), safeBusinessError(errorText), recoveryVisible);
        } finally {
            try { devTools.send(Network.disable()); } catch (RuntimeException ignored) { }
            try { devTools.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void editTutorial(KeyboardJourney keyboard, String value) {
        WebElement input = keyboard.focus(By.cssSelector(".capability-duration-input input"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), value);
        keyboard.recordStep();
    }

    private Injector installInjector(String profile, String route, AtomicBoolean triggered) {
        DevTools devTools = driver.getDevTools();
        AtomicBoolean headersValid = new AtomicBoolean(
                !route.endsWith("/governed-runs/runId/evidence"));
        devTools.createSession();
        devTools.addListener(Fetch.requestPaused(), event -> {
            String url = event.getRequest().getUrl();
            String method = event.getRequest().getMethod();
            boolean target = sameRoute(url, route) && expectedMethod(route).equals(method);
            if (!target) {
                devTools.send(Fetch.continueRequest(event.getRequestId(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty()));
                return;
            }
            if (route.endsWith("/governed-runs/runId/evidence")) {
                headersValid.set("Bearer bloge-aneke-demo-token".equals(
                                headerValue(event.getRequest().getHeaders(), "Authorization"))
                        && "CAPABILITY_STUDIO_REHEARSAL".equals(
                                headerValue(event.getRequest().getHeaders(), "X-Purpose")));
            }
            triggered.set(true);
            if ("ERROR".equals(profile)) {
                String body = Base64.getEncoder().encodeToString(("{\"code\":\"CAPABILITY_STUDIO_TEMPORARILY_UNAVAILABLE\","
                        + "\"whatHappened\":\"The requested capability data is temporarily unavailable.\","
                        + "\"impact\":\"The current business view was not changed.\","
                        + "\"recoveryAction\":\"Retry the current business view.\"}").getBytes(StandardCharsets.UTF_8));
                devTools.send(Fetch.fulfillRequest(event.getRequestId(), 503,
                        Optional.of(List.of(new HeaderEntry("Content-Type", "application/json"))),
                        Optional.empty(), Optional.of(body), Optional.of("Service Unavailable")));
            } else {
                devTools.send(Fetch.failRequest(event.getRequestId(), ErrorReason.CONNECTIONFAILED));
            }
        });
        String patternRoute = route.replace("/governed-runs/runId/evidence",
                "/governed-runs/*/evidence");
        devTools.send(Fetch.enable(Optional.of(List.of(new RequestPattern(
                Optional.of("http://localhost:" + port + patternRoute + "*"), Optional.empty(),
                Optional.of(RequestStage.REQUEST)))), Optional.empty()));
        return new Injector(devTools, headersValid);
    }

    private static String headerValue(Map<String, Object> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(entry -> String.valueOf(entry.getValue()))
                .findFirst()
                .orElse("");
    }

    private void openDefaultWithInjectedFailure(String locale) {
        driver.get("http://localhost:" + port + "/capabilities/?lang=" + locale);
        waitFor(By.cssSelector("[data-testid='capability-load-error']"));
    }

    private void waitForError(String goldenPath) {
        By locator = switch (goldenPath) {
            case "GP-01", "GP-02", "GP-07" -> By.cssSelector("[data-testid='capability-load-error']");
            case "GP-03" -> By.cssSelector("[data-testid='capability-scenario-error']");
            case "GP-04" -> By.cssSelector("[data-testid='capability-tutorial-error']");
            case "GP-05", "GP-06" -> By.cssSelector(".capability-feature-error");
            case "GP-08" -> By.cssSelector(".capability-governed-error");
            case "GP-09" -> By.cssSelector("[data-testid='capability-quality-impact-error']");
            case "GP-10" -> By.cssSelector("[data-testid='governed-run-evidence-error']");
            default -> throw new IllegalArgumentException("unknown error path " + goldenPath);
        };
        waitFor(locator);
    }

    private boolean ready(String goldenPath) {
        return !driver.findElements(readyLocator(goldenPath)).isEmpty();
    }

    private By readyLocator(String goldenPath) {
        return switch (goldenPath) {
            case "GP-01", "GP-02", "GP-07" -> By.cssSelector("[data-testid='capability-overview'], [data-testid='capability-contract'], [data-testid='capability-tool']");
            case "GP-03" -> By.cssSelector("[data-testid='capability-scenarios']");
            case "GP-04" -> By.cssSelector("[data-testid='capability-tutorial-branch']");
            case "GP-05", "GP-06" -> By.cssSelector("[data-testid='capability-feature-rehearsal'] .feature-dag-node");
            case "GP-08" -> By.cssSelector("[data-testid='governed-baseline-result']");
            case "GP-09" -> By.cssSelector("[data-testid='capability-quality-impact']");
            case "GP-10" -> By.cssSelector("[data-testid='governed-run-evidence-panel']");
            default -> throw new IllegalArgumentException("unknown ready path " + goldenPath);
        };
    }

    private List<CapabilityStudioBrowserAnomalyMatrixArtifact.EvidenceRef> writeEvidence(
            String key, CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger trigger) throws IOException {
        ObjectNode material = JSON.createObjectNode()
                .put("mechanism", trigger.mechanism()).put("targetRoute", trigger.targetRoute())
                .put("observedFailureClass", trigger.observedFailureClass())
                .put("triggered", trigger.triggered())
                .put("businessPayloadIncluded", false);
        if (trigger.observedHttpStatus() == null) material.putNull("observedHttpStatus");
        else material.put("observedHttpStatus", trigger.observedHttpStatus());
        byte[] triggerBytes = JSON.writeValueAsBytes(material);
        String prefix = "artifact:browser-anomaly-evidence/" + key.toLowerCase(Locale.ROOT);
        Path triggerPath = evidenceDirectory.resolve(key.toLowerCase(Locale.ROOT) + "-trigger.json");
        Files.write(triggerPath, triggerBytes);
        return List.of(
                ref(prefix + "-error.png", screenshotFingerprint(key + "-error.png")),
                ref(prefix + "-recovered.png", screenshotFingerprint(key + "-recovered.png")),
                ref(prefix + "-trigger.json", rawFingerprint(triggerBytes)));
    }

    private CapabilityStudioBrowserAnomalyMatrixArtifact.EvidenceRef ref(String exactRef, String fingerprint) {
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.EvidenceRef(exactRef, fingerprint);
    }

    private String screenshotFingerprint(String file) throws IOException {
        Path path = evidenceDirectory.resolve(file);
        assertThat(path).as("real browser screenshot evidence").isRegularFile();
        return rawFingerprint(Files.readAllBytes(path));
    }

    private boolean hasEvidenceFile(String file) {
        Path path = evidenceDirectory.resolve(file);
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void resetEvidence(String key) throws IOException {
        Files.deleteIfExists(evidenceDirectory.resolve(key + "-error.png"));
        Files.deleteIfExists(evidenceDirectory.resolve(key + "-recovered.png"));
        Files.deleteIfExists(evidenceDirectory.resolve(
                key.toLowerCase(Locale.ROOT) + "-trigger.json"));
    }

    private String rawFingerprint(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("evidence cannot be fingerprinted", failure);
        }
    }

    private void capture(String file) throws IOException {
        Files.write(evidenceDirectory.resolve(file),
                ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
    }

    private BrowserAudit audit() throws IOException {
        JavascriptExecutor js = driver;
        @SuppressWarnings("unchecked") Map<String, Number> size = (Map<String, Number>) js.executeScript(
                "return {width: window.innerWidth, height: window.innerHeight};");
        Number overflow = (Number) js.executeScript("const r=document.scrollingElement||document.documentElement; return Math.max(0,r.scrollWidth-window.innerWidth);");
        String body = driver.findElement(By.tagName("body")).getText();
        Number raw = (Number) js.executeScript("return [...document.querySelectorAll('textarea:not([readonly]), [contenteditable=\"true\"]')].filter(n=>String(n.value??n.textContent??'').trim().match(/^[{[]/)).length;");
        int errors = driver.findElements(By.cssSelector(
                "[data-testid='capability-load-error'], [data-testid='capability-scenario-error'], "
                        + "[data-testid='capability-quality-impact-error'], [data-testid='capability-tutorial-error'], "
                        + ".capability-feature-error, .capability-governed-error, [data-testid='governed-run-evidence-error']")).size();
        AxeCounts axe = axeCounts();
        int technical = 0;
        for (String token : List.of("RG.CAPABILITY_STUDIO.", "METADATA_READY_RUNTIME_EVIDENCE_PENDING",
                "CONTRACT_READY_RUNTIME_PENDING", "DAG_CONTRACT_READY_RUNTIME_PENDING", "DEVELOPMENT_TEST_OWNED")) {
            for (int at = 0; (at = body.indexOf(token, at)) >= 0; at += token.length()) technical++;
        }
        return new BrowserAudit(new CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport(
                size.get("width").intValue(), size.get("height").intValue()), overflow.doubleValue() > 2,
                axe.serious(), axe.critical(), technical, raw.intValue(), errors);
    }

    @SuppressWarnings("unchecked")
    private AxeCounts axeCounts() throws IOException {
        driver.executeScript(Files.readString(axeSource));
        Map<String, Object> report = (Map<String, Object>) driver.executeAsyncScript(
                "const done=arguments[arguments.length-1]; window.axe.run(document).then(r=>done({serious:r.violations.filter(v=>v.impact==='serious').length,critical:r.violations.filter(v=>v.impact==='critical').length})).catch(()=>done({serious:0,critical:1}));");
        return new AxeCounts(((Number) report.get("serious")).intValue(), ((Number) report.get("critical")).intValue());
    }

    private void openTask(String task, CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport,
                          KeyboardJourney keyboard) {
        if (viewport.width() == 390) {
            int target = MOBILE_TASKS.indexOf(task);
            for (int i = 1; i <= target; i++) {
                WebElement tab = keyboard.focus(By.id("capability-mobile-task-" + MOBILE_TASKS.get(i - 1)));
                tab.sendKeys(Keys.ARROW_RIGHT); keyboard.recordStep();
            }
            return;
        }
        if ("overview".equals(task) || "contract".equals(task)) {
            List<WebElement> buttons = driver.findElements(By.cssSelector(".capability-sidebar .capability-task-button"));
            keyboard.activate(buttons.get("overview".equals(task) ? 0 : 1), Keys.ENTER);
        } else keyboard.activate(By.cssSelector("[data-testid='capability-task-" + task + "']"), Keys.ENTER);
    }

    private void openDefault(String locale) {
        driver.get("http://localhost:" + port + "/capabilities/?lang=" + locale);
        waitFor(By.cssSelector("[data-testid='capability-overview']"));
    }

    private JsonNode readTutorialBranch() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/capability-studio/tutorial-branch"))
                .header("Authorization", DEMO_AUTHORIZATION)
                .header("X-Purpose", DEMO_PURPOSE)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static boolean safeBusinessError(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return (lower.contains("what happened") || text.contains("发生了什么"))
                && (lower.contains("impact") || text.contains("影响"))
                && (lower.contains("recovery") || lower.contains("retry") || text.contains("恢复") || text.contains("重试"))
                && !text.matches("(?s).*\\bRG\\.[A-Z0-9_.-]+\\b.*")
                && !text.matches("(?s).*\\{.*\\}.*");
    }

    private boolean visibleError() {
        return driver.findElements(By.cssSelector(
                ".capability-error-state, .capability-operation-error")).stream()
                .anyMatch(this::intersectsViewport);
    }

    private boolean recoveryActionVisible() {
        return driver.findElements(By.cssSelector(
                ".capability-error-state button, .capability-operation-error button")).stream()
                .anyMatch(this::fullyWithinViewport);
    }

    private boolean intersectsViewport(WebElement element) {
        return Boolean.TRUE.equals(driver.executeScript(
                "const r=arguments[0].getBoundingClientRect(); "
                        + "return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 "
                        + "&& r.top < window.innerHeight && r.left < window.innerWidth;",
                element));
    }

    private boolean fullyWithinViewport(WebElement element) {
        return Boolean.TRUE.equals(driver.executeScript(
                "const r=arguments[0].getBoundingClientRect(); "
                        + "return r.width > 0 && r.height > 0 && r.top >= 0 && r.left >= 0 "
                        + "&& r.bottom <= window.innerHeight && r.right <= window.innerWidth;",
                element));
    }

    private static boolean isDemoPackRoute(String route) { return route.endsWith("/demo-pack"); }

    private static String targetRoute(String goldenPath) {
        return switch (goldenPath) {
            case "GP-01", "GP-02", "GP-07" -> "/api/capability-studio/demo-pack";
            case "GP-03" -> "/api/capability-studio/scenario-dataset";
            case "GP-04" -> "/api/capability-studio/tutorial-branch/behaviors/compensation-history";
            case "GP-05", "GP-06" -> "/api/capability-studio/feature-rehearsal";
            case "GP-08" -> "/api/capability-studio/governed-baseline";
            case "GP-09" -> "/api/capability-studio/scenario-dataset/quality-impact";
            case "GP-10" -> "/api/capability-studio/governed-runs/runId/evidence";
            default -> throw new IllegalArgumentException("unknown target route " + goldenPath);
        };
    }

    private static boolean sameRoute(String url, String route) {
        try {
            String path = URI.create(url).getPath();
            if (route.endsWith("/governed-runs/runId/evidence")) {
                return path.matches("/api/capability-studio/governed-runs/[^/]+/evidence");
            }
            return route.equals(path);
        } catch (RuntimeException ignored) { return false; }
    }

    private static String expectedMethod(String route) {
        if (route.endsWith("/behaviors/compensation-history")) return "PUT";
        if (route.endsWith("/governed-baseline")) return "POST";
        return "GET";
    }

    private CapabilityStudioBrowserAnomalyMatrixArtifact.Candidate candidate(JsonNode base) {
        JsonNode value = base.path("candidate");
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.Candidate(
                value.path("buildRef").asText(), value.path("revision").asText(),
                value.path("artifactFingerprint").asText(), value.path("sourceCommit").asText(),
                value.path("sourceTreeStatus").asText());
    }

    private CapabilityStudioBrowserAnomalyMatrixArtifact.Baseline baseline(JsonNode base) {
        JsonNode value = base.path("baselineRef");
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.Baseline(
                value.path("id").asText(), value.path("revision").asInt(), value.path("fingerprint").asText());
    }

    private CapabilityStudioBrowserAnomalyMatrixArtifact.Environment environment(
            JsonNode base, Path repository, Path executable) throws IOException {
        JsonNode value = base.path("environment");
        String axeVersion = JSON.readTree(Files.readString(
                axeSource.getParent().resolve("package.json"))).path("version").asText();
        String driverVersion = command(repository, executable.toString(), "--version")
                .replace("ChromeDriver ", "").split(" ")[0];
        String browserVersion = probeBrowserVersion(executable);
        ObjectNode material = JSON.createObjectNode()
                .put("profile", "test")
                .put("browserName", "chrome")
                .put("browserVersion", browserVersion)
                .put("driverVersion", driverVersion)
                .put("axeVersion", axeVersion)
                .put("os", System.getProperty("os.name") + "-" + System.getProperty("os.arch"))
                .put("java", System.getProperty("java.version"));
        CapabilityStudioBrowserAnomalyMatrixArtifact.Environment observed =
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Environment(
                        CapabilityStudioBrowserAnomalyMatrixArtifact.fingerprint(material),
                        "test", "chrome", browserVersion, driverVersion, axeVersion);
        assertThat(observed.environmentFingerprint())
                .as("anomaly Chrome environment matches COMPLETE normal base")
                .isEqualTo(value.path("environmentFingerprint").asText());
        assertThat(observed.browserVersion()).isEqualTo(value.path("browserVersion").asText());
        assertThat(observed.driverVersion()).isEqualTo(value.path("driverVersion").asText());
        assertThat(observed.axeVersion()).isEqualTo(value.path("axeVersion").asText());
        return observed;
    }

    private void verifyBaseFingerprint(JsonNode base) {
        ObjectNode material = (ObjectNode) base.deepCopy();
        material.remove("evidenceClosureFingerprint");
        assertThat(CapabilityStudioBrowserMatrixArtifact.fingerprint(material))
                .as("base Browser Matrix material fingerprint")
                .isEqualTo(base.path("evidenceClosureFingerprint").asText());
    }

    private void verifyCandidateArtifact(
            Path repository,
            CapabilityStudioBrowserAnomalyMatrixArtifact.Candidate candidate) throws IOException {
        Path canonicalArtifact = repository.resolve("resource-gateway-examples/target/"
                + "bloge-examples-resource-gateway-1.0.0.jar").toAbsolutePath().normalize();
        Path configuredArtifact = Path.of(propertyOr(
                "capability.browser.anomaly.candidate-artifact",
                canonicalArtifact.toString())).toAbsolutePath().normalize();
        assertThat(canonicalArtifact)
                .as("canonical candidate artifact bound by COMPLETE normal base")
                .isRegularFile();
        Path resolvedCanonicalArtifact;
        try {
            resolvedCanonicalArtifact = canonicalArtifact.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "canonical candidate artifact cannot be resolved: " + canonicalArtifact,
                    failure);
        }
        Path resolvedConfiguredArtifact;
        try {
            resolvedConfiguredArtifact = configuredArtifact.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "candidate artifact must resolve to canonical repository artifact: "
                            + resolvedCanonicalArtifact,
                    failure);
        }
        assertThat(resolvedConfiguredArtifact)
                .as("candidate artifact must be the canonical repository artifact %s",
                        resolvedCanonicalArtifact)
                .isEqualTo(resolvedCanonicalArtifact);
        assertThat(rawFingerprint(Files.readAllBytes(resolvedCanonicalArtifact)))
                .as("candidate artifact fingerprint matches COMPLETE normal base")
                .isEqualTo(candidate.artifactFingerprint());

        String currentCommit = command(repository, "git", "rev-parse", "HEAD");
        assertThat(currentCommit)
                .as("current git HEAD must match Base Matrix candidate.sourceCommit")
                .isEqualTo(candidate.sourceCommit());
        String mainSourceStatus = command(repository, "git", "status", "--porcelain=v1",
                "--untracked-files=all", "--", "resource-gateway-examples/src/main");
        assertThat(mainSourceStatus)
                .as("resource-gateway-examples/src/main must have no tracked or untracked changes")
                .isBlank();
    }

    private String probeBrowserVersion(Path executable) {
        openBrowser(executable, CapabilityStudioBrowserAnomalyMatrixArtifact.VIEWPORTS.getFirst());
        try {
            return driver.getCapabilities().getBrowserVersion();
        } finally {
            closeBrowser();
        }
    }

    private void openBrowser(Path executable,
                             CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--disable-dev-shm-usage",
                "--no-sandbox", "--window-size=" + viewport.width() + "," + viewport.height());
        if (Files.isExecutable(MAC_CHROME_BINARY)) options.setBinary(MAC_CHROME_BINARY.toString());
        driverService = new ChromeDriverService.Builder().usingDriverExecutable(executable.toFile())
                .usingAnyFreePort().build();
        driver = new ChromeDriver(driverService, options);
        driver.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
                "width", viewport.width(), "height", viewport.height(), "deviceScaleFactor", 1, "mobile", false));
        assertThat(((Number) driver.executeScript("return window.innerWidth;")).intValue()).isEqualTo(viewport.width());
    }

    private void closeBrowser() {
        if (driver != null) { try { driver.quit(); } catch (RuntimeException ignored) { } finally { driver = null; } }
        if (driverService != null) { driverService.stop(); driverService = null; }
    }

    private WebElement waitFor(By locator) { return new WebDriverWait(driver, WAIT_TIMEOUT).until(ExpectedConditions.visibilityOfElementLocated(locator)); }

    private Path chromeDriverExecutable() throws IOException {
        String configured = System.getProperty("webdriver.chrome.driver", "").trim();
        if (!configured.isBlank() && Files.isExecutable(Path.of(configured))) return Path.of(configured);
        try (var paths = Files.find(CHROMEDRIVER_CACHE, 4, (path, attributes) -> attributes.isRegularFile()
                && "chromedriver".equals(path.getFileName().toString()) && Files.isExecutable(path))) {
            Path cached = paths.sorted(Comparator.reverseOrder()).findFirst().orElse(null);
            assertThat(cached).as("ChromeDriver for real browser anomaly matrix").isNotNull();
            return cached;
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if ("resource-gateway-examples".equals(current.getFileName().toString())) return current.getParent();
        if (Files.isDirectory(current.resolve("resource-gateway-examples"))) return current;
        throw new IllegalStateException("repository root cannot be resolved from " + current);
    }

    private Path outputPath(Path repository) { return Path.of(propertyOr("capability.browser.anomaly.output",
            repository.resolve("resource-gateway-examples/target/acceptance/capability-studio-browser-anomaly-matrix-result-v1.json").toString())); }

    private Path baseOutputPath(Path repository) {
        String configured = System.getProperty("capability.browser.matrix.base-output", "").trim();
        if (configured.isBlank()) {
            configured = System.getProperty("capability.browser.anomaly.base", "").trim();
        }
        return Path.of(configured.isBlank() ? repository.resolve(
                "resource-gateway-examples/target/acceptance/capability-studio-browser-matrix-result-v1.json")
                .toString() : configured);
    }

    private boolean developmentMode(Path repository) {
        return Boolean.getBoolean("capability.browser.anomaly.development")
                || !command(repository, "git", "status", "--porcelain=v1").isBlank();
    }

    private boolean requireComplete() {
        return Boolean.getBoolean("capability.browser.matrix.require-complete")
                || Boolean.getBoolean("capability.browser.anomaly.require-complete");
    }

    private String propertyOr(String name, String fallback) { String value = System.getProperty(name, "").trim(); return value.isBlank() ? fallback : value; }

    private String command(Path directory, String... command) {
        try { Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.waitFor() != 0) throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
            return output;
        } catch (IOException failure) { throw new IllegalStateException(String.join(" ", command) + " failed", failure); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(String.join(" ", command) + " was interrupted", interrupted); }
    }

    private record AxeCounts(int serious, int critical) { }
    private record BrowserAudit(CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport actualViewport,
                                boolean overflow, int axeSerious, int axeCritical, int technicalIds,
                                int rawJson, int browserErrors) {
        int p0() { return axeCritical > 0 || browserErrors > 0 ? 1 : 0; }
        int p1() { return overflow || axeSerious > 0 || technicalIds > 0 || rawJson > 0 ? 1 : 0; }
    }
    private record BrowserFacts(CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport actualViewport,
                                boolean overflow, int axeSerious, int axeCritical, int technicalIds,
                                int rawJson, boolean keyboardCompleted, int keyboardSteps,
                                int focusLosses, boolean errorVisible, boolean businessSafe,
                                boolean recoveryVisible, boolean recoveryAttempted, boolean recovered,
                                boolean localDraft, boolean serverRevision, boolean staleGreen,
                                boolean staleError, boolean staleEvidence, boolean staleSuccess,
                                int p0, int p1) {
        boolean pass(CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport expected, boolean conflict) {
            return actualViewport.equals(expected) && !overflow && axeSerious == 0 && axeCritical == 0
                    && technicalIds == 0 && rawJson == 0 && keyboardCompleted && keyboardSteps >= 1
                    && focusLosses == 0 && errorVisible && businessSafe && recoveryVisible
                    && recoveryAttempted && recovered && (!conflict || (localDraft && serverRevision))
                    && staleGreen && staleError && staleEvidence && staleSuccess && p0 == 0 && p1 == 0;
        }
        CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations toArtifact() {
            return new CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations(actualViewport,
                    overflow, new CapabilityStudioBrowserAnomalyMatrixArtifact.Axe(axeSerious, axeCritical),
                    technicalIds, rawJson,
                    new CapabilityStudioBrowserAnomalyMatrixArtifact.KeyboardPath(keyboardCompleted, keyboardSteps, focusLosses),
                    errorVisible, businessSafe, recoveryVisible, recoveryAttempted, recovered, localDraft,
                    serverRevision, staleGreen, staleError, staleEvidence, staleSuccess, p0, p1);
        }
    }
    private record ConflictState(
            boolean triggered,
            int status,
            boolean localDraft,
            boolean serverRevision,
            boolean errorVisible,
            boolean businessSafe,
            boolean recoveryVisible) { }
    private record Filter(
            Set<String> profiles,
            Set<String> goldenPaths,
            Set<String> locales,
            Set<String> viewports,
            boolean active) {
        static Filter read() {
            FilterValue profiles = values("capability.browser.anomaly.profile",
                    List.of("ERROR", "OFFLINE", "CONFLICT"));
            FilterValue goldenPaths = values("capability.browser.anomaly.gp",
                    CapabilityStudioBrowserAnomalyMatrixArtifact.GOLDEN_PATHS);
            FilterValue locales = values("capability.browser.anomaly.locale",
                    CapabilityStudioBrowserAnomalyMatrixArtifact.LOCALES);
            FilterValue viewports = values("capability.browser.anomaly.viewport",
                    List.of("1440x900", "1024x768", "390x844"));
            return new Filter(profiles.selected(), goldenPaths.selected(), locales.selected(),
                    viewports.selected(), profiles.filtered() || goldenPaths.filtered()
                            || locales.filtered() || viewports.filtered());
        }

        private static FilterValue values(String key, List<String> allowed) {
            String configured = System.getProperty(key, "").trim();
            if (configured.isBlank()) return new FilterValue(Set.copyOf(allowed), false);
            Set<String> selected = Set.of(configured.split(","));
            assertThat(allowed).containsAll(selected);
            return new FilterValue(selected, true);
        }
    }
    private record FilterValue(Set<String> selected, boolean filtered) { }
    private final class Injector {
        private final DevTools devTools;
        private final AtomicBoolean headersValid;
        private boolean enabled = true;
        private Injector(DevTools devTools, AtomicBoolean headersValid) {
            this.devTools = devTools;
            this.headersValid = headersValid;
        }
        private boolean headersValid() { return headersValid.get(); }
        private void disable() {
            if (!enabled) return;
            try { devTools.send(Fetch.disable()); }
            catch (RuntimeException ignored) { }
            finally { enabled = false; }
        }
        private void close() {
            disable();
            try { devTools.close(); } catch (RuntimeException ignored) { }
        }
    }
    private final class KeyboardJourney {
        private int steps;
        private int focusLosses;
        WebElement focus(By locator) { return focus(new WebDriverWait(driver, WAIT_TIMEOUT).until(ExpectedConditions.presenceOfElementLocated(locator))); }
        WebElement focus(WebElement target) {
            for (int i = 0; i < 120; i++) {
                if (target.equals(driver.switchTo().activeElement())) return target;
                driver.switchTo().activeElement().sendKeys(Keys.TAB);
            }
            focusLosses++;
            return target;
        }
        void activate(By locator, Keys key) { WebElement target = focus(locator); if (!target.equals(driver.switchTo().activeElement())) focusLosses++; target.sendKeys(key); steps++; }
        void activate(WebElement target, Keys key) { WebElement focused = focus(target); if (!focused.equals(driver.switchTo().activeElement())) focusLosses++; focused.sendKeys(key); steps++; }
        void recordStep() { steps++; }
        boolean completed() { return steps > 0 && focusLosses == 0; }
        int steps() { return steps; }
        int focusLosses() { return focusLosses; }
    }
}
