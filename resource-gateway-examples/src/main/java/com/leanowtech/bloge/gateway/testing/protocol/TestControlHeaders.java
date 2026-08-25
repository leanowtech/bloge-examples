package com.leanowtech.bloge.gateway.testing.protocol;

import java.util.Objects;
import java.util.Optional;

/** Immutable parsed projection of the four BLOGE test-control headers. */
public final class TestControlHeaders {
    private final TestControlEnvelope envelope;
    private final String fidelityToken;
    private final String scopeToken;
    private final TestInlineControl inline;

    TestControlHeaders(
            TestControlEnvelope envelope,
            String fidelityToken,
            String scopeToken,
            TestInlineControl inline) {
        this.envelope = envelope;
        this.fidelityToken = fidelityToken;
        this.scopeToken = scopeToken;
        this.inline = inline;
    }

    public static TestControlHeaders empty() {
        return new TestControlHeaders(null, null, null, null);
    }

    public TestControlEnvelope envelope() {
        return envelope;
    }

    public String fidelityToken() {
        return fidelityToken;
    }

    public String scopeToken() {
        return scopeToken;
    }

    public TestInlineControl inline() {
        return inline;
    }

    public Optional<TestControlEnvelope> envelopeOptional() {
        return Optional.ofNullable(envelope);
    }

    public Optional<String> fidelity() {
        return Optional.ofNullable(fidelityToken);
    }

    public Optional<String> scope() {
        return Optional.ofNullable(scopeToken);
    }

    public Optional<TestInlineControl> inlineOptional() {
        return Optional.ofNullable(inline);
    }

    /** True when at least one control header carries a non-empty control plan. */
    public boolean hasControlPlan() {
        return envelope != null || fidelityToken != null || scopeToken != null || inline != null;
    }

    public boolean isEmpty() {
        return !hasControlPlan();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TestControlHeaders that
                && Objects.equals(envelope, that.envelope)
                && Objects.equals(fidelityToken, that.fidelityToken)
                && Objects.equals(scopeToken, that.scopeToken)
                && Objects.equals(inline, that.inline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(envelope, fidelityToken, scopeToken, inline);
    }

    @Override
    public String toString() {
        return "TestControlHeaders{controlPlan=" + hasControlPlan() + "}";
    }
}
