package com.leanowtech.bloge.gateway.testing.api;

/**
 * Signals that a stored fixture envelope no longer matches its canonical bundle content.
 *
 * <p>The exception deliberately carries no fixture identifier, fingerprint, or payload. Authorized
 * service boundaries may emit a bounded security event and map it to their stable public error
 * code without turning storage corruption into a payload or existence oracle.</p>
 */
public final class FixtureBundleIntegrityException extends RuntimeException {

    /** Creates the payload-free integrity failure used by all fixture consumers. */
    public FixtureBundleIntegrityException() {
        super("Stored fixture integrity verification failed");
    }

    /** Creates the payload-free integrity failure while retaining an internal encoding cause. */
    public FixtureBundleIntegrityException(Throwable cause) {
        super("Stored fixture integrity verification failed", cause);
    }
}
