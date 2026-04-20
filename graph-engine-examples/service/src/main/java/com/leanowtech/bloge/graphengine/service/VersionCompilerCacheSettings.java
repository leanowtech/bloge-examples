package com.leanowtech.bloge.graphengine.service;

import java.time.Duration;

/**
 * Immutable settings for the in-process {@link VersionCompiler} result cache.
 *
 * @param enabled whether compile-result caching is enabled
 * @param maximumSize maximum number of cached compile results to retain
 * @param expireAfterAccess how long an unused entry remains eligible for reuse
 */
public record VersionCompilerCacheSettings(boolean enabled, long maximumSize, Duration expireAfterAccess) {

    /**
     * Default compile-cache policy used when no custom settings are supplied.
     */
    public static final VersionCompilerCacheSettings DEFAULT =
            new VersionCompilerCacheSettings(true, 1_000, Duration.ofMinutes(60));

    /**
     * Normalizes the cache settings so callers cannot accidentally disable cache
     * bounds or expiry with invalid values.
     */
    public VersionCompilerCacheSettings {
        maximumSize = maximumSize < 1 ? DEFAULT.maximumSize : maximumSize;
        expireAfterAccess = expireAfterAccess == null || expireAfterAccess.isNegative() || expireAfterAccess.isZero()
                ? DEFAULT.expireAfterAccess
                : expireAfterAccess;
    }
}
