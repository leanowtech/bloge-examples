package com.leanowtech.bloge.gateway.testing.protocol;

/**
 * Parser failure with a stable reason code and a deliberately value-free message.
 *
 * <p>The exception intentionally does not retain a parser cause. Header values,
 * inline JSON and business data must not become part of an error chain or log
 * message by accident.</p>
 */
public final class TestControlProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final TestControlProtocolReason reasonCode;

    public TestControlProtocolException(TestControlProtocolReason reasonCode) {
        super("BLOGE_TEST_CONTROL_" + requireReason(reasonCode).name());
        this.reasonCode = reasonCode;
    }

    public TestControlProtocolReason reasonCode() {
        return reasonCode;
    }

    private static TestControlProtocolReason requireReason(TestControlProtocolReason reasonCode) {
        if (reasonCode == null) {
            throw new IllegalArgumentException("reason code is required");
        }
        return reasonCode;
    }
}
