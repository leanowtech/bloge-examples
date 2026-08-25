package com.leanowtech.bloge.gateway.testing.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Immutable source for run identity and terminal wall-clock observations. */
public record TestRunIdentitySource(
        Supplier<String> runIdSupplier,
        Supplier<Instant> clockSupplier) {

    public TestRunIdentitySource {
        Objects.requireNonNull(runIdSupplier, "runIdSupplier");
        Objects.requireNonNull(clockSupplier, "clockSupplier");
    }

    /** @return the next run identifier from this source */
    public String nextRunId() {
        return Objects.requireNonNull(runIdSupplier.get(), "runIdSupplier returned null");
    }

    /** @return the next wall-clock observation from this source */
    public Instant now() {
        return Objects.requireNonNull(clockSupplier.get(), "clockSupplier returned null");
    }

    /** @return the compatibility source used at production constructor boundaries */
    public static TestRunIdentitySource system() {
        return new TestRunIdentitySource(
                () -> UUID.randomUUID().toString(), Instant::now);
    }
}
