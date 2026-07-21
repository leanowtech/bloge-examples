package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed identity for one physically isolated suite-stability attempt.
 *
 * <p>The identity freezes the exact durable queue fence, immutable execution intent, selected
 * runtime binding, provider deployment, and physical isolation mode before dispatch. It proves
 * neither that the provider accepted the dispatch nor that a process is running; those facts need
 * separate verified provider receipts.</p>
 *
 * @param schemaVersion exact identity protocol generation
 * @param attemptId content-addressed provider-independent attempt id
 * @param identityFingerprint canonical semantic identity fingerprint
 * @param tenantId authenticated tenant scope
 * @param environmentId isolated {@code test} or {@code staging} environment
 * @param jobId durable suite-stability parent job
 * @param requestFingerprint immutable queue execution intent
 * @param ownerId worker owner bound to the durable queue lease
 * @param leaseEpoch monotonic durable queue ownership generation
 * @param runtimeBindingFingerprint immutable executable runtime binding
 * @param providerId selected isolated-runtime provider
 * @param deploymentId exact provider workload generation
 * @param isolationMode physical boundary required for this attempt
 */
public record TestSuiteStabilityPhysicalAttemptIdentity(
        String schemaVersion,
        String attemptId,
        String identityFingerprint,
        String tenantId,
        String environmentId,
        String jobId,
        String requestFingerprint,
        String ownerId,
        long leaseEpoch,
        String runtimeBindingFingerprint,
        String providerId,
        String deploymentId,
        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode isolationMode) {

    /** Exact physical-attempt identity protocol generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptIdentity.v1";
    private static final Pattern ATTEMPT_ID =
            Pattern.compile("stability-attempt-[a-f0-9]{64}");
    private static final Pattern JOB_ID = Pattern.compile("stability-job-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

    /** Enforces a closed, payload-free, content-addressed identity envelope. */
    public TestSuiteStabilityPhysicalAttemptIdentity {
        schemaVersion = required(schemaVersion, "schemaVersion");
        attemptId = required(attemptId, "attemptId");
        identityFingerprint = required(identityFingerprint, "identityFingerprint");
        tenantId = requiredIdentifier(tenantId, "tenantId");
        environmentId = required(environmentId, "environmentId");
        jobId = required(jobId, "jobId");
        requestFingerprint = required(requestFingerprint, "requestFingerprint");
        ownerId = requiredIdentifier(ownerId, "ownerId");
        runtimeBindingFingerprint = required(
                runtimeBindingFingerprint, "runtimeBindingFingerprint");
        providerId = requiredIdentifier(providerId, "providerId");
        deploymentId = requiredIdentifier(deploymentId, "deploymentId");
        isolationMode = Objects.requireNonNull(isolationMode, "isolationMode");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !ATTEMPT_ID.matcher(attemptId).matches()
                || !FINGERPRINT.matcher(identityFingerprint).matches()
                || !attemptId.equals("stability-attempt-"
                + identityFingerprint.substring("sha256:".length()))
                || !Set.of("test", "staging").contains(environmentId)
                || !JOB_ID.matcher(jobId).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || leaseEpoch < 1
                || !FINGERPRINT.matcher(runtimeBindingFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt identity");
        }
    }

    /**
     * Derives one deterministic identity from a durable queue lease and selected runtime.
     *
     * @param objectMapper canonical protocol mapper
     * @param lease exact live queue ownership fence
     * @param runtimeBindingFingerprint immutable executable runtime binding
     * @param providerId selected isolated-runtime provider
     * @param deploymentId exact provider workload generation
     * @param isolationMode required physical isolation boundary
     * @return immutable content-addressed physical-attempt identity
     */
    public static TestSuiteStabilityPhysicalAttemptIdentity create(
            ObjectMapper objectMapper,
            TestSuiteStabilityJobLease lease,
            String runtimeBindingFingerprint,
            String providerId,
            String deploymentId,
            TestSuiteStabilityAttemptCancellationReceipt.IsolationMode isolationMode) {
        TestSuiteStabilityJobLease requiredLease = Objects.requireNonNull(lease, "lease");
        Map<String, Object> material = material(
                requiredLease.tenantId(), requiredLease.environmentId(), requiredLease.jobId(),
                requiredLease.requestFingerprint(), requiredLease.ownerId(),
                requiredLease.epoch(), runtimeBindingFingerprint, providerId, deploymentId,
                isolationMode);
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material);
        return new TestSuiteStabilityPhysicalAttemptIdentity(
                SCHEMA_VERSION,
                "stability-attempt-" + fingerprint.substring("sha256:".length()),
                fingerprint, requiredLease.tenantId(), requiredLease.environmentId(),
                requiredLease.jobId(), requiredLease.requestFingerprint(),
                requiredLease.ownerId(), requiredLease.epoch(), runtimeBindingFingerprint,
                providerId, deploymentId, isolationMode);
    }

    /**
     * Reconstructs the exact semantic material used to derive this identity.
     *
     * @return canonical identity material excluding only its derived id and fingerprint
     */
    public Map<String, Object> canonicalMaterial() {
        return material(tenantId, environmentId, jobId, requestFingerprint, ownerId,
                leaseEpoch, runtimeBindingFingerprint, providerId, deploymentId,
                isolationMode);
    }

    private static Map<String, Object> material(
            String tenantId,
            String environmentId,
            String jobId,
            String requestFingerprint,
            String ownerId,
            long leaseEpoch,
            String runtimeBindingFingerprint,
            String providerId,
            String deploymentId,
            TestSuiteStabilityAttemptCancellationReceipt.IsolationMode isolationMode) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("tenantId", tenantId);
        material.put("environmentId", environmentId);
        material.put("jobId", jobId);
        material.put("requestFingerprint", requestFingerprint);
        material.put("ownerId", ownerId);
        material.put("leaseEpoch", leaseEpoch);
        material.put("runtimeBindingFingerprint", runtimeBindingFingerprint);
        material.put("providerId", providerId);
        material.put("deploymentId", deploymentId);
        material.put("isolationMode", isolationMode);
        return Map.copyOf(material);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String requiredIdentifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
