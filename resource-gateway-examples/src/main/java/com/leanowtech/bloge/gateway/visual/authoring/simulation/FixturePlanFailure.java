package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import java.util.Locale;
import java.util.Objects;

/** Code-only, payload-safe failure returned by the Fixture Plan compiler. */
public final class FixturePlanFailure extends RuntimeException {
    public enum Code {
        VALIDATION,
        FIXTURE_NOT_FOUND,
        FIXTURE_REFERENCE_MISMATCH,
        FIXTURE_SUBJECT_MISMATCH,
        FIXTURE_STALE,
        CASE_NOT_FOUND,
        CONDITION_NOT_FOUND,
        CONDITION_NOT_SATISFIED,
        AUTO_MATCH_EMPTY,
        AUTO_MATCH_AMBIGUOUS,
        TARGET_OVERLAP,
        TARGET_UNSUPPORTED,
        MATERIAL_UNAVAILABLE,
        INTEGRITY
    }

    private final Code code;

    public FixturePlanFailure(Code code) {
        super("fixture.plan." + Objects.requireNonNull(code, "code").name().toLowerCase(Locale.ROOT));
        this.code = code;
    }

    public Code code() { return code; }
}
