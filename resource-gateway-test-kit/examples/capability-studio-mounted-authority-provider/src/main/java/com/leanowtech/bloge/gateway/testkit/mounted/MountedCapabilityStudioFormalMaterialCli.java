package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentUnavailableException;
import com.leanowtech.bloge.gateway.testkit.mounted.MountedCapabilityStudioStageAcceptanceAuthorityProvider.FormalMaterialDeclaration;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Offline deployment CLI for declaring the mounted reference Provider's formal material.
 *
 * <p>The command accepts no arguments and reads only the Provider's three JVM properties. It may
 * initialize a new execution-lease store at generation zero, but it never verifies a Stage Result,
 * invokes post-run authorities, or commits an execution lease. Its payload-free output is material
 * for an independently authenticated deployment pin, not formal acceptance evidence.</p>
 */
public final class MountedCapabilityStudioFormalMaterialCli {
    private static final String DECLARED =
            "RG.CAPABILITY_STUDIO.MOUNTED_FORMAL_MATERIAL_CLI.DECLARED";
    private static final String INVALID =
            "RG.CAPABILITY_STUDIO.MOUNTED_FORMAL_MATERIAL_CLI.INVALID";
    private static final String UNAVAILABLE =
            "RG.CAPABILITY_STUDIO.MOUNTED_FORMAL_MATERIAL_CLI.UNAVAILABLE";

    private MountedCapabilityStudioFormalMaterialCli() {
    }

    /**
     * Declares the configured formal material and exits with zero only after emitting it.
     *
     * @param args must be empty
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, Clock.systemUTC()));
    }

    static int run(String[] args, PrintStream output, Clock clock) {
        if (args == null || args.length != 0) {
            return emit(output, "NOT_DECLARED status=INVALID reasonCode=" + INVALID, 2);
        }
        Instant trustedTime;
        try {
            trustedTime = clock.instant();
        } catch (RuntimeException unavailable) {
            return emit(output, "NOT_DECLARED status=BLOCKED reasonCode=" + UNAVAILABLE, 2);
        }
        try {
            var provider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider(
                    Clock.fixed(trustedTime, ZoneOffset.UTC));
            FormalMaterialDeclaration declaration = provider.formalMaterialDeclaration();
            return emit(output, "DECLARED status=DECLARED"
                    + " authorityMaterialFingerprint="
                    + declaration.authorityMaterialFingerprint()
                    + " formalOuterFingerprint=" + declaration.formalOuterFingerprint()
                    + " targetAdmissionMaterialFingerprint="
                    + declaration.targetAdmissionMaterialFingerprint()
                    + " deploymentAdmissionAuthorityMaterialFingerprint="
                    + declaration.deploymentAdmissionAuthorityMaterialFingerprint()
                    + " trustedClockMaterialFingerprint="
                    + declaration.trustedClockMaterialFingerprint()
                    + " admissionLifecycleAuthorityMaterialFingerprint="
                    + declaration.admissionLifecycleAuthorityMaterialFingerprint()
                    + " executionLeaseAuthorityMaterialFingerprint="
                    + declaration.executionLeaseAuthorityMaterialFingerprint()
                    + " storeDescriptorFingerprint="
                    + declaration.storeDescriptorFingerprint()
                    + " reasonCode=" + DECLARED, 0);
        } catch (DeploymentUnavailableException unavailable) {
            return emit(output, "NOT_DECLARED status=BLOCKED reasonCode=" + UNAVAILABLE, 2);
        } catch (RuntimeException failure) {
            if (MountedCapabilityStudioStageAcceptanceAuthorityProvider
                    .deploymentUnavailableCause(failure) != null) {
                return emit(output,
                        "NOT_DECLARED status=BLOCKED reasonCode=" + UNAVAILABLE, 2);
            }
            return emit(output, "NOT_DECLARED status=INVALID reasonCode=" + INVALID, 2);
        }
    }

    private static int emit(PrintStream output, String line, int exit) {
        try {
            output.println(line);
            output.flush();
            return output.checkError() ? 2 : exit;
        } catch (RuntimeException failure) {
            return 2;
        }
    }
}
