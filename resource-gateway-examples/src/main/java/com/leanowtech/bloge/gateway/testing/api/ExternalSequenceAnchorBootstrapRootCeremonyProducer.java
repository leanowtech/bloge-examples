package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Produces one cross-signed bootstrap-root transition without handling private key material.
 *
 * <p>The producer derives sequence and predecessor from a fully verified current chain, applies
 * an expected-head compare-and-set, sends deterministic requests to independently supplied opaque
 * signers, verifies every returned Ed25519 signature locally, requires both configured quorums,
 * and finally replays the complete candidate bundle through the consumer trust-store verifier.
 * No partial bundle is returned when either quorum or self-verification fails.</p>
 *
 * <p>This class provides protocol-artifact atomicity, not a distributed transaction over HSM
 * signing side effects. A durable ceremony service must persist the request/outcome by ceremony id
 * and add approval, custody, publisher, and recovery controls around this pure kernel.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootCeremonyProducer {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding binding;
    private final Set<String> acceptedPolicyFingerprints;
    private final ExternalSequenceAnchorBootstrapRootGenesis genesis;
    private final String genesisFingerprint;

    /**
     * Creates one deployment-bound producer kernel.
     *
     * @param objectMapper canonical protocol mapper
     * @param clock trusted ceremony host clock
     * @param binding exact consumer-compatible chain and lifecycle policy
     * @param acceptedPolicyFingerprints accepted ceremony policy revisions
     * @param genesis deployment-pinned public-only genesis
     */
    public ExternalSequenceAnchorBootstrapRootCeremonyProducer(
            ObjectMapper objectMapper,
            Clock clock,
            ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            ExternalSequenceAnchorBootstrapRootGenesis genesis) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.acceptedPolicyFingerprints = acceptedPolicies(acceptedPolicyFingerprints);
        this.genesis = Objects.requireNonNull(genesis, "genesis");
        if (!binding.scopeId().equals(genesis.scopeId())
                || !binding.rootSetId().equals(genesis.rootSetId())
                || !binding.trustDomain().equals(genesis.trustDomain())
                || binding.signatureThreshold() != genesis.signatureThreshold()
                || binding.maximumFaults() != genesis.maximumFaults()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root producer genesis does not match its binding");
        }
        this.genesisFingerprint = genesis.materialFingerprint(objectMapper);
    }

    /**
     * Produces sequence one from the deployment-pinned genesis.
     *
     * @param request immutable successor command with genesis fingerprint as expected predecessor
     * @param authorizingAuthorities opaque genesis-root signers
     * @param incomingAuthorities opaque successor-root signers
     * @return complete self-verified sequence-one bundle and aggregate signer attempts
     */
    public CeremonyOutcome begin(
            RotationRequest request,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority>
                    authorizingAuthorities,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> incomingAuthorities) {
        return produce(null, request, authorizingAuthorities, incomingAuthorities);
    }

    /**
     * Appends exactly one successor to a complete, already cross-signed bundle.
     *
     * @param currentBundle untrusted complete current chain
     * @param request immutable successor command with the exact current head as predecessor
     * @param authorizingAuthorities opaque current-root signers
     * @param incomingAuthorities opaque successor-root signers
     * @return complete self-verified successor bundle and aggregate signer attempts
     */
    public CeremonyOutcome append(
            ExternalSequenceAnchorBootstrapRootBundle currentBundle,
            RotationRequest request,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority>
                    authorizingAuthorities,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> incomingAuthorities) {
        return produce(Objects.requireNonNull(currentBundle, "currentBundle"), request,
                authorizingAuthorities, incomingAuthorities);
    }

    private CeremonyOutcome produce(
            ExternalSequenceAnchorBootstrapRootBundle currentBundle,
            RotationRequest request,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority>
                    authorizingAuthorities,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> incomingAuthorities) {
        RotationRequest command = Objects.requireNonNull(request, "request");
        validateCeremonyClock(command.issuedAt());

        CurrentHead current = currentBundle == null
                ? new CurrentHead(1L, genesisFingerprint, genesis.rootKeys(),
                quorumHorizon(genesis.rootKeys(), command.issuedAt(), null))
                : verifiedCurrentHead(currentBundle, command.issuedAt());
        if (!current.previousFingerprint().equals(
                command.expectedPreviousMaterialFingerprint())) {
            throw new CeremonyException(FailureReason.STALE_PREDECESSOR,
                    null, 0, binding.signatureThreshold());
        }
        if (!acceptedPolicyFingerprints.contains(command.policyFingerprint())) {
            throw new CeremonyException(FailureReason.POLICY_NOT_ACCEPTED,
                    null, 0, binding.signatureThreshold());
        }
        if (current.sequence() > ExternalSequenceAnchorBootstrapRootBundle.MAXIMUM_TRANSITIONS
                || current.sequence() > binding.maximumTransitionCount()) {
            throw new CeremonyException(FailureReason.CHAIN_LIMIT_REACHED,
                    null, 0, binding.signatureThreshold());
        }
        validateSuccessorLifecycle(command, current.quorumHorizon());

        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                binding.rootSetId(), current.sequence(), current.previousFingerprint(),
                binding.scopeId(), binding.trustDomain(), binding.signatureThreshold(),
                binding.maximumFaults(), command.incomingRootKeys(),
                command.policyFingerprint(), command.issuedAt(), command.notBefore(),
                command.expiresAt());
        String materialFingerprint = ProtocolFingerprint.of(objectMapper, material);

        List<SignerBinding> authorizers = bindAuthorities(
                authorizingAuthorities, current.authorizingKeys(), command.issuedAt(),
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.AUTHORIZING_ROOT);
        List<SignerBinding> incoming = bindAuthorities(
                incomingAuthorities, command.incomingRootKeys(), command.issuedAt(),
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.INCOMING_ROOT);
        SigningResult oldRootResult = collectSignatures(command, materialFingerprint,
                current.sequence(), ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                        .AUTHORIZING_ROOT, authorizers);
        requireQuorum(oldRootResult,
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.AUTHORIZING_ROOT);
        SigningResult incomingResult = collectSignatures(command, materialFingerprint,
                current.sequence(), ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                        .INCOMING_ROOT, incoming);
        requireQuorum(incomingResult,
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.INCOMING_ROOT);

        var transition = new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, materialFingerprint, oldRootResult.signatures(),
                incomingResult.signatures());
        List<ExternalSequenceAnchorBootstrapRootTransition> transitions = new ArrayList<>();
        if (currentBundle != null) {
            transitions.addAll(currentBundle.transitions());
        }
        transitions.add(transition);
        var bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                genesisFingerprint, transitions, materialFingerprint);
        verifyProducedBundle(bundle, command.notBefore());

        List<SigningAttempt> attempts = new ArrayList<>(oldRootResult.attempts());
        attempts.addAll(incomingResult.attempts());
        return new CeremonyOutcome(CeremonyOutcome.SCHEMA_VERSION,
                command.ceremonyId(), bundle, attempts);
    }

    private CurrentHead verifiedCurrentHead(
            ExternalSequenceAnchorBootstrapRootBundle bundle, Instant issuedAt) {
        try {
            new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
                    objectMapper, Clock.fixed(issuedAt, ZoneOffset.UTC),
                    producerValidationBinding(), acceptedPolicyFingerprints,
                    genesis, new VerificationFloor(), bundle);
            ExternalSequenceAnchorBootstrapRootTransition head =
                    bundle.transitions().getLast();
            return new CurrentHead(head.material().sequence() + 1L,
                    head.materialFingerprint(), head.material().rootKeys(),
                    quorumHorizon(head.material().rootKeys(), issuedAt,
                            head.material().expiresAt()));
        } catch (RuntimeException invalid) {
            throw new CeremonyException(FailureReason.INVALID_CURRENT_CHAIN,
                    null, 0, binding.signatureThreshold(), invalid);
        }
    }

    private void verifyProducedBundle(
            ExternalSequenceAnchorBootstrapRootBundle bundle, Instant notBefore) {
        Instant now = clock.instant();
        Instant verifyAt = now.isAfter(notBefore) ? now : notBefore;
        try {
            new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
                    objectMapper, Clock.fixed(verifyAt, ZoneOffset.UTC), binding,
                    acceptedPolicyFingerprints, genesis, new VerificationFloor(), bundle);
        } catch (RuntimeException invalid) {
            throw new CeremonyException(FailureReason.SELF_VERIFICATION_FAILED,
                    null, 0, binding.signatureThreshold(), invalid);
        }
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding
            producerValidationBinding() {
        return new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding(
                binding.scopeId(), binding.rootSetId(), binding.trustDomain(),
                binding.signatureThreshold(), binding.maximumFaults(),
                binding.maximumRootLifetime(), binding.clockSkew(), Duration.ZERO,
                binding.maximumTransitionCount());
    }

    private List<SignerBinding> bindAuthorities(
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> values,
            List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> expectedKeys,
            Instant issuedAt,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role) {
        List<ExternalSequenceAnchorBootstrapRootSigningAuthority> authorities =
                values == null ? List.of() : new ArrayList<>(values);
        if (authorities.size()
                > ExternalSequenceAnchorBootstrapRootTransition.MAXIMUM_SIGNATURES_PER_ROLE) {
            throw new CeremonyException(FailureReason.SIGNER_BINDING_INVALID,
                    role, 0, binding.signatureThreshold());
        }
        Map<String, ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> keys =
                new HashMap<>();
        for (ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial key : expectedKeys) {
            keys.put(key.authorityId() + '\u0000' + key.keyId(), key);
        }
        Set<String> authorityIds = new HashSet<>();
        List<SignerBinding> bindings = new ArrayList<>();
        for (ExternalSequenceAnchorBootstrapRootSigningAuthority authority : authorities) {
            if (authority == null) {
                throw new CeremonyException(FailureReason.SIGNER_BINDING_INVALID,
                        role, 0, binding.signatureThreshold());
            }
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor descriptor;
            try {
                descriptor = Objects.requireNonNull(
                        authority.descriptor(), "signer descriptor");
            } catch (RuntimeException invalid) {
                throw new CeremonyException(FailureReason.SIGNER_BINDING_INVALID,
                        role, 0, binding.signatureThreshold());
            }
            ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial key = keys.get(
                    descriptor.authorityId() + '\u0000' + descriptor.keyId());
            if (!authorityIds.add(descriptor.authorityId()) || key == null
                    || !key.publicKeyBase64().equals(descriptor.publicKeyBase64())
                    || !activeAt(key, issuedAt)) {
                throw new CeremonyException(FailureReason.SIGNER_BINDING_INVALID,
                        role, 0, binding.signatureThreshold());
            }
            bindings.add(new SignerBinding(authority, descriptor, publicKey(key)));
        }
        bindings.sort(Comparator.comparing(binding -> binding.descriptor().authorityId()));
        if (bindings.size() < binding.signatureThreshold()) {
            throw new CeremonyException(FailureReason.PREFLIGHT_QUORUM_UNAVAILABLE,
                    role, bindings.size(), binding.signatureThreshold());
        }
        return List.copyOf(bindings);
    }

    private SigningResult collectSignatures(
            RotationRequest command,
            String materialFingerprint,
            long sequence,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role,
            List<SignerBinding> signers) {
        List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures =
                new ArrayList<>();
        List<SigningAttempt> attempts = new ArrayList<>();
        for (SignerBinding signer : signers) {
            ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest request =
                    signingRequest(command, materialFingerprint, sequence, role,
                            signer.descriptor());
            ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse response;
            try {
                response = signer.authority().sign(request);
            } catch (RuntimeException unavailable) {
                attempts.add(new SigningAttempt(role, signer.descriptor().authorityId(),
                        signer.descriptor().keyId(), AttemptStatus.UNAVAILABLE));
                continue;
            }
            if (!matches(response, request, signer.descriptor())) {
                attempts.add(new SigningAttempt(role, signer.descriptor().authorityId(),
                        signer.descriptor().keyId(), AttemptStatus.INVALID_RESPONSE));
                continue;
            }
            if (!signatureVerified(signer.publicKey(), materialFingerprint,
                    response.signature())) {
                attempts.add(new SigningAttempt(role, signer.descriptor().authorityId(),
                        signer.descriptor().keyId(), AttemptStatus.INVALID_SIGNATURE));
                continue;
            }
            signatures.add(new TestSuiteStabilityServingInventory.AuthoritySignature(
                    response.authorityId(), response.keyId(), response.algorithm(),
                    response.signedAt(), response.signature()));
            attempts.add(new SigningAttempt(role, signer.descriptor().authorityId(),
                    signer.descriptor().keyId(), AttemptStatus.SIGNED));
        }
        signatures.sort(Comparator.comparing(
                TestSuiteStabilityServingInventory.AuthoritySignature::authorityId)
                .thenComparing(TestSuiteStabilityServingInventory.AuthoritySignature::keyId));
        return new SigningResult(List.copyOf(signatures), List.copyOf(attempts));
    }

    private ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest signingRequest(
            RotationRequest command,
            String materialFingerprint,
            long sequence,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor descriptor) {
        String requestId = ProtocolFingerprint.of(objectMapper, new SigningIntent(
                SigningIntent.SCHEMA_VERSION, command.ceremonyId(), role,
                binding.rootSetId(), sequence, descriptor.authorityId(), descriptor.keyId(),
                materialFingerprint, command.issuedAt()));
        return new ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest
                        .SCHEMA_VERSION,
                requestId, command.ceremonyId(), role, binding.rootSetId(), sequence,
                descriptor.authorityId(), descriptor.keyId(), materialFingerprint,
                command.issuedAt());
    }

    private static boolean matches(
            ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse response,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest request,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor descriptor) {
        return response != null
                && response.requestId().equals(request.requestId())
                && response.authorityId().equals(descriptor.authorityId())
                && response.keyId().equals(descriptor.keyId())
                && response.algorithm().equals(descriptor.algorithm())
                && response.materialFingerprint().equals(request.materialFingerprint())
                && response.signedAt().equals(request.issuedAt());
    }

    private void requireQuorum(
            SigningResult result,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role) {
        if (result.signatures().size() < binding.signatureThreshold()) {
            throw new CeremonyException(FailureReason.SIGNING_QUORUM_UNAVAILABLE,
                    role, result.signatures().size(), binding.signatureThreshold());
        }
    }

    private void validateCeremonyClock(Instant issuedAt) {
        Instant now = clock.instant();
        Duration drift = Duration.between(issuedAt, now).abs();
        if (drift.compareTo(binding.clockSkew()) > 0) {
            throw new CeremonyException(FailureReason.CLOCK_OUT_OF_BOUNDS,
                    null, 0, binding.signatureThreshold());
        }
    }

    private void validateSuccessorLifecycle(
            RotationRequest command, Instant precedingQuorumHorizon) {
        Instant now = clock.instant();
        Instant activationObservation = now.isAfter(command.notBefore())
                ? now : command.notBefore();
        Duration lifetime = Duration.between(command.issuedAt(), command.expiresAt());
        long activeAuthorities = command.incomingRootKeys().stream()
                .filter(key -> activeAt(key, activationObservation))
                .map(ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial::authorityId)
                .distinct().count();
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(binding.maximumRootLifetime()) > 0
                || !activationObservation.isBefore(command.expiresAt())
                || Duration.between(activationObservation, command.expiresAt())
                .compareTo(binding.minimumRemainingValidity()) < 0
                || command.notBefore().isAfter(precedingQuorumHorizon)
                || activeAuthorities < binding.signatureThreshold()) {
            throw new CeremonyException(FailureReason.INVALID_SUCCESSOR_LIFECYCLE,
                    null, 0, binding.signatureThreshold());
        }
    }

    private Instant quorumHorizon(
            List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> keys,
            Instant issuedAt,
            Instant materialExpiresAt) {
        Map<String, Instant> authorityHorizons = new HashMap<>();
        keys.stream().filter(key -> activeAt(key, issuedAt)).forEach(key ->
                authorityHorizons.merge(key.authorityId(), key.expiresAt(),
                        (left, right) -> left.isAfter(right) ? left : right));
        List<Instant> horizons = authorityHorizons.values().stream()
                .sorted(Comparator.reverseOrder()).toList();
        if (horizons.size() < binding.signatureThreshold()) {
            throw new CeremonyException(FailureReason.INVALID_CURRENT_CHAIN,
                    null, 0, binding.signatureThreshold());
        }
        Instant keyHorizon = horizons.get(binding.signatureThreshold() - 1);
        return materialExpiresAt != null && materialExpiresAt.isBefore(keyHorizon)
                ? materialExpiresAt : keyHorizon;
    }

    private static boolean activeAt(
            ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial key,
            Instant at) {
        return key.enabled() && !key.revoked()
                && !at.isBefore(key.notBefore()) && at.isBefore(key.expiresAt());
    }

    private static PublicKey publicKey(
            ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial material) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(
                            material.publicKeyBase64())));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "External bootstrap-root signer public key is invalid", invalid);
        }
    }

    private static boolean signatureVerified(
            PublicKey publicKey, String materialFingerprint, String signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Set<String> acceptedPolicies(Set<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String normalized = normalized(value);
            if (!FINGERPRINT.matcher(normalized).matches() || !result.add(normalized)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root producer accepted ceremony policy is invalid");
            }
        }
        if (result.isEmpty() || result.size() > 32) {
            throw new IllegalArgumentException(
                    "One through 32 bootstrap-root producer policies are required");
        }
        return Set.copyOf(result);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Immutable, compare-and-set successor command.
     *
     * @param schemaVersion ceremony command generation
     * @param ceremonyId stable orchestration/idempotency identity
     * @param expectedPreviousMaterialFingerprint exact expected genesis or current head
     * @param incomingRootKeys complete canonical successor public key set
     * @param policyFingerprint exact accepted ceremony policy
     * @param issuedAt canonical material and signature issuance time
     * @param notBefore inclusive successor activation time
     * @param expiresAt exclusive hard successor deadline
     */
    public record RotationRequest(
            String schemaVersion,
            String ceremonyId,
            String expectedPreviousMaterialFingerprint,
            List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial>
                    incomingRootKeys,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current immutable ceremony command protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootCeremonyRequest.v1";

        /** Rejects ambiguous identity, unordered key material, and invalid lifecycle. */
        public RotationRequest {
            schemaVersion = normalized(schemaVersion);
            ceremonyId = normalized(ceremonyId);
            expectedPreviousMaterialFingerprint = normalized(
                    expectedPreviousMaterialFingerprint);
            incomingRootKeys = ExternalSequenceAnchorBootstrapRootGenesis.immutableKeys(
                    incomingRootKeys);
            policyFingerprint = normalized(policyFingerprint);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(ceremonyId).matches()
                    || !FINGERPRINT.matcher(expectedPreviousMaterialFingerprint).matches()
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !ExternalSequenceAnchorBootstrapRootGenesis.wholeSecond(issuedAt)
                    || !ExternalSequenceAnchorBootstrapRootGenesis.wholeSecond(notBefore)
                    || !ExternalSequenceAnchorBootstrapRootGenesis.wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "External bootstrap-root ceremony request is invalid");
            }
        }
    }

    /** Aggregate status for one signer invocation; provider error text is deliberately absent. */
    public enum AttemptStatus {
        /** Response identity and detached signature were accepted. */
        SIGNED,

        /** Authority invocation failed without exposing provider diagnostics. */
        UNAVAILABLE,

        /** Response did not echo the complete signing request identity. */
        INVALID_RESPONSE,

        /** Response identity matched, but Ed25519 verification failed. */
        INVALID_SIGNATURE
    }

    /**
     * Payload-free signer attempt projection for ceremony audit and operator alerting.
     *
     * @param role old-root authorization or incoming-root possession proof
     * @param authorityId stable authority identity
     * @param keyId rotation-aware key identity
     * @param status bounded outcome classification
     */
    public record SigningAttempt(
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role,
            String authorityId,
            String keyId,
            AttemptStatus status) {

        /** Enforces complete bounded attempt identity. */
        public SigningAttempt {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            if (role == null || !IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches() || status == null) {
                throw new IllegalArgumentException(
                        "External bootstrap-root signing attempt is invalid");
            }
        }
    }

    /**
     * Successful ceremony output.
     *
     * <p>The attempts are operational metadata, not signed trust evidence. Only {@code bundle}
     * has been cryptographically self-verified and is suitable for publication.</p>
     *
     * @param schemaVersion outcome protocol generation
     * @param ceremonyId exact request correlation identity
     * @param bundle complete genesis-to-successor trust artifact
     * @param signingAttempts canonical role/authority signer outcomes
     */
    public record CeremonyOutcome(
            String schemaVersion,
            String ceremonyId,
            ExternalSequenceAnchorBootstrapRootBundle bundle,
            List<SigningAttempt> signingAttempts) {

        /** Current successful producer outcome protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootCeremonyOutcome.v1";

        /** Rejects incomplete or non-canonical outcome projections. */
        public CeremonyOutcome {
            schemaVersion = normalized(schemaVersion);
            ceremonyId = normalized(ceremonyId);
            signingAttempts = signingAttempts == null
                    ? List.of() : List.copyOf(signingAttempts);
            List<SigningAttempt> ordered = signingAttempts.stream()
                    .sorted(Comparator.comparing(SigningAttempt::role)
                            .thenComparing(SigningAttempt::authorityId)
                            .thenComparing(SigningAttempt::keyId))
                    .toList();
            Set<String> identities = new HashSet<>();
            Set<ExternalSequenceAnchorBootstrapRootSigningAuthority.Role> roles =
                    new HashSet<>();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(ceremonyId).matches()
                    || bundle == null || signingAttempts.isEmpty()
                    || signingAttempts.size()
                    > 2 * ExternalSequenceAnchorBootstrapRootTransition
                    .MAXIMUM_SIGNATURES_PER_ROLE
                    || !ordered.equals(signingAttempts)
                    || signingAttempts.stream().anyMatch(attempt -> {
                        roles.add(attempt.role());
                        return !identities.add(attempt.role() + "\u0000"
                                + attempt.authorityId());
                    })
                    || roles.size() != ExternalSequenceAnchorBootstrapRootSigningAuthority
                    .Role.values().length) {
                throw new IllegalArgumentException(
                        "External bootstrap-root ceremony outcome is invalid");
            }
        }
    }

    /** Stable producer failure categories suitable for metrics and workflow decisions. */
    public enum FailureReason {
        /** Existing transition bundle did not pass complete-chain verification. */
        INVALID_CURRENT_CHAIN,

        /** Request expected a genesis or head fingerprint other than the current value. */
        STALE_PREDECESSOR,

        /** Requested policy fingerprint is outside the deployment allowlist. */
        POLICY_NOT_ACCEPTED,

        /** Current chain has reached the configured maximum transition count. */
        CHAIN_LIMIT_REACHED,

        /** Ceremony issue time is outside the trusted clock tolerance. */
        CLOCK_OUT_OF_BOUNDS,

        /** Successor activation or expiry violates lifecycle or quorum continuity. */
        INVALID_SUCCESSOR_LIFECYCLE,

        /** Signer descriptor does not exactly match the expected public root key. */
        SIGNER_BINDING_INVALID,

        /** Supplied signer cohort cannot reach quorum before any authority is called. */
        PREFLIGHT_QUORUM_UNAVAILABLE,

        /** Valid detached signatures did not reach quorum after authority calls. */
        SIGNING_QUORUM_UNAVAILABLE,

        /** Produced complete bundle failed the consumer verifier replay. */
        SELF_VERIFICATION_FAILED
    }

    /**
     * Aggregate ceremony failure without provider exception text, key material, or signatures.
     */
    public static final class CeremonyException extends RuntimeException {
        /** Stable failure classification. */
        private final FailureReason reason;

        /** Affected quorum role, or {@code null} for whole-ceremony failures. */
        private final ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role;

        /** Distinct valid signatures accepted before the failure. */
        private final int acceptedSignatures;

        /** Configured quorum threshold. */
        private final int requiredSignatures;

        private CeremonyException(
                FailureReason reason,
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role,
                int acceptedSignatures,
                int requiredSignatures) {
            this(reason, role, acceptedSignatures, requiredSignatures, null);
        }

        private CeremonyException(
                FailureReason reason,
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role,
                int acceptedSignatures,
                int requiredSignatures,
                Throwable cause) {
            super("Bootstrap-root ceremony failed: " + reason, cause);
            this.reason = Objects.requireNonNull(reason, "reason");
            this.role = role;
            this.acceptedSignatures = acceptedSignatures;
            this.requiredSignatures = requiredSignatures;
        }

        /**
         * Returns the stable machine-readable failure category.
         *
         * @return failure category
         */
        public FailureReason reason() {
            return reason;
        }

        /**
         * Returns the quorum role associated with the failure.
         *
         * @return failed quorum role, or {@code null} for whole-ceremony failures
         */
        public ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role() {
            return role;
        }

        /**
         * Returns the number of accepted distinct signatures before failure.
         *
         * @return accepted signature count
         */
        public int acceptedSignatures() {
            return acceptedSignatures;
        }

        /**
         * Returns the configured quorum threshold.
         *
         * @return required signature count
         */
        public int requiredSignatures() {
            return requiredSignatures;
        }
    }

    private record CurrentHead(
            long sequence,
            String previousFingerprint,
            List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial>
                    authorizingKeys,
            Instant quorumHorizon) {
    }

    private record SignerBinding(
            ExternalSequenceAnchorBootstrapRootSigningAuthority authority,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor descriptor,
            PublicKey publicKey) {
    }

    private record SigningResult(
            List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures,
            List<SigningAttempt> attempts) {
    }

    private record SigningIntent(
            String schemaVersion,
            String ceremonyId,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.Role role,
            String rootSetId,
            long sequence,
            String authorityId,
            String keyId,
            String materialFingerprint,
            Instant issuedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootSigningIntent.v1";
    }

    private static final class VerificationFloor
            implements ExternalSequenceAnchorBootstrapRootPublicationFloor {
        @Override
        public void accept(VerifiedChain chain) {
            Objects.requireNonNull(chain, "chain");
        }

        @Override
        public boolean durable() {
            return true;
        }
    }
}
