package com.leanowtech.bloge.gateway.visual.catalog;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Exports the server's built-in Java operator registry as a portable operator-library artifact
 * (decision D16, idea #5).
 *
 * <p>The resource gateway is one concrete instance of the generic visual canvas, and its default,
 * server-implemented operators form an operator library just like any user-uploaded one. Projecting
 * that registry into a {@code bloge.visualOperatorLibrary.v1} document lets it be exported and
 * re-imported into a fresh canvas instance - the clearest demonstration that the canvas can consume
 * any operator library, including the gateway's own.</p>
 */
@Service
public class BuiltinOperatorLibraryExporter {

    /** Stable id for the virtual library that represents the built-in operator registry. */
    public static final String BUILTIN_LIBRARY_ID = "builtin";

    private final JavaOperatorInventoryProjector projector;

    /**
     * @param projector projector that turns the runtime Java operator registry into visual definitions
     */
    public BuiltinOperatorLibraryExporter(JavaOperatorInventoryProjector projector) {
        this.projector = projector;
    }

    /**
     * Projects the built-in Java operator registry into a portable operator library.
     *
     * @return the built-in operators as a {@code bloge.visualOperatorLibrary.v1} library
     */
    public OperatorLibrary export() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                BUILTIN_LIBRARY_ID,
                "Built-in Operators",
                "1.0.0",
                "bloge-platform",
                OperatorLibrary.STATUS_ACTIVE,
                BuiltInFunctionCatalog.defaults(),
                projector.project()
        );
    }

    /**
     * Projects the built-in Java operator registry into an operator library that can pass through the
     * same portable bundle import path as user-provided libraries.
     *
     * <p>The raw runtime view uses system-owned source kinds such as {@code java-operator}. Those are
     * intentionally rejected by the import validator so user uploads cannot impersonate server-owned
     * inventory. For D16 round-trip export, the trusted built-in snapshot is therefore re-labeled as a
     * normal {@code user-library} source while preserving native lowering back to the original Java
     * operator reference. Java registry projections also expose the DTO input as {@code input}, which
     * is not safe as a BLOGE DSL field, so the portable view renames that port to {@code inputs}.</p>
     *
     * @return importable built-in operators as a {@code bloge.visualOperatorLibrary.v1} library
     */
    public OperatorLibrary exportImportable() {
        OperatorLibrary library = export();
        List<OperatorDefinition> importableOperators = library.operators().stream()
                .map(BuiltinOperatorLibraryExporter::importableOperator)
                .toList();
        return new OperatorLibrary(
                library.schemaVersion(),
                library.libraryId(),
                library.displayName(),
                library.version(),
                library.owner(),
                library.status(),
                library.builtInFunctions(),
                importableOperators
        );
    }

    private static OperatorDefinition importableOperator(OperatorDefinition operator) {
        return new OperatorDefinition(
                operator.schemaVersion(),
                operator.operatorRef(),
                operator.operatorVersion(),
                operator.display(),
                new OperatorDefinition.Source("user-library", "", "", "", false, BUILTIN_LIBRARY_ID),
                importablePorts(operator.ports()),
                operator.configSchema(),
                operator.capabilities(),
                operator.policy(),
                operator.lowering(),
                List.of()
        );
    }

    private static OperatorDefinition.Ports importablePorts(OperatorDefinition.Ports ports) {
        return new OperatorDefinition.Ports(
                ports.inputs().stream()
                        .map(BuiltinOperatorLibraryExporter::importableInputPort)
                        .toList(),
                ports.outputs()
        );
    }

    private static OperatorDefinition.Port importableInputPort(OperatorDefinition.Port port) {
        if (!"input".equals(port.name())) {
            return port;
        }
        return new OperatorDefinition.Port("inputs", port.schema(), port.required(), port.description());
    }
}
