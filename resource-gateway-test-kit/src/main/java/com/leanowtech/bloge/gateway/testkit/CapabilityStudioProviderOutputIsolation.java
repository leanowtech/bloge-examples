package com.leanowtech.bloge.gateway.testkit;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Isolates synchronous provider output for the Capability Studio CLI process.
 *
 * <p>The provider SPI has no output channel, so a provider that writes directly to
 * {@code System.out} or {@code System.err} must not corrupt the CLI's payload-free
 * protocol output. This is deliberately process-local and synchronous: it serializes
 * isolation windows in this JVM, discards writes made through the current global
 * streams, and restores the exact streams that were installed before the window.</p>
 */
final class CapabilityStudioProviderOutputIsolation {
    private static final Object LOCK = new Object();
    private static final PrintStream DISCARD = new PrintStream(OutputStream.nullOutputStream(), true);

    private CapabilityStudioProviderOutputIsolation() {
    }

    /**
     * Runs one synchronous provider operation with both global output streams redirected.
     *
     * <p>No {@link Error} is caught or converted. The {@code finally} block restores the
     * streams before an exception or error leaves the window.</p>
     *
     * @param operation synchronous operation to run
     * @param <T> operation result type
     * @return operation result
     */
    static <T> T call(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation is required");
        synchronized (LOCK) {
            PrintStream previousOut = System.out;
            PrintStream previousErr = System.err;
            boolean outRedirected = false;
            boolean errRedirected = false;
            try {
                System.setOut(DISCARD);
                outRedirected = true;
                System.setErr(DISCARD);
                errRedirected = true;
                return operation.get();
            } finally {
                if (errRedirected) {
                    System.setErr(previousErr);
                }
                if (outRedirected) {
                    System.setOut(previousOut);
                }
            }
        }
    }
}
