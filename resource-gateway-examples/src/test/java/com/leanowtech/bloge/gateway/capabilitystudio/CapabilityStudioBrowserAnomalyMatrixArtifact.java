package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic test-side producer for the fixed 126-cell browser anomaly matrix.
 *
 * <p>This class deliberately emits a test artifact only. It does not infer a PASS from a
 * status string: a PASS must carry the browser observations and the real trigger facts that
 * make the corresponding anomaly reproducible.</p>
 */
final class CapabilityStudioBrowserAnomalyMatrixArtifact {
    static final String SCHEMA_VERSION = "bloge.capabilityStudioBrowserAnomalyMatrixResult.v1";
    static final String CONTRACT_ID = "S0-AC-01";
    static final int EXPECTED_OBLIGATION_COUNT = 126;
    static final int ERROR_OBLIGATION_COUNT = 60;
    static final int OFFLINE_OBLIGATION_COUNT = 60;
    static final int CONFLICT_OBLIGATION_COUNT = 6;

    static final List<String> GOLDEN_PATHS = List.of(
            "GP-01", "GP-02", "GP-03", "GP-04", "GP-05",
            "GP-06", "GP-07", "GP-08", "GP-09", "GP-10");
    static final List<String> LOCALES = List.of("zh-CN", "en-US");
    static final List<Viewport> VIEWPORTS = List.of(
            new Viewport(1440, 900),
            new Viewport(1024, 768),
            new Viewport(390, 844));
    private static final Set<String> VALID_PROFILES = Set.of("ERROR", "OFFLINE", "CONFLICT");
    private static final Set<String> VALID_STATUSES = Set.of("PASS", "FAIL", "NOT_RUN");
    private static final Set<String> VALID_MECHANISMS = Set.of(
            "CDP_FETCH_FULFILL", "CDP_FETCH_FAIL", "REAL_HTTP_STALE_REVISION");
    private static final Set<String> VALID_FAILURE_CLASSES = Set.of(
            "HTTP_4XX", "HTTP_5XX", "TRANSPORT_FAILURE", "REVISION_CONFLICT");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObligationDescriptor> OBLIGATION_DESCRIPTORS = obligationDescriptors();
    static final List<String> OBLIGATION_IDS = OBLIGATION_DESCRIPTORS.stream()
            .map(ObligationDescriptor::id)
            .toList();

    record Candidate(
            String buildRef,
            String revision,
            String artifactFingerprint,
            String sourceCommit,
            String sourceTreeStatus) {
        Candidate {
            requireSafeRef(buildRef, "candidate.buildRef");
            requireSafeRef(revision, "candidate.revision");
            requireFingerprint(artifactFingerprint, "candidate.artifactFingerprint");
            if (sourceCommit == null || !sourceCommit.matches("^[a-f0-9]{7,64}$")) {
                throw new IllegalArgumentException("candidate.sourceCommit must be lowercase hex");
            }
            if (!Set.of("CLEAN", "DIRTY").contains(sourceTreeStatus)) {
                throw new IllegalArgumentException("candidate.sourceTreeStatus is invalid");
            }
        }
    }

    record Baseline(String id, int revision, String fingerprint) {
        Baseline {
            requireSafeRef(id, "baselineRef.id");
            requirePositive(revision, "baselineRef.revision");
            requireFingerprint(fingerprint, "baselineRef.fingerprint");
        }
    }

    record Environment(
            String environmentFingerprint,
            String profile,
            String browserName,
            String browserVersion,
            String driverVersion,
            String axeVersion) {
        Environment {
            requireFingerprint(environmentFingerprint, "environment.environmentFingerprint");
            requireSafeRef(profile, "environment.profile");
            requireSafeRef(browserName, "environment.browserName");
            requireSafeRef(browserVersion, "environment.browserVersion");
            requireSafeRef(driverVersion, "environment.driverVersion");
            requireSafeRef(axeVersion, "environment.axeVersion");
        }
    }

    record BaseMatrixRef(String exactRef, String fingerprint, String resultStatus) {
        BaseMatrixRef {
            requireSafeRef(exactRef, "baseMatrixRef.exactRef");
            requireFingerprint(fingerprint, "baseMatrixRef.fingerprint");
            if (!"COMPLETE".equals(resultStatus)) {
                throw new IllegalArgumentException("baseMatrixRef.resultStatus must be COMPLETE");
            }
        }

        BaseMatrixRef(String exactRef, String fingerprint) {
            this(exactRef, fingerprint, "COMPLETE");
        }
    }

    record Viewport(int width, int height) {
        Viewport {
            if (!isFixed(width, height)) {
                throw new IllegalArgumentException("viewport is outside the fixed anomaly matrix");
            }
        }

        String coordinate() {
            return width + "x" + height;
        }

        private static boolean isFixed(int width, int height) {
            return width == 1440 && height == 900
                    || width == 1024 && height == 768
                    || width == 390 && height == 844;
        }
    }

    record Trigger(
            String mechanism,
            String targetRoute,
            String observedFailureClass,
            Integer observedHttpStatus,
            boolean triggered) {
        Trigger {
            if (!VALID_MECHANISMS.contains(mechanism)) {
                throw new IllegalArgumentException("trigger.mechanism is invalid");
            }
            if (targetRoute == null || !targetRoute.matches("^/?[A-Za-z0-9][A-Za-z0-9._:/@+-]*$")) {
                throw new IllegalArgumentException("trigger.targetRoute is invalid");
            }
            if (!VALID_FAILURE_CLASSES.contains(observedFailureClass)) {
                throw new IllegalArgumentException("trigger.observedFailureClass is invalid");
            }
            if (observedHttpStatus != null && (observedHttpStatus < 400 || observedHttpStatus > 599)) {
                throw new IllegalArgumentException("trigger.observedHttpStatus must be null or 400..599");
            }
        }
    }

    record Axe(int serious, int critical) {
        Axe {
            requireNonNegative(serious, "browserObservations.axe.serious");
            requireNonNegative(critical, "browserObservations.axe.critical");
        }
    }

    record KeyboardPath(boolean completed, int steps, int focusLosses) {
        KeyboardPath {
            requireNonNegative(steps, "browserObservations.keyboardPath.steps");
            requireNonNegative(focusLosses, "browserObservations.keyboardPath.focusLosses");
        }
    }

    record BrowserObservations(
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
        BrowserObservations {
            if (axe == null || keyboardPath == null) {
                throw new IllegalArgumentException("browser observations require axe and keyboard path");
            }
            requireNonNegative(technicalIdCount, "browserObservations.technicalIdCount");
            requireNonNegative(rawJsonCount, "browserObservations.rawJsonCount");
            requireNonNegative(p0Count, "browserObservations.p0Count");
            requireNonNegative(p1Count, "browserObservations.p1Count");
        }

        static BrowserObservations notRun() {
            return new BrowserObservations(
                    null, false, new Axe(0, 0), 0, 0,
                    new KeyboardPath(false, 0, 0), false, false, false, false, false,
                    false, false, false, false, false, false, 0, 0);
        }
    }

    record EvidenceRef(String exactRef, String fingerprint) {
        EvidenceRef {
            requireSafeRef(exactRef, "evidenceRef.exactRef");
            requireFingerprint(fingerprint, "evidenceRef.fingerprint");
        }
    }

    record Observation(
            String obligationId,
            String stateProfile,
            String goldenPathId,
            String locale,
            Viewport viewport,
            String status,
            Trigger trigger,
            String expectedUiState,
            String expectedRecoveryAction,
            BrowserObservations browserObservations,
            List<EvidenceRef> evidenceRefs) {
        Observation {
            requireSafeRef(obligationId, "obligation.obligationId");
            if (!VALID_PROFILES.contains(stateProfile)) {
                throw new IllegalArgumentException("obligation.stateProfile is invalid");
            }
            if (!GOLDEN_PATHS.contains(goldenPathId)) {
                throw new IllegalArgumentException("obligation.goldenPathId is invalid");
            }
            if (!LOCALES.contains(locale)) {
                throw new IllegalArgumentException("obligation.locale is invalid");
            }
            if (viewport == null || !VIEWPORTS.contains(viewport)) {
                throw new IllegalArgumentException("obligation.viewport is outside the fixed matrix");
            }
            if (!VALID_STATUSES.contains(status)) {
                throw new IllegalArgumentException("obligation.status is invalid");
            }
            if (trigger == null || browserObservations == null) {
                throw new IllegalArgumentException("obligation trigger and browser observations are required");
            }
            requireSafeText(expectedUiState, "obligation.expectedUiState");
            requireSafeText(expectedRecoveryAction, "obligation.expectedRecoveryAction");
            if (!CapabilityStudioBrowserAnomalyMatrixArtifact.expectedUiState(stateProfile).equals(expectedUiState)
                    || !CapabilityStudioBrowserAnomalyMatrixArtifact.expectedRecoveryAction(stateProfile)
                    .equals(expectedRecoveryAction)) {
                throw new IllegalArgumentException("obligation expectation does not match state profile");
            }
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            validateTargetRoute(status, stateProfile, goldenPathId, trigger);
            if ("NOT_RUN".equals(status) && (!evidenceRefs.isEmpty()
                    || trigger.triggered()
                    || browserObservations.actualViewport() != null
                    || browserObservations.pageHorizontalOverflow()
                    || browserObservations.axe().serious() != 0
                    || browserObservations.axe().critical() != 0
                    || browserObservations.technicalIdCount() != 0
                    || browserObservations.rawJsonCount() != 0
                    || browserObservations.keyboardPath().completed()
                    || browserObservations.keyboardPath().steps() != 0
                    || browserObservations.keyboardPath().focusLosses() != 0
                    || browserObservations.errorVisible()
                    || browserObservations.businessSafeExplanation()
                    || browserObservations.recoveryActionVisible()
                    || browserObservations.recoveryAttempted()
                    || browserObservations.recoveredToReady()
                    || browserObservations.localDraftRetained()
                    || browserObservations.serverRevisionPreserved()
                    || browserObservations.staleGreenPreflightAbsent()
                    || browserObservations.staleErrorAbsent()
                    || browserObservations.staleEvidenceAbsent()
                    || browserObservations.staleSuccessAbsent()
                    || browserObservations.p0Count() != 0
                    || browserObservations.p1Count() != 0)) {
                throw new IllegalArgumentException("NOT_RUN obligation cannot carry observations or evidence");
            }
            if ("PASS".equals(status)) {
                validatePass(stateProfile, viewport, trigger, browserObservations, evidenceRefs);
            }
            if ("FAIL".equals(status)) {
                validateFail(stateProfile, trigger, browserObservations, evidenceRefs);
            }
        }

        static Observation notRun(String obligationId, String profile, String goldenPathId,
                                  String locale, Viewport viewport) {
            return new Observation(
                    obligationId, profile, goldenPathId, locale, viewport, "NOT_RUN",
                    defaultTrigger(profile, goldenPathId),
                    CapabilityStudioBrowserAnomalyMatrixArtifact.expectedUiState(profile),
                    CapabilityStudioBrowserAnomalyMatrixArtifact.expectedRecoveryAction(profile),
                    BrowserObservations.notRun(), List.of());
        }
    }

    private final String resultId;
    private final int revision;
    private final String contractRevision;
    private final Candidate candidate;
    private final Baseline baseline;
    private final Environment environment;
    private final OffsetDateTime startedAt;
    private final BaseMatrixRef baseMatrixRef;
    private final Map<String, Observation> observations = new LinkedHashMap<>();

    CapabilityStudioBrowserAnomalyMatrixArtifact(
            String resultId,
            int revision,
            String contractRevision,
            Candidate candidate,
            Baseline baseline,
            Environment environment,
            OffsetDateTime startedAt,
            BaseMatrixRef baseMatrixRef) {
        if (resultId == null || !resultId.matches("^BAMR-[A-Za-z0-9._-]{1,120}$")) {
            throw new IllegalArgumentException("resultId must use BAMR- prefix");
        }
        requirePositive(revision, "revision");
        requireSafeRef(contractRevision, "contractRevision");
        if (candidate == null || baseline == null || environment == null || startedAt == null || baseMatrixRef == null) {
            throw new IllegalArgumentException("artifact metadata is required");
        }
        this.resultId = resultId;
        this.revision = revision;
        this.contractRevision = contractRevision;
        this.candidate = candidate;
        this.baseline = baseline;
        this.environment = environment;
        this.startedAt = startedAt;
        this.baseMatrixRef = baseMatrixRef;
    }

    void record(Observation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation is required");
        }
        ObligationDescriptor descriptor = descriptorFor(observation.obligationId());
        if (descriptor == null) {
            throw new IllegalArgumentException("unknown browser anomaly obligation: " + observation.obligationId());
        }
        ObligationKey expected = obligationKey(observation.stateProfile(), observation.goldenPathId(),
                observation.locale(), observation.viewport());
        if (!descriptor.id().equals(observation.obligationId()) || !expected.id().equals(observation.obligationId())) {
            throw new IllegalArgumentException("obligation identity does not match profile, path, locale, and viewport");
        }
        if (observations.putIfAbsent(observation.obligationId(), observation) != null) {
            throw new IllegalArgumentException("duplicate browser anomaly obligation: " + observation.obligationId());
        }
    }

    ObjectNode build(OffsetDateTime completedAt) {
        if (completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("executionWindow.completedAt must not precede startedAt");
        }
        List<Observation> completeObligations = OBLIGATION_IDS.stream()
                .map(id -> observations.getOrDefault(id, notRunById(id)))
                .toList();

        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("resultId", resultId);
        result.put("revision", revision);
        result.put("contractId", CONTRACT_ID);
        result.put("contractRevision", contractRevision);
        writeCandidate(result.putObject("candidate"), candidate);
        writeBaseline(result.putObject("baselineRef"), baseline);
        writeEnvironment(result.putObject("environment"), environment);
        result.putObject("executionWindow")
                .put("startedAt", startedAt.toString())
                .put("completedAt", completedAt.toString());
        writeBaseMatrixRef(result.putObject("baseMatrixRef"), baseMatrixRef);

        ArrayNode obligations = result.putArray("obligations");
        completeObligations.forEach(observation -> writeObservation(obligations.addObject(), observation));

        int passed = count(completeObligations, "PASS");
        int failed = count(completeObligations, "FAIL");
        int notRun = count(completeObligations, "NOT_RUN");
        ObjectNode summary = result.putObject("summary");
        summary.put("expected", EXPECTED_OBLIGATION_COUNT);
        summary.put("actual", completeObligations.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("notRun", notRun);
        summary.put("errorExpected", ERROR_OBLIGATION_COUNT);
        summary.put("offlineExpected", OFFLINE_OBLIGATION_COUNT);
        summary.put("conflictExpected", CONFLICT_OBLIGATION_COUNT);

        boolean hasFailed = failed > 0 || !"CLEAN".equals(candidate.sourceTreeStatus())
                || !"COMPLETE".equals(baseMatrixRef.resultStatus());
        String resultStatus = hasFailed ? "FAILED"
                : passed == EXPECTED_OBLIGATION_COUNT ? "COMPLETE" : "NOT_RUN";
        result.put("resultStatus", resultStatus);
        writeDiagnostics(result.putArray("diagnostics"), candidate, baseMatrixRef, failed, notRun, resultStatus);

        ObjectNode material = result.deepCopy();
        material.remove("evidenceClosureFingerprint");
        result.put("evidenceClosureFingerprint", fingerprint(material));
        return result;
    }

    static String fingerprint(JsonNode value) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical(value));
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalArgumentException("browser anomaly material cannot be fingerprinted", failure);
        }
    }

    private static void validatePass(String stateProfile, Viewport viewport, Trigger trigger,
                                     BrowserObservations browser, List<EvidenceRef> evidenceRefs) {
        if (!triggerMatchesProfile(stateProfile, trigger)) {
            throw new IllegalArgumentException("PASS obligation has a profile-mismatched trigger");
        }
        if ("CONFLICT".equals(stateProfile)
                && (!browser.localDraftRetained()
                || !browser.serverRevisionPreserved()
                || !browser.staleGreenPreflightAbsent())) {
            throw new IllegalArgumentException("PASS conflict obligation does not preserve revision facts");
        }
        if (!trigger.triggered()
                || !browser.errorVisible()
                || !browser.businessSafeExplanation()
                || !browser.recoveryActionVisible()
                || !browser.recoveryAttempted()
                || !browser.recoveredToReady()
                || !browser.staleGreenPreflightAbsent()
                || !browser.staleErrorAbsent()
                || !browser.staleEvidenceAbsent()
                || !browser.staleSuccessAbsent()
                || !viewport.equals(browser.actualViewport())
                || browser.pageHorizontalOverflow()
                || browser.axe().serious() != 0
                || browser.axe().critical() != 0
                || browser.technicalIdCount() != 0
                || browser.rawJsonCount() != 0
                || !browser.keyboardPath().completed()
                || browser.keyboardPath().steps() < 1
                || browser.keyboardPath().focusLosses() != 0
                || browser.p0Count() != 0
                || browser.p1Count() != 0
                || evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("PASS obligation does not satisfy browser acceptance gates");
        }
    }

    private static void validateFail(String stateProfile, Trigger trigger,
                                     BrowserObservations browser, List<EvidenceRef> evidenceRefs) {
        if (!triggerMatchesProfile(stateProfile, trigger)) {
            throw new IllegalArgumentException("FAIL obligation has a profile-mismatched trigger");
        }
        if (!trigger.triggered() || browser.actualViewport() == null || evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "FAIL obligation requires a triggered attempt, actual viewport, and evidence");
        }
    }

    private static boolean triggerMatchesProfile(String stateProfile, Trigger trigger) {
        return switch (stateProfile) {
            case "ERROR" -> "CDP_FETCH_FULFILL".equals(trigger.mechanism())
                    && Set.of("HTTP_4XX", "HTTP_5XX").contains(trigger.observedFailureClass())
                    && trigger.observedHttpStatus() != null
                    && trigger.observedHttpStatus() >= 400 && trigger.observedHttpStatus() <= 599;
            case "OFFLINE" -> "CDP_FETCH_FAIL".equals(trigger.mechanism())
                    && "TRANSPORT_FAILURE".equals(trigger.observedFailureClass())
                    && trigger.observedHttpStatus() == null;
            case "CONFLICT" -> "REAL_HTTP_STALE_REVISION".equals(trigger.mechanism())
                    && "REVISION_CONFLICT".equals(trigger.observedFailureClass())
                    && Integer.valueOf(409).equals(trigger.observedHttpStatus());
            default -> false;
        };
    }

    private static void validateTargetRoute(String status, String stateProfile,
                                            String goldenPathId, Trigger trigger) {
        String expected = expectedTargetRoute(stateProfile, goldenPathId);
        if (!expected.equals(trigger.targetRoute())) {
            throw new IllegalArgumentException(status + " obligation targetRoute drift: expected " + expected);
        }
    }

    private static List<ObligationDescriptor> obligationDescriptors() {
        List<ObligationDescriptor> descriptors = new ArrayList<>(EXPECTED_OBLIGATION_COUNT);
        for (String profile : List.of("ERROR", "OFFLINE")) {
            for (String goldenPath : GOLDEN_PATHS) {
                for (String locale : LOCALES) {
                    for (Viewport viewport : VIEWPORTS) {
                        descriptors.add(descriptor(profile, goldenPath, locale, viewport));
                    }
                }
            }
        }
        for (String locale : LOCALES) {
            for (Viewport viewport : VIEWPORTS) {
                descriptors.add(descriptor("CONFLICT", "GP-04", locale, viewport));
            }
        }
        return List.copyOf(descriptors);
    }

    private static Observation notRunById(String obligationId) {
        ObligationDescriptor descriptor = descriptorFor(obligationId);
        if (descriptor == null) {
            throw new IllegalArgumentException("unknown browser anomaly obligation: " + obligationId);
        }
        return Observation.notRun(obligationId, descriptor.profile(), descriptor.goldenPathId(),
                descriptor.locale(), descriptor.viewport());
    }

    private static ObligationKey obligationKey(String profile, String goldenPath, String locale, Viewport viewport) {
        if (!VALID_PROFILES.contains(profile) || !GOLDEN_PATHS.contains(goldenPath)
                || !LOCALES.contains(locale) || viewport == null || !VIEWPORTS.contains(viewport)) {
            throw new IllegalArgumentException("unknown browser anomaly obligation combination");
        }
        if ("CONFLICT".equals(profile) && !"GP-04".equals(goldenPath)) {
            throw new IllegalArgumentException("CONFLICT obligations are fixed to GP-04");
        }
        return new ObligationKey(descriptor(profile, goldenPath, locale, viewport).id());
    }

    private record ObligationKey(String id) {
    }

    private record ObligationDescriptor(
            String id, String profile, String goldenPathId, String locale, Viewport viewport) {
    }

    private static ObligationDescriptor descriptor(String profile, String goldenPath, String locale,
                                                    Viewport viewport) {
        return new ObligationDescriptor(
                "BAM-" + profile + "-" + goldenPath + "-" + locale + "-" + viewport.coordinate(),
                profile, goldenPath, locale, viewport);
    }

    private static ObligationDescriptor descriptorFor(String obligationId) {
        return OBLIGATION_DESCRIPTORS.stream()
                .filter(descriptor -> descriptor.id().equals(obligationId))
                .findFirst()
                .orElse(null);
    }

    private static String expectedUiState(String profile) {
        return profile + "_FEEDBACK";
    }

    private static String expectedRecoveryAction(String profile) {
        return "CONFLICT".equals(profile) ? "RETRY_OR_MERGE" : "RETRY";
    }

    private static Trigger defaultTrigger(String profile, String goldenPathId) {
        String targetRoute = expectedTargetRoute(profile, goldenPathId);
        return switch (profile) {
            case "ERROR" -> new Trigger("CDP_FETCH_FULFILL", targetRoute, "HTTP_5XX", null, false);
            case "OFFLINE" -> new Trigger("CDP_FETCH_FAIL", targetRoute, "TRANSPORT_FAILURE", null, false);
            case "CONFLICT" -> new Trigger(
                    "REAL_HTTP_STALE_REVISION", targetRoute, "REVISION_CONFLICT", null, false);
            default -> throw new IllegalArgumentException("unknown anomaly profile: " + profile);
        };
    }

    private static String expectedTargetRoute(String profile, String goldenPathId) {
        if ("CONFLICT".equals(profile)) {
            if (!"GP-04".equals(goldenPathId)) {
                throw new IllegalArgumentException("CONFLICT targetRoute is fixed to GP-04");
            }
            return "/api/capability-studio/tutorial-branch/behaviors/compensation-history";
        }
        return switch (goldenPathId) {
            case "GP-01", "GP-02", "GP-07" -> "/api/capability-studio/demo-pack";
            case "GP-03" -> "/api/capability-studio/scenario-dataset";
            case "GP-04" -> "/api/capability-studio/tutorial-branch/behaviors/compensation-history";
            case "GP-05", "GP-06" -> "/api/capability-studio/feature-rehearsal";
            case "GP-08" -> "/api/capability-studio/governed-baseline";
            case "GP-09" -> "/api/capability-studio/scenario-dataset/quality-impact";
            case "GP-10" -> "/api/capability-studio/governed-runs/runId/evidence";
            default -> throw new IllegalArgumentException("unknown golden path targetRoute: " + goldenPathId);
        };
    }

    private static int count(List<Observation> values, String status) {
        return (int) values.stream().filter(value -> status.equals(value.status())).count();
    }

    private static void writeCandidate(ObjectNode target, Candidate value) {
        target.put("buildRef", value.buildRef()).put("revision", value.revision())
                .put("artifactFingerprint", value.artifactFingerprint())
                .put("sourceCommit", value.sourceCommit()).put("sourceTreeStatus", value.sourceTreeStatus());
    }

    private static void writeBaseline(ObjectNode target, Baseline value) {
        target.put("id", value.id()).put("revision", value.revision()).put("fingerprint", value.fingerprint());
    }

    private static void writeEnvironment(ObjectNode target, Environment value) {
        target.put("environmentFingerprint", value.environmentFingerprint()).put("profile", value.profile())
                .put("browserName", value.browserName()).put("browserVersion", value.browserVersion())
                .put("driverVersion", value.driverVersion()).put("axeVersion", value.axeVersion());
    }

    private static void writeBaseMatrixRef(ObjectNode target, BaseMatrixRef value) {
        target.put("exactRef", value.exactRef()).put("fingerprint", value.fingerprint())
                .put("resultStatus", value.resultStatus());
    }

    private static void writeObservation(ObjectNode target, Observation value) {
        target.put("obligationId", value.obligationId()).put("stateProfile", value.stateProfile())
                .put("goldenPathId", value.goldenPathId()).put("locale", value.locale());
        writeViewport(target.putObject("viewport"), value.viewport());
        target.put("status", value.status());
        writeTrigger(target.putObject("trigger"), value.trigger());
        target.put("expectedUiState", value.expectedUiState())
                .put("expectedRecoveryAction", value.expectedRecoveryAction());
        writeBrowserObservations(target.putObject("browserObservations"), value.browserObservations());
        ArrayNode refs = target.putArray("evidenceRefs");
        value.evidenceRefs().stream().sorted(Comparator.comparing(EvidenceRef::exactRef)).forEach(ref ->
                refs.addObject().put("exactRef", ref.exactRef()).put("fingerprint", ref.fingerprint()));
    }

    private static void writeTrigger(ObjectNode target, Trigger value) {
        target.put("mechanism", value.mechanism()).put("targetRoute", value.targetRoute())
                .put("observedFailureClass", value.observedFailureClass());
        if (value.observedHttpStatus() == null) {
            target.putNull("observedHttpStatus");
        } else {
            target.put("observedHttpStatus", value.observedHttpStatus());
        }
        target.put("triggered", value.triggered());
    }

    private static void writeBrowserObservations(ObjectNode target, BrowserObservations value) {
        if (value.actualViewport() == null) {
            target.putNull("actualViewport");
        } else {
            writeViewport(target.putObject("actualViewport"), value.actualViewport());
        }
        target.put("pageHorizontalOverflow", value.pageHorizontalOverflow());
        target.putObject("axe").put("serious", value.axe().serious()).put("critical", value.axe().critical());
        target.put("technicalIdCount", value.technicalIdCount()).put("rawJsonCount", value.rawJsonCount());
        target.putObject("keyboardPath").put("completed", value.keyboardPath().completed())
                .put("steps", value.keyboardPath().steps()).put("focusLosses", value.keyboardPath().focusLosses());
        target.put("errorVisible", value.errorVisible()).put("businessSafeExplanation", value.businessSafeExplanation())
                .put("recoveryActionVisible", value.recoveryActionVisible()).put("recoveryAttempted", value.recoveryAttempted())
                .put("recoveredToReady", value.recoveredToReady()).put("localDraftRetained", value.localDraftRetained())
                .put("serverRevisionPreserved", value.serverRevisionPreserved())
                .put("staleGreenPreflightAbsent", value.staleGreenPreflightAbsent())
                .put("staleErrorAbsent", value.staleErrorAbsent()).put("staleEvidenceAbsent", value.staleEvidenceAbsent())
                .put("staleSuccessAbsent", value.staleSuccessAbsent()).put("p0Count", value.p0Count())
                .put("p1Count", value.p1Count());
    }

    private static void writeViewport(ObjectNode target, Viewport value) {
        target.put("width", value.width()).put("height", value.height());
    }

    private static void writeDiagnostics(ArrayNode target, Candidate candidate, BaseMatrixRef base,
                                         int failed, int notRun, String resultStatus) {
        Set<String> codes = new LinkedHashSet<>();
        if (!"CLEAN".equals(candidate.sourceTreeStatus())) {
            codes.add("CANDIDATE_SOURCE_TREE_DIRTY");
        }
        if (!"COMPLETE".equals(base.resultStatus())) {
            codes.add("BASE_MATRIX_NOT_COMPLETE");
        }
        if (failed > 0) {
            codes.add("BROWSER_ANOMALY_FAILURE");
        }
        if (notRun > 0 && "NOT_RUN".equals(resultStatus)) {
            codes.add("BROWSER_ANOMALY_NOT_RUN");
        }
        codes.forEach(code -> target.addObject().put("code", code));
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static void requireSafeRef(String value, String field) {
        if (value == null || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:/@+-]*$")) {
            throw new IllegalArgumentException(field + " is not a safe reference");
        }
    }

    private static void requireSafeText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(field + " must be non-blank and <= 512 characters");
        }
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("^sha256:[A-Fa-f0-9]{64}$")) {
            throw new IllegalArgumentException(field + " is not a sha256 fingerprint");
        }
    }

    private static void requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }
}
