package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
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

/**
 * Atomically refreshes a signed serving-inventory publication from a bounded HTTPS source.
 *
 * <p>Bootstrap verifies three independent layers before this authority becomes observable: the
 * exact inventory, its active-or-revoked publication state, and an independent witness checkpoint.
 * Periodic refresh uses conditional GET and publishes either the complete verified successor or
 * no successor. Fetch, media negotiation, parsing, signature, time, sequence, predecessor, or
 * witness ambiguity immediately makes the local authority unavailable; capability and health
 * reads never perform remote I/O.</p>
 *
 * <p>A valid {@code REVOKED} publication is a successful refresh with an explicitly unavailable
 * inventory, not a transport failure. Every cryptographically verified chain head must also pass
 * a durable monotonic floor before it becomes locally observable.</p>
 */
public final class DynamicTestSuiteStabilityServingInventoryAuthority
        implements TestSuiteStabilityServingInventoryAuthority, AutoCloseable {

    /** Version-negotiated media type required from the remote publication endpoint. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.suite-stability-serving-inventory.v1+json";
    /** Exact response header that prevents silent protocol downgrade through generic JSON. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-Serving-Inventory-Protocol";
    /** Aggregate source identity used by cohort capability and health projections. */
    public static final String SOURCE_TYPE =
            "DYNAMIC_HTTPS_SIGNED_PUBLICATION_WITH_WITNESS";

    private static final Logger log = LoggerFactory.getLogger(
            DynamicTestSuiteStabilityServingInventoryAuthority.class);
    private static final int MAXIMUM_DOCUMENT_BYTES = 512 * 1024;
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_PUBLICATION_LIFETIME = Duration.ofDays(1);

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String trustDomain;
    private final Set<String> acceptedPolicyFingerprints;
    private final int signatureThreshold;
    private final List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            authorityKeys;
    private final Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            indexedAuthorityKeys;
    private final ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding;
    private final TestSuiteStabilityServingInventoryPublicationFloor publicationFloor;
    private final String witnessDomain;
    private final int witnessSignatureThreshold;
    private final Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            indexedWitnessKeys;
    private final DynamicTestSuiteStabilityServingInventoryTrustRootAuthority managedTrustRoots;
    private final Settings settings;
    private final DocumentFetcher fetcher;
    private final Object refreshLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    private volatile RefreshState state = RefreshState.empty();
    private volatile boolean closed;

    /**
     * Bootstraps one remote signed publication and starts background refresh.
     *
     * @param objectMapper application protocol mapper
     * @param trustDomain exact deployment-inventory and publication trust domain
     * @param acceptedPolicyFingerprints accepted inventory/publication policy revisions
     * @param signatureThreshold required distinct deployment-authority signatures
     * @param authorityKeys public inventory/publication verification keys
     * @param binding exact local scope, cohort, artifact, protocol, and serving slot
     * @param publicationFloor durable cross-restart publication/witness floor
     * @param witnessDomain exact independent witness trust domain
     * @param witnessSignatureThreshold required distinct witness signatures
     * @param witnessKeys independent public witness verification keys
     * @param settings remote refresh and hard freshness policy
     */
    public DynamicTestSuiteStabilityServingInventoryAuthority(
            ObjectMapper objectMapper,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    authorityKeys,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding,
            TestSuiteStabilityServingInventoryPublicationFloor publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> witnessKeys,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), trustDomain, acceptedPolicyFingerprints,
                signatureThreshold, authorityKeys, binding, publicationFloor, witnessDomain,
                witnessSignatureThreshold, witnessKeys, settings, null, true, null);
    }

    DynamicTestSuiteStabilityServingInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    authorityKeys,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding,
            TestSuiteStabilityServingInventoryPublicationFloor publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> witnessKeys,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this(objectMapper, clock, trustDomain, acceptedPolicyFingerprints,
                signatureThreshold, authorityKeys, binding, publicationFloor, witnessDomain,
                witnessSignatureThreshold, witnessKeys, settings, fetcher, startScheduler, null);
    }

    private DynamicTestSuiteStabilityServingInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    authorityKeys,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding,
            TestSuiteStabilityServingInventoryPublicationFloor publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> witnessKeys,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler,
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority managedTrustRoots) {
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
        this.indexedAuthorityKeys =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        this.authorityKeys, signatureThreshold);
        this.binding = Objects.requireNonNull(binding, "binding");
        this.publicationFloor = Objects.requireNonNull(
                publicationFloor, "publicationFloor");
        this.witnessDomain = normalized(witnessDomain);
        this.witnessSignatureThreshold = witnessSignatureThreshold;
        this.indexedWitnessKeys =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        witnessKeys, witnessSignatureThreshold);
        this.managedTrustRoots = managedTrustRoots;
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        if (!this.publicationFloor.durable()) {
            throw new IllegalArgumentException(
                    "Dynamic serving inventory requires a durable publication floor");
        }
        if (this.trustDomain.isBlank() || this.witnessDomain.isBlank()
                || this.trustDomain.equals(this.witnessDomain)
                || !independentAuthorities(this.authorityKeys, witnessKeys)) {
            throw new IllegalArgumentException(
                    "Inventory publication and witness authorities must be independent");
        }
        this.fetcher = fetcher == null ? new HttpDocumentFetcher(this.settings) : fetcher;
        if (!refresh() || !observation().available()) {
            throw new IllegalStateException(
                    "Dynamic serving-inventory publication bootstrap is unavailable");
        }
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Bootstraps a remote serving-inventory publication using restart-free managed runtime keys.
     *
     * @param objectMapper application protocol mapper
     * @param acceptedPolicyFingerprints accepted inventory/publication policy revisions
     * @param binding exact local deployment binding
     * @param publicationFloor durable publication/witness floor
     * @param managedTrustRoots atomic dual-quorum runtime-key authority
     * @param settings remote inventory refresh settings
     */
    public DynamicTestSuiteStabilityServingInventoryAuthority(
            ObjectMapper objectMapper,
            Set<String> acceptedPolicyFingerprints,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding,
            TestSuiteStabilityServingInventoryPublicationFloor publicationFloor,
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority managedTrustRoots,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), acceptedPolicyFingerprints, binding,
                publicationFloor, managedTrustRoots, settings, null, true);
    }

    DynamicTestSuiteStabilityServingInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            Set<String> acceptedPolicyFingerprints,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding,
            TestSuiteStabilityServingInventoryPublicationFloor publicationFloor,
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority managedTrustRoots,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this(objectMapper, clock, acceptedPolicyFingerprints, binding, publicationFloor,
                Objects.requireNonNull(managedTrustRoots, "managedTrustRoots"), settings,
                fetcher, startScheduler, managedMaterial(managedTrustRoots));
    }

    private DynamicTestSuiteStabilityServingInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            Set<String> acceptedPolicyFingerprints,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding,
            TestSuiteStabilityServingInventoryPublicationFloor publicationFloor,
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority managedTrustRoots,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler,
            ManagedMaterial material) {
        this(objectMapper, clock,
                material.deploymentTrustDomain(),
                acceptedPolicyFingerprints,
                material.deploymentSignatureThreshold(), material.deploymentKeys(),
                binding, publicationFloor,
                material.witnessTrustDomain(), material.witnessSignatureThreshold(),
                material.witnessKeys(), settings, fetcher, startScheduler, managedTrustRoots);
    }

    /**
     * Parses strict public-key configuration and constructs a dynamic authority.
     *
     * @param objectMapper application protocol mapper
     * @param trustDomain deployment publication trust domain
     * @param acceptedPolicies comma-separated accepted policy fingerprints
     * @param signatureThreshold deployment-authority M-of-N threshold
     * @param authorityKeysJson public deployment-authority key array
     * @param binding exact local deployment binding
     * @param publicationFloor durable cross-restart publication/witness floor
     * @param witnessDomain independent witness trust domain
     * @param witnessSignatureThreshold witness M-of-N threshold
     * @param witnessKeysJson public witness key array
     * @param settings remote refresh settings
     * @return bootstrapped dynamic authority
     */
    public static DynamicTestSuiteStabilityServingInventoryAuthority fromJson(
            ObjectMapper objectMapper,
            String trustDomain,
            String acceptedPolicies,
            int signatureThreshold,
            String authorityKeysJson,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding,
            TestSuiteStabilityServingInventoryPublicationFloor publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            String witnessKeysJson,
            Settings settings) {
        try {
            ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            return new DynamicTestSuiteStabilityServingInventoryAuthority(
                    objectMapper, trustDomain,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parsePolicies(
                            acceptedPolicies),
                    signatureThreshold,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, authorityKeysJson),
                    binding, publicationFloor, witnessDomain, witnessSignatureThreshold,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, witnessKeysJson), settings);
        } catch (RuntimeException | java.io.IOException
                 | java.security.GeneralSecurityException invalid) {
            throw new IllegalArgumentException(
                    "Dynamic serving-inventory trust configuration is invalid", invalid);
        }
    }

    /** Returns current verified local state without triggering remote I/O. */
    @Override
    public Observation observation() {
        RefreshState observed = state;
        if (observed.inventoryAuthority() == null) {
            throw new IllegalStateException("Serving-inventory publication is not bootstrapped");
        }
        Observation inventory = observed.inventoryAuthority().observation();
        DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.Snapshot rootSnapshot =
                managedTrustRoots == null ? null : managedTrustRoots.snapshot();
        Instant now = clock.instant();
        boolean available = false;
        String status;
        if (closed) {
            status = "CLOSED";
        } else if (observed.state() != LocalState.HEALTHY) {
            status = "REFRESH_UNAVAILABLE";
        } else if (rootSnapshot != null && !rootSnapshot.available()) {
            status = "TRUST_ROOT_" + rootSnapshot.status();
        } else if (managedTrustRoots != null
                && !observed.trustRootGenerationFingerprint().equals(
                managedTrustRoots.generationFingerprint())) {
            status = "TRUST_ROOT_GENERATION_UNVERIFIED";
        } else if (observed.lastSuccessfulRefreshAt() == null
                || !now.isBefore(observed.lastSuccessfulRefreshAt()
                .plus(settings.maximumSnapshotAge()))) {
            status = "SOURCE_EXPIRED";
        } else if (!inventory.available()) {
            status = "INVENTORY_" + inventory.status();
        } else if (now.isBefore(observed.publication().material().notBefore())) {
            status = "PUBLICATION_NOT_YET_VALID";
        } else if (!now.isBefore(observed.publication().material().expiresAt())) {
            status = "PUBLICATION_EXPIRED";
        } else if (now.isBefore(
                observed.publication().witness().material().notBefore())) {
            status = "WITNESS_NOT_YET_VALID";
        } else if (!now.isBefore(
                observed.publication().witness().material().expiresAt())) {
            status = "WITNESS_EXPIRED";
        } else if (observed.publication().material().state()
                == TestSuiteStabilityServingInventoryPublication.State.REVOKED) {
            status = "REVOKED";
        } else {
            status = "VERIFIED";
            available = true;
        }
        return new Observation(Observation.SCHEMA_VERSION, true, true, available,
                status, SOURCE_TYPE, observed.publication().material().sequence(),
                sourceGenerationFingerprint(observed), inventory.revision(),
                inventory.materialFingerprint(), inventory.policyFingerprint(),
                inventory.expectedInstanceIds(), inventory.expiresAt(),
                inventory.validSignatureCount(), inventory.requiredSignatureCount());
    }

    /** Publishes bounded refresh semantics without endpoint, ETag, ids, keys, or fingerprints. */
    @Override
    public Descriptor descriptor() {
        Observation observed = observation();
        RefreshState refresh = state;
        return new Descriptor(Descriptor.SCHEMA_VERSION, true, true,
                observed.available(), observed.status(),
                observed.expectedInstanceIds().size(), observed.revision(),
                Map.ofEntries(
                        Map.entry("sourceType", SOURCE_TYPE),
                        Map.entry("privateMaterialPresent", false),
                        Map.entry("automaticRefresh", scheduler != null && !closed),
                        Map.entry("refreshState", effectiveRefreshState(refresh)),
                        Map.entry("refreshIntervalSeconds",
                                settings.refreshInterval().toSeconds()),
                        Map.entry("maximumSnapshotAgeSeconds",
                                settings.maximumSnapshotAge().toSeconds()),
                        Map.entry("conditionalRequests", true),
                        Map.entry("failClosedOnRefreshFailure", true),
                        Map.entry("signedRevocation", true),
                        Map.entry("witnessedPublications", true),
                        Map.entry("protocolVersion",
                                TestSuiteStabilityServingInventoryPublication.SCHEMA_VERSION),
                        Map.entry("witnessSignatureThreshold",
                                currentWitnessSignatureThreshold()),
                        Map.entry("durablePublicationFloor", true),
                        Map.entry("managedTrustRootRefresh", managedTrustRoots != null),
                        Map.entry("atomicDualTrustRootPublication",
                                managedTrustRoots != null)));
    }

    /**
     * Returns aggregate process-local refresh state for Actuator and fixed-cardinality telemetry.
     *
     * @return key-free refresh snapshot
     */
    public Snapshot snapshot() {
        RefreshState observed = state;
        Observation inventory = observation();
        long sequence = observed.publication() == null
                ? 0 : observed.publication().material().sequence();
        String publicationState = observed.publication() == null
                ? "UNAVAILABLE" : observed.publication().material().state().name();
        return new Snapshot("bloge.testSuiteStabilityServingInventoryRefreshSnapshot.v1",
                inventory.available(), effectiveRefreshState(observed), publicationState,
                sequence, observed.lastSuccessfulRefreshAt(),
                observed.refreshSuccessCount(), observed.refreshFailureCount(),
                observed.lastFailureCode(), settings.refreshInterval().toSeconds(),
                settings.maximumSnapshotAge().toSeconds(),
                currentWitnessSignatureThreshold(), true);
    }

    /** Stops background refresh and immediately makes the local authority unavailable. */
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
            RefreshState previous = state;
            try {
                FetchedDocument fetched = fetcher.fetch(
                        settings.publicationUri(), previous.etag(), settings.requestTimeout());
                TestSuiteStabilityServingInventoryPublication publication;
                ConfiguredTestSuiteStabilityServingInventoryAuthority inventoryAuthority;
                String trustRootGenerationFingerprint;
                if (fetched.notModified()) {
                    if (previous.publication() == null
                            || previous.inventoryAuthority() == null) {
                        throw new IllegalArgumentException(
                                "Serving-inventory source returned 304 before bootstrap");
                    }
                    publication = previous.publication();
                    if (managedTrustRoots != null
                            && !previous.trustRootGenerationFingerprint().equals(
                            managedTrustRoots.generationFingerprint())) {
                        VerifiedPublication verified = verify(publication, previous, now);
                        inventoryAuthority = verified.inventoryAuthority();
                        trustRootGenerationFingerprint =
                                verified.trustRootGenerationFingerprint();
                    } else {
                        inventoryAuthority = previous.inventoryAuthority();
                        trustRootGenerationFingerprint =
                                previous.trustRootGenerationFingerprint();
                    }
                } else {
                    publication = parse(fetched.body());
                    VerifiedPublication verified = verify(publication, previous, now);
                    inventoryAuthority = verified.inventoryAuthority();
                    trustRootGenerationFingerprint =
                            verified.trustRootGenerationFingerprint();
                }
                String etag = fetched.etag().isBlank() ? previous.etag() : fetched.etag();
                state = new RefreshState(publication, inventoryAuthority,
                        trustRootGenerationFingerprint, etag,
                        LocalState.HEALTHY, now,
                        previous.refreshSuccessCount() + 1,
                        previous.refreshFailureCount(), "");
                refreshFailureLogged.set(false);
                return true;
            } catch (RuntimeException unavailable) {
                state = previous.failed(failureCode(unavailable));
                if (refreshFailureLogged.compareAndSet(false, true)) {
                    log.warn("Dynamic suite-stability serving-inventory refresh failed; "
                            + "fresh admission is now fail-closed");
                }
                return false;
            }
        }
    }

    private TestSuiteStabilityServingInventoryPublication parse(byte[] body) {
        try {
            return objectMapper.readValue(
                    body, TestSuiteStabilityServingInventoryPublication.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(
                    "Serving-inventory publication JSON is invalid", invalid);
        }
    }

    private VerifiedPublication verify(
            TestSuiteStabilityServingInventoryPublication publication,
            RefreshState previous,
            Instant now) {
        VerificationTrust verificationTrust = verificationTrust(publication);
        if (!publication.fingerprintVerified(objectMapper)
                || !publication.witness().fingerprintVerified(objectMapper)
                || !verificationTrust.deploymentTrustDomain().equals(
                publication.material().trustDomain())
                || !acceptedPolicyFingerprints.contains(
                publication.material().policyFingerprint())) {
            throw new IllegalArgumentException(
                    "Serving-inventory publication identity or policy is invalid");
        }
        requireCurrentWindow(publication.material().issuedAt(),
                publication.material().notBefore(), publication.material().expiresAt(), now,
                "Serving-inventory publication");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                verificationTrust.deploymentKeys(),
                verificationTrust.deploymentSignatureThreshold(), publication.signatures(),
                publication.materialFingerprint(), publication.material().issuedAt(),
                publication.material().expiresAt(), now,
                "Serving-inventory publication");

        TestSuiteStabilityServingInventoryPublication.WitnessMaterial witness =
                publication.witness().material();
        if (!verificationTrust.witnessTrustDomain().equals(witness.witnessDomain())
                || witness.notBefore().isAfter(
                publication.material().notBefore().plus(CLOCK_SKEW))
                || witness.expiresAt().isBefore(publication.material().expiresAt())) {
            throw new IllegalArgumentException(
                    "Serving-inventory witness binding is invalid");
        }
        requireCurrentWindow(witness.issuedAt(), witness.notBefore(),
                witness.expiresAt(), now, "Serving-inventory witness");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                verificationTrust.witnessKeys(),
                verificationTrust.witnessSignatureThreshold(),
                publication.witness().signatures(),
                publication.witness().materialFingerprint(), witness.issuedAt(),
                witness.expiresAt(), now, "Serving-inventory witness");
        requireSuccessor(publication, previous.publication());

        ConfiguredTestSuiteStabilityServingInventoryAuthority inventoryAuthority =
                new ConfiguredTestSuiteStabilityServingInventoryAuthority(
                objectMapper, clock, verificationTrust.deploymentTrustDomain(),
                acceptedPolicyFingerprints,
                verificationTrust.deploymentSignatureThreshold(),
                verificationTrust.deploymentKeyList(), publication.inventory(), binding);
        publicationFloor.accept(publicationGeneration(publication));
        return new VerifiedPublication(inventoryAuthority,
                verificationTrust.trustRootGenerationFingerprint());
    }

    private TestSuiteStabilityServingInventoryPublicationFloor.Generation
            publicationGeneration(TestSuiteStabilityServingInventoryPublication publication) {
        return new TestSuiteStabilityServingInventoryPublicationFloor.Generation(
                TestSuiteStabilityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                binding.scopeId(), publication.material().sequence(),
                publication.materialFingerprint(),
                publication.witness().materialFingerprint(),
                publication.material().previousPublicationFingerprint(),
                publication.witness().material().previousWitnessFingerprint());
    }

    private VerificationTrust verificationTrust(
            TestSuiteStabilityServingInventoryPublication publication) {
        if (managedTrustRoots == null) {
            return new VerificationTrust(trustDomain, witnessDomain, signatureThreshold,
                    witnessSignatureThreshold, indexedAuthorityKeys, indexedWitnessKeys,
                    authorityKeys, "");
        }
        var keys = managedTrustRoots.keysFor(
                publication.signatures(), publication.witness().signatures());
        return new VerificationTrust(keys.deploymentTrustDomain(),
                keys.witnessTrustDomain(), keys.deploymentSignatureThreshold(),
                keys.witnessSignatureThreshold(), keys.deploymentKeys(), keys.witnessKeys(),
                List.copyOf(keys.deploymentKeys().values()),
                managedTrustRoots.generationFingerprint());
    }

    private int currentWitnessSignatureThreshold() {
        return managedTrustRoots == null ? witnessSignatureThreshold
                : managedTrustRoots.snapshot().witnessSignatureThreshold();
    }

    private String sourceGenerationFingerprint(RefreshState observed) {
        if (observed.trustRootGenerationFingerprint().isBlank()) {
            return observed.publication().witness().materialFingerprint();
        }
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.testSuiteStabilityServingInventoryManagedGeneration.v1",
                "publicationMaterialFingerprint",
                observed.publication().materialFingerprint(),
                "witnessMaterialFingerprint",
                observed.publication().witness().materialFingerprint(),
                "trustRootGenerationFingerprint",
                observed.trustRootGenerationFingerprint()));
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
            TestSuiteStabilityServingInventoryPublication next,
            TestSuiteStabilityServingInventoryPublication previous) {
        if (previous == null) {
            return;
        }
        long nextSequence = next.material().sequence();
        long previousSequence = previous.material().sequence();
        long nextInventoryRevision = next.inventory().material().revision();
        long previousInventoryRevision = previous.inventory().material().revision();
        if (nextInventoryRevision < previousInventoryRevision
                || nextInventoryRevision == previousInventoryRevision
                && !next.inventory().materialFingerprint().equals(
                previous.inventory().materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Serving-inventory revision is rolled back or forked");
        }
        if (nextSequence == previousSequence) {
            if (!next.materialFingerprint().equals(previous.materialFingerprint())
                    || !next.witness().materialFingerprint().equals(
                    previous.witness().materialFingerprint())
                    || !next.inventory().materialFingerprint().equals(
                    previous.inventory().materialFingerprint())) {
                throw new IllegalArgumentException(
                        "Serving-inventory publication sequence is forked");
            }
            return;
        }
        if (nextSequence != previousSequence + 1
                || !next.material().previousPublicationFingerprint().equals(
                previous.materialFingerprint())
                || !next.witness().material().previousWitnessFingerprint().equals(
                previous.witness().materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Serving-inventory publication chain is rolled back or discontinuous");
        }
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-stability-serving-inventory-refresh");
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
                state = state.failed("REFRESH_TASK_FAILED");
            }
        }
    }

    private String effectiveRefreshState(RefreshState observed) {
        if (closed) {
            return "CLOSED";
        }
        if (observed.state() == LocalState.HEALTHY
                && (observed.lastSuccessfulRefreshAt() == null
                || !clock.instant().isBefore(observed.lastSuccessfulRefreshAt()
                .plus(settings.maximumSnapshotAge())))) {
            return "EXPIRED";
        }
        return observed.state().name();
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

    private enum LocalState {
        BOOTSTRAPPING,
        HEALTHY,
        UNAVAILABLE
    }

    private record VerificationTrust(
            String deploymentTrustDomain,
            String witnessTrustDomain,
            int deploymentSignatureThreshold,
            int witnessSignatureThreshold,
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentKeys,
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    witnessKeys,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentKeyList,
            String trustRootGenerationFingerprint) {
    }

    private record VerifiedPublication(
            ConfiguredTestSuiteStabilityServingInventoryAuthority inventoryAuthority,
            String trustRootGenerationFingerprint) {
    }

    /**
     * Bounded remote source policy. Maximum snapshot age is a local hard fence and must cover at
     * least one fixed-delay refresh plus one request timeout.
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

        /** @return validated immutable settings */
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
                        "Serving-inventory snapshot age must cover refresh plus timeout");
            }
            return new Settings(publicationUri, refresh, timeout, maximumAge,
                    allowInsecureLoopback);
        }

        private static void validateUri(URI uri, boolean allowInsecureLoopback) {
            if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException(
                        "A valid serving-inventory publication URI is required");
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
                        "Serving-inventory publication source must use HTTPS");
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
            if (etag.length() > 512 || etag.chars().anyMatch(Character::isISOControl)
                    || !notModified && (body.length == 0
                    || body.length > MAXIMUM_DOCUMENT_BYTES)) {
                throw new IllegalArgumentException(
                        "Invalid serving-inventory publication response");
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
                                TestSuiteStabilityServingInventoryPublication.SCHEMA_VERSION);
                if (!etag.isBlank()) {
                    request.header("If-None-Match", etag);
                }
                HttpResponse<InputStream> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 304) {
                    requireProtocolHeaders(response);
                    response.body().close();
                    return FetchedDocument.notModified(
                            response.headers().firstValue("ETag").orElse(etag));
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new RemotePublicationUnavailableException(
                            "Serving-inventory source returned a non-success status");
                }
                requireProtocolHeaders(response);
                long declaredLength = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1);
                if (declaredLength > MAXIMUM_DOCUMENT_BYTES) {
                    response.body().close();
                    throw new IllegalArgumentException(
                            "Serving-inventory publication is too large");
                }
                byte[] body;
                try (InputStream input = response.body()) {
                    body = input.readNBytes(MAXIMUM_DOCUMENT_BYTES + 1);
                }
                return FetchedDocument.modified(body,
                        response.headers().firstValue("ETag").orElse(""));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RemotePublicationUnavailableException(
                        "Serving-inventory request was interrupted", interrupted);
            } catch (IOException unavailable) {
                throw new RemotePublicationUnavailableException(
                        "Serving-inventory request failed", unavailable);
            }
        }

        private static void requireProtocolHeaders(HttpResponse<?> response) {
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT);
            String protocol = response.headers().firstValue(PROTOCOL_HEADER).orElse("");
            boolean exactMediaType = contentType.equals(MEDIA_TYPE)
                    || contentType.startsWith(MEDIA_TYPE + ";");
            if (!exactMediaType
                    || !TestSuiteStabilityServingInventoryPublication.SCHEMA_VERSION.equals(
                    protocol)) {
                try {
                    if (response.body() instanceof InputStream body) {
                        body.close();
                    }
                } catch (IOException ignored) {
                    // The protocol error remains the authoritative failure.
                }
                throw new IllegalArgumentException(
                        "Serving-inventory source protocol negotiation failed");
            }
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

    private record RefreshState(
            TestSuiteStabilityServingInventoryPublication publication,
            ConfiguredTestSuiteStabilityServingInventoryAuthority inventoryAuthority,
            String trustRootGenerationFingerprint,
            String etag,
            LocalState state,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode) {

        private RefreshState {
            trustRootGenerationFingerprint = normalized(trustRootGenerationFingerprint);
            etag = normalized(etag);
            state = state == null ? LocalState.UNAVAILABLE : state;
            lastFailureCode = normalized(lastFailureCode);
        }

        private static RefreshState empty() {
            return new RefreshState(null, null, "", "", LocalState.BOOTSTRAPPING,
                    null, 0, 0, "");
        }

        private RefreshState failed(String failureCode) {
            return new RefreshState(publication, inventoryAuthority,
                    trustRootGenerationFingerprint, etag,
                    LocalState.UNAVAILABLE, lastSuccessfulRefreshAt,
                    refreshSuccessCount, refreshFailureCount + 1, failureCode);
        }
    }

    /**
     * Key-free refresh snapshot for health and telemetry.
     *
     * @param schemaVersion snapshot generation
     * @param available whether the current publication may admit work
     * @param refreshState bounded process-local refresh state
     * @param publicationState active, revoked, or unavailable
     * @param sequence current aggregate publication sequence
     * @param lastSuccessfulRefreshAt last complete atomic publication time
     * @param refreshSuccessCount process-local successful refresh count
     * @param refreshFailureCount process-local failed refresh count
     * @param lastFailureCode stable payload-free failure family
     * @param refreshIntervalSeconds configured refresh interval
     * @param maximumSnapshotAgeSeconds hard local freshness fence
     * @param witnessSignatureThreshold configured independent witness threshold
     * @param durablePublicationFloor whether the chain head survives complete fleet restart
     */
    public record Snapshot(
            String schemaVersion,
            boolean available,
            String refreshState,
            String publicationState,
            long sequence,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode,
            long refreshIntervalSeconds,
            long maximumSnapshotAgeSeconds,
            int witnessSignatureThreshold,
            boolean durablePublicationFloor) {

        /** Enforces a bounded aggregate-only operational projection. */
        public Snapshot {
            refreshState = normalized(refreshState);
            publicationState = normalized(publicationState);
            lastFailureCode = normalized(lastFailureCode);
            if (!"bloge.testSuiteStabilityServingInventoryRefreshSnapshot.v1".equals(
                    schemaVersion)
                    || refreshState.isBlank() || publicationState.isBlank()
                    || sequence < 0 || refreshSuccessCount < 0 || refreshFailureCount < 0
                    || refreshIntervalSeconds < 1 || maximumSnapshotAgeSeconds < 2
                    || witnessSignatureThreshold < 1 || witnessSignatureThreshold > 32
                    || !durablePublicationFloor) {
                throw new IllegalArgumentException(
                        "Invalid serving-inventory refresh snapshot");
            }
        }
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String label) {
        Duration result = Objects.requireNonNull(value, label);
        if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Invalid serving-inventory " + label);
        }
        return result;
    }

    private static boolean independentAuthorities(
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentKeys,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    witnessKeys) {
        Set<String> authorityIds = new HashSet<>();
        Set<String> publicKeys = new HashSet<>();
        for (ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key
                : deploymentKeys) {
            authorityIds.add(key.authorityId());
            publicKeys.add(Base64.getEncoder().encodeToString(key.publicKey().getEncoded()));
        }
        for (ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key
                : witnessKeys == null
                ? List.<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>of()
                : witnessKeys) {
            if (authorityIds.contains(key.authorityId())
                    || publicKeys.contains(Base64.getEncoder().encodeToString(
                    key.publicKey().getEncoded()))) {
                return false;
            }
        }
        return true;
    }

    private static ManagedMaterial managedMaterial(
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority authority) {
        var keys = Objects.requireNonNull(authority, "managedTrustRoots")
                .keysFor(List.of(), List.of());
        return new ManagedMaterial(keys.deploymentTrustDomain(),
                keys.deploymentSignatureThreshold(),
                List.copyOf(keys.deploymentKeys().values()),
                keys.witnessTrustDomain(), keys.witnessSignatureThreshold(),
                List.copyOf(keys.witnessKeys().values()));
    }

    private record ManagedMaterial(
            String deploymentTrustDomain,
            int deploymentSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentKeys,
            String witnessTrustDomain,
            int witnessSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> witnessKeys) {
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
