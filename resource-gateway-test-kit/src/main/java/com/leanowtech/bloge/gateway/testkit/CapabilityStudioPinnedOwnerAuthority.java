package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AcceptanceContext;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerSignoff;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.SignoffDecision;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed local authority for externally produced Capability Studio owner signatures.
 *
 * <p>Each owner role is bound to explicit actors, one signature issuer and scope, and one
 * independently pinned {@link EvidenceVerificationKeySet}. The existing key-set verifier and
 * lifecycle policy remain the only trust infrastructure. Resource Gateway verifies owner proofs;
 * it never mints signatures or impersonates an owner authority.</p>
 *
 * <p>Producers sign the fingerprint returned by {@link #canonicalFingerprint(OwnerSignoff,
 * ResolvedEvidence, AcceptanceContext, String)}. The signed material excludes the material
 * fingerprint and signature bytes themselves.</p>
 */
public final class CapabilityStudioPinnedOwnerAuthority implements OwnerAuthority {
    /** Canonical owner-signature message protocol identifier. */
    public static final String CANONICAL_MESSAGE_VERSION =
            "RG.CAPABILITY_STUDIO.OWNER_SIGNATURE_V1";

    private static final Pattern SAFE_REF = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern ALGORITHM = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final String ED25519 = "Ed25519";
    private static final int MAX_CANONICAL_MESSAGE_BYTES = 16 * 1024;
    private static final Duration CLOCK_SKEW = EvidenceVerificationSupport.KEY_CREATION_SKEW;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;
    private final Map<String, TrustedOwnerRole> roles;
    private final TestSuiteEvidenceVerifier keySetVerifier;

    /**
     * Creates an immutable owner authority from explicit role bindings.
     *
     * @param clock trusted clock for key-set freshness and signature expiry
     * @param trustedRoles complete owner-role policy set
     * @throws NullPointerException when the clock or role collection is absent
     * @throws IllegalArgumentException when configuration is empty, duplicated, incomplete, or
     *         contains a key-set snapshot rejected by the existing trust verifier
     */
    public CapabilityStudioPinnedOwnerAuthority(
            Clock clock, Collection<TrustedOwnerRole> trustedRoles) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        Objects.requireNonNull(trustedRoles, "trustedRoles are required");
        if (trustedRoles.isEmpty()) {
            throw new IllegalArgumentException("at least one trusted owner role is required");
        }
        this.keySetVerifier = new TestSuiteEvidenceVerifier(clock);
        Map<String, TrustedOwnerRole> configured = new LinkedHashMap<>();
        for (TrustedOwnerRole role : trustedRoles) {
            if (role == null) {
                throw new IllegalArgumentException("trusted owner role is required");
            }
            if (configured.putIfAbsent(role.role(), role) != null) {
                throw new IllegalArgumentException("trusted owner role must be unique");
            }
            TestSuiteEvidenceVerifier.KeySetVerificationResult result =
                    keySetVerifier.verifyKeySet(
                            role.keySet(), role.pinnedKeySetFingerprint());
            if (!result.verified()) {
                throw new IllegalArgumentException("trusted owner key-set is not admissible");
            }
        }
        this.roles = Map.copyOf(configured);
    }

    /**
     * Binds one owner role to actors and an independently pinned signing authority.
     *
     * @param role exact owner role
     * @param allowedActorRefs exact actors permitted to approve for the role
     * @param signatureIssuerRef exact external signature issuer
     * @param scope exact external signature authority scope
     * @param pinnedKeySetFingerprint independently trusted key-set snapshot fingerprint
     * @param keySet complete signed key lifecycle snapshot
     * @param maxSignatureTtl maximum duration from signature creation to expiry
     */
    public record TrustedOwnerRole(
            String role,
            Set<String> allowedActorRefs,
            String signatureIssuerRef,
            String scope,
            String pinnedKeySetFingerprint,
            EvidenceVerificationKeySet keySet,
            Duration maxSignatureTtl) {
        /** Validates and defensively copies one owner-role policy. */
        public TrustedOwnerRole {
            role = required(role, SAFE_REF, "role");
            Objects.requireNonNull(allowedActorRefs, "allowedActorRefs are required");
            if (allowedActorRefs.isEmpty()
                    || allowedActorRefs.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("allowed actor references are incomplete");
            }
            for (String actorRef : allowedActorRefs) {
                required(actorRef, SAFE_REF, "actorRef");
            }
            allowedActorRefs = Set.copyOf(allowedActorRefs);
            signatureIssuerRef = required(
                    signatureIssuerRef, SAFE_REF, "signatureIssuerRef");
            scope = required(scope, SAFE_REF, "scope");
            pinnedKeySetFingerprint = required(
                    pinnedKeySetFingerprint, FINGERPRINT, "pinnedKeySetFingerprint");
            keySet = Objects.requireNonNull(keySet, "keySet is required");
            maxSignatureTtl = Objects.requireNonNull(
                    maxSignatureTtl, "maxSignatureTtl is required");
            if (maxSignatureTtl.isZero() || maxSignatureTtl.isNegative()) {
                throw new IllegalArgumentException("maxSignatureTtl must be positive");
            }
        }
    }

    /**
     * Verifies one resolved owner signature against its role and acceptance context.
     *
     * <p>Verification is local and deterministic. Expected failures and unexpected runtime or
     * cryptographic faults return stable payload-free REJECTED decisions; this implementation does
     * not return UNAVAILABLE.</p>
     *
     * @param signoff verified v2 owner signoff
     * @param signature resolved payload-free signature facts
     * @param context immutable stage acceptance context
     * @return stable payload-free authority decision
     */
    @Override
    public AuthorityDecision verify(
            OwnerSignoff signoff,
            ResolvedEvidence signature,
            AcceptanceContext context) {
        try {
            if (signoff == null || signature == null || context == null) {
                return rejected("OWNER_AUTHORITY_INPUT_INVALID");
            }
            TrustedOwnerRole trusted = roles.get(signoff.role());
            if (trusted == null) {
                return rejected("OWNER_ROLE_UNKNOWN");
            }
            TestSuiteEvidenceVerifier.KeySetVerificationResult keySetResult =
                    keySetVerifier.verifyKeySet(
                            trusted.keySet(), trusted.pinnedKeySetFingerprint());
            if (!keySetResult.verified()) {
                return rejected("OWNER_KEY_SET_NOT_TRUSTED");
            }
            AuthorityDecision projectionFailure = verifyProjection(
                    trusted, signoff, signature, context);
            if (projectionFailure != null) {
                return projectionFailure;
            }
            EvidenceVerificationKeySet.KeyPolicy key = trusted.keySet().keys().stream()
                    .filter(candidate -> candidate.keyId().equals(signature.keyId()))
                    .findFirst().orElse(null);
            if (key == null) {
                return rejected("OWNER_KEY_NOT_IN_PINNED_SET");
            }
            String lifecycleReason = EvidenceVerificationSupport.signingTimePolicyReason(
                    trusted.keySet(), key.keyId(), signature.signedAt());
            if (!lifecycleReason.isBlank()) {
                return rejected(ownerLifecycleCode(lifecycleReason));
            }
            String expectedFingerprint = canonicalFingerprint(
                    signoff, signature, context, trusted.pinnedKeySetFingerprint());
            if (!expectedFingerprint.equals(signature.materialFingerprint())) {
                return rejected("OWNER_SIGNATURE_MATERIAL_FINGERPRINT_MISMATCH");
            }
            boolean valid;
            try {
                valid = EvidenceVerificationSupport.verifyEd25519(
                        expectedFingerprint, signature.signature(), key.encodedPublicKey());
            } catch (GeneralSecurityException | RuntimeException invalidSignature) {
                return rejected("OWNER_SIGNATURE_INVALID");
            }
            return valid ? AuthorityDecision.verified() : rejected("OWNER_SIGNATURE_INVALID");
        } catch (RuntimeException failure) {
            return rejected("OWNER_AUTHORITY_REJECTED");
        }
    }

    /**
     * Builds the deterministic JSON message signed by an external owner authority.
     *
     * @param signoff owner signoff projection
     * @param signature resolved signature facts, excluding material fingerprint and signature bytes
     * @param context immutable stage acceptance context
     * @param pinnedKeySetFingerprint independently trusted key-set snapshot fingerprint
     * @return deterministic canonical JSON message
     * @throws IllegalArgumentException when a required canonical field is absent or malformed
     */
    public static String canonicalMessage(
            OwnerSignoff signoff,
            ResolvedEvidence signature,
            AcceptanceContext context,
            String pinnedKeySetFingerprint) {
        ObjectNode message = canonicalNode(
                signoff, signature, context, pinnedKeySetFingerprint);
        try {
            String value = JSON.writeValueAsString(message);
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_MESSAGE_BYTES) {
                throw new IllegalArgumentException("canonical owner message exceeds its byte limit");
            }
            return value;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("canonical owner message cannot be encoded");
        }
    }

    /**
     * Computes the exact canonical fingerprint an external owner authority must sign.
     *
     * @param signoff owner signoff projection
     * @param signature resolved signature facts, excluding material fingerprint and signature bytes
     * @param context immutable stage acceptance context
     * @param pinnedKeySetFingerprint independently trusted key-set snapshot fingerprint
     * @return SHA-256 fingerprint of the canonical owner-signature message
     */
    public static String canonicalFingerprint(
            OwnerSignoff signoff,
            ResolvedEvidence signature,
            AcceptanceContext context,
            String pinnedKeySetFingerprint) {
        String message = canonicalMessage(
                signoff, signature, context, pinnedKeySetFingerprint);
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("canonical owner fingerprint unavailable");
        }
    }

    /**
     * Returns a redacted description without roles, actors, coordinates, or cryptographic material.
     *
     * @return redacted authority description
     */
    @Override
    public String toString() {
        return "CapabilityStudioPinnedOwnerAuthority[roleBindings="
                + roles.size() + ", authorityMaterial=REDACTED]";
    }

    private AuthorityDecision verifyProjection(
            TrustedOwnerRole trusted,
            OwnerSignoff signoff,
            ResolvedEvidence signature,
            AcceptanceContext context) {
        if (signature.evidenceKind() != EvidenceKind.OWNER_SIGNATURE) {
            return rejected("OWNER_SIGNATURE_KIND_MISMATCH");
        }
        if (!signoff.signatureCoordinate().equals(signature.coordinate())) {
            return rejected("OWNER_SIGNATURE_COORDINATE_MISMATCH");
        }
        if (!trusted.allowedActorRefs().contains(signoff.actorRef())) {
            return rejected("OWNER_ACTOR_NOT_ALLOWED");
        }
        if (signoff.decision() != SignoffDecision.APPROVED) {
            return rejected("OWNER_DECISION_NOT_APPROVED");
        }
        if (!trusted.signatureIssuerRef().equals(signature.issuerRef())
                || !trusted.scope().equals(signature.scope())) {
            return rejected("OWNER_SIGNATURE_ISSUER_SCOPE_MISMATCH");
        }
        if (signature.keyId() == null || signature.algorithm() == null
                || signature.materialFingerprint() == null || signature.signedAt() == null
                || signature.expiresAt() == null || signature.signature() == null) {
            return rejected("OWNER_SIGNATURE_FACTS_INCOMPLETE");
        }
        if (!ED25519.equals(signature.algorithm())) {
            return rejected("OWNER_SIGNATURE_ALGORITHM_REJECTED");
        }
        if (!signoff.signedAt().equals(signature.signedAt())) {
            return rejected("OWNER_SIGNATURE_SIGNED_AT_MISMATCH");
        }
        if (signature.candidateArtifactFingerprint() == null
                || signature.candidateIntentFingerprint() == null
                || signature.environmentFingerprint() == null
                || signature.observedFrom() == null || signature.observedThrough() == null
                || signature.evidenceClosureFingerprint() == null) {
            return rejected("OWNER_SIGNATURE_CONTEXT_FACTS_INCOMPLETE");
        }
        if (!signoff.evidenceClosureFingerprint().equals(context.evidenceClosureFingerprint())
                || !signature.evidenceClosureFingerprint().equals(
                context.evidenceClosureFingerprint())) {
            return rejected("OWNER_SIGNATURE_CLOSURE_MISMATCH");
        }
        if (!signature.candidateArtifactFingerprint().equals(
                context.candidateArtifactFingerprint())
                || !signature.candidateIntentFingerprint().equals(
                context.candidateIntentFingerprint())
                || !signature.environmentFingerprint().equals(context.environmentFingerprint())) {
            return rejected("OWNER_SIGNATURE_CONTEXT_BINDING_MISMATCH");
        }
        if (!signature.observedFrom().equals(context.executionStartedAt())
                || !signature.observedThrough().equals(context.evidenceCompletedAt())) {
            return rejected("OWNER_SIGNATURE_CONTEXT_WINDOW_MISMATCH");
        }
        if (signature.signedAt().isBefore(context.evidenceCompletedAt().minus(CLOCK_SKEW))
                || signature.signedAt().isAfter(context.decidedAt().plus(CLOCK_SKEW))) {
            return rejected("OWNER_SIGNATURE_SIGNING_TIME_OUTSIDE_CONTEXT");
        }
        if (!signature.expiresAt().isAfter(signature.signedAt())
                || !signature.expiresAt().isAfter(clock.instant())) {
            return rejected("OWNER_SIGNATURE_EXPIRED");
        }
        try {
            if (Duration.between(signature.signedAt(), signature.expiresAt())
                    .compareTo(trusted.maxSignatureTtl()) > 0) {
                return rejected("OWNER_SIGNATURE_TTL_EXCEEDED");
            }
        } catch (RuntimeException invalidTime) {
            return rejected("OWNER_SIGNATURE_TIME_POLICY_INVALID");
        }
        return null;
    }

    private static ObjectNode canonicalNode(
            OwnerSignoff signoff,
            ResolvedEvidence signature,
            AcceptanceContext context,
            String pinnedKeySetFingerprint) {
        Objects.requireNonNull(signoff, "signoff is required");
        Objects.requireNonNull(signature, "signature is required");
        Objects.requireNonNull(context, "context is required");
        String pin = required(
                pinnedKeySetFingerprint, FINGERPRINT, "pinnedKeySetFingerprint");
        ObjectNode message = JSON.createObjectNode();
        message.put("messageVersion", CANONICAL_MESSAGE_VERSION);
        ObjectNode result = message.putObject("result");
        result.put("resultId", context.resultId());
        result.put("revision", context.revision());
        result.put("contractId", context.contractId());
        result.put("contractRevision", context.contractRevision());
        ObjectNode owner = message.putObject("ownerSignoff");
        owner.put("role", signoff.role());
        owner.put("actorRef", signoff.actorRef());
        owner.put("decision", signoff.decision().name());
        owner.put("signedAt", signoff.signedAt().toString());
        ObjectNode coordinate = owner.putObject("signatureCoordinate");
        coordinate.put("exactRef", signoff.signatureCoordinate().exactRef());
        coordinate.put("fingerprint", signoff.signatureCoordinate().fingerprint());
        ObjectNode candidate = message.putObject("candidate");
        candidate.put("artifactFingerprint", context.candidateArtifactFingerprint());
        candidate.put("intentFingerprint", context.candidateIntentFingerprint());
        candidate.put("environmentFingerprint", context.environmentFingerprint());
        ObjectNode window = message.putObject("acceptanceWindow");
        window.put("executionStartedAt", context.executionStartedAt().toString());
        window.put("evidenceCompletedAt", context.evidenceCompletedAt().toString());
        window.put("decidedAt", context.decidedAt().toString());
        ObjectNode observed = message.putObject("signatureObservedWindow");
        observed.put("observedFrom", requiredInstant(
                signature.observedFrom(), "observedFrom"));
        observed.put("observedThrough", requiredInstant(
                signature.observedThrough(), "observedThrough"));
        message.put("evidenceClosureFingerprint", context.evidenceClosureFingerprint());
        ObjectNode authority = message.putObject("signatureAuthority");
        authority.put("issuerRef", required(
                signature.issuerRef(), SAFE_REF, "issuerRef"));
        authority.put("scope", required(signature.scope(), SAFE_REF, "scope"));
        ObjectNode key = message.putObject("verificationKey");
        key.put("keySetSnapshotFingerprint", pin);
        key.put("keyId", required(signature.keyId(), SAFE_REF, "keyId"));
        key.put("algorithm", required(signature.algorithm(), ALGORITHM, "algorithm"));
        ObjectNode validity = message.putObject("signatureValidity");
        validity.put("signedAt", requiredInstant(signature.signedAt(), "signedAt"));
        validity.put("expiresAt", requiredInstant(signature.expiresAt(), "expiresAt"));
        return message;
    }

    private static String ownerLifecycleCode(String evidenceCode) {
        return evidenceCode.startsWith("EVIDENCE_")
                ? "OWNER_" + evidenceCode.substring("EVIDENCE_".length()) : evidenceCode;
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
}
