package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.test.MockOperator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BuiltinOperatorLibraryExporter}: projecting the built-in registry into a portable
 * library, and re-importing that library into a fresh catalog (idea #5, decision D16).
 */
class BuiltinOperatorLibraryExporterTest {

    @Test
    void exportsRegisteredOperatorsAsBuiltinLibrary() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("myBuiltin", MockOperator.returning(Map.of("ok", true)));
        BuiltinOperatorLibraryExporter exporter = exporterFor(registry);

        OperatorLibrary library = exporter.export();

        assertThat(library.libraryId()).isEqualTo(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID);
        assertThat(library.schemaVersion()).isEqualTo("bloge.visualOperatorLibrary.v1");
        assertThat(library.status()).isEqualTo(OperatorLibrary.STATUS_ACTIVE);
        assertThat(library.operators())
                .extracting(OperatorDefinition::operatorRef)
                .contains("myBuiltin");
    }

    @Test
    void exportedLibraryCanBeReimportedIntoAFreshCatalog() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("myBuiltin", MockOperator.returning(Map.of("ok", true)));
        BuiltinOperatorLibraryExporter exporter = exporterFor(registry);

        OperatorLibrary exported = exporter.export();

        // Round-trip: load the exported built-in library into a brand-new catalog instance and resolve
        // its operators — demonstrating the generic canvas can consume the gateway's own library.
        DefaultVisualOperatorCatalog freshCatalog = VisualCatalogTestSupport.catalogWithLibrary(exported);
        assertThat(freshCatalog.find("myBuiltin")).isPresent();
    }

    @Test
    void emptyRegistryExportsWellFormedEmptyLibrary() {
        BuiltinOperatorLibraryExporter exporter = exporterFor(new DefaultOperatorRegistry());

        OperatorLibrary library = exporter.export();

        assertThat(library.libraryId()).isEqualTo(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID);
        assertThat(library.operators()).isEmpty();
    }

    private static BuiltinOperatorLibraryExporter exporterFor(DefaultOperatorRegistry registry) {
        return new BuiltinOperatorLibraryExporter(JavaOperatorInventoryProjector.forRegistry(registry));
    }
}
