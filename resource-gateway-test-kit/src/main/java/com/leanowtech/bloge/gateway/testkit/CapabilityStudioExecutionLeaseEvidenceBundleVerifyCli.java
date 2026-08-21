package com.leanowtech.bloge.gateway.testkit;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider
        .EvidenceFailureKind;

import java.io.PrintStream;
import java.nio.file.Path;

/** Read-only command-line verifier for a durable execution-lease evidence wrapper. */
public final class CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli {
    /** Closed success reason. */
    public static final String VERIFIED_REASON =
            "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_BUNDLE_VERIFY_CLI.VERIFIED";
    private static final String INVALID_REASON =
            "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_BUNDLE_VERIFY_CLI.INVALID";
    private static final String UNAVAILABLE_REASON =
            "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_BUNDLE_VERIFY_CLI.UNAVAILABLE";

    private CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli() {
    }

    /**
     * Runs the read-only verifier and exits with its closed status.
     *
     * @param args exact verifier arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /**
     * Verifies one wrapper without Provider discovery or filesystem mutation.
     *
     * @param args exact transcript path, Stage raw, formal outer, and publication pin flags
     * @param out payload-free single-line output
     * @return 0 verified, 2 invalid/output failure, or 3 unavailable
     */
    public static int run(String[] args, PrintStream out) {
        PrintStream safeOut = out == null ? System.out : out;
        if (args == null || args.length != 8
                || !"--transcript".equals(args[0])
                || !"--expected-stage-result-raw-fingerprint".equals(args[2])
                || !"--expected-formal-outer-fingerprint".equals(args[4])
                || !"--expected-publication-fingerprint".equals(args[6])) {
            return emit(safeOut, "INVALID errorCode=" + INVALID_REASON) ? 2 : 2;
        }
        try {
            Path transcript = Path.of(args[1]);
            var verified = CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                    transcript, args[3], args[5], args[7]);
            String line = "VERIFIED status=VERIFIED verificationScope=DURABLE_WRAPPER"
                    + " evidenceTransactionId=" + verified.evidenceTransactionId()
                    + " transcriptRawFingerprint=" + verified.transcriptRawFingerprint()
                    + " transcriptFingerprint=" + verified.transcriptFingerprint()
                    + " leaseReceiptFingerprint=" + verified.leaseReceiptFingerprint()
                    + " transitionWitnessFingerprint="
                    + verified.transitionWitnessFingerprint()
                    + " reasonCode=" + VERIFIED_REASON;
            return emit(safeOut, line) ? 0 : 2;
        } catch (CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                .VerificationException failure) {
            if (failure.failureKind() == EvidenceFailureKind.UNAVAILABLE) {
                return emit(safeOut, "NOT_VERIFIED outcome=BLOCKED reasonCode="
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
