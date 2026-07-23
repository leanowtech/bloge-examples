package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Ephemeral, run-generation snapshot of governed exact and retry-trajectory outcomes.
 *
 * <p>Instances are assembled only after publication, policy, retention, grant, deletion, region,
 * classification, and content-address checks pass. Response JSON remains private byte material and
 * is exposed only as a declarative {@link FixtureRule} at invocation time. Text rendering,
 * evidence, plans, and public accessors expose only payload-free references and fingerprints.
 * Constructors model an already-authorized in-memory snapshot; they do not confer governance
 * authority. The integration path can inject a snapshot into compilation only through the
 * package-private compiler boundary after online serving revalidation.</p>
 */
public final class ResolvedCorpusPayloads {
    /** Whole-generation in-memory payload bound. */
    public static final long MAXIMUM_TOTAL_BYTES = 256L * 1024 * 1024;

    private static final ResolvedCorpusPayloads EMPTY =
            new ResolvedCorpusPayloads(Map.of(), Map.of());

    private final Map<MirrorArtifactRef, CapabilityCorpus> byCapability;
    private final Map<String, CapabilityCorpus> bySite;

    private ResolvedCorpusPayloads(
            Map<MirrorArtifactRef, CapabilityCorpus> byCapability,
            Map<String, CapabilityCorpus> bySite) {
        this.byCapability = immutableCapabilities(byCapability);
        this.bySite = Collections.unmodifiableMap(new java.util.TreeMap<>(
                bySite == null ? Map.of() : bySite));
        long bytes = this.byCapability.values().stream()
                .mapToLong(CapabilityCorpus::payloadBytes)
                .sum();
        if (bytes > MAXIMUM_TOTAL_BYTES) {
            throw new IllegalArgumentException(
                    "resolved corpus payloads exceed the whole-generation byte bound");
        }
        if (this.bySite.values().stream()
                .anyMatch(value -> !this.byCapability.containsValue(value))) {
            throw new IllegalArgumentException(
                    "site corpus must belong to the resolved capability closure");
        }
    }

    /** @return empty corpus snapshot */
    public static ResolvedCorpusPayloads empty() {
        return EMPTY;
    }

    /**
     * Creates one validated capability-keyed snapshot before graph-site binding.
     *
     * @param corpora exact resolved capability corpora
     * @return immutable unbound snapshot
     */
    public static ResolvedCorpusPayloads of(List<CapabilityCorpus> corpora) {
        Map<MirrorArtifactRef, CapabilityCorpus> indexed = new LinkedHashMap<>();
        if (corpora != null) {
            for (CapabilityCorpus corpus : corpora) {
                CapabilityCorpus exact = Objects.requireNonNull(corpus, "corpus");
                if (indexed.putIfAbsent(exact.capabilityRef(), exact) != null) {
                    throw new IllegalArgumentException(
                            "duplicate resolved corpus capability");
                }
            }
        }
        return indexed.isEmpty()
                ? empty() : new ResolvedCorpusPayloads(indexed, Map.of());
    }

    /** @return exact capability revisions materialized into this snapshot */
    public Set<MirrorArtifactRef> capabilityRefs() {
        return byCapability.keySet();
    }

    /** @return whether this snapshot contains no corpus publications */
    public boolean isEmpty() {
        return byCapability.isEmpty();
    }

    /**
     * Binds exact graph invocation sites to their external capability corpora.
     *
     * @param capabilityBySite exact external capability per site
     * @return same immutable payload snapshot with deterministic site bindings
     */
    public ResolvedCorpusPayloads bindSites(
            Map<String, MirrorArtifactRef> capabilityBySite) {
        Map<String, CapabilityCorpus> sites = new LinkedHashMap<>();
        if (capabilityBySite != null) {
            capabilityBySite.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String site = required(entry.getKey(), "invocationSiteId");
                        CapabilityCorpus corpus = byCapability.get(entry.getValue());
                        if (corpus != null) {
                            sites.put(site, corpus);
                        }
                    });
        }
        return sites.isEmpty() && bySite.isEmpty()
                ? this : new ResolvedCorpusPayloads(byCapability, sites);
    }

    /**
     * Returns the exact corpus bound to one invocation site.
     *
     * @param invocationSiteId frozen site identity
     * @return site corpus or empty when no publication was selected
     */
    public Optional<CapabilityCorpus> forSite(String invocationSiteId) {
        return Optional.ofNullable(bySite.get(invocationSiteId));
    }

    /** @return deterministic invocation sites backed by any recorded corpus data */
    public Set<String> siteIds() {
        return bySite.keySet();
    }

    /** @return deterministic sites with at least one standalone recorded-exact sample */
    public Set<String> exactSiteIds() {
        return bySite.entrySet().stream()
                .filter(entry -> !entry.getValue().samples().isEmpty())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** @return deterministic sites with at least one governed retry trajectory */
    public Set<String> trajectorySiteIds() {
        return bySite.entrySet().stream()
                .filter(entry -> !entry.getValue().trajectories().isEmpty())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Prevents response material from entering logs. */
    @Override
    public String toString() {
        return "ResolvedCorpusPayloads[capabilities=" + byCapability.size()
                + ", sites=" + bySite.size() + ", samples="
                + byCapability.values().stream().mapToInt(value -> value.samples().size()).sum()
                + ", trajectories="
                + byCapability.values().stream()
                .mapToInt(value -> value.trajectories().size()).sum()
                + "]";
    }

    /**
     * One exact publication and its deterministic request-fingerprint index.
     */
    public static final class CapabilityCorpus {
        private final MirrorArtifactRef capabilityRef;
        private final MirrorArtifactRef publicationRef;
        private final MirrorArtifactRef corpusRevisionRef;
        private final Instant materializedAt;
        private final Instant usableUntil;
        private final Map<String, Sample> byRequestFingerprint;
        private final Map<String, Trajectory> trajectoryByRequestFingerprint;

        /**
         * Creates one conflict-free capability corpus.
         *
         * @param capabilityRef exact capability
         * @param publicationRef exact latest serving publication
         * @param corpusRevisionRef exact published revision
         * @param materializedAt frozen serving instant
         * @param usableUntil exclusive serving horizon
         * @param samples unique exact request outcomes
         */
        public CapabilityCorpus(
                MirrorArtifactRef capabilityRef,
                MirrorArtifactRef publicationRef,
                MirrorArtifactRef corpusRevisionRef,
                Instant materializedAt,
                Instant usableUntil,
                List<Sample> samples) {
            this(capabilityRef, publicationRef, corpusRevisionRef,
                    materializedAt, usableUntil, samples, List.of());
        }

        /**
         * Creates one capability corpus with independent exact and trajectory indexes.
         *
         * @param capabilityRef exact capability
         * @param publicationRef exact latest serving publication
         * @param corpusRevisionRef exact published revision
         * @param materializedAt frozen serving instant
         * @param usableUntil exclusive serving horizon
         * @param samples unique standalone exact request outcomes
         * @param trajectories unique reviewed retry sequences by request fingerprint
         */
        public CapabilityCorpus(
                MirrorArtifactRef capabilityRef,
                MirrorArtifactRef publicationRef,
                MirrorArtifactRef corpusRevisionRef,
                Instant materializedAt,
                Instant usableUntil,
                List<Sample> samples,
                List<Trajectory> trajectories) {
            this.capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
            this.publicationRef = ref(publicationRef,
                    "CAPABILITY_CORPUS_PUBLICATION", "publicationRef");
            this.corpusRevisionRef = ref(corpusRevisionRef,
                    "CAPABILITY_CORPUS_REVISION", "corpusRevisionRef");
            this.materializedAt = Objects.requireNonNull(materializedAt, "materializedAt");
            this.usableUntil = Objects.requireNonNull(usableUntil, "usableUntil");
            if (!usableUntil.isAfter(materializedAt)) {
                throw new IllegalArgumentException("usableUntil must follow materializedAt");
            }
            List<Sample> exactSamples =
                    samples == null ? List.of() : List.copyOf(samples);
            List<Trajectory> exactTrajectories =
                    trajectories == null ? List.of() : List.copyOf(trajectories);
            if (exactSamples.isEmpty() && exactTrajectories.isEmpty()) {
                throw new IllegalArgumentException(
                        "capability corpus requires an exact sample or trajectory");
            }
            if (exactSamples.size() > 1_000 || exactTrajectories.size() > 1_000) {
                throw new IllegalArgumentException(
                        "capability corpus outcome indexes exceed their item bounds");
            }
            Map<String, Sample> indexed = new java.util.TreeMap<>();
            for (Sample sample : exactSamples) {
                Sample exact = Objects.requireNonNull(sample, "sample");
                if (indexed.putIfAbsent(exact.requestFingerprint(), exact) != null) {
                    throw new IllegalArgumentException(
                            "capability corpus contains a duplicate request fingerprint");
                }
            }
            this.byRequestFingerprint = Collections.unmodifiableMap(indexed);
            Map<String, Trajectory> trajectoryIndex = new java.util.TreeMap<>();
            for (Trajectory trajectory : exactTrajectories) {
                Trajectory exact = Objects.requireNonNull(
                        trajectory, "trajectory");
                if (trajectoryIndex.putIfAbsent(
                        exact.requestFingerprint(), exact) != null) {
                    throw new IllegalArgumentException(
                            "capability corpus contains ambiguous trajectories");
                }
            }
            this.trajectoryByRequestFingerprint =
                    Collections.unmodifiableMap(trajectoryIndex);
        }

        /** @return exact capability */
        public MirrorArtifactRef capabilityRef() {
            return capabilityRef;
        }

        /** @return exact serving publication */
        public MirrorArtifactRef publicationRef() {
            return publicationRef;
        }

        /** @return exact published corpus revision */
        public MirrorArtifactRef corpusRevisionRef() {
            return corpusRevisionRef;
        }

        /** @return frozen materialization instant */
        public Instant materializedAt() {
            return materializedAt;
        }

        /** @return exclusive serving horizon */
        public Instant usableUntil() {
            return usableUntil;
        }

        /** @return payload-safe deterministic sample list */
        public List<Sample> samples() {
            return List.copyOf(byRequestFingerprint.values());
        }

        /** @return exact outcome for one canonical request fingerprint */
        public Optional<Sample> find(String requestFingerprint) {
            return Optional.ofNullable(byRequestFingerprint.get(requestFingerprint));
        }

        /** @return payload-safe reviewed retry trajectories */
        public List<Trajectory> trajectories() {
            return List.copyOf(trajectoryByRequestFingerprint.values());
        }

        /** @return reviewed retry trajectory for one canonical request fingerprint */
        public Optional<Trajectory> findTrajectory(String requestFingerprint) {
            return Optional.ofNullable(
                    trajectoryByRequestFingerprint.get(requestFingerprint));
        }

        private long payloadBytes() {
            return byRequestFingerprint.values().stream()
                    .mapToLong(Sample::payloadBytes)
                    .sum()
                    + trajectoryByRequestFingerprint.values().stream()
                    .mapToLong(Trajectory::payloadBytes)
                    .sum();
        }

        /** Prevents nested payload material from entering logs. */
        @Override
        public String toString() {
            return "CapabilityCorpus[capabilityRef=" + capabilityRef
                    + ", publicationRef=" + publicationRef
                    + ", corpusRevisionRef=" + corpusRevisionRef
                    + ", materializedAt=" + materializedAt
                    + ", usableUntil=" + usableUntil
                    + ", samples=" + byRequestFingerprint.size()
                    + ", trajectories="
                    + trajectoryByRequestFingerprint.size() + "]";
        }
    }

    /**
     * One reviewed, ordered retry sequence selected by canonical request fingerprint.
     */
    public static final class Trajectory {
        private final String requestFingerprint;
        private final MirrorArtifactRef publicationRef;
        private final List<Sample> attempts;

        /**
         * Creates one bounded trajectory whose attempts all share one exact request.
         *
         * @param requestFingerprint canonical request fingerprint
         * @param publicationRef exact reviewed trajectory publication
         * @param attempts ordered attempt outcomes, numbered from one by list position
         */
        public Trajectory(
                String requestFingerprint,
                MirrorArtifactRef publicationRef,
                List<Sample> attempts) {
            this.requestFingerprint = fingerprint(requestFingerprint);
            this.publicationRef = ref(
                    publicationRef,
                    "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION",
                    "trajectoryPublicationRef");
            this.attempts = attempts == null ? List.of() : List.copyOf(attempts);
            if (this.attempts.size() < 2 || this.attempts.size() > 32) {
                throw new IllegalArgumentException(
                        "trajectory requires between 2 and 32 attempts");
            }
            if (this.attempts.stream().anyMatch(
                    attempt -> attempt == null
                            || !this.requestFingerprint.equals(
                            attempt.requestFingerprint()))) {
                throw new IllegalArgumentException(
                        "trajectory attempts must share the trajectory request fingerprint");
            }
            for (int index = 0; index < this.attempts.size() - 1; index++) {
                if (!this.attempts.get(index).retryableError()) {
                    throw new IllegalArgumentException(
                            "trajectory intermediate attempts must be retryable errors");
                }
            }
            if (this.attempts.getLast().retryableError()) {
                throw new IllegalArgumentException(
                        "trajectory final attempt must be terminal");
            }
        }

        /** @return canonical request fingerprint */
        public String requestFingerprint() {
            return requestFingerprint;
        }

        /** @return exact reviewed trajectory publication */
        public MirrorArtifactRef publicationRef() {
            return publicationRef;
        }

        /** @return ordered payload-safe attempt outcomes */
        public List<Sample> attempts() {
            return attempts;
        }

        /**
         * Returns one one-based attempt outcome.
         *
         * @param attempt one-based delegate attempt
         * @return matching outcome, or empty when the sequence is exhausted
         */
        public Optional<Sample> attempt(int attempt) {
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            return attempt > attempts.size()
                    ? Optional.empty() : Optional.of(attempts.get(attempt - 1));
        }

        private long payloadBytes() {
            return attempts.stream().mapToLong(Sample::payloadBytes).sum();
        }

        /** Prevents nested response JSON from entering logs. */
        @Override
        public String toString() {
            return "Trajectory[requestFingerprint=" + requestFingerprint
                    + ", publicationRef=" + publicationRef
                    + ", attempts=" + attempts.size()
                    + ", payloadBytes=" + payloadBytes() + "]";
        }
    }

    /**
     * One exact request outcome with payload-safe provenance.
     */
    public static final class Sample {
        private final String requestFingerprint;
        private final byte[] canonicalResponseJson;
        private final ErrorOutcome error;
        private final List<MirrorArtifactRef> artifactRefs;
        private final List<String> ruleRefs;
        private final double freshness;
        private final List<String> limitations;

        private Sample(
                String requestFingerprint,
                byte[] canonicalResponseJson,
                ErrorOutcome error,
                List<MirrorArtifactRef> artifactRefs,
                List<String> ruleRefs,
                double freshness,
                List<String> limitations) {
            this.requestFingerprint = fingerprint(requestFingerprint);
            this.canonicalResponseJson = canonicalResponseJson == null
                    ? new byte[0]
                    : Arrays.copyOf(canonicalResponseJson, canonicalResponseJson.length);
            this.error = error;
            if ((this.canonicalResponseJson.length > 0) == (error != null)) {
                throw new IllegalArgumentException(
                        "sample requires exactly one response or normalized error");
            }
            this.artifactRefs = ResolvedCorpusPayloads.artifactRefs(artifactRefs);
            this.ruleRefs = strings(ruleRefs, "ruleRefs", 1_000);
            if (!Double.isFinite(freshness) || freshness < 0 || freshness > 1) {
                throw new IllegalArgumentException(
                        "freshness must be in the closed interval [0,1]");
            }
            this.freshness = freshness;
            this.limitations = strings(limitations, "limitations", 64);
        }

        /** Creates a successful exact recorded response. */
        public static Sample response(
                String requestFingerprint,
                byte[] canonicalResponseJson,
                List<MirrorArtifactRef> artifactRefs,
                List<String> ruleRefs,
                double freshness,
                List<String> limitations) {
            return new Sample(requestFingerprint, canonicalResponseJson, null,
                    artifactRefs, ruleRefs, freshness, limitations);
        }

        /** Creates a normalized exact recorded business error. */
        public static Sample error(
                String requestFingerprint,
                String errorCode,
                String errorType,
                boolean retryable,
                String detailsFingerprint,
                List<MirrorArtifactRef> artifactRefs,
                List<String> ruleRefs,
                double freshness,
                List<String> limitations) {
            return new Sample(requestFingerprint, null,
                    new ErrorOutcome(errorCode, errorType, retryable, detailsFingerprint),
                    artifactRefs, ruleRefs, freshness, limitations);
        }

        /** @return canonical request fingerprint */
        public String requestFingerprint() {
            return requestFingerprint;
        }

        /** @return whether the exact observation produced a normalized error */
        public boolean error() {
            return error != null;
        }

        /** Used only after the serving boundary authorized this observed retry label. */
        boolean retryableError() {
            return error != null && error.retryable();
        }

        /** @return exact normalized error-details fingerprint, or blank for responses */
        public String errorDetailsFingerprint() {
            return error == null ? "" : error.detailsFingerprint();
        }

        /** @return exact payload-free artifacts used by this outcome */
        public List<MirrorArtifactRef> artifactRefs() {
            return artifactRefs;
        }

        /** @return exact payload-free source rule identities */
        public List<String> ruleRefs() {
            return ruleRefs;
        }

        /** @return normalized source freshness */
        public double freshness() {
            return freshness;
        }

        /** @return bounded fidelity and governance limitations */
        public List<String> limitations() {
            return limitations;
        }

        /**
         * Lowers this sample into the shared schema-gated test-double behavior.
         *
         * @param mapper runtime mapper
         * @return optional unbounded synthetic rule selected only by request fingerprint
         */
        public FixtureRule toRule(ObjectMapper mapper) {
            Objects.requireNonNull(mapper, "mapper");
            FixtureRule.Behavior behavior;
            if (error == null) {
                try {
                    behavior = FixtureRule.Behavior.returning(
                            mapper.readValue(canonicalResponseJson, Object.class));
                } catch (IOException invalid) {
                    throw new TestControlException(
                            "CORPUS_PAYLOAD_GENERATION_INVALID", "MIRROR_RESOLUTION",
                            "Frozen corpus response could not be materialized.");
                }
            } else {
                behavior = FixtureRule.Behavior.throwing(
                        error.errorCode(), error.errorType(), "");
            }
            String id = "corpus:" + requestFingerprint.substring(7);
            return new FixtureRule(FixtureRule.SCHEMA_VERSION, id,
                    FixtureRule.Selector.any(), behavior,
                    new FixtureRule.Consumption(false, 0, 0,
                            FixtureRule.ExhaustedAction.FAIL,
                            FixtureRule.UnmatchedAction.FAIL),
                    FixtureRule.SchemaCheck.strict());
        }

        private long payloadBytes() {
            return canonicalResponseJson.length;
        }

        /** Prevents response JSON from entering logs. */
        @Override
        public String toString() {
            return "Sample[requestFingerprint=" + requestFingerprint
                    + ", outcome=" + (error == null ? "RESPONSE" : "ERROR")
                    + ", payloadBytes=" + canonicalResponseJson.length
                    + ", artifactRefs=" + artifactRefs.size() + "]";
        }
    }

    private record ErrorOutcome(
            String errorCode,
            String errorType,
            boolean retryable,
            String detailsFingerprint
    ) {
        private ErrorOutcome {
            errorCode = required(errorCode, "errorCode");
            errorType = required(errorType, "errorType");
            detailsFingerprint = fingerprint(detailsFingerprint);
        }
    }

    private static Map<MirrorArtifactRef, CapabilityCorpus> immutableCapabilities(
            Map<MirrorArtifactRef, CapabilityCorpus> values) {
        Map<MirrorArtifactRef, CapabilityCorpus> ordered = new LinkedHashMap<>();
        (values == null ? Map.<MirrorArtifactRef, CapabilityCorpus>of() : values)
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint)))
                .forEach(entry -> {
                    MirrorArtifactRef key = ref(
                            entry.getKey(), "CAPABILITY", "capabilityRef");
                    CapabilityCorpus value = Objects.requireNonNull(
                            entry.getValue(), "capabilityCorpus");
                    if (!key.equals(value.capabilityRef())) {
                        throw new IllegalArgumentException(
                                "resolved corpus key differs from capabilityRef");
                    }
                    ordered.put(key, value);
                });
        return Collections.unmodifiableMap(ordered);
    }

    private static List<MirrorArtifactRef> artifactRefs(List<MirrorArtifactRef> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("artifactRefs must not be empty");
        }
        List<MirrorArtifactRef> sorted = values.stream()
                .map(value -> Objects.requireNonNull(value, "artifactRef"))
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (sorted.size() > 1_000 || new LinkedHashSet<>(sorted).size() != sorted.size()) {
            throw new IllegalArgumentException("artifactRefs must be unique and bounded");
        }
        return List.copyOf(sorted);
    }

    private static List<String> strings(
            List<String> values, String field, int maximum) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> sorted = new java.util.TreeSet<>();
        for (String value : values) {
            String exact = required(value, field);
            if (exact.length() > 512 || !sorted.add(exact)) {
                throw new IllegalArgumentException(field + " must be unique and bounded");
            }
        }
        if (sorted.size() > maximum) {
            throw new IllegalArgumentException(field + " exceeds its item bound");
        }
        return List.copyOf(sorted);
    }

    private static MirrorArtifactRef ref(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String fingerprint(String value) {
        String exact = required(value, "fingerprint");
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("fingerprint is invalid");
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 2_048) {
            throw new IllegalArgumentException(field + " must be bounded and non-blank");
        }
        return exact;
    }
}
