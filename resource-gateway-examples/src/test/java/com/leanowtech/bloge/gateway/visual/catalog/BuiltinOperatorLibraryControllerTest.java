package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.test.MockOperator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Endpoint-level test for {@link BuiltinOperatorLibraryController}.
 */
class BuiltinOperatorLibraryControllerTest {

    @Test
    void exportEndpointReturnsBuiltinLibrary() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("myBuiltin", MockOperator.returning(Map.of("ok", true)));
        BuiltinOperatorLibraryController controller = new BuiltinOperatorLibraryController(
                new BuiltinOperatorLibraryExporter(JavaOperatorInventoryProjector.forRegistry(registry)));

        OperatorLibrary library = controller.export();

        assertThat(library.libraryId()).isEqualTo(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID);
        assertThat(library.operators())
                .extracting(OperatorDefinition::operatorRef)
                .contains("myBuiltin");
    }
}
