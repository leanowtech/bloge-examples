package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict fixture bindings from exact corpus publications to reviewed retry trajectories.
 *
 * <p>The wire location is {@code fixtureBundle.metadata.mirrorTrajectories}. Every trajectory
 * must also name the exact capability and corpus publication already selected by
 * {@code mirrorCorpus}; this prevents a trajectory from silently retargeting another corpus head.
 * Multiple trajectories may cover one capability, but online serving rejects more than one
 * trajectory for the same canonical request fingerprint.</p>
 *
 * @param configured whether the reserved metadata object was present
 * @param trajectories canonically ordered exact trajectory bindings
 */
public record FixtureMirrorTrajectoryBindings(
        boolean configured,
        List<TrajectoryBinding> trajectories
) {
    /** Reserved fixture metadata property. */
    public static final String METADATA_KEY = "mirrorTrajectories";
    /** Version of the nested trajectory-binding contract. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.fixtureMirrorTrajectoryBindings.v1";
    /** Maximum reviewed trajectory publications bound by one fixture revision. */
    public static final int MAXIMUM_TRAJECTORIES = 1_000;

    private static final Set<String> ROOT_PROPERTIES =
            Set.of("schemaVersion", "trajectories");
    private static final Set<String> BINDING_PROPERTIES =
            Set.of("capabilityRef", "corpusPublicationRef",
                    "trajectoryPublicationRef");
    private static final Set<String> REF_PROPERTIES =
            Set.of("kind", "id", "revision", "fingerprint");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Comparator<TrajectoryBinding> ORDER =
            Comparator.comparing(
                            (TrajectoryBinding value) -> value.capabilityRef().id())
                    .thenComparingLong(value -> value.capabilityRef().revision())
                    .thenComparing(value -> value.capabilityRef().fingerprint())
                    .thenComparing(value -> value.trajectoryPublicationRef().id())
                    .thenComparingLong(
                            value -> value.trajectoryPublicationRef().revision())
                    .thenComparing(
                            value -> value.trajectoryPublicationRef().fingerprint());

    /** Validates canonical ordering and exact trajectory uniqueness. */
    public FixtureMirrorTrajectoryBindings {
        trajectories = trajectories == null ? List.of() : List.copyOf(trajectories);
        if (!configured && !trajectories.isEmpty()) {
            throw invalid("cannot carry trajectories when the reserved object is absent");
        }
        if (trajectories.size() > MAXIMUM_TRAJECTORIES) {
            throw invalid("contains more than 1000 trajectories");
        }
        if (!trajectories.equals(trajectories.stream().sorted(ORDER).toList())) {
            throw invalid("trajectories must use canonical capability and trajectory order");
        }
        Set<String> trajectoryRefs = new HashSet<>();
        for (TrajectoryBinding binding : trajectories) {
            TrajectoryBinding exact = Objects.requireNonNull(binding, "trajectory");
            if (!trajectoryRefs.add(coordinate(exact.trajectoryPublicationRef()))) {
                throw invalid("contains a duplicate or forked trajectory coordinate");
            }
        }
    }

    /**
     * Parses and cross-checks one fixture's reserved trajectory metadata.
     *
     * @param fixture exact immutable fixture revision
     * @param corpusBindings exact corpus publications selected by the same fixture
     * @return absent or fully validated trajectory bindings
     */
    public static FixtureMirrorTrajectoryBindings from(
            FixtureBundle fixture,
            FixtureMirrorCorpusBindings corpusBindings) {
        FixtureBundle exact = Objects.requireNonNull(fixture, "fixture");
        FixtureMirrorCorpusBindings corpora =
                Objects.requireNonNull(corpusBindings, "corpusBindings");
        if (!exact.metadata().containsKey(METADATA_KEY)) {
            return new FixtureMirrorTrajectoryBindings(false, List.of());
        }
        Object value = exact.metadata().get(METADATA_KEY);
        Map<?, ?> root = map(value, "must be an object");
        if (!root.keySet().equals(ROOT_PROPERTIES)
                || !SCHEMA_VERSION.equals(root.get("schemaVersion"))) {
            throw invalid("must contain exactly schemaVersion and trajectories");
        }
        if (!(root.get("trajectories") instanceof List<?> raw)
                || raw.isEmpty() || raw.size() > MAXIMUM_TRAJECTORIES) {
            throw invalid("trajectories must contain between 1 and 1000 bindings");
        }
        List<TrajectoryBinding> bindings = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Map<?, ?> item = map(
                    raw.get(index),
                    "trajectory at index " + index + " must be an object");
            if (!item.keySet().equals(BINDING_PROPERTIES)) {
                throw invalid("trajectory at index " + index
                        + " must contain exactly capabilityRef, corpusPublicationRef, "
                        + "and trajectoryPublicationRef");
            }
            bindings.add(new TrajectoryBinding(
                    ref(item.get("capabilityRef"), "CAPABILITY",
                            "capabilityRef at index " + index),
                    ref(item.get("corpusPublicationRef"),
                            CapabilityCorpusPublication.ARTIFACT_KIND,
                            "corpusPublicationRef at index " + index),
                    ref(item.get("trajectoryPublicationRef"),
                            CapabilityCorpusTrajectoryPublication.ARTIFACT_KIND,
                            "trajectoryPublicationRef at index " + index)));
        }
        FixtureMirrorTrajectoryBindings parsed =
                new FixtureMirrorTrajectoryBindings(true, bindings);
        for (TrajectoryBinding binding : parsed.trajectories()) {
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
     * One exact trajectory selected under one exact capability and corpus publication.
     *
     * @param capabilityRef capability revision used by the graph closure
     * @param corpusPublicationRef exact current corpus publication containing all attempts
     * @param trajectoryPublicationRef exact reviewed trajectory publication
     */
    public record TrajectoryBinding(
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef corpusPublicationRef,
            MirrorArtifactRef trajectoryPublicationRef
    ) {
        /** Enforces exact artifact kinds before mutable serving lookups. */
        public TrajectoryBinding {
            capabilityRef = kind(capabilityRef, "CAPABILITY", "capabilityRef");
            corpusPublicationRef = kind(
                    corpusPublicationRef,
                    CapabilityCorpusPublication.ARTIFACT_KIND,
                    "corpusPublicationRef");
            trajectoryPublicationRef = kind(
                    trajectoryPublicationRef,
                    CapabilityCorpusTrajectoryPublication.ARTIFACT_KIND,
                    "trajectoryPublicationRef");
        }
    }

    private static MirrorArtifactRef ref(
            Object value, String expectedKind, String field) {
        Map<?, ?> raw = map(value, field + " must be an object");
        if (!raw.keySet().equals(REF_PROPERTIES)
                || !(raw.get("kind") instanceof String refKind)
                || !(raw.get("id") instanceof String id)
                || !(raw.get("revision") instanceof Number revision)
                || !(raw.get("fingerprint") instanceof String fingerprint)) {
            throw invalid(field + " must be an exact artifact reference");
        }
        if (!expectedKind.equals(refKind)
                || !IDENTIFIER.matcher(id).matches()
                || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw invalid(field + " must be an exact artifact reference");
        }
        long exactRevision = exactRevision(revision, field);
        try {
            return new MirrorArtifactRef(
                    refKind, id, exactRevision, fingerprint);
        } catch (IllegalArgumentException malformed) {
            throw invalid(field + " must be an exact artifact reference");
        }
    }

    private static long exactRevision(Number revision, String field) {
        try {
            java.math.BigDecimal exact =
                    new java.math.BigDecimal(revision.toString());
            long value = exact.longValueExact();
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
                "fixtureBundle.metadata.mirrorTrajectories " + reason + ".");
    }
}
