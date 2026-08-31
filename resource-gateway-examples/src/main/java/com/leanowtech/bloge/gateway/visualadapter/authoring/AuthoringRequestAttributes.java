package com.leanowtech.bloge.gateway.visualadapter.authoring;

/** Shared trusted servlet attributes for the API authoring transport boundary. */
public final class AuthoringRequestAttributes {
    /** Correlation identifier produced by the trusted integration authenticator. */
    public static final String CORRELATION_ID = AuthoringRequestAttributes.class.getName() + ".correlationId";

    private AuthoringRequestAttributes() { }
}
