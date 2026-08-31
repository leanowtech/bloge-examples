package com.leanowtech.bloge.gateway.visual.authoring.flow;

/** Shared validator for the Flow draft store's opaque strong-validator subset. */
final class ReusableFlowStrongEtag {
    private ReusableFlowStrongEtag() { }

    static boolean isValid(String value) {
        if (value == null || value.length() < 3 || value.length() > 258
                || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return false;
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\' || character < 0x21 || character > 0x7e) return false;
        }
        return true;
    }
}
