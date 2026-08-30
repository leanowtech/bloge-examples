package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, machine-readable failure category persisted in the command journal. */
public record CommandFailureCode(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
    /** Optimistic concurrency failure. */
    public static final CommandFailureCode CAS_MISMATCH = new CommandFailureCode("CAS_MISMATCH");
    /** Required projection compilation or integrity failure. */
    public static final CommandFailureCode PROJECTION_INVALID = new CommandFailureCode("PROJECTION_INVALID");
    /** Supplied final receipt failed validation. */
    public static final CommandFailureCode RECEIPT_INVALID = new CommandFailureCode("RECEIPT_INVALID");
    /** Unexpected internal failure. */
    public static final CommandFailureCode INTERNAL = new CommandFailureCode("INTERNAL");

    /** Validates the stable uppercase wire representation. */
    public CommandFailureCode {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) throw new IllegalArgumentException("invalid command failure code");
    }
}
