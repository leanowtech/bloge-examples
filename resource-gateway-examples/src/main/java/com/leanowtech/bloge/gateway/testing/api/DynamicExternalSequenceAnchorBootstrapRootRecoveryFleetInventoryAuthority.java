package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.LaneResolver;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Atomically refreshes a witnessed recovery-fleet inventory publication from bounded HTTPS.
 *
 * <p>Every modified response is verified as four nested authorities before it becomes observable:
 * the exact inventory, its deployment-authority publication, an independent witness checkpoint,
 * and the durable publication floor. An {@code ACTIVE} publication additionally resolves every
 * signed lane through the reviewed local runtime catalog and requires exact descriptor equality.
 * A {@code REVOKED} publication deliberately performs no runtime resolution, so removal of a
 * revoked lane cannot prevent governance from withdrawing it.</p>
 *
 * <p>Refresh uses strict media/protocol negotiation, strong ETags, no redirects, bounded response
 * size, request deadlines, and fixed-delay jitter. A failed refresh retains the last verified
 * object for diagnostics but makes admission unavailable immediately. Observation, descriptor,
 * inventory snapshot, and refresh snapshot reads perform no network or database I/O.</p>
 */
public final class DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
        implements ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority,
        AutoCloseable {

    /** Version-negotiated media type required from the remote publication endpoint. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.bootstrap-root-recovery-fleet-inventory.v1+json";
    /** Exact response header preventing downgrade through generic JSON. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-Recovery-Fleet-Inventory-Protocol";
    /** Aggregate authority implementation identity. */
    public static final String SOURCE_TYPE =
            "DYNAMIC_HTTPS_SIGNED_PUBLICATION_WITH_WITNESS";

    private static final Logger log = LoggerFactory.getLogger(
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.class);
    private static final int MAXIMUM_DOCUMENT_BYTES = 512 * 1024;
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_PUBLICATION_LIFETIME = Duration.ofDays(1);
    private static final Pattern STRONG_ETAG =
            Pattern.compile("\"[\\x21\\x23-\\x7E]{1,256}\"");

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String trustDomain;
    private final Set<String> acceptedPolicyFingerprints;
    private final int signatureThreshold;
    private final List<AuthorityKey> authorityKeys;
    private final Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            indexedAuthorityKeys;
    private final VerifiedBinding binding;
    private final LaneResolver laneResolver;
    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            publicationFloor;
    private final String witnessDomain;
    private final int witnessSignatureThreshold;
    private final List<AuthorityKey> witnessKeys;
    private final Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            indexedWitnessKeys;
    private final Settings settings;
    private final DocumentFetcher fetcher;
    private final Object refreshLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    private volatile RefreshState refreshState = RefreshState.empty();
    private volatile boolean closed;

    /**
     * Bootstraps one remote publication and starts background refresh.
     *
     * @param objectMapper canonical application protocol mapper
     * @param trustDomain exact inventory/publication trust domain
     * @param acceptedPolicyFingerprints accepted inventory/publication policy revisions
     * @param signatureThreshold required distinct deployment-authority signatures
     * @param authorityKeys public inventory/publication verification keys
     * @param binding exact local deployment artifact and fixed fleet topology
     * @param laneResolver reviewed non-blocking local runtime catalog
     * @param publicationFloor durable cross-restart publication/witness floor
     * @param witnessDomain exact independent witness trust domain
     * @param witnessSignatureThreshold required distinct witness signatures
     * @param witnessKeys independent public witness verification keys
     * @param settings bounded remote refresh and freshness policy
     */
    public DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
            ObjectMapper objectMapper,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            VerifiedBinding binding,
            LaneResolver laneResolver,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                    publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<AuthorityKey> witnessKeys,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), trustDomain, acceptedPolicyFingerprints,
                signatureThreshold, authorityKeys, binding, laneResolver, publicationFloor,
                witnessDomain, witnessSignatureThreshold, witnessKeys, settings, null, true);
    }

    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            VerifiedBinding binding,
            LaneResolver laneResolver,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                    publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<AuthorityKey> witnessKeys,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.trustDomain = normalized(trustDomain);
        this.acceptedPolicyFingerprints =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.acceptedPolicies(
                        acceptedPolicyFingerprints);
        this.signatureThreshold = signatureThreshold;
        this.authorityKeys = List.copyOf(authorityKeys == null ? List.of() : authorityKeys);
        this.indexedAuthorityKeys = ConfiguredTestSuiteStabilityServingInventoryAuthority
                .indexedKeys(this.authorityKeys.stream().map(AuthorityKey::delegate).toList(),
                        signatureThreshold);
        this.binding = Objects.requireNonNull(binding, "binding");
        this.laneResolver = Objects.requireNonNull(laneResolver, "laneResolver");
        this.publicationFloor = Objects.requireNonNull(publicationFloor, "publicationFloor");
        this.witnessDomain = normalized(witnessDomain);
        this.witnessSignatureThreshold = witnessSignatureThreshold;
        this.witnessKeys = List.copyOf(witnessKeys == null ? List.of() : witnessKeys);
        this.indexedWitnessKeys = ConfiguredTestSuiteStabilityServingInventoryAuthority
                .indexedKeys(this.witnessKeys.stream().map(AuthorityKey::delegate).toList(),
                        witnessSignatureThreshold);
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        if (!this.publicationFloor.durable()) {
            throw new IllegalArgumentException(
                    "Dynamic recovery-fleet inventory requires a durable publication floor");
        }
        if (this.trustDomain.isBlank() || this.witnessDomain.isBlank()
                || this.trustDomain.equals(this.witnessDomain)
                || !independentAuthorities(this.authorityKeys, this.witnessKeys)) {
            throw new IllegalArgumentException(
                    "Recovery-fleet publication and witness authorities must be independent");
        }
        this.fetcher = fetcher == null ? new HttpDocumentFetcher(this.settings) : fetcher;
        if (!refresh() || !observation().available()) {
            throw new IllegalStateException(
                    "Dynamic recovery-fleet inventory publication bootstrap is unavailable");
        }
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Parses strict public-key configuration and constructs a dynamic authority.
     *
     * @param objectMapper canonical application protocol mapper
     * @param trustDomain exact inventory/publication trust domain
     * @param acceptedPolicies comma-separated accepted policy fingerprints
     * @param signatureThreshold required distinct deployment-authority signatures
     * @param authorityKeysJson public deployment-authority key array
     * @param binding exact local deployment artifact and fixed topology
     * @param laneResolver reviewed non-blocking local runtime catalog
     * @param publicationFloor durable publication/witness floor
     * @param witnessDomain independent witness trust domain
     * @param witnessSignatureThreshold required distinct witness signatures
     * @param witnessKeysJson public witness key array
     * @param settings bounded remote refresh and freshness policy
     * @return bootstrapped dynamic authority
     */
    public static DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            fromJson(
            ObjectMapper objectMapper,
            String trustDomain,
            String acceptedPolicies,
            int signatureThreshold,
            String authorityKeysJson,
            VerifiedBinding binding,
            LaneResolver laneResolver,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                    publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            String witnessKeysJson,
            Settings settings) {
        try {
            ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                    objectMapper, trustDomain,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parsePolicies(
                            acceptedPolicies),
                    signatureThreshold, parseKeys(strict, authorityKeysJson), binding,
                    laneResolver, publicationFloor, witnessDomain, witnessSignatureThreshold,
                    parseKeys(strict, witnessKeysJson), settings);
        } catch (RuntimeException | IOException | java.security.GeneralSecurityException invalid) {
            throw new IllegalArgumentException(
                    "Dynamic recovery-fleet inventory trust configuration is invalid", invalid);
        }
    }

    /** Returns the current exact active inventory without remote or database I/O. */
    @Override
    public Snapshot snapshot() {
        Observation observed = observation();
        RefreshState state = refreshState;
        if (!observed.available() || state.inventorySnapshot() == null
                || state.inventorySnapshot().generation() != observed.generation()) {
            throw new IllegalStateException(
                    "Dynamic recovery-fleet inventory authority is " + observed.status());
        }
        return state.inventorySnapshot();
    }

    /** Re-evaluates all local freshness and revocation fences without external I/O. */
    @Override
    public Observation observation() {
        RefreshState state = refreshState;
        return observation(state, clock.instant());
    }

    private Observation observation(RefreshState state, Instant now) {
        if (state.publication() == null || state.verifiedInventory() == null) {
            throw new IllegalStateException(
                    "Dynamic recovery-fleet inventory publication is not bootstrapped");
        }
        Material inventory = state.verifiedInventory().material();
        boolean available = false;
        String status;
        if (closed) {
            status = "CLOSED";
        } else if (state.localState() != LocalState.HEALTHY) {
            status = "REFRESH_UNAVAILABLE";
        } else if (state.lastSuccessfulRefreshAt() == null
                || !now.isBefore(state.lastSuccessfulRefreshAt()
                .plus(settings.maximumSnapshotAge()))) {
            status = "SOURCE_EXPIRED";
        } else if (now.isBefore(state.publication().material().notBefore())) {
            status = "PUBLICATION_NOT_YET_VALID";
        } else if (!now.isBefore(state.publication().material().expiresAt())) {
            status = "PUBLICATION_EXPIRED";
        } else if (now.isBefore(state.publication().witness().material().notBefore())) {
            status = "WITNESS_NOT_YET_VALID";
        } else if (!now.isBefore(state.publication().witness().material().expiresAt())) {
            status = "WITNESS_EXPIRED";
        } else if (state.publication().material().state() == State.REVOKED) {
            status = "REVOKED";
        } else if (now.isBefore(inventory.notBefore())) {
            status = "INVENTORY_NOT_YET_VALID";
        } else if (!now.isBefore(inventory.expiresAt())) {
            status = "INVENTORY_EXPIRED";
        } else {
            status = "VERIFIED";
            available = true;
        }
        return new Observation(Observation.SCHEMA_VERSION, available, status, SOURCE_TYPE,
                inventory.generation(), inventory.laneDescriptors().size(),
                inventory.expiresAt(), state.verifiedInventory().validSignatureCount(),
                signatureThreshold);
    }

    /** Returns the exact deployment and fixed topology bound by every accepted publication. */
    @Override
    public VerifiedBinding verifiedBinding() {
        return binding;
    }

    /** Publishes aggregate refresh truth without endpoint, ETag, key, policy, or fingerprints. */
    @Override
    public Descriptor descriptor() {
        RefreshState state = refreshState;
        Observation observed = observation(state, clock.instant());
        return new Descriptor(Descriptor.SCHEMA_VERSION, true, true,
                observed.available(), observed.status(), observed.generation(),
                observed.laneCount(), Map.ofEntries(
                Map.entry("sourceType", SOURCE_TYPE),
                Map.entry("protocolVersion",
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                                .SCHEMA_VERSION),
                Map.entry("privateMaterialPresent", false),
                Map.entry("signatureThreshold", signatureThreshold),
                Map.entry("runtimeExpiryFence", true),
                Map.entry("fleetTopologyBound", true),
                Map.entry("exactRuntimeBinding", true),
                Map.entry("automaticRefresh", scheduler != null && !closed),
                Map.entry("signedRevocation", true),
                Map.entry("durableGenerationFloor", true),
                Map.entry("refreshState", effectiveRefreshState(state)),
                Map.entry("publicationState", state.publication().material().state().name()),
                Map.entry("publicationSequence", state.publication().material().sequence()),
                Map.entry("conditionalRequests", true),
                Map.entry("failClosedOnRefreshFailure", true),
                Map.entry("witnessedPublications", true),
                Map.entry("witnessSignatureThreshold", witnessSignatureThreshold),
                Map.entry("refreshIntervalSeconds", settings.refreshInterval().toSeconds()),
                Map.entry("maximumSnapshotAgeSeconds",
                        settings.maximumSnapshotAge().toSeconds()),
                Map.entry("externallyAnchoredPublicationFloor",
                        publicationFloor.externallyAnchored()),
                Map.entry("byzantineQuorumAnchoredPublicationFloor",
                        publicationFloor.byzantineQuorumAnchored())));
    }

    /**
     * Returns fixed-cardinality local refresh telemetry without external I/O.
     *
     * @return aggregate process-local refresh snapshot
     */
    public RefreshSnapshot refreshSnapshot() {
        RefreshState state = refreshState;
        Observation observed = observation(state, clock.instant());
        return new RefreshSnapshot(RefreshSnapshot.SCHEMA_VERSION, observed.available(),
                effectiveRefreshState(state), state.publication().material().state().name(),
                state.publication().material().sequence(), observed.generation(),
                state.lastSuccessfulRefreshAt(), state.refreshSuccessCount(),
                state.refreshFailureCount(), state.lastFailureCode(),
                settings.refreshInterval().toSeconds(),
                settings.maximumSnapshotAge().toSeconds(), witnessSignatureThreshold,
                publicationFloor.durable(), publicationFloor.externallyAnchored(),
                publicationFloor.byzantineQuorumAnchored());
    }

    /** Stops background refresh and immediately closes local inventory admission. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(
                        Math.min(1_000L, settings.requestTimeout().toMillis()),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean refreshNow() {
        return refresh();
    }

    private boolean refresh() {
        synchronized (refreshLock) {
            if (closed) {
                return false;
            }
            Instant now = clock.instant();
            RefreshState previous = refreshState;
            try {
                FetchedDocument fetched = fetcher.fetch(
                        settings.publicationUri(), previous.etag(),
                        settings.requestTimeout());
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        publication = fetched.notModified()
                        ? cachedPublication(previous) : parse(fetched.body());
                requireEtagConsistency(fetched, publication, previous);
                VerifiedPublication verified = verify(publication, previous, now);
                String etag = fetched.etag();
                refreshState = new RefreshState(publication, verified.inventory(),
                        verified.inventorySnapshot(), etag, LocalState.HEALTHY, now,
                        previous.refreshSuccessCount() + 1,
                        previous.refreshFailureCount(), "");
                refreshFailureLogged.set(false);
                return true;
            } catch (RuntimeException unavailable) {
                refreshState = previous.failed(failureCode(unavailable));
                if (refreshFailureLogged.compareAndSet(false, true)) {
                    log.warn("Dynamic recovery-fleet inventory refresh failed; "
                            + "recovery admission is now fail-closed");
                }
                return false;
            }
        }
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
            cachedPublication(RefreshState previous) {
        if (previous.publication() == null || previous.etag().isBlank()) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory source returned 304 before bootstrap");
        }
        return previous.publication();
    }

    private static void requireEtagConsistency(
            FetchedDocument fetched,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication,
            RefreshState previous) {
        if (previous.publication() == null) {
            return;
        }
        if (fetched.notModified() && !fetched.etag().equals(previous.etag())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory 304 changed its ETag");
        }
        if (!fetched.notModified() && fetched.etag().equals(previous.etag())
                && !publication.equals(previous.publication())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory source reused an ETag for changed content");
        }
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication parse(
            byte[] body) {
        try {
            return objectMapper.readValue(body,
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory publication JSON is invalid", invalid);
        }
    }

    private VerifiedPublication verify(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication,
            RefreshState previous,
            Instant now) {
        var material = publication.material();
        if (!publication.fingerprintVerified(objectMapper)
                || !publication.witness().fingerprintVerified(objectMapper)
                || !trustDomain.equals(material.trustDomain())
                || !binding.deploymentScopeId().equals(material.deploymentScopeId())
                || !binding.fleetId().equals(material.fleetId())
                || !acceptedPolicyFingerprints.contains(material.policyFingerprint())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory publication identity or policy is invalid");
        }
        requireCurrentWindow(material.issuedAt(), material.notBefore(),
                material.expiresAt(), now, "Recovery-fleet inventory publication");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                indexedAuthorityKeys, signatureThreshold,
                signatures(publication.signatures()), publication.materialFingerprint(),
                material.issuedAt(), material.expiresAt(), now,
                "Recovery-fleet inventory publication");

        var witness = publication.witness().material();
        if (!witnessDomain.equals(witness.witnessDomain())
                || !binding.deploymentScopeId().equals(witness.deploymentScopeId())
                || !binding.fleetId().equals(witness.fleetId())
                || witness.issuedAt().isBefore(material.issuedAt().minus(CLOCK_SKEW))
                || witness.notBefore().isAfter(material.notBefore().plus(CLOCK_SKEW))
                || witness.expiresAt().isBefore(material.expiresAt())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory witness binding is invalid");
        }
        requireCurrentWindow(witness.issuedAt(), witness.notBefore(), witness.expiresAt(),
                now, "Recovery-fleet inventory witness");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                indexedWitnessKeys, witnessSignatureThreshold,
                signatures(publication.witness().signatures()),
                publication.witness().materialFingerprint(), witness.issuedAt(),
                witness.expiresAt(), now, "Recovery-fleet inventory witness");
        requireSuccessor(publication, previous.publication());

        int validInventorySignatures =
                ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .verifyEnvelope(objectMapper, trustDomain,
                                acceptedPolicyFingerprints, indexedAuthorityKeys,
                                signatureThreshold, publication.inventory(), binding, now);
        VerifiedInventory verifiedInventory = new VerifiedInventory(
                publication.inventory().material(), validInventorySignatures);
        Snapshot inventorySnapshot = material.state() == State.ACTIVE
                ? ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .resolveSnapshot(publication.inventory().material(), laneResolver)
                : null;
        publicationFloor.accept(publicationGeneration(publication));
        return new VerifiedPublication(verifiedInventory, inventorySnapshot);
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.Generation
            publicationGeneration(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                .Generation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                        .Generation.SCHEMA_VERSION,
                binding.deploymentScopeId(), binding.fleetId(),
                publication.material().sequence(),
                publication.inventory().material().generation(),
                publication.inventory().materialFingerprint(),
                publication.materialFingerprint(),
                publication.witness().materialFingerprint(),
                publication.material().state(),
                publication.material().previousPublicationFingerprint(),
                publication.witness().material().previousWitnessFingerprint());
    }

    private static void requireCurrentWindow(
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            Instant now,
            String label) {
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_PUBLICATION_LIFETIME) > 0
                || issuedAt.isAfter(now.plus(CLOCK_SKEW))
                || now.isBefore(notBefore) || !now.isBefore(expiresAt)) {
            throw new IllegalArgumentException(label + " freshness is invalid");
        }
    }

    private static void requireSuccessor(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication next,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication previous) {
        if (previous == null) {
            return;
        }
        long nextGeneration = next.inventory().material().generation();
        long previousGeneration = previous.inventory().material().generation();
        if (nextGeneration < previousGeneration
                || nextGeneration == previousGeneration
                && !next.inventory().materialFingerprint().equals(
                previous.inventory().materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory generation is rolled back or forked");
        }
        long nextSequence = next.material().sequence();
        long previousSequence = previous.material().sequence();
        if (nextSequence == previousSequence) {
            if (!next.materialFingerprint().equals(previous.materialFingerprint())
                    || !next.witness().materialFingerprint().equals(
                    previous.witness().materialFingerprint())
                    || !next.inventory().materialFingerprint().equals(
                    previous.inventory().materialFingerprint())) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory publication sequence is forked");
            }
            return;
        }
        if (nextSequence != previousSequence + 1
                || !next.material().previousPublicationFingerprint().equals(
                previous.materialFingerprint())
                || !next.witness().material().previousWitnessFingerprint().equals(
                previous.witness().materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory publication chain is rolled back or discontinuous");
        }
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-recovery-fleet-inventory-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        long intervalMillis = settings.refreshInterval().toMillis();
        long initialDelayMillis = ThreadLocalRandom.current().nextLong(
                Math.max(1L, intervalMillis / 2L), intervalMillis + 1L);
        executor.scheduleWithFixedDelay(this::refreshSafely, initialDelayMillis,
                intervalMillis, TimeUnit.MILLISECONDS);
        return executor;
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException failure) {
            synchronized (refreshLock) {
                refreshState = refreshState.failed("REFRESH_TASK_FAILED");
            }
        }
    }

    private String effectiveRefreshState(RefreshState state) {
        if (closed) {
            return "CLOSED";
        }
        if (state.localState() == LocalState.HEALTHY
                && (state.lastSuccessfulRefreshAt() == null
                || !clock.instant().isBefore(state.lastSuccessfulRefreshAt()
                .plus(settings.maximumSnapshotAge())))) {
            return "EXPIRED";
        }
        return state.localState().name();
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof RemotePublicationUnavailableException) {
            return "REMOTE_AUTHORITY_UNAVAILABLE";
        }
        if (failure instanceof IllegalArgumentException) {
            return "REMOTE_DOCUMENT_INVALID";
        }
        return "REMOTE_REFRESH_FAILED";
    }

    private static List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures(
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                    .AuthoritySignature> values) {
        return values.stream().map(signature ->
                new TestSuiteStabilityServingInventory.AuthoritySignature(
                        signature.authorityId(), signature.keyId(), signature.algorithm(),
                        signature.signedAt(), signature.signature())).toList();
    }

    private static List<AuthorityKey> parseKeys(ObjectMapper mapper, String json)
            throws IOException, java.security.GeneralSecurityException {
        return ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(mapper, json)
                .stream().map(key -> new AuthorityKey(key.authorityId(), key.keyId(),
                        key.publicKey(), key.notBefore(), key.expiresAt(), key.enabled(),
                        key.revoked())).toList();
    }

    private static boolean independentAuthorities(
            List<AuthorityKey> deployment,
            List<AuthorityKey> witness) {
        Set<String> deploymentAuthorities = new HashSet<>();
        Set<String> deploymentPublicKeys = new HashSet<>();
        deployment.forEach(key -> {
            deploymentAuthorities.add(key.authorityId());
            deploymentPublicKeys.add(Base64.getEncoder().encodeToString(
                    key.publicKey().getEncoded()));
        });
        return witness.stream().noneMatch(key ->
                deploymentAuthorities.contains(key.authorityId())
                        || deploymentPublicKeys.contains(Base64.getEncoder().encodeToString(
                        key.publicKey().getEncoded())));
    }

    private enum LocalState {
        BOOTSTRAPPING,
        HEALTHY,
        UNAVAILABLE
    }

    private record VerifiedInventory(Material material, int validSignatureCount) {

        private VerifiedInventory {
            material = Objects.requireNonNull(material, "material");
            if (validSignatureCount < 1 || validSignatureCount > 32) {
                throw new IllegalArgumentException("Invalid verified inventory signature count");
            }
        }
    }

    private record VerifiedPublication(
            VerifiedInventory inventory,
            Snapshot inventorySnapshot) {

        private VerifiedPublication {
            inventory = Objects.requireNonNull(inventory, "inventory");
        }
    }

    private record RefreshState(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication,
            VerifiedInventory verifiedInventory,
            Snapshot inventorySnapshot,
            String etag,
            LocalState localState,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode) {

        private RefreshState {
            etag = normalized(etag);
            localState = localState == null ? LocalState.UNAVAILABLE : localState;
            lastFailureCode = normalized(lastFailureCode);
            if (refreshSuccessCount < 0 || refreshFailureCount < 0) {
                throw new IllegalArgumentException("Invalid refresh counters");
            }
        }

        private static RefreshState empty() {
            return new RefreshState(null, null, null, "", LocalState.BOOTSTRAPPING,
                    null, 0, 0, "");
        }

        private RefreshState failed(String failureCode) {
            return new RefreshState(publication, verifiedInventory, inventorySnapshot, etag,
                    LocalState.UNAVAILABLE, lastSuccessfulRefreshAt, refreshSuccessCount,
                    refreshFailureCount + 1, failureCode);
        }
    }

    /**
     * Bounded remote source policy.
     *
     * <p>Maximum snapshot age is a hard local fence and must cover at least one refresh interval
     * plus one request timeout.</p>
     *
     * @param publicationUri HTTPS signed-publication endpoint
     * @param refreshInterval fixed-delay background refresh interval
     * @param requestTimeout per-fetch connect and request timeout
     * @param maximumSnapshotAge hard local snapshot freshness fence
     * @param allowInsecureLoopback local-test-only HTTP loopback escape hatch
     */
    public record Settings(
            URI publicationUri,
            Duration refreshInterval,
            Duration requestTimeout,
            Duration maximumSnapshotAge,
            boolean allowInsecureLoopback) {

        /**
         * Validates URI and finite timing bounds.
         *
         * @return validated immutable settings
         */
        public Settings validated() {
            validateUri(publicationUri, allowInsecureLoopback);
            Duration refresh = bounded(refreshInterval, Duration.ofSeconds(1),
                    Duration.ofHours(1), "refresh interval");
            Duration timeout = bounded(requestTimeout, Duration.ofMillis(100),
                    Duration.ofSeconds(30), "request timeout");
            Duration maximumAge = bounded(maximumSnapshotAge, Duration.ofSeconds(2),
                    Duration.ofHours(24), "maximum snapshot age");
            if (maximumAge.compareTo(refresh.plus(timeout)) < 0) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory snapshot age must cover refresh plus timeout");
            }
            return new Settings(publicationUri, refresh, timeout, maximumAge,
                    allowInsecureLoopback);
        }

        private static void validateUri(URI uri, boolean allowInsecureLoopback) {
            if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException(
                        "A valid recovery-fleet inventory publication URI is required");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1");
            if (!allowInsecureLoopback || !loopback
                    || !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory publication source must use HTTPS");
            }
        }
    }

    @FunctionalInterface
    interface DocumentFetcher {
        FetchedDocument fetch(URI uri, String etag, Duration timeout);
    }

    record FetchedDocument(byte[] body, String etag, boolean notModified) {

        FetchedDocument {
            body = body == null ? new byte[0] : body.clone();
            etag = normalized(etag);
            if (!STRONG_ETAG.matcher(etag).matches()
                    || notModified && body.length != 0
                    || !notModified && (body.length == 0
                    || body.length > MAXIMUM_DOCUMENT_BYTES)) {
                throw new IllegalArgumentException(
                        "Invalid recovery-fleet inventory publication response");
            }
        }

        static FetchedDocument modified(byte[] body, String etag) {
            return new FetchedDocument(body, etag, false);
        }

        static FetchedDocument notModified(String etag) {
            return new FetchedDocument(new byte[0], etag, true);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class HttpDocumentFetcher implements DocumentFetcher {
        private final HttpClient client;

        private HttpDocumentFetcher(Settings settings) {
            client = HttpClient.newBuilder()
                    .connectTimeout(settings.requestTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Override
        public FetchedDocument fetch(URI uri, String etag, Duration timeout) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .GET()
                        .timeout(timeout)
                        .header("Accept", MEDIA_TYPE)
                        .header(PROTOCOL_HEADER,
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                                        .SCHEMA_VERSION);
                if (!etag.isBlank()) {
                    request.header("If-None-Match", etag);
                }
                HttpResponse<InputStream> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 304) {
                    try (InputStream ignored = response.body()) {
                        requireProtocolHeaders(response);
                        String returnedEtag = singleHeader(response, "ETag");
                        if (!returnedEtag.equals(etag)) {
                            throw new IllegalArgumentException(
                                    "Recovery-fleet inventory 304 changed its ETag");
                        }
                        return FetchedDocument.notModified(returnedEtag);
                    }
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new RemotePublicationUnavailableException(
                            "Recovery-fleet inventory source returned a non-success status");
                }
                requireProtocolHeaders(response);
                long declaredLength = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1);
                if (declaredLength > MAXIMUM_DOCUMENT_BYTES) {
                    response.body().close();
                    throw new IllegalArgumentException(
                            "Recovery-fleet inventory publication is too large");
                }
                byte[] body;
                try (InputStream input = response.body()) {
                    body = input.readNBytes(MAXIMUM_DOCUMENT_BYTES + 1);
                }
                String responseEtag = singleHeader(response, "ETag");
                return FetchedDocument.modified(body, responseEtag);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RemotePublicationUnavailableException(
                        "Recovery-fleet inventory request was interrupted", interrupted);
            } catch (IOException unavailable) {
                throw new RemotePublicationUnavailableException(
                        "Recovery-fleet inventory request failed", unavailable);
            }
        }

        private static void requireProtocolHeaders(HttpResponse<?> response) {
            String contentType = singleHeader(response, "Content-Type")
                    .toLowerCase(Locale.ROOT);
            String protocol = singleHeader(response, PROTOCOL_HEADER);
            boolean exactMediaType = contentType.equals(MEDIA_TYPE)
                    || contentType.startsWith(MEDIA_TYPE + ";");
            if (!exactMediaType
                    || !ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                    .SCHEMA_VERSION.equals(protocol)) {
                try {
                    if (response.body() instanceof InputStream body) {
                        body.close();
                    }
                } catch (IOException ignored) {
                    // The protocol error remains authoritative.
                }
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory source protocol negotiation failed");
            }
        }

        private static String singleHeader(HttpResponse<?> response, String name) {
            List<String> values = response.headers().allValues(name);
            if (values.size() != 1 || values.getFirst().isBlank()) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory source response headers are ambiguous");
            }
            return values.getFirst();
        }
    }

    private static final class RemotePublicationUnavailableException extends RuntimeException {
        private RemotePublicationUnavailableException(String message) {
            super(message);
        }

        private RemotePublicationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Aggregate process-local refresh telemetry.
     *
     * @param schemaVersion snapshot protocol generation
     * @param available whether the current exact inventory is admitted
     * @param refreshState bounded local refresh state
     * @param publicationState current signed ACTIVE or REVOKED state
     * @param publicationSequence current signed publication sequence
     * @param inventoryGeneration current signed inventory generation
     * @param lastSuccessfulRefreshAt last successful conditional fetch
     * @param refreshSuccessCount process-local successful refresh count
     * @param refreshFailureCount process-local failed refresh count
     * @param lastFailureCode stable payload-free failure classification
     * @param refreshIntervalSeconds configured fixed-delay interval
     * @param maximumSnapshotAgeSeconds hard local freshness fence
     * @param witnessSignatureThreshold required independent witness signatures
     * @param durablePublicationFloor whether the floor survives complete restart
     * @param externallyAnchoredPublicationFloor whether heads commit outside the local database
     * @param byzantineQuorumAnchoredPublicationFloor whether the external anchor is Byzantine-safe
     */
    public record RefreshSnapshot(
            String schemaVersion,
            boolean available,
            String refreshState,
            String publicationState,
            long publicationSequence,
            long inventoryGeneration,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode,
            long refreshIntervalSeconds,
            long maximumSnapshotAgeSeconds,
            int witnessSignatureThreshold,
            boolean durablePublicationFloor,
            boolean externallyAnchoredPublicationFloor,
            boolean byzantineQuorumAnchoredPublicationFloor) {

        /** Current aggregate refresh snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryRefreshSnapshot.v1";

        private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

        /** Rejects incomplete or unbounded aggregate telemetry. */
        public RefreshSnapshot {
            schemaVersion = normalized(schemaVersion);
            refreshState = normalized(refreshState);
            publicationState = normalized(publicationState);
            lastFailureCode = normalized(lastFailureCode);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !STATUS.matcher(refreshState).matches()
                    || !(publicationState.equals("ACTIVE")
                    || publicationState.equals("REVOKED"))
                    || publicationSequence < 1 || inventoryGeneration < 1
                    || lastSuccessfulRefreshAt == null
                    || refreshSuccessCount < 1 || refreshFailureCount < 0
                    || !lastFailureCode.isEmpty()
                    && !STATUS.matcher(lastFailureCode).matches()
                    || refreshIntervalSeconds < 1
                    || maximumSnapshotAgeSeconds < 2
                    || witnessSignatureThreshold < 1 || witnessSignatureThreshold > 32
                    || !durablePublicationFloor
                    || byzantineQuorumAnchoredPublicationFloor
                    && !externallyAnchoredPublicationFloor) {
                throw new IllegalArgumentException(
                        "Dynamic recovery-fleet inventory refresh snapshot is invalid");
            }
        }
    }

    private static Duration bounded(
            Duration value,
            Duration minimum,
            Duration maximum,
            String label) {
        if (value == null || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory " + label + " is invalid");
        }
        return value;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
