package com.leanowtech.bloge.gateway.testing.admission;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Unified admission boundary shared by graph, operator, suite, batch-child, and durable creation.
 *
 * <p>Services submit only frozen, payload-free target identities. Implementations must acquire all
 * requested dimensions atomically and fail closed when the admission authority is unavailable.</p>
 */
public interface TestRuntimeAdmissionGate {

    /** Hard bound preventing a malformed artifact from creating an unbounded lock set. */
    int MAXIMUM_SUBJECTS = 10_000;

    /** Kinds of work that receive separately fingerprinted admission identities. */
    enum Kind {
        GRAPH,
        OPERATOR,
        SUITE,
        DURABLE_CREATION
    }

    /**
     * Acquires every quota claim required by one frozen execution intent.
     *
     * @param identity verified non-production caller identity
     * @param intent payload-free exact admission intent
     * @return live guard that must be closed after work reaches a committed boundary
     */
    AdmissionGuard admit(IntegrationRequestContext identity, AdmissionIntent intent);

    /**
     * A renewable all-dimension permit.
     *
     * <p>{@link #checkpoint()} must be called before publishing a terminal result. It fails closed
     * if the distributed lease was lost while user code was executing. {@link #close()} never
     * masks the primary execution failure; a failed release is recovered by lease expiry.</p>
     */
    interface AdmissionGuard extends AutoCloseable {
        /** Verifies that heartbeat ownership has not been lost. */
        void checkpoint();

        /** Releases the permit when possible; otherwise the database lease expires naturally. */
        @Override
        void close();
    }

    /**
     * Payload-free target closure used to derive hashed quota subjects.
     *
     * @param kind submitted work kind
     * @param stableRequestKey caller-stable key for suite/durable work, random key for direct work
     * @param intentFingerprint canonical fingerprint of the authorized request and principal
     * @param suiteRef suite identity for aggregate suite admission, otherwise blank
     * @param operatorRefs frozen reachable operator references
     * @param dependencyRefs frozen reachable dependency references
     */
    record AdmissionIntent(
            Kind kind,
            String stableRequestKey,
            String intentFingerprint,
            String suiteRef,
            Set<String> operatorRefs,
            Set<String> dependencyRefs) {

        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Normalizes and bounds every identifier before hashing or database access. */
        public AdmissionIntent {
            kind = java.util.Objects.requireNonNull(kind, "kind");
            stableRequestKey = required(stableRequestKey, "stableRequestKey");
            intentFingerprint = required(intentFingerprint, "intentFingerprint");
            suiteRef = normalized(suiteRef);
            if (!FINGERPRINT.matcher(intentFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Admission intentFingerprint must be canonical SHA-256");
            }
            if (stableRequestKey.length() > 255 || suiteRef.length() > 512) {
                throw new IllegalArgumentException("Admission identifiers exceed bounded size");
            }
            operatorRefs = normalizedRefs(operatorRefs);
            dependencyRefs = normalizedRefs(dependencyRefs);
            int total = operatorRefs.size() + dependencyRefs.size()
                    + (suiteRef.isBlank() ? 0 : 1);
            if (total > MAXIMUM_SUBJECTS) {
                throw new IllegalArgumentException(
                        "Admission subject closure exceeds " + MAXIMUM_SUBJECTS);
            }
            if (kind == Kind.SUITE && suiteRef.isBlank()) {
                throw new IllegalArgumentException("Suite admission requires suiteRef");
            }
            if (kind != Kind.SUITE && !suiteRef.isBlank()) {
                throw new IllegalArgumentException("Only suite admission may carry suiteRef");
            }
        }

        private static Set<String> normalizedRefs(Collection<String> values) {
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            if (values != null) {
                for (String value : values) {
                    String ref = required(value, "admissionRef");
                    if (ref.length() > 512) {
                        throw new IllegalArgumentException(
                                "Admission subject identifier exceeds bounded size");
                    }
                    normalized.add(ref);
                }
            }
            return Set.copyOf(normalized);
        }

        private static String required(String value, String name) {
            String result = normalized(value);
            if (result.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return result;
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /**
     * Creates a no-op gate for focused tests whose subject is unrelated to admission behavior.
     *
     * @return gate whose guards are always held
     */
    static TestRuntimeAdmissionGate unbounded() {
        return (identity, intent) -> new AdmissionGuard() {
            @Override
            public void checkpoint() {
                // Intentionally unbounded only for legacy focused tests.
            }

            @Override
            public void close() {
                // No distributed permit exists.
            }
        };
    }
}
