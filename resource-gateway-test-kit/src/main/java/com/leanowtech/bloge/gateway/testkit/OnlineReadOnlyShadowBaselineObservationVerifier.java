package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;

/**
 * Dependency-light hostile-input verifier for one regional online baseline observation.
 *
 * <p>The verifier links no Resource Gateway server or Spring class. It independently validates
 * the strict command and observation schemas, command content address, source idempotency,
 * command-to-observation coordinate closure, deterministic observation identity, complete
 * observation content address, authority key policy, signing window, and Ed25519 seal. Its result
 * is payload-free and distinguishes authentic evidence from the separately reported zero-write
 * safety outcome.</p>
 */
public final class OnlineReadOnlyShadowBaselineObservationVerifier {
    /** Maximum canonical online baseline command bytes. */
    public static final int MAXIMUM_COMMAND_BYTES = 128 * 1024;
    /** Maximum canonical online baseline observation bytes. */
    public static final int MAXIMUM_OBSERVATION_BYTES = 512 * 1024;
    /** Maximum canonical deterministic identity bytes. */
    public static final int MAXIMUM_IDENTITY_BYTES = 16 * 1024;
    /** Maximum canonical source-idempotency bytes. */
    public static final int MAXIMUM_IDEMPOTENCY_BYTES = 8 * 1024;
    /** Maximum canonical authority-signature material bytes. */
    public static final int MAXIMUM_SEAL_MATERIAL_BYTES = 16 * 1024;

    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(1);
    private static final Duration KEY_CREATION_SKEW =
            Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBSERVATION_KIND =
            "SHADOW_BASELINE_OBSERVATION";
    private static final String OBSERVATION_ID_PREFIX =
            "online-baseline-";
    private static final String OBSERVATION_ID_DOMAIN =
            "RESOURCE_GATEWAY_ONLINE_READ_ONLY_SHADOW_BASELINE_OBSERVATION_ID_V1";
    private static final String IDEMPOTENCY_DOMAIN =
            "RESOURCE_GATEWAY_ONLINE_READ_ONLY_SHADOW_BASELINE_IDEMPOTENCY_V1";
    private static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_ONLINE_READ_ONLY_SHADOW_BASELINE_OBSERVATION_V1";

    /** Creates a stateless standalone verifier. */
    public OnlineReadOnlyShadowBaselineObservationVerifier() {
    }

    /** Closed online-baseline verification outcomes. */
    public enum Outcome {
        /** Every schema, identity, coordinate, time, key, content, and signature check passed. */
        VERIFIED,
        /** Structure, temporal semantics, content address, identity, or signature is invalid. */
        INVALID,
        /** The authenticated command or expected artifact coordinates do not match the evidence. */
        EXPECTATION_MISMATCH,
        /** No exact regional observation authority key was supplied. */
        KEY_UNAVAILABLE,
        /** Signature algorithm or authority-key lifecycle policy rejected the evidence. */
        POLICY_REJECTED,
        /** The evidence is from the future relative to the trusted consumer clock. */
        WINDOW_REJECTED
    }

    /**
     * Authenticated consumer coordinates for one exact regional baseline invocation.
     *
     * @param command exact strict sidecar command sent for the invocation
     * @param expectedObservationRef exact observation artifact coordinates expected by the caller
     * @param verificationTime trusted consumer verification clock
     */
    public record VerificationContext(
            JsonNode command,
            JsonNode expectedObservationRef,
            Instant verificationTime
    ) {
        /** Defensively copies exact command and artifact-reference coordinates. */
        public VerificationContext {
            command = object(command, "command");
            expectedObservationRef = object(
                    expectedObservationRef,
                    "expectedObservationRef");
            verificationTime = Objects.requireNonNull(
                    verificationTime,
                    "verificationTime");
        }

        /**
         * Returns a defensive copy of the exact sidecar command.
         *
         * @return detached command JSON
         */
        @Override
        public JsonNode command() {
            return command.deepCopy();
        }

        /**
         * Returns a defensive copy of the expected artifact reference.
         *
         * @return detached exact observation reference
         */
        @Override
        public JsonNode expectedObservationRef() {
            return expectedObservationRef.deepCopy();
        }
    }

    /**
     * Bounded payload-free result suitable for CI, evidence admission, and governance logs.
     *
     * @param outcome closed verification outcome
     * @param reasonCode stable machine-readable reason
     * @param observationId deterministic observation identity, or blank
     * @param revision immutable observation revision, or zero
     * @param observationFingerprint complete observation content address, or blank
     * @param executionId stable source execution identity, or blank
     * @param requestId durable Resource Gateway request identity, or blank
     * @param keyId regional observation authority key id, or blank
     * @param zeroWrite whether authenticated evidence proves no write credential or write attempt
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String observationId,
            long revision,
            String observationFingerprint,
            String executionId,
            String requestId,
            String keyId,
            boolean zeroWrite
    ) {
        /** Normalizes one log-safe verification result. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = reason(reasonCode);
            observationId = boundedOptional(
                    observationId, 512);
            observationFingerprint = fingerprintOptional(
                    observationFingerprint);
            executionId = boundedOptional(
                    executionId, 512);
            requestId = boundedOptional(
                    requestId, 512);
            keyId = boundedOptional(keyId, 255);
            if (revision < 0) {
                throw new IllegalArgumentException(
                        "online baseline verification revision is invalid");
            }
        }

        /**
         * Reports whether every independent authenticity and closure check passed.
         *
         * @return true only for a completely verified observation
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one regional observation against its exact command and public key.
     *
     * @param observation untrusted decoded online baseline observation
     * @param key independently resolved regional observation authority key; may be {@code null}
     * @param context exact authenticated command, artifact reference, and consumer time
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode observation,
            EvidenceVerificationKey key,
            VerificationContext context) {
        Coordinates coordinates =
                Coordinates.from(observation);
        if (context == null) {
            return result(
                    Outcome.EXPECTATION_MISMATCH,
                    "ONLINE_BASELINE_CONTEXT_UNAVAILABLE",
                    coordinates);
        }
        try {
            CapabilityMirrorSchemaValidator.require(
                    context.command(),
                    CapabilityMirrorProtocol
                            .ONLINE_READ_ONLY_SHADOW_BASELINE_COMMAND_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.ONLINE_BASELINE_COMMAND_SCHEMA_INVALID");
            CapabilityMirrorSchemaValidator.require(
                    observation,
                    CapabilityMirrorProtocol
                            .ONLINE_READ_ONLY_SHADOW_BASELINE_OBSERVATION_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.ONLINE_BASELINE_OBSERVATION_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_BASELINE_SCHEMA_INVALID",
                    coordinates);
        }

        JsonNode command = context.command();
        try {
            requireTemporalClosure(
                    command, observation);
            String commandFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            command,
                            MAXIMUM_COMMAND_BYTES);
            String idempotencyFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            idempotencyMaterial(command),
                            MAXIMUM_IDEMPOTENCY_BYTES);
            if (!matchesCommand(
                    observation,
                    command,
                    commandFingerprint,
                    idempotencyFingerprint)) {
                return result(
                        Outcome.EXPECTATION_MISMATCH,
                        "ONLINE_BASELINE_COMMAND_MISMATCH",
                        coordinates);
            }

            String expectedId = expectedObservationId(
                    observation,
                    commandFingerprint);
            if (!expectedId.equals(
                    observation.path("observationId")
                            .asText())) {
                return result(
                        Outcome.INVALID,
                        "ONLINE_BASELINE_IDENTITY_INVALID",
                        coordinates);
            }
            String observationFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            observationFingerprintMaterial(
                                    observation),
                            MAXIMUM_OBSERVATION_BYTES);
            if (!observationFingerprint.equals(
                    observation.path(
                            "observationFingerprint")
                            .asText())) {
                return result(
                        Outcome.INVALID,
                        "ONLINE_BASELINE_FINGERPRINT_INVALID",
                        coordinates);
            }
            if (!matchesExpectedReference(
                    context.expectedObservationRef(),
                    observation,
                    observationFingerprint)) {
                return result(
                        Outcome.EXPECTATION_MISMATCH,
                        "ONLINE_BASELINE_REFERENCE_MISMATCH",
                        coordinates);
            }
            String materialFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            signatureMaterial(observation),
                            MAXIMUM_SEAL_MATERIAL_BYTES);
            if (!materialFingerprint.equals(
                    observation.at(
                            "/observationSeal/materialFingerprint")
                            .asText())) {
                return result(
                        Outcome.INVALID,
                        "ONLINE_BASELINE_SEAL_MATERIAL_INVALID",
                        coordinates);
            }
            verifyCanonicalBase64(
                    observation.at(
                            "/observationSeal/signature")
                            .asText());
        } catch (VerificationFailure invalid) {
            return result(
                    invalid.outcome,
                    invalid.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_BASELINE_MATERIAL_INVALID",
                    coordinates);
        }

        if (key == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "ONLINE_BASELINE_KEY_UNAVAILABLE",
                    coordinates);
        }
        JsonNode seal = observation.path(
                "observationSeal");
        if (!key.keyId().equals(
                seal.path("keyId").asText())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "ONLINE_BASELINE_KEY_IDENTITY_MISMATCH",
                    coordinates);
        }
        if (!key.verificationAllowed()
                || !"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(
                seal.path("algorithm").asText())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "ONLINE_BASELINE_KEY_POLICY_REJECTED",
                    coordinates);
        }

        Instant signedAt;
        Instant issuedAt;
        try {
            signedAt = instant(
                    seal.path("signedAt"),
                    "observationSeal.signedAt");
            issuedAt = instant(
                    observation.path("issuedAt"),
                    "issuedAt");
        } catch (VerificationFailure invalid) {
            return result(
                    invalid.outcome,
                    invalid.reasonCode,
                    coordinates);
        }
        if (signedAt.isBefore(
                key.createdAt().minus(
                        KEY_CREATION_SKEW))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "ONLINE_BASELINE_KEY_OUTSIDE_VALIDITY",
                    coordinates);
        }
        if (signedAt.isBefore(issuedAt)) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_BASELINE_SIGNING_TIME_INVALID",
                    coordinates);
        }
        if (signedAt.isAfter(
                context.verificationTime().plus(
                        MAXIMUM_CLOCK_SKEW))) {
            return result(
                    Outcome.WINDOW_REJECTED,
                    "ONLINE_BASELINE_FUTURE_EVIDENCE",
                    coordinates);
        }

        try {
            if (!EvidenceVerificationSupport.verifyEd25519(
                    seal.path("materialFingerprint")
                            .asText(),
                    seal.path("signature").asText(),
                    key.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "ONLINE_BASELINE_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates);
        } catch (RuntimeException
                 | GeneralSecurityException invalid) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_BASELINE_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
    }

    private static void requireTemporalClosure(
            JsonNode command,
            JsonNode observation) {
        Instant admittedAt = instant(
                command.path("admittedAt"),
                "command.admittedAt");
        Instant commandDeadline = instant(
                command.path("deadlineAt"),
                "command.deadlineAt");
        Instant startedAt = instant(
                observation.path("startedAt"),
                "startedAt");
        Instant completedAt = instant(
                observation.path("completedAt"),
                "completedAt");
        Instant deadlineAt = instant(
                observation.path("deadlineAt"),
                "deadlineAt");
        Instant identityExpiresAt = instant(
                observation.path(
                        "workloadIdentityExpiresAt"),
                "workloadIdentityExpiresAt");
        Instant issuedAt = instant(
                observation.path("issuedAt"),
                "issuedAt");
        if (!commandDeadline.isAfter(admittedAt)
                || startedAt.isBefore(admittedAt)
                || completedAt.isBefore(startedAt)
                || completedAt.isAfter(commandDeadline)
                || !deadlineAt.equals(commandDeadline)
                || !deadlineAt.isAfter(completedAt)
                || !identityExpiresAt.isAfter(
                completedAt)
                || issuedAt.isBefore(completedAt)) {
            fail(
                    Outcome.INVALID,
                    "ONLINE_BASELINE_TEMPORAL_CLOSURE_INVALID");
        }
    }

    private static boolean matchesCommand(
            JsonNode observation,
            JsonNode command,
            String commandFingerprint,
            String idempotencyFingerprint) {
        return commandFingerprint.equals(
                observation.path("commandFingerprint")
                        .asText())
                && equal(
                observation.path("scope"),
                command.path("scope"))
                && textEqual(
                observation,
                command,
                "executionId")
                && textEqual(
                observation,
                command,
                "requestId")
                && equal(
                observation.path("scenarioCaseRef"),
                command.path("scenarioCaseRef"))
                && equal(
                observation.path("targetCapabilityRef"),
                command.path("targetCapabilityRef"))
                && equal(
                observation.path("baselineBindingRef"),
                command.path("baselineBindingRef"))
                && equal(
                observation.path("comparisonPolicyRef"),
                command.path("comparisonPolicyRef"))
                && equal(
                observation.path("samplingGrantRef"),
                command.at(
                        "/accessGrant/samplingGrantRef"))
                && equal(
                observation.path("egressAuthorityRef"),
                command.at(
                        "/accessGrant/egressAuthorityRef"))
                && equal(
                observation.path("killSwitchRef"),
                command.at(
                        "/accessGrant/killSwitchRef"))
                && "READ_ONLY".equals(
                observation.path("accessMode")
                        .asText())
                && idempotencyFingerprint.equals(
                observation.path(
                        "idempotencyKeyFingerprint")
                        .asText());
    }

    private static boolean matchesExpectedReference(
            JsonNode reference,
            JsonNode observation,
            String fingerprint) {
        return reference.size() == 4
                && OBSERVATION_KIND.equals(
                reference.path("kind").asText())
                && reference.path("id").asText()
                .equals(
                        observation.path(
                                "observationId")
                                .asText())
                && reference.path("revision").asLong()
                == observation.path("revision").asLong()
                && fingerprint.equals(
                reference.path("fingerprint")
                        .asText());
    }

    private static String expectedObservationId(
            JsonNode observation,
            String commandFingerprint) {
        ObjectNode material = JSON.createObjectNode();
        material.put("domain", OBSERVATION_ID_DOMAIN);
        material.set(
                "scope",
                observation.path("scope").deepCopy());
        material.put(
                "executionId",
                observation.path("executionId")
                        .asText());
        material.put(
                "commandFingerprint",
                commandFingerprint);
        material.set(
                "baselineBindingRef",
                observation.path("baselineBindingRef")
                        .deepCopy());
        String fingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        material,
                        MAXIMUM_IDENTITY_BYTES);
        return OBSERVATION_ID_PREFIX
                + fingerprint.substring(
                "sha256:".length());
    }

    private static ObjectNode idempotencyMaterial(
            JsonNode command) {
        ObjectNode material = JSON.createObjectNode();
        material.put("domain", IDEMPOTENCY_DOMAIN);
        material.set(
                "scope",
                command.path("scope").deepCopy());
        material.put(
                "executionId",
                command.path("executionId").asText());
        return material;
    }

    private static ObjectNode
    observationFingerprintMaterial(
            JsonNode observation) {
        ObjectNode material =
                ((ObjectNode) observation).deepCopy();
        material.put("observationFingerprint", "");
        material.remove("observationSeal");
        return material;
    }

    private static ObjectNode signatureMaterial(
            JsonNode observation) {
        ObjectNode material = JSON.createObjectNode();
        material.put("domain", SIGNATURE_DOMAIN);
        material.put(
                "schemaVersion",
                observation.path("schemaVersion")
                        .asText());
        material.put(
                "observationId",
                observation.path("observationId")
                        .asText());
        material.put(
                "revision",
                observation.path("revision").asLong());
        material.set(
                "scope",
                observation.path("scope").deepCopy());
        material.put(
                "issuedAt",
                observation.path("issuedAt").asText());
        material.put(
                "observationFingerprint",
                observation.path(
                        "observationFingerprint")
                        .asText());
        return material;
    }

    private static Instant instant(
            JsonNode value,
            String field) {
        try {
            Instant parsed = Instant.parse(
                    value.asText());
            if (Instant.EPOCH.equals(parsed)
                    || !parsed.toString().equals(
                    value.asText())) {
                fail(
                        Outcome.INVALID,
                        "ONLINE_BASELINE_TIME_INVALID");
            }
            return parsed;
        } catch (DateTimeParseException invalid) {
            fail(
                    Outcome.INVALID,
                    "ONLINE_BASELINE_TIME_INVALID");
            return Instant.EPOCH;
        }
    }

    private static void verifyCanonicalBase64(
            String value) {
        try {
            byte[] decoded =
                    Base64.getDecoder().decode(value);
            if (decoded.length == 0
                    || !value.equals(
                    Base64.getEncoder()
                            .encodeToString(decoded))) {
                fail(
                        Outcome.INVALID,
                        "ONLINE_BASELINE_SIGNATURE_ENCODING_INVALID");
            }
        } catch (IllegalArgumentException invalid) {
            fail(
                    Outcome.INVALID,
                    "ONLINE_BASELINE_SIGNATURE_ENCODING_INVALID");
        }
    }

    private static boolean textEqual(
            JsonNode left,
            JsonNode right,
            String field) {
        return left.path(field).asText()
                .equals(right.path(field).asText());
    }

    private static boolean equal(
            JsonNode left,
            JsonNode right) {
        return left != null && left.equals(right);
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reason,
                coordinates.observationId(),
                coordinates.revision(),
                coordinates.observationFingerprint(),
                coordinates.executionId(),
                coordinates.requestId(),
                coordinates.keyId(),
                coordinates.zeroWrite());
    }

    private static void fail(
            Outcome outcome,
            String reason) {
        throw new VerificationFailure(
                outcome, reason);
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

    private static String reason(String value) {
        String exact = value == null
                ? "" : value.trim();
        if (!exact.matches(
                "[A-Z][A-Z0-9_.-]{0,254}")) {
            throw new IllegalArgumentException(
                    "online baseline verification reason is invalid");
        }
        return exact;
    }

    private static String boundedOptional(
            String value,
            int maximum) {
        String exact = value == null
                ? "" : value.trim();
        if (exact.length() > maximum
                || exact.indexOf('\n') >= 0
                || exact.indexOf('\r') >= 0) {
            return "";
        }
        return exact;
    }

    private static String fingerprintOptional(
            String value) {
        String exact = boundedOptional(value, 71);
        return exact.isEmpty()
                || exact.matches(
                "sha256:[a-f0-9]{64}")
                ? exact : "";
    }

    private record Coordinates(
            String observationId,
            long revision,
            String observationFingerprint,
            String executionId,
            String requestId,
            String keyId,
            boolean zeroWrite
    ) {
        private static Coordinates from(
                JsonNode value) {
            if (value == null) {
                return new Coordinates(
                        "", 0, "", "", "", "", false);
            }
            return new Coordinates(
                    value.path("observationId").asText(),
                    Math.max(
                            0,
                            value.path("revision").asLong()),
                    value.path("observationFingerprint")
                            .asText(),
                    value.path("executionId").asText(),
                    value.path("requestId").asText(),
                    value.at("/observationSeal/keyId")
                            .asText(),
                    !value.path("writeCredentialExposed")
                            .asBoolean(true)
                            && value.path("writeAttemptCount")
                            .asLong(-1) == 0);
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final Outcome outcome;
        private final String reasonCode;

        private VerificationFailure(
                Outcome outcome,
                String reasonCode) {
            super(reasonCode, null, false, false);
            this.outcome = Objects.requireNonNull(
                    outcome, "outcome");
            this.reasonCode = reason(reasonCode);
        }
    }
}
