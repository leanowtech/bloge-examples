package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

/** Single strong HTTP entity-tag validator shared by Fixture persistence and transport. */
public final class FixtureSetStrongEtag {
    private FixtureSetStrongEtag() { }

    public static boolean isValid(String value) {
        if (value == null || value.length() < 3 || value.length() > 256
                || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return false;
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (character == '"' || character < 0x21 || character == 0x7f) return false;
        }
        return true;
    }
}
