package com.leanowtech.bloge.graphengine.ai.prompt;

import com.leanowtech.bloge.core.schema.SchemaDescriptorJsonCodec;
import com.leanowtech.bloge.core.spi.OperatorAnnotationDetails;
import com.leanowtech.bloge.core.spi.OperatorAnnotationIntrospector;
import com.leanowtech.bloge.core.spi.OperatorMetadata;
import com.leanowtech.bloge.core.spi.OperatorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a prompt-facing operator catalog from the runtime {@link OperatorRegistry}.
 *
 * <p>Annotation introspection is delegated to
 * {@link OperatorAnnotationIntrospector} in {@code bloge-core} so the same
 * logic is shared with the product-layer operator inventory API.</p>
 */
public final class OperatorCatalogBuilder {

    private final OperatorRegistry operatorRegistry;

    /**
     * Creates a catalog builder backed by one registry.
     *
     * @param operatorRegistry operator registry that should be exposed to the LLM prompt
     */
    public OperatorCatalogBuilder(OperatorRegistry operatorRegistry) {
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
    }

    /**
     * Builds a catalog for all discoverable operators.
     *
     * @return immutable operator catalog
     */
    public List<OperatorCatalogEntry> build() {
        return build("*");
    }

    /**
     * Builds a catalog for operators matching the supplied discovery pattern.
     *
     * @param pattern glob-style discovery pattern
     * @return immutable operator catalog
     */
    public List<OperatorCatalogEntry> build(String pattern) {
        List<OperatorCatalogEntry> entries = new ArrayList<>();
        for (String name : operatorRegistry.discover(pattern)) {
            OperatorMetadata metadata = operatorRegistry.metadata(name);
            Object operator = operatorRegistry.lookup(name);
            OperatorAnnotationDetails details = OperatorAnnotationIntrospector.introspect(operator);
            entries.add(new OperatorCatalogEntry(
                    name,
                    details.description(),
                    details.owner(),
                    details.tags(),
                    details.promptHint(),
                    details.usageExample(),
                    details.constraintsDescription(),
                    SchemaDescriptorJsonCodec.serialize(metadata.inputSchema()),
                    SchemaDescriptorJsonCodec.serialize(metadata.outputSchema())
            ));
        }
        return List.copyOf(entries);
    }
}
