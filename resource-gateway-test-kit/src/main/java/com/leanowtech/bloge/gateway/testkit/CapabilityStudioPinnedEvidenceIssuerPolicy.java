package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AcceptanceContext;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceReference;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed local issuer policy for Capability Studio stage acceptance evidence.
 *
 * <p>The policy admits only explicitly configured issuer/scope pairs. Each pair owns an immutable
 * {@link EvidenceVerificationKeySet} snapshot and an allow-list of evidence meanings. The existing
 * {@link TestSuiteEvidenceVerifier} remains the trust-root implementation for snapshot admission,
 * while {@link EvidenceVerificationSupport} supplies the shared signing-time and Ed25519 checks.
 * Resource Gateway consumes proofs; it does not mint or replace producer authority.</p>
 *
 * <p>Producers must sign the fingerprint returned by {@link #canonicalFingerprint(ResolvedEvidence,
 * AcceptanceContext, String)}. The fingerprint is derived from the stable JSON message returned by
 * {@link #canonicalMessage(ResolvedEvidence, AcceptanceContext, String)} and therefore binds the
 * coordinate, evidence meaning, issuer scope, candidate, environment, observation window, closure,
 * key-set pin, key metadata, and proof validity window.</p>
 */
public final class CapabilityStudioPinnedEvidenceIssuerPolicy implements EvidenceIssuerPolicy {
    /** Canonical message protocol identifier. */
    public static final String CANONICAL_MESSAGE_VERSION =
            "RG.CAPABILITY_STUDIO.EVIDENCE_PROOF_V1";

    private static final Pattern SAFE_REF = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String ALGORITHM = "Ed25519";
    private static final int MAX_CANONICAL_MESSAGE_BYTES = 16 * 1024;
    private static final Duration CLOCK_SKEW = EvidenceVerificationSupport.KEY_CREATION_SKEW;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;
    private final Map<IssuerScope, TrustedIssuer> issuers;
    private final TestSuiteEvidenceVerifier keySetVerifier;

    /**
     * Creates an immutable policy from explicit issuer bindings.
     *
     * @param clock trusted local clock used for key-set freshness and proof expiry
     * @param trustedIssuers complete issuer/scope policy set
     * @throws NullPointerException when the clock or issuer collection is absent
     * @throws IllegalArgumentException when a binding is incomplete, duplicated, or its key-set
     *         snapshot fails the existing trust verifier
     */
    public CapabilityStudioPinnedEvidenceIssuerPolicy(
            Clock clock, Collection<TrustedIssuer> trustedIssuers) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        Objects.requireNonNull(trustedIssuers, "trustedIssuers are required");
        if (trustedIssuers.isEmpty()) {
            throw new IllegalArgumentException("at least one trusted issuer is required");
        }
        this.keySetVerifier = new TestSuiteEvidenceVerifier(clock);
        Map<IssuerScope, TrustedIssuer> configured = new LinkedHashMap<>();
        for (TrustedIssuer issuer : trustedIssuers) {
            if (issuer == null) {
                throw new IllegalArgumentException("trusted issuer is required");
            }
            IssuerScope identity = new IssuerScope(issuer.issuerRef(), issuer.scope());
            if (configured.putIfAbsent(identity, issuer) != null) {
                throw new IllegalArgumentException("issuer and scope must be unique");
            }
            TestSuiteEvidenceVerifier.KeySetVerificationResult result =
                    keySetVerifier.verifyKeySet(issuer.keySet(), issuer.pinnedKeySetFingerprint());
            if (!result.verified()) {
                throw new IllegalArgumentException("trusted issuer key-set is not admissible");
            }
        }
        this.issuers = Map.copyOf(configured);
    }

    /**
     * Declares one exact issuer/scope binding and its pinned verification policy.
     *
     * @param issuerRef producer authority identity
     * @param scope producer authority scope
     * @param allowedEvidenceKinds meanings this issuer may sign
     * @param pinnedKeySetFingerprint independently pinned key-set snapshot fingerprint
     * @param keySet complete signed key lifecycle snapshot
     * @param maxProofTtl maximum duration from proof signing to proof expiry
     */
    public record TrustedIssuer(
            String issuerRef,
            String scope,
            Set<EvidenceKind> allowedEvidenceKinds,
            String pinnedKeySetFingerprint,
            EvidenceVerificationKeySet keySet,
            Duration maxProofTtl) {
        /** Validates and defensively copies one issuer binding. */
        public TrustedIssuer {
            issuerRef = required(issuerRef, SAFE_REF, "issuerRef");
            scope = required(scope, SAFE_REF, "scope");
            Objects.requireNonNull(allowedEvidenceKinds, "allowedEvidenceKinds is required");
            if (allowedEvidenceKinds.isEmpty()
                    || allowedEvidenceKinds.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("allowed evidence kinds are incomplete");
            }
            allowedEvidenceKinds = Set.copyOf(EnumSet.copyOf(allowedEvidenceKinds));
            pinnedKeySetFingerprint = required(
                    pinnedKeySetFingerprint, FINGERPRINT, "pinnedKeySetFingerprint");
            keySet = Objects.requireNonNull(keySet, "keySet is required");
            maxProofTtl = Objects.requireNonNull(maxProofTtl, "maxProofTtl is required");
            if (maxProofTtl.isZero() || maxProofTtl.isNegative()) {
                throw new IllegalArgumentException("maxProofTtl must be positive");
            }
        }
    }

    /**
     * Verifies one resolved evidence artifact against its exact configured issuer.
     *
     * <p>This is deliberately local and deterministic. A normal result is VERIFIED or REJECTED;
     * unexpected runtime and cryptographic failures are converted to a redacted REJECTED decision,
     * never to an UNAVAILABLE result.</p>
     *
     * @param reference exact v2 evidence reference
     * @param evidence resolved payload-free evidence facts
     * @param context immutable stage acceptance context
     * @return stable, payload-free authority decision
     */
    @Override
    public AuthorityDecision verify(
            EvidenceReference reference,
            ResolvedEvidence evidence,
            AcceptanceContext context) {
        try {
            if (reference == null || evidence == null || context == null) {
                return rejected("EVIDENCE_AUTHORITY_INPUT_INVALID");
            }
            if (!reference.coordinate().equals(evidence.coordinate())) {
                return rejected("EVIDENCE_COORDINATE_MISMATCH");
            }
            TrustedIssuer issuer = issuers.get(new IssuerScope(
                    evidence.issuerRef(), evidence.scope()));
            if (issuer == null) {
                return rejected("EVIDENCE_ISSUER_OR_SCOPE_UNKNOWN");
            }
            if (!issuer.allowedEvidenceKinds().contains(evidence.evidenceKind())) {
                return rejected("EVIDENCE_KIND_NOT_ALLOWED");
            }
            TestSuiteEvidenceVerifier.KeySetVerificationResult keySetResult =
                    keySetVerifier.verifyKeySet(issuer.keySet(), issuer.pinnedKeySetFingerprint());
            if (!keySetResult.verified()) {
                return rejected("EVIDENCE_KEY_SET_NOT_TRUSTED");
            }
            AuthorityDecision facts = verifyFacts(issuer, evidence, context);
            if (facts != null) {
                return facts;
            }
            EvidenceVerificationKeySet.KeyPolicy key = issuer.keySet().keys().stream()
                    .filter(candidate -> candidate.keyId().equals(evidence.keyId()))
                    .findFirst().orElse(null);
            if (key == null) {
                return rejected("EVIDENCE_KEY_NOT_IN_PINNED_SET");
            }
            String lifecycleReason = EvidenceVerificationSupport.signingTimePolicyReason(
                    issuer.keySet(), key.keyId(), evidence.signedAt());
            if (!lifecycleReason.isBlank()) {
                return rejected(lifecycleReason);
            }
            String expectedFingerprint = canonicalFingerprint(
                    evidence, context, issuer.pinnedKeySetFingerprint());
            if (!expectedFingerprint.equals(evidence.materialFingerprint())) {
                return rejected("EVIDENCE_MATERIAL_FINGERPRINT_MISMATCH");
            }
            boolean signatureValid;
            try {
                signatureValid = EvidenceVerificationSupport.verifyEd25519(
                        expectedFingerprint, evidence.signature(), key.encodedPublicKey());
            } catch (GeneralSecurityException | RuntimeException invalidSignature) {
                return rejected("EVIDENCE_SIGNATURE_INVALID");
            }
            if (!signatureValid) {
                return rejected("EVIDENCE_SIGNATURE_INVALID");
            }
            return AuthorityDecision.verified();
        } catch (RuntimeException failure) {
            return rejected("EVIDENCE_AUTHORITY_REJECTED");
        }
    }

    /**
     * Builds the stable JSON message whose fingerprint is signed by an evidence producer.
     *
     * @param evidence resolved proof facts; its material fingerprint is intentionally excluded
     * @param context acceptance context that all bound fields must agree with
     * @param pinnedKeySetFingerprint exact key-set snapshot pin selected by the issuer policy
     * @return deterministic canonical JSON message
     * @throws IllegalArgumentException when a required binding field is absent or malformed
     */
    public static String canonicalMessage(
            ResolvedEvidence evidence,
            AcceptanceContext context,
            String pinnedKeySetFingerprint) {
        Objects.requireNonNull(evidence, "evidence is required");
        Objects.requireNonNull(context, "context is required");
        String pin = required(pinnedKeySetFingerprint, FINGERPRINT,
                "pinnedKeySetFingerprint");
        ObjectNode message = JSON.createObjectNode();
        message.put("messageVersion", CANONICAL_MESSAGE_VERSION);
        ObjectNode coordinate = message.putObject("evidenceCoordinate");
        coordinate.put("exactRef", evidence.coordinate().exactRef());
        coordinate.put("fingerprint", evidence.coordinate().fingerprint());
        message.put("evidenceKind", evidence.evidenceKind().name());
        message.put("issuerRef", required(evidence.issuerRef(), SAFE_REF, "issuerRef"));
        message.put("scope", required(evidence.scope(), SAFE_REF, "scope"));
        message.put("candidateArtifactFingerprint", required(
                evidence.candidateArtifactFingerprint(), FINGERPRINT,
                "candidateArtifactFingerprint"));
        message.put("candidateIntentFingerprint", required(
                evidence.candidateIntentFingerprint(), FINGERPRINT,
                "candidateIntentFingerprint"));
        message.put("environmentFingerprint", required(
                evidence.environmentFingerprint(), FINGERPRINT, "environmentFingerprint"));
        message.put("observedFrom", requiredInstant(evidence.observedFrom(), "observedFrom"));
        message.put("observedThrough", requiredInstant(
                evidence.observedThrough(), "observedThrough"));
        message.put("evidenceClosureFingerprint", required(
                evidence.evidenceClosureFingerprint(), FINGERPRINT,
                "evidenceClosureFingerprint"));
        message.put("keySetSnapshotFingerprint", pin);
        message.put("keyId", required(evidence.keyId(), SAFE_REF, "keyId"));
        message.put("algorithm", required(evidence.algorithm(), Pattern.compile(
                "[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"), "algorithm"));
        message.put("signedAt", requiredInstant(evidence.signedAt(), "signedAt"));
        message.put("expiresAt", requiredInstant(evidence.expiresAt(), "expiresAt"));
        try {
            String value = JSON.writeValueAsString(message);
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_MESSAGE_BYTES) {
                throw new IllegalArgumentException("canonical message exceeds its byte limit");
            }
            return value;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("canonical message cannot be encoded");
        }
    }

    /**
     * Computes the exact fingerprint that an evidence producer must sign.
     *
     * @param evidence resolved proof facts; its material fingerprint is excluded
     * @param context acceptance context
     * @param pinnedKeySetFingerprint exact key-set snapshot pin
     * @return SHA-256 fingerprint of the canonical signed message
     */
    public static String canonicalFingerprint(
            ResolvedEvidence evidence,
            AcceptanceContext context,
            String pinnedKeySetFingerprint) {
        String message = canonicalMessage(evidence, context, pinnedKeySetFingerprint);
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("canonical fingerprint unavailable");
        }
    }

    /**
     * Returns a redacted description that never includes proof material or key material.
     *
     * @return redacted policy description
     */
    @Override
    public String toString() {
        return "CapabilityStudioPinnedEvidenceIssuerPolicy[issuerBindings="
                + issuers.size() + ", keyMaterial=REDACTED]";
    }

    private AuthorityDecision verifyFacts(
            TrustedIssuer issuer, ResolvedEvidence evidence, AcceptanceContext context) {
        if (!ALGORITHM.equals(evidence.algorithm())) {
            return rejected("EVIDENCE_SIGNATURE_ALGORITHM_REJECTED");
        }
        if (evidence.keyId() == null || evidence.materialFingerprint() == null
                || evidence.signedAt() == null || evidence.expiresAt() == null
                || evidence.signature() == null) {
            return rejected("EVIDENCE_SIGNATURE_FACTS_INCOMPLETE");
        }
        if (!FINGERPRINT.matcher(evidence.materialFingerprint()).matches()) {
            return rejected("EVIDENCE_MATERIAL_FINGERPRINT_INVALID");
        }
        if (evidence.candidateArtifactFingerprint() == null
                || evidence.candidateIntentFingerprint() == null
                || evidence.environmentFingerprint() == null
                || evidence.observedFrom() == null
                || evidence.observedThrough() == null
                || evidence.evidenceClosureFingerprint() == null) {
            return rejected("EVIDENCE_CONTEXT_FACTS_INCOMPLETE");
        }
        if (!evidence.candidateArtifactFingerprint().equals(
                context.candidateArtifactFingerprint())
                || !evidence.candidateIntentFingerprint().equals(
                context.candidateIntentFingerprint())
                || !evidence.environmentFingerprint().equals(context.environmentFingerprint())
                || !evidence.evidenceClosureFingerprint().equals(
                context.evidenceClosureFingerprint())) {
            return rejected("EVIDENCE_CONTEXT_BINDING_MISMATCH");
        }
        if (evidence.observedThrough().isBefore(evidence.observedFrom())
                || evidence.observedFrom().isBefore(context.executionStartedAt().minus(CLOCK_SKEW))
                || evidence.observedThrough().isAfter(context.decidedAt().plus(CLOCK_SKEW))) {
            return rejected("EVIDENCE_OBSERVED_WINDOW_INVALID");
        }
        if (evidence.expiresAt().isBefore(evidence.signedAt())
                || evidence.expiresAt().equals(evidence.signedAt())
                || !evidence.expiresAt().isAfter(clock.instant())) {
            return rejected("EVIDENCE_EXPIRED");
        }
        try {
            if (Duration.between(evidence.signedAt(), evidence.expiresAt())
                    .compareTo(issuer.maxProofTtl()) > 0) {
                return rejected("EVIDENCE_TTL_EXCEEDED");
            }
            if (evidence.signedAt().isBefore(context.executionStartedAt().minus(CLOCK_SKEW))
                    || evidence.signedAt().isAfter(context.decidedAt().plus(CLOCK_SKEW))) {
                return rejected("EVIDENCE_SIGNING_TIME_OUTSIDE_CONTEXT");
            }
        } catch (RuntimeException invalidTime) {
            return rejected("EVIDENCE_TIME_POLICY_INVALID");
        }
        return null;
    }

    private static AuthorityDecision rejected(String suffix) {
        return AuthorityDecision.rejected(suffix);
    }

    private static String requiredInstant(Instant value, String field) {
        return Objects.requireNonNull(value, field + " is required").toString();
    }

    private static String required(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private record IssuerScope(String issuerRef, String scope) {
    }
}
