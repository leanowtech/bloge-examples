package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cross-replica partition lease and cursor protocol for bootstrap-root recovery scanning.
 *
 * <p>The coordinator owns scheduling facts only: a durable inventory generation, fixed partition
 * topology, cyclic partition assignment, and one exclusive database-clock lease per partition.
 * It is not an execution lock. Each recovery lane's ceremony journal remains the authority for
 * attempt acquisition and write fencing, so an expired outer lease can cause at-least-once polling
 * but cannot authorize duplicate recovery writes.</p>
 *
 * <p>A newer inventory generation fences every older assignment before it can renew or advance a
 * cursor. Partition count is immutable for a fleet identity because changing it silently remaps
 * every lane; operators must use a new fleet identifier for a topology migration. Implementations
 * must compare the complete lease revision, including expiry, on renewal and completion so a stale
 * in-process copy cannot overwrite a newer heartbeat.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator {

    /** Maximum fixed scheduling partitions accepted for one fleet. */
    int MAXIMUM_PARTITIONS = 64;

    /**
     * Acquires the next cyclically eligible partition using the coordinator's authoritative clock.
     *
     * @param command exact inventory identity, worker identity, and bounded lease duration
     * @return acquired lease or a payload-free busy result when every partition is actively leased
     */
    Acquisition acquire(AcquisitionCommand command);

    /**
     * Renews an exact live lease revision without changing its fencing epoch or scan cursor.
     *
     * @param lease latest lease revision returned by this coordinator
     * @return renewed revision, or empty when expiry, takeover, or inventory advance fenced it
     */
    Optional<Lease> renew(Lease lease);

    /**
     * Releases an exact live lease and durably advances its partition cursor.
     *
     * @param lease latest lease revision returned by this coordinator
     * @param lastAttempted last lane actually attempted, or {@code null} for an empty partition
     * @return completion status; fenced completion never mutates the cursor
     */
    CompletionStatus complete(Lease lease, LaneKey lastAttempted);

    /**
     * Releases an exact live lease without changing its durable partition cursor.
     *
     * <p>Workers call this on fatal cycle failure so another replica can resume immediately. A
     * stale revision is fenced and cannot release a newer owner's assignment.</p>
     *
     * @param lease latest lease revision returned by this coordinator
     * @return abandonment status; fenced abandonment never mutates the assignment or cursor
     */
    AbandonStatus abandon(Lease lease);

    /**
     * Reports whether assignment and cursors survive process loss and are shared across replicas.
     *
     * @return {@code true} only for a durable cross-replica implementation
     */
    boolean durable();

    /**
     * Computes the canonical public-content fingerprint of one inventory snapshot.
     *
     * <p>The digest excludes generation and runtime object identity. Generation is carried
     * separately, while same-generation process-local service/resolver replacement remains a fleet
     * worker invariant. Every scalar is length framed before hashing, preventing delimiter
     * ambiguity.</p>
     *
     * @param snapshot validated canonical inventory snapshot
     * @return lowercase {@code sha256:} inventory descriptor fingerprint
     */
    static String inventoryFingerprint(Snapshot snapshot) {
        Snapshot safe = Objects.requireNonNull(snapshot, "snapshot");
        MessageDigest digest = sha256();
        update(digest, Snapshot.SCHEMA_VERSION);
        update(digest, safe.descriptors().size());
        for (LaneDescriptor descriptor : safe.descriptors()) {
            ExpectedBinding binding = descriptor.expectedBinding();
            update(digest, binding.scopeId());
            update(digest, binding.rootSetId());
            update(digest, binding.trustDomain());
            update(digest, binding.signatureThreshold());
            update(digest, binding.maximumFaults());
            update(digest, binding.maximumRootLifetime().getSeconds());
            update(digest, binding.maximumRootLifetime().getNano());
            update(digest, binding.clockSkew().getSeconds());
            update(digest, binding.clockSkew().getNano());
            update(digest, binding.minimumRemainingValidity().getSeconds());
            update(digest, binding.minimumRemainingValidity().getNano());
            update(digest, binding.maximumTransitionCount());
            update(digest, descriptor.runtimeBindingFingerprint());
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Maps a lane key to a stable fixed partition using a length-framed SHA-256 digest.
     *
     * @param key canonical scope/root-set lane identity
     * @param partitionCount fixed fleet partition count from 1 through 64
     * @return zero-based partition identifier
     */
    static int partitionFor(LaneKey key, int partitionCount) {
        LaneKey safe = Objects.requireNonNull(key, "key");
        if (partitionCount < 1 || partitionCount > MAXIMUM_PARTITIONS) {
            throw new IllegalArgumentException("Recovery fleet partition count is invalid");
        }
        MessageDigest digest = sha256();
        update(digest, safe.scopeId());
        update(digest, safe.rootSetId());
        long prefix = ByteBuffer.wrap(digest.digest()).getLong();
        return (int) Math.floorMod(prefix, partitionCount);
    }

    /** Result of one partition acquisition attempt. */
    enum AcquisitionStatus {
        /** One exact partition lease was acquired. */
        ACQUIRED,
        /** Every partition has an unexpired lease. */
        BUSY
    }

    /** Result of an exact lease completion attempt. */
    enum CompletionStatus {
        /** Cursor and release were committed atomically. */
        COMPLETED,
        /** Lease expiry, takeover, renewal, or generation advance rejected the stale revision. */
        FENCED
    }

    /** Result of an exact lease abandonment attempt. */
    enum AbandonStatus {
        /** The assignment was released while preserving its committed cursor. */
        ABANDONED,
        /** Lease expiry, takeover, renewal, or generation advance rejected the stale revision. */
        FENCED
    }

    /**
     * Immutable fixed-partition inventory identity.
     *
     * @param schemaVersion manifest protocol generation
     * @param fleetId stable deployment-wide scheduler identity
     * @param inventoryGeneration strictly positive inventory authority revision
     * @param inventoryFingerprint canonical public descriptor fingerprint
     * @param partitionCount immutable partition topology for this fleet identifier
     */
    record FleetManifest(
            String schemaVersion,
            String fleetId,
            long inventoryGeneration,
            String inventoryFingerprint,
            int partitionCount) {

        /** Current durable fleet manifest schema. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetManifest.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces canonical identity, fingerprint, generation, and topology bounds. */
        public FleetManifest {
            schemaVersion = normalized(schemaVersion);
            fleetId = normalized(fleetId);
            inventoryFingerprint = normalized(inventoryFingerprint);
            if (!SCHEMA_VERSION.equals(schemaVersion) || !IDENTIFIER.matcher(fleetId).matches()
                    || inventoryGeneration < 1L
                    || !FINGERPRINT.matcher(inventoryFingerprint).matches()
                    || partitionCount < 1 || partitionCount > MAXIMUM_PARTITIONS) {
                throw new IllegalArgumentException("Recovery fleet manifest is invalid");
            }
        }

        /**
         * Builds a manifest from a validated canonical local inventory.
         *
         * @param fleetId stable deployment-wide scheduler identity
         * @param snapshot exact inventory generation to schedule
         * @param partitionCount fixed fleet partition count
         * @return complete immutable manifest
         */
        public static FleetManifest from(
                String fleetId, Snapshot snapshot, int partitionCount) {
            Snapshot safe = Objects.requireNonNull(snapshot, "snapshot");
            return new FleetManifest(SCHEMA_VERSION, fleetId, safe.generation(),
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                            .inventoryFingerprint(safe), partitionCount);
        }
    }

    /**
     * One bounded acquisition request.
     *
     * @param schemaVersion command protocol generation
     * @param manifest exact local inventory identity
     * @param workerId stable authenticated replica worker identity
     * @param commandId unique retry-deduplication key while this acquisition remains active
     * @param leaseDurationSeconds database-clock lease duration from 3 through 300 seconds
     */
    record AcquisitionCommand(
            String schemaVersion,
            FleetManifest manifest,
            String workerId,
            String commandId,
            long leaseDurationSeconds) {

        /** Current fleet acquisition command schema. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetAcquire.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern COMMAND_ID = Pattern.compile("[a-f0-9]{32}");

        /** Enforces manifest, owner, active retry key, and lease-duration bounds. */
        public AcquisitionCommand {
            schemaVersion = normalized(schemaVersion);
            manifest = Objects.requireNonNull(manifest, "manifest");
            workerId = normalized(workerId);
            commandId = normalized(commandId);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(workerId).matches()
                    || !COMMAND_ID.matcher(commandId).matches()
                    || leaseDurationSeconds < 3L || leaseDurationSeconds > 300L) {
                throw new IllegalArgumentException(
                        "Recovery fleet acquisition command is invalid");
            }
        }
    }

    /**
     * Payload-free acquisition result.
     *
     * @param schemaVersion acquisition result protocol generation
     * @param status acquired or all-partitions-busy status
     * @param lease exact lease only when acquired
     */
    record Acquisition(String schemaVersion, AcquisitionStatus status, Lease lease) {

        /** Current fleet acquisition result schema. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetAcquisition.v1";

        /** Enforces the exact status-dependent lease shape. */
        public Acquisition {
            schemaVersion = normalized(schemaVersion);
            status = Objects.requireNonNull(status, "status");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || (status == AcquisitionStatus.ACQUIRED) != (lease != null)) {
                throw new IllegalArgumentException("Recovery fleet acquisition is invalid");
            }
        }

        /**
         * Creates an acquired result.
         *
         * @param lease exact acquired lease
         * @return acquired result
         */
        public static Acquisition acquired(Lease lease) {
            return new Acquisition(SCHEMA_VERSION, AcquisitionStatus.ACQUIRED,
                    Objects.requireNonNull(lease, "lease"));
        }

        /**
         * Creates an all-partitions-busy result.
         *
         * @return payload-free busy result
         */
        public static Acquisition busy() {
            return new Acquisition(SCHEMA_VERSION, AcquisitionStatus.BUSY, null);
        }
    }

    /**
     * Exact revision of one cross-replica partition lease.
     *
     * @param schemaVersion lease protocol generation
     * @param manifest inventory identity accepted at acquisition
     * @param partitionId zero-based fixed partition identifier
     * @param fleetEpoch generation fencing epoch
     * @param leaseEpoch monotonically increasing partition takeover epoch
     * @param leaseToken unguessable exact acquisition token
     * @param workerId authenticated owner
     * @param commandId active acquisition retry key bound to this assignment
     * @param leaseDurationSeconds duration used for each authoritative renewal
     * @param leaseExpiresAt authoritative database-clock expiry of this exact revision
     * @param cursorExclusive last durably attempted lane in this partition, when present
     */
    record Lease(
            String schemaVersion,
            FleetManifest manifest,
            int partitionId,
            long fleetEpoch,
            long leaseEpoch,
            String leaseToken,
            String workerId,
            String commandId,
            long leaseDurationSeconds,
            Instant leaseExpiresAt,
            LaneKey cursorExclusive) {

        /** Current exact partition lease schema. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetLease.v1";

        private static final Pattern TOKEN = Pattern.compile("[a-f0-9]{32}");
        private static final Pattern COMMAND_ID = Pattern.compile("[a-f0-9]{32}");
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces identity, epoch, owner, expiry, and cursor-partition coherence. */
        public Lease {
            schemaVersion = normalized(schemaVersion);
            manifest = Objects.requireNonNull(manifest, "manifest");
            leaseToken = normalized(leaseToken);
            workerId = normalized(workerId);
            commandId = normalized(commandId);
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            if (!SCHEMA_VERSION.equals(schemaVersion) || partitionId < 0
                    || partitionId >= manifest.partitionCount() || fleetEpoch < 1L
                    || leaseEpoch < 1L || !TOKEN.matcher(leaseToken).matches()
                    || !IDENTIFIER.matcher(workerId).matches()
                    || !COMMAND_ID.matcher(commandId).matches()
                    || leaseDurationSeconds < 3L || leaseDurationSeconds > 300L
                    || cursorExclusive != null
                    && partitionFor(cursorExclusive, manifest.partitionCount()) != partitionId) {
                throw new IllegalArgumentException("Recovery fleet lease is invalid");
            }
        }

        /**
         * Returns whether a lane belongs to this lease's fixed partition.
         *
         * @param key lane to test
         * @return whether the stable digest maps the lane to this partition
         */
        public boolean owns(LaneKey key) {
            return partitionFor(key, manifest.partitionCount()) == partitionId;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void update(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
