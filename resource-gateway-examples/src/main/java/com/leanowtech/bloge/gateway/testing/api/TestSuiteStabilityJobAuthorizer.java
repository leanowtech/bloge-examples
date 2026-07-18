package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Current-authority revalidation boundary for one durable stability job.
 *
 * <p>The durable principal is an authenticated submission snapshot, not perpetual authority.
 * Implementations must consult the current policy, delegation, tenant, and environment state
 * without returning credentials or business payloads.</p>
 */
public interface TestSuiteStabilityJobAuthorizer {

    /** Current authorization decisions consumed before engine execution. */
    enum Decision {
        /** Current authority still permits the exact submitted stability intent. */
        AUTHORIZED,
        /** Authority was definitively revoked or no longer satisfies policy. */
        REVOKED,
        /** Current authority could not be determined and execution must fail closed. */
        UNAVAILABLE
    }

    /**
     * Payload-free revalidation result.
     *
     * @param decision current authority decision
     * @param failureCode stable diagnostic only when not authorized
     */
    record Authorization(Decision decision, String failureCode) {

        private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Enforces an empty success diagnostic or one bounded failure code. */
        public Authorization {
            decision = Objects.requireNonNull(decision, "decision");
            failureCode = failureCode == null ? "" : failureCode.trim();
            if ((decision == Decision.AUTHORIZED) != failureCode.isBlank()
                    || !failureCode.isBlank() && !CODE.matcher(failureCode).matches()) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability job authorization result");
            }
        }

        /** @return successful current-authority result */
        public static Authorization authorized() {
            return new Authorization(Decision.AUTHORIZED, "");
        }

        /** @return definitive current-authority revocation */
        public static Authorization revoked(String failureCode) {
            return new Authorization(Decision.REVOKED, failureCode);
        }

        /** @return fail-closed current-authority ambiguity */
        public static Authorization unavailable(String failureCode) {
            return new Authorization(Decision.UNAVAILABLE, failureCode);
        }
    }

    /**
     * Revalidates one credential-free immutable job immediately before engine execution.
     *
     * @param job integrity-verified claimed job and durable principal snapshot
     * @return current payload-free decision
     */
    Authorization reauthorize(TestSuiteStabilityJobRecord job);
}
