package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared, payload-free coordinates and governance values for correctness authoring protocols. */
public final class CorrectnessProtocol {

    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

    private CorrectnessProtocol() {
    }

    public enum TargetKind { GRAPH, OPERATOR, FUNCTION }
    public enum PrincipalKind { USER, TEAM, SERVICE }
    public enum ReviewStatus { PENDING, APPROVED, REJECTED }
    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnterpriseScope(
            String tenantId,
            String organizationId,
            String projectId,
            String environment,
            String region
    ) {
        public EnterpriseScope {
            tenantId = required(tenantId, "tenantId");
            organizationId = required(organizationId, "organizationId");
            projectId = required(projectId, "projectId");
            environment = required(environment, "environment");
            region = required(region, "region");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExactAssetRef(String kind, String id, long revision, String fingerprint) {
        public ExactAssetRef {
            kind = required(kind, "kind").toUpperCase(Locale.ROOT);
            id = required(id, "id");
            positiveRevision(revision, "revision");
            fingerprint = exactFingerprint(fingerprint, "fingerprint");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExactTargetRef(
            TargetKind kind,
            String id,
            long revision,
            String fingerprint
    ) {
        public ExactTargetRef {
            kind = required(kind, "kind");
            id = required(id, "id");
            positiveRevision(revision, "revision");
            fingerprint = exactFingerprint(fingerprint, "fingerprint");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExactObligationRef(
            ExactAssetRef inventoryRef,
            String obligationId,
            String obligationFingerprint
    ) {
        public ExactObligationRef {
            inventoryRef = required(inventoryRef, "inventoryRef");
            obligationId = required(obligationId, "obligationId");
            obligationFingerprint = exactFingerprint(
                    obligationFingerprint, "obligationFingerprint");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExactCaseRef(
            ExactAssetRef scenarioDraftSetRef,
            String caseId,
            String caseFingerprint
    ) {
        public ExactCaseRef {
            scenarioDraftSetRef = required(scenarioDraftSetRef, "scenarioDraftSetRef");
            caseId = required(caseId, "caseId");
            caseFingerprint = exactFingerprint(caseFingerprint, "caseFingerprint");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExactBasisRef(String kind, String id, long revision, String fingerprint) {
        public ExactBasisRef {
            kind = required(kind, "kind").toUpperCase(Locale.ROOT);
            id = required(id, "id");
            positiveRevision(revision, "revision");
            fingerprint = exactFingerprint(fingerprint, "fingerprint");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExactSourceSnapshotRef(
            String kind,
            String id,
            long revision,
            String fingerprint
    ) {
        public ExactSourceSnapshotRef {
            kind = required(kind, "kind").toUpperCase(Locale.ROOT);
            id = required(id, "id");
            positiveRevision(revision, "revision");
            fingerprint = exactFingerprint(fingerprint, "fingerprint");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExactSchemaRef(String id, long revision, String fingerprint) {
        public ExactSchemaRef {
            id = required(id, "id");
            positiveRevision(revision, "revision");
            fingerprint = exactFingerprint(fingerprint, "fingerprint");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrincipalRef(String id, PrincipalKind kind, String displayName) {
        public PrincipalRef {
            id = required(id, "id");
            kind = required(kind, "kind");
            displayName = trimmed(displayName);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewRecord(
            ReviewStatus status,
            PrincipalRef reviewer,
            Instant reviewedAt,
            String comment
    ) {
        public ReviewRecord {
            status = status == null ? ReviewStatus.PENDING : status;
            comment = trimmed(comment);
            if (status != ReviewStatus.PENDING && (reviewer == null || reviewedAt == null)) {
                throw new IllegalArgumentException(
                        "A completed review requires reviewer and reviewedAt");
            }
        }

        public boolean approved() {
            return status == ReviewStatus.APPROVED;
        }

        public static ReviewRecord pending() {
            return new ReviewRecord(ReviewStatus.PENDING, null, null, "");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Waiver(
            String reason,
            Instant expiresAt,
            PrincipalRef approvedBy,
            Instant approvedAt
    ) {
        public Waiver {
            reason = required(reason, "reason");
            expiresAt = required(expiresAt, "expiresAt");
            approvedBy = required(approvedBy, "approvedBy");
            approvedAt = required(approvedAt, "approvedAt");
            if (!expiresAt.isAfter(approvedAt)) {
                throw new IllegalArgumentException("Waiver expiresAt must be after approvedAt");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditMetadata(
            Instant createdAt,
            Instant updatedAt,
            PrincipalRef createdBy,
            PrincipalRef updatedBy
    ) {
        public AuditMetadata {
            createdAt = required(createdAt, "createdAt");
            updatedAt = required(updatedAt, "updatedAt");
            createdBy = required(createdBy, "createdBy");
            updatedBy = required(updatedBy, "updatedBy");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not precede createdAt");
            }
        }
    }

    static String protocolVersion(String actual, String expected) {
        String normalized = trimmed(actual);
        if (normalized.isEmpty()) return expected;
        if (!expected.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + normalized);
        }
        return normalized;
    }

    static String required(String value, String field) {
        String normalized = trimmed(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    static <T> T required(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    static String exactFingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be an exact lowercase SHA-256 fingerprint");
        }
        return normalized;
    }

    static long mutableRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        return revision;
    }

    static void positiveRevision(long revision, String field) {
        if (revision < 1) throw new IllegalArgumentException(field + " must be positive");
    }

    static List<String> sortedStrings(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(CorrectnessProtocol::trimmed)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
