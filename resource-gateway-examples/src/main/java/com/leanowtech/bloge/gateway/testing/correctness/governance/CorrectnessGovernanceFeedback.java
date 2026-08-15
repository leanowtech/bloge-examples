package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest.PublicationRef;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Immutable ANEKE decision projection bound to one exact Correctness Publication. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessGovernanceFeedback(
        String schemaVersion,
        String feedbackId,
        EnterpriseScope scope,
        PublicationRef publicationRef,
        String sourceSystem,
        String sourceProtocolVersion,
        String sourceDecisionId,
        long sourceDecisionRevision,
        String sourceDecisionFingerprint,
        GateDecision decision,
        WorkbookStatus workbookStatus,
        OwnerApprovalStatus ownerApprovalStatus,
        BreakingMigrationStatus breakingMigrationStatus,
        List<Finding> findings,
        Instant producedAt,
        Instant expiresAt,
        Instant receivedAt,
        String receivedBy,
        String correlationId
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.correctnessFeedback.v1";

    public enum GateDecision { ACCEPTED, BLOCKED, REVIEW_REQUIRED, NOT_EVALUATED }
    public enum WorkbookStatus { CURRENT, STALE, MISSING, NOT_EVALUATED }
    public enum OwnerApprovalStatus { APPROVED, REQUIRED, REJECTED, NOT_EVALUATED }
    public enum BreakingMigrationStatus { NONE, DETECTED, REVIEW_REQUIRED, NOT_EVALUATED }
    public enum Severity { BLOCKING, WARNING, INFO }
    public enum Category { CONTRACT, WORKBOOK, OWNER, MIGRATION, RUNTIME, POLICY }

    public CorrectnessGovernanceFeedback {
        schemaVersion = version(schemaVersion);
        feedbackId = required(feedbackId, "feedbackId");
        sourceSystem = required(sourceSystem, "sourceSystem").toUpperCase(Locale.ROOT);
        sourceProtocolVersion = required(sourceProtocolVersion, "sourceProtocolVersion");
        sourceDecisionId = required(sourceDecisionId, "sourceDecisionId");
        if (scope == null || publicationRef == null || decision == null
                || workbookStatus == null || ownerApprovalStatus == null
                || breakingMigrationStatus == null || producedAt == null || receivedAt == null) {
            throw new IllegalArgumentException("Complete governance feedback coordinates are required");
        }
        if (sourceDecisionRevision < 1) {
            throw new IllegalArgumentException("sourceDecisionRevision must be positive");
        }
        sourceDecisionFingerprint = fingerprint(sourceDecisionFingerprint);
        findings = findings == null ? List.of() : findings.stream().distinct()
                .sorted(Comparator.comparing(Finding::severity)
                        .thenComparing(Finding::code)
                        .thenComparing(Finding::findingId)).toList();
        if (decision == GateDecision.BLOCKED
                && findings.stream().noneMatch(value -> value.severity() == Severity.BLOCKING)) {
            throw new IllegalArgumentException(
                    "A BLOCKED governance decision requires a blocking finding");
        }
        if (expiresAt != null && !expiresAt.isAfter(producedAt)) {
            throw new IllegalArgumentException("expiresAt must follow producedAt");
        }
        if (receivedAt.isBefore(producedAt)) {
            throw new IllegalArgumentException("receivedAt must not precede producedAt");
        }
        receivedBy = required(receivedBy, "receivedBy");
        correlationId = required(correlationId, "correlationId");
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(
            String findingId,
            Severity severity,
            Category category,
            String code,
            String message,
            String remediation,
            String deepLink
    ) {
        public Finding {
            findingId = required(findingId, "findingId");
            if (severity == null || category == null) {
                throw new IllegalArgumentException("Finding severity and category are required");
            }
            code = required(code, "code").toUpperCase(Locale.ROOT);
            message = bounded(message, "message", 1000);
            remediation = bounded(remediation, "remediation", 1000);
            deepLink = deepLink == null ? "" : deepLink.trim();
            if (!deepLink.isEmpty() && !(deepLink.startsWith("/")
                    || deepLink.startsWith("https://"))) {
                throw new IllegalArgumentException("Finding deepLink must be relative or HTTPS");
            }
        }
    }

    private static String version(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return SCHEMA_VERSION;
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported governance feedback schemaVersion");
        }
        return normalized;
    }

    private static String fingerprint(String value) {
        String normalized = required(value, "sourceDecisionFingerprint");
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact source decision fingerprint is required");
        }
        return normalized;
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = required(value, field);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
