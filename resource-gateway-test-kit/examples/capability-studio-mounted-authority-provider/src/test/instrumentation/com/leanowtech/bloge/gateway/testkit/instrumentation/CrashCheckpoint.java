package com.leanowtech.bloge.gateway.testkit.instrumentation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-harness-only abrupt process checkpoint. */
public final class CrashCheckpoint {
    private static final int CRASH_EXIT = 86;
    private static final String HOLD_OWNER_DURABLE = "HOLD_OWNER_DURABLE";
    private static final String OBSERVE_PUBLICATION_FILE_LOCK_MISS =
            "OBSERVE_PUBLICATION_FILE_LOCK_MISS";
    private static final AtomicBoolean LOCK_MISS_RECORDED = new AtomicBoolean();
    private static volatile String selected = "NO_CRASH";
    private static volatile Path ready;
    private static volatile Path barrier;
    private static volatile Path markerDurabilityBarrier;

    private CrashCheckpoint() {
    }

    /** Selects the single checkpoint passed by the packaged child harness. */
    public static void select(String checkpoint) {
        selected = Objects.requireNonNull(checkpoint, "checkpoint is required");
        ready = null;
        barrier = null;
        markerDurabilityBarrier = null;
        LOCK_MISS_RECORDED.set(false);
    }

    /** Selects the deterministic owner-durable hold used by the packaged concurrency test. */
    public static void selectOwnerDurableHold(
            Path readyMarker, Path barrierFile, Path durabilityBarrierFile) {
        selected = HOLD_OWNER_DURABLE;
        ready = Objects.requireNonNull(readyMarker, "ready marker is required");
        barrier = Objects.requireNonNull(barrierFile, "barrier file is required");
        markerDurabilityBarrier = Objects.requireNonNull(
                durabilityBarrierFile, "durability barrier is required");
        LOCK_MISS_RECORDED.set(false);
    }

    /** Selects one durable marker after the shadow CLI observes a real FileLock miss. */
    public static void selectPublicationFileLockMiss(
            Path marker, Path durabilityBarrierFile) {
        selected = OBSERVE_PUBLICATION_FILE_LOCK_MISS;
        ready = Objects.requireNonNull(marker, "lock miss marker is required");
        barrier = null;
        markerDurabilityBarrier = Objects.requireNonNull(
                durabilityBarrierFile, "durability barrier is required");
        LOCK_MISS_RECORDED.set(false);
    }

    /** Halts the test child only when the instrumented checkpoint is selected. */
    public static void hit(String checkpoint) {
        if (selected.equals(HOLD_OWNER_DURABLE) && checkpoint.equals("OWNER_DURABLE")) {
            holdOwnerDurable();
            return;
        }
        if (selected.equals(checkpoint)) {
            Runtime.getRuntime().halt(CRASH_EXIT);
        }
    }

    /** Records the first instrumented publication FileLock miss in this child JVM. */
    public static void publicationFileLockMiss() {
        if (selected.equals(OBSERVE_PUBLICATION_FILE_LOCK_MISS)
                && LOCK_MISS_RECORDED.compareAndSet(false, true)) {
            publishDurableMarker(ready, "LOCK_MISS\n");
        }
    }

    private static void holdOwnerDurable() {
        publishDurableMarker(ready, "READY\n");
        try (FileChannel channel = FileChannel.open(barrier,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var ignored = channel.lock()) {
            // The parent releases this lock only after the second JVM has missed the real lock.
        } catch (IOException failure) {
            throw new AssertionError("owner-durable hold failed", failure);
        }
    }

    private static void publishDurableMarker(Path markerPath, String value) {
        Path part = markerPath.resolveSibling("." + markerPath.getFileName() + ".part");
        try (FileChannel marker = FileChannel.open(part,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))) {
            var bytes = java.nio.ByteBuffer.wrap(value.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                marker.write(bytes);
            }
            marker.force(true);
            Files.move(part, markerPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new AssertionError("durable test marker failed", failure);
        }
        pauseBeforeMarkerParentForce();
        try (FileChannel parent = FileChannel.open(markerPath.getParent(),
                StandardOpenOption.READ)) {
            parent.force(true);
        } catch (IOException failure) {
            throw new AssertionError("durable test marker parent force failed", failure);
        }
        publishCompletionAck(markerPath);
    }

    private static void pauseBeforeMarkerParentForce() {
        try (FileChannel channel = FileChannel.open(markerDurabilityBarrier,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var ignored = channel.lock()) {
            // The parent releases this only after observing the marker without its ACK.
        } catch (IOException failure) {
            throw new AssertionError("durable test marker pause failed", failure);
        }
    }

    private static void publishCompletionAck(Path markerPath) {
        Path ack = markerPath.resolveSibling(markerPath.getFileName() + ".ack");
        Path part = ack.resolveSibling("." + ack.getFileName() + ".part");
        try {
            Files.writeString(part, "ACK\n", java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            Files.move(part, ack, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new AssertionError("test marker completion ACK failed", failure);
        }
    }
}
