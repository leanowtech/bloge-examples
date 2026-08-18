package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic producer for Capability Studio Browser Anomaly Matrix Result v1.
 *
 * <p>The denominator is protocol-owned: ERROR and OFFLINE each contain 10 golden paths x 2
 * locales x 3 viewports, while CONFLICT contains GP-04 x 2 locales x 3 viewports. A new builder
 * starts with all 126 obligations as {@code NOT_RUN}. It can therefore never turn an absent
 * browser execution into a passing result.</p>
 *
 * <p>Summary, root status, diagnostics and closure are derived fields. The public API only accepts
 * typed observations and immutable binding facts, so callers cannot supply a root closure or
 * authority projection.</p>
 */
public final class CapabilityStudioBrowserAnomalyMatrixResultBuilder {
    /** Result schema version emitted by this builder. */
    public static final String SCHEMA_VERSION =
            "bloge.capabilityStudioBrowserAnomalyMatrixResult.v1";
    /** Acceptance contract identity emitted by this builder. */
    public static final String CONTRACT_ID = "S0-AC-01";
    /** Fixed obligation denominator. */
    public static final int EXPECTED_OBLIGATION_COUNT = 126;
    /** ERROR obligation denominator. */
    public static final int EXPECTED_ERROR_COUNT = 60;
    /** OFFLINE obligation denominator. */
    public static final int EXPECTED_OFFLINE_COUNT = 60;
    /** CONFLICT obligation denominator. */
    public static final int EXPECTED_CONFLICT_COUNT = 6;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> GOLDEN_PATHS = List.of(
            "GP-01", "GP-02", "GP-03", "GP-04", "GP-05",
            "GP-06", "GP-07", "GP-08", "GP-09", "GP-10");
    private static final List<String> LOCALES = List.of("zh-CN", "en-US");
    private static final List<Viewport> VIEWPORTS = List.of(
            new Viewport(1440, 900), new Viewport(1024, 768), new Viewport(390, 844));
    private static final List<StateProfile> PROFILE_ORDER = List.of(
            StateProfile.ERROR, StateProfile.OFFLINE, StateProfile.CONFLICT);

    private final String resultId;
    private final int revision;
    private final String contractRevision;
    private final Candidate candidate;
    private final BaselineRef baselineRef;
    private final Environment environment;
    private final ExecutionWindow executionWindow;
    private final BaseMatrixRef baseMatrixRef;
    private final Map<ObligationKey, Observation> observations = new LinkedHashMap<>();
    private final Set<ObligationKey> recorded = new LinkedHashSet<>();

    /**
     * Creates a builder with all 126 obligations in {@code NOT_RUN} state.
     *
     * @param resultId result artifact identity
     * @param revision result artifact revision
     * @param contractRevision anomaly contract revision
     * @param candidate candidate binding
     * @param baselineRef acceptance baseline binding
     * @param environment browser environment binding
     * @param executionWindow execution window
     * @param baseMatrixRef exact, already-complete normal browser matrix reference
     */
    public CapabilityStudioBrowserAnomalyMatrixResultBuilder(
            String resultId,
            int revision,
            String contractRevision,
            Candidate candidate,
            BaselineRef baselineRef,
            Environment environment,
            ExecutionWindow executionWindow,
            BaseMatrixRef baseMatrixRef) {
        this.resultId = requirePattern(resultId, "resultId", "BAMR-[A-Za-z0-9._-]{1,120}");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        this.revision = revision;
        this.contractRevision = requireSafeRef(contractRevision, "contractRevision");
        this.candidate = Objects.requireNonNull(candidate, "candidate is required");
        this.baselineRef = Objects.requireNonNull(baselineRef, "baselineRef is required");
        this.environment = Objects.requireNonNull(environment, "environment is required");
        this.executionWindow = Objects.requireNonNull(
                executionWindow, "executionWindow is required");
        executionWindow.validateOrder();
        this.baseMatrixRef = Objects.requireNonNull(baseMatrixRef, "baseMatrixRef is required");
        expectedObligations().forEach(key -> observations.put(key, Observation.notRun(key)));
    }

    /**
     * Returns the fixed obligations in protocol order.
     *
     * @return immutable canonical obligation list
     */
    public static List<ObligationKey> expectedObligations() {
        List<ObligationKey> keys = new ArrayList<>(EXPECTED_OBLIGATION_COUNT);
        for (StateProfile profile : PROFILE_ORDER) {
            for (String goldenPath : GOLDEN_PATHS) {
                if (profile == StateProfile.CONFLICT && !"GP-04".equals(goldenPath)) {
                    continue;
                }
                for (String locale : LOCALES) {
                    for (Viewport viewport : VIEWPORTS) {
                        keys.add(new ObligationKey(profile, goldenPath, locale, viewport));
                    }
                }
            }
        }
        return List.copyOf(keys);
    }

    /**
     * Records one observation; duplicate and unknown obligations fail immediately.
     *
     * @param key fixed obligation identity
     * @param observation typed browser observation
     * @return this builder
     */
    public CapabilityStudioBrowserAnomalyMatrixResultBuilder record(
            ObligationKey key,
            Observation observation) {
        Objects.requireNonNull(key, "obligation key is required");
        Objects.requireNonNull(observation, "observation is required");
        if (!observations.containsKey(key)) {
            throw new IllegalArgumentException("unknown anomaly obligation: " + key.obligationId());
        }
        if (!key.equals(observation.key())) {
            throw new IllegalArgumentException("observation key does not match obligation");
        }
        if (!expectedTargetRoute(key).equals(observation.trigger().targetRoute())) {
            throw new IllegalArgumentException("observation target route does not match obligation");
        }
        if (!recorded.add(key)) {
            throw new IllegalStateException("anomaly obligation already recorded: " + key.obligationId());
        }
        observations.put(key, observation);
        return this;
    }

    /**
     * Alias for producer-oriented callers.
     *
     * @param key fixed obligation identity
     * @param observation typed browser observation
     * @return this builder
     */
    public CapabilityStudioBrowserAnomalyMatrixResultBuilder recordObservation(
            ObligationKey key,
            Observation observation) {
        return record(key, observation);
    }

    /**
     * Records a strict passing browser observation.
     *
     * @param key fixed obligation identity
     * @param browserObservations observed browser facts
     * @param evidenceRefs payload-free evidence coordinates
     * @return this builder
     */
    public CapabilityStudioBrowserAnomalyMatrixResultBuilder pass(
            ObligationKey key,
            BrowserObservations browserObservations,
            List<EvidenceRef> evidenceRefs) {
        return record(key, Observation.pass(key, browserObservations, evidenceRefs));
    }

    /**
     * Records a real observed failure without converting it into a fabricated pass.
     *
     * @param key fixed obligation identity
     * @param trigger observed trigger facts
     * @param browserObservations observed browser facts
     * @param evidenceRefs payload-free evidence coordinates
     * @return this builder
     */
    public CapabilityStudioBrowserAnomalyMatrixResultBuilder fail(
            ObligationKey key,
            Trigger trigger,
            BrowserObservations browserObservations,
            List<EvidenceRef> evidenceRefs) {
        return record(key, Observation.fail(key, trigger, browserObservations, evidenceRefs));
    }

    /**
     * Explicitly records non-execution. The emitted obligation contains no browser evidence.
     *
     * @param key fixed obligation identity
     * @return this builder
     */
    public CapabilityStudioBrowserAnomalyMatrixResultBuilder notRun(ObligationKey key) {
        return record(key, Observation.notRun(key));
    }

    /**
     * Produces a detached, deterministic JSON artifact.
     *
     * @return strict anomaly result artifact
     */
    public ObjectNode build() {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("resultId", resultId);
        result.put("revision", revision);
        result.put("contractId", CONTRACT_ID);
        result.put("contractRevision", contractRevision);
        writeCandidate(result.putObject("candidate"));
        writeBaseline(result.putObject("baselineRef"));
        writeEnvironment(result.putObject("environment"));
        writeExecutionWindow(result.putObject("executionWindow"));
        writeBaseMatrixRef(result.putObject("baseMatrixRef"));
        result.put("resultStatus", resultStatus());
        ArrayNode obligations = result.putArray("obligations");
        expectedObligations().forEach(key -> writeObligation(
                obligations.addObject(), key, observations.get(key)));
        writeSummary(result.putObject("summary"));
        writeDiagnostics(result.putArray("diagnostics"));
        ObjectNode material = result.deepCopy();
        result.put("evidenceClosureFingerprint", EvidenceVerificationSupport.sha256Bounded(
                material, CapabilityStudioBrowserAnomalyMatrixResultVerifier.MAXIMUM_RESULT_BYTES));
        return result;
    }

    /**
     * Produces the exact UTF-8 JSON wire artifact.
     *
     * @return UTF-8 encoded anomaly result
     */
    public byte[] buildBytes() {
        try {
            return JSON.writeValueAsBytes(build());
        } catch (Exception failure) {
            throw new IllegalStateException("browser anomaly matrix cannot be serialized", failure);
        }
    }

    /**
     * Alias for producer-oriented callers.
     *
     * @return strict anomaly result artifact
     */
    public ObjectNode produce() {
        return build();
    }

    private void writeCandidate(ObjectNode target) {
        target.put("buildRef", candidate.buildRef());
        target.put("revision", candidate.revision());
        target.put("artifactFingerprint", candidate.artifactFingerprint());
        target.put("sourceCommit", candidate.sourceCommit());
        target.put("sourceTreeStatus", candidate.sourceTreeStatus().name());
    }

    private void writeBaseline(ObjectNode target) {
        target.put("id", baselineRef.id());
        target.put("revision", baselineRef.revision());
        target.put("fingerprint", baselineRef.fingerprint());
    }

    private void writeEnvironment(ObjectNode target) {
        target.put("environmentFingerprint", environment.environmentFingerprint());
        target.put("profile", environment.profile());
        target.put("browserName", environment.browserName());
        target.put("browserVersion", environment.browserVersion());
        target.put("driverVersion", environment.driverVersion());
        target.put("axeVersion", environment.axeVersion());
    }

    private void writeExecutionWindow(ObjectNode target) {
        target.put("startedAt", executionWindow.startedAt());
        target.put("completedAt", executionWindow.completedAt());
    }

    private void writeBaseMatrixRef(ObjectNode target) {
        target.put("exactRef", baseMatrixRef.exactRef());
        target.put("fingerprint", baseMatrixRef.fingerprint());
        target.put("resultStatus", baseMatrixRef.resultStatus().name());
    }

    private void writeObligation(ObjectNode target, ObligationKey key, Observation observation) {
        target.put("obligationId", key.obligationId());
        target.put("stateProfile", key.profile().name());
        target.put("goldenPathId", key.goldenPathId());
        target.put("locale", key.locale());
        writeViewport(target.putObject("viewport"), key.viewport());
        target.put("status", observation.status().name());
        writeTrigger(target.putObject("trigger"), observation.trigger());
        target.put("expectedUiState", key.profile().name() + "_FEEDBACK");
        target.put("expectedRecoveryAction",
                key.profile() == StateProfile.CONFLICT ? "RETRY_OR_MERGE" : "RETRY");
        writeBrowserObservations(target.putObject("browserObservations"), observation.browser());
        ArrayNode evidence = target.putArray("evidenceRefs");
        observation.evidenceRefs().forEach(ref -> evidence.addObject()
                .put("exactRef", ref.exactRef())
                .put("fingerprint", ref.fingerprint()));
    }

    private static void writeTrigger(ObjectNode target, Trigger trigger) {
        target.put("mechanism", trigger.mechanism().name());
        target.put("targetRoute", trigger.targetRoute());
        target.put("observedFailureClass", trigger.observedFailureClass().name());
        if (trigger.observedHttpStatus() == null) {
            target.putNull("observedHttpStatus");
        } else {
            target.put("observedHttpStatus", trigger.observedHttpStatus());
        }
        target.put("triggered", trigger.triggered());
    }

    private static void writeBrowserObservations(ObjectNode target, BrowserObservations browser) {
        if (browser.actualViewport() == null) {
            target.putNull("actualViewport");
        } else {
            writeViewport(target.putObject("actualViewport"), browser.actualViewport());
        }
        target.put("pageHorizontalOverflow", browser.pageHorizontalOverflow());
        target.putObject("axe")
                .put("serious", browser.axe().serious())
                .put("critical", browser.axe().critical());
        target.put("technicalIdCount", browser.technicalIdCount());
        target.put("rawJsonCount", browser.rawJsonCount());
        target.putObject("keyboardPath")
                .put("completed", browser.keyboardPath().completed())
                .put("steps", browser.keyboardPath().steps())
                .put("focusLosses", browser.keyboardPath().focusLosses());
        target.put("errorVisible", browser.errorVisible());
        target.put("businessSafeExplanation", browser.businessSafeExplanation());
        target.put("recoveryActionVisible", browser.recoveryActionVisible());
        target.put("recoveryAttempted", browser.recoveryAttempted());
        target.put("recoveredToReady", browser.recoveredToReady());
        target.put("localDraftRetained", browser.localDraftRetained());
        target.put("serverRevisionPreserved", browser.serverRevisionPreserved());
        target.put("staleGreenPreflightAbsent", browser.staleGreenPreflightAbsent());
        target.put("staleErrorAbsent", browser.staleErrorAbsent());
        target.put("staleEvidenceAbsent", browser.staleEvidenceAbsent());
        target.put("staleSuccessAbsent", browser.staleSuccessAbsent());
        target.put("p0Count", browser.p0Count());
        target.put("p1Count", browser.p1Count());
    }

    private static void writeViewport(ObjectNode target, Viewport viewport) {
        target.put("width", viewport.width());
        target.put("height", viewport.height());
    }

    private void writeSummary(ObjectNode target) {
        long passed = count(Status.PASS);
        long failed = count(Status.FAIL);
        long notRun = count(Status.NOT_RUN);
        target.put("expected", EXPECTED_OBLIGATION_COUNT);
        target.put("actual", observations.size());
        target.put("passed", passed);
        target.put("failed", failed);
        target.put("notRun", notRun);
        target.put("errorExpected", EXPECTED_ERROR_COUNT);
        target.put("offlineExpected", EXPECTED_OFFLINE_COUNT);
        target.put("conflictExpected", EXPECTED_CONFLICT_COUNT);
    }

    private void writeDiagnostics(ArrayNode target) {
        Set<String> codes = new LinkedHashSet<>();
        if (candidate.sourceTreeStatus() != SourceTreeStatus.CLEAN) {
            codes.add("CANDIDATE_SOURCE_TREE_NOT_CLEAN");
        }
        if (baseMatrixRef.resultStatus() != BaseMatrixStatus.COMPLETE) {
            codes.add("BASE_MATRIX_NOT_COMPLETE");
        }
        if (count(Status.FAIL) > 0) {
            codes.add("ANOMALY_OBLIGATION_FAILED");
        }
        if (count(Status.NOT_RUN) > 0) {
            codes.add("ANOMALY_OBLIGATION_NOT_RUN");
        }
        codes.stream().sorted().forEach(code -> target.addObject().put("code", code));
    }

    private String resultStatus() {
        if (candidate.sourceTreeStatus() != SourceTreeStatus.CLEAN
                || baseMatrixRef.resultStatus() != BaseMatrixStatus.COMPLETE
                || count(Status.FAIL) > 0) {
            return "FAILED";
        }
        return count(Status.NOT_RUN) > 0 ? "NOT_RUN" : "COMPLETE";
    }

    private long count(Status status) {
        return observations.values().stream().filter(o -> o.status() == status).count();
    }

    private static String expectedTargetRoute(ObligationKey key) {
        return switch (key.goldenPathId()) {
            case "GP-01", "GP-02", "GP-07" -> "/api/capability-studio/demo-pack";
            case "GP-03" -> "/api/capability-studio/scenario-dataset";
            case "GP-04" ->
                    "/api/capability-studio/tutorial-branch/behaviors/compensation-history";
            case "GP-05", "GP-06" -> "/api/capability-studio/feature-rehearsal";
            case "GP-08" -> "/api/capability-studio/governed-baseline";
            case "GP-09" -> "/api/capability-studio/scenario-dataset/quality-impact";
            case "GP-10" -> "/api/capability-studio/governed-runs/runId/evidence";
            default -> throw new IllegalArgumentException("unknown golden path target route");
        };
    }

    private static Trigger defaultTrigger(ObligationKey key) {
        return switch (key.profile()) {
            case ERROR -> new Trigger(TriggerMechanism.CDP_FETCH_FULFILL, expectedTargetRoute(key),
                    FailureClass.HTTP_5XX, null, false);
            case OFFLINE -> new Trigger(TriggerMechanism.CDP_FETCH_FAIL, expectedTargetRoute(key),
                    FailureClass.TRANSPORT_FAILURE, null, false);
            case CONFLICT -> new Trigger(TriggerMechanism.REAL_HTTP_STALE_REVISION,
                    expectedTargetRoute(key), FailureClass.REVISION_CONFLICT, null, false);
        };
    }

    private static BrowserObservations emptyBrowserObservations() {
        return new BrowserObservations(null, false, Axe.clear(), 0, 0,
                KeyboardPath.notRun(), false, false, false, false, false, false,
                false, false, false, false, false, 0, 0);
    }

    private static String requireSafeRef(String value, String field) {
        return requirePattern(value, field, "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    }

    private static String requireFingerprint(String value, String field) {
        return requirePattern(value, field, "sha256:[A-Fa-f0-9]{64}");
    }

    private static String requirePattern(String value, String field, String pattern) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(field + " has invalid protocol shape");
        }
        return value;
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    /** State profile whose failure/recovery contract is being tested. */
    public enum StateProfile {
        /** Server returned an HTTP error. */
        ERROR,
        /** Browser transport failed before an HTTP response. */
        OFFLINE,
        /** Server rejected a stale revision. */
        CONFLICT
    }

    /** Observation outcome for one fixed obligation. */
    public enum Status {
        /** Strict browser contract passed. */
        PASS,
        /** Real observation failed one or more contract facts. */
        FAIL,
        /** Browser execution did not produce an observation. */
        NOT_RUN
    }

    /** CDP or real HTTP mechanism used to trigger the state. */
    public enum TriggerMechanism {
        /** CDP fulfilled a request with an HTTP error. */
        CDP_FETCH_FULFILL,
        /** CDP failed a request at the transport layer. */
        CDP_FETCH_FAIL,
        /** A real stale revision request returned conflict. */
        REAL_HTTP_STALE_REVISION
    }

    /** Normalized observed failure classification. */
    public enum FailureClass {
        /** HTTP 400 through 499. */
        HTTP_4XX,
        /** HTTP 500 through 599. */
        HTTP_5XX,
        /** No HTTP response was received. */
        TRANSPORT_FAILURE,
        /** Server rejected a stale revision. */
        REVISION_CONFLICT
    }

    /** Candidate source tree state. */
    public enum SourceTreeStatus {
        /** Candidate source tree has no uncommitted changes. */
        CLEAN,
        /** Candidate source tree has uncommitted changes. */
        DIRTY
    }

    /** State of the exact normal browser matrix referenced by this result. */
    public enum BaseMatrixStatus {
        /** Normal browser matrix completed successfully. */
        COMPLETE
    }

    /**
     * Fixed browser viewport.
     *
     * @param width viewport width in CSS pixels
     * @param height viewport height in CSS pixels
     */
    public record Viewport(int width, int height) {
        /** Validates that this is one of the three protocol viewports. */
        public Viewport {
            if (!isFixed(width, height)) {
                throw new IllegalArgumentException("viewport is outside anomaly matrix");
            }
        }

        private static boolean isFixed(int width, int height) {
            return width == 1440 && height == 900
                    || width == 1024 && height == 768
                    || width == 390 && height == 844;
        }
    }

    /**
     * Candidate identity bound into the result.
     *
     * @param buildRef build reference
     * @param revision candidate revision
     * @param artifactFingerprint candidate artifact fingerprint
     * @param sourceCommit source commit
     * @param sourceTreeStatus source tree state
     */
    public record Candidate(
            String buildRef,
            String revision,
            String artifactFingerprint,
            String sourceCommit,
            SourceTreeStatus sourceTreeStatus) {
        /** Validates candidate identity. */
        public Candidate {
            buildRef = requireSafeRef(buildRef, "candidate.buildRef");
            revision = requireSafeRef(revision, "candidate.revision");
            artifactFingerprint = requireFingerprint(artifactFingerprint,
                    "candidate.artifactFingerprint");
        sourceCommit = requirePattern(sourceCommit, "candidate.sourceCommit", "[a-f0-9]{7,64}");
            sourceTreeStatus = Objects.requireNonNull(sourceTreeStatus,
                    "candidate.sourceTreeStatus is required");
        }
    }

    /**
     * Baseline identity shared with normal browser acceptance.
     *
     * @param id baseline identity
     * @param revision baseline revision
     * @param fingerprint baseline fingerprint
     */
    public record BaselineRef(String id, int revision, String fingerprint) {
        /** Validates baseline identity. */
        public BaselineRef {
            id = requireSafeRef(id, "baselineRef.id");
            if (revision < 1) {
                throw new IllegalArgumentException("baselineRef.revision must be positive");
            }
            fingerprint = requireFingerprint(fingerprint, "baselineRef.fingerprint");
        }
    }

    /**
     * Browser and axe runtime identity.
     *
     * @param environmentFingerprint environment fingerprint
     * @param profile environment profile
     * @param browserName browser name
     * @param browserVersion browser version
     * @param driverVersion driver version
     * @param axeVersion axe version
     */
    public record Environment(
            String environmentFingerprint,
            String profile,
            String browserName,
            String browserVersion,
            String driverVersion,
            String axeVersion) {
        /** Validates environment identity. */
        public Environment {
            environmentFingerprint = requireFingerprint(environmentFingerprint,
                    "environment.environmentFingerprint");
            profile = requireSafeRef(profile, "environment.profile");
            browserName = requireSafeRef(browserName, "environment.browserName");
            browserVersion = requireSafeRef(browserVersion, "environment.browserVersion");
            driverVersion = requireSafeRef(driverVersion, "environment.driverVersion");
            axeVersion = requireSafeRef(axeVersion, "environment.axeVersion");
        }
    }

    /**
     * Inclusive execution window.
     *
     * @param startedAt execution start timestamp
     * @param completedAt execution completion timestamp
     */
    public record ExecutionWindow(String startedAt, String completedAt) {
        /** Validates ISO-8601 timestamps. */
        public ExecutionWindow {
            parse(startedAt, "executionWindow.startedAt");
            parse(completedAt, "executionWindow.completedAt");
        }

        /**
         * Creates a window from offset date-times.
         *
         * @param startedAt execution start
         * @param completedAt execution completion
         */
        public ExecutionWindow(OffsetDateTime startedAt, OffsetDateTime completedAt) {
            this(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                            Objects.requireNonNull(startedAt, "startedAt is required")),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                            Objects.requireNonNull(completedAt, "completedAt is required")));
        }

        private static OffsetDateTime parse(String value, String field) {
            if (value == null) {
                throw new IllegalArgumentException(field + " is required");
            }
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException(field + " is not date-time", failure);
            }
        }

        private void validateOrder() {
            if (parse(completedAt, "executionWindow.completedAt")
                    .isBefore(parse(startedAt, "executionWindow.startedAt"))) {
                throw new IllegalArgumentException("execution window completes before it starts");
            }
        }
    }

    /**
     * Exact normal browser matrix coordinate.
     *
     * @param exactRef exact base artifact reference
     * @param fingerprint base artifact closure fingerprint
     * @param resultStatus base artifact status
     */
    public record BaseMatrixRef(String exactRef, String fingerprint, BaseMatrixStatus resultStatus) {
        /** Validates the base matrix reference. */
        public BaseMatrixRef {
            exactRef = requireSafeRef(exactRef, "baseMatrixRef.exactRef");
            fingerprint = requireFingerprint(fingerprint, "baseMatrixRef.fingerprint");
            resultStatus = Objects.requireNonNull(resultStatus, "baseMatrixRef.resultStatus");
        }
    }

    /**
     * Identity of one fixed anomaly obligation.
     *
     * @param profile anomaly state profile
     * @param goldenPathId golden path identity
     * @param locale UI locale
     * @param viewport fixed browser viewport
     */
    public record ObligationKey(
            StateProfile profile,
            String goldenPathId,
            String locale,
            Viewport viewport) {
        /** Validates profile, golden path, locale and conflict scope. */
        public ObligationKey {
            profile = Objects.requireNonNull(profile, "profile is required");
            if (!GOLDEN_PATHS.contains(goldenPathId)) {
                throw new IllegalArgumentException("unknown golden path: " + goldenPathId);
            }
            if (!LOCALES.contains(locale)) {
                throw new IllegalArgumentException("unknown locale: " + locale);
            }
            viewport = Objects.requireNonNull(viewport, "viewport is required");
            if (profile == StateProfile.CONFLICT && !"GP-04".equals(goldenPathId)) {
                throw new IllegalArgumentException("CONFLICT obligations are scoped to GP-04");
            }
        }

        /**
         * Returns the stable protocol identity.
         *
         * @return stable obligation identity
         */
        public String obligationId() {
            return "BAM-" + profile.name() + "-" + goldenPathId + "-" + locale + "-"
                    + viewport.width() + "x" + viewport.height();
        }
    }

    /**
     * Trigger facts recorded by the browser harness.
     *
     * @param mechanism trigger mechanism
     * @param targetRoute target route
     * @param observedFailureClass normalized failure class
     * @param observedHttpStatus observed HTTP status, when any
     * @param triggered whether the trigger was observed
     */
    public record Trigger(
            TriggerMechanism mechanism,
            String targetRoute,
            FailureClass observedFailureClass,
            Integer observedHttpStatus,
            boolean triggered) {
        /** Validates the shape of trigger facts; profile-specific semantics are verifier-owned. */
        public Trigger {
            mechanism = Objects.requireNonNull(mechanism, "trigger.mechanism");
            targetRoute = requirePattern(targetRoute, "trigger.targetRoute",
                    "/?[A-Za-z0-9][A-Za-z0-9._:/@+-]*");
            observedFailureClass = Objects.requireNonNull(
                    observedFailureClass, "trigger.observedFailureClass");
            if (observedHttpStatus != null
                    && (observedHttpStatus < 400 || observedHttpStatus > 599)) {
                throw new IllegalArgumentException("trigger.observedHttpStatus must be 400..599");
            }
        }
    }

    /**
     * Accessibility facts.
     *
     * @param serious serious axe violations
     * @param critical critical axe violations
     */
    public record Axe(int serious, int critical) {
        /** Validates non-negative counts. */
        public Axe {
            requireNonNegative(serious, "axe.serious");
            requireNonNegative(critical, "axe.critical");
        }

        /**
         * Returns a clear axe result.
         *
         * @return zero serious and critical violations
         */
        public static Axe clear() {
            return new Axe(0, 0);
        }
    }

    /**
     * Keyboard traversal facts.
     *
     * @param completed whether traversal completed
     * @param steps traversal step count
     * @param focusLosses focus loss count
     */
    public record KeyboardPath(boolean completed, int steps, int focusLosses) {
        /** Validates non-negative counts. */
        public KeyboardPath {
            requireNonNegative(steps, "keyboardPath.steps");
            requireNonNegative(focusLosses, "keyboardPath.focusLosses");
        }

        /**
         * Returns a completed traversal.
         *
         * @param steps traversal step count
         * @return completed traversal
         */
        public static KeyboardPath complete(int steps) {
            if (steps < 1) {
                throw new IllegalArgumentException("completed keyboard path needs a step");
            }
            return new KeyboardPath(true, steps, 0);
        }

        /**
         * Returns the no-observation traversal used by NOT_RUN.
         *
         * @return empty traversal
         */
        public static KeyboardPath notRun() {
            return new KeyboardPath(false, 0, 0);
        }
    }

    /**
     * Business-safe browser observations for one obligation.
     *
     * @param actualViewport measured viewport, or null when not observed
     * @param pageHorizontalOverflow page overflow flag
     * @param axe accessibility counts
     * @param technicalIdCount technical identifier count
     * @param rawJsonCount raw JSON leak count
     * @param keyboardPath keyboard traversal facts
     * @param errorVisible error state visibility
     * @param businessSafeExplanation business-safe explanation visibility
     * @param recoveryActionVisible recovery action visibility
     * @param recoveryAttempted whether recovery was attempted
     * @param recoveredToReady whether recovery reached READY
     * @param localDraftRetained whether local draft was retained
     * @param serverRevisionPreserved whether server revision was preserved
     * @param staleGreenPreflightAbsent whether stale green preflight was absent
     * @param staleErrorAbsent whether stale error was absent
     * @param staleEvidenceAbsent whether stale evidence was absent
     * @param staleSuccessAbsent whether stale success was absent
     * @param p0Count P0 finding count
     * @param p1Count P1 finding count
     */
    public record BrowserObservations(
            Viewport actualViewport,
            boolean pageHorizontalOverflow,
            Axe axe,
            int technicalIdCount,
            int rawJsonCount,
            KeyboardPath keyboardPath,
            boolean errorVisible,
            boolean businessSafeExplanation,
            boolean recoveryActionVisible,
            boolean recoveryAttempted,
            boolean recoveredToReady,
            boolean localDraftRetained,
            boolean serverRevisionPreserved,
            boolean staleGreenPreflightAbsent,
            boolean staleErrorAbsent,
            boolean staleEvidenceAbsent,
            boolean staleSuccessAbsent,
            int p0Count,
            int p1Count) {
        /** Validates non-negative findings and required fact objects. */
        public BrowserObservations {
            axe = Objects.requireNonNull(axe, "browserObservations.axe");
            keyboardPath = Objects.requireNonNull(keyboardPath,
                    "browserObservations.keyboardPath");
            requireNonNegative(technicalIdCount, "technicalIdCount");
            requireNonNegative(rawJsonCount, "rawJsonCount");
            requireNonNegative(p0Count, "p0Count");
            requireNonNegative(p1Count, "p1Count");
        }
    }

    /**
     * Evidence coordinate; payloads are intentionally impossible to pass to this API.
     *
     * @param exactRef exact evidence reference
     * @param fingerprint evidence fingerprint
     */
    public record EvidenceRef(String exactRef, String fingerprint) {
        /** Validates evidence identity. */
        public EvidenceRef {
            exactRef = requireSafeRef(exactRef, "evidenceRef.exactRef");
            fingerprint = requireFingerprint(fingerprint, "evidenceRef.fingerprint");
        }
    }

    /**
     * One immutable observation.
     *
     * @param key fixed obligation identity
     * @param status observation status
     * @param trigger trigger facts
     * @param browser browser facts
     * @param evidenceRefs evidence coordinates
     */
    public record Observation(
            ObligationKey key,
            Status status,
            Trigger trigger,
            BrowserObservations browser,
            List<EvidenceRef> evidenceRefs) {
        /** Validates the obligation key, status and deterministic evidence order. */
        public Observation {
            key = Objects.requireNonNull(key, "observation.key");
            status = Objects.requireNonNull(status, "observation.status");
            trigger = Objects.requireNonNull(trigger, "observation.trigger");
            browser = Objects.requireNonNull(browser, "observation.browser");
            evidenceRefs = sortedEvidence(evidenceRefs);
            if (status == Status.NOT_RUN && (!trigger.equals(defaultTrigger(key))
                    || !browser.equals(emptyBrowserObservations()) || !evidenceRefs.isEmpty())) {
                throw new IllegalArgumentException("NOT_RUN cannot contain browser evidence");
            }
        }

        /**
         * Creates the only valid non-execution observation.
         *
         * @param key fixed obligation identity
         * @return pure NOT_RUN observation
         */
        public static Observation notRun(ObligationKey key) {
            return new Observation(key, Status.NOT_RUN, defaultTrigger(key),
                    emptyBrowserObservations(), List.of());
        }

        /**
         * Creates a passing observation; semantic validation happens in the independent verifier.
         *
         * @param key fixed obligation identity
         * @param browser browser facts
         * @param evidenceRefs evidence coordinates
         * @return passing observation
         */
        public static Observation pass(
                ObligationKey key,
                BrowserObservations browser,
                List<EvidenceRef> evidenceRefs) {
            return new Observation(key, Status.PASS,
                    expectedTrigger(key, true), browser, evidenceRefs);
        }

        /**
         * Creates a real observed failure.
         *
         * @param key fixed obligation identity
         * @param trigger trigger facts
         * @param browser browser facts
         * @param evidenceRefs evidence coordinates
         * @return failed observation
         */
        public static Observation fail(
                ObligationKey key,
                Trigger trigger,
                BrowserObservations browser,
                List<EvidenceRef> evidenceRefs) {
            return new Observation(key, Status.FAIL, trigger, browser, evidenceRefs);
        }

        private static Trigger expectedTrigger(ObligationKey key, boolean triggered) {
            return switch (key.profile()) {
                case ERROR -> new Trigger(TriggerMechanism.CDP_FETCH_FULFILL,
                        expectedTargetRoute(key), FailureClass.HTTP_5XX, 503, triggered);
                case OFFLINE -> new Trigger(TriggerMechanism.CDP_FETCH_FAIL,
                        expectedTargetRoute(key), FailureClass.TRANSPORT_FAILURE, null, triggered);
                case CONFLICT -> new Trigger(TriggerMechanism.REAL_HTTP_STALE_REVISION,
                        expectedTargetRoute(key), FailureClass.REVISION_CONFLICT, 409, triggered);
            };
        }

        private static List<EvidenceRef> sortedEvidence(List<EvidenceRef> refs) {
            List<EvidenceRef> copy = refs == null ? new ArrayList<>() : new ArrayList<>(refs);
            if (copy.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("evidenceRefs cannot contain null");
            }
            copy.sort(Comparator.comparing(EvidenceRef::exactRef));
            for (int i = 1; i < copy.size(); i++) {
                if (copy.get(i - 1).exactRef().equals(copy.get(i).exactRef())) {
                    throw new IllegalArgumentException("duplicate evidence reference");
                }
            }
            return List.copyOf(copy);
        }
    }
}
