package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict capability-to-publication bindings carried by reserved fixture metadata.
 *
 * <p>The wire location is {@code fixtureBundle.metadata.mirrorCorpus}. The containing fixture is
 * already immutable and content addressed, so these exact publication references become part of
 * the mirror-plan input without adding mutable request fields. This parser rejects unknown fields,
 * non-canonical ordering, duplicate capabilities, and non-exact references before any repository
 * or payload authority is consulted.</p>
 *
 * @param configured whether the reserved metadata object was present
 * @param publications canonically ordered exact capability-to-publication bindings
 */
public record FixtureMirrorCorpusBindings(
        boolean configured,
        List<PublicationBinding> publications
) {
    /** Reserved fixture metadata property. */
    public static final String METADATA_KEY = "mirrorCorpus";
    /** Version of the nested corpus-binding contract. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.fixtureMirrorCorpusBindings.v1";
    /** Maximum capability corpora bound by one fixture revision. */
    public static final int MAXIMUM_PUBLICATIONS = 1_000;

    private static final Set<String> ROOT_PROPERTIES =
            Set.of("schemaVersion", "publications");
    private static final Set<String> BINDING_PROPERTIES =
            Set.of("capabilityRef", "publicationRef");
    private static final Set<String> REF_PROPERTIES =
            Set.of("kind", "id", "revision", "fingerprint");
    private static final Comparator<PublicationBinding> ORDER =
            Comparator.comparing((PublicationBinding value) -> value.capabilityRef().id())
                    .thenComparingLong(value -> value.capabilityRef().revision())
                    .thenComparing(value -> value.capabilityRef().fingerprint());

    /** Validates canonical ordering and uniqueness independently of the wire parser. */
    public FixtureMirrorCorpusBindings {
        publications = publications == null ? List.of() : List.copyOf(publications);
        if (!configured && !publications.isEmpty()) {
            throw invalid("cannot carry publications when the reserved object is absent");
        }
        if (publications.size() > MAXIMUM_PUBLICATIONS) {
            throw invalid("contains more than 1000 publications");
        }
        if (!publications.equals(publications.stream().sorted(ORDER).toList())) {
            throw invalid("publications must use canonical capability order");
        }
        Set<String> capabilities = new HashSet<>();
        Set<String> publicationRefs = new HashSet<>();
        for (PublicationBinding binding : publications) {
            PublicationBinding exact = Objects.requireNonNull(binding, "publication");
            if (!capabilities.add(coordinate(exact.capabilityRef()))) {
                throw invalid("contains a duplicate or forked capability coordinate");
            }
            if (!publicationRefs.add(coordinate(exact.publicationRef()))) {
                throw invalid("contains a duplicate or forked publication coordinate");
            }
        }
    }

    /**
     * Parses one fixture's reserved mirror-corpus metadata.
     *
     * @param fixture exact immutable fixture revision
     * @return absent or fully validated corpus bindings
     */
    public static FixtureMirrorCorpusBindings from(FixtureBundle fixture) {
        FixtureBundle exact = Objects.requireNonNull(fixture, "fixture");
        Object value = exact.metadata().get(METADATA_KEY);
        if (value == null) {
            return new FixtureMirrorCorpusBindings(false, List.of());
        }
        Map<?, ?> root = map(value, "must be an object");
        if (!root.keySet().equals(ROOT_PROPERTIES)
                || !SCHEMA_VERSION.equals(root.get("schemaVersion"))) {
            throw invalid("must contain exactly schemaVersion and publications");
        }
        if (!(root.get("publications") instanceof List<?> raw)
                || raw.isEmpty() || raw.size() > MAXIMUM_PUBLICATIONS) {
            throw invalid("publications must contain between 1 and 1000 bindings");
        }
        List<PublicationBinding> bindings = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Map<?, ?> item = map(raw.get(index),
                    "publication at index " + index + " must be an object");
            if (!item.keySet().equals(BINDING_PROPERTIES)) {
                throw invalid("publication at index " + index
                        + " must contain exactly capabilityRef and publicationRef");
            }
            bindings.add(new PublicationBinding(
                    ref(item.get("capabilityRef"), "CAPABILITY",
                            "capabilityRef at index " + index),
                    ref(item.get("publicationRef"),
                            CapabilityCorpusPublication.ARTIFACT_KIND,
                            "publicationRef at index " + index)));
        }
        return new FixtureMirrorCorpusBindings(true, bindings);
    }

    /**
     * One exact serving publication selected for one exact capability revision.
     *
     * @param capabilityRef external capability revision used by the graph closure
     * @param publicationRef latest reviewed serving publication expected by the fixture
     */
    public record PublicationBinding(
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef publicationRef
    ) {
        /** Enforces exact artifact kinds before mutable lookups. */
        public PublicationBinding {
            capabilityRef = kind(capabilityRef, "CAPABILITY", "capabilityRef");
            publicationRef = kind(publicationRef,
                    CapabilityCorpusPublication.ARTIFACT_KIND, "publicationRef");
        }
    }

    private static MirrorArtifactRef ref(Object value, String expectedKind, String field) {
        Map<?, ?> raw = map(value, field + " must be an object");
        if (!raw.keySet().equals(REF_PROPERTIES)
                || !(raw.get("kind") instanceof String kind)
                || !(raw.get("id") instanceof String id)
                || !(raw.get("revision") instanceof Number revision)
                || !(raw.get("fingerprint") instanceof String fingerprint)) {
            throw invalid(field + " must be an exact artifact reference");
        }
        long exactRevision = revision.longValue();
        if (revision.doubleValue() != exactRevision) {
            throw invalid(field + " revision must be an integer");
        }
        try {
            return kind(new MirrorArtifactRef(kind, id, exactRevision, fingerprint),
                    expectedKind, field);
        } catch (IllegalArgumentException malformed) {
            throw invalid(field + " must be an exact artifact reference");
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
                "fixtureBundle.metadata.mirrorCorpus " + reason + ".");
    }
}
