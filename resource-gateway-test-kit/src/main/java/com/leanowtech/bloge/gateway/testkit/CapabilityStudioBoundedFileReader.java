package com.leanowtech.bloge.gateway.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

/**
 * Reads one immutable, bounded regular file for a command-line boundary.
 *
 * <p>This deliberately has no error detail API. Callers must map every failure to their stable
 * protocol error without exposing an input path or filesystem message.</p>
 */
final class CapabilityStudioBoundedFileReader {
    private static final int BUFFER_BYTES = 8192;

    private CapabilityStudioBoundedFileReader() {
    }

    /**
     * Reads a regular file only when its identity and size are stable for the whole operation.
     *
     * @param path input path
     * @param maximumBytes inclusive byte limit
     * @return the complete file bytes, or {@code null} for every invalid, unstable, or failed read
     */
    static byte[] read(Path path, long maximumBytes) {
        return read(path, maximumBytes, null);
    }

    /**
     * Test-only overload that mutates the path after the channel has been opened.
     *
     * <p>The hook is package-private so boundary tests can deterministically exercise truncation
     * and replacement without making the production CLI depend on timing or a large file.</p>
     */
    static byte[] read(Path path, long maximumBytes, Runnable afterOpenForTest) {
        if (path == null || maximumBytes < 0 || maximumBytes > Integer.MAX_VALUE) {
            return null;
        }
        try {
            BasicFileAttributes before = attributes(path);
            if (!eligible(before, maximumBytes)) {
                return null;
            }
            try (SeekableByteChannel channel = Files.newByteChannel(
                    path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                BasicFileAttributes opened = attributes(path);
                if (!sameFile(before, opened) || channel.size() != before.size()) {
                    return null;
                }
                if (afterOpenForTest != null) {
                    afterOpenForTest.run();
                }

                ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                        (int) Math.min(before.size(), maximumBytes));
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
                long total = 0;
                while (true) {
                    buffer.clear();
                    int count = channel.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    if (count == 0) {
                        if (channel.position() >= channel.size()) {
                            break;
                        }
                        continue;
                    }
                    total += count;
                    if (total > maximumBytes) {
                        return null;
                    }
                    bytes.write(buffer.array(), 0, count);
                }

                BasicFileAttributes after = attributes(path);
                return sameFile(before, after)
                        && channel.size() == after.size()
                        && total == after.size()
                        ? bytes.toByteArray() : null;
            }
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean eligible(BasicFileAttributes attributes, long maximumBytes) {
        return attributes.isRegularFile()
                && !attributes.isSymbolicLink()
                && attributes.size() <= maximumBytes;
    }

    private static boolean sameFile(BasicFileAttributes left, BasicFileAttributes right) {
        return eligible(right, Integer.MAX_VALUE)
                && Objects.equals(left.fileKey(), right.fileKey())
                && left.size() == right.size()
                && left.creationTime().equals(right.creationTime())
                && left.lastModifiedTime().equals(right.lastModifiedTime());
    }
}
