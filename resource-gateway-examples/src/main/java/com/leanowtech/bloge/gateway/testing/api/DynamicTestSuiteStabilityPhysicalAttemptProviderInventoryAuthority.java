package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Binding;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.ProviderDeployment;
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
import java.util.HashMap;
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
 * Dynamically refreshes a signed physical provider-inventory publication over bounded HTTPS.
 *
 * <p>Bootstrap verifies the nested complete inventory, ACTIVE/REVOKED publication, exact signed
 * replica set, independent witness, and durable publication floor before exposing any resolver.
 * Conditional refresh atomically swaps a complete verified successor or marks local admission
 * unavailable. Capability, descriptor, cohort, and resolver reads never initiate remote refresh
 * or provider I/O.</p>
 *
 * <p>Every resolved adapter is fenced to the complete publication generation, not only the nested
 * inventory fingerprint. A signed revocation or any successor therefore invalidates previously
 * resolved wrappers before their next descriptor or observation admission.</p>
 */
public final class DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
        implements TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority, AutoCloseable {

    /** Version-negotiated media type required from the publication endpoint. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.physical-attempt-provider-inventory-publication.v1+json";
    /** Exact response header that prevents generic-JSON protocol downgrade. */
    public static final String PROTOCOL_HEADER = "X-BLOGE-Physical-Provider-Inventory-Protocol";
    /** Aggregate source identity used by capability and cohort projections. */
    public static final String SOURCE_TYPE =
            "DYNAMIC_HTTPS_SIGNED_PUBLICATION_WITH_WITNESS";

    private static final Logger log = LoggerFactory.getLogger(
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class);
    private static final int MAXIMUM_DOCUMENT_BYTES = 1024 * 1024;
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_PUBLICATION_LIFETIME = Duration.ofDays(1);
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ExpectedBinding expected;
    private final int signatureThreshold;
    private final List<AuthorityKey> authorityKeys;
    private final Map<String, AuthorityKey> indexedAuthorityKeys;
    private final Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
            runtimeCatalog;
    private final TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor
            publicationFloor;
    private final String witnessDomain;
    private final int witnessSignatureThreshold;
    private final List<AuthorityKey> witnessKeys;
    private final Map<String, AuthorityKey> indexedWitnessKeys;
    private final DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
            managedTrustRoots;
    private final Settings settings;
    private final DocumentFetcher fetcher;
    private final Object refreshLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    private volatile RefreshState state = RefreshState.empty();
    private volatile boolean closed;

    /**
     * Bootstraps one dynamic provider inventory and starts autonomous refresh.
     *
     * @param objectMapper application protocol mapper
     * @param expected exact trust, scope, cohort, protocol, and policy binding
     * @param signatureThreshold required deployment-authority signature threshold
     * @param authorityKeys public deployment-authority Ed25519 keys
     * @param runtimeCatalog installed provider/deployment runtime adapters
     * @param publicationFloor durable publication and witness floor
     * @param witnessDomain independent witness trust domain
     * @param witnessSignatureThreshold required independent witness signatures
     * @param witnessKeys public independent witness Ed25519 keys
     * @param settings bounded HTTPS refresh policy
     */
    public DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            ExpectedBinding expected,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    runtimeCatalog,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<AuthorityKey> witnessKeys,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), expected, signatureThreshold, authorityKeys,
                runtimeCatalog, publicationFloor, witnessDomain, witnessSignatureThreshold,
                witnessKeys, settings, null, true);
    }

    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding expected,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    runtimeCatalog,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<AuthorityKey> witnessKeys,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this(objectMapper, clock, expected, signatureThreshold, authorityKeys,
                runtimeCatalog, publicationFloor, witnessDomain,
                witnessSignatureThreshold, witnessKeys, settings, fetcher,
                startScheduler, null);
    }

    private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding expected,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    runtimeCatalog,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor publicationFloor,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<AuthorityKey> witnessKeys,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler,
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                    managedTrustRoots) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expected = Objects.requireNonNull(expected, "expected");
        this.signatureThreshold = signatureThreshold;
        this.authorityKeys = List.copyOf(authorityKeys == null ? List.of() : authorityKeys);
        this.indexedAuthorityKeys =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        this.authorityKeys, signatureThreshold);
        this.runtimeCatalog = validatedCatalog(runtimeCatalog);
        this.publicationFloor = Objects.requireNonNull(publicationFloor, "publicationFloor");
        this.witnessDomain = normalized(witnessDomain);
        this.witnessSignatureThreshold = witnessSignatureThreshold;
        this.witnessKeys = List.copyOf(witnessKeys == null ? List.of() : witnessKeys);
        this.indexedWitnessKeys =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        this.witnessKeys, witnessSignatureThreshold);
        this.managedTrustRoots = managedTrustRoots;
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        if (!this.publicationFloor.durable()) {
            throw new IllegalArgumentException(
                    "Dynamic physical provider inventory requires a durable floor");
        }
        if (this.witnessDomain.isBlank()
                || expected.trustDomain().equals(this.witnessDomain)
                || !independentAuthorities(this.authorityKeys, this.witnessKeys)) {
            throw new IllegalArgumentException(
                    "Physical provider inventory and witness authorities must be independent");
        }
        this.fetcher = fetcher == null ? new HttpDocumentFetcher(this.settings) : fetcher;
        if (!refresh() || !observation().available()) {
            throw new IllegalStateException(
                    "Dynamic physical provider-inventory bootstrap is unavailable");
        }
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Bootstraps a physical provider inventory with restart-free atomic runtime verification keys.
     *
     * @param objectMapper application protocol mapper
     * @param expected exact scope, cohort, provider protocol, and policy binding
     * @param runtimeCatalog installed provider/deployment runtime adapters
     * @param publicationFloor durable inventory publication and witness floor
     * @param managedTrustRoots atomic dual-quorum runtime-key authority
     * @param settings bounded HTTPS inventory refresh policy
     */
    public DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            ExpectedBinding expected,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    runtimeCatalog,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor publicationFloor,
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                    managedTrustRoots,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), expected, runtimeCatalog, publicationFloor,
                managedTrustRoots, settings, null, true);
    }

    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding expected,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    runtimeCatalog,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor publicationFloor,
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                    managedTrustRoots,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this(objectMapper, clock, expected, runtimeCatalog, publicationFloor,
                Objects.requireNonNull(managedTrustRoots, "managedTrustRoots"), settings,
                fetcher, startScheduler, managedMaterial(managedTrustRoots));
    }

    private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding expected,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    runtimeCatalog,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor publicationFloor,
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                    managedTrustRoots,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler,
            ManagedMaterial material) {
        this(objectMapper, clock, expected, material.deploymentSignatureThreshold(),
                material.deploymentKeys(), runtimeCatalog, publicationFloor,
                material.witnessTrustDomain(), material.witnessSignatureThreshold(),
                material.witnessKeys(), settings, fetcher, startScheduler, managedTrustRoots);
    }

    /** Returns current verified local state without network, database, or provider I/O. */
    @Override
    public Observation observation() {
        RefreshState observed = state;
        if (observed.inventoryAuthority() == null || observed.publication() == null) {
            throw new IllegalStateException(
                    "Physical provider-inventory publication is not bootstrapped");
        }
        Observation inventory = observed.inventoryAuthority().observation();
        DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority.Snapshot
                rootSnapshot = managedTrustRoots == null ? null : managedTrustRoots.snapshot();
        Instant now = clock.instant();
        boolean available = false;
        String status;
        if (closed) {
            status = "CLOSED";
        } else if (observed.localState() != LocalState.HEALTHY) {
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
        } else if (now.isBefore(observed.publication().witness().material().notBefore())) {
            status = "WITNESS_NOT_YET_VALID";
        } else if (!now.isBefore(
                observed.publication().witness().material().expiresAt())) {
            status = "WITNESS_EXPIRED";
        } else if (observed.publication().material().state()
                == TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.REVOKED) {
            status = "REVOKED";
        } else {
            status = "VERIFIED";
            available = true;
        }
        return new Observation(Observation.SCHEMA_VERSION, true, available, status, SOURCE_TYPE,
                observed.publication().material().sequence(), sourceGeneration(observed),
                inventory.revision(), inventory.materialFingerprint(),
                inventory.policyFingerprint(), inventory.cohortId(), inventory.bindings(),
                effectiveExpiry(inventory, observed.publication()),
                inventory.validSignatureCount(), inventory.requiredSignatureCount());
    }

    /** Publishes aggregate dynamic trust facts without endpoint, ETag, keys, or identities. */
    @Override
    public Descriptor descriptor() {
        Observation observed = observation();
        DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority.Snapshot roots =
                managedTrustRoots == null ? null : managedTrustRoots.snapshot();
        return new Descriptor(Descriptor.SCHEMA_VERSION, true, true, observed.available(),
                observed.status(), observed.revision(), observed.bindings().size(),
                Map.ofEntries(
                        Map.entry("sourceType", SOURCE_TYPE),
                        Map.entry("privateMaterialPresent", false),
                        Map.entry("dynamicInventory", true),
                        Map.entry("automaticRefresh", scheduler != null && !closed),
                        Map.entry("signedRevocation", true),
                        Map.entry("durablePublicationFloor", publicationFloor.durable()),
                        Map.entry("witnessedPublications", true),
                        Map.entry("externalNonEquivocation",
                                publicationFloor.externallyAnchored()),
                        Map.entry("byzantineQuorumNonEquivocation",
                                publicationFloor.byzantineQuorumAnchored()),
                        Map.entry("managedTrustRootRefresh", managedTrustRoots != null),
                        Map.entry("managedTrustRootAvailable",
                                roots != null && roots.available()),
                        Map.entry("managedTrustRootStatus",
                                roots == null ? "DISABLED" : roots.status()),
                        Map.entry("managedTrustRootSequence",
                                roots == null ? 0L : roots.sequence()),
                        Map.entry("atomicDualTrustRootPublication",
                                managedTrustRoots != null),
                        Map.entry("durableTrustRootFloor", managedTrustRoots != null),
                        Map.entry("externallyAnchoredTrustRootFloor",
                                managedTrustRoots != null
                                        && managedTrustRoots.externallyAnchoredFloor()),
                        Map.entry("byzantineQuorumAnchoredTrustRootFloor",
                                managedTrustRoots != null
                                        && managedTrustRoots.byzantineQuorumAnchoredFloor())));
    }

    /**
     * Resolves an exact currently published binding and fences the wrapper to the publication.
     *
     * @param providerId retained provider identity
     * @param deploymentId retained provider generation
     * @return exact publication-fenced observation authority
     */
    @Override
    public TestSuiteStabilityPhysicalAttemptObservationAuthority resolve(
            String providerId, String deploymentId) {
        RefreshState observed = state;
        String generation = sourceGeneration(observed);
        requireCurrent(generation);
        TestSuiteStabilityPhysicalAttemptObservationAuthority delegate =
                observed.inventoryAuthority().resolve(providerId, deploymentId);
        return new PublicationFencedAuthority(delegate, generation);
    }

    /**
     * Returns the signed exact replica set and private generation for database cohort fencing.
     *
     * @return current signed cohort binding without external I/O
     */
    public CohortBinding cohortBinding() {
        RefreshState observed = state;
        Observation inventory = observation();
        var material = observed.publication().material();
        return new CohortBinding(CohortBinding.SCHEMA_VERSION, material.scopeId(),
                material.cohortId(), material.expectedReplicaIds(), inventory.available(),
                inventory.sourceSequence(), inventory.sourceGenerationFingerprint(),
                inventory.expiresAt());
    }

    /**
     * Returns aggregate process-local refresh state for health and bounded telemetry.
     *
     * @return identity-free refresh snapshot
     */
    public Snapshot snapshot() {
        RefreshState observed = state;
        Observation inventory = observation();
        DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority.Snapshot roots =
                managedTrustRoots == null ? null : managedTrustRoots.snapshot();
        return new Snapshot(Snapshot.SCHEMA_VERSION, inventory.available(),
                effectiveRefreshState(observed), observed.publication().material().state().name(),
                observed.publication().material().sequence(),
                observed.lastSuccessfulRefreshAt(), observed.refreshSuccessCount(),
                observed.refreshFailureCount(), observed.lastFailureCode(),
                settings.refreshInterval().toSeconds(),
                settings.maximumSnapshotAge().toSeconds(),
                currentWitnessSignatureThreshold(), publicationFloor.durable(),
                managedTrustRoots != null, roots != null && roots.available(),
                roots == null ? "DISABLED" : roots.status(),
                roots == null ? 0L : roots.sequence(),
                managedTrustRoots != null,
                managedTrustRoots != null && managedTrustRoots.externallyAnchoredFloor(),
                managedTrustRoots != null
                        && managedTrustRoots.byzantineQuorumAnchoredFloor());
    }

    /** Stops refresh and immediately closes new provider resolution. */
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
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication;
                ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority authority;
                String trustRootGenerationFingerprint;
                if (fetched.notModified()) {
                    if (previous.publication() == null
                            || previous.inventoryAuthority() == null) {
                        throw new IllegalArgumentException(
                                "Physical provider-inventory source returned 304 before bootstrap");
                    }
                    publication = previous.publication();
                    if (managedTrustRoots != null
                            && !previous.trustRootGenerationFingerprint().equals(
                            managedTrustRoots.generationFingerprint())) {
                        VerifiedPublication verified = verify(publication, previous, now);
                        authority = verified.inventoryAuthority();
                        trustRootGenerationFingerprint =
                                verified.trustRootGenerationFingerprint();
                    } else {
                        authority = previous.inventoryAuthority();
                        trustRootGenerationFingerprint =
                                previous.trustRootGenerationFingerprint();
                    }
                } else {
                    publication = parse(fetched.body());
                    VerifiedPublication verified = verify(publication, previous, now);
                    authority = verified.inventoryAuthority();
                    trustRootGenerationFingerprint =
                            verified.trustRootGenerationFingerprint();
                }
                String etag = fetched.etag().isBlank() ? previous.etag() : fetched.etag();
                state = new RefreshState(publication, authority,
                        trustRootGenerationFingerprint, etag, LocalState.HEALTHY, now,
                        previous.refreshSuccessCount() + 1,
                        previous.refreshFailureCount(), "");
                refreshFailureLogged.set(false);
                return true;
            } catch (RuntimeException unavailable) {
                state = previous.failed(failureCode(unavailable));
                if (refreshFailureLogged.compareAndSet(false, true)) {
                    log.warn("Dynamic physical provider-inventory refresh failed; "
                            + "provider resolution is now fail-closed");
                }
                return false;
            }
        }
    }

    private TestSuiteStabilityPhysicalAttemptProviderInventoryPublication parse(byte[] body) {
        try {
            return objectMapper.readValue(body,
                    TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory publication JSON is invalid", invalid);
        }
    }

    private VerifiedPublication verify(
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication,
            RefreshState previous,
            Instant now) {
        VerificationTrust verificationTrust = verificationTrust(publication);
        if (!publication.fingerprintVerified(objectMapper)
                || !publication.witness().fingerprintVerified(objectMapper)
                || !verificationTrust.deploymentTrustDomain().equals(
                publication.material().trustDomain())
                || !expected.acceptedPolicyFingerprints().contains(
                publication.material().policyFingerprint())) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory publication identity or policy is invalid");
        }
        requireCurrentWindow(publication.material().issuedAt(),
                publication.material().notBefore(), publication.material().expiresAt(), now,
                "Physical provider-inventory publication");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                verificationTrust.deploymentKeys(),
                verificationTrust.deploymentSignatureThreshold(), publication.signatures(),
                publication.materialFingerprint(), publication.material().issuedAt(),
                publication.material().expiresAt(), now,
                "Physical provider-inventory publication");

        var witness = publication.witness().material();
        if (!verificationTrust.witnessTrustDomain().equals(witness.witnessDomain())
                || witness.notBefore().isAfter(
                publication.material().notBefore().plus(CLOCK_SKEW))
                || witness.expiresAt().isBefore(publication.material().expiresAt())) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory witness binding is invalid");
        }
        requireCurrentWindow(witness.issuedAt(), witness.notBefore(), witness.expiresAt(), now,
                "Physical provider-inventory witness");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                verificationTrust.witnessKeys(),
                verificationTrust.witnessSignatureThreshold(),
                publication.witness().signatures(),
                publication.witness().materialFingerprint(), witness.issuedAt(),
                witness.expiresAt(), now, "Physical provider-inventory witness");
        requireSuccessor(publication, previous.publication());

        Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority> active =
                selectRuntimeAdapters(publication.inventory().material().bindings());
        var authority =
                new ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                        objectMapper, clock, expected,
                        verificationTrust.deploymentSignatureThreshold(),
                        verificationTrust.deploymentKeyList(), publication.inventory(), active);
        publicationFloor.accept(new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.Generation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.Generation
                        .SCHEMA_VERSION,
                expected.scopeId(), publication.material().sequence(),
                publication.materialFingerprint(),
                publication.witness().materialFingerprint(),
                publication.material().previousPublicationFingerprint(),
                witness.previousWitnessFingerprint()));
        return new VerifiedPublication(authority,
                verificationTrust.trustRootGenerationFingerprint());
    }

    private VerificationTrust verificationTrust(
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication) {
        if (managedTrustRoots == null) {
            return new VerificationTrust(expected.trustDomain(), witnessDomain,
                    signatureThreshold, witnessSignatureThreshold,
                    indexedAuthorityKeys, indexedWitnessKeys, authorityKeys, "");
        }
        var keys = managedTrustRoots.keysFor(
                publication.signatures(), publication.witness().signatures());
        return new VerificationTrust(keys.deploymentTrustDomain(),
                keys.witnessTrustDomain(), keys.deploymentSignatureThreshold(),
                keys.witnessSignatureThreshold(), keys.deploymentKeys(), keys.witnessKeys(),
                keys.deploymentKeys().values().stream().toList(),
                keys.generationFingerprint());
    }

    private int currentWitnessSignatureThreshold() {
        return managedTrustRoots == null ? witnessSignatureThreshold
                : managedTrustRoots.snapshot().witnessSignatureThreshold();
    }

    private Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
            selectRuntimeAdapters(List<Binding> bindings) {
        Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority> selected =
                new HashMap<>();
        for (Binding binding : bindings) {
            TestSuiteStabilityPhysicalAttemptObservationAuthority adapter =
                    runtimeCatalog.get(binding.identity());
            if (adapter == null) {
                throw new IllegalArgumentException(
                        "Published physical provider inventory has no installed runtime adapter");
            }
            selected.put(binding.identity(), adapter);
        }
        return Map.copyOf(selected);
    }

    private void requireCurrent(String expectedGeneration) {
        Observation current = observation();
        if (!current.available()
                || !current.sourceGenerationFingerprint().equals(expectedGeneration)) {
            throw new IllegalStateException(
                    "Physical provider-inventory publication generation is unavailable");
        }
    }

    private String sourceGeneration(RefreshState observed) {
        if (observed == null || observed.publication() == null) {
            throw new IllegalStateException(
                    "Physical provider-inventory publication is unavailable");
        }
        if (observed.trustRootGenerationFingerprint().isBlank()) {
            return ProtocolFingerprint.of(objectMapper, Map.of(
                    "schemaVersion",
                    "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryGeneration.v1",
                    "publicationMaterialFingerprint",
                    observed.publication().materialFingerprint(),
                    "witnessMaterialFingerprint",
                    observed.publication().witness().materialFingerprint(),
                    "inventoryMaterialFingerprint",
                    observed.publication().inventory().materialFingerprint()));
        }
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryManagedGeneration.v1",
                "publicationMaterialFingerprint",
                observed.publication().materialFingerprint(),
                "witnessMaterialFingerprint",
                observed.publication().witness().materialFingerprint(),
                "inventoryMaterialFingerprint",
                observed.publication().inventory().materialFingerprint(),
                "trustRootGenerationFingerprint",
                observed.trustRootGenerationFingerprint()));
    }

    private static Instant effectiveExpiry(
            Observation inventory,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication) {
        return List.of(inventory.expiresAt(), publication.material().expiresAt(),
                        publication.witness().material().expiresAt())
                .stream().min(Instant::compareTo).orElseThrow();
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
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication next,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication previous) {
        if (previous == null) {
            return;
        }
        long nextRevision = next.inventory().material().revision();
        long previousRevision = previous.inventory().material().revision();
        if (nextRevision < previousRevision
                || nextRevision == previousRevision
                && !next.inventory().materialFingerprint().equals(
                previous.inventory().materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory revision is rolled back or forked");
        }
        if (next.material().sequence() == previous.material().sequence()) {
            if (!next.materialFingerprint().equals(previous.materialFingerprint())
                    || !next.witness().materialFingerprint().equals(
                    previous.witness().materialFingerprint())
                    || !next.inventory().materialFingerprint().equals(
                    previous.inventory().materialFingerprint())) {
                throw new IllegalArgumentException(
                        "Physical provider-inventory publication sequence is forked");
            }
            return;
        }
        if (next.material().sequence() != previous.material().sequence() + 1
                || !next.material().previousPublicationFingerprint().equals(
                previous.materialFingerprint())
                || !next.witness().material().previousWitnessFingerprint().equals(
                previous.witness().materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory publication chain is discontinuous");
        }
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-physical-provider-inventory-refresh");
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
        if (observed.localState() == LocalState.HEALTHY
                && (observed.lastSuccessfulRefreshAt() == null
                || !clock.instant().isBefore(observed.lastSuccessfulRefreshAt()
                .plus(settings.maximumSnapshotAge())))) {
            return "EXPIRED";
        }
        return observed.localState().name();
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

    private static Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
            validatedCatalog(
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    candidates) {
        Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority> catalog =
                candidates == null ? Map.of() : Map.copyOf(candidates);
        if (catalog.isEmpty() || catalog.size() > 128
                || catalog.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException(
                    "Physical provider runtime adapter catalog is invalid");
        }
        return catalog;
    }

    private static boolean independentAuthorities(
            List<AuthorityKey> deploymentKeys,
            List<AuthorityKey> witnessKeys) {
        Set<String> authorityIds = new HashSet<>();
        Set<String> publicKeys = new HashSet<>();
        for (AuthorityKey key : deploymentKeys) {
            authorityIds.add(key.authorityId());
            publicKeys.add(Base64.getEncoder().encodeToString(key.publicKey().getEncoded()));
        }
        for (AuthorityKey key : witnessKeys) {
            if (authorityIds.contains(key.authorityId())
                    || publicKeys.contains(Base64.getEncoder().encodeToString(
                    key.publicKey().getEncoded()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bounded remote publication source policy.
     *
     * @param publicationUri HTTPS publication endpoint
     * @param refreshInterval fixed-delay refresh interval
     * @param requestTimeout per-fetch connect and request timeout
     * @param maximumSnapshotAge hard local source freshness fence
     * @param allowInsecureLoopback local-test-only HTTP loopback escape hatch
     */
    public record Settings(
            URI publicationUri,
            Duration refreshInterval,
            Duration requestTimeout,
            Duration maximumSnapshotAge,
            boolean allowInsecureLoopback) {

        /**
         * Validates HTTPS, bounded time, and refresh/freshness relationships.
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
                        "Physical provider-inventory snapshot age must cover refresh plus timeout");
            }
            return new Settings(publicationUri, refresh, timeout, maximumAge,
                    allowInsecureLoopback);
        }

        private static void validateUri(URI uri, boolean allowInsecureLoopback) {
            if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException(
                        "A valid physical provider-inventory publication URI is required");
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
                        "Physical provider-inventory source must use HTTPS");
            }
        }
    }

    /**
     * Signed exact cohort binding consumed by the database convergence gate.
     *
     * @param schemaVersion binding generation
     * @param scopeId stable provider-fleet scope
     * @param cohortId exact rollout cohort
     * @param expectedReplicaIds sorted complete signed replica set
     * @param inventoryAvailable whether this publication may currently resolve work
     * @param sourceSequence exact publication sequence
     * @param sourceGenerationFingerprint exact publication generation identity
     * @param expiresAt earliest inventory/publication/witness deadline
     */
    public record CohortBinding(
            String schemaVersion,
            String scopeId,
            String cohortId,
            List<String> expectedReplicaIds,
            boolean inventoryAvailable,
            long sourceSequence,
            String sourceGenerationFingerprint,
            Instant expiresAt) {

        /** Current signed physical provider-inventory cohort binding generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryCohortBinding.v1";

        /** Rejects unordered, duplicated, or generation-incomplete private bindings. */
        public CohortBinding {
            schemaVersion = normalized(schemaVersion);
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            expectedReplicaIds = expectedReplicaIds == null
                    ? List.of() : List.copyOf(expectedReplicaIds);
            sourceGenerationFingerprint = normalized(sourceGenerationFingerprint);
            if (!SCHEMA_VERSION.equals(schemaVersion) || scopeId.isBlank()
                    || cohortId.isBlank() || expectedReplicaIds.isEmpty()
                    || expectedReplicaIds.size() > 256
                    || !expectedReplicaIds.stream().sorted().toList()
                    .equals(expectedReplicaIds)
                    || new HashSet<>(expectedReplicaIds).size() != expectedReplicaIds.size()
                    || sourceSequence < 1
                    || !FINGERPRINT.matcher(sourceGenerationFingerprint).matches()
                    || expiresAt == null) {
                throw new IllegalArgumentException(
                        "Physical provider-inventory cohort binding is invalid");
            }
        }
    }

    /**
     * Identity-free dynamic refresh snapshot.
     *
     * @param schemaVersion snapshot generation
     * @param available whether current publication may resolve work
     * @param refreshState bounded local refresh state
     * @param publicationState ACTIVE or REVOKED
     * @param sequence current publication sequence
     * @param lastSuccessfulRefreshAt last complete refresh time
     * @param refreshSuccessCount successful local refresh count
     * @param refreshFailureCount failed local refresh count
     * @param lastFailureCode stable payload-free failure family
     * @param refreshIntervalSeconds configured fixed-delay interval
     * @param maximumSnapshotAgeSeconds hard local source freshness
     * @param witnessSignatureThreshold configured witness quorum
     * @param durablePublicationFloor whether ordering survives fleet restart
     * @param managedTrustRootRefresh whether runtime verification keys refresh atomically
     * @param managedTrustRootAvailable whether the current dual key set is usable
     * @param managedTrustRootStatus bounded managed-root lifecycle state
     * @param managedTrustRootSequence current accepted managed-root sequence, or zero when disabled
     * @param durableTrustRootFloor whether managed-root ordering survives fleet restart
     * @param externallyAnchoredTrustRootFloor whether roots are ordered outside the local database
     * @param byzantineQuorumAnchoredTrustRootFloor whether the root anchor tolerates faulty notaries
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
            boolean durablePublicationFloor,
            boolean managedTrustRootRefresh,
            boolean managedTrustRootAvailable,
            String managedTrustRootStatus,
            long managedTrustRootSequence,
            boolean durableTrustRootFloor,
            boolean externallyAnchoredTrustRootFloor,
            boolean byzantineQuorumAnchoredTrustRootFloor) {

        /** Current identity-free refresh snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryRefreshSnapshot.v2";

        /** Enforces bounded aggregate-only operational state. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            refreshState = normalized(refreshState);
            publicationState = normalized(publicationState);
            lastFailureCode = normalized(lastFailureCode);
            managedTrustRootStatus = normalized(managedTrustRootStatus);
            if (!SCHEMA_VERSION.equals(schemaVersion) || refreshState.isBlank()
                    || publicationState.isBlank() || sequence < 1
                    || refreshSuccessCount < 0 || refreshFailureCount < 0
                    || refreshIntervalSeconds < 1 || maximumSnapshotAgeSeconds < 2
                    || witnessSignatureThreshold < 1 || witnessSignatureThreshold > 32
                    || !durablePublicationFloor || managedTrustRootStatus.isBlank()
                    || managedTrustRootRefresh != durableTrustRootFloor
                    || managedTrustRootRefresh && (managedTrustRootSequence < 1
                    || managedTrustRootAvailable != "HEALTHY".equals(managedTrustRootStatus))
                    || !managedTrustRootRefresh && (managedTrustRootAvailable
                    || managedTrustRootSequence != 0
                    || !"DISABLED".equals(managedTrustRootStatus))
                    || externallyAnchoredTrustRootFloor && !durableTrustRootFloor
                    || byzantineQuorumAnchoredTrustRootFloor
                    && !externallyAnchoredTrustRootFloor) {
                throw new IllegalArgumentException(
                        "Physical provider-inventory refresh snapshot is invalid");
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
                        "Physical provider-inventory publication response is invalid");
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

    private final class PublicationFencedAuthority
            implements TestSuiteStabilityPhysicalAttemptObservationAuthority {
        private final TestSuiteStabilityPhysicalAttemptObservationAuthority delegate;
        private final String generation;

        private PublicationFencedAuthority(
                TestSuiteStabilityPhysicalAttemptObservationAuthority delegate,
                String generation) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.generation = generation;
        }

        @Override
        public Descriptor descriptor() {
            requireCurrent(generation);
            return delegate.descriptor();
        }

        @Override
        public TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observe(
                TestSuiteStabilityPhysicalAttemptObservationCommand command) {
            requireCurrent(generation);
            return delegate.observe(command);
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
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET().timeout(timeout)
                        .header("Accept", MEDIA_TYPE)
                        .header(PROTOCOL_HEADER,
                                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication
                                        .SCHEMA_VERSION);
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
                            "Physical provider-inventory source returned non-success status");
                }
                requireProtocolHeaders(response);
                long declared = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1);
                if (declared > MAXIMUM_DOCUMENT_BYTES) {
                    response.body().close();
                    throw new IllegalArgumentException(
                            "Physical provider-inventory publication is too large");
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
                        "Physical provider-inventory request was interrupted", interrupted);
            } catch (IOException unavailable) {
                throw new RemotePublicationUnavailableException(
                        "Physical provider-inventory request failed", unavailable);
            }
        }

        private static void requireProtocolHeaders(HttpResponse<?> response) {
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT);
            String protocol = response.headers().firstValue(PROTOCOL_HEADER).orElse("");
            boolean exactMediaType = contentType.equals(MEDIA_TYPE)
                    || contentType.startsWith(MEDIA_TYPE + ";");
            if (!exactMediaType
                    || !TestSuiteStabilityPhysicalAttemptProviderInventoryPublication
                    .SCHEMA_VERSION.equals(protocol)) {
                try {
                    if (response.body() instanceof InputStream body) {
                        body.close();
                    }
                } catch (IOException ignored) {
                    // Protocol rejection remains authoritative.
                }
                throw new IllegalArgumentException(
                        "Physical provider-inventory protocol negotiation failed");
            }
        }
    }

    private enum LocalState {
        BOOTSTRAPPING,
        HEALTHY,
        UNAVAILABLE
    }

    private record RefreshState(
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication,
            ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                    inventoryAuthority,
            String trustRootGenerationFingerprint,
            String etag,
            LocalState localState,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode) {

        private RefreshState {
            trustRootGenerationFingerprint = normalized(trustRootGenerationFingerprint);
            etag = normalized(etag);
            localState = localState == null ? LocalState.UNAVAILABLE : localState;
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

    private static ManagedMaterial managedMaterial(
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority roots) {
        var keys = Objects.requireNonNull(roots, "roots").keysFor(List.of(), List.of());
        return new ManagedMaterial(keys.deploymentSignatureThreshold(),
                keys.witnessTrustDomain(), keys.witnessSignatureThreshold(),
                keys.deploymentKeys().values().stream().toList(),
                keys.witnessKeys().values().stream().toList());
    }

    private record VerificationTrust(
            String deploymentTrustDomain,
            String witnessTrustDomain,
            int deploymentSignatureThreshold,
            int witnessSignatureThreshold,
            Map<String, AuthorityKey> deploymentKeys,
            Map<String, AuthorityKey> witnessKeys,
            List<AuthorityKey> deploymentKeyList,
            String trustRootGenerationFingerprint) {
    }

    private record VerifiedPublication(
            ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                    inventoryAuthority,
            String trustRootGenerationFingerprint) {
    }

    private record ManagedMaterial(
            int deploymentSignatureThreshold,
            String witnessTrustDomain,
            int witnessSignatureThreshold,
            List<AuthorityKey> deploymentKeys,
            List<AuthorityKey> witnessKeys) {
    }

    private static final class RemotePublicationUnavailableException extends RuntimeException {
        private RemotePublicationUnavailableException(String message) {
            super(message);
        }

        private RemotePublicationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String label) {
        Duration result = Objects.requireNonNull(value, label);
        if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory " + label + " is invalid");
        }
        return result;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
