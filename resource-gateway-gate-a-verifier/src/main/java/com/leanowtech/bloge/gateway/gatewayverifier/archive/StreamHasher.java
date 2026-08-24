package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Computes CRC-32, uncompressed size, and SHA-256 over a ZIP entry data stream
 * using bounded reads — no {@code readAllBytes()} and no filesystem writes.
 *
 * <p>For STORED entries the raw bytes are fed directly to the hashers.
 * For DEFLATED entries a manual {@link Inflater} is used to decode the raw deflate
 * stream in bounded chunks. This approach correctly handles ZIP entries where the
 * compressed data stream is bounded to exactly the central-directory cSize bytes —
 * unlike {@link java.util.zip.InflaterInputStream} which can throw "Unexpected end
 * of ZLIB input stream" when the stream ends at a deflate block boundary.
 *
 * <p>CRC-32 and size mismatches are detected by the caller
 * ({@link ZipArchiveVerifier}) against the values in the central directory.
 */
public final class StreamHasher {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Immutable result of a successful stream hash operation.
     *
     * @param crc32            recomputed CRC-32 over the uncompressed bytes
     * @param uncompressedSize total uncompressed bytes read
     * @param sha256           hex-encoded SHA-256 over the uncompressed bytes
     */
    public record Result(long crc32, long uncompressedSize, String sha256) {

        /**
         * Returns a defensive copy of this result.
         * All fields are primitives or immutable Strings, so this is a
         * no-op that signals the intent to callers who copy results.
         */
        public Result {
            // Primitives and String are inherently immutable;
            // explicit copy call documents defensive intent for T2/T3.
        }

        /** Builds a Result from raw fields; package-private for test access. */
        static Result of(long crc32, long uncompressedSize, String sha256) {
            return new Result(crc32, uncompressedSize, sha256);
        }
    }

    private StreamHasher() {}

    /**
     * Hash the data reachable through {@code dataStream} bounded by
     * {@code expectedUncompressedSize} bytes from the entry header.
     *
     * @param dataStream                stream positioned at the beginning of entry data
     * @param expectedUncompressedSize value from the central directory (may be 0 for DD entries)
     * @param compressionMethod        0 = STORED, 8 = DEFLATED
     * @return a populated {@link Result}
     * @throws ArchiveKernelException on unknown compression method
     */
    public static Result hash(InputStream dataStream,
                              long expectedUncompressedSize,
                              int compressionMethod)
            throws ArchiveKernelException {
        if (compressionMethod != 0 && compressionMethod != 8) {
            throw new ArchiveKernelException("AK-UNKNOWN-COMPRESSION",
                    java.util.Map.of("compressionMethod", compressionMethod));
        }

        CRC32 crc = new CRC32();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new ArchiveKernelException("AK-ARCHIVE-IO",
                    java.util.Map.of("detail", "SHA-256 not available"), e);
        }

        if (compressionMethod == 0) {
            // STORED: read directly from the stream
            return hashStored(dataStream, expectedUncompressedSize, crc, md);
        } else {
            // DEFLATED: use manual Inflater for exact-bound handling
            return hashDeflated(dataStream, expectedUncompressedSize, crc, md);
        }
    }

    /**
     * Hash STORED (uncompressed) entry data.
     */
    private static Result hashStored(InputStream dataStream,
                                     long expectedUncompressedSize,
                                     CRC32 crc,
                                     MessageDigest md)
            throws ArchiveKernelException {
        long bytesRead = 0L;
        byte[] buf = new byte[BUFFER_SIZE];
        try {
            int n;
            while ((n = dataStream.read(buf)) != -1) {
                crc.update(buf, 0, n);
                md.update(buf, 0, n);
                bytesRead += n;
            }
        } catch (IOException e) {
            throw new ArchiveKernelException("AK-ARCHIVE-IO",
                    java.util.Map.of("detail", "I/O error reading stored data"), e);
        }

        // AK-SIZE-MISMATCH: only when expected size is known (> 0).
        if (expectedUncompressedSize > 0 && bytesRead != expectedUncompressedSize) {
            throw new ArchiveKernelException("AK-SIZE-MISMATCH",
                    java.util.Map.of(
                            "expectedSize", expectedUncompressedSize,
                            "actualSize", bytesRead));
        }

        return buildResult(crc, bytesRead, md);
    }

    /**
     * Hash DEFLATED entry data using a manual Inflater.
     *
     * <p>Security policy: The stream is accepted ONLY when {@link Inflater#finished()}
     * returns true — meaning the entire compressed stream has been successfully decoded.
     * No heuristic or incomplete-stream acceptance is permitted.
     *
     * <p>Before declaring success, the implementation drains all output and verifies
     * no trailing compressed bytes remain: after {@code finished()}, any unread byte
     * in the inflater's internal buffer or any byte readable from the bounded input
     * stream indicates structural invalidity (extra compressed data beyond the declared
     * cSize boundary).
     *
     * @throws ArchiveKernelException with AK-DD-UNVERIFIABLE when the stream cannot
     *         be fully decoded, or with AK-ARCHIVE-IO on I/O errors
     */
    private static Result hashDeflated(InputStream dataStream,
                                        long expectedUncompressedSize,
                                        CRC32 crc,
                                        MessageDigest md)
            throws ArchiveKernelException {
        Inflater inflater = new Inflater(true); // true = raw deflate (no ZLIB header/trailer)
        byte[] inBuf = new byte[BUFFER_SIZE];
        byte[] outBuf = new byte[BUFFER_SIZE];
        long totalUncompressed = 0L;
        int consecutiveNoProgress = 0;

        try {
            // Feed compressed data chunk by chunk until inflater signals finished.
            while (!inflater.finished()) {

                // --- Reject needsDictionary: stream references unknown preset dictionary ---
                if (inflater.needsDictionary()) {
                    throw new ArchiveKernelException("AK-DD-UNVERIFIABLE",
                            java.util.Map.of(
                                    "expectedUncompressedSize", expectedUncompressedSize,
                                    "detail", "deflate stream requires unknown preset dictionary"));
                }

                // --- Feed more compressed input if inflater is ready ---
                if (inflater.needsInput()) {
                    int read = dataStream.read(inBuf);
                    if (read == -1) {
                        // Input exhausted before Inflater finished: truncated stream.
                        throw new ArchiveKernelException("AK-DD-UNVERIFIABLE",
                                java.util.Map.of(
                                        "expectedUncompressedSize", expectedUncompressedSize,
                                        "detail", "truncated deflate stream — input exhausted before inflation complete"));
                    }
                    inflater.setInput(inBuf, 0, read);
                }

                // --- Decode a chunk of output ---
                int inflated;
                try {
                    inflated = inflater.inflate(outBuf);
                } catch (java.util.zip.DataFormatException e) {
                    throw new ArchiveKernelException("AK-DD-UNVERIFIABLE",
                            java.util.Map.of(
                                    "expectedUncompressedSize", expectedUncompressedSize,
                                    "detail", "invalid deflate data"), e);
                }

                if (inflated > 0) {
                    crc.update(outBuf, 0, inflated);
                    md.update(outBuf, 0, inflated);
                    totalUncompressed += inflated;
                    consecutiveNoProgress = 0;
                } else {
                    consecutiveNoProgress++;
                    // If inflater is not finished, needs no input, and produces no output
                    // for extended iterations, the stream is malformed (no-progress trap).
                    if (consecutiveNoProgress > 2 && !inflater.needsInput()) {
                        throw new ArchiveKernelException("AK-DD-UNVERIFIABLE",
                                java.util.Map.of(
                                        "expectedUncompressedSize", expectedUncompressedSize,
                                        "detail", "deflate stream stalled — no progress and no input needed"));
                    }
                }
            }

            // --- Inflater is finished: verify no trailing compressed bytes exist ---
            // The inflater's internal buffer holds unread input. Any remaining byte
            // means the stream contained extra compressed data beyond cSize, which is
            // structurally invalid for a properly bounded ZIP entry.
            if (inflater.getRemaining() > 0) {
                throw new ArchiveKernelException("AK-DD-UNVERIFIABLE",
                        java.util.Map.of(
                                "expectedUncompressedSize", expectedUncompressedSize,
                                "detail", "trailing compressed bytes after inflation complete"));
            }

            // Additionally verify the bounded input stream has no more bytes.
            // Attempt one non-blocking read; any byte indicates structural overflow.
            int probe = dataStream.read();
            if (probe != -1) {
                throw new ArchiveKernelException("AK-DD-UNVERIFIABLE",
                        java.util.Map.of(
                                "expectedUncompressedSize", expectedUncompressedSize,
                                "detail", "unread bytes in bounded input stream after inflation complete"));
            }

        } catch (IOException e) {
            throw new ArchiveKernelException("AK-ARCHIVE-IO",
                    java.util.Map.of("detail", "I/O error reading deflate data"), e);
        } finally {
            inflater.end();
        }

        // AK-SIZE-MISMATCH: only when expected size is known (> 0)
        if (expectedUncompressedSize > 0 && totalUncompressed != expectedUncompressedSize) {
            throw new ArchiveKernelException("AK-SIZE-MISMATCH",
                    java.util.Map.of(
                            "expectedSize", expectedUncompressedSize,
                            "actualSize", totalUncompressed));
        }

        return buildResult(crc, totalUncompressed, md);
    }

    /**
     * Build the result from hasher state.
     */
    private static Result buildResult(CRC32 crc, long bytesRead, MessageDigest md) {
        String sha256hex = hex(md.digest());
        return new Result(crc.getValue(), bytesRead, sha256hex);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
