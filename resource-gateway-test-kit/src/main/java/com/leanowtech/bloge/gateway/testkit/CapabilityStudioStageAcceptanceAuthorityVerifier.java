package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Performs the external authority stage after the local v2 semantic verifier.
 *
 * <p>The resolver returns bounded, payload-free authority facts. This verifier binds known
 * environment, egress, and owner-signature facts to one immutable acceptance context before
 * caller-owned authorities evaluate issuer policy or cryptographic signatures.</p>
 */
public final class CapabilityStudioStageAcceptanceAuthorityVerifier {
    /** Prefix shared by every result and authority decision code emitted by this verifier. */
    public static final String CODE_PREFIX =
            "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2.";

    private static final Pattern PROTOCOL_CODE = Pattern.compile(
            "RG\\.CAPABILITY_STUDIO\\.STAGE_ACCEPTANCE_RESULT_V2\\.[A-Z0-9_.-]{1,220}");
    private static final Pattern SAFE_CODE_SUFFIX =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,120}");
    private static final Pattern SAFE_REF = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    private static final Pattern RESULT_ID = Pattern.compile("SAR-[A-Za-z0-9._-]{1,120}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[A-Fa-f0-9]{64}");
    private static final Pattern ALGORITHM = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final Pattern SIGNATURE = Pattern.compile("[A-Za-z0-9_+/=-]{1,16384}");
    private static final List<String> REQUIRED_OWNER_ROLES = List.of(
            "CORRECTNESS_OWNER", "RUNTIME_OWNER", "QA_OWNER");

    private final CapabilityStudioStageAcceptanceResultV2Verifier semanticVerifier;

    /** Creates an authority verifier using the existing v2 semantic verifier. */
    public CapabilityStudioStageAcceptanceAuthorityVerifier() {
        this(new CapabilityStudioStageAcceptanceResultV2Verifier());
    }

    /**
     * Creates an authority verifier with an explicitly supplied v2 semantic verifier.
     *
     * @param semanticVerifier verifier for the v2 schema and semantic contract
     */
    public CapabilityStudioStageAcceptanceAuthorityVerifier(
            CapabilityStudioStageAcceptanceResultV2Verifier semanticVerifier) {
        this.semanticVerifier = Objects.requireNonNull(
                semanticVerifier, "semanticVerifier is required");
    }

    /** Final authority-stage outcomes. */
    public enum Outcome {
        /** Every semantic and external authority check passed. */
        ACCEPTED,
        /** Acceptance could not complete because an authority dependency was unavailable. */
        BLOCKED,
        /** Resolved authority facts or an authority decision rejected acceptance. */
        REJECTED,
        /** The v2 document failed schema or semantic verification. */
        PROTOCOL_INVALID,
        /** The v2 document is valid but does not declare {@code PASS}. */
        NOT_ACCEPTED
    }

    /** Distinguishes evidence references from owner signature references. */
    public enum ReferenceKind {
        /** A reference from the v2 evidence catalog. */
        EVIDENCE,
        /** A signature reference from an owner signoff. */
        SIGNATURE
    }

    /** Declares the authority meaning of resolved payload-free facts. */
    public enum EvidenceKind {
        /** Ordinary pre-sign acceptance evidence. */
        ACCEPTANCE_EVIDENCE,
        /** Target-environment attestation evidence. */
        ENVIRONMENT_ATTESTATION,
        /** Deployment egress observation evidence. */
        DEPLOYMENT_EGRESS_OBSERVATION,
        /** Cryptographic evidence for an owner signoff. */
        OWNER_SIGNATURE
    }

    /**
     * Exact, payload-free identity of one externally resolved artifact.
     *
     * @param exactRef immutable external reference
     * @param fingerprint SHA-256 content fingerprint
     */
    public record EvidenceCoordinate(String exactRef, String fingerprint) {
        /**
         * Validates one exact evidence coordinate.
         *
         * @param exactRef immutable external reference
         * @param fingerprint SHA-256 content fingerprint
         * @throws IllegalArgumentException when either component is outside protocol bounds
         */
        public EvidenceCoordinate {
            exactRef = required(exactRef, SAFE_REF, "exactRef");
            fingerprint = required(fingerprint, FINGERPRINT, "fingerprint");
        }

        static EvidenceCoordinate from(JsonNode value) {
            return new EvidenceCoordinate(
                    value.path("exactRef").textValue(), value.path("fingerprint").textValue());
        }
    }

    /**
     * Immutable context shared by every authority invocation for one result.
     *
     * @param resultId stage acceptance result identity
     * @param revision stage acceptance result revision
     * @param contractId acceptance contract identity
     * @param contractRevision acceptance contract revision
     * @param candidateArtifactFingerprint candidate artifact fingerprint
     * @param candidateIntentFingerprint candidate execution intent fingerprint
     * @param environmentFingerprint target environment fingerprint
     * @param executionStartedAt candidate execution start
     * @param evidenceCompletedAt completion of the evidence window
     * @param decidedAt stage acceptance decision time
     * @param evidenceClosureFingerprint verified evidence closure fingerprint
     * @param environmentProfile target environment profile
     * @param environmentScope target environment scope
     * @param environmentIssuer target environment attestation issuer
     */
    public record AcceptanceContext(
            String resultId,
            int revision,
            String contractId,
            String contractRevision,
            String candidateArtifactFingerprint,
            String candidateIntentFingerprint,
            String environmentFingerprint,
            Instant executionStartedAt,
            Instant evidenceCompletedAt,
            Instant decidedAt,
            String evidenceClosureFingerprint,
            String environmentProfile,
            String environmentScope,
            String environmentIssuer) {
        /**
         * Validates one complete authority acceptance context.
         *
         * @param resultId stage acceptance result identity
         * @param revision stage acceptance result revision
         * @param contractId acceptance contract identity
         * @param contractRevision acceptance contract revision
         * @param candidateArtifactFingerprint candidate artifact fingerprint
         * @param candidateIntentFingerprint candidate execution intent fingerprint
         * @param environmentFingerprint target environment fingerprint
         * @param executionStartedAt candidate execution start
         * @param evidenceCompletedAt completion of the evidence window
         * @param decidedAt stage acceptance decision time
         * @param evidenceClosureFingerprint verified evidence closure fingerprint
         * @param environmentProfile target environment profile
         * @param environmentScope target environment scope
         * @param environmentIssuer target environment attestation issuer
         * @throws IllegalArgumentException when a value or time ordering is invalid
         * @throws NullPointerException when a required timestamp is absent
         */
        public AcceptanceContext {
            resultId = required(resultId, RESULT_ID, "resultId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            contractId = required(contractId, SAFE_REF, "contractId");
            contractRevision = required(contractRevision, SAFE_REF, "contractRevision");
            candidateArtifactFingerprint = required(
                    candidateArtifactFingerprint, FINGERPRINT, "candidateArtifactFingerprint");
            candidateIntentFingerprint = required(
                    candidateIntentFingerprint, FINGERPRINT, "candidateIntentFingerprint");
            environmentFingerprint = required(
                    environmentFingerprint, FINGERPRINT, "environmentFingerprint");
            executionStartedAt = Objects.requireNonNull(
                    executionStartedAt, "executionStartedAt is required");
            evidenceCompletedAt = Objects.requireNonNull(
                    evidenceCompletedAt, "evidenceCompletedAt is required");
            decidedAt = Objects.requireNonNull(decidedAt, "decidedAt is required");
            if (evidenceCompletedAt.isBefore(executionStartedAt)
                    || decidedAt.isBefore(evidenceCompletedAt)) {
                throw new IllegalArgumentException("acceptance context window is invalid");
            }
            evidenceClosureFingerprint = required(
                    evidenceClosureFingerprint, FINGERPRINT, "evidenceClosureFingerprint");
            environmentProfile = required(
                    environmentProfile, SAFE_REF, "environmentProfile");
            environmentScope = required(environmentScope, SAFE_REF, "environmentScope");
            environmentIssuer = required(environmentIssuer, SAFE_REF, "environmentIssuer");
        }
    }

    /**
     * One v2 evidence catalog entry supplied to the issuer policy.
     *
     * @param evidenceId stable evidence catalog identity
     * @param coordinate exact external evidence coordinate
     */
    public record EvidenceReference(String evidenceId, EvidenceCoordinate coordinate) {
        /**
         * Validates one evidence reference.
         *
         * @param evidenceId stable evidence catalog identity
         * @param coordinate exact external evidence coordinate
         * @throws IllegalArgumentException when the evidence ID is outside protocol bounds
         * @throws NullPointerException when the coordinate is absent
         */
        public EvidenceReference {
            evidenceId = required(evidenceId, SAFE_REF, "evidenceId");
            coordinate = Objects.requireNonNull(coordinate, "coordinate is required");
        }
    }

    /** Typed signoff decision from the v2 projection. */
    public enum SignoffDecision {
        /** The owner approved the verified evidence closure. */
        APPROVED,
        /** The owner rejected the verified evidence closure. */
        REJECTED
    }

    /**
     * One v2 signoff supplied to the owner authority.
     *
     * @param role owner role
     * @param actorRef owner actor reference
     * @param decision projected owner decision
     * @param signedAt projected signing time
     * @param signatureCoordinate exact signature coordinate
     * @param evidenceClosureFingerprint signed evidence closure fingerprint
     */
    public record OwnerSignoff(
            String role,
            String actorRef,
            SignoffDecision decision,
            Instant signedAt,
            EvidenceCoordinate signatureCoordinate,
            String evidenceClosureFingerprint) {
        /**
         * Validates one owner signoff projection.
         *
         * @param role owner role
         * @param actorRef owner actor reference
         * @param decision projected owner decision
         * @param signedAt projected signing time
         * @param signatureCoordinate exact signature coordinate
         * @param evidenceClosureFingerprint signed evidence closure fingerprint
         * @throws IllegalArgumentException when a textual component is outside protocol bounds
         * @throws NullPointerException when a required typed component is absent
         */
        public OwnerSignoff {
            role = required(role, SAFE_REF, "role");
            actorRef = required(actorRef, SAFE_REF, "actorRef");
            decision = Objects.requireNonNull(decision, "decision is required");
            signedAt = Objects.requireNonNull(signedAt, "signedAt is required");
            signatureCoordinate = Objects.requireNonNull(
                    signatureCoordinate, "signatureCoordinate is required");
            evidenceClosureFingerprint = required(
                    evidenceClosureFingerprint, FINGERPRINT, "evidenceClosureFingerprint");
        }
    }

    /**
     * Typed request passed to the shared external resolver.
     *
     * @param kind reference category
     * @param key evidence ID or owner role used for stable ordering
     * @param coordinate expected exact external coordinate
     */
    public record ResolutionRequest(
            ReferenceKind kind,
            String key,
            EvidenceCoordinate coordinate) {
        /**
         * Validates one resolver request.
         *
         * @param kind reference category
         * @param key evidence ID or owner role used for stable ordering
         * @param coordinate expected exact external coordinate
         * @throws IllegalArgumentException when the key is outside protocol bounds
         * @throws NullPointerException when a required typed component is absent
         */
        public ResolutionRequest {
            kind = Objects.requireNonNull(kind, "kind is required");
            key = required(key, SAFE_REF, "key");
            coordinate = Objects.requireNonNull(coordinate, "coordinate is required");
        }

        /**
         * Creates a resolver request for a v2 evidence catalog entry.
         *
         * @param reference evidence catalog entry
         * @return typed evidence resolution request
         * @throws NullPointerException when the reference is absent
         */
        public static ResolutionRequest evidence(EvidenceReference reference) {
            Objects.requireNonNull(reference, "reference is required");
            return new ResolutionRequest(ReferenceKind.EVIDENCE,
                    reference.evidenceId(), reference.coordinate());
        }

        /**
         * Creates a resolver request for an owner signature.
         *
         * @param signoff owner signoff projection
         * @return typed signature resolution request
         * @throws NullPointerException when the signoff is absent
         */
        public static ResolutionRequest signature(OwnerSignoff signoff) {
            Objects.requireNonNull(signoff, "signoff is required");
            return new ResolutionRequest(ReferenceKind.SIGNATURE,
                    signoff.role(), signoff.signatureCoordinate());
        }
    }

    /** Resolver outcomes. */
    public enum ResolutionStatus {
        /** The resolver returned authority facts for the exact artifact. */
        AVAILABLE,
        /** The exact artifact deterministically does not exist. */
        NOT_FOUND,
        /** The resolver could not determine availability. */
        UNAVAILABLE
    }

    /**
     * Bounded, immutable, payload-free authority facts for resolved evidence or signatures.
     *
     * <p>Fields other than coordinate and kind may be absent for ordinary pre-sign evidence.
     * Known environment, egress, and owner-signature coordinates are checked for their required
     * facts before any authority callback is invoked.</p>
     *
     * @param coordinate exact resolved artifact coordinate
     * @param evidenceKind authority meaning of the artifact
     * @param issuerRef evidence or signature issuer reference, when available
     * @param scope authority scope, when available
     * @param candidateArtifactFingerprint bound candidate artifact fingerprint, when applicable
     * @param candidateIntentFingerprint bound candidate intent fingerprint, when applicable
     * @param environmentFingerprint bound environment fingerprint, when applicable
     * @param observedFrom beginning of the observed evidence window, when applicable
     * @param observedThrough end of the observed evidence window, when applicable
     * @param evidenceClosureFingerprint bound evidence closure, required for owner signatures
     * @param keyId verification key identity, when available
     * @param algorithm signature algorithm, when available
     * @param materialFingerprint signed material fingerprint, when available
     * @param signedAt signature creation time, when available
     * @param expiresAt authority fact expiry time, when available
     * @param signature bounded encoded cryptographic signature, when available
     */
    public record ResolvedEvidence(
            EvidenceCoordinate coordinate,
            EvidenceKind evidenceKind,
            String issuerRef,
            String scope,
            String candidateArtifactFingerprint,
            String candidateIntentFingerprint,
            String environmentFingerprint,
            Instant observedFrom,
            Instant observedThrough,
            String evidenceClosureFingerprint,
            String keyId,
            String algorithm,
            String materialFingerprint,
            Instant signedAt,
            Instant expiresAt,
            String signature) {
        /**
         * Validates bounded, payload-free authority facts.
         *
         * @param coordinate exact resolved artifact coordinate
         * @param evidenceKind authority meaning of the artifact
         * @param issuerRef evidence or signature issuer reference, when available
         * @param scope authority scope, when available
         * @param candidateArtifactFingerprint bound candidate artifact fingerprint, when applicable
         * @param candidateIntentFingerprint bound candidate intent fingerprint, when applicable
         * @param environmentFingerprint bound environment fingerprint, when applicable
         * @param observedFrom beginning of the observed evidence window, when applicable
         * @param observedThrough end of the observed evidence window, when applicable
         * @param evidenceClosureFingerprint bound evidence closure, required for owner signatures
         * @param keyId verification key identity, when available
         * @param algorithm signature algorithm, when available
         * @param materialFingerprint signed material fingerprint, when available
         * @param signedAt signature creation time, when available
         * @param expiresAt authority fact expiry time, when available
         * @param signature bounded encoded cryptographic signature, when available
         * @throws IllegalArgumentException when a fact is outside bounds or its window is invalid
         * @throws NullPointerException when the coordinate or evidence kind is absent
         */
        public ResolvedEvidence {
            coordinate = Objects.requireNonNull(coordinate, "coordinate is required");
            evidenceKind = Objects.requireNonNull(evidenceKind, "evidenceKind is required");
            issuerRef = optional(issuerRef, SAFE_REF, "issuerRef");
            scope = optional(scope, SAFE_REF, "scope");
            candidateArtifactFingerprint = optional(
                    candidateArtifactFingerprint, FINGERPRINT,
                    "candidateArtifactFingerprint");
            candidateIntentFingerprint = optional(
                    candidateIntentFingerprint, FINGERPRINT,
                    "candidateIntentFingerprint");
            environmentFingerprint = optional(
                    environmentFingerprint, FINGERPRINT, "environmentFingerprint");
            evidenceClosureFingerprint = optional(
                    evidenceClosureFingerprint, FINGERPRINT,
                    "evidenceClosureFingerprint");
            keyId = optional(keyId, SAFE_REF, "keyId");
            algorithm = optional(algorithm, ALGORITHM, "algorithm");
            materialFingerprint = optional(
                    materialFingerprint, FINGERPRINT, "materialFingerprint");
            signature = optional(signature, SIGNATURE, "signature");
            if (observedFrom != null && observedThrough != null
                    && observedThrough.isBefore(observedFrom)) {
                throw new IllegalArgumentException("observed evidence window is invalid");
            }
        }

        /**
         * Returns a redacted description that omits coordinates and signatures.
         *
         * @return redacted authority-facts description
         */
        @Override
        public String toString() {
            return "ResolvedEvidence[evidenceKind=" + evidenceKind + ", authorityFacts=REDACTED]";
        }
    }

    /**
     * Typed resolver response.
     *
     * @param status bounded resolution status
     * @param evidence resolved authority facts for {@link ResolutionStatus#AVAILABLE}
     */
    public record EvidenceResolution(ResolutionStatus status, ResolvedEvidence evidence) {
        /**
         * Validates one resolver response.
         *
         * @param status bounded resolution status
         * @param evidence resolved authority facts, when available
         * @throws NullPointerException when the status is absent
         */
        public EvidenceResolution {
            status = Objects.requireNonNull(status, "resolution status is required");
        }

        /**
         * Creates an available response carrying resolved authority facts.
         *
         * @param evidence resolved authority facts
         * @return available resolver response
         */
        public static EvidenceResolution available(ResolvedEvidence evidence) {
            return new EvidenceResolution(ResolutionStatus.AVAILABLE, evidence);
        }

        /**
         * Creates a deterministic not-found response.
         *
         * @return not-found resolver response
         */
        public static EvidenceResolution notFound() {
            return new EvidenceResolution(ResolutionStatus.NOT_FOUND, null);
        }

        /**
         * Creates an unavailable resolver response.
         *
         * @return unavailable resolver response
         */
        public static EvidenceResolution unavailable() {
            return new EvidenceResolution(ResolutionStatus.UNAVAILABLE, null);
        }
    }

    /** Deep external resolution boundary used by both evidence and signatures. */
    @FunctionalInterface
    public interface EvidenceResolver {
        /**
         * Resolves authority facts for one exact coordinate.
         *
         * @param request typed resolution request
         * @return bounded resolution response
         */
        EvidenceResolution resolve(ResolutionRequest request);
    }

    /** Independent issuer and signature policy for one resolved evidence artifact. */
    @FunctionalInterface
    public interface EvidenceIssuerPolicy {
        /**
         * Verifies issuer policy and signature facts against the acceptance context.
         *
         * @param reference verified v2 evidence reference
         * @param evidence resolved payload-free authority facts
         * @param context immutable acceptance context shared across all checks
         * @return typed authority decision
         */
        AuthorityDecision verify(
                EvidenceReference reference,
                ResolvedEvidence evidence,
                AcceptanceContext context);
    }

    /** Independent authority for one owner signoff and its resolved signature artifact. */
    @FunctionalInterface
    public interface OwnerAuthority {
        /**
         * Verifies one owner signature against its signoff and acceptance context.
         *
         * @param signoff verified v2 owner signoff
         * @param signature resolved payload-free signature authority facts
         * @param context immutable acceptance context shared across all checks
         * @return typed authority decision
         */
        AuthorityDecision verify(
                OwnerSignoff signoff,
                ResolvedEvidence signature,
                AcceptanceContext context);
    }

    /**
     * Typed decision returned by issuer and owner authorities.
     *
     * @param status bounded authority decision status
     * @param reasonCode stable protocol reason code
     */
    public record AuthorityDecision(Decision status, String reasonCode) {
        /** Bounded authority decision states. */
        public enum Decision {
            /** The authority verified the supplied facts and context. */
            VERIFIED,
            /** The authority could not complete verification. */
            UNAVAILABLE,
            /** The authority deterministically rejected the supplied facts. */
            REJECTED
        }

        /**
         * Validates and normalizes one authority decision.
         *
         * @param status bounded authority decision status
         * @param reasonCode stable protocol reason code or bounded suffix
         * @throws NullPointerException when the status is absent
         */
        public AuthorityDecision {
            status = Objects.requireNonNull(status, "status is required");
            reasonCode = normalizeDecisionCode(status, reasonCode);
        }

        /**
         * Creates a verified authority decision.
         *
         * @return verified decision
         */
        public static AuthorityDecision verified() {
            return new AuthorityDecision(Decision.VERIFIED, CODE_PREFIX + "VERIFIED");
        }

        /**
         * Creates an unavailable decision with the default reason code.
         *
         * @return unavailable decision
         */
        public static AuthorityDecision unavailable() {
            return new AuthorityDecision(
                    Decision.UNAVAILABLE, CODE_PREFIX + "AUTHORITY_UNAVAILABLE");
        }

        /**
         * Creates an unavailable decision with a stable caller-supplied reason.
         *
         * @param reasonCode full protocol code or bounded code suffix
         * @return unavailable decision
         */
        public static AuthorityDecision unavailable(String reasonCode) {
            return new AuthorityDecision(Decision.UNAVAILABLE, reasonCode);
        }

        /**
         * Creates a rejected decision with the default reason code.
         *
         * @return rejected decision
         */
        public static AuthorityDecision rejected() {
            return new AuthorityDecision(Decision.REJECTED, CODE_PREFIX + "AUTHORITY_REJECTED");
        }

        /**
         * Creates a rejected decision with a stable caller-supplied reason.
         *
         * @param reasonCode full protocol code or bounded code suffix
         * @return rejected decision
         */
        public static AuthorityDecision rejected(String reasonCode) {
            return new AuthorityDecision(Decision.REJECTED, reasonCode);
        }
    }

    /**
     * Payload-free authority result.
     *
     * @param outcome final bounded verification outcome
     * @param reasonCode stable protocol reason code
     */
    public record VerificationResult(Outcome outcome, String reasonCode) {
        /**
         * Validates one payload-free verification result.
         *
         * @param outcome final bounded verification outcome
         * @param reasonCode stable protocol reason code
         * @throws IllegalArgumentException when the reason code is outside protocol bounds
         * @throws NullPointerException when the outcome is absent
         */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome is required");
            if (reasonCode == null || !PROTOCOL_CODE.matcher(reasonCode).matches()) {
                throw new IllegalArgumentException("authority result code is invalid");
            }
        }

        /**
         * Reports whether the result completed as accepted.
         *
         * @return true only for {@link Outcome#ACCEPTED}
         */
        public boolean accepted() {
            return outcome == Outcome.ACCEPTED;
        }
    }

    /**
     * Verifies with the current instant as the trusted v2 evidence-window clock.
     *
     * @param result decoded v2 stage acceptance result
     * @param resolver external evidence and signature resolver
     * @param issuerPolicy external evidence issuer policy
     * @param ownerAuthority external owner signature authority
     * @return payload-free authority verification result
     */
    public VerificationResult verify(
            JsonNode result,
            EvidenceResolver resolver,
            EvidenceIssuerPolicy issuerPolicy,
            OwnerAuthority ownerAuthority) {
        return verify(result, Instant.now(), resolver, issuerPolicy, ownerAuthority);
    }

    /**
     * Verifies with an explicit trusted instant for deterministic callers and tests.
     *
     * @param result decoded v2 stage acceptance result
     * @param now trusted verification instant
     * @param resolver external evidence and signature resolver
     * @param issuerPolicy external evidence issuer policy
     * @param ownerAuthority external owner signature authority
     * @return payload-free authority verification result
     */
    public VerificationResult verify(
            JsonNode result,
            Instant now,
            EvidenceResolver resolver,
            EvidenceIssuerPolicy issuerPolicy,
            OwnerAuthority ownerAuthority) {
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult semantic;
        try {
            semantic = semanticVerifier.verify(result, now);
        } catch (RuntimeException invalid) {
            return protocolInvalid("PROTOCOL_INVALID");
        }
        if (!semantic.verified()) {
            return protocolInvalid(semantic.errorCode());
        }
        if (!"PASS".equals(result.path("status").textValue())) {
            return result(Outcome.NOT_ACCEPTED, "STATUS_NOT_PASS");
        }
        if (resolver == null || issuerPolicy == null || ownerAuthority == null) {
            return result(Outcome.BLOCKED, "AUTHORITY_DEPENDENCY_UNAVAILABLE");
        }

        try {
            AcceptanceContext context = acceptanceContext(result);
            ProjectionBindings bindings = projectionBindings(result);
            EvidenceStage evidenceStage = verifyEvidence(
                    evidenceReferences(result), resolver, issuerPolicy, context, bindings);
            if (evidenceStage.failure() != null) {
                return evidenceStage.failure();
            }

            SignoffStage signoffStage = verifySignoffs(
                    ownerSignoffs(result), resolver, ownerAuthority, context);
            if (signoffStage.failure() != null) {
                return signoffStage.failure();
            }
            return result(Outcome.ACCEPTED, "ACCEPTED");
        } catch (RuntimeException invalidProjection) {
            return protocolInvalid("PROTOCOL_PROJECTION_INVALID");
        }
    }

    /**
     * Verifies with dependencies first and the explicit trusted instant last.
     *
     * @param result decoded v2 stage acceptance result
     * @param resolver external evidence and signature resolver
     * @param issuerPolicy external evidence issuer policy
     * @param ownerAuthority external owner signature authority
     * @param now trusted verification instant
     * @return payload-free authority verification result
     */
    public VerificationResult verify(
            JsonNode result,
            EvidenceResolver resolver,
            EvidenceIssuerPolicy issuerPolicy,
            OwnerAuthority ownerAuthority,
            Instant now) {
        return verify(result, now, resolver, issuerPolicy, ownerAuthority);
    }

    private static EvidenceStage verifyEvidence(
            List<EvidenceReference> references,
            EvidenceResolver resolver,
            EvidenceIssuerPolicy issuerPolicy,
            AcceptanceContext context,
            ProjectionBindings bindings) {
        List<ResolvedEvidence> resolved = new ArrayList<>();
        VerificationResult firstFailure = null;
        for (EvidenceReference reference : references) {
            EvidenceResolution resolution;
            try {
                resolution = resolver.resolve(ResolutionRequest.evidence(reference));
            } catch (RuntimeException unavailable) {
                resolution = null;
            }
            VerificationResult failure = evidenceResolutionFailure(reference, resolution);
            if (failure == null) {
                failure = evidenceAuthorityFactsFailure(
                        reference, resolution.evidence(), context, bindings);
            }
            if (failure != null) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            } else {
                resolved.add(resolution.evidence());
            }
        }
        if (firstFailure != null) {
            return new EvidenceStage(firstFailure);
        }

        for (int index = 0; index < references.size(); index++) {
            AuthorityDecision decision;
            try {
                decision = issuerPolicy.verify(
                        references.get(index), resolved.get(index), context);
            } catch (RuntimeException unavailable) {
                decision = null;
            }
            VerificationResult failure = authorityFailure(
                    decision, "EVIDENCE_ISSUER_UNAVAILABLE", "EVIDENCE_ISSUER_REJECTED");
            if (failure != null && firstFailure == null) {
                firstFailure = failure;
            }
        }
        return new EvidenceStage(firstFailure);
    }

    private static SignoffStage verifySignoffs(
            List<OwnerSignoff> signoffs,
            EvidenceResolver resolver,
            OwnerAuthority ownerAuthority,
            AcceptanceContext context) {
        List<ResolvedEvidence> resolved = new ArrayList<>();
        VerificationResult firstFailure = null;
        for (OwnerSignoff signoff : signoffs) {
            EvidenceResolution resolution;
            try {
                resolution = resolver.resolve(ResolutionRequest.signature(signoff));
            } catch (RuntimeException unavailable) {
                resolution = null;
            }
            VerificationResult failure = signatureResolutionFailure(signoff, resolution, context);
            if (failure != null) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            } else {
                resolved.add(resolution.evidence());
            }
        }
        if (firstFailure != null) {
            return new SignoffStage(firstFailure);
        }

        for (int index = 0; index < signoffs.size(); index++) {
            AuthorityDecision decision;
            try {
                decision = ownerAuthority.verify(
                        signoffs.get(index), resolved.get(index), context);
            } catch (RuntimeException unavailable) {
                decision = null;
            }
            VerificationResult failure = authorityFailure(
                    decision, "OWNER_AUTHORITY_UNAVAILABLE", "OWNER_AUTHORITY_REJECTED");
            if (failure != null && firstFailure == null) {
                firstFailure = failure;
            }
        }
        return new SignoffStage(firstFailure);
    }

    private static VerificationResult evidenceResolutionFailure(
            EvidenceReference reference, EvidenceResolution resolution) {
        if (resolution == null) {
            return result(Outcome.BLOCKED, "EVIDENCE_RESOLVER_UNAVAILABLE");
        }
        return switch (resolution.status()) {
            case AVAILABLE -> resolution.evidence() == null
                    ? result(Outcome.BLOCKED, "EVIDENCE_RESOLVER_RESPONSE_INVALID")
                    : coordinateFailure(reference.coordinate(), resolution.evidence().coordinate(),
                    "EVIDENCE_COORDINATE_MISMATCH");
            case NOT_FOUND -> result(Outcome.REJECTED, "EVIDENCE_NOT_FOUND");
            case UNAVAILABLE -> result(Outcome.BLOCKED, "EVIDENCE_RESOLVER_UNAVAILABLE");
        };
    }

    private static VerificationResult evidenceAuthorityFactsFailure(
            EvidenceReference reference,
            ResolvedEvidence evidence,
            AcceptanceContext context,
            ProjectionBindings bindings) {
        if (reference.coordinate().equals(bindings.environment().coordinate())) {
            return environmentFactsFailure(evidence, context, bindings.environment());
        }
        if (reference.coordinate().equals(bindings.egress().coordinate())) {
            return egressFactsFailure(evidence, context, bindings.egress());
        }
        return evidence.evidenceKind() == EvidenceKind.ACCEPTANCE_EVIDENCE
                ? null : result(Outcome.REJECTED, "EVIDENCE_KIND_MISMATCH");
    }

    private static VerificationResult environmentFactsFailure(
            ResolvedEvidence evidence,
            AcceptanceContext context,
            EnvironmentProjection projection) {
        if (evidence.evidenceKind() != EvidenceKind.ENVIRONMENT_ATTESTATION) {
            return result(Outcome.REJECTED, "ENVIRONMENT_EVIDENCE_KIND_MISMATCH");
        }
        if (evidence.issuerRef() == null || evidence.scope() == null
                || evidence.candidateArtifactFingerprint() == null
                || evidence.environmentFingerprint() == null
                || evidence.observedFrom() == null || evidence.observedThrough() == null) {
            return result(Outcome.BLOCKED, "ENVIRONMENT_AUTHORITY_FACTS_INCOMPLETE");
        }
        if (!evidence.issuerRef().equals(context.environmentIssuer())
                || !evidence.scope().equals(context.environmentScope())
                || !evidence.candidateArtifactFingerprint().equals(
                context.candidateArtifactFingerprint())
                || !evidence.environmentFingerprint().equals(context.environmentFingerprint())) {
            return result(Outcome.REJECTED, "ENVIRONMENT_AUTHORITY_BINDING_MISMATCH");
        }
        if (!evidence.observedFrom().equals(projection.observedFrom())
                || !evidence.observedThrough().equals(projection.observedThrough())
                || evidence.observedFrom().isAfter(context.executionStartedAt())
                || evidence.observedThrough().isBefore(context.evidenceCompletedAt())) {
            return result(Outcome.REJECTED, "ENVIRONMENT_AUTHORITY_WINDOW_MISMATCH");
        }
        return null;
    }

    private static VerificationResult egressFactsFailure(
            ResolvedEvidence evidence,
            AcceptanceContext context,
            EgressProjection projection) {
        if (evidence.evidenceKind() != EvidenceKind.DEPLOYMENT_EGRESS_OBSERVATION) {
            return result(Outcome.REJECTED, "EGRESS_EVIDENCE_KIND_MISMATCH");
        }
        if (evidence.candidateIntentFingerprint() == null
                || evidence.observedFrom() == null || evidence.observedThrough() == null) {
            return result(Outcome.BLOCKED, "EGRESS_AUTHORITY_FACTS_INCOMPLETE");
        }
        if (!evidence.candidateIntentFingerprint().equals(
                context.candidateIntentFingerprint())) {
            return result(Outcome.REJECTED, "EGRESS_AUTHORITY_BINDING_MISMATCH");
        }
        if (!evidence.observedFrom().equals(projection.observedFrom())
                || !evidence.observedThrough().equals(projection.observedThrough())
                || evidence.observedFrom().isAfter(context.executionStartedAt())
                || evidence.observedThrough().isBefore(context.evidenceCompletedAt())) {
            return result(Outcome.REJECTED, "EGRESS_AUTHORITY_WINDOW_MISMATCH");
        }
        return null;
    }

    private static VerificationResult signatureResolutionFailure(
            OwnerSignoff signoff,
            EvidenceResolution resolution,
            AcceptanceContext context) {
        if (resolution == null) {
            return result(Outcome.BLOCKED, "SIGNATURE_RESOLVER_UNAVAILABLE");
        }
        VerificationResult resolutionFailure = switch (resolution.status()) {
            case AVAILABLE -> resolution.evidence() == null
                    ? result(Outcome.BLOCKED, "SIGNATURE_RESOLVER_RESPONSE_INVALID")
                    : coordinateFailure(signoff.signatureCoordinate(),
                    resolution.evidence().coordinate(), "SIGNATURE_COORDINATE_MISMATCH");
            case NOT_FOUND -> result(Outcome.REJECTED, "SIGNATURE_NOT_FOUND");
            case UNAVAILABLE -> result(Outcome.BLOCKED, "SIGNATURE_RESOLVER_UNAVAILABLE");
        };
        if (resolutionFailure != null) {
            return resolutionFailure;
        }
        ResolvedEvidence signature = resolution.evidence();
        if (signature.evidenceKind() != EvidenceKind.OWNER_SIGNATURE) {
            return result(Outcome.REJECTED, "OWNER_SIGNATURE_KIND_MISMATCH");
        }
        if (signature.evidenceClosureFingerprint() == null) {
            return result(Outcome.BLOCKED, "OWNER_SIGNATURE_FACTS_INCOMPLETE");
        }
        if (!signature.evidenceClosureFingerprint().equals(context.evidenceClosureFingerprint())) {
            return result(Outcome.REJECTED, "OWNER_SIGNATURE_CLOSURE_MISMATCH");
        }
        return null;
    }

    private static VerificationResult coordinateFailure(
            EvidenceCoordinate expected, EvidenceCoordinate actual, String code) {
        return expected.equals(actual) ? null : result(Outcome.REJECTED, code);
    }

    private static VerificationResult authorityFailure(
            AuthorityDecision decision, String unavailableCode, String rejectedCode) {
        if (decision == null) {
            return result(Outcome.BLOCKED, "AUTHORITY_DECISION_INVALID");
        }
        return switch (decision.status()) {
            case VERIFIED -> null;
            case UNAVAILABLE -> result(Outcome.BLOCKED,
                    authorityCode(decision.reasonCode(), unavailableCode));
            case REJECTED -> result(Outcome.REJECTED,
                    authorityCode(decision.reasonCode(), rejectedCode));
        };
    }

    private static AcceptanceContext acceptanceContext(JsonNode result) {
        JsonNode binding = result.path("candidateExecutionBinding");
        JsonNode environment = result.path("environmentAttestation");
        return new AcceptanceContext(
                result.path("resultId").textValue(),
                result.path("revision").intValue(),
                result.path("contractId").textValue(),
                result.path("contractRevision").textValue(),
                binding.path("candidateBuild").path("artifactFingerprint").textValue(),
                binding.path("candidateIntentFingerprint").textValue(),
                binding.path("environmentFingerprint").textValue(),
                Instant.parse(binding.path("executionStartedAt").textValue()),
                Instant.parse(binding.path("evidenceCompletedAt").textValue()),
                Instant.parse(result.path("decidedAt").textValue()),
                result.path("evidenceClosureFingerprint").textValue(),
                environment.path("profile").textValue(),
                environment.path("scope").textValue(),
                environment.path("issuer").textValue());
    }

    private static ProjectionBindings projectionBindings(JsonNode result) {
        JsonNode environment = result.path("environmentAttestation");
        JsonNode egress = result.path("deploymentEgressObservation");
        EnvironmentProjection environmentProjection = new EnvironmentProjection(
                EvidenceCoordinate.from(environment),
                Instant.parse(environment.path("issuedAt").textValue()),
                Instant.parse(environment.path("expiresAt").textValue()));
        EgressProjection egressProjection = new EgressProjection(
                EvidenceCoordinate.from(egress),
                Instant.parse(egress.path("observationStartedAt").textValue()),
                Instant.parse(egress.path("observationCompletedAt").textValue()));
        if (environmentProjection.coordinate().equals(egressProjection.coordinate())) {
            throw new IllegalArgumentException("authority projection coordinates collide");
        }
        return new ProjectionBindings(environmentProjection, egressProjection);
    }

    private static List<EvidenceReference> evidenceReferences(JsonNode result) {
        List<EvidenceReference> references = new ArrayList<>();
        for (JsonNode value : result.path("evidenceRefs")) {
            references.add(new EvidenceReference(
                    value.path("evidenceId").textValue(), EvidenceCoordinate.from(value)));
        }
        references.sort(Comparator.comparing(EvidenceReference::evidenceId));
        return List.copyOf(references);
    }

    private static List<OwnerSignoff> ownerSignoffs(JsonNode result) {
        List<OwnerSignoff> signoffs = new ArrayList<>();
        for (JsonNode value : result.path("signoffs")) {
            signoffs.add(new OwnerSignoff(
                    value.path("role").textValue(),
                    value.path("actorRef").textValue(),
                    SignoffDecision.valueOf(value.path("decision").textValue()),
                    Instant.parse(value.path("signedAt").textValue()),
                    EvidenceCoordinate.from(value.path("signatureRef")),
                    value.path("evidenceClosureFingerprint").textValue()));
        }
        signoffs.sort(Comparator.comparing(OwnerSignoff::role));
        List<OwnerSignoff> ordered = List.copyOf(signoffs);
        if (REQUIRED_OWNER_ROLES.stream().anyMatch(required -> ordered.stream()
                .noneMatch(signoff -> required.equals(signoff.role())))) {
            throw new IllegalArgumentException("required owner role is missing");
        }
        return ordered;
    }

    private static String required(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String optional(String value, Pattern pattern, String field) {
        return value == null ? null : required(value, pattern, field);
    }

    private static String normalizeDecisionCode(
            AuthorityDecision.Decision status, String reasonCode) {
        String fallback = CODE_PREFIX + (status == AuthorityDecision.Decision.VERIFIED
                ? "VERIFIED"
                : status == AuthorityDecision.Decision.UNAVAILABLE
                ? "AUTHORITY_UNAVAILABLE" : "AUTHORITY_REJECTED");
        if (reasonCode == null || reasonCode.isBlank()) {
            return fallback;
        }
        if (PROTOCOL_CODE.matcher(reasonCode).matches()) {
            return reasonCode;
        }
        if (SAFE_CODE_SUFFIX.matcher(reasonCode).matches()) {
            return CODE_PREFIX + reasonCode.toUpperCase(Locale.ROOT);
        }
        return fallback;
    }

    private static String authorityCode(String decisionCode, String fallbackSuffix) {
        return PROTOCOL_CODE.matcher(decisionCode).matches()
                ? decisionCode : CODE_PREFIX + fallbackSuffix;
    }

    private static VerificationResult protocolInvalid(String reasonCode) {
        return result(Outcome.PROTOCOL_INVALID,
                PROTOCOL_CODE.matcher(String.valueOf(reasonCode)).matches()
                        ? reasonCode : "PROTOCOL_INVALID");
    }

    private static VerificationResult result(Outcome outcome, String suffix) {
        return new VerificationResult(outcome, CODE_PREFIX + suffix);
    }

    private record EnvironmentProjection(
            EvidenceCoordinate coordinate, Instant observedFrom, Instant observedThrough) {
    }

    private record EgressProjection(
            EvidenceCoordinate coordinate, Instant observedFrom, Instant observedThrough) {
    }

    private record ProjectionBindings(
            EnvironmentProjection environment, EgressProjection egress) {
    }

    private record EvidenceStage(VerificationResult failure) {
    }

    private record SignoffStage(VerificationResult failure) {
    }
}
