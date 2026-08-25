package com.leanowtech.bloge.gateway.testing.protocol;

/** Conservative limits for the transport-level test-control protocol. */
public final class TestControlProtocolLimits {
    public static final int MAX_ENCODED_HEADER_BYTES = 8_192;
    public static final int MAX_DECODED_ENVELOPE_BYTES = 4_096;
    public static final int MAX_DECODED_INLINE_BYTES = 8_192;
    public static final int MAX_JSON_DEPTH = 16;
    public static final int MAX_CONTAINER_ENTRIES = 256;
    public static final int MAX_STRING_CHARS = 2_048;
    public static final int MAX_TOKEN_CHARS = 128;

    private TestControlProtocolLimits() {
    }
}
