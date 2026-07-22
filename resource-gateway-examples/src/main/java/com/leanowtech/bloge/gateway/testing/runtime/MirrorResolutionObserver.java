package com.leanowtech.bloge.gateway.testing.runtime;

/**
 * Run-scoped sink for payload-free mirror resolution outcomes.
 *
 * <p>The observer is invoked only for controls compiled with mirror source precedence. Ordinary
 * test execution uses {@link #noop()} and preserves its existing rule-selection path.</p>
 */
public interface MirrorResolutionObserver {

    /** Records a resolved output without retaining the output after fingerprinting. */
    void resolved(
            InvocationRecorder.InvocationBinding binding,
            int attempt,
            String requestFingerprint,
            MirrorResolverChain.Decision decision,
            Object output);

    /** Records a source-selected business error or policy rejection. */
    void failed(
            InvocationRecorder.InvocationBinding binding,
            int attempt,
            String requestFingerprint,
            MirrorResolverChain.Decision decision,
            Exception failure);

    /** Records that every admitted source declined the invocation. */
    void abstained(
            InvocationRecorder.InvocationBinding binding,
            int attempt,
            String requestFingerprint);

    /** @return an allocation-free observer for ordinary test runs */
    static MirrorResolutionObserver noop() {
        return Noop.INSTANCE;
    }

    /** Neutral observer used outside capability-mirror execution. */
    enum Noop implements MirrorResolutionObserver {
        INSTANCE;

        @Override
        public void resolved(
                InvocationRecorder.InvocationBinding binding,
                int attempt,
                String requestFingerprint,
                MirrorResolverChain.Decision decision,
                Object output) {
            // Ordinary test execution does not produce mirror resolution artifacts.
        }

        @Override
        public void failed(
                InvocationRecorder.InvocationBinding binding,
                int attempt,
                String requestFingerprint,
                MirrorResolverChain.Decision decision,
                Exception failure) {
            // Ordinary test execution does not produce mirror resolution artifacts.
        }

        @Override
        public void abstained(
                InvocationRecorder.InvocationBinding binding,
                int attempt,
                String requestFingerprint) {
            // Ordinary test execution does not produce mirror resolution artifacts.
        }
    }
}
