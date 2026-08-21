package com.leanowtech.bloge.gateway.testkit;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Independent strict semantic verifier for one canonical execution-lease transcript file.
 *
 * <p>This command does not verify the durable owner/wrapper closure and therefore is not evidence
 * for RG-CS-FELT-v1 FELT-08 or FELT-14.</p>
 */
public final class CapabilityStudioExecutionLeaseTranscriptVerifyCli {
    private CapabilityStudioExecutionLeaseTranscriptVerifyCli() {
    }

    /**
     * Verifies exactly one transcript path.
     *
     * @param args one transcript path
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream output) {
        if (output == null) {
            return 2;
        }
        try {
            if (args == null || args.length != 1) {
                throw new IllegalArgumentException("arguments are invalid");
            }
            byte[] bytes = CapabilityStudioBoundedFileReader.read(Path.of(args[0]),
                    CapabilityStudioExecutionLeaseTranscript.MAXIMUM_BYTES);
            CapabilityStudioExecutionLeaseTranscript.Transcript transcript =
                    CapabilityStudioExecutionLeaseTranscript.verify(bytes);
            return line(output, "VERIFIED status=VERIFIED verificationScope=SEMANTIC_ONLY"
                    + " transcriptFingerprint="
                    + transcript.transcriptFingerprint()
                    + " commitStatus=" + transcript.commitStatus()
                    + " leaseReceiptFingerprint="
                    + transcript.executionLeaseReceipt().fingerprint()
                    + " transitionWitnessFingerprint="
                    + transcript.executionLeaseTransitionWitness().fingerprint()
                    + " reasonCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_TRANSCRIPT_CLI.VERIFIED") ? 0 : 2;
        } catch (RuntimeException failure) {
            line(output, "INVALID status=INVALID reasonCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_TRANSCRIPT_CLI.INVALID");
            return 2;
        }
    }

    private static boolean line(PrintStream output, String value) {
        try {
            output.print(value);
            output.print('\n');
            output.flush();
            return !output.checkError();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
