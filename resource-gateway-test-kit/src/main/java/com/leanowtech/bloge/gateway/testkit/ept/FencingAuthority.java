package com.leanowtech.bloge.gateway.testkit.ept;

import java.nio.file.Path;

/**
 * Injected authority-authenticated receipt primitive for EPT.
 *
 * <p>The issued token is an authority-authenticated opaque receipt: it is
 * deterministic given stable inputs, but only verifiable by the FencingAuthority
 * itself.  The token bytes carry the fencing fingerprint and epoch that are
 * required in both the B0 closure computation and the ExpectedPins verification.</p>
 */
@FunctionalInterface
public interface FencingAuthority {

    /**
     * Issues one authority-authenticated fencing token for the given request.
     *
     * <p>The token must be stable for the same stableRequestId + authorityInputTreeFingerprint
     * pair.  It must carry opaque token bytes whose SHA-256 is the authorityFingerprint
     * and a positive epoch value used in ExpectedPins verification.</p>
     *
     * @param stableRequestId stable request identity (不含 nonce)
     * @param authorityInputTreeFingerprint authority input tree fingerprint
     * @param workingDirectory private working directory (0700) for token persistence
     * @return authority token record
     * @throws FencingAuthorityException on capability unavailable or incompatible
     */
    FencingToken issue(String stableRequestId,
                       String authorityInputTreeFingerprint,
                       Path workingDirectory);

    /**
     * Authority-issued opaque token record.
     *
     * @param tokenBytes opaque token bytes whose SHA-256 is the tokenFingerprint
     * @param tokenFingerprint SHA-256 of tokenBytes
     * @param epoch positive epoch assigned by the authority
     */
    record FencingToken(
            byte[] tokenBytes,
            String tokenFingerprint,
            long epoch) {

        /** Validates tokenBytes, tokenFingerprint, and epoch. */
        public FencingToken {
            if (tokenBytes == null || tokenBytes.length == 0) {
                throw new IllegalArgumentException("tokenBytes required");
            }
            if (tokenFingerprint == null
                    || !tokenFingerprint.matches("^sha256:[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("tokenFingerprint must be sha256:hex64");
            }
            if (epoch <= 0) {
                throw new IllegalArgumentException("epoch must be > 0");
            }
        }
    }

    /** Checked exception for fencing authority failures. */
    class FencingAuthorityException extends RuntimeException {
                /** Stable failure code without credentials. */
        private final String code;

        /**
         * Constructs a FencingAuthorityException.
         * @param code stable failure code
         * @param message failure description
         */
        public FencingAuthorityException(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * Constructs a FencingAuthorityException with a cause.
         * @param code stable failure code
         * @param message failure description
         * @param cause underlying exception
         */
        public FencingAuthorityException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /**
         * Stable failure code without credentials.
         * @return failure code string.
         */
        public String code() {
            return code;
        }
    }
}
