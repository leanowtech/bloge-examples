package com.leanowtech.bloge.gateway.visualadapter.authoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes non-sensitive deployment gates before the static workbench offers authoring actions. */
@RestController
public final class AuthoringAvailabilityController {
    private final Availability availability;

    /** Captures the startup feature state without depending on feature-scoped authoring beans. */
    public AuthoringAvailabilityController(
            @Value("${gateway.authoring.api-resource.enabled:false}") boolean apiResource,
            @Value("${gateway.authoring.reusable-flow.enabled:false}") boolean reusableFlow) {
        this.availability = new Availability(
                "bloge.authoringAvailability.v1", apiResource, reusableFlow);
    }

    /** Returns an always-available, non-cacheable workbench capability projection. */
    @GetMapping("/api/authoring/availability")
    public ResponseEntity<Availability> availability() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(availability);
    }

    /** Stable payload-free feature projection consumed before any authoring request. */
    public record Availability(String schemaVersion, boolean apiResource, boolean reusableFlow) { }
}
