package com.leanowtech.bloge.gateway.testkit;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider
        .EvidenceFailureKind;

import java.io.PrintStream;
import java.nio.file.Path;

/** Deployment-only CLI for provisioning the fixed evidence publication preflight objects. */
public final class CapabilityStudioExecutionLeaseEvidencePublicationProvisioningCli {
    private static final String PROVISIONED_REASON =
            "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_PUBLICATION_CLI.PROVISIONED";
    private static final String INVALID_REASON =
            "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_PUBLICATION_CLI.INVALID";
    private static final String UNAVAILABLE_REASON =
            "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_PUBLICATION_CLI.UNAVAILABLE";

    private CapabilityStudioExecutionLeaseEvidencePublicationProvisioningCli() {
    }

    /**
     * Runs deployment provisioning.
     *
     * @param args exact provisioning arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /**
     * Provisions one publication parent without executing Stage validation or lease admission.
     *
     * @param args exact {@code --publication-parent PATH --publication-nonce sha256:...}
     * @param out payload-free one-line output
     * @return 0 provisioned, 2 invalid/output failure, or 3 unavailable
     */
    public static int run(String[] args, PrintStream out) {
        PrintStream safeOut = out == null ? System.out : out;
        if (args == null || args.length != 4
                || !"--publication-parent".equals(args[0])
                || !"--publication-nonce".equals(args[2])) {
            return emit(safeOut, "INVALID errorCode=" + INVALID_REASON) ? 2 : 2;
        }
        try {
            var declaration = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                    Path.of(args[1]), args[3]);
            String line = "PROVISIONED status=PROVISIONED publicationFingerprint="
                    + declaration.publicationFingerprint()
                    + " ownerBootstrapFingerprint="
                    + declaration.ownerBootstrapFingerprint()
                    + " lockRawFingerprint=" + declaration.lockRawFingerprint()
                    + " reasonCode=" + PROVISIONED_REASON;
            return emit(safeOut, line) ? 0 : 2;
        } catch (CapabilityStudioExecutionLeaseEvidencePublication
                 .PublicationException failure) {
            if (failure.failureKind() == EvidenceFailureKind.UNAVAILABLE) {
                return emit(safeOut, "NOT_PROVISIONED outcome=BLOCKED reasonCode="
                        + UNAVAILABLE_REASON) ? 3 : 2;
            }
            return emit(safeOut, "INVALID errorCode=" + INVALID_REASON) ? 2 : 2;
        } catch (RuntimeException invalid) {
            return emit(safeOut, "INVALID errorCode=" + INVALID_REASON) ? 2 : 2;
        }
    }

    private static boolean emit(PrintStream output, String line) {
        try {
            output.print(line);
            output.print('\n');
            output.flush();
            return !output.checkError();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
