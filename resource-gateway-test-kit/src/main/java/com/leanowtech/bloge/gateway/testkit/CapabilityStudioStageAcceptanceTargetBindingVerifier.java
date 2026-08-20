package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Verifies the local wire and cross-document portion of CANDENV phase 1.
 *
 * <p>The verifier consumes raw UTF-8 documents so the attestation coordinates can be checked
 * against the exact bytes that were supplied. It deliberately delegates external authority
 * authenticity to two callbacks. Those callbacks receive immutable, payload-free facts and never
 * receive the Stage Result or any other document.</p>
 */
public final class CapabilityStudioStageAcceptanceTargetBindingVerifier {
    /** Maximum size of each new CANDENV attestation or binding document before parsing. */
    public static final int MAXIMUM_DOCUMENT_BYTES = 1024 * 1024;
    /** Stage Result v2 keeps its established four MiB wire limit. */
    public static final int MAXIMUM_STAGE_RESULT_BYTES =
            CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES;
    /** Alias for callers that name the target binding limit explicitly. */
    public static final int MAXIMUM_TARGET_BINDING_BYTES = MAXIMUM_DOCUMENT_BYTES;
    /** Alias for callers that name the Candidate Attestation limit explicitly. */
    public static final int MAXIMUM_CANDIDATE_ATTESTATION_BYTES = MAXIMUM_DOCUMENT_BYTES;
    /** Alias for callers that name the Environment Attestation limit explicitly. */
    public static final int MAXIMUM_ENVIRONMENT_ATTESTATION_BYTES = MAXIMUM_DOCUMENT_BYTES;

    /** Candidate Attestation v1 schema version. */
    public static final String CANDIDATE_ATTESTATION_SCHEMA_VERSION =
            "resource-gateway.capability-studio.candidate-attestation.v1";
    /** Environment Attestation v1 schema version. */
    public static final String ENVIRONMENT_ATTESTATION_SCHEMA_VERSION =
            "resource-gateway.capability-studio.environment-attestation.v1";
    /** Stage Acceptance Target Binding v1 schema version. */
    public static final String TARGET_BINDING_SCHEMA_VERSION =
            "resource-gateway.capability-studio.stage-acceptance-target-binding.v1";
    /** Stable reason-code prefix for this verifier. */
    public static final String CODE_PREFIX = "RG.CAPABILITY_STUDIO.CANDENV.";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SAFE_REF = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    private static final Pattern SOURCE_COMMIT = Pattern.compile("[A-Fa-f0-9]{7,64}");
    private static final ObjectMapper JSON = new ObjectMapper(strictFactory());
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();

    /** Final local verification outcomes. */
    public enum Outcome {
        /** Every local and callback check passed. */
        VERIFIED,
        /** A deterministic protocol or binding fact was rejected. */
        REJECTED,
        /** A required external authority callback could not decide. */
        BLOCKED
    }

    /**
     * Typed result that contains no input documents or parser detail.
     *
     * @param outcome final local verification outcome
     * @param checks names of checks that completed successfully
     * @param reasonCode stable redacted protocol reason code, if not verified
     */
    public record VerificationResult(Outcome outcome, Set<String> checks, String reasonCode) {
        /** Validates and snapshots the public result boundary. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome is required");
            checks = checks == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(checks));
            if (reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("reasonCode is not a protocol code");
            }
        }

        /**
         * Returns whether the complete CANDENV local contract passed.
         *
         * @return {@code true} when verification passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED && reasonCode == null;
        }

        /**
         * Returns whether the input was deterministically rejected.
         *
         * @return {@code true} when verification rejected the input
         */
        public boolean rejected() {
            return outcome == Outcome.REJECTED;
        }

        /**
         * Returns whether verification was blocked by an unavailable authority.
         *
         * @return {@code true} when an authority decision was unavailable
         */
        public boolean blocked() {
            return outcome == Outcome.BLOCKED;
        }

        /**
         * Returns the final local verification outcome.
         *
         * @return final outcome
         */
        public Outcome status() {
            return outcome;
        }

        /** Redacted representation suitable for logs. */
        @Override
        public String toString() {
            return "VerificationResult[outcome=" + outcome
                    + ", checks=" + checks.size()
                    + ", reasonCode=" + reasonCode + "]";
        }
    }

    /**
     * Status returned by either external authority callback.
     *
     * @param status callback outcome
     * @param reasonCode callback-local reason, reduced to a protocol-safe value
     */
    public record AuthorityDecision(Decision status, String reasonCode) {
        /** Closed authority decision vocabulary. */
        public enum Decision {
            /** The authority verified the typed attestation facts. */
            VERIFIED,
            /** The authority deterministically rejected the typed facts. */
            REJECTED,
            /** The authority could not make a decision. */
            UNAVAILABLE
        }

        /** Validates the decision without retaining untrusted detail in verifier output. */
        public AuthorityDecision {
            status = Objects.requireNonNull(status, "status is required");
            reasonCode = reasonCode == null || reasonCode.isBlank()
                    ? status.name()
                    : reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")
                    ? reasonCode : status.name();
        }

        /**
         * Creates a verified callback decision.
         *
         * @return a verified decision
         */
        public static AuthorityDecision verified() {
            return new AuthorityDecision(Decision.VERIFIED, "VERIFIED");
        }

        /**
         * Creates a rejected callback decision.
         *
         * @return a rejected decision
         */
        public static AuthorityDecision rejected() {
            return new AuthorityDecision(Decision.REJECTED, "REJECTED");
        }

        /**
         * Creates an unavailable callback decision.
         *
         * @return an unavailable decision
         */
        public static AuthorityDecision unavailable() {
            return new AuthorityDecision(Decision.UNAVAILABLE, "UNAVAILABLE");
        }

        /** Redacted representation that omits callback detail. */
        @Override
        public String toString() {
            return "AuthorityDecision[status=" + status + "]";
        }
    }

    /** Candidate Authority callback. It receives Candidate facts only. */
    @FunctionalInterface
    public interface CandidateAuthority {
        /**
         * Verifies external authority for one candidate attestation.
         *
         * <p>For {@link AuthorityDecision.Decision#VERIFIED}, the callback must verify the
         * detached signature, issuer, scope, key-set, TTL, and revocation state against the
         * raw-coordinate facts. This callback is not formal authority acceptance without
         * Provider atomic binding.</p>
         *
         * @param facts immutable candidate facts
         * @return a non-null typed authority decision
         */
        AuthorityDecision verify(CandidateAttestationFacts facts);
    }

    /** Environment Authority callback. It receives Environment facts only. */
    @FunctionalInterface
    public interface EnvironmentAuthority {
        /**
         * Verifies external authority for one environment attestation.
         *
         * <p>For {@link AuthorityDecision.Decision#VERIFIED}, the callback must verify the
         * detached signature, issuer, scope, key-set, TTL, and revocation state against the
         * raw-coordinate facts. This callback is not formal authority acceptance without
         * Provider atomic binding.</p>
         *
         * @param facts immutable environment facts
         * @return a non-null typed authority decision
         */
        AuthorityDecision verify(EnvironmentAttestationFacts facts);
    }

    /**
     * Exact immutable candidate attestation coordinate.
     *
     * @param candidateRef immutable candidate reference
     * @param attestationRevision candidate attestation revision
     * @param fingerprint SHA-256 fingerprint of the raw attestation bytes
     */
    public record CandidateCoordinate(String candidateRef, long attestationRevision,
                                      String fingerprint) {
        /** Validates a candidate coordinate. */
        public CandidateCoordinate {
            candidateRef = requiredRef(candidateRef, "candidateRef");
            if (attestationRevision < 1) {
                throw new IllegalArgumentException("attestationRevision must be positive");
            }
            fingerprint = requiredFingerprint(fingerprint, "fingerprint");
        }

        /** Redacted representation of an external coordinate. */
        @Override
        public String toString() {
            return "CandidateCoordinate[attestationRevision=" + attestationRevision
                    + ", fingerprint=REDACTED]";
        }
    }

    /**
     * Exact immutable environment attestation coordinate.
     *
     * @param environmentRef immutable environment reference
     * @param attestationRevision environment attestation revision
     * @param fingerprint SHA-256 fingerprint of the raw attestation bytes
     */
    public record EnvironmentCoordinate(String environmentRef, long attestationRevision,
                                        String fingerprint) {
        /** Validates an environment coordinate. */
        public EnvironmentCoordinate {
            environmentRef = requiredRef(environmentRef, "environmentRef");
            if (attestationRevision < 1) {
                throw new IllegalArgumentException("attestationRevision must be positive");
            }
            fingerprint = requiredFingerprint(fingerprint, "fingerprint");
        }

        /** Redacted representation of an external coordinate. */
        @Override
        public String toString() {
            return "EnvironmentCoordinate[attestationRevision=" + attestationRevision
                    + ", fingerprint=REDACTED]";
        }
    }

    /**
     * Immutable exact reference used by attestation facts.
     *
     * @param exactRef immutable content reference
     * @param fingerprint SHA-256 fingerprint of the referenced content
     */
    public record ExactReference(String exactRef, String fingerprint) {
        /** Validates an exact content reference. */
        public ExactReference {
            exactRef = requiredRef(exactRef, "exactRef");
            fingerprint = requiredFingerprint(fingerprint, "fingerprint");
        }

        /** Redacted representation of an exact external reference. */
        @Override
        public String toString() {
            return "ExactReference[fingerprint=REDACTED]";
        }
    }

    /**
     * Immutable execution admission window.
     *
     * @param from inclusive start instant
     * @param through inclusive end instant
     */
    public record AdmissionWindow(Instant from, Instant through) {
        /** Validates the window ordering. */
        public AdmissionWindow {
            from = Objects.requireNonNull(from, "from is required");
            through = Objects.requireNonNull(through, "through is required");
            if (through.isBefore(from)) {
                throw new IllegalArgumentException("admission window is inverted");
            }
        }
    }

    /**
     * Immutable payload-free facts supplied to the Candidate Authority.
     *
     * @param coordinate raw attestation coordinate
     * @param buildRef candidate build reference
     * @param revision candidate revision
     * @param sourceCommit source commit
     * @param sourceTreeStatus source tree status
     * @param artifactDigest candidate artifact digest
     * @param baselineRef exact baseline reference
     * @param demoPackRef exact demo pack reference
     * @param executionIntentFingerprint execution intent fingerprint
     * @param scope authority scope
     * @param role authority role
     * @param issuer authority issuer
     * @param issuedAt attestation issue time
     * @param expiresAt attestation expiry time
     */
    public record CandidateAttestationFacts(
            CandidateCoordinate coordinate,
            String buildRef,
            String revision,
            String sourceCommit,
            String sourceTreeStatus,
            String artifactDigest,
            ExactReference baselineRef,
            ExactReference demoPackRef,
            String executionIntentFingerprint,
            String scope,
            String role,
            String issuer,
            Instant issuedAt,
            Instant expiresAt) {
        /** Validates and snapshots candidate facts. */
        public CandidateAttestationFacts {
            coordinate = Objects.requireNonNull(coordinate, "coordinate is required");
            buildRef = requiredRef(buildRef, "buildRef");
            revision = requiredRef(revision, "revision");
            sourceCommit = requiredSourceCommit(sourceCommit);
            sourceTreeStatus = "CLEAN".equals(sourceTreeStatus)
                    ? sourceTreeStatus : invalid("sourceTreeStatus");
            artifactDigest = requiredFingerprint(artifactDigest, "artifactDigest");
            baselineRef = Objects.requireNonNull(baselineRef, "baselineRef is required");
            demoPackRef = Objects.requireNonNull(demoPackRef, "demoPackRef is required");
            executionIntentFingerprint = requiredFingerprint(
                    executionIntentFingerprint, "executionIntentFingerprint");
            scope = requiredRef(scope, "scope");
            role = "CANDIDATE_AUTHORITY".equals(role) ? role : invalid("role");
            issuer = requiredRef(issuer, "issuer");
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt is required");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("candidate attestation window is invalid");
            }
        }

        /** Redacted representation without candidate coordinates or issuer details. */
        @Override
        public String toString() {
            return "CandidateAttestationFacts[coordinate=REDACTED, role=" + role + "]";
        }
    }

    /**
     * Immutable payload-free facts supplied to the Environment Authority.
     *
     * @param coordinate raw attestation coordinate
     * @param executionLeaseId exact execution lease
     * @param candidateAttestation exact Candidate Attestation coordinate
     * @param environmentFingerprint environment fingerprint
     * @param targetProfile target profile
     * @param scope authority scope
     * @param region target region
     * @param runtimeIdentity runtime identity
     * @param networkPolicy network policy
     * @param featureFlagsRef payload-free exact feature-flag reference
     * @param logicalClock environment logical clock
     * @param admissionWindow execution admission window
     * @param trustedTargetIdentities trusted target identities
     * @param role authority role
     * @param issuer authority issuer
     * @param issuedAt attestation issue time
     * @param expiresAt attestation expiry time
     */
    public record EnvironmentAttestationFacts(
            EnvironmentCoordinate coordinate,
            String executionLeaseId,
            CandidateCoordinate candidateAttestation,
            String environmentFingerprint,
            String targetProfile,
            String scope,
            String region,
            String runtimeIdentity,
            String networkPolicy,
            ExactReference featureFlagsRef,
            Instant logicalClock,
            AdmissionWindow admissionWindow,
            Set<String> trustedTargetIdentities,
            String role,
            String issuer,
            Instant issuedAt,
            Instant expiresAt) {
        /** Validates and defensively snapshots environment facts. */
        public EnvironmentAttestationFacts {
            coordinate = Objects.requireNonNull(coordinate, "coordinate is required");
            executionLeaseId = requiredRef(executionLeaseId, "executionLeaseId");
            candidateAttestation = Objects.requireNonNull(
                    candidateAttestation, "candidateAttestation is required");
            environmentFingerprint = requiredFingerprint(
                    environmentFingerprint, "environmentFingerprint");
            targetProfile = requiredRef(targetProfile, "targetProfile");
            scope = requiredRef(scope, "scope");
            region = requiredRef(region, "region");
            runtimeIdentity = requiredRef(runtimeIdentity, "runtimeIdentity");
            networkPolicy = requiredRef(networkPolicy, "networkPolicy");
            featureFlagsRef = Objects.requireNonNull(
                    featureFlagsRef, "featureFlagsRef is required");
            logicalClock = Objects.requireNonNull(logicalClock, "logicalClock is required");
            admissionWindow = Objects.requireNonNull(
                    admissionWindow, "admissionWindow is required");
            trustedTargetIdentities = immutableRefs(
                    trustedTargetIdentities, "trustedTargetIdentities");
            role = "ENVIRONMENT_AUTHORITY".equals(role) ? role : invalid("role");
            issuer = requiredRef(issuer, "issuer");
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt is required");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("environment attestation window is invalid");
            }
        }

        /** Redacted representation without environment coordinates or issuer details. */
        @Override
        public String toString() {
            return "EnvironmentAttestationFacts[coordinate=REDACTED, role=" + role
                    + ", featureFlagsRef=REDACTED]";
        }
    }

    /**
     * External pin used to bind a result to one execution lease and target identity set.
     *
     * @param executionLeaseId expected execution lease
     * @param trustedTargetIdentities expected target identities
     * @param expectedTargetBindingFingerprint deployment-owned target-binding fingerprint
     */
    public record VerificationContext(String executionLeaseId,
                                      Set<String> trustedTargetIdentities,
                                      String expectedTargetBindingFingerprint) {
        /** Validates and snapshots the required deployment pin. */
        public VerificationContext {
            executionLeaseId = requiredRef(executionLeaseId, "executionLeaseId");
            trustedTargetIdentities = immutableRefs(
                    trustedTargetIdentities, "trustedTargetIdentities");
            if (trustedTargetIdentities.isEmpty()) {
                throw new IllegalArgumentException("trustedTargetIdentities is empty");
            }
            expectedTargetBindingFingerprint = requiredFingerprint(
                    expectedTargetBindingFingerprint, "expectedTargetBindingFingerprint");
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioStageAcceptanceTargetBindingVerifier() {
    }

    /**
     * Rejects the convenience form that lacks a deployment pin.
     *
     * @param stageResultBytes raw Stage Acceptance Result v2
     * @param targetBindingBytes raw Target Binding v1
     * @param candidateAttestationBytes raw Candidate Attestation v1
     * @param environmentAttestationBytes raw Environment Attestation v1
     * @param candidateAuthority external Candidate Authority callback
     * @param environmentAuthority external Environment Authority callback
     * @return redacted verification result
     */
    public VerificationResult verify(
            byte[] stageResultBytes,
            byte[] targetBindingBytes,
            byte[] candidateAttestationBytes,
            byte[] environmentAttestationBytes,
            CandidateAuthority candidateAuthority,
            EnvironmentAuthority environmentAuthority) {
        return blocked("TARGET_BINDING", "CONTEXT_UNAVAILABLE");
    }

    /**
     * Verifies four raw UTF-8 documents against an explicit clock and deployment pin.
     *
     * @param stageResultBytes raw Stage Acceptance Result v2
     * @param targetBindingBytes raw Target Binding v1
     * @param candidateAttestationBytes raw Candidate Attestation v1
     * @param environmentAttestationBytes raw Environment Attestation v1
     * @param context external lease and target identity pin
     * @param now trusted verification clock
     * @param candidateAuthority external Candidate Authority callback
     * @param environmentAuthority external Environment Authority callback
     * @return redacted verification result
     */
    public VerificationResult verify(
            byte[] stageResultBytes,
            byte[] targetBindingBytes,
            byte[] candidateAttestationBytes,
            byte[] environmentAttestationBytes,
            VerificationContext context,
            Instant now,
            CandidateAuthority candidateAuthority,
            EnvironmentAuthority environmentAuthority) {
        if (now == null) {
            return rejected("CLOCK", "CLOCK_INVALID");
        }
        if (context == null) {
            return blocked("TARGET_BINDING", "CONTEXT_UNAVAILABLE");
        }

        ParseOutcome stage = parse(stageResultBytes, "STAGE_RESULT_V2",
                MAXIMUM_STAGE_RESULT_BYTES, null);
        if (!stage.valid()) {
            return stage.failure();
        }
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult stageVerification =
                new CapabilityStudioStageAcceptanceResultV2Verifier().verify(stage.value(), now);
        if (!stageVerification.verified()) {
            if ("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_UNAVAILABLE"
                    .equals(stageVerification.errorCode())) {
                return blocked("STAGE_RESULT_V2", "STAGE_RESULT_V2_SCHEMA_UNAVAILABLE");
            }
            return rejected("STAGE_RESULT_V2", "STAGE_RESULT_V2_INVALID");
        }
        JsonNode stageResult = stage.value();
        if (!"PASS".equals(stageResult.path("status").textValue())) {
            return rejected("STAGE_RESULT_V2", "STAGE_RESULT_V2_NOT_PASS");
        }

        ParseOutcome targetBinding = parse(targetBindingBytes, "TARGET_BINDING",
                MAXIMUM_TARGET_BINDING_BYTES,
                CapabilityStudioSchemaSupport.STAGE_ACCEPTANCE_TARGET_BINDING_V1_RESOURCE);
        if (!targetBinding.valid()) {
            return targetBinding.failure();
        }
        ParseOutcome candidate = parse(candidateAttestationBytes, "CANDIDATE_ATTESTATION",
                MAXIMUM_CANDIDATE_ATTESTATION_BYTES,
                CapabilityStudioSchemaSupport.CANDIDATE_ATTESTATION_V1_RESOURCE);
        if (!candidate.valid()) {
            return candidate.failure();
        }
        ParseOutcome environment = parse(environmentAttestationBytes, "ENVIRONMENT_ATTESTATION",
                MAXIMUM_ENVIRONMENT_ATTESTATION_BYTES,
                CapabilityStudioSchemaSupport.ENVIRONMENT_ATTESTATION_V1_RESOURCE);
        if (!environment.valid()) {
            return environment.failure();
        }

        CandidateAttestationFacts candidateFacts;
        EnvironmentAttestationFacts environmentFacts;
        try {
            candidateFacts = candidateFacts(candidate.value(), candidateAttestationBytes);
            environmentFacts = environmentFacts(environment.value(), environmentAttestationBytes);
        } catch (InvalidFacts invalidFacts) {
            return rejected("ATTESTATION_FACTS", invalidFacts.code);
        }

        String candidateRawFingerprint;
        String environmentRawFingerprint;
        try {
            candidateRawFingerprint = rawAttestationFingerprint(candidateAttestationBytes);
            environmentRawFingerprint = rawAttestationFingerprint(environmentAttestationBytes);
        } catch (IllegalArgumentException invalidBytes) {
            return rejected("ATTESTATION_COORDINATE", "ATTESTATION_SIZE_LIMIT");
        }

        VerificationResult coordinate = verifyCoordinates(
                targetBinding.value(), candidateFacts, environmentFacts,
                candidateRawFingerprint, environmentRawFingerprint);
        if (!coordinate.verified()) {
            return coordinate;
        }

        VerificationResult binding = verifyTargetBinding(
                stageResult, targetBinding.value(), environmentFacts, context, now);
        if (!binding.verified()) {
            return binding;
        }
        VerificationResult roles = verifyRoles(candidateFacts, environmentFacts);
        if (!roles.verified()) {
            return roles;
        }
        VerificationResult candidateProjection = verifyCandidateProjection(
                stageResult, candidateFacts);
        if (!candidateProjection.verified()) {
            return candidateProjection;
        }
        VerificationResult environmentProjection = verifyEnvironmentProjection(
                stageResult, candidateFacts, environmentFacts, environmentRawFingerprint);
        if (!environmentProjection.verified()) {
            return environmentProjection;
        }
        VerificationResult window = verifyTimeFacts(
                stageResult, candidateFacts, environmentFacts, now);
        if (!window.verified()) {
            return window;
        }

        VerificationResult candidateAuthorityResult = invokeCandidateAuthority(
                candidateAuthority, candidateFacts);
        if (!candidateAuthorityResult.verified()) {
            return candidateAuthorityResult;
        }
        VerificationResult environmentAuthorityResult = invokeEnvironmentAuthority(
                environmentAuthority, environmentFacts);
        if (!environmentAuthorityResult.verified()) {
            return environmentAuthorityResult;
        }

        return verified(
                "STAGE_RESULT_V2",
                "TARGET_BINDING_SCHEMA",
                "TARGET_BINDING_FINGERPRINT",
                "CANDIDATE_ATTESTATION_SCHEMA",
                "CANDIDATE_ATTESTATION_COORDINATE",
                "ENVIRONMENT_ATTESTATION_SCHEMA",
                "ENVIRONMENT_ATTESTATION_COORDINATE",
                "ROLE_AND_ISSUER_SEPARATION",
                "CANDIDATE_PROJECTION",
                "ENVIRONMENT_PROJECTION",
                "ADMISSION_WINDOW",
                "CANDIDATE_AUTHORITY",
                "ENVIRONMENT_AUTHORITY");
    }

    /**
     * Computes the lowercase SHA-256 coordinate of one raw attestation document.
     *
     * @param rawBytes exact bounded attestation bytes
     * @return lowercase {@code sha256:} digest
     */
    public static String rawAttestationFingerprint(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length > MAXIMUM_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("attestation bytes exceed the protocol limit");
        }
        return sha256(rawBytes);
    }

    /**
     * Computes a raw attestation coordinate using the shorter helper name.
     *
     * @param rawBytes exact bounded attestation bytes
     * @return lowercase {@code sha256:} digest
     */
    public static String attestationFingerprint(byte[] rawBytes) {
        return rawAttestationFingerprint(rawBytes);
    }

    /**
     * Builds the exact canonical message for external target-binding signers.
     *
     * <p>The projection has fixed field order, sorts target identities, and writes the binding
     * {@code fingerprint} as JSON {@code null}. This method only builds the message; it never
     * signs it.</p>
     *
     * @param targetBinding decoded Target Binding v1 object
     * @return compact UTF-8-compatible canonical JSON message
     */
    public static String canonicalMessage(JsonNode targetBinding) {
        try {
            return CANONICAL_JSON.writeValueAsString(canonicalTargetBinding(targetBinding));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("target binding canonical message is invalid");
        }
    }

    /**
     * Builds the canonical target-binding message using the explicit helper name.
     *
     * @param targetBinding decoded Target Binding v1 object
     * @return compact canonical JSON message
     */
    public static String targetBindingCanonicalMessage(JsonNode targetBinding) {
        return canonicalMessage(targetBinding);
    }

    /**
     * Computes the SHA-256 fingerprint of {@link #canonicalMessage(JsonNode)}.
     *
     * @param targetBinding decoded Target Binding v1 object
     * @return lowercase {@code sha256:} digest
     */
    public static String canonicalFingerprint(JsonNode targetBinding) {
        return sha256(canonicalMessage(targetBinding).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes the canonical target-binding fingerprint using the explicit helper name.
     *
     * @param targetBinding decoded Target Binding v1 object
     * @return lowercase {@code sha256:} digest
     */
    public static String targetBindingFingerprint(JsonNode targetBinding) {
        return canonicalFingerprint(targetBinding);
    }

    /** Redacted verifier description. */
    @Override
    public String toString() {
        return "CapabilityStudioStageAcceptanceTargetBindingVerifier[authorities=REDACTED]";
    }

    private static VerificationResult verifyCoordinates(
            JsonNode targetBinding,
            CandidateAttestationFacts candidate,
            EnvironmentAttestationFacts environment,
            String candidateRawFingerprint,
            String environmentRawFingerprint) {
        CandidateCoordinate targetCandidate = candidateCoordinate(
                targetBinding.path("candidateAttestation"));
        EnvironmentCoordinate targetEnvironment = environmentCoordinate(
                targetBinding.path("environmentAttestation"));
        if (targetCandidate == null || targetEnvironment == null) {
            return rejected("ATTESTATION_COORDINATE", "ATTESTATION_COORDINATE_INVALID");
        }
        CandidateCoordinate actualCandidate = candidate.coordinate();
        EnvironmentCoordinate actualEnvironment = environment.coordinate();
        if (!targetCandidate.candidateRef().equals(actualCandidate.candidateRef())
                || targetCandidate.attestationRevision() != actualCandidate.attestationRevision()
                || !targetCandidate.fingerprint().equals(candidateRawFingerprint)) {
            return rejected("CANDIDATE_ATTESTATION_COORDINATE",
                    "CANDIDATE_ATTESTATION_COORDINATE_MISMATCH");
        }
        if (!targetEnvironment.environmentRef().equals(actualEnvironment.environmentRef())
                || targetEnvironment.attestationRevision()
                != actualEnvironment.attestationRevision()
                || !targetEnvironment.fingerprint().equals(environmentRawFingerprint)) {
            return rejected("ENVIRONMENT_ATTESTATION_COORDINATE",
                    "ENVIRONMENT_ATTESTATION_COORDINATE_MISMATCH");
        }
        if (!environment.candidateAttestation().equals(actualCandidate)) {
            return rejected("ENVIRONMENT_ATTESTATION_COORDINATE",
                    "ENVIRONMENT_CANDIDATE_COORDINATE_MISMATCH");
        }
        if (actualCandidate.candidateRef().equals(actualEnvironment.environmentRef())
                || actualCandidate.fingerprint().equals(actualEnvironment.fingerprint())
                || candidateRawFingerprint.equals(environmentRawFingerprint)) {
            return rejected("ATTESTATION_COORDINATE", "ATTESTATION_COORDINATE_COLLISION");
        }
        return verified("CANDIDATE_ATTESTATION_COORDINATE",
                "ENVIRONMENT_ATTESTATION_COORDINATE");
    }

    private static VerificationResult verifyTargetBinding(
            JsonNode stageResult,
            JsonNode targetBinding,
            EnvironmentAttestationFacts environment,
            VerificationContext context,
            Instant now) {
        if (!stageResult.path("resultId").textValue()
                .equals(targetBinding.path("resultId").textValue())
                || stageResult.path("revision").asLong() != targetBinding.path("resultRevision").asLong()
                || !stageResult.path("contractId").textValue()
                .equals(targetBinding.path("contractId").textValue())
                || !stageResult.path("contractRevision").textValue()
                .equals(targetBinding.path("contractRevision").textValue())) {
            return rejected("TARGET_BINDING", "TARGET_BINDING_RESULT_MISMATCH");
        }
        String lease = targetBinding.path("executionLeaseId").textValue();
        if (!context.executionLeaseId().equals(lease)) {
            return rejected("TARGET_BINDING", "TARGET_BINDING_EXECUTION_LEASE_MISMATCH");
        }
        if (!environment.executionLeaseId().equals(lease)) {
            return rejected("TARGET_BINDING", "ENVIRONMENT_EXECUTION_LEASE_MISMATCH");
        }
        Set<String> bindingIdentities = stringSet(targetBinding.path("trustedTargetIdentities"));
        if (bindingIdentities == null || bindingIdentities.isEmpty()) {
            return rejected("TARGET_BINDING", "TARGET_BINDING_IDENTITIES_INVALID");
        }
        if (!context.trustedTargetIdentities().equals(bindingIdentities)) {
            return rejected("TARGET_BINDING", "TARGET_BINDING_IDENTITIES_MISMATCH");
        }
        if (!environment.trustedTargetIdentities().equals(bindingIdentities)) {
            return rejected("TARGET_BINDING", "ENVIRONMENT_TARGET_IDENTITIES_MISMATCH");
        }
        if (!environment.trustedTargetIdentities().equals(
                context.trustedTargetIdentities())) {
            return rejected("TARGET_BINDING", "ENVIRONMENT_CONTEXT_IDENTITIES_MISMATCH");
        }
        if (!environment.trustedTargetIdentities().contains(environment.runtimeIdentity())) {
            return rejected("TARGET_BINDING", "RUNTIME_IDENTITY_NOT_TRUSTED");
        }
        try {
            String documentFingerprint = targetBinding.path("fingerprint").textValue();
            String canonicalFingerprint = canonicalFingerprint(targetBinding);
            if (!documentFingerprint.equals(canonicalFingerprint)) {
                return rejected("TARGET_BINDING", "TARGET_BINDING_FINGERPRINT_MISMATCH");
            }
            if (!context.expectedTargetBindingFingerprint().equals(documentFingerprint)
                    || !context.expectedTargetBindingFingerprint().equals(canonicalFingerprint)) {
                return rejected("TARGET_BINDING",
                        "TARGET_BINDING_DEPLOYMENT_FINGERPRINT_MISMATCH");
            }
        } catch (IllegalArgumentException invalid) {
            return rejected("TARGET_BINDING", "TARGET_BINDING_CANONICALIZATION_FAILED");
        }
        if (now == null) {
            return rejected("TARGET_BINDING", "CLOCK_INVALID");
        }
        return verified("TARGET_BINDING_RESULT_BINDING", "TARGET_BINDING_EXECUTION_LEASE");
    }

    private static VerificationResult verifyRoles(
            CandidateAttestationFacts candidate, EnvironmentAttestationFacts environment) {
        if (candidate.role().equals(environment.role())) {
            return rejected("ROLE_AND_ISSUER_SEPARATION", "ATTESTATION_ROLE_COLLAPSE");
        }
        if (candidate.issuer().equals(environment.issuer())) {
            return rejected("ROLE_AND_ISSUER_SEPARATION", "ATTESTATION_ISSUER_COLLAPSE");
        }
        if (!candidate.scope().equals(environment.scope())) {
            return rejected("ROLE_AND_ISSUER_SEPARATION", "ATTESTATION_SCOPE_MISMATCH");
        }
        return verified("ROLE_AND_ISSUER_SEPARATION");
    }

    private static VerificationResult verifyCandidateProjection(
            JsonNode stageResult, CandidateAttestationFacts candidate) {
        JsonNode binding = stageResult.path("candidateExecutionBinding");
        JsonNode build = binding.path("candidateBuild");
        if (!candidate.buildRef().equals(build.path("buildRef").textValue())
                || !candidate.revision().equals(build.path("revision").textValue())
                || !candidate.sourceCommit().equals(build.path("sourceCommit").textValue())
                || !"CLEAN".equals(build.path("sourceTreeStatus").textValue())
                || !candidate.artifactDigest().equals(build.path("artifactFingerprint").textValue())) {
            return rejected("CANDIDATE_PROJECTION", "CANDIDATE_BUILD_PROJECTION_MISMATCH");
        }
        if (!sameReference(binding.path("baselineRef"), candidate.baselineRef())
                || !sameReference(binding.path("demoPackRef"), candidate.demoPackRef())
                || !candidate.executionIntentFingerprint().equals(
                binding.path("candidateIntentFingerprint").textValue())) {
            return rejected("CANDIDATE_PROJECTION", "CANDIDATE_INTENT_PROJECTION_MISMATCH");
        }
        return verified("CANDIDATE_PROJECTION");
    }

    private static VerificationResult verifyEnvironmentProjection(
            JsonNode stageResult,
            CandidateAttestationFacts candidate,
            EnvironmentAttestationFacts environment,
            String environmentRawFingerprint) {
        JsonNode binding = stageResult.path("candidateExecutionBinding");
        JsonNode projection = stageResult.path("environmentAttestation");
        if (!environment.environmentFingerprint().equals(
                binding.path("environmentFingerprint").textValue())
                || !environment.environmentFingerprint().equals(
                projection.path("environmentFingerprint").textValue())
                || !environment.coordinate().environmentRef()
                .equals(projection.path("exactRef").textValue())
                || !environmentRawFingerprint.equals(projection.path("fingerprint").textValue())
                || !environment.targetProfile().equals(projection.path("profile").textValue())
                || !environment.scope().equals(projection.path("scope").textValue())
                || !environment.networkPolicy().equals(stageResult
                .path("deploymentEgressObservation").path("networkPolicyRef").textValue())
                || !environment.issuer().equals(projection.path("issuer").textValue())
                || !candidate.artifactDigest().equals(
                projection.path("candidateArtifactFingerprint").textValue())
                || !sameInstantText(projection.path("issuedAt"), environment.issuedAt())
                || !sameInstantText(projection.path("expiresAt"), environment.expiresAt())) {
            return rejected("ENVIRONMENT_PROJECTION", "ENVIRONMENT_PROJECTION_MISMATCH");
        }
        return verified("ENVIRONMENT_PROJECTION");
    }

    private static VerificationResult verifyTimeFacts(
            JsonNode stageResult,
            CandidateAttestationFacts candidate,
            EnvironmentAttestationFacts environment,
            Instant now) {
        Instant executionStarted = instant(stageResult.path("candidateExecutionBinding")
                .path("executionStartedAt"));
        Instant evidenceCompleted = instant(stageResult.path("candidateExecutionBinding")
                .path("evidenceCompletedAt"));
        if (executionStarted == null || evidenceCompleted == null) {
            return rejected("ADMISSION_WINDOW", "EXECUTION_WINDOW_INVALID");
        }
        if (!validNow(candidate.issuedAt(), candidate.expiresAt(), now)
                || !validNow(environment.issuedAt(), environment.expiresAt(), now)) {
            return rejected("ADMISSION_WINDOW", "ATTESTATION_NOT_CURRENT");
        }
        if (executionStarted.isBefore(candidate.issuedAt())
                || evidenceCompleted.isAfter(candidate.expiresAt())
                || executionStarted.isBefore(environment.issuedAt())
                || evidenceCompleted.isAfter(environment.expiresAt())) {
            return rejected("ADMISSION_WINDOW", "ATTESTATION_EXECUTION_WINDOW_MISMATCH");
        }
        AdmissionWindow window = environment.admissionWindow();
        if (window.from().isAfter(executionStarted)
                || window.through().isBefore(evidenceCompleted)
                || environment.logicalClock().isAfter(now)) {
            return rejected("ADMISSION_WINDOW", "ADMISSION_WINDOW_MISMATCH");
        }
        return verified("ADMISSION_WINDOW");
    }

    private static VerificationResult invokeCandidateAuthority(
            CandidateAuthority authority, CandidateAttestationFacts facts) {
        if (authority == null) {
            return blocked("CANDIDATE_AUTHORITY", "CANDIDATE_AUTHORITY_UNAVAILABLE");
        }
        AuthorityDecision decision;
        try {
            decision = authority.verify(facts);
        } catch (RuntimeException unavailable) {
            return blocked("CANDIDATE_AUTHORITY", "CANDIDATE_AUTHORITY_UNAVAILABLE");
        }
        if (decision == null || decision.status() == null) {
            return blocked("CANDIDATE_AUTHORITY", "CANDIDATE_AUTHORITY_DECISION_INVALID");
        }
        return switch (decision.status()) {
            case VERIFIED -> verified("CANDIDATE_AUTHORITY");
            case REJECTED -> rejected("CANDIDATE_AUTHORITY", "CANDIDATE_AUTHORITY_REJECTED");
            case UNAVAILABLE -> blocked("CANDIDATE_AUTHORITY",
                    "CANDIDATE_AUTHORITY_UNAVAILABLE");
        };
    }

    private static VerificationResult invokeEnvironmentAuthority(
            EnvironmentAuthority authority, EnvironmentAttestationFacts facts) {
        if (authority == null) {
            return blocked("ENVIRONMENT_AUTHORITY", "ENVIRONMENT_AUTHORITY_UNAVAILABLE");
        }
        AuthorityDecision decision;
        try {
            decision = authority.verify(facts);
        } catch (RuntimeException unavailable) {
            return blocked("ENVIRONMENT_AUTHORITY", "ENVIRONMENT_AUTHORITY_UNAVAILABLE");
        }
        if (decision == null || decision.status() == null) {
            return blocked("ENVIRONMENT_AUTHORITY", "ENVIRONMENT_AUTHORITY_DECISION_INVALID");
        }
        return switch (decision.status()) {
            case VERIFIED -> verified("ENVIRONMENT_AUTHORITY");
            case REJECTED -> rejected("ENVIRONMENT_AUTHORITY", "ENVIRONMENT_AUTHORITY_REJECTED");
            case UNAVAILABLE -> blocked("ENVIRONMENT_AUTHORITY",
                    "ENVIRONMENT_AUTHORITY_UNAVAILABLE");
        };
    }

    private static CandidateAttestationFacts candidateFacts(JsonNode value, byte[] rawBytes) {
        try {
            CandidateCoordinate coordinate = new CandidateCoordinate(
                    text(value, "candidateRef"), longValue(value, "attestationRevision"),
                    rawAttestationFingerprint(rawBytes));
            return new CandidateAttestationFacts(
                    coordinate,
                    text(value, "buildRef"),
                    text(value, "revision"),
                    text(value, "sourceCommit"),
                    text(value, "sourceTreeStatus"),
                    text(value, "artifactDigest"),
                    reference(value.path("baselineRef")),
                    reference(value.path("demoPackRef")),
                    text(value, "executionIntentFingerprint"),
                    text(value, "scope"),
                    text(value, "role"),
                    text(value, "issuer"),
                    instantRequired(value.path("issuedAt"), "issuedAt"),
                    instantRequired(value.path("expiresAt"), "expiresAt"));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidFacts("CANDIDATE_FACTS_INVALID");
        }
    }

    private static EnvironmentAttestationFacts environmentFacts(
            JsonNode value, byte[] rawBytes) {
        try {
            EnvironmentCoordinate coordinate = new EnvironmentCoordinate(
                    text(value, "environmentRef"), longValue(value, "attestationRevision"),
                    rawAttestationFingerprint(rawBytes));
            Set<String> identities = stringSet(value.path("trustedTargetIdentities"));
            if (identities == null || identities.isEmpty()) {
                throw new IllegalArgumentException("trustedTargetIdentities");
            }
            return new EnvironmentAttestationFacts(
                    coordinate,
                    text(value, "executionLeaseId"),
                    candidateCoordinate(value.path("candidateAttestation")),
                    text(value, "environmentFingerprint"),
                    text(value, "targetProfile"),
                    text(value, "scope"),
                    text(value, "region"),
                    text(value, "runtimeIdentity"),
                    text(value, "networkPolicy"),
                    reference(value.path("featureFlagsRef")),
                    instantRequired(value.path("logicalClock"), "logicalClock"),
                    admissionWindow(value.path("admissionWindow")),
                    identities,
                    text(value, "role"),
                    text(value, "issuer"),
                    instantRequired(value.path("issuedAt"), "issuedAt"),
                    instantRequired(value.path("expiresAt"), "expiresAt"));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidFacts("ENVIRONMENT_FACTS_INVALID");
        }
    }

    private static ParseOutcome parse(
            byte[] bytes, String kind, int maximumBytes, String schemaResource) {
        if (bytes == null || bytes.length > maximumBytes) {
            return ParseOutcome.failure(rejected(kind, kind + "_SIZE_LIMIT"));
        }
        try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
            JsonNode value = JSON.readTree(parser);
            if (value == null || parser.nextToken() != null) {
                return ParseOutcome.failure(rejected(kind, kind + "_INVALID_JSON"));
            }
            if (schemaResource != null) {
                try {
                    if (!CapabilityStudioSchemaSupport.validate(value, schemaResource).isEmpty()) {
                        return ParseOutcome.failure(rejected(kind, kind + "_SCHEMA_INVALID"));
                    }
                } catch (RuntimeException schemaFailure) {
                    if (isSchemaUnavailable(schemaFailure)) {
                        return ParseOutcome.failure(
                                blocked(kind, kind + "_SCHEMA_UNAVAILABLE"));
                    }
                    return ParseOutcome.failure(rejected(kind, kind + "_SCHEMA_INVALID"));
                }
            }
            return ParseOutcome.success(value);
        } catch (JsonParseException duplicateOrInvalid) {
            String code = duplicateOrInvalid.getMessage() != null
                    && duplicateOrInvalid.getMessage().contains("Duplicate field")
                    ? kind + "_DUPLICATE_FIELD" : kind + "_INVALID_JSON";
            return ParseOutcome.failure(rejected(kind, code));
        } catch (IOException | RuntimeException invalid) {
            return ParseOutcome.failure(rejected(kind, kind + "_INVALID_JSON"));
        }
    }

    private static ObjectNode canonicalTargetBinding(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("target binding must be an object");
        }
        try {
            if (!CapabilityStudioSchemaSupport.validate(value,
                    CapabilityStudioSchemaSupport.STAGE_ACCEPTANCE_TARGET_BINDING_V1_RESOURCE)
                    .isEmpty()) {
                throw new IllegalArgumentException("target binding schema is invalid");
            }
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException unavailable) {
            throw new IllegalArgumentException("target binding schema is unavailable");
        }
        ObjectNode canonical = CANONICAL_JSON.createObjectNode();
        canonical.put("schemaVersion", text(value, "schemaVersion"));
        canonical.put("resultId", text(value, "resultId"));
        canonical.put("resultRevision", longValue(value, "resultRevision"));
        canonical.put("contractId", text(value, "contractId"));
        canonical.put("contractRevision", text(value, "contractRevision"));
        canonical.put("executionLeaseId", text(value, "executionLeaseId"));
        CandidateCoordinate candidate = candidateCoordinate(value.path("candidateAttestation"));
        EnvironmentCoordinate environment = environmentCoordinate(
                value.path("environmentAttestation"));
        if (candidate == null || environment == null) {
            throw new IllegalArgumentException("target binding coordinates are invalid");
        }
        ObjectNode candidateNode = canonical.putObject("candidateAttestation");
        candidateNode.put("candidateRef", candidate.candidateRef());
        candidateNode.put("attestationRevision", candidate.attestationRevision());
        candidateNode.put("fingerprint", candidate.fingerprint());
        ObjectNode environmentNode = canonical.putObject("environmentAttestation");
        environmentNode.put("environmentRef", environment.environmentRef());
        environmentNode.put("attestationRevision", environment.attestationRevision());
        environmentNode.put("fingerprint", environment.fingerprint());
        Set<String> identities = stringSet(value.path("trustedTargetIdentities"));
        if (identities == null || identities.isEmpty()) {
            throw new IllegalArgumentException("target identities are invalid");
        }
        ArrayNode identityArray = canonical.putArray("trustedTargetIdentities");
        identities.stream().sorted().forEach(identityArray::add);
        canonical.putNull("fingerprint");
        return canonical;
    }

    private static boolean isSchemaUnavailable(RuntimeException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if ("RG.CAPABILITY_STUDIO.VERIFIER_SCHEMA_UNAVAILABLE"
                    .equals(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static CandidateCoordinate candidateCoordinate(JsonNode value) {
        if (value == null || !value.isObject()
                || !value.path("candidateRef").isTextual()
                || !value.path("attestationRevision").canConvertToLong()
                || !value.path("fingerprint").isTextual()) {
            return null;
        }
        try {
            return new CandidateCoordinate(value.path("candidateRef").textValue(),
                    value.path("attestationRevision").longValue(),
                    value.path("fingerprint").textValue());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static EnvironmentCoordinate environmentCoordinate(JsonNode value) {
        if (value == null || !value.isObject()
                || !value.path("environmentRef").isTextual()
                || !value.path("attestationRevision").canConvertToLong()
                || !value.path("fingerprint").isTextual()) {
            return null;
        }
        try {
            return new EnvironmentCoordinate(value.path("environmentRef").textValue(),
                    value.path("attestationRevision").longValue(),
                    value.path("fingerprint").textValue());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static ExactReference reference(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("exact reference is invalid");
        }
        return new ExactReference(text(value, "exactRef"), text(value, "fingerprint"));
    }

    private static AdmissionWindow admissionWindow(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("admission window is invalid");
        }
        return new AdmissionWindow(
                instantRequired(value.path("from"), "from"),
                instantRequired(value.path("through"), "through"));
    }

    private static boolean sameReference(JsonNode value, ExactReference expected) {
        return value.isObject()
                && expected.exactRef().equals(value.path("exactRef").textValue())
                && expected.fingerprint().equals(value.path("fingerprint").textValue());
    }

    private static boolean sameInstantText(JsonNode value, Instant expected) {
        return value.isTextual() && expected.equals(instant(value));
    }

    private static boolean validNow(Instant issuedAt, Instant expiresAt, Instant now) {
        return !issuedAt.isAfter(now) && now.isBefore(expiresAt);
    }

    private static Instant instant(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(value.textValue());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Instant instantRequired(JsonNode value, String field) {
        Instant parsed = instant(value);
        if (parsed == null) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return parsed;
    }

    private static String text(JsonNode value, String field) {
        String text = value.path(field).textValue();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return text;
    }

    private static long longValue(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return node.longValue();
    }

    private static Set<String> stringSet(JsonNode value) {
        if (value == null || !value.isArray()) {
            return null;
        }
        Set<String> result = new TreeSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || !result.add(item.textValue())) {
                return null;
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> immutableRefs(Set<String> values, String field) {
        if (values == null || values.stream().anyMatch(value -> value == null
                || !SAFE_REF.matcher(value).matches())) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static String requiredRef(String value, String field) {
        if (value == null || !SAFE_REF.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String requiredFingerprint(String value, String field) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String requiredSourceCommit(String value) {
        if (value == null || !SOURCE_COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException("sourceCommit is invalid");
        }
        return value;
    }

    private static String invalid(String field) {
        throw new IllegalArgumentException(field + " is invalid");
    }

    private static JsonFactory strictFactory() {
        return JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static VerificationResult verified(String... checks) {
        return new VerificationResult(Outcome.VERIFIED, orderedSet(checks), null);
    }

    private static VerificationResult rejected(String check, String reasonSuffix) {
        return new VerificationResult(Outcome.REJECTED, Set.of(check), CODE_PREFIX + reasonSuffix);
    }

    private static VerificationResult blocked(String check, String reasonSuffix) {
        return new VerificationResult(Outcome.BLOCKED, Set.of(check), CODE_PREFIX + reasonSuffix);
    }

    private static Set<String> orderedSet(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private record ParseOutcome(JsonNode value, VerificationResult failure) {
        static ParseOutcome success(JsonNode value) {
            return new ParseOutcome(value, null);
        }

        static ParseOutcome failure(VerificationResult failure) {
            return new ParseOutcome(null, failure);
        }

        boolean valid() {
            return value != null;
        }
    }

    private static final class InvalidFacts extends RuntimeException {
        private final String code;

        private InvalidFacts(String code) {
            this.code = code;
        }
    }
}
