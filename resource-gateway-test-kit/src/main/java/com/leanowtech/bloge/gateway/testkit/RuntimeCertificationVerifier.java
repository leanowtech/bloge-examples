package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring-free offline verifier for one complete runtime-certification evidence closure.
 *
 * <p>The verifier validates the packaged strict Schemas, recomputes all three producer content
 * addresses and both signature materials, checks the fixed component/scenario/invariant
 * denominator, enforces execution and recovery windows, and binds the report to caller-pinned
 * regional and isolation evidence. Signature trust remains customer-owned through callbacks.</p>
 */
public final class RuntimeCertificationVerifier {
    /** Packaged manifest Schema. */
    public static final String MANIFEST_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-mirror/runtime-certification-manifest-v1.schema.json";
    /** Packaged destructive authorization Schema. */
    public static final String AUTHORIZATION_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-mirror/"
                    + "runtime-certification-execution-authorization-v1.schema.json";
    /** Packaged complete report Schema. */
    public static final String REPORT_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-mirror/runtime-certification-report-v1.schema.json";
    /** Maximum canonical manifest size. */
    public static final int MAXIMUM_MANIFEST_BYTES = 4 * 1024 * 1024;
    /** Maximum canonical authorization size. */
    public static final int MAXIMUM_AUTHORIZATION_BYTES = 2 * 1024 * 1024;
    /** Maximum canonical report size. */
    public static final int MAXIMUM_REPORT_BYTES = 8 * 1024 * 1024;
    /** Authorization signature domain. */
    public static final String AUTHORIZATION_SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_RUNTIME_CERTIFICATION_EXECUTION_AUTHORIZATION_V1";
    /** Report signature domain. */
    public static final String REPORT_SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_RUNTIME_CERTIFICATION_REPORT_V1";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> COMPONENTS = List.of(
            "RESOURCE_GATEWAY", "BLOGE_ENGINE", "DATABASE", "JVM");
    private static final List<String> SCENARIOS = List.of(
            "POSTGRES_PRIMARY_KILL_BEFORE_STAGE",
            "POSTGRES_PRIMARY_KILL_AFTER_STAGE",
            "POSTGRES_PRIMARY_KILL_AFTER_APPLY",
            "POSTGRES_PRIMARY_KILL_AFTER_COMMIT",
            "NETWORK_PARTITION", "LEASE_TAKEOVER", "ROLLING_UPGRADE", "BACKUP_RESTORE",
            "KMS_KEY_ROTATION", "MTLS_CA_ROTATION", "VAULT_UNAVAILABLE",
            "WRITE_ESCAPE_PROBE");
    private static final Map<String, List<String>> MANDATORY_INVARIANTS = invariants();

    /** Customer-owned detached-seal trust callback. */
    @FunctionalInterface
    public interface ExternalSealVerifier {
        /**
         * Verifies authority identity, key lifecycle, and detached signature.
         *
         * @param seal defensive copy of the seal
         * @param artifact defensive copy of the complete addressed artifact
         * @return true only when external trust passes
         */
        boolean verify(JsonNode seal, JsonNode artifact);
    }

    /** Creates a verifier backed by Schemas packaged in the Test Kit. */
    public RuntimeCertificationVerifier() {
    }

    /**
     * Requires a complete certified report bound to caller-pinned regional evidence.
     *
     * @param manifest content-addressed runtime profile
     * @param authorization externally approved single-use authorization
     * @param report complete signed result
     * @param expectedRegionalCertificationRef pinned regional certification reference
     * @param expectedIsolationDecisionRef pinned v2 isolation decision reference
     * @param expectedIsolationAttestationRef pinned underlying isolation attestation reference
     * @param authorizationSealVerifier customer authorization trust
     * @param reportSealVerifier customer report-authority trust
     * @return immutable verified release coordinates
     */
    public VerifiedCoordinates require(
            JsonNode manifest,
            JsonNode authorization,
            JsonNode report,
            JsonNode expectedRegionalCertificationRef,
            JsonNode expectedIsolationDecisionRef,
            JsonNode expectedIsolationAttestationRef,
            ExternalSealVerifier authorizationSealVerifier,
            ExternalSealVerifier reportSealVerifier) {
        JsonNode exactManifest = copy(manifest, "RG.MIRROR.CLIENT.RUNTIME_MANIFEST_INVALID");
        JsonNode exactAuthorization = copy(
                authorization, "RG.MIRROR.CLIENT.RUNTIME_AUTHORIZATION_INVALID");
        JsonNode exactReport = copy(report, "RG.MIRROR.CLIENT.RUNTIME_REPORT_INVALID");
        JsonNode regionalRef = copy(expectedRegionalCertificationRef,
                "RG.MIRROR.CLIENT.RUNTIME_REGIONAL_REF_INVALID");
        JsonNode isolationDecisionRef = copy(expectedIsolationDecisionRef,
                "RG.MIRROR.CLIENT.RUNTIME_ISOLATION_DECISION_REF_INVALID");
        JsonNode isolationAttestationRef = copy(expectedIsolationAttestationRef,
                "RG.MIRROR.CLIENT.RUNTIME_ISOLATION_ATTESTATION_REF_INVALID");
        CapabilityMirrorSchemaValidator.require(exactManifest, MANIFEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.RUNTIME_MANIFEST_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(exactAuthorization,
                AUTHORIZATION_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.RUNTIME_AUTHORIZATION_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(exactReport, REPORT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.RUNTIME_REPORT_SCHEMA_INVALID");

        verifyFingerprints(exactManifest, exactAuthorization, exactReport);
        if (authorizationSealVerifier == null || !authorizationSealVerifier.verify(
                exactAuthorization.path("authorizationSeal").deepCopy(),
                exactAuthorization.deepCopy())) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_AUTHORIZATION_AUTHORITY_REJECTED");
        }
        if (reportSealVerifier == null || !reportSealVerifier.verify(
                exactReport.path("reportSeal").deepCopy(), exactReport.deepCopy())) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_REPORT_AUTHORITY_REJECTED");
        }
        verifyManifest(exactManifest);
        verifyAuthorization(exactManifest, exactAuthorization);
        verifyReport(exactManifest, exactAuthorization, exactReport,
                regionalRef, isolationDecisionRef, isolationAttestationRef);
        return new VerifiedCoordinates(
                exactManifest.path("manifestId").asText(),
                exactManifest.path("revision").asLong(),
                exactManifest.path("manifestFingerprint").asText(),
                exactAuthorization.path("authorizationId").asText(),
                exactAuthorization.path("revision").asLong(),
                exactAuthorization.path("authorizationFingerprint").asText(),
                exactReport.path("reportId").asText(),
                exactReport.path("revision").asLong(),
                exactReport.path("reportFingerprint").asText(),
                exactReport.path("startedAt").asText(),
                exactReport.path("completedAt").asText());
    }

    private static void verifyFingerprints(
            JsonNode manifest, JsonNode authorization, JsonNode report) {
        ObjectNode manifestEnvelope = JSON.createObjectNode();
        manifestEnvelope.put("schemaVersion", manifest.path("schemaVersion").asText());
        manifestEnvelope.put("manifestFingerprint", "");
        manifestEnvelope.set("material", material(manifest, List.of(
                "manifestId", "revision", "scope", "region", "deployment",
                "environmentClass", "environmentFingerprint", "components", "scenarios",
                "validFrom", "expiresAt", "owner")));
        if (!manifest.path("manifestFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256Bounded(
                        manifestEnvelope, MAXIMUM_MANIFEST_BYTES))) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_MANIFEST_FINGERPRINT_INVALID");
        }

        ObjectNode authorizationMaterial = material(authorization, List.of(
                "authorizationId", "revision", "manifestRef", "scope", "environmentClass",
                "environmentFingerprint", "deployment", "allowedScenarios",
                "destructiveActionsAllowed", "productionExecutionDenied", "singleUse",
                "nonceFingerprint", "issuedAt", "validFrom", "expiresAt", "issuer",
                "approvalRefs"));
        verifySignedAddress(authorization, authorizationMaterial,
                "authorizationFingerprint", "authorizationSeal",
                AUTHORIZATION_SIGNATURE_DOMAIN, MAXIMUM_AUTHORIZATION_BYTES,
                "RG.MIRROR.CLIENT.RUNTIME_AUTHORIZATION_MATERIAL_FINGERPRINT_INVALID",
                "RG.MIRROR.CLIENT.RUNTIME_AUTHORIZATION_FINGERPRINT_INVALID");

        ObjectNode reportMaterial = material(report, List.of(
                "reportId", "revision", "manifestRef", "authorizationRef",
                "authorizationConsumptionRef", "regionalDataPlaneCertificationRef",
                "isolationDecisionRef", "isolationAttestationRef", "scope", "region",
                "deployment", "environmentClass", "environmentFingerprint", "adapter",
                "observedComponents", "startedAt", "completedAt", "scenarioResults",
                "verdict", "externalBusinessWriteAttemptCount", "writeEscapeCount", "issuer",
                "proofRefs"));
        verifySignedAddress(report, reportMaterial, "reportFingerprint", "reportSeal",
                REPORT_SIGNATURE_DOMAIN, MAXIMUM_REPORT_BYTES,
                "RG.MIRROR.CLIENT.RUNTIME_REPORT_MATERIAL_FINGERPRINT_INVALID",
                "RG.MIRROR.CLIENT.RUNTIME_REPORT_FINGERPRINT_INVALID");
    }

    private static void verifySignedAddress(
            JsonNode artifact,
            ObjectNode artifactMaterial,
            String fingerprintField,
            String sealField,
            String domain,
            int maximumBytes,
            String materialReason,
            String artifactReason) {
        ObjectNode signatureEnvelope = JSON.createObjectNode();
        signatureEnvelope.put("domain", domain);
        signatureEnvelope.put("schemaVersion", artifact.path("schemaVersion").asText());
        signatureEnvelope.set("material", artifactMaterial);
        if (!artifact.path(sealField).path("materialFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256Bounded(
                        signatureEnvelope, maximumBytes))) {
            throw invalid(materialReason);
        }
        ObjectNode addressEnvelope = JSON.createObjectNode();
        addressEnvelope.put("schemaVersion", artifact.path("schemaVersion").asText());
        addressEnvelope.put(fingerprintField, "");
        addressEnvelope.set("material", artifactMaterial);
        addressEnvelope.set(sealField, artifact.path(sealField).deepCopy());
        if (!artifact.path(fingerprintField).asText().equals(
                EvidenceVerificationSupport.sha256Bounded(addressEnvelope, maximumBytes))) {
            throw invalid(artifactReason);
        }
    }

    private static void verifyManifest(JsonNode manifest) {
        if (!exactKinds(manifest.path("components"), COMPONENTS, "kind")
                || !exactKinds(manifest.path("scenarios"), SCENARIOS, "scenario")) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_MANIFEST_DENOMINATOR_INVALID");
        }
        Instant validFrom = instant(manifest.path("validFrom").asText());
        Instant expiresAt = instant(manifest.path("expiresAt").asText());
        if (!expiresAt.isAfter(validFrom)
                || Duration.between(validFrom, expiresAt).compareTo(Duration.ofDays(31)) > 0) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_MANIFEST_WINDOW_INVALID");
        }
        for (int index = 0; index < SCENARIOS.size(); index++) {
            JsonNode requirement = manifest.path("scenarios").get(index);
            long executionSeconds = requirement.path("maximumExecutionSeconds").asLong();
            long recoverySeconds = requirement.path("maximumRecoverySeconds").asLong();
            List<String> codes = strings(requirement.path("requiredInvariantCodes"));
            if (recoverySeconds > executionSeconds
                    || !strictlySorted(codes)
                    || !codes.containsAll(MANDATORY_INVARIANTS.get(SCENARIOS.get(index)))) {
                throw invalid("RG.MIRROR.CLIENT.RUNTIME_MANIFEST_INVARIANTS_INVALID");
            }
        }
    }

    private static void verifyAuthorization(JsonNode manifest, JsonNode authorization) {
        if (!artifactEquals(authorization.path("manifestRef"),
                "RUNTIME_CERTIFICATION_MANIFEST", manifest.path("manifestId").asText(),
                manifest.path("revision").asLong(),
                manifest.path("manifestFingerprint").asText())
                || !manifest.path("scope").equals(authorization.path("scope"))
                || !manifest.path("deployment").equals(authorization.path("deployment"))
                || !manifest.path("environmentClass").equals(
                authorization.path("environmentClass"))
                || !manifest.path("environmentFingerprint").equals(
                authorization.path("environmentFingerprint"))
                || !strings(authorization.path("allowedScenarios")).equals(SCENARIOS)) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_AUTHORIZATION_CLOSURE_INVALID");
        }
        Instant issuedAt = instant(authorization.path("issuedAt").asText());
        Instant validFrom = instant(authorization.path("validFrom").asText());
        Instant expiresAt = instant(authorization.path("expiresAt").asText());
        if (validFrom.isBefore(issuedAt) || !expiresAt.isAfter(validFrom)
                || Duration.between(issuedAt, expiresAt)
                .compareTo(Duration.ofMinutes(30)) > 0) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_AUTHORIZATION_WINDOW_INVALID");
        }
    }

    private static void verifyReport(
            JsonNode manifest,
            JsonNode authorization,
            JsonNode report,
            JsonNode expectedRegionalRef,
            JsonNode expectedIsolationDecisionRef,
            JsonNode expectedIsolationAttestationRef) {
        JsonNode adapter = report.path("adapter");
        if (!artifactEquals(report.path("manifestRef"),
                "RUNTIME_CERTIFICATION_MANIFEST", manifest.path("manifestId").asText(),
                manifest.path("revision").asLong(),
                manifest.path("manifestFingerprint").asText())
                || !artifactEquals(report.path("authorizationRef"),
                "RUNTIME_CERTIFICATION_EXECUTION_AUTHORIZATION",
                authorization.path("authorizationId").asText(),
                authorization.path("revision").asLong(),
                authorization.path("authorizationFingerprint").asText())
                || !report.path("regionalDataPlaneCertificationRef").equals(expectedRegionalRef)
                || !report.path("isolationDecisionRef").equals(expectedIsolationDecisionRef)
                || !report.path("isolationAttestationRef").equals(
                expectedIsolationAttestationRef)
                || !manifest.path("scope").equals(report.path("scope"))
                || !manifest.path("region").equals(report.path("region"))
                || !manifest.path("deployment").equals(report.path("deployment"))
                || !manifest.path("environmentClass").equals(report.path("environmentClass"))
                || !manifest.path("environmentFingerprint").equals(
                report.path("environmentFingerprint"))
                || !manifest.path("environmentClass").equals(
                adapter.path("environmentClass"))
                || !manifest.path("environmentFingerprint").equals(
                adapter.path("environmentFingerprint"))) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_REPORT_CLOSURE_INVALID");
        }
        if (!adapter.path("available").asBoolean()
                || !adapter.path("isolatedControlPlane").asBoolean()
                || !adapter.path("productionExecutionDenied").asBoolean()
                || !adapter.path("externalAuthorizationRequired").asBoolean()
                || !adapter.path("durableReplayProtection").asBoolean()
                || !strings(adapter.path("supportedScenarios")).equals(SCENARIOS)) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_ADAPTER_REJECTED");
        }
        if (!manifest.path("components").equals(report.path("observedComponents"))
                || !exactKinds(report.path("scenarioResults"), SCENARIOS, "scenario")) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_REPORT_DENOMINATOR_INVALID");
        }
        Instant reportStarted = instant(report.path("startedAt").asText());
        Instant reportCompleted = instant(report.path("completedAt").asText());
        if (reportCompleted.isBefore(reportStarted)
                || !covered(manifest, reportStarted, reportCompleted)
                || !covered(authorization, reportStarted, reportCompleted)) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_REPORT_WINDOW_INVALID");
        }
        long writeAttempts = 0;
        long writeEscapes = 0;
        boolean allPassed = true;
        for (int index = 0; index < SCENARIOS.size(); index++) {
            JsonNode requirement = manifest.path("scenarios").get(index);
            JsonNode observed = report.path("scenarioResults").get(index);
            Instant started = instant(observed.path("startedAt").asText());
            Instant completed = instant(observed.path("completedAt").asText());
            Instant applied = optionalInstant(observed.path("faultAppliedAt"));
            Instant removed = optionalInstant(observed.path("faultRemovedAt"));
            Instant recovered = optionalInstant(observed.path("recoveryObservedAt"));
            if (started.isBefore(reportStarted) || completed.isAfter(reportCompleted)
                    || completed.isBefore(started)
                    || applied != null && (applied.isBefore(started)
                    || applied.isAfter(completed))
                    || removed != null && (applied == null || removed.isBefore(applied)
                    || removed.isAfter(completed))
                    || recovered != null && (removed == null || recovered.isBefore(removed)
                    || recovered.isAfter(completed))
                    || observed.path("faultApplied").asBoolean() != (applied != null)
                    || observed.path("recoveryObserved").asBoolean() != (recovered != null)
                    || Duration.between(started, completed).compareTo(Duration.ofSeconds(
                    requirement.path("maximumExecutionSeconds").asLong())) > 0
                    || removed != null && recovered != null
                    && Duration.between(removed, recovered).compareTo(Duration.ofSeconds(
                    requirement.path("maximumRecoverySeconds").asLong())) > 0) {
                throw invalid("RG.MIRROR.CLIENT.RUNTIME_SCENARIO_WINDOW_INVALID");
            }
            List<String> requiredCodes = strings(
                    requirement.path("requiredInvariantCodes"));
            List<String> observedCodes = new java.util.ArrayList<>();
            boolean allInvariantsPassed = true;
            for (JsonNode invariant : observed.path("invariantObservations")) {
                observedCodes.add(invariant.path("code").asText());
                allInvariantsPassed &= "PASSED".equals(invariant.path("status").asText());
            }
            if (!requiredCodes.equals(observedCodes)) {
                throw invalid("RG.MIRROR.CLIENT.RUNTIME_SCENARIO_INVARIANTS_INVALID");
            }
            long scenarioAttempts = observed.path(
                    "externalBusinessWriteAttemptCount").asLong(-1);
            long scenarioEscapes = observed.path("writeEscapeCount").asLong(-1);
            boolean passed = "PASSED".equals(observed.path("status").asText());
            if (passed && (applied == null || removed == null || recovered == null
                    || !allInvariantsPassed || scenarioAttempts != 0 || scenarioEscapes != 0)) {
                throw invalid("RG.MIRROR.CLIENT.RUNTIME_PASSED_SCENARIO_INVALID");
            }
            allPassed &= passed;
            writeAttempts += scenarioAttempts;
            writeEscapes += scenarioEscapes;
        }
        if (writeAttempts != report.path("externalBusinessWriteAttemptCount").asLong(-1)
                || writeEscapes != report.path("writeEscapeCount").asLong(-1)) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_WRITE_COUNTER_INVALID");
        }
        if (!allPassed || !"CERTIFIED".equals(report.path("verdict").asText())
                || writeAttempts != 0 || writeEscapes != 0) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_NOT_CERTIFIED");
        }
    }

    private static ObjectNode material(JsonNode source, List<String> fields) {
        ObjectNode material = JSON.createObjectNode();
        fields.forEach(field -> material.set(field, source.path(field).deepCopy()));
        return material;
    }

    private static boolean exactKinds(JsonNode values, List<String> expected, String field) {
        if (!values.isArray() || values.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).equals(values.get(index).path(field).asText())) {
                return false;
            }
        }
        return true;
    }

    private static List<String> strings(JsonNode values) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static boolean strictlySorted(List<String> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).compareTo(values.get(index)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean artifactEquals(
            JsonNode ref, String kind, String id, long revision, String fingerprint) {
        return kind.equals(ref.path("kind").asText())
                && id.equals(ref.path("id").asText())
                && revision == ref.path("revision").asLong()
                && fingerprint.equals(ref.path("fingerprint").asText());
    }

    private static boolean covered(JsonNode artifact, Instant start, Instant end) {
        Instant validFrom = instant(artifact.path("validFrom").asText());
        Instant expiresAt = instant(artifact.path("expiresAt").asText());
        return !start.isBefore(validFrom) && start.isBefore(expiresAt)
                && !end.isBefore(start) && end.isBefore(expiresAt);
    }

    private static Instant optionalInstant(JsonNode value) {
        return value == null || value.isNull() ? null : instant(value.asText());
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw invalid("RG.MIRROR.CLIENT.RUNTIME_TIMESTAMP_INVALID");
        }
    }

    private static JsonNode copy(JsonNode value, String reason) {
        if (value == null || !value.isObject()) {
            throw invalid(reason);
        }
        return value.deepCopy();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    private static Map<String, List<String>> invariants() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("POSTGRES_PRIMARY_KILL_BEFORE_STAGE",
                List.of("NO_PARTIAL_VISIBILITY", "EXACT_REPLAY", "NO_COMMITTED_STATE_LOSS"));
        values.put("POSTGRES_PRIMARY_KILL_AFTER_STAGE",
                List.of("NO_PARTIAL_VISIBILITY", "EXACT_REPLAY", "NO_COMMITTED_STATE_LOSS"));
        values.put("POSTGRES_PRIMARY_KILL_AFTER_APPLY",
                List.of("NO_PARTIAL_VISIBILITY", "EXACT_REPLAY", "NO_COMMITTED_STATE_LOSS"));
        values.put("POSTGRES_PRIMARY_KILL_AFTER_COMMIT",
                List.of("COMMITTED_STATE_VISIBLE", "EXACT_REPLAY", "NO_DUPLICATE_EFFECT"));
        values.put("NETWORK_PARTITION",
                List.of("SINGLE_ACTIVE_OWNER", "OLD_EPOCH_FENCED", "EVENTUAL_RECOVERY"));
        values.put("LEASE_TAKEOVER",
                List.of("SINGLE_ACTIVE_OWNER", "OLD_EPOCH_FENCED", "EVENTUAL_RECOVERY"));
        values.put("ROLLING_UPGRADE",
                List.of("MIXED_VERSION_COMPATIBLE", "NO_COMMITTED_STATE_LOSS",
                        "EVENTUAL_RECOVERY"));
        values.put("BACKUP_RESTORE",
                List.of("RESTORE_CONTINUITY", "NO_COMMITTED_STATE_LOSS",
                        "MONOTONIC_FENCE_PRESERVED"));
        values.put("KMS_KEY_ROTATION",
                List.of("KEY_ROTATION_CONTINUITY", "OLD_GENERATION_REJECTED", "RESTART_FREE"));
        values.put("MTLS_CA_ROTATION",
                List.of("CA_ROTATION_CONTINUITY", "OLD_GENERATION_REJECTED", "RESTART_FREE"));
        values.put("VAULT_UNAVAILABLE",
                List.of("FAIL_CLOSED", "NO_SECRET_FALLBACK", "EVENTUAL_RECOVERY"));
        values.put("WRITE_ESCAPE_PROBE",
                List.of("ZERO_EXTERNAL_BUSINESS_WRITES", "FAIL_CLOSED"));
        return Map.copyOf(values);
    }

    /** Immutable coordinates safe to carry into a release gate. */
    public record VerifiedCoordinates(
            String manifestId,
            long manifestRevision,
            String manifestFingerprint,
            String authorizationId,
            long authorizationRevision,
            String authorizationFingerprint,
            String reportId,
            long reportRevision,
            String reportFingerprint,
            String startedAt,
            String completedAt
    ) {
    }
}
