package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Durable store for Agent TDD overlays and exact idempotency responses.
 */
public interface AgentTddStateRepository {

    /**
     * Immutable point-in-time view of selected asset kinds inside one exact scope.
     *
     * <p>The snapshot prevents a read model from combining an older journey with newer evidence
     * or contracts. Returned assets and JSON payloads are defensive copies.</p>
     *
     * @param scopeKey server-derived scope shared by every asset
     * @param assets assets visible at the same repository read point
     */
    record AssetReadSnapshot(String scopeKey, List<AgentTddStoredAsset> assets) {
        /** Validates one-scope membership and freezes the supplied assets in stable order. */
        public AssetReadSnapshot {
            String normalizedScope = scopeKey == null ? "" : scopeKey.trim();
            if (normalizedScope.isBlank()) throw new IllegalArgumentException("snapshot scope is required");
            scopeKey = normalizedScope;
            assets = (assets == null ? List.<AgentTddStoredAsset>of() : assets).stream()
                    .map(Objects::requireNonNull)
                    .peek(asset -> {
                        if (!normalizedScope.equals(asset.scopeKey())) {
                            throw new IllegalArgumentException("snapshot assets must share one scope");
                        }
                    })
                    .sorted(java.util.Comparator.comparing(AgentTddStoredAsset::kind)
                            .thenComparing(AgentTddStoredAsset::assetRef))
                    .map(AssetReadSnapshot::copy)
                    .toList();
        }

        /** Finds one exact asset without consulting mutable repository state. */
        public Optional<AgentTddStoredAsset> find(String kind, String assetRef) {
            return assets.stream()
                    .filter(asset -> asset.kind().equals(kind) && asset.assetRef().equals(assetRef))
                    .findFirst()
                    .map(AssetReadSnapshot::copy);
        }

        /** Lists one asset kind in stable reference order without consulting mutable state. */
        public List<AgentTddStoredAsset> list(String kind) {
            return assets.stream()
                    .filter(asset -> asset.kind().equals(kind))
                    .map(AssetReadSnapshot::copy)
                    .toList();
        }

        @Override
        public List<AgentTddStoredAsset> assets() {
            return assets.stream().map(AssetReadSnapshot::copy).toList();
        }

        private static AgentTddStoredAsset copy(AgentTddStoredAsset asset) {
            return new AgentTddStoredAsset(asset.scopeKey(), asset.kind(), asset.assetRef(),
                    asset.revision(), asset.fingerprint(), asset.data(), asset.updatedAt());
        }
    }

    /** Durable lifecycle of a non-transactional external execution reservation. */
    enum ExternalExecutionStatus {
        /** This caller durably acquired the right to perform the external execution. */
        ACQUIRED,
        /** Another caller or a crashed process owns an unfinished durable reservation. */
        IN_PROGRESS,
        /** The execution already completed and its exact response is available for replay. */
        COMPLETED
    }

    /**
     * Result of reserving an external execution whose network effects cannot join a database
     * transaction.
     *
     * @param status durable reservation status
     * @param response exact completed response; present only for {@link ExternalExecutionStatus#COMPLETED}
     */
    record ExternalExecutionReservation(ExternalExecutionStatus status, JsonNode response) {
        public ExternalExecutionReservation {
            if (status == null) throw new IllegalArgumentException("status is required");
            response = response == null ? null : response.deepCopy();
            if (status == ExternalExecutionStatus.COMPLETED && response == null) {
                throw new IllegalArgumentException("completed reservation requires a response");
            }
            if (status != ExternalExecutionStatus.COMPLETED && response != null) {
                throw new IllegalArgumentException("unfinished reservation cannot carry a response");
            }
        }

        @Override
        public JsonNode response() {
            return response == null ? null : response.deepCopy();
        }
    }

    /** Finds the current overlay for one exact server-derived scope. */
    Optional<AgentTddStoredAsset> find(String scopeKey, String kind, String assetRef);

    /** Lists current overlays of one kind inside one exact scope. */
    List<AgentTddStoredAsset> list(String scopeKey, String kind);

    /**
     * Reads every requested kind from one consistent repository snapshot.
     *
     * <p>The default is safe only when {@link #executeAtomically(Supplier)} also provides stable
     * reads. Durable implementations with statement-level read consistency must override this
     * method with one physical query.</p>
     */
    default AssetReadSnapshot readSnapshot(String scopeKey, List<String> kinds) {
        List<String> selectedKinds = kinds == null ? List.of() : kinds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(kind -> !kind.isBlank())
                .distinct()
                .sorted()
                .toList();
        return executeAtomically(() -> new AssetReadSnapshot(scopeKey, selectedKinds.stream()
                .flatMap(kind -> list(scopeKey, kind).stream())
                .toList()));
    }

    /** Stores the next overlay revision and returns its server-owned envelope. */
    AgentTddStoredAsset save(String scopeKey, String kind, String assetRef, JsonNode data);

    /**
     * Stores the next overlay revision only when the durable current revision exactly matches.
     *
     * <p>An expected revision of {@code 0} means that the asset must not exist. Implementations
     * must make the comparison and write atomically; this is the human-review revision fence.</p>
     *
     * @throws AgentTddToolException when the asset changed after it was reviewed
     */
    AgentTddStoredAsset saveIfRevision(String scopeKey,
                                       String kind,
                                       String assetRef,
                                       long expectedRevision,
                                       JsonNode data);

    /**
     * Executes a group of related state mutations as one atomic unit.
     *
     * <p>Durable implementations must roll back every mutation when the action fails. This fence is
     * used when one case-set CAS and its evidence/verdict projections must commit together.</p>
     */
    default <T> T executeAtomically(Supplier<T> action) {
        return action.get();
    }

    /**
     * Reads and locks one exact asset revision until the surrounding atomic unit completes.
     *
     * <p>The lock is required even when the caller has no state change to make: a failed or already
     * READY execution still must not persist evidence for a case set edited concurrently.</p>
     *
     * @throws AgentTddToolException when the asset is absent or its revision differs
     */
    default AgentTddStoredAsset lockRevision(String scopeKey,
                                             String kind,
                                             String assetRef,
                                             long expectedRevision) {
        return find(scopeKey, kind, assetRef)
                .filter(asset -> asset.revision() == expectedRevision)
                .orElseThrow(() -> new AgentTddToolException(
                        "GATE_REJECTED", "Asset changed after the executed revision."));
    }

    /**
     * Replays the exact response for a matching idempotency request.
     *
     * @throws AgentTddToolException when the key was already used for different request material
     */
    Optional<JsonNode> replay(String scopeKey,
                              String operation,
                              String idempotencyKey,
                              String requestFingerprint);

    /** Records the exact successful response for subsequent idempotent replay. */
    void record(String scopeKey,
                String operation,
                String idempotencyKey,
                String requestFingerprint,
                JsonNode response);

    /**
     * Executes one state-changing action and records its exact response as one atomic unit.
     *
     * <p>Concurrent callers using the same key and request fingerprint receive the committed
     * response; different request material fails closed. Implementations must not expose a
     * successful business write without its replay record.</p>
     */
    JsonNode executeOnce(String scopeKey,
                         String operation,
                         String idempotencyKey,
                         String requestFingerprint,
                         Supplier<JsonNode> action);

    /**
     * Commits a durable reservation before a caller performs a real external execution.
     *
     * <p>The reservation must commit in a transaction independent from the later network call.
     * An unfinished reservation survives process loss and fails closed as {@code IN_PROGRESS}; it
     * must never be silently reclaimed because the external effect may already have occurred.</p>
     */
    ExternalExecutionReservation reserveExternalExecution(String scopeKey,
                                                          String operation,
                                                          String idempotencyKey,
                                                          String requestFingerprint);

    /**
     * Completes a previously acquired external execution reservation in an independent transaction.
     *
     * @return an immutable copy of the exact response recorded for subsequent replay
     */
    JsonNode completeExternalExecution(String scopeKey,
                                       String operation,
                                       String idempotencyKey,
                                       String requestFingerprint,
                                       JsonNode response);
}
