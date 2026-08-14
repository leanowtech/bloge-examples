package com.leanowtech.bloge.gateway.businessmirror.compilation;

/** Retryable fencing failure raised when compile dependencies move inside one compile attempt. */
public final class PackageDependencyDriftException extends RuntimeException {
    public PackageDependencyDriftException(String message) {
        super(message);
    }
}
