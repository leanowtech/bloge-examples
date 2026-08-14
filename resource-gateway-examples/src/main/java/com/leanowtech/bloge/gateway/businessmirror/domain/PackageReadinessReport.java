package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Content-addressed, compiler-owned readiness result for one exact Package draft revision.
 *
 * <p>Status is derived from findings and cannot be supplied optimistically by an author.</p>
 */
public record PackageReadinessReport(
        String schemaVersion,
        String reportId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        String packageId,
        long sourceDraftRevision,
        String sourceDraftFingerprint,
        Status status,
        List<Finding> findings,
        Instant createdAt
) {
    /** Current readiness report version. */
    public static final String SCHEMA_VERSION = "resourceGateway.packageReadinessReport.v1";

    /** Derived readiness status. */
    public enum Status {
        READY,
        REVIEW_REQUIRED,
        BLOCKED
    }

    /** Finding severity. */
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    /** Stable readiness domain used for task routing. */
    public enum Category {
        BUSINESS_DEFINITION,
        CONTRACT,
        DEPENDENCY,
        ISOLATION,
        SCENARIO,
        OUTCOME,
        GOVERNANCE,
        INDEX
    }

    /** One payload-free, deep-linkable readiness finding. */
    public record Finding(
            String findingId,
            String code,
            Severity severity,
            Category category,
            String fieldPath,
            MirrorArtifactRef artifactRef,
            String messageId
    ) {
        /** Normalizes stable presentation coordinates without accepting arbitrary messages. */
        public Finding {
            findingId = BusinessMirrorProtocolSupport.identifier(findingId, "findingId");
            code = BusinessMirrorProtocolSupport.upper(code, "finding code");
            severity = java.util.Objects.requireNonNull(severity, "severity");
            category = java.util.Objects.requireNonNull(category, "category");
            fieldPath = BusinessMirrorProtocolSupport.required(fieldPath, "fieldPath");
            if (!fieldPath.startsWith("/")) {
                throw new IllegalArgumentException("fieldPath must be a JSON Pointer");
            }
            messageId = BusinessMirrorProtocolSupport.identifier(messageId, "messageId");
        }
    }

    /** Enforces deterministic ordering, bounded size, exact draft identity, and derived status. */
    public PackageReadinessReport {
        schemaVersion = BusinessMirrorProtocolSupport.version(schemaVersion, SCHEMA_VERSION);
        reportId = BusinessMirrorProtocolSupport.identifier(reportId, "reportId");
        if (revision < 1) {
            throw new IllegalArgumentException("readiness report revision must be positive");
        }
        fingerprint = BusinessMirrorProtocolSupport.optionalFingerprint(fingerprint, "fingerprint");
        scope = java.util.Objects.requireNonNull(scope, "scope");
        packageId = BusinessMirrorProtocolSupport.identifier(packageId, "packageId");
        if (sourceDraftRevision < 1) {
            throw new IllegalArgumentException("sourceDraftRevision must be positive");
        }
        sourceDraftFingerprint = BusinessMirrorProtocolSupport.fingerprint(
                sourceDraftFingerprint, "sourceDraftFingerprint");
        findings = BusinessMirrorProtocolSupport.sortedUnique(
                findings,
                Comparator.comparing(Finding::severity).reversed()
                        .thenComparing(Finding::code)
                        .thenComparing(Finding::findingId),
                Finding::findingId,
                "findings");
        Status derived = derive(findings);
        status = status == null ? derived : status;
        if (status != derived) {
            throw new IllegalArgumentException("readiness status must be derived from findings");
        }
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
    }

    /** @return identical report with a replacement canonical fingerprint */
    public PackageReadinessReport withFingerprint(String value) {
        return new PackageReadinessReport(schemaVersion, reportId, revision, value, scope,
                packageId, sourceDraftRevision, sourceDraftFingerprint, status, findings, createdAt);
    }

    /** @return sealed report whose content address excludes its fingerprint field */
    public PackageReadinessReport seal(ObjectMapper mapper) {
        PackageReadinessReport material = withFingerprint("");
        return withFingerprint(ProtocolFingerprint.ofBounded(
                java.util.Objects.requireNonNull(mapper, "mapper"), material,
                BusinessMirrorProtocolSupport.MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies the report content address. */
    public void verify(ObjectMapper mapper) {
        if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
            throw new IllegalArgumentException("Package readiness report fingerprint mismatch");
        }
    }

    private static Status derive(List<Finding> findings) {
        if (findings.stream().anyMatch(finding -> finding.severity() == Severity.ERROR)) {
            return Status.BLOCKED;
        }
        if (findings.stream().anyMatch(finding -> finding.severity() == Severity.WARNING)) {
            return Status.REVIEW_REQUIRED;
        }
        return Status.READY;
    }
}
