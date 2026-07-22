package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable cross-system projection of one Resource, Operator, or Graph capability revision.
 *
 * <p>The snapshot is deliberately not a replacement registry. Its {@link Source} points back to the
 * authoritative asset and its exact fingerprint. Mirror-plan compilation consumes only sealed
 * snapshots so Resource Gateway and ANEKE can upgrade independently without resolving mutable
 * registry state during execution.</p>
 *
 * @param schemaVersion snapshot protocol version
 * @param capabilityId stable capability identifier
 * @param revision positive immutable snapshot revision
 * @param fingerprint canonical fingerprint of the snapshot with this field blanked
 * @param kind external or composed capability kind
 * @param source authoritative source asset identity
 * @param contract complete capability contract
 * @param runtime current frozen runtime binding projection
 * @param dependencies exact transitive-edge inputs for composed capabilities
 * @param ownership owner and escalation metadata
 * @param lifecycle governed snapshot lifecycle
 * @param provenance trust and source lineage
 * @param createdAt snapshot creation time
 */
public record CapabilitySnapshot(
        String schemaVersion,
        String capabilityId,
        long revision,
        String fingerprint,
        Kind kind,
        Source source,
        CapabilityContract contract,
        RuntimeBinding runtime,
        List<Dependency> dependencies,
        Ownership ownership,
        Lifecycle lifecycle,
        ArtifactProvenance provenance,
        Instant createdAt
) {
    /** Current capability snapshot version. */
    public static final String SCHEMA_VERSION = "resourceGateway.capabilitySnapshot.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Capability execution kind. */
    public enum Kind {
        EXTERNAL,
        COMPOSED
    }

    /** Governed snapshot lifecycle. */
    public enum Lifecycle {
        DRAFT,
        REVIEWED,
        ACTIVE,
        DEPRECATED,
        STALE,
        REVOKED
    }

    /**
     * Normalizes collections and rejects snapshots that cannot represent an exact source revision.
     */
    public CapabilitySnapshot {
        schemaVersion = version(schemaVersion);
        capabilityId = required(capabilityId, "capabilityId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        fingerprint = normalized(fingerprint);
        if (!fingerprint.isBlank() && !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint must be blank or canonical SHA-256");
        }
        kind = kind == null ? Kind.EXTERNAL : kind;
        source = java.util.Objects.requireNonNull(source, "source");
        contract = java.util.Objects.requireNonNull(contract, "contract");
        runtime = runtime == null ? RuntimeBinding.unavailable("runtime binding is absent") : runtime;
        dependencies = dependencies == null ? List.of() : dependencies.stream()
                .sorted(Comparator.comparing(Dependency::nodeId)
                        .thenComparing(dependency -> dependency.capabilityRef().id())
                        .thenComparingLong(dependency -> dependency.capabilityRef().revision()))
                .toList();
        ownership = ownership == null ? Ownership.unassigned() : ownership;
        lifecycle = lifecycle == null ? Lifecycle.DRAFT : lifecycle;
        provenance = java.util.Objects.requireNonNull(provenance, "provenance");
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        if (kind == Kind.EXTERNAL && !dependencies.isEmpty()) {
            throw new IllegalArgumentException("EXTERNAL capability must not declare dependencies");
        }
        if (kind == Kind.COMPOSED && dependencies.isEmpty()) {
            throw new IllegalArgumentException("COMPOSED capability requires at least one dependency");
        }
    }

    /**
     * Returns an identical snapshot with a newly attached canonical fingerprint.
     *
     * @param value canonical fingerprint, or blank to create fingerprint material
     * @return copied snapshot
     */
    public CapabilitySnapshot withFingerprint(String value) {
        return new CapabilitySnapshot(schemaVersion, capabilityId, revision, value, kind, source,
                contract, runtime, dependencies, ownership, lifecycle, provenance, createdAt);
    }

    /**
     * Authoritative source asset of the projection.
     *
     * @param sourceKind resource, operator, or graph
     * @param sourceRef source-owned stable identifier
     * @param sourceFingerprint exact source content fingerprint
     */
    public record Source(SourceKind sourceKind, String sourceRef, String sourceFingerprint) {
        /** Validates exact source identity. */
        public Source {
            sourceKind = java.util.Objects.requireNonNull(sourceKind, "sourceKind");
            sourceRef = required(sourceRef, "sourceRef");
            sourceFingerprint = required(sourceFingerprint, "sourceFingerprint");
            if (!FINGERPRINT.matcher(sourceFingerprint).matches()) {
                throw new IllegalArgumentException("sourceFingerprint must be canonical SHA-256");
            }
        }
    }

    /** Source registry kind. */
    public enum SourceKind {
        RESOURCE,
        OPERATOR,
        GRAPH
    }

    /**
     * Frozen runtime binding used to determine whether projection implies execution readiness.
     *
     * @param kind runtime implementation kind
     * @param bindingRef stable binding reference
     * @param bindingFingerprint exact binding fingerprint
     * @param ready whether the binding was executable when captured
     * @param limitations explicit reasons why execution is unavailable or constrained
     */
    public record RuntimeBinding(
            String kind,
            String bindingRef,
            String bindingFingerprint,
            boolean ready,
            List<String> limitations
    ) {
        /** Normalizes binding fields without converting absence into readiness. */
        public RuntimeBinding {
            kind = required(kind, "runtime kind").toUpperCase(java.util.Locale.ROOT);
            bindingRef = normalized(bindingRef);
            bindingFingerprint = normalized(bindingFingerprint);
            limitations = normalizedList(limitations);
            if (ready && (bindingRef.isBlank() || !FINGERPRINT.matcher(bindingFingerprint).matches())) {
                throw new IllegalArgumentException("ready runtime requires bindingRef and fingerprint");
            }
            if (!ready && limitations.isEmpty()) {
                throw new IllegalArgumentException("unavailable runtime requires a limitation");
            }
        }

        /** @return unavailable runtime binding with an explicit reason */
        public static RuntimeBinding unavailable(String reason) {
            return new RuntimeBinding("UNRESOLVED", "", "", false, List.of(required(reason, "reason")));
        }
    }

    /**
     * One exact child capability used by a composed capability.
     *
     * @param nodeId stable node/invocation identifier in the parent
     * @param capabilityRef exact child snapshot
     * @param required whether the dependency is reachable on every successful path
     * @param conditions stable route identifiers under which the dependency is reachable
     */
    public record Dependency(
            String nodeId,
            MirrorArtifactRef capabilityRef,
            boolean required,
            List<String> conditions
    ) {
        /** Validates a dependency coordinate. */
        public Dependency {
            nodeId = CapabilitySnapshot.required(nodeId, "nodeId");
            capabilityRef = java.util.Objects.requireNonNull(capabilityRef, "capabilityRef");
            if (!"CAPABILITY".equals(capabilityRef.kind())) {
                throw new IllegalArgumentException("dependency must reference a CAPABILITY artifact");
            }
            conditions = normalizedList(conditions);
        }
    }

    /**
     * Human and organizational ownership of a capability.
     *
     * @param owner primary accountable owner
     * @param team owning team
     * @param escalation escalation route for correctness or runtime incidents
     */
    public record Ownership(String owner, String team, String escalation) {
        /** Normalizes ownership fields. */
        public Ownership {
            owner = normalized(owner);
            team = normalized(team);
            escalation = normalized(escalation);
        }

        /** @return explicit unassigned ownership */
        public static Ownership unassigned() {
            return new Ownership("", "", "");
        }
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + normalized);
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalizedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(CapabilitySnapshot::normalized).filter(value -> !value.isEmpty())
                .distinct().sorted().toList();
    }
}
