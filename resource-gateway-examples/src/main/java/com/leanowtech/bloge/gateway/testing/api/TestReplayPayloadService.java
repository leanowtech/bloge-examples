package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadGovernanceException;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadRedactionManifest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadSanitizer;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Captures and resolves side-effect-free node outputs from signed governed run payloads.
 *
 * <p>This service is the trust boundary between visual run history and the test replay vault. It
 * never accepts caller-supplied replay values. Source run and detached payload fingerprints must
 * match, lifecycle signatures and workload scope are checked, one exact successful attempt is
 * selected, and the server sanitizer runs again before the value enters the isolated test store.</p>
 */
public final class TestReplayPayloadService {

    /** Dedicated workload purpose required for capture and payload reads. */
    public static final String AUTHORIZED_PURPOSE = "TEST_REPLAY";

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final VisualGraphRunRepository sourceRuns;
    private final ReplayPayloadRepository payloads;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;
    private final Duration maximumRetention;

    /**
     * Creates the trust boundary between governed visual run history and the isolated test vault.
     *
     * @param sourceRuns signed visual run evidence and detached payload owner
     * @param payloads isolated test replay vault
     * @param securityEvents fail-closed test security audit sink
     * @param objectMapper canonical fingerprint mapper
     * @param maximumRetention maximum lifetime of a captured test payload
     */
    public TestReplayPayloadService(VisualGraphRunRepository sourceRuns,
                                    ReplayPayloadRepository payloads,
                                    TestSecurityEventRepository securityEvents,
                                    ObjectMapper objectMapper,
                                    Duration maximumRetention) {
        this.sourceRuns = Objects.requireNonNull(sourceRuns, "sourceRuns");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.maximumRetention = maximumRetention == null || maximumRetention.isZero()
                || maximumRetention.isNegative() ? Duration.ofDays(30) : maximumRetention;
    }

    /**
     * Captures one exact successful node attempt into an immutable replay payload revision.
     *
     * @param replayPayloadId path-bound destination id
     * @param request exact source and retention command
     * @param identity verified workload identity with {@code TEST_REPLAY} purpose
     * @return newly stored or content-equivalent existing payload
     */
    public StoredReplayPayload capture(String replayPayloadId,
                                       ReplayPayloadCaptureRequest request,
                                       IntegrationRequestContext identity) {
        requireReplayIdentity(identity);
        validateCaptureRequest(replayPayloadId, request, identity);
        Instant now = payloads.currentTime();
        VisualGraphRunRecord run = sourceRun(request.source().runId(), identity);
        requireSignedRun(run, request.source(), identity);
        VisualRunPayloadRepository.Access access = governedPayload(run, identity, now);
        VisualRunPayloadStatus sourceStatus = access.status();
        requireSourceFingerprint(request.source(), sourceStatus, access, identity);
        VisualNodeExecutionAttempt attempt = exactSuccessfulAttempt(
                access.payload().nodeAttempts(), request.source(), identity);
        VisualPayloadSanitizer.Capture sanitized = VisualPayloadSanitizer.capture(
                Map.of(), attempt.output(), Map.of());
        if (sourceStatus.descriptor().expiresAt().isBefore(request.expiresAt())
                || now.plus(maximumRetention).isBefore(request.expiresAt())) {
            throw badRequest(identity, "RG.TEST.REPLAY_RETENTION_INVALID",
                    "Replay payload expiry cannot exceed source retention or server policy.",
                    Map.of("maximumRetentionSeconds", maximumRetention.toSeconds(),
                            "sourceExpiresAt", sourceStatus.descriptor().expiresAt()));
        }
        if (access.payload().redaction().truncated() || sanitized.redaction().truncated()) {
            throw conflict(identity, "RG.TEST.REPLAY_SOURCE_TRUNCATED",
                    "Truncated source data cannot become an executable replay payload.", Map.of());
        }

        String classification = request.classification();
        if (!CLASSIFICATIONS.contains(sourceStatus.descriptor().classification())) {
            throw conflict(identity, "RG.TEST.REPLAY_SOURCE_CLASSIFICATION_INVALID",
                    "Source payload classification is not recognized by the test replay policy.", Map.of());
        }
        requireNoClassificationDowngrade(classification,
                sourceStatus.descriptor().classification(), identity);
        requireClearance(classification, identity);
        boolean signedPublication = VisualGraphRunRecord.SOURCE_PUBLICATION.equals(run.sourceKind())
                && sourceRuns.evidenceSigner().verify(
                run.evidenceSeal(), run.evidenceMaterialFingerprint()).valid();
        List<String> gaps = new ArrayList<>();
        if (!VisualGraphRunRecord.SOURCE_PUBLICATION.equals(run.sourceKind())) {
            gaps.add("SOURCE_NOT_IMMUTABLE_PUBLICATION_RUN");
        }
        if (!signedPublication) {
            gaps.add("SOURCE_NOT_CERTIFIABLE");
        }
        ReplayPayloadDescriptor.Source source = new ReplayPayloadDescriptor.Source(
                "GOVERNED_RUN_NODE_ATTEMPT", run.runId(), request.source().nodeId(),
                request.source().attempt(), run.evidenceMaterialFingerprint(),
                access.payload().payloadFingerprint(), run.environment());
        ReplayPayloadDescriptor.Redaction redaction = redaction(
                access.payload().redaction(), sanitized.redaction());
        ReplayPayloadDescriptor draft = new ReplayPayloadDescriptor("", replayPayloadId,
                request.revision(), "", classification, source, redaction, now, request.expiresAt(),
                signedPublication, gaps);
        String fingerprint = ProtocolFingerprint.of(objectMapper,
                Map.of("descriptor", draft, "value",
                        sanitized.output() == null ? objectMapper.nullNode() : sanitized.output()));
        ReplayPayloadDescriptor descriptor = new ReplayPayloadDescriptor("", replayPayloadId,
                request.revision(), fingerprint, classification, source, redaction, now,
                request.expiresAt(), signedPublication, gaps);
        StoredReplayPayload stored = new StoredReplayPayload("", identity.tenantId(),
                identity.environmentId(), descriptor, StoredReplayPayload.AVAILABLE, true,
                sanitized.output(), now, identity.actorId());
        try {
            return payloads.create(stored);
        } catch (ReplayPayloadConflictException immutableConflict) {
            throw conflict(identity, "RG.TEST.REPLAY_REVISION_CONFLICT",
                    immutableConflict.getMessage(), Map.of());
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.REPLAY_STORE_UNAVAILABLE",
                    "The isolated replay payload vault is unavailable.");
        }
    }

    /**
     * Reads one exact replay revision after lifecycle, scope, purpose, and clearance checks.
     *
     * @param replayPayloadId stable payload id
     * @param revision exact revision
     * @param identity verified replay workload identity
     * @return available payload; expired values produce a gone response with no value
     */
    public StoredReplayPayload find(String replayPayloadId, long revision,
                                    IntegrationRequestContext identity) {
        requireReplayIdentity(identity);
        StoredReplayPayload stored;
        try {
            stored = payloads.find(identity.tenantId(), identity.environmentId(),
                            normalized(replayPayloadId), revision)
                    .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.TEST.REPLAY_PAYLOAD_NOT_FOUND",
                            "Replay payload was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.REPLAY_STORE_UNAVAILABLE",
                    "The isolated replay payload vault is unavailable.");
        }
        requireClearance(stored.descriptor().classification(), identity);
        if (!stored.readable()) {
            throw new IntegrationProblemException(IntegrationProblem.gone(
                    "RG.TEST.REPLAY_PAYLOAD_UNAVAILABLE",
                    "Replay payload value is no longer available.", identity.correlationId(),
                    Map.of("state", stored.state(),
                            "expiresAt", stored.descriptor().expiresAt())));
        }
        return stored;
    }

    private void validateCaptureRequest(String pathId, ReplayPayloadCaptureRequest request,
                                        IntegrationRequestContext identity) {
        if (request == null || !ReplayPayloadCaptureRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || !IDENTIFIER.matcher(normalized(pathId)).matches() || request.revision() <= 0
                || request.source() == null || request.source().runId().isBlank()
                || request.source().nodeId().isBlank() || request.source().attempt() <= 0
                || !FINGERPRINT.matcher(request.source().runEvidenceFingerprint()).matches()
                || !FINGERPRINT.matcher(request.source().payloadFingerprint()).matches()
                || request.expiresAt() == null || !request.expiresAt().isAfter(payloads.currentTime())
                || !CLASSIFICATIONS.contains(request.classification())) {
            throw badRequest(identity, "RG.TEST.REPLAY_CAPTURE_REQUEST_INVALID",
                    "Capture requires an exact source fingerprint, node attempt, classification, and future expiry.",
                    Map.of());
        }
    }

    private VisualGraphRunRecord sourceRun(String runId, IntegrationRequestContext identity) {
        VisualGraphRunRecord run;
        try {
            run = sourceRuns.find(runId).orElse(null);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.REPLAY_SOURCE_UNAVAILABLE",
                    "Signed source run history is unavailable.");
        }
        if (run == null || !identity.tenantId().equals(run.tenantId())
                || !identity.environmentId().equals(run.environment())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.TEST.REPLAY_SOURCE_NOT_FOUND",
                    "Source run was not found in the authorized scope.",
                    identity.correlationId(), Map.of()));
        }
        if (!run.success() || VisualGraphRunRecord.SOURCE_RECORDED_REPLAY.equals(run.sourceKind())) {
            throw conflict(identity, "RG.TEST.REPLAY_SOURCE_INVALID",
                    "Only a successful non-replay run can supply a replay payload.", Map.of());
        }
        return run;
    }

    private void requireSignedRun(VisualGraphRunRecord run,
                                  ReplayPayloadCaptureRequest.Source source,
                                  IntegrationRequestContext identity) {
        if (!source.runEvidenceFingerprint().equals(run.evidenceMaterialFingerprint())) {
            throw conflict(identity, "RG.TEST.REPLAY_SOURCE_FINGERPRINT_CONFLICT",
                    "Source run evidence changed after selection.", Map.of());
        }
        if (!sourceRuns.evidenceSigner().verify(
                run.evidenceSeal(), run.evidenceMaterialFingerprint()).valid()) {
            securityEvent(identity, "REPLAY_SOURCE_SIGNATURE_INVALID", "REJECTED",
                    "RG.TEST.REPLAY_SOURCE_SIGNATURE_INVALID", Map.of("runId", run.runId()));
            throw conflict(identity, "RG.TEST.REPLAY_SOURCE_SIGNATURE_INVALID",
                    "Source run evidence signature is unavailable or invalid.", Map.of());
        }
    }

    private VisualRunPayloadRepository.Access governedPayload(VisualGraphRunRecord run,
                                                               IntegrationRequestContext identity,
                                                               Instant observedAt) {
        VisualRunPayloadRepository repository = sourceRuns.payloadRepository();
        if (repository == null) {
            throw unavailable(identity, "RG.TEST.REPLAY_SOURCE_GOVERNANCE_UNAVAILABLE",
                    "The governed source payload vault is unavailable.");
        }
        try {
            VisualRunPayloadRepository.Access access = repository.access(run.runId(), observedAt);
            VisualRunPayloadStatus status = access.status();
            if (status == null || !run.tenantId().equals(status.tenantId())
                    || !run.environment().equals(status.environment())) {
                throw new VisualPayloadGovernanceException(VisualPayloadGovernanceException.Reason.CORRUPT,
                        "Source payload scope does not match signed run evidence.");
            }
            requireSourceAuthorization(status, identity);
            if (!access.readable()) {
                throw new IntegrationProblemException(IntegrationProblem.gone(
                        "RG.TEST.REPLAY_SOURCE_PAYLOAD_UNAVAILABLE",
                        "Governed source payload is no longer available.", identity.correlationId(),
                        Map.of("state", status.state(), "expiresAt", status.descriptor().expiresAt())));
            }
            return access;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (VisualPayloadGovernanceException corrupt) {
            securityEvent(identity, "REPLAY_SOURCE_PAYLOAD_INVALID", "REJECTED",
                    "RG.TEST.REPLAY_SOURCE_PAYLOAD_INVALID", Map.of("runId", run.runId()));
            throw conflict(identity, "RG.TEST.REPLAY_SOURCE_PAYLOAD_INVALID",
                    "Governed source payload failed lifecycle or integrity verification.", Map.of());
        }
    }

    private void requireSourceFingerprint(ReplayPayloadCaptureRequest.Source source,
                                          VisualRunPayloadStatus status,
                                          VisualRunPayloadRepository.Access access,
                                          IntegrationRequestContext identity) {
        if (!source.payloadFingerprint().equals(status.descriptor().payloadFingerprint())
                || !source.payloadFingerprint().equals(access.payload().payloadFingerprint())) {
            throw conflict(identity, "RG.TEST.REPLAY_SOURCE_PAYLOAD_FINGERPRINT_CONFLICT",
                    "Source payload changed after selection.", Map.of());
        }
    }

    private VisualNodeExecutionAttempt exactSuccessfulAttempt(
            Map<String, List<VisualNodeExecutionAttempt>> attempts,
            ReplayPayloadCaptureRequest.Source source,
            IntegrationRequestContext identity) {
        List<VisualNodeExecutionAttempt> nodeAttempts = attempts.getOrDefault(source.nodeId(), List.of());
        VisualNodeExecutionAttempt found = nodeAttempts.stream()
                .filter(attempt -> attempt.attempt() == source.attempt()).findFirst().orElse(null);
        if (found == null || !"SUCCESS".equals(found.status())) {
            throw conflict(identity, "RG.TEST.REPLAY_SOURCE_ATTEMPT_INVALID",
                    "Replay capture requires one exact successful source node attempt.",
                    Map.of("nodeId", source.nodeId(), "attempt", source.attempt()));
        }
        return found;
    }

    private void requireReplayIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!AUTHORIZED_PURPOSE.equals(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.REPLAY_PURPOSE_REQUIRED",
                    "Replay payload operations require a verified TEST_REPLAY workload purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(Locale.ROOT))) {
            securityEvent(identity, "TEST_REPLAY_PRODUCTION_TOUCH", "REJECTED",
                    "RG.TEST.REPLAY_ENVIRONMENT_FORBIDDEN", Map.of());
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.REPLAY_ENVIRONMENT_FORBIDDEN",
                    "Replay payload operations are restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireSourceAuthorization(VisualRunPayloadStatus status,
                                                   IntegrationRequestContext identity) {
        if (!identity.hasClearanceAtLeast(status.descriptor().requiredClearance())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.REPLAY_SOURCE_CLEARANCE_REQUIRED",
                    "Workload clearance cannot read the governed source payload.",
                    identity.correlationId(), Map.of(
                    "classification", status.descriptor().classification(),
                    "requiredClearance", status.descriptor().requiredClearance())));
        }
        Set<String> missing = new HashSet<>(status.descriptor().requiredGroups());
        missing.removeAll(identity.groups());
        if (!missing.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.REPLAY_SOURCE_GROUP_REQUIRED",
                    "Workload identity is outside the source payload group boundary.",
                    identity.correlationId(), Map.of("missingGroupCount", missing.size())));
        }
    }

    private static void requireNoClassificationDowngrade(String destination, String source,
                                                         IntegrationRequestContext identity) {
        if (classificationRank(destination) < classificationRank(source)) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.REPLAY_CLASSIFICATION_DOWNGRADE",
                    "Replay payload classification cannot be weaker than its source.",
                    identity.correlationId(), Map.of("sourceClassification", source)));
        }
    }

    private static void requireClearance(String classification, IntegrationRequestContext identity) {
        if (!identity.hasClearanceAtLeast(classification)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.REPLAY_CLEARANCE_FORBIDDEN",
                    "Workload clearance cannot access this replay payload classification.",
                    identity.correlationId(), Map.of("classification", classification)));
        }
    }

    private static ReplayPayloadDescriptor.Redaction redaction(
            VisualPayloadRedactionManifest source,
            VisualPayloadRedactionManifest capture) {
        return new ReplayPayloadDescriptor.Redaction(source.profile(), source.redactedCount(),
                capture.profile(), capture.redactedCount(), source.truncated() || capture.truncated(),
                capture.redactedPaths());
    }

    private static int classificationRank(String value) {
        return CLASSIFICATIONS.indexOf(normalized(value).toUpperCase(Locale.ROOT));
    }

    private void securityEvent(IntegrationRequestContext identity, String type, String outcome,
                               String reasonCode, Map<String, Object> details) {
        try {
            securityEvents.append(new TestSecurityEvent(0, Instant.now(), identity.correlationId(),
                    identity.tenantId(), identity.environmentId(), identity.actorId(), type, outcome,
                    reasonCode, details));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SECURITY_AUDIT_UNAVAILABLE",
                    "Replay security decision could not be committed to the audit sink.");
        }
    }

    private static IntegrationProblemException badRequest(IntegrationRequestContext identity,
                                                          String code, String message,
                                                          Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, message, identity.correlationId(), details));
    }

    private static IntegrationProblemException conflict(IntegrationRequestContext identity,
                                                        String code, String message,
                                                        Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, message, identity.correlationId(), details));
    }

    private static IntegrationProblemException unavailable(IntegrationRequestContext identity,
                                                           String code, String message) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, message, identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
