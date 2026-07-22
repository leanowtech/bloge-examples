package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Append-only lifecycle state machine for immutable capability snapshot revisions.
 *
 * <p>A lifecycle decision never edits an existing snapshot. It creates the next sealed revision.
 * Behavior material may change only when the next revision is {@link CapabilitySnapshot.Lifecycle#DRAFT};
 * review, activation, deprecation, staleness, and revocation must preserve the reviewed capability.
 * A revoked capability id is terminal so accidental re-use cannot bypass an explicit kill decision.</p>
 */
public final class CapabilitySnapshotLifecycle {
    private static final Map<CapabilitySnapshot.Lifecycle, Set<CapabilitySnapshot.Lifecycle>> TRANSITIONS =
            transitions();

    private CapabilitySnapshotLifecycle() {
    }

    /**
     * Creates and seals the next lifecycle-only revision.
     *
     * @param mapper canonical fingerprint mapper
     * @param current currently stored sealed revision
     * @param target requested target lifecycle
     * @param nextRevision exact next revision number
     * @param approvedBy reviewer for REVIEWED/ACTIVE; ignored for other targets
     * @param approvedAt approval time for REVIEWED/ACTIVE
     * @param expiresAt approval expiry
     * @param revocationRef immutable revocation decision for REVOKED
     * @param createdAt new revision creation time
     * @return sealed next snapshot revision
     */
    public static CapabilitySnapshot transition(ObjectMapper mapper,
                                                CapabilitySnapshot current,
                                                CapabilitySnapshot.Lifecycle target,
                                                long nextRevision,
                                                String approvedBy,
                                                Instant approvedAt,
                                                Instant expiresAt,
                                                String revocationRef,
                                                Instant createdAt) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        CapabilitySnapshotIntegrity.verify(mapper, current);
        if (nextRevision != current.revision() + 1) {
            throw new IllegalArgumentException("next lifecycle revision must be current revision + 1");
        }
        if (!allowed(current.lifecycle(), target)) {
            throw new IllegalArgumentException("lifecycle transition is not allowed: "
                    + current.lifecycle() + " -> " + target);
        }
        Instant timestamp = Objects.requireNonNull(createdAt, "createdAt");
        if (!timestamp.isAfter(current.createdAt())) {
            throw new IllegalArgumentException("next snapshot createdAt must be after the current revision");
        }
        ArtifactProvenance provenance = switch (target) {
            case REVIEWED, ACTIVE -> current.provenance().withApproval(approvedBy, approvedAt, expiresAt);
            case REVOKED -> current.provenance().withRevocation(revocationRef);
            case DRAFT -> current.provenance().asDraft();
            default -> current.provenance();
        };
        CapabilitySnapshot.RuntimeBinding runtime = target == CapabilitySnapshot.Lifecycle.STALE
                || target == CapabilitySnapshot.Lifecycle.REVOKED
                ? CapabilitySnapshot.RuntimeBinding.unavailable("capability lifecycle is " + target)
                : current.runtime();
        CapabilitySnapshot next = new CapabilitySnapshot(current.schemaVersion(), current.capabilityId(),
                nextRevision, "", current.kind(), current.scope(), current.source(), current.contract(), runtime,
                current.dependencies(), current.ownership(), target, provenance, timestamp);
        validateAppend(current, next);
        return CapabilitySnapshotIntegrity.seal(mapper, next);
    }

    /**
     * Validates one append against the previous immutable revision.
     *
     * @param previous current latest snapshot
     * @param next candidate next snapshot
     */
    public static void validateAppend(CapabilitySnapshot previous, CapabilitySnapshot next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        if (!previous.scope().equals(next.scope())
                || !previous.capabilityId().equals(next.capabilityId())) {
            throw new IllegalArgumentException("snapshot append identity and scope must not change");
        }
        if (next.revision() != previous.revision() + 1) {
            throw new IllegalArgumentException("snapshot revisions must be contiguous");
        }
        if (!allowed(previous.lifecycle(), next.lifecycle())) {
            throw new IllegalArgumentException("lifecycle transition is not allowed: "
                    + previous.lifecycle() + " -> " + next.lifecycle());
        }
        if (next.lifecycle() != CapabilitySnapshot.Lifecycle.DRAFT) {
            requireSameGovernedMaterial(previous, next);
        }
        validateLifecycleState(next);
    }

    /** Validates rules that must hold even for the first stored DRAFT revision. */
    public static void validateLifecycleState(CapabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ArtifactProvenance provenance = snapshot.provenance();
        switch (snapshot.lifecycle()) {
            case DRAFT -> {
                if (!provenance.approvedBy().isBlank() || provenance.approvedAt() != null
                        || !provenance.revocationRef().isBlank()) {
                    throw new IllegalArgumentException("DRAFT snapshot must not carry approval or revocation");
                }
            }
            case REVIEWED, ACTIVE -> {
                if (provenance.approvedBy().isBlank() || provenance.approvedAt() == null) {
                    throw new IllegalArgumentException(snapshot.lifecycle()
                            + " snapshot requires approval coordinates");
                }
            }
            case REVOKED -> {
                if (provenance.revocationRef().isBlank()) {
                    throw new IllegalArgumentException("REVOKED snapshot requires revocationRef");
                }
                if (snapshot.runtime().ready()) {
                    throw new IllegalArgumentException("REVOKED snapshot runtime must be unavailable");
                }
            }
            case STALE -> {
                if (snapshot.runtime().ready()) {
                    throw new IllegalArgumentException("STALE snapshot runtime must be unavailable");
                }
            }
            case DEPRECATED -> {
                // Deprecated revisions remain executable until a separate stale/revoke decision.
            }
        }
        if (snapshot.lifecycle() == CapabilitySnapshot.Lifecycle.ACTIVE
                && (!snapshot.runtime().ready()
                || snapshot.contract().effect().mode() == EffectContract.Mode.UNKNOWN)) {
            throw new IllegalArgumentException("ACTIVE snapshot requires ready runtime and resolved effect");
        }
    }

    /** @return whether an append-only transition is part of the governed state machine */
    public static boolean allowed(CapabilitySnapshot.Lifecycle source,
                                  CapabilitySnapshot.Lifecycle target) {
        return source != null && target != null
                && TRANSITIONS.getOrDefault(source, Set.of()).contains(target);
    }

    private static void requireSameGovernedMaterial(CapabilitySnapshot previous,
                                                    CapabilitySnapshot next) {
        if (previous.kind() != next.kind()
                || !previous.source().equals(next.source())
                || !previous.contract().equals(next.contract())
                || !previous.dependencies().equals(next.dependencies())
                || !previous.ownership().equals(next.ownership())) {
            throw new IllegalArgumentException(
                    "non-DRAFT lifecycle revision must preserve governed capability material");
        }
        boolean deactivation = next.lifecycle() == CapabilitySnapshot.Lifecycle.STALE
                || next.lifecycle() == CapabilitySnapshot.Lifecycle.REVOKED;
        if (!deactivation && !previous.runtime().equals(next.runtime())) {
            throw new IllegalArgumentException(
                    "non-DRAFT lifecycle revision must preserve runtime binding");
        }
        if (deactivation && next.runtime().ready()) {
            throw new IllegalArgumentException("deactivated lifecycle runtime must be unavailable");
        }
        ArtifactProvenance left = previous.provenance();
        ArtifactProvenance right = next.provenance();
        boolean sameLineage = left.sourceType() == right.sourceType()
                && left.sourceRefs().equals(right.sourceRefs())
                && left.tenantId().equals(right.tenantId())
                && left.purpose().equals(right.purpose())
                && Objects.equals(left.sampleFrom(), right.sampleFrom())
                && Objects.equals(left.sampleTo(), right.sampleTo())
                && Objects.equals(left.sampleCount(), right.sampleCount())
                && Objects.equals(left.confidence(), right.confidence())
                && left.biasRisks().equals(right.biasRisks());
        if (!sameLineage) {
            throw new IllegalArgumentException(
                    "non-DRAFT lifecycle revision must preserve provenance lineage");
        }
    }

    private static Map<CapabilitySnapshot.Lifecycle, Set<CapabilitySnapshot.Lifecycle>> transitions() {
        EnumMap<CapabilitySnapshot.Lifecycle, Set<CapabilitySnapshot.Lifecycle>> values =
                new EnumMap<>(CapabilitySnapshot.Lifecycle.class);
        values.put(CapabilitySnapshot.Lifecycle.DRAFT, Set.of(
                CapabilitySnapshot.Lifecycle.DRAFT,
                CapabilitySnapshot.Lifecycle.REVIEWED,
                CapabilitySnapshot.Lifecycle.REVOKED));
        values.put(CapabilitySnapshot.Lifecycle.REVIEWED, Set.of(
                CapabilitySnapshot.Lifecycle.DRAFT,
                CapabilitySnapshot.Lifecycle.ACTIVE,
                CapabilitySnapshot.Lifecycle.STALE,
                CapabilitySnapshot.Lifecycle.REVOKED));
        values.put(CapabilitySnapshot.Lifecycle.ACTIVE, Set.of(
                CapabilitySnapshot.Lifecycle.DRAFT,
                CapabilitySnapshot.Lifecycle.DEPRECATED,
                CapabilitySnapshot.Lifecycle.STALE,
                CapabilitySnapshot.Lifecycle.REVOKED));
        values.put(CapabilitySnapshot.Lifecycle.DEPRECATED, Set.of(
                CapabilitySnapshot.Lifecycle.DRAFT,
                CapabilitySnapshot.Lifecycle.ACTIVE,
                CapabilitySnapshot.Lifecycle.STALE,
                CapabilitySnapshot.Lifecycle.REVOKED));
        values.put(CapabilitySnapshot.Lifecycle.STALE, Set.of(
                CapabilitySnapshot.Lifecycle.DRAFT,
                CapabilitySnapshot.Lifecycle.REVOKED));
        values.put(CapabilitySnapshot.Lifecycle.REVOKED, Set.of());
        return Map.copyOf(values);
    }
}
