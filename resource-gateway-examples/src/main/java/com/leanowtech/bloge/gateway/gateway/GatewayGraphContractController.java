package com.leanowtech.bloge.gateway.gateway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API exposing formal input/output schemas for resource gateway graphs.
 */
@RestController
@RequestMapping("/api/gateway/graphs/contracts")
public class GatewayGraphContractController {

    private final GatewayGraphContractCatalog catalog;

    public GatewayGraphContractController(GatewayGraphContractCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @return all resource graph contracts
     */
    @GetMapping
    public GatewayGraphContractCatalogResponse contracts() {
        return new GatewayGraphContractCatalogResponse(catalog.all());
    }

    /**
     * @param graphName graph name
     * @return matching graph contract, or 404
     */
    @GetMapping("/{graphName}")
    public ResponseEntity<GatewayGraphContract> contract(@PathVariable String graphName) {
        return catalog.find(graphName)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
