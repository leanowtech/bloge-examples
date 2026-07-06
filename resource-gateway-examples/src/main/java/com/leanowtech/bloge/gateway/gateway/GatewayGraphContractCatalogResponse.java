package com.leanowtech.bloge.gateway.gateway;

import java.util.List;

/**
 * List response for resource gateway graph contracts.
 *
 * @param schemaVersion response schema version
 * @param contracts graph contracts
 */
public record GatewayGraphContractCatalogResponse(
        String schemaVersion,
        List<GatewayGraphContract> contracts
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContracts.v1";

    public GatewayGraphContractCatalogResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        contracts = contracts == null ? List.of() : List.copyOf(contracts);
    }

    public GatewayGraphContractCatalogResponse(List<GatewayGraphContract> contracts) {
        this(SCHEMA_VERSION, contracts);
    }
}
