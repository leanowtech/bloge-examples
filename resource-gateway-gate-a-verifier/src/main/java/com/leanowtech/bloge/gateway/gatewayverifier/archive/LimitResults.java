package com.leanowtech.bloge.gateway.gatewayverifier.archive;

/**
 * Five-limit check result booleans emitted inside {@link ZipArchiveVerifier.Result}.
 *
 * @param rawBytes          true when total archive bytes exceed the limit
 * @param zipEntries        true when entry count exceeds the limit
 * @param singleEntry       true when any single entry exceeds the limit
 * @param totalUncompressed true when total uncompressed bytes exceed the limit
 * @param compressionRatio  true when any DEFLATED entry exceeds the ratio limit
 */
public record LimitResults(
        boolean rawBytes,
        boolean zipEntries,
        boolean singleEntry,
        boolean totalUncompressed,
        boolean compressionRatio
) {}
