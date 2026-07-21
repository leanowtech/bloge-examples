package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Process-local, already-authorized inventory of bootstrap-root recovery lanes.
 *
 * <p>The SPI deliberately returns fully composed service/resolver pairs rather than endpoints or
 * credentials. Every lane is rebound to the immutable public binding exposed by its ceremony
 * service, preventing an inventory label from routing one root-set through another service. The
 * inventory call must be a bounded in-memory snapshot; remote discovery, signature verification,
 * IAM authorization, and refresh happen before publication through this interface.</p>
 *
 * <p>A generation is immutable: callers must publish a strictly newer generation to add, remove,
 * or replace a lane. The fleet worker additionally rejects rollback and same-generation runtime
 * replacement. This interface is therefore an embedding boundary, not by itself a signed serving
 * inventory or cross-replica consensus protocol.</p>
 */
@FunctionalInterface
public interface ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory {

    /** Maximum root-set lanes accepted in one local inventory generation. */
    int MAXIMUM_LANES = 256;

    /**
     * Returns the current immutable local inventory without remote I/O.
     *
     * @return already-authorized generation and composed recovery lanes
     */
    Snapshot snapshot();

    /**
     * Immutable canonical fleet inventory generation.
     *
     * @param schemaVersion inventory protocol generation
     * @param generation strictly positive authority-controlled revision
     * @param lanes zero through 256 exact recovery lanes
     */
    record Snapshot(
            String schemaVersion,
            long generation,
            List<Lane> lanes) {

        /** Current process-local recovery fleet inventory schema. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventory.v1";

        /** Canonicalizes lane order and rejects duplicate scope/root-set identities. */
        public Snapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            if (!SCHEMA_VERSION.equals(schemaVersion) || generation < 1L) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory is invalid");
            }
            List<Lane> canonical = new ArrayList<>(List.copyOf(
                    Objects.requireNonNull(lanes, "lanes")));
            if (canonical.size() > MAXIMUM_LANES) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory is invalid");
            }
            canonical.sort(Comparator.comparing(Lane::key));
            Set<LaneKey> keys = new HashSet<>();
            if (canonical.stream().anyMatch(lane -> lane == null
                    || !keys.add(lane.key()))) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory is invalid");
            }
            lanes = List.copyOf(canonical);
        }

        /**
         * Returns canonical public descriptors without retaining runtime ports.
         *
         * @return stable descriptor list suitable for generation drift comparison
         */
        public List<LaneDescriptor> descriptors() {
            return lanes.stream().map(Lane::descriptor).toList();
        }
    }

    /**
     * One exactly bound recovery service and authority resolver.
     *
     * @param expectedBinding public root-set binding asserted by the inventory authority
     * @param runtimeBindingFingerprint {@code sha256:} fingerprint of the reviewed runtime closure
     * @param service durable ceremony service for the exact binding
     * @param authorityResolver resolver for the exact approved signer cohort
     */
    record Lane(
            ExpectedBinding expectedBinding,
            String runtimeBindingFingerprint,
            ExternalSequenceAnchorBootstrapRootCeremonyService service,
            ExternalSequenceAnchorBootstrapRootAuthorityResolver authorityResolver) {

        private static final Pattern SHA_256 = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects binding drift before the lane can enter a fleet generation. */
        public Lane {
            expectedBinding = Objects.requireNonNull(expectedBinding, "expectedBinding");
            runtimeBindingFingerprint = runtimeBindingFingerprint == null
                    ? "" : runtimeBindingFingerprint.trim();
            service = Objects.requireNonNull(service, "service");
            authorityResolver = Objects.requireNonNull(
                    authorityResolver, "authorityResolver");
            if (!SHA_256.matcher(runtimeBindingFingerprint).matches()
                    || !expectedBinding.equals(service.expectedBinding())) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery lane binding is invalid");
            }
        }

        /**
         * Returns the stable scope/root-set key used by the fair cursor.
         *
         * @return canonical lane key
         */
        public LaneKey key() {
            return new LaneKey(expectedBinding.scopeId(), expectedBinding.rootSetId());
        }

        /**
         * Returns public metadata used to detect same-generation inventory drift.
         *
         * @return immutable lane descriptor
         */
        public LaneDescriptor descriptor() {
            return new LaneDescriptor(expectedBinding, runtimeBindingFingerprint);
        }
    }

    /**
     * Stable fleet cursor key.
     *
     * @param scopeId exact fleet scope
     * @param rootSetId exact managed root-chain identity
     */
    record LaneKey(String scopeId, String rootSetId) implements Comparable<LaneKey> {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Reuses validated binding identifiers and rejects empty standalone keys. */
        public LaneKey {
            scopeId = scopeId == null ? "" : scopeId.trim();
            rootSetId = rootSetId == null ? "" : rootSetId.trim();
            if (!IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(rootSetId).matches()) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery lane key is invalid");
            }
        }

        /** Orders first by fleet scope and then by root-set identity. */
        @Override
        public int compareTo(LaneKey other) {
            Objects.requireNonNull(other, "other");
            int scopeOrder = scopeId.compareTo(other.scopeId);
            return scopeOrder != 0 ? scopeOrder : rootSetId.compareTo(other.rootSetId);
        }
    }

    /**
     * Public content identity for one composed lane.
     *
     * @param expectedBinding exact public ceremony policy and root-set binding
     * @param runtimeBindingFingerprint reviewed runtime implementation closure fingerprint
     */
    record LaneDescriptor(
            ExpectedBinding expectedBinding,
            String runtimeBindingFingerprint) {

        /** Enforces complete immutable descriptor material. */
        public LaneDescriptor {
            expectedBinding = Objects.requireNonNull(expectedBinding, "expectedBinding");
            runtimeBindingFingerprint = Objects.requireNonNull(
                    runtimeBindingFingerprint, "runtimeBindingFingerprint");
            if (!Pattern.matches("sha256:[a-f0-9]{64}", runtimeBindingFingerprint)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery lane descriptor is invalid");
            }
        }

        /**
         * Returns this descriptor's stable fleet key.
         *
         * @return exact scope/root-set key
         */
        public LaneKey key() {
            return new LaneKey(expectedBinding.scopeId(), expectedBinding.rootSetId());
        }
    }
}
