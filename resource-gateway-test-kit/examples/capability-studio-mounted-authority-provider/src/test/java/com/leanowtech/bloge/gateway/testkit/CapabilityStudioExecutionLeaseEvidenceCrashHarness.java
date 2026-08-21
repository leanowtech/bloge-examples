package com.leanowtech.bloge.gateway.testkit;

import java.net.URI;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ServiceLoader;

/** Test-JAR-only controller for abrupt packaged evidence-process termination. */
public final class CapabilityStudioExecutionLeaseEvidenceCrashHarness {
    private static final String HARNESS_JAR =
            "bloge-capability-studio-mounted-authority-provider-1.0.0-child-harness.jar";

    private CapabilityStudioExecutionLeaseEvidenceCrashHarness() {
    }

    /** Runs the instrumented evidence flow selected by the first argument. */
    public static void main(String[] args) throws Exception {
        boolean holdRequested = args.length > 0
                && "HOLD_OWNER_DURABLE".equals(args[0]);
        boolean lockMissRequested = args.length > 0
                && "OBSERVE_PUBLICATION_FILE_LOCK_MISS".equals(args[0]);
        boolean ownerDurableHold = holdRequested && args.length == 6;
        boolean publicationLockMiss = lockMissRequested && args.length == 5;
        if ((holdRequested && !ownerDurableHold)
                || (lockMissRequested && !publicationLockMiss)
                || (!holdRequested && !lockMissRequested && args.length != 3)) {
            System.exit(84);
        }
        requirePackagedSource(CapabilityStudioExecutionLeaseEvidenceCli.class, HARNESS_JAR);
        Class<?> filesystemAuthority = Class.forName(
                "com.leanowtech.bloge.gateway.testkit.mounted."
                        + "FilesystemDeploymentAdmissionAuthority");
        requirePackagedSource(filesystemAuthority, HARNESS_JAR);
        var providers = ServiceLoader.load(
                CapabilityStudioStageAcceptanceAuthorityProvider.class).stream().toList();
        if (providers.size() != 1) {
            System.exit(85);
        }
        Class<?> provider = providers.getFirst().type();
        requirePackagedSource(provider,
                "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                        + "runtime-under-test.jar");
        requirePackagedSource(CapabilityStudioExecutionLeaseEvidenceCrashHarness.class,
                HARNESS_JAR);
        Class<?> checkpoint = Class.forName(
                "com.leanowtech.bloge.gateway.testkit.instrumentation.CrashCheckpoint");
        requirePackagedSource(checkpoint, HARNESS_JAR);
        URI schema = CapabilityStudioExecutionLeaseEvidenceCrashHarness.class.getResource(
                "/schemas/resource-gateway-capability-studio/"
                        + "capability-studio-execution-lease-transcript-v1.schema.json").toURI();
        if (!"jar".equals(schema.getScheme())) {
            System.exit(85);
        }

        if (ownerDurableHold) {
            checkpoint.getMethod("selectOwnerDurableHold",
                            Path.class, Path.class, Path.class)
                    .invoke(null, Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
        } else if (publicationLockMiss) {
            checkpoint.getMethod("selectPublicationFileLockMiss", Path.class, Path.class)
                    .invoke(null, Path.of(args[3]), Path.of(args[4]));
        } else {
            checkpoint.getMethod("select", String.class).invoke(null, args[0]);
        }
        int exit = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{args[1], args[2]}, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
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
