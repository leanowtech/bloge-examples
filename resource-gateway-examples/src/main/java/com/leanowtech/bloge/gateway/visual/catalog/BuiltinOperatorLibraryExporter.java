package com.leanowtech.bloge.gateway.visual.catalog;

import org.springframework.stereotype.Service;

/**
 * Exports the server's built-in Java operator registry as a portable operator-library artifact
 * (decision D16, idea #5).
 *
 * <p>The resource gateway is one concrete instance of the generic visual canvas, and its default,
 * server-implemented operators form an operator library just like any user-uploaded one. Projecting
 * that registry into a {@code bloge.visualOperatorLibrary.v1} document lets it be exported and
 * re-imported into a fresh canvas instance — the clearest demonstration that the canvas can consume
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
                projector.project()
        );
    }
}
