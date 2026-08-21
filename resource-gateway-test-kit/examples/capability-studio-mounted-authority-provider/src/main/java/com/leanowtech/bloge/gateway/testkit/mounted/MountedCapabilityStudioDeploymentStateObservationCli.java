package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioDeploymentStateObservation;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentUnavailableException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Existing-only, payload-free deployment state observation command.
 *
 * <p>The store is observed under its shared descriptor lock before any evidence output is
 * created. The observer performs no explicit store write, namespace mutation, permission change,
 * durability force, initialization, repair, or recovery. Ordinary file reads may update atime on
 * filesystems that do not suppress it; deployments requiring atime stability need a read-only or
 * noatime mount.</p>
 */
public final class MountedCapabilityStudioDeploymentStateObservationCli {
    /** Successful closed reason. */
    public static final String OBSERVED_REASON =
            "RG.CAPABILITY_STUDIO.DEPLOYMENT_STATE_OBSERVATION_CLI.OBSERVED";
    /** Malformed invocation or unsafe output reason. */
    public static final String INVALID_REASON =
            "RG.CAPABILITY_STUDIO.DEPLOYMENT_STATE_OBSERVATION_CLI.INVALID";
    /** Existing store or shared-lock dependency unavailable reason. */
    public static final String UNAVAILABLE_REASON =
            "RG.CAPABILITY_STUDIO.DEPLOYMENT_STATE_OBSERVATION_CLI.UNAVAILABLE";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> ARGUMENTS = Set.of("--phase",
            "--evidence-transaction-id", "--state-root",
            "--expected-store-descriptor-fingerprint", "--output");
    private static final Set<java.nio.file.attribute.PosixFilePermission> BUILD_FILE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> FINAL_FILE =
            PosixFilePermissions.fromString("r--------");

    private MountedCapabilityStudioDeploymentStateObservationCli() {
    }

    /**
     * Runs the strict existing-only observer.
     *
     * @param args closed observation arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream output) {
        if (output == null) {
            return 2;
        }
        try {
            Arguments parsed = parse(args);
            CapabilityStudioDeploymentStateObservation.Observation observation =
                    FilesystemDeploymentAdmissionAuthority.observeExistingStore(
                            parsed.stateRoot, parsed.descriptorFingerprint,
                            parsed.phase, parsed.transactionId);
            if (parsed.output.startsWith(parsed.stateRoot)) {
                throw new IllegalArgumentException("output is invalid");
            }
            writeFresh(parsed.output, observation.bytes());
            String line = "OBSERVED status=OBSERVED phase=" + observation.phase()
                    + " evidenceTransactionId=" + observation.evidenceTransactionId()
                    + " storeDescriptorFingerprint="
                    + observation.storeDescriptorFingerprint()
                    + " stateMaterialFingerprint="
                    + observation.stateMaterialFingerprint()
                    + " observationFingerprint=" + observation.observationFingerprint()
                    + " reasonCode=" + OBSERVED_REASON;
            return writeLine(output, line) ? 0 : 2;
        } catch (DeploymentUnavailableException unavailable) {
            writeLine(output, "FAILED status=BLOCKED reasonCode=" + UNAVAILABLE_REASON);
            return 2;
        } catch (RuntimeException | IOException failure) {
            writeLine(output, "FAILED status=INVALID reasonCode=" + INVALID_REASON);
            return 2;
        }
    }

    private static Arguments parse(String[] args) {
        if (args == null || args.length != ARGUMENTS.size() * 2) {
            throw new IllegalArgumentException("arguments are invalid");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            String name = args[index];
            String value = args[index + 1];
            if (!ARGUMENTS.contains(name) || value == null || value.isBlank()
                    || values.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("arguments are invalid");
            }
        }
        if (!values.keySet().equals(ARGUMENTS)) {
            throw new IllegalArgumentException("arguments are invalid");
        }
        CapabilityStudioDeploymentStateObservation.Phase phase =
                CapabilityStudioDeploymentStateObservation.Phase.valueOf(
                        values.get("--phase"));
        String transaction = fingerprint(values.get("--evidence-transaction-id"));
        String descriptor = fingerprint(
                values.get("--expected-store-descriptor-fingerprint"));
        Path stateRoot = absolute(values.get("--state-root"));
        Path output = absolute(values.get("--output"));
        return new Arguments(phase, transaction, descriptor, stateRoot, output);
    }

    private static Path absolute(String value) {
        Path path = Path.of(value);
        Path normalized = path.toAbsolutePath().normalize();
        if (!path.isAbsolute() || !path.equals(normalized)) {
            throw new IllegalArgumentException("path is invalid");
        }
        return path;
    }

    private static String fingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException("fingerprint is invalid");
        }
        return value;
    }

    private static void writeFresh(Path output, byte[] bytes) throws IOException {
        Path parent = output.getParent();
        if (parent == null || !output.equals(parent.resolve(output.getFileName()).normalize())) {
            throw new IOException("output unavailable");
        }
        BasicFileAttributes before = Files.readAttributes(parent,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        int parentMode = ((Number) Files.getAttribute(parent, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
        long parentUid = ((Number) Files.getAttribute(parent, "unix:uid",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        if (before.isSymbolicLink() || !before.isDirectory()
                || before.fileKey() == null || parentMode != 0700) {
            throw new IOException("output unavailable");
        }
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String suffix = java.util.HexFormat.of().formatHex(nonce);
        Path temporary = parent.resolve("." + output.getFileName() + "." + suffix + ".part");
        Object temporaryKey = null;
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(BUILD_FILE))) {
                temporaryKey = Files.readAttributes(temporary, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).fileKey();
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
                Files.setPosixFilePermissions(temporary, FINAL_FILE);
                channel.force(true);
            }
            Files.createLink(output, temporary);
            forceDirectory(parent);
            Files.delete(temporary);
            temporaryKey = null;
            forceDirectory(parent);
            requireFinal(output, bytes, parentUid);
            BasicFileAttributes after = Files.readAttributes(parent,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long afterUid = ((Number) Files.getAttribute(parent, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            if (!java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || parentUid != afterUid) {
                throw new IOException("output unavailable");
            }
        } catch (FileAlreadyExistsException exists) {
            throw new IOException("output unavailable", exists);
        } finally {
            if (temporaryKey != null) {
                try {
                    BasicFileAttributes current = Files.readAttributes(temporary,
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (java.util.Objects.equals(temporaryKey, current.fileKey())) {
                        Files.delete(temporary);
                    }
                } catch (IOException ignored) {
                    // Unknown or unavailable residue is never modified further.
                }
            }
        }
    }

    private static void requireFinal(
            Path output, byte[] expected, long parentUid) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(output,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long links = ((Number) Files.getAttribute(output, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        int mode = ((Number) Files.getAttribute(output, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
        long uid = ((Number) Files.getAttribute(output, "unix:uid",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                || attributes.fileKey() == null || links != 1 || mode != 0400
                || uid != parentUid
                || !Arrays.equals(expected, Files.readAllBytes(output))) {
            throw new IOException("output unavailable");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("output unavailable", unsupported);
        }
    }

    private static boolean writeLine(PrintStream output, String line) {
        try {
            output.print(line);
            output.print('\n');
            output.flush();
            return !output.checkError();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private record Arguments(
            CapabilityStudioDeploymentStateObservation.Phase phase,
            String transactionId,
            String descriptorFingerprint,
            Path stateRoot,
            Path output) {
    }
}
