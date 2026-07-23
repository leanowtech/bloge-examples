package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict fixture bindings from exact corpus publications to reviewed recorded clusters.
 *
 * <p>The wire location is {@code fixtureBundle.metadata.mirrorClusters}. Every cluster repeats
 * the exact capability and corpus publication selected by {@code mirrorCorpus}; this prevents a
 * fixture from retargeting an independently validated cluster to another corpus generation.
 * Parsing is deliberately completed before any mutable repository or payload authority lookup.</p>
 *
 * @param configured whether the reserved metadata object was present
 * @param clusters canonically ordered exact cluster bindings
 */
public record FixtureMirrorClusterBindings(
        boolean configured,
        List<ClusterBinding> clusters
) {
    /** Reserved fixture metadata property. */
    public static final String METADATA_KEY = "mirrorClusters";
    /** Version of the nested cluster-binding contract. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.fixtureMirrorClusterBindings.v1";
    /** Maximum reviewed clusters selected by one fixture revision. */
    public static final int MAXIMUM_CLUSTERS = 1_000;

    private static final Set<String> ROOT_PROPERTIES =
            Set.of("schemaVersion", "clusters");
    private static final Set<String> BINDING_PROPERTIES =
            Set.of("capabilityRef", "corpusPublicationRef",
                    "clusterPublicationRef");
    private static final Set<String> REF_PROPERTIES =
            Set.of("kind", "id", "revision", "fingerprint");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Comparator<ClusterBinding> ORDER =
            Comparator.comparing(
                            (ClusterBinding value) -> value.capabilityRef().id())
                    .thenComparingLong(value -> value.capabilityRef().revision())
                    .thenComparing(value -> value.capabilityRef().fingerprint())
                    .thenComparing(value -> value.clusterPublicationRef().id())
                    .thenComparingLong(
                            value -> value.clusterPublicationRef().revision())
                    .thenComparing(
                            value -> value.clusterPublicationRef().fingerprint());

    /** Validates canonical ordering and exact cluster uniqueness. */
    public FixtureMirrorClusterBindings {
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        if (!configured && !clusters.isEmpty()) {
            throw invalid("cannot carry clusters when the reserved object is absent");
        }
        if (clusters.size() > MAXIMUM_CLUSTERS) {
            throw invalid("contains more than 1000 clusters");
        }
        if (!clusters.equals(clusters.stream().sorted(ORDER).toList())) {
            throw invalid("clusters must use canonical capability and cluster order");
        }
        Set<String> clusterRefs = new HashSet<>();
        for (ClusterBinding binding : clusters) {
            ClusterBinding exact = Objects.requireNonNull(binding, "cluster");
            if (!clusterRefs.add(coordinate(exact.clusterPublicationRef()))) {
                throw invalid("contains a duplicate or forked cluster coordinate");
            }
        }
    }

    /**
     * Parses and cross-checks one fixture's reserved cluster metadata.
     *
     * @param fixture exact immutable fixture revision
     * @param corpusBindings exact corpus publications selected by the same fixture
     * @return absent or fully validated cluster bindings
     */
    public static FixtureMirrorClusterBindings from(
            FixtureBundle fixture,
            FixtureMirrorCorpusBindings corpusBindings) {
        FixtureBundle exact = Objects.requireNonNull(fixture, "fixture");
        FixtureMirrorCorpusBindings corpora =
                Objects.requireNonNull(corpusBindings, "corpusBindings");
        if (!exact.metadata().containsKey(METADATA_KEY)) {
            return new FixtureMirrorClusterBindings(false, List.of());
        }
        Map<?, ?> root = map(
                exact.metadata().get(METADATA_KEY), "must be an object");
        if (!root.keySet().equals(ROOT_PROPERTIES)
                || !SCHEMA_VERSION.equals(root.get("schemaVersion"))) {
            throw invalid("must contain exactly schemaVersion and clusters");
        }
        if (!(root.get("clusters") instanceof List<?> raw)
                || raw.isEmpty() || raw.size() > MAXIMUM_CLUSTERS) {
            throw invalid("clusters must contain between 1 and 1000 bindings");
        }
        List<ClusterBinding> bindings = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Map<?, ?> item = map(
                    raw.get(index),
                    "cluster at index " + index + " must be an object");
            if (!item.keySet().equals(BINDING_PROPERTIES)) {
                throw invalid("cluster at index " + index
                        + " must contain exactly capabilityRef, corpusPublicationRef, "
                        + "and clusterPublicationRef");
            }
            bindings.add(new ClusterBinding(
                    ref(item.get("capabilityRef"), "CAPABILITY",
                            "capabilityRef at index " + index),
                    ref(item.get("corpusPublicationRef"),
                            CapabilityCorpusPublication.ARTIFACT_KIND,
                            "corpusPublicationRef at index " + index),
                    ref(item.get("clusterPublicationRef"),
                            CapabilityCorpusClusterPublication.ARTIFACT_KIND,
                            "clusterPublicationRef at index " + index)));
        }
        FixtureMirrorClusterBindings parsed =
                new FixtureMirrorClusterBindings(true, bindings);
        for (ClusterBinding binding : parsed.clusters()) {
            boolean selected = corpora.publications().stream().anyMatch(
                    publication -> publication.capabilityRef().equals(
                            binding.capabilityRef())
                            && publication.publicationRef().equals(
                            binding.corpusPublicationRef()));
            if (!selected) {
                throw invalid("must reference an exact mirrorCorpus publication");
            }
        }
        return parsed;
    }

    /**
     * One exact cluster selected under one exact capability and corpus publication.
     *
     * @param capabilityRef capability revision used by the graph closure
     * @param corpusPublicationRef exact current corpus publication containing all members
     * @param clusterPublicationRef exact reviewed cluster publication
     */
    public record ClusterBinding(
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef corpusPublicationRef,
            MirrorArtifactRef clusterPublicationRef
    ) {
        /** Enforces exact artifact kinds before mutable serving lookups. */
        public ClusterBinding {
            capabilityRef = kind(capabilityRef, "CAPABILITY", "capabilityRef");
            corpusPublicationRef = kind(
                    corpusPublicationRef,
                    CapabilityCorpusPublication.ARTIFACT_KIND,
                    "corpusPublicationRef");
            clusterPublicationRef = kind(
                    clusterPublicationRef,
                    CapabilityCorpusClusterPublication.ARTIFACT_KIND,
                    "clusterPublicationRef");
        }
    }

    private static MirrorArtifactRef ref(
            Object value, String expectedKind, String field) {
        Map<?, ?> raw = map(value, field + " must be an object");
        if (!raw.keySet().equals(REF_PROPERTIES)
                || !(raw.get("kind") instanceof String refKind)
                || !(raw.get("id") instanceof String id)
                || !(raw.get("revision") instanceof Number revision)
                || !(raw.get("fingerprint") instanceof String fingerprint)
                || !expectedKind.equals(refKind)
                || !IDENTIFIER.matcher(id).matches()
                || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw invalid(field + " must be an exact artifact reference");
        }
        try {
            return new MirrorArtifactRef(
                    refKind, id, exactRevision(revision, field), fingerprint);
        } catch (IllegalArgumentException malformed) {
            throw invalid(field + " must be an exact artifact reference");
        }
    }

    private static long exactRevision(Number revision, String field) {
        try {
            long value = new BigDecimal(revision.toString()).longValueExact();
            if (value < 1) {
                throw new ArithmeticException("non-positive");
            }
            return value;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw invalid(field + " revision must be a positive 64-bit integer");
        }
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value, String expected, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw invalid(field + " must reference " + expected);
        }
        return exact;
    }

    private static Map<?, ?> map(Object value, String reason) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid(reason);
        }
        return raw;
    }

    private static String coordinate(MirrorArtifactRef ref) {
        return ref.kind() + "\u0000" + ref.id() + "\u0000" + ref.revision();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(
                "fixtureBundle.metadata.mirrorClusters " + reason + ".");
    }
}
