package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed public-only compatibility fixture for one complete durable online Shadow execution.
 *
 * <p>The fixture composes an immutable request, terminal job, complete lifecycle, signed
 * comparison, independently signed online baseline and candidate sources, and a signed v2
 * source-resolution proof. Verification first delegates to the existing independent artifact
 * verifiers and then closes every cross-artifact identity, reference, role, time, and source
 * coordinate. The envelope contains no private key, endpoint, credential, worker identity,
 * business request, or business response.</p>
 *
 * @param expectedScope authenticated enterprise scope
 * @param expectedJobId deterministic durable job and online execution identity
 * @param expectedRequestFingerprint immutable request content address
 * @param expectedComparisonRef exact terminal comparison reference
 * @param expectedAttestationRef exact online source-resolution proof reference
 * @param expectedLifecycleNextSequence complete lifecycle terminal cursor
 * @param request immutable payload-free job request
 * @param job terminal durable job projection
 * @param lifecyclePage complete admitted-to-terminal lifecycle page
 * @param comparison signed terminal comparison
 * @param comparisonKey public comparison verification key
 * @param baselineCommand exact payload-free regional baseline command
 * @param baselineObservation exact independently signed regional observation
 * @param baselineKey public regional observation key
 * @param candidateCommand exact payload-free same-input candidate command
 * @param candidateEvidenceBundle exact independently signed candidate evidence
 * @param candidateEvidenceKey public candidate evidence key
 * @param attestation exact signed online paired-source proof
 * @param attestationKey public source-resolution authority key
 * @param verificationTime frozen compatibility verification time
 */
public record OnlineReadOnlyShadowWorkerCompatibilityFixture(
        JsonNode expectedScope,
        String expectedJobId,
        String expectedRequestFingerprint,
        JsonNode expectedComparisonRef,
        JsonNode expectedAttestationRef,
        long expectedLifecycleNextSequence,
        JsonNode request,
        JsonNode job,
        JsonNode lifecyclePage,
        JsonNode comparison,
        EvidenceVerificationKey comparisonKey,
        JsonNode baselineCommand,
        JsonNode baselineObservation,
        EvidenceVerificationKey baselineKey,
        JsonNode candidateCommand,
        JsonNode candidateEvidenceBundle,
        EvidenceVerificationKey candidateEvidenceKey,
        JsonNode attestation,
        EvidenceVerificationKey attestationKey,
        Instant verificationTime
) {
    /** Fixed durable online worker compatibility-fixture envelope version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.onlineReadOnlyShadowWorkerCompatibility.v1";

    /** Defensively copies untrusted JSON and validates bounded consumer expectations. */
    public OnlineReadOnlyShadowWorkerCompatibilityFixture {
        expectedScope = object(
                expectedScope, "expectedScope");
        expectedJobId = required(
                expectedJobId, "expectedJobId");
        expectedRequestFingerprint =
                fingerprint(
                        expectedRequestFingerprint,
                        "expectedRequestFingerprint");
        expectedComparisonRef = object(
                expectedComparisonRef,
                "expectedComparisonRef");
        expectedAttestationRef = object(
                expectedAttestationRef,
                "expectedAttestationRef");
        if (expectedLifecycleNextSequence < 1) {
            throw new IllegalArgumentException(
                    "expectedLifecycleNextSequence is invalid");
        }
        request = object(request, "request");
        job = object(job, "job");
        lifecyclePage = object(
                lifecyclePage, "lifecyclePage");
        comparison = object(
                comparison, "comparison");
        comparisonKey = Objects.requireNonNull(
                comparisonKey, "comparisonKey");
        baselineCommand = object(
                baselineCommand, "baselineCommand");
        baselineObservation = object(
                baselineObservation,
                "baselineObservation");
        baselineKey = Objects.requireNonNull(
                baselineKey, "baselineKey");
        candidateCommand = object(
                candidateCommand, "candidateCommand");
        candidateEvidenceBundle = object(
                candidateEvidenceBundle,
                "candidateEvidenceBundle");
        candidateEvidenceKey =
                Objects.requireNonNull(
                        candidateEvidenceKey,
                        "candidateEvidenceKey");
        attestation = object(
                attestation, "attestation");
        attestationKey = Objects.requireNonNull(
                attestationKey, "attestationKey");
        verificationTime = Objects.requireNonNull(
                verificationTime, "verificationTime");
    }

    /**
     * Parses one exact public-only durable worker compatibility envelope.
     *
     * @param value untrusted decoded fixture JSON
     * @return defensively copied typed fixture
     */
    public static OnlineReadOnlyShadowWorkerCompatibilityFixture
    from(JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "verificationKeys",
                        "expected",
                        "request",
                        "job",
                        "lifecyclePage",
                        "comparison",
                        "baselineCommand",
                        "baselineObservation",
                        "candidateCommand",
                        "candidateEvidenceBundle",
                        "attestation"),
                "fixture");
        if (!SCHEMA_VERSION.equals(
                value.path("schemaVersion")
                        .asText())) {
            throw new IllegalArgumentException(
                    "Online worker fixture schemaVersion is invalid");
        }
        JsonNode keys =
                value.path("verificationKeys");
        requireFields(
                keys,
                Set.of(
                        "comparison",
                        "baselineObservation",
                        "candidateEvidence",
                        "sourceResolution"),
                "verificationKeys");
        JsonNode expected =
                value.path("expected");
        requireFields(
                expected,
                Set.of(
                        "scope",
                        "jobId",
                        "requestFingerprint",
                        "comparisonRef",
                        "sourceResolutionAttestationRef",
                        "lifecycleNextSequence"),
                "expected");
        return new OnlineReadOnlyShadowWorkerCompatibilityFixture(
                expected.path("scope"),
                expected.path("jobId").asText(),
                expected.path(
                        "requestFingerprint").asText(),
                expected.path("comparisonRef"),
                expected.path(
                        "sourceResolutionAttestationRef"),
                expected.path(
                        "lifecycleNextSequence")
                        .asLong(-1),
                value.path("request"),
                value.path("job"),
                value.path("lifecyclePage"),
                value.path("comparison"),
                key(keys.path("comparison")),
                value.path("baselineCommand"),
                value.path("baselineObservation"),
                key(keys.path(
                        "baselineObservation")),
                value.path("candidateCommand"),
                value.path(
                        "candidateEvidenceBundle"),
                key(keys.path(
                        "candidateEvidence")),
                value.path("attestation"),
                key(keys.path(
                        "sourceResolution")),
                instant(
                        value.path("verificationTime"),
                        "verificationTime"));
    }

    /**
     * Independently verifies every artifact and their complete durable execution closure.
     *
     * @return bounded payload-free verification result
     */
    public VerificationResult verify() {
        Coordinates coordinates =
                Coordinates.from(this);
        if (!separateAuthorityKeys()) {
            return result(
                    Outcome.CLOSURE_INVALID,
                    "ONLINE_WORKER_AUTHORITY_ROLES_INVALID",
                    coordinates,
                    false);
        }
        ReadOnlyShadowJobVerifier.VerificationResult
                verifiedJob =
                new ReadOnlyShadowJobVerifier()
                        .verify(
                                job,
                                request,
                                comparison,
                                comparisonKey);
        if (!verifiedJob.verified()) {
            return result(
                    Outcome.JOB_INVALID,
                    "ONLINE_WORKER_"
                            + verifiedJob.reasonCode(),
                    coordinates,
                    false);
        }
        ReadOnlyShadowLifecycleVerifier
                .VerificationResult verifiedLifecycle =
                new ReadOnlyShadowLifecycleVerifier()
                        .verify(job, lifecyclePage);
        if (!verifiedLifecycle.complete()) {
            return result(
                    Outcome.LIFECYCLE_INVALID,
                    "ONLINE_WORKER_"
                            + verifiedLifecycle.reasonCode(),
                    coordinates,
                    false);
        }
        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                .VerificationResult verifiedSources =
                new OnlineReadOnlyShadowSourceResolutionAttestationVerifier()
                        .verify(
                                attestation,
                                attestationKey,
                                sourceContext());
        if (!verifiedSources.verified()) {
            return result(
                    Outcome.SOURCE_RESOLUTION_INVALID,
                    "ONLINE_WORKER_"
                            + verifiedSources.reasonCode(),
                    coordinates,
                    false);
        }
        String closureReason = closureReason();
        if (!closureReason.isEmpty()) {
            return result(
                    Outcome.CLOSURE_INVALID,
                    closureReason,
                    coordinates,
                    false);
        }
        return result(
                Outcome.VERIFIED,
                "VERIFIED",
                coordinates,
                verifiedSources.zeroWrite());
    }

    /** Closed durable online worker verification outcomes. */
    public enum Outcome {
        /** Every artifact, authority, and cross-artifact closure verified. */
        VERIFIED,
        /** The durable job, immutable request, or terminal comparison is invalid. */
        JOB_INVALID,
        /** The append-only lifecycle does not completely close the terminal job. */
        LIFECYCLE_INVALID,
        /** One online source, command, proof, or authority signature is invalid. */
        SOURCE_RESOLUTION_INVALID,
        /** Individually valid artifacts do not describe one exact execution. */
        CLOSURE_INVALID
    }

    /**
     * Log-safe result for CI and governance admission.
     *
     * @param outcome closed verification outcome
     * @param reasonCode stable machine-readable reason
     * @param jobId durable job identity when available
     * @param requestFingerprint immutable request fingerprint when available
     * @param comparisonFingerprint terminal comparison fingerprint when available
     * @param attestationFingerprint source-resolution proof fingerprint when available
     * @param lifecycleNextSequence terminal lifecycle cursor when available
     * @param zeroWrite whether the complete verified chain proves zero write
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String jobId,
            String requestFingerprint,
            String comparisonFingerprint,
            String attestationFingerprint,
            long lifecycleNextSequence,
            boolean zeroWrite
    ) {
        /** Normalizes one bounded result without retaining untrusted payloads or diagnostics. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = reason(reasonCode);
            jobId = bounded(jobId, 512);
            requestFingerprint =
                    fingerprintOptional(
                            requestFingerprint);
            comparisonFingerprint =
                    fingerprintOptional(
                            comparisonFingerprint);
            attestationFingerprint =
                    fingerprintOptional(
                            attestationFingerprint);
            lifecycleNextSequence =
                    Math.max(
                            0L,
                            lifecycleNextSequence);
        }

        /**
         * Reports whether the complete public-only durable worker chain verified.
         *
         * @return {@code true} only for a zero-write verified chain
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED
                    && zeroWrite;
        }
    }

    /**
     * Returns an independently owned expected enterprise scope.
     *
     * @return defensive scope copy
     */
    @Override
    public JsonNode expectedScope() {
        return expectedScope.deepCopy();
    }

    /**
     * Returns an independently owned expected comparison reference.
     *
     * @return defensive comparison-reference copy
     */
    @Override
    public JsonNode expectedComparisonRef() {
        return expectedComparisonRef.deepCopy();
    }

    /**
     * Returns an independently owned expected source-resolution reference.
     *
     * @return defensive source-proof-reference copy
     */
    @Override
    public JsonNode expectedAttestationRef() {
        return expectedAttestationRef.deepCopy();
    }

    /**
     * Returns an independently owned immutable request.
     *
     * @return defensive immutable-request copy
     */
    @Override
    public JsonNode request() {
        return request.deepCopy();
    }

    /**
     * Returns an independently owned terminal job.
     *
     * @return defensive terminal-job copy
     */
    @Override
    public JsonNode job() {
        return job.deepCopy();
    }

    /**
     * Returns an independently owned complete lifecycle page.
     *
     * @return defensive complete-lifecycle-page copy
     */
    @Override
    public JsonNode lifecyclePage() {
        return lifecyclePage.deepCopy();
    }

    /**
     * Returns an independently owned signed comparison.
     *
     * @return defensive signed-comparison copy
     */
    @Override
    public JsonNode comparison() {
        return comparison.deepCopy();
    }

    /**
     * Returns an independently owned regional baseline command.
     *
     * @return defensive regional-baseline-command copy
     */
    @Override
    public JsonNode baselineCommand() {
        return baselineCommand.deepCopy();
    }

    /**
     * Returns an independently owned signed baseline observation.
     *
     * @return defensive signed-baseline-observation copy
     */
    @Override
    public JsonNode baselineObservation() {
        return baselineObservation.deepCopy();
    }

    /**
     * Returns an independently owned same-input candidate command.
     *
     * @return defensive same-input-candidate-command copy
     */
    @Override
    public JsonNode candidateCommand() {
        return candidateCommand.deepCopy();
    }

    /**
     * Returns an independently owned signed candidate evidence bundle.
     *
     * @return defensive signed-candidate-evidence copy
     */
    @Override
    public JsonNode candidateEvidenceBundle() {
        return candidateEvidenceBundle.deepCopy();
    }

    /**
     * Returns an independently owned signed source-resolution proof.
     *
     * @return defensive signed-source-resolution-proof copy
     */
    @Override
    public JsonNode attestation() {
        return attestation.deepCopy();
    }

    OnlineReadOnlyShadowWorkerCompatibilityFixture
    detachedCopy() {
        return new OnlineReadOnlyShadowWorkerCompatibilityFixture(
                expectedScope,
                expectedJobId,
                expectedRequestFingerprint,
                expectedComparisonRef,
                expectedAttestationRef,
                expectedLifecycleNextSequence,
                request,
                job,
                lifecyclePage,
                comparison,
                comparisonKey,
                baselineCommand,
                baselineObservation,
                baselineKey,
                candidateCommand,
                candidateEvidenceBundle,
                candidateEvidenceKey,
                attestation,
                attestationKey,
                verificationTime);
    }

    private OnlineReadOnlyShadowSourceResolutionAttestationVerifier
            .VerificationContext sourceContext() {
        return new OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                .VerificationContext(
                expectedScope,
                expectedAttestationRef,
                text(request, "requestId"),
                expectedJobId,
                text(
                        baselineCommand,
                        "admissionFingerprint"),
                baselineCommand,
                baselineObservation,
                baselineKey,
                candidateCommand,
                candidateEvidenceBundle,
                candidateEvidenceKey,
                verificationTime);
    }

    private boolean separateAuthorityKeys() {
        List<EvidenceVerificationKey> keys =
                List.of(
                        comparisonKey,
                        baselineKey,
                        candidateEvidenceKey,
                        attestationKey);
        Set<String> keyIds =
                new HashSet<>();
        Set<String> publicKeys =
                new HashSet<>();
        for (EvidenceVerificationKey key : keys) {
            keyIds.add(key.keyId());
            publicKeys.add(
                    key.encodedPublicKey());
        }
        return keyIds.size() == keys.size()
                && publicKeys.size() == keys.size();
    }

    private String closureReason() {
        if (!expectedScope.equals(
                request.path("scope"))
                || !expectedScope.equals(
                job.path("scope"))
                || !expectedScope.equals(
                comparison.path("scope"))
                || !expectedScope.equals(
                attestation.path("scope"))) {
            return "ONLINE_WORKER_SCOPE_CLOSURE_INVALID";
        }
        if (!expectedJobId.equals(
                text(job, "jobId"))
                || !expectedJobId.equals(
                text(
                        comparison,
                        "comparisonId"))
                || !expectedJobId.equals(
                text(
                        attestation,
                        "executionId"))
                || !expectedJobId.equals(
                text(
                        baselineCommand,
                        "executionId"))
                || !expectedJobId.equals(
                text(
                        candidateCommand,
                        "executionId"))) {
            return "ONLINE_WORKER_EXECUTION_CLOSURE_INVALID";
        }
        if (!expectedRequestFingerprint.equals(
                text(
                        job,
                        "requestFingerprint"))
                || !expectedComparisonRef.equals(
                job.path("comparisonRef"))
                || !expectedComparisonRef.equals(
                comparisonRef(comparison))
                || !expectedAttestationRef.equals(
                comparison.path(
                        "sourceResolutionAttestationRef"))
                || !expectedAttestationRef.equals(
                attestationRef(attestation))
                || expectedLifecycleNextSequence
                != lifecyclePage.path(
                        "nextSequence").asLong(-1)) {
            return "ONLINE_WORKER_ARTIFACT_CLOSURE_INVALID";
        }
        if (!sameRequestCoordinates(
                baselineCommand, false)
                || !sameRequestCoordinates(
                candidateCommand, true)
                || !text(
                baselineCommand,
                "admissionFingerprint")
                .equals(text(
                        candidateCommand,
                        "admissionFingerprint"))
                || !baselineCommand.path("admittedAt")
                .equals(
                        candidateCommand.path(
                                "admittedAt"))
                || !baselineCommand.path("deadlineAt")
                .equals(
                        candidateCommand.path(
                                "deadlineAt"))) {
            return "ONLINE_WORKER_COMMAND_CLOSURE_INVALID";
        }
        JsonNode authority =
                comparison.path("authorityProof");
        if (!text(
                baselineCommand,
                "admissionFingerprint")
                .equals(text(
                        attestation,
                        "admissionFingerprint"))
                || !text(
                baselineCommand,
                "admissionFingerprint")
                .equals(text(
                        authority,
                        "admissionFingerprint"))
                || !baselineCommand.path("admittedAt")
                .equals(
                        attestation.path(
                                "admittedAt"))
                || !baselineCommand.path("admittedAt")
                .equals(
                        authority.path(
                                "admittedAt"))
                || !attestation.path("confirmedAt")
                .equals(
                        authority.path(
                                "confirmedAt"))) {
            return "ONLINE_WORKER_AUTHORITY_CLOSURE_INVALID";
        }
        if (!sourceMatches(
                comparison.path("baseline"),
                attestation.path("baseline"))
                || !sourceMatches(
                comparison.path("candidate"),
                attestation.path("candidate"))
                || !text(
                attestation,
                "requestContextFingerprint")
                .equals(text(
                        comparison.path("baseline"),
                        "requestContextFingerprint"))
                || !text(
                attestation,
                "requestContextFingerprint")
                .equals(text(
                        comparison.path("candidate"),
                        "requestContextFingerprint"))) {
            return "ONLINE_WORKER_SOURCE_CLOSURE_INVALID";
        }
        try {
            Instant requestDeadline =
                    instant(
                            request.path("deadlineAt"),
                            "request.deadlineAt");
            Instant sourceDeadline =
                    instant(
                            baselineCommand.path(
                                    "deadlineAt"),
                            "baselineCommand.deadlineAt");
            Instant proofIssuedAt =
                    instant(
                            attestation.path("issuedAt"),
                            "attestation.issuedAt");
            Instant observedAt =
                    instant(
                            comparison.path("observedAt"),
                            "comparison.observedAt");
            Instant completedAt =
                    instant(
                            job.path("completedAt"),
                            "job.completedAt");
            if (sourceDeadline.isAfter(
                    requestDeadline)
                    || proofIssuedAt.isAfter(
                    observedAt)
                    || observedAt.isAfter(
                    completedAt)) {
                return "ONLINE_WORKER_TIME_CLOSURE_INVALID";
            }
        } catch (RuntimeException invalid) {
            return "ONLINE_WORKER_TIME_CLOSURE_INVALID";
        }
        return "";
    }

    private boolean sameRequestCoordinates(
            JsonNode command,
            boolean candidate) {
        return request.path("requestId")
                .equals(command.path("requestId"))
                && request.path("scope")
                .equals(command.path("scope"))
                && request.path("inventoryRef")
                .equals(command.path("inventoryRef"))
                && request.path("unitId")
                .equals(command.path("unitId"))
                && request.path("scenarioCaseRef")
                .equals(command.path("scenarioCaseRef"))
                && request.path("targetCapabilityRef")
                .equals(command.path("targetCapabilityRef"))
                && request.path("comparisonPolicyRef")
                .equals(command.path("comparisonPolicyRef"))
                && request.path("accessGrant")
                .equals(command.path("accessGrant"))
                && (candidate
                ? request.path("candidatePlanRef")
                .equals(command.path(
                        "candidatePlanRef"))
                : request.path("baselineBindingRef")
                .equals(command.path(
                        "baselineBindingRef")));
    }

    private static boolean sourceMatches(
            JsonNode comparisonSource,
            JsonNode proofSource) {
        return comparisonSource.path("artifactRef")
                .equals(proofSource.path("artifactRef"))
                && comparisonSource.path(
                "semanticResultFingerprint")
                .equals(proofSource.path(
                        "semanticResultFingerprint"))
                && comparisonSource.path("completedAt")
                .equals(proofSource.path(
                        "sourceCompletedAt"))
                && comparisonSource.path("evidenceClass")
                .equals(proofSource.path(
                        "evidenceClass"))
                && comparisonSource.path("evidenceComplete")
                .equals(proofSource.path(
                        "evidenceComplete"))
                && !proofSource.path(
                "writeCredentialExposed").asBoolean()
                && proofSource.path(
                "writeAttemptCount").asLong(-1) == 0;
    }

    private static ObjectNode comparisonRef(
            JsonNode value) {
        return artifactRef(
                "FIDELITY_SHADOW_COMPARISON",
                text(value, "comparisonId"),
                value.path("revision"),
                text(
                        value,
                        "comparisonFingerprint"));
    }

    private static ObjectNode attestationRef(
            JsonNode value) {
        return artifactRef(
                "SHADOW_SOURCE_RESOLUTION_ATTESTATION",
                text(value, "attestationId"),
                value.path("revision"),
                text(
                        value,
                        "attestationFingerprint"));
    }

    private static ObjectNode artifactRef(
            String kind,
            String id,
            JsonNode revision,
            String value) {
        ObjectNode ref =
                JsonNodeFactory.instance.objectNode();
        ref.put("kind", kind);
        ref.put("id", id);
        ref.set(
                "revision",
                revision.deepCopy());
        ref.put("fingerprint", value);
        return ref;
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates,
            boolean zeroWrite) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.jobId,
                coordinates.requestFingerprint,
                coordinates.comparisonFingerprint,
                coordinates.attestationFingerprint,
                coordinates.lifecycleNextSequence,
                zeroWrite);
    }

    private static EvidenceVerificationKey key(
            JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "keyId",
                        "algorithm",
                        "encodedPublicKey",
                        "createdAt",
                        "state",
                        "provider"),
                "verificationKey");
        return new EvidenceVerificationKey(
                value.path("schemaVersion")
                        .asText(),
                value.path("keyId").asText(),
                value.path("algorithm").asText(),
                value.path("encodedPublicKey")
                        .asText(),
                instant(
                        value.path("createdAt"),
                        "verificationKey.createdAt"),
                value.path("state").asText(),
                value.path("provider").asText());
    }

    private static void requireFields(
            JsonNode value,
            Set<String> expected,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        HashSet<String> actual =
                new HashSet<>();
        value.fieldNames()
                .forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    field + " fields are invalid");
        }
    }

    private static Instant instant(
            JsonNode value,
            String field) {
        try {
            Instant exact = Instant.parse(
                    value.asText());
            if (Instant.EPOCH.equals(exact)
                    || !exact.toString().equals(
                    value.asText())) {
                throw new IllegalArgumentException(
                        field + " is invalid");
            }
            return exact;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    field + " is invalid",
                    invalid);
        }
    }

    private static JsonNode object(
            JsonNode value,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        return value.deepCopy();
    }

    private static String required(
            String value,
            String field) {
        String exact =
                value == null ? "" : value.trim();
        if (exact.isBlank()
                || exact.length() > 512) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(
            String value,
            String field) {
        String exact =
                value == null ? "" : value.trim();
        if (!exact.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String fingerprintOptional(
            String value) {
        String exact =
                value == null ? "" : value.trim();
        return exact.matches(
                "sha256:[a-f0-9]{64}")
                ? exact : "";
    }

    private static String reason(
            String value) {
        String exact =
                value == null ? "" : value.trim();
        if (!exact.matches(
                "[A-Z][A-Z0-9_.-]{0,254}")) {
            return "ONLINE_WORKER_VERIFICATION_INVALID";
        }
        return exact;
    }

    private static String bounded(
            String value,
            int maximumLength) {
        String exact =
                value == null ? "" : value.trim();
        return exact.length() <= maximumLength
                ? exact
                : exact.substring(0, maximumLength);
    }

    private static String text(
            JsonNode value,
            String field) {
        return value == null
                ? ""
                : value.path(field).asText("");
    }

    private record Coordinates(
            String jobId,
            String requestFingerprint,
            String comparisonFingerprint,
            String attestationFingerprint,
            long lifecycleNextSequence
    ) {
        private static Coordinates from(
                OnlineReadOnlyShadowWorkerCompatibilityFixture
                        fixture) {
            return new Coordinates(
                    fixture.expectedJobId,
                    fixture.expectedRequestFingerprint,
                    text(
                            fixture.expectedComparisonRef,
                            "fingerprint"),
                    text(
                            fixture.expectedAttestationRef,
                            "fingerprint"),
                    fixture.expectedLifecycleNextSequence);
        }
    }
}
