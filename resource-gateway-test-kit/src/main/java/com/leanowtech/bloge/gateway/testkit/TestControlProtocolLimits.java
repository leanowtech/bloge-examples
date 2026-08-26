package com.leanowtech.bloge.gateway.testkit;

/** Wire limits mirrored from the server's public test-control protocol. */
public final class TestControlProtocolLimits {
    /** Maximum encoded HTTP header value size. */
    public static final int MAX_ENCODED_HEADER_BYTES = 8_192;
    /** Maximum decoded envelope JSON size. */
    public static final int MAX_DECODED_ENVELOPE_BYTES = 4_096;
    /** Maximum JSON nesting depth. */
    public static final int MAX_JSON_DEPTH = 16;
    /** Maximum object or array entries. */
    public static final int MAX_CONTAINER_ENTRIES = 256;
    /** Maximum protocol text length. */
    public static final int MAX_STRING_CHARS = 2_048;

    private TestControlProtocolLimits() {
    }
}
