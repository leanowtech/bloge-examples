package com.leanowtech.bloge.gateway.visual.authoring.flow;

/** Payload-free, exact catalog choice shown while composing a reusable Flow. */
public record ComposableCatalogItem(String schemaVersion, String displayName,
                                    ReusableFlowCommand.ComposableRef reference,
                                    ReusableFlowCommand.Contract contract) {
    public static final String SCHEMA_VERSION = "bloge.composableCatalogItem.v1";

    public ComposableCatalogItem {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || displayName == null || displayName.isBlank()
                || displayName.length() > 200 || reference == null || contract == null) {
            throw new IllegalArgumentException("composable catalog item is invalid");
        }
        contract = new ReusableFlowCommand.Contract(contract.input(), contract.output());
    }

    @Override public ReusableFlowCommand.Contract contract() {
        return new ReusableFlowCommand.Contract(contract.input(), contract.output());
    }
}
