package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * Server-owned authorization binding from a governed fixture revision to one enterprise scope.
 *
 * <p>The existing testing fixture registry is tenant/environment scoped. Mirror planning requires
 * organization, project, and region isolation as well, so registration writes this payload-free
 * companion instead of weakening the mirror boundary or changing the FixtureBundle v1 wire shape.</p>
 *
 * @param scope complete enterprise scope allowed to consume the fixture in a mirror
 * @param fixtureBundleRef exact immutable governed fixture revision
 * @param boundAt server registration time
 * @param boundBy authenticated actor that registered the exact revision
 */
public record MirrorFixtureScopeBinding(
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef fixtureBundleRef,
        Instant boundAt,
        String boundBy
) {
    /** Validates one complete payload-free authorization coordinate. */
    public MirrorFixtureScopeBinding {
        scope = Objects.requireNonNull(scope, "scope");
        fixtureBundleRef = Objects.requireNonNull(fixtureBundleRef, "fixtureBundleRef");
        if (!"FIXTURE_BUNDLE".equals(fixtureBundleRef.kind())) {
            throw new IllegalArgumentException("fixtureBundleRef must reference FIXTURE_BUNDLE");
        }
        boundAt = Objects.requireNonNull(boundAt, "boundAt");
        boundBy = required(boundBy, "boundBy");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
