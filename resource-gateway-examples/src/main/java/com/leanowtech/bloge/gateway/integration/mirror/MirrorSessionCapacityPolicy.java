package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Hierarchical hard limits for the encrypted stateful-mirror data plane.
 *
 * <p>Global limits protect the shared database while scope limits prevent one authenticated
 * enterprise namespace from consuming the entire deployment. The database store evaluates all
 * four limits under one cross-replica capacity fence.</p>
 *
 * @param maximumActiveSessions deployment-wide live-session limit
 * @param maximumScopeActiveSessions live-session limit for one complete enterprise scope
 * @param maximumRetainedPayloadBytes deployment-wide retained canonical-payload-byte limit
 * @param maximumScopeRetainedPayloadBytes retained canonical-byte limit for one enterprise scope
 */
public record MirrorSessionCapacityPolicy(
        long maximumActiveSessions,
        long maximumScopeActiveSessions,
        long maximumRetainedPayloadBytes,
        long maximumScopeRetainedPayloadBytes
) {
    private static final long MAXIMUM_SESSION_LIMIT = 1_000_000;
    private static final long MAXIMUM_BYTE_LIMIT = 1L << 40;

    /** Validates positive, bounded, and correctly nested capacity limits. */
    public MirrorSessionCapacityPolicy {
        if (maximumActiveSessions < 1
                || maximumActiveSessions > MAXIMUM_SESSION_LIMIT
                || maximumScopeActiveSessions < 1
                || maximumScopeActiveSessions > maximumActiveSessions) {
            throw new IllegalArgumentException(
                    "mirror session count limits are invalid");
        }
        if (maximumRetainedPayloadBytes < 1
                || maximumRetainedPayloadBytes > MAXIMUM_BYTE_LIMIT
                || maximumScopeRetainedPayloadBytes < 1
                || maximumScopeRetainedPayloadBytes
                > maximumRetainedPayloadBytes) {
            throw new IllegalArgumentException(
                    "mirror session payload-byte limits are invalid");
        }
    }

    /**
     * Returns conservative defaults for an explicitly enabled test or staging data plane.
     *
     * @return immutable default policy
     */
    public static MirrorSessionCapacityPolicy defaults() {
        return new MirrorSessionCapacityPolicy(
                1_000,
                100,
                4L * 1024 * 1024 * 1024,
                512L * 1024 * 1024);
    }
}
