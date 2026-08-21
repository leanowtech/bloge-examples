package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceCli;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.CodeSource;
import java.util.ServiceLoader;
import java.util.Set;

/** Test-JAR-only launcher that marks start before invoking packaged production evidence code. */
public final class MountedProductionEvidenceInvocationWorker {
    private static final String HARNESS_JAR =
            "bloge-capability-studio-mounted-authority-provider-1.0.0-child-harness.jar";
    private static final String TEST_KIT_JAR =
            "bloge-resource-gateway-test-kit-1.0.0-cli.jar";
    private static final String PROVIDER_JAR =
            "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                    + "runtime-under-test.jar";

    private MountedProductionEvidenceInvocationWorker() {
    }

    /** Creates the durable started marker, then invokes the public production Evidence CLI. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.exit(84);
        }
        requirePackagedSource(MountedProductionEvidenceInvocationWorker.class, HARNESS_JAR);
        requirePackagedSource(CapabilityStudioExecutionLeaseEvidenceCli.class, TEST_KIT_JAR);
        Class<?> filesystemAuthority = Class.forName(
                "com.leanowtech.bloge.gateway.testkit.mounted."
                        + "FilesystemDeploymentAdmissionAuthority");
        requirePackagedSource(filesystemAuthority, PROVIDER_JAR);
        var providers = ServiceLoader.load(
                CapabilityStudioStageAcceptanceAuthorityProvider.class).stream().toList();
        if (providers.size() != 1) {
            System.exit(85);
        }
        requirePackagedSource(providers.getFirst().type(), PROVIDER_JAR);
        URI schema = MountedProductionEvidenceInvocationWorker.class.getResource(
                "/schemas/resource-gateway-capability-studio/"
                        + "capability-studio-execution-lease-transcript-v1.schema.json").toURI();
        if (!"jar".equals(schema.getScheme())) {
            System.exit(85);
        }
        createDurableMarker(Path.of(args[0]));
        CapabilityStudioExecutionLeaseEvidenceCli.main(new String[]{args[1], args[2]});
    }

    private static void createDurableMarker(Path marker) throws Exception {
        Path part = marker.resolveSibling("." + marker.getFileName() + ".part");
        try (FileChannel channel = FileChannel.open(part,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer bytes = ByteBuffer.wrap("STARTED\n".getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
            Files.move(part, marker, StandardCopyOption.ATOMIC_MOVE);
        }
        try (FileChannel parent = FileChannel.open(marker.getParent(),
                StandardOpenOption.READ)) {
            parent.force(true);
        }
    }

    private static void requirePackagedSource(Class<?> type, String expectedName)
            throws Exception {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null
                || !Path.of(source.getLocation().toURI()).getFileName().toString()
                .equals(expectedName)) {
            System.exit(85);
        }
    }
}
