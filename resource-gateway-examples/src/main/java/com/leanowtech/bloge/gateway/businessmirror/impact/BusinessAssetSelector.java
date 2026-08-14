package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;

import java.util.regex.Pattern;

/** Logical selector used to find all exact revisions of one business asset. */
public record BusinessAssetSelector(
        BusinessAssetRef.Kind kind,
        String id,
        String authority
) {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    public BusinessAssetSelector {
        kind = java.util.Objects.requireNonNull(kind, "kind");
        id = required(id, "id");
        authority = normalized(authority);
        if (!authority.isBlank() && !IDENTIFIER.matcher(authority).matches()) {
            throw new IllegalArgumentException("business asset authority is invalid");
        }
    }

    private static String required(String value, String name) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException("business asset " + name + " is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
