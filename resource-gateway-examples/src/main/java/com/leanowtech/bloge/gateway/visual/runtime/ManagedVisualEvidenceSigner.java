package com.leanowtech.bloge.gateway.visual.runtime;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Evidence signer backed by a non-exportable key provider.
 * Only public verification material is cached in Resource Gateway; every signature is locally verified before use.
 */
public final class ManagedVisualEvidenceSigner implements VisualEvidenceSigner {
    private static final String ALGORITHM = "Ed25519";
    private static final int MAX_KEYS = 64;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(1);
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    private final ManagedEvidenceSigningProvider provider;
    private final Settings settings;
    private final Clock clock;
    private final Object refreshLock = new Object();
    private final AtomicLong successfulSignatures = new AtomicLong();
    private final AtomicLong failedSignatures = new AtomicLong();

    private volatile State state;
    private volatile Instant nextUnknownKeyRefreshAt = Instant.MIN;

    public ManagedVisualEvidenceSigner(ManagedEvidenceSigningProvider provider, Settings settings) {
        this(provider, settings, Clock.systemUTC());
    }

    ManagedVisualEvidenceSigner(ManagedEvidenceSigningProvider provider, Settings settings, Clock clock) {
        if (provider == null) {
            throw new IllegalArgumentException("Managed evidence signing provider is required");
        }
        this.provider = provider;
        this.settings = (settings == null ? new Settings(null, null) : settings).validated();
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.state = State.empty(this.clock.instant());
        refresh(true);
        requireUsable(state, this.clock.instant());
    }

    @Override
    public VisualRunEvidenceSeal seal(String materialFingerprint) {
        return seal(
                materialFingerprint,
                UUID.randomUUID().toString(),
                false);
    }

    @Override
    public VisualRunEvidenceSeal seal(
            String materialFingerprint,
            String idempotencyKey) {
        String requestId = normalize(idempotencyKey);
        if (!IDEMPOTENCY_KEY.matcher(requestId).matches()) {
            throw new IllegalArgumentException(
                    "Evidence signing idempotency key is invalid");
        }
        return seal(materialFingerprint, requestId, true);
    }

    private VisualRunEvidenceSeal seal(
            String materialFingerprint,
            String requestId,
            boolean historicalReplayAllowed) {
        String fingerprint = normalize(materialFingerprint);
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("Evidence material fingerprint must be a canonical sha256 value");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return sign(
                        fingerprint,
                        requestId,
                        attempt > 0,
                        historicalReplayAllowed);
            } catch (EvidenceSigningProviderException failure) {
                if (attempt == 0 && "KEY_VERSION_MISMATCH".equals(failure.code())) {
                    refresh(true);
                    continue;
                }
                failedSignatures.incrementAndGet();
                markFailure(failure);
                throw failure;
            } catch (RuntimeException failure) {
                failedSignatures.incrementAndGet();
                markFailure(failure);
                throw failure;
            }
        }
        throw new IllegalStateException("Managed signing retry loop exhausted");
    }

    private VisualRunEvidenceSeal sign(
            String fingerprint,
            String requestId,
            boolean afterRotationRefresh,
            boolean historicalReplayAllowed) {
        State observed = usableState();
        KeyMaterial active = observed.keys().get(observed.activeKeyId());
        if (active == null || !"ACTIVE".equals(active.state())) {
            throw providerFailure("ACTIVE_KEY_UNAVAILABLE", "Managed signing authority has no active key", false);
        }
        ManagedEvidenceSigningProvider.SignatureRequest request =
                new ManagedEvidenceSigningProvider.SignatureRequest("", requestId, active.descriptor().keyId(),
                        ALGORITHM, fingerprint);
        ManagedEvidenceSigningProvider.SignatureResult result;
        try {
            result = provider.sign(request);
        } catch (EvidenceSigningProviderException failure) {
            if (afterRotationRefresh && "KEY_VERSION_MISMATCH".equals(failure.code())) {
                throw providerFailure("ROTATION_UNSTABLE",
                        "Managed signing key changed repeatedly during one signing attempt", true, failure);
            }
            throw failure;
        }
        validateResult(
                request,
                result,
                observed,
                historicalReplayAllowed);
        successfulSignatures.incrementAndGet();
        clearFailure();
        return new VisualRunEvidenceSeal("", fingerprint, ALGORITHM, result.keyId(), result.signedAt(),
                result.signature());
    }

    private void validateResult(ManagedEvidenceSigningProvider.SignatureRequest request,
                                ManagedEvidenceSigningProvider.SignatureResult result,
                                State observed,
                                boolean historicalReplayAllowed) {
        if (result == null
                || !ManagedEvidenceSigningProvider.SignatureResult.SCHEMA_VERSION.equals(result.schemaVersion())
                || !request.requestId().equals(result.requestId())
                || !request.algorithm().equals(result.algorithm())
                || !request.materialFingerprint().equals(result.materialFingerprint())
                || result.signedAt() == null
                || result.signature().isBlank()) {
            throw providerFailure("INVALID_SIGN_RESPONSE",
                    "Managed signing provider returned a response that was not bound to the request", false);
        }
        KeyMaterial resultKey = observed.keys().get(
                result.keyId());
        if (resultKey == null
                || (!historicalReplayAllowed
                && !request.keyId().equals(result.keyId()))
                || "REVOKED".equals(resultKey.state())
                || "DISABLED".equals(resultKey.state())) {
            throw providerFailure(
                    "INVALID_SIGN_RESPONSE",
                    "Managed signing provider returned a disallowed signing key",
                    false);
        }
        Instant now = clock.instant();
        Instant earliest = historicalReplayAllowed
                ? resultKey.descriptor().createdAt()
                : observed.generatedAt();
        if (result.signedAt().isBefore(earliest.minus(MAX_CLOCK_SKEW))
                || result.signedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw providerFailure("INVALID_SIGN_TIME",
                    "Managed signing provider returned an invalid signing time", false);
        }
        Verification verification = verifySignature(resultKey.publicKey(), request.materialFingerprint(),
                result.signature());
        if (!verification.valid()) {
            throw providerFailure("PROVIDER_SIGNATURE_INVALID",
                    "Managed signing provider returned a signature that failed local verification", false);
        }
    }

    @Override
    public Verification verify(VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
        if (seal == null || !seal.signed()) {
            return new Verification(false, "UNSIGNED", "Run evidence has no persisted signature.");
        }
        if (!seal.materialFingerprint().equals(actualMaterialFingerprint)) {
            return new Verification(false, "INVALID", "Run evidence material fingerprint does not match its seal.");
        }
        if (!ALGORITHM.equals(seal.algorithm())) {
            return new Verification(false, "ALGORITHM_UNSUPPORTED", "Run evidence uses an unsupported algorithm.");
        }
        State observed;
        try {
            observed = usableState();
            if (!observed.keys().containsKey(seal.keyId())) {
                refreshUnknownKey();
                observed = usableStateWithoutRefresh();
            }
        } catch (EvidenceSigningProviderException failure) {
            return Verification.unavailable(failure.code() + ": " + failure.getMessage());
        }
        KeyMaterial material = observed.keys().get(seal.keyId());
        if (material == null) {
            return new Verification(false, "KEY_UNAVAILABLE", "Verification key is not available: " + seal.keyId());
        }
        if ("REVOKED".equals(material.state())) {
            return new Verification(false, "KEY_REVOKED", "Verification key has been revoked: " + seal.keyId());
        }
        if ("DISABLED".equals(material.state())) {
            return new Verification(false, "KEY_DISABLED", "Verification key has been disabled: " + seal.keyId());
        }
        if (seal.signedAt().isBefore(material.descriptor().createdAt().minus(MAX_CLOCK_SKEW))) {
            return new Verification(false, "INVALID", "Run evidence predates its verification key.");
        }
        return verifySignature(material.publicKey(), seal.materialFingerprint(), seal.signature());
    }

    private static Verification verifySignature(PublicKey key, String fingerprint, String encodedSignature) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(encodedSignature);
            if (signatureBytes.length != 64) {
                return new Verification(false, "INVALID", "Run evidence signature has an invalid length.");
            }
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes)
                    ? new Verification(true, "VERIFIED", "")
                    : new Verification(false, "INVALID", "Run evidence signature verification failed.");
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return new Verification(false, "INVALID", "Run evidence signature could not be decoded or verified.");
        }
    }

    @Override
    public Optional<VerificationKey> key(String keyId) {
        KeyResolution resolution = resolveKey(keyId);
        return resolution.status() == KeyResolutionStatus.AVAILABLE
                ? Optional.of(resolution.key()) : Optional.empty();
    }

    @Override
    public KeyResolution resolveKey(String keyId) {
        String normalized = normalize(keyId);
        try {
            State observed = usableState();
            KeyMaterial material = observed.keys().get(normalized);
            if (material == null && !normalized.isBlank()) {
                refreshUnknownKey();
                material = usableStateWithoutRefresh().keys().get(normalized);
            }
            return material == null
                    ? KeyResolution.notFound("Evidence verification key was not found.")
                    : KeyResolution.available(material.descriptor());
        } catch (EvidenceSigningProviderException failure) {
            return KeyResolution.providerUnavailable(failure.code() + ": " + failure.getMessage());
        }
    }

    @Override
    public KeySetResolution resolveKeySet() {
        try {
            State observed = usableState();
            List<EvidenceVerificationKeySet.KeyPolicy> keys = observed.keys().values().stream()
                    .map(KeyMaterial::policy)
                    .sorted(java.util.Comparator.comparing(EvidenceVerificationKeySet.KeyPolicy::keyId))
                    .toList();
            return KeySetResolution.available(new EvidenceVerificationKeySet.Source(
                    provider.providerName(), observed.generatedAt(), observed.expiresAt(),
                    observed.activeKeyId(), observed.policyCompleteness(), keys,
                    observed.lifecycleEvents()));
        } catch (EvidenceSigningProviderException failure) {
            return KeySetResolution.providerUnavailable(failure.code() + ": " + failure.getMessage());
        } catch (RuntimeException failure) {
            return KeySetResolution.providerUnavailable("INVALID_KEY_LIFECYCLE_POLICY");
        }
    }

    @Override
    public boolean available() {
        try {
            State observed = usableState();
            KeyMaterial active = observed.keys().get(observed.activeKeyId());
            return active != null && "ACTIVE".equals(active.state());
        } catch (EvidenceSigningProviderException failure) {
            return false;
        }
    }

    @Override
    public Descriptor descriptor() {
        refresh(false);
        State observed = state;
        Instant now = clock.instant();
        boolean available = observed.lastSuccessfulRefreshAt() != null
                && observed.expiresAt().isAfter(now)
                && observed.refreshState() != RefreshState.UNAVAILABLE
                && observed.keys().containsKey(observed.activeKeyId())
                && "ACTIVE".equals(observed.keys().get(observed.activeKeyId()).state());
        Map<String, Long> states = new LinkedHashMap<>();
        observed.keys().values().forEach(key -> states.merge(key.state(), 1L, Long::sum));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keySetSchemaVersion", ManagedEvidenceSigningProvider.KeySet.SCHEMA_VERSION);
        properties.put("signRequestSchemaVersion", ManagedEvidenceSigningProvider.SignatureRequest.SCHEMA_VERSION);
        properties.put("signResponseSchemaVersion", ManagedEvidenceSigningProvider.SignatureResult.SCHEMA_VERSION);
        properties.put("refreshIntervalSeconds", settings.refreshInterval().toSeconds());
        properties.put("unknownKeyRefreshIntervalSeconds", settings.unknownKeyRefreshInterval().toSeconds());
        properties.put("maximumSnapshotLifetimeSeconds", settings.maximumSnapshotLifetime().toSeconds());
        properties.put("failClosedAfterSnapshotExpiry", true);
        properties.put("returnedSignatureLocallyVerified", true);
        properties.put("privateMaterialPresent", false);
        properties.put("keyStates", states);
        properties.put("keySetPolicyAvailable", true);
        properties.put("keySetPolicyCompleteness", observed.policyCompleteness().name());
        properties.put("keyLifecycleEventCount", observed.lifecycleEvents().size());
        properties.put("refreshSuccessCount", observed.refreshSuccessCount());
        properties.put("refreshFailureCount", observed.refreshFailureCount());
        properties.put("lastFailureCode", observed.lastFailureCode());
        KeyMaterial active = observed.keys().get(observed.activeKeyId());
        if (active != null) {
            properties.put("activeProviderKeyVersion", active.providerKeyVersion());
        }
        return new Descriptor("", "MANAGED_KMS_HSM", provider.providerName(), available,
                observed.refreshState().name(), observed.activeKeyId(), true, false, observed.keys().size(),
                observed.lastSuccessfulRefreshAt(), observed.expiresAt(), successfulSignatures.get(),
                failedSignatures.get(), properties);
    }

    private State usableState() {
        refresh(false);
        return usableStateWithoutRefresh();
    }

    private State usableStateWithoutRefresh() {
        State observed = state;
        requireUsable(observed, clock.instant());
        return observed;
    }

    private static void requireUsable(State observed, Instant now) {
        if (observed.refreshState() == RefreshState.UNAVAILABLE
                || observed.lastSuccessfulRefreshAt() == null || !observed.expiresAt().isAfter(now)) {
            throw providerFailure("KEY_SNAPSHOT_UNAVAILABLE",
                    "Managed evidence verification key snapshot is unavailable or expired", true);
        }
    }

    private void refresh(boolean force) {
        Instant now = clock.instant();
        State observed = state;
        if (!force && now.isBefore(observed.nextRefreshAt())) {
            expireIfNeeded(observed, now);
            return;
        }
        synchronized (refreshLock) {
            now = clock.instant();
            observed = state;
            if (!force && now.isBefore(observed.nextRefreshAt())) {
                expireIfNeeded(observed, now);
                return;
            }
            try {
                ManagedEvidenceSigningProvider.KeySet keySet = provider.fetchKeys();
                state = parseKeySet(keySet, now, observed);
            } catch (RuntimeException failure) {
                state = failedState(observed, now, failure);
            }
        }
    }

    private void refreshUnknownKey() {
        Instant now = clock.instant();
        if (now.isBefore(nextUnknownKeyRefreshAt)) {
            return;
        }
        synchronized (refreshLock) {
            now = clock.instant();
            if (now.isBefore(nextUnknownKeyRefreshAt)) {
                return;
            }
            nextUnknownKeyRefreshAt = now.plus(settings.unknownKeyRefreshInterval());
            State observed = state;
            try {
                state = parseKeySet(provider.fetchKeys(), now, observed);
            } catch (RuntimeException failure) {
                state = failedState(observed, now, failure);
            }
        }
    }

    private void expireIfNeeded(State observed, Instant now) {
        if (observed.expiresAt().isAfter(now) || observed.refreshState() == RefreshState.UNAVAILABLE) {
            return;
        }
        synchronized (refreshLock) {
            if (state == observed) {
                state = observed.withRefreshState(RefreshState.UNAVAILABLE, now);
            }
        }
    }

    private State parseKeySet(ManagedEvidenceSigningProvider.KeySet keySet, Instant now, State previous) {
        if (keySet == null
                || (!ManagedEvidenceSigningProvider.KeySet.SCHEMA_VERSION.equals(keySet.schemaVersion())
                && !ManagedEvidenceSigningProvider.KeySet.SCHEMA_VERSION_V1.equals(keySet.schemaVersion()))
                || keySet.generatedAt() == null || keySet.expiresAt() == null
                || keySet.generatedAt().isAfter(now.plus(MAX_CLOCK_SKEW))
                || !keySet.expiresAt().isAfter(keySet.generatedAt())
                || !keySet.expiresAt().isAfter(now)
                || keySet.expiresAt().isAfter(keySet.generatedAt().plus(settings.maximumSnapshotLifetime()))
                || keySet.activeKeyId().isBlank()
                || keySet.keys().isEmpty() || keySet.keys().size() > MAX_KEYS) {
            throw providerFailure("INVALID_KEY_SNAPSHOT", "Managed signing provider returned an invalid key set", false);
        }
        Map<String, KeyMaterial> parsed = new LinkedHashMap<>();
        int activeCount = 0;
        for (ManagedEvidenceSigningProvider.ManagedKey key : keySet.keys()) {
            KeyMaterial material = parseKey(key, now);
            if (parsed.putIfAbsent(material.descriptor().keyId(), material) != null) {
                throw providerFailure("INVALID_KEY_SNAPSHOT", "Managed signing key ids must be unique", false);
            }
            if ("ACTIVE".equals(material.state())) {
                activeCount++;
            }
        }
        KeyMaterial active = parsed.get(keySet.activeKeyId());
        if (activeCount != 1 || active == null || !"ACTIVE".equals(active.state())) {
            throw providerFailure("INVALID_KEY_SNAPSHOT",
                    "Managed signing key set must identify exactly one active key", false);
        }
        EvidenceVerificationKeySet.PolicyCompleteness completeness =
                ManagedEvidenceSigningProvider.KeySet.SCHEMA_VERSION_V1.equals(keySet.schemaVersion())
                        ? EvidenceVerificationKeySet.PolicyCompleteness.CURRENT_STATE_ONLY
                        : policyCompleteness(keySet.policyCompleteness());
        List<EvidenceVerificationKeySet.LifecycleEvent> lifecycleEvents =
                lifecycleEvents(keySet.lifecycleEvents());
        if (completeness == EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE
                && lifecycleEvents.isEmpty()) {
            throw providerFailure("INVALID_KEY_SNAPSHOT",
                    "Complete managed signing policy requires lifecycle events", false);
        }
        try {
            new EvidenceVerificationKeySet.Source(provider.providerName(), keySet.generatedAt(),
                    keySet.expiresAt(), keySet.activeKeyId(), completeness,
                    parsed.values().stream().map(KeyMaterial::policy).toList(), lifecycleEvents);
        } catch (IllegalArgumentException failure) {
            throw providerFailure("INVALID_KEY_SNAPSHOT",
                    "Managed signing lifecycle policy is invalid", false, failure);
        }
        return new State(Map.copyOf(parsed), keySet.activeKeyId(), keySet.generatedAt(), keySet.expiresAt(),
                completeness, lifecycleEvents,
                now, now.plus(settings.refreshInterval()), RefreshState.HEALTHY,
                previous.refreshSuccessCount() + 1, previous.refreshFailureCount(), "");
    }

    private KeyMaterial parseKey(ManagedEvidenceSigningProvider.ManagedKey key, Instant now) {
        if (key == null || key.keyId().isBlank() || key.keyId().length() > 255
                || key.keyId().chars().anyMatch(Character::isISOControl)
                || !ALGORITHM.equals(key.algorithm())
                || key.encodedPublicKey().isBlank() || key.encodedPublicKey().length() > 1024
                || key.createdAt() == null || key.createdAt().isAfter(now.plus(MAX_CLOCK_SKEW))
                || key.notBefore() == null || key.notBefore().isBefore(key.createdAt())
                || (key.notAfter() != null && !key.notAfter().isAfter(key.notBefore()))
                || !List.of("ACTIVE", "VERIFY_ONLY", "DISABLED", "REVOKED").contains(key.state())
                || key.providerKeyVersion().isBlank() || key.providerKeyVersion().length() > 255) {
            throw providerFailure("INVALID_KEY_SNAPSHOT", "Managed signing key metadata is invalid", false);
        }
        try {
            byte[] encoded = Base64.getDecoder().decode(key.encodedPublicKey());
            PublicKey publicKey = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(encoded));
            VerificationKey descriptor = new VerificationKey("", key.keyId(), ALGORITHM,
                    key.encodedPublicKey(), key.createdAt(), key.state(), provider.providerName());
            EvidenceVerificationKeySet.KeyPolicy policy = new EvidenceVerificationKeySet.KeyPolicy(
                    key.keyId(), ALGORITHM, key.encodedPublicKey(), key.createdAt(), key.notBefore(),
                    key.notAfter(), EvidenceVerificationKeySet.KeyState.valueOf(key.state()),
                    key.providerKeyVersion());
            return new KeyMaterial(descriptor, publicKey, key.state(), key.providerKeyVersion(), policy);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw providerFailure("INVALID_KEY_SNAPSHOT", "Managed signing public key is invalid", false, failure);
        }
    }

    private State failedState(State previous, Instant now, RuntimeException failure) {
        Duration retry = settings.refreshInterval().compareTo(Duration.ofSeconds(5)) > 0
                ? Duration.ofSeconds(5) : settings.refreshInterval();
        boolean retryable = failure instanceof EvidenceSigningProviderException providerFailure
                && providerFailure.retryable();
        RefreshState refreshState = retryable && previous.lastSuccessfulRefreshAt() != null
                && previous.expiresAt().isAfter(now) ? RefreshState.DEGRADED : RefreshState.UNAVAILABLE;
        return new State(previous.keys(), previous.activeKeyId(), previous.generatedAt(), previous.expiresAt(),
                previous.policyCompleteness(), previous.lifecycleEvents(),
                previous.lastSuccessfulRefreshAt(), now.plus(retry), refreshState,
                previous.refreshSuccessCount(), previous.refreshFailureCount() + 1, failureCode(failure));
    }

    private void markFailure(RuntimeException failure) {
        synchronized (refreshLock) {
            State observed = state;
            boolean retryable = failure instanceof EvidenceSigningProviderException providerFailure
                    && providerFailure.retryable();
            state = new State(observed.keys(), observed.activeKeyId(), observed.generatedAt(), observed.expiresAt(),
                    observed.policyCompleteness(), observed.lifecycleEvents(),
                    observed.lastSuccessfulRefreshAt(), observed.nextRefreshAt(),
                    retryable ? RefreshState.DEGRADED : RefreshState.UNAVAILABLE,
                    observed.refreshSuccessCount(), observed.refreshFailureCount(), failureCode(failure));
        }
    }

    private void clearFailure() {
        synchronized (refreshLock) {
            State observed = state;
            if (observed.refreshState() == RefreshState.DEGRADED && observed.expiresAt().isAfter(clock.instant())) {
                state = new State(observed.keys(), observed.activeKeyId(), observed.generatedAt(),
                        observed.expiresAt(), observed.policyCompleteness(), observed.lifecycleEvents(),
                        observed.lastSuccessfulRefreshAt(), observed.nextRefreshAt(),
                        RefreshState.HEALTHY, observed.refreshSuccessCount(), observed.refreshFailureCount(), "");
            }
        }
    }

    private static String failureCode(RuntimeException failure) {
        return failure instanceof EvidenceSigningProviderException providerFailure
                ? providerFailure.code() : failure.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private static EvidenceSigningProviderException providerFailure(String code,
                                                                     String message,
                                                                     boolean retryable) {
        return new EvidenceSigningProviderException(code, message, retryable);
    }

    private static EvidenceSigningProviderException providerFailure(String code,
                                                                     String message,
                                                                     boolean retryable,
                                                                     Throwable cause) {
        return new EvidenceSigningProviderException(code, message, retryable, cause);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static EvidenceVerificationKeySet.PolicyCompleteness policyCompleteness(String value) {
        try {
            return EvidenceVerificationKeySet.PolicyCompleteness.valueOf(normalize(value));
        } catch (RuntimeException failure) {
            throw providerFailure("INVALID_KEY_SNAPSHOT",
                    "Managed signing policy completeness is invalid", false, failure);
        }
    }

    private static List<EvidenceVerificationKeySet.LifecycleEvent> lifecycleEvents(
            List<ManagedEvidenceSigningProvider.KeyLifecycleEvent> events) {
        if (events == null) {
            return List.of();
        }
        try {
            return events.stream().map(event -> new EvidenceVerificationKeySet.LifecycleEvent(
                    event.sequence(), event.eventId(), event.keyId(),
                    EvidenceVerificationKeySet.EventType.valueOf(event.type()), event.occurredAt(),
                    event.effectiveAt(), event.revocationMode().isBlank() ? null
                    : EvidenceVerificationKeySet.RevocationMode.valueOf(event.revocationMode()),
                    event.invalidFrom(), event.reasonCode())).toList();
        } catch (RuntimeException failure) {
            throw providerFailure("INVALID_KEY_SNAPSHOT",
                    "Managed signing lifecycle events are invalid", false, failure);
        }
    }

    public record Settings(Duration refreshInterval,
                           Duration unknownKeyRefreshInterval,
                           Duration maximumSnapshotLifetime) {
        public Settings(Duration refreshInterval, Duration maximumSnapshotLifetime) {
            this(refreshInterval, Duration.ofSeconds(5), maximumSnapshotLifetime);
        }

        public Settings validated() {
            Duration refresh = refreshInterval == null ? Duration.ofSeconds(30) : refreshInterval;
            Duration unknown = unknownKeyRefreshInterval == null
                    ? Duration.ofSeconds(5) : unknownKeyRefreshInterval;
            Duration lifetime = maximumSnapshotLifetime == null ? Duration.ofHours(24) : maximumSnapshotLifetime;
            if (refresh.compareTo(Duration.ofSeconds(1)) < 0 || refresh.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("Managed signing refresh interval must be between 1 second and 1 hour");
            }
            if (lifetime.compareTo(refresh) < 0 || lifetime.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException(
                        "Managed signing snapshot lifetime must cover refresh and be at most 7 days");
            }
            if (unknown.compareTo(Duration.ofSeconds(1)) < 0 || unknown.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException(
                        "Managed signing unknown-key refresh interval must be between 1 second and 5 minutes");
            }
            return new Settings(refresh, unknown, lifetime);
        }
    }

    private enum RefreshState {
        BOOTSTRAPPING,
        HEALTHY,
        DEGRADED,
        UNAVAILABLE
    }

    private record KeyMaterial(VerificationKey descriptor,
                               PublicKey publicKey,
                               String state,
                               String providerKeyVersion,
                               EvidenceVerificationKeySet.KeyPolicy policy) {
    }

    private record State(Map<String, KeyMaterial> keys,
                         String activeKeyId,
                         Instant generatedAt,
                         Instant expiresAt,
                         EvidenceVerificationKeySet.PolicyCompleteness policyCompleteness,
                         List<EvidenceVerificationKeySet.LifecycleEvent> lifecycleEvents,
                         Instant lastSuccessfulRefreshAt,
                         Instant nextRefreshAt,
                         RefreshState refreshState,
                         long refreshSuccessCount,
                         long refreshFailureCount,
                         String lastFailureCode) {
        private State {
            keys = keys == null ? Map.of() : Map.copyOf(keys);
            activeKeyId = normalize(activeKeyId);
            generatedAt = generatedAt == null ? Instant.EPOCH : generatedAt;
            expiresAt = expiresAt == null ? Instant.EPOCH : expiresAt;
            policyCompleteness = policyCompleteness == null
                    ? EvidenceVerificationKeySet.PolicyCompleteness.CURRENT_STATE_ONLY
                    : policyCompleteness;
            lifecycleEvents = lifecycleEvents == null ? List.of() : List.copyOf(lifecycleEvents);
            nextRefreshAt = nextRefreshAt == null ? Instant.MIN : nextRefreshAt;
            refreshState = refreshState == null ? RefreshState.UNAVAILABLE : refreshState;
            lastFailureCode = normalize(lastFailureCode);
        }

        static State empty(Instant now) {
            return new State(Map.of(), "", Instant.EPOCH, Instant.EPOCH,
                    EvidenceVerificationKeySet.PolicyCompleteness.CURRENT_STATE_ONLY, List.of(), null, now,
                    RefreshState.BOOTSTRAPPING, 0, 0, "");
        }

        State withRefreshState(RefreshState value, Instant nextRefresh) {
            return new State(keys, activeKeyId, generatedAt, expiresAt,
                    policyCompleteness, lifecycleEvents, lastSuccessfulRefreshAt, nextRefresh,
                    value, refreshSuccessCount, refreshFailureCount, lastFailureCode);
        }
    }
}
