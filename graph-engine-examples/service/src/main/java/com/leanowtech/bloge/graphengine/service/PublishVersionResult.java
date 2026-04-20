package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import com.leanowtech.bloge.graphengine.model.GraphVersion;

import java.util.Objects;

/**
 * Result of publishing a product-layer graph version into the underlying runtime.
 *
 * @param version updated published version snapshot
 * @param compatibility schema compatibility against the previously published version
 */
public record PublishVersionResult(
        GraphVersion version,
        SchemaCompatibility compatibility
) {
    public PublishVersionResult {
        version = Objects.requireNonNull(version, "version");
        compatibility = Objects.requireNonNull(compatibility, "compatibility");
    }
}
