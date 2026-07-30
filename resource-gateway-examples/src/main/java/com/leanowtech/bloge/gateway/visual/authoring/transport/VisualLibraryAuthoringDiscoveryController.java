package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.leanowtech.bloge.gateway.visual.authoring.discovery.AuthoringFactProjection;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.AuthoringFactProjectionService;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.catalog.AsyncApiOperatorLibraryImportRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImportRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-neutral discovery API for progressive operator/function library authoring.
 *
 * <p>The legacy source-specific preview endpoints remain unchanged. These endpoints add one
 * stable fact/parity response contract for Workbench, CI, and external tooling.</p>
 */
@RestController
@RequestMapping("/admin/visual-operator-library-authoring/discovery")
public final class VisualLibraryAuthoringDiscoveryController {

    private final AuthoringFactProjectionService service;

    public VisualLibraryAuthoringDiscoveryController(
            AuthoringFactProjectionService service) {
        this.service = service;
    }

    @GetMapping("/runtime")
    public AuthoringFactProjection runtimeInventory() {
        return service.runtimeInventory();
    }

    @PostMapping("/capability-catalog")
    public AuthoringFactProjection capabilityCatalog(
            @RequestBody(required = false) CapabilityCatalogDiscoveryRequest request) {
        return service.capabilityCatalog(
                request == null ? "" : request.sourceId(),
                request == null ? Map.of() : request.catalog());
    }

    @PostMapping("/asyncapi")
    public AuthoringFactProjection asyncApi(
            @RequestBody(required = false) AsyncApiOperatorLibraryImportRequest request) {
        return service.asyncApi(request);
    }

    @PostMapping("/openapi")
    public AuthoringFactProjection openApi(
            @RequestBody(required = false) OpenApiResourceDesignContractImportRequest request) {
        return service.openApi(request);
    }

    @PostMapping("/dsl")
    public AuthoringFactProjection dsl(
            @RequestBody(required = false) DslImportPreviewRequest request) {
        return service.dsl(request == null
                ? new DslImportPreviewRequest("", "", null, null, "", null)
                : request);
    }

    @ExceptionHandler(AuthoringFactProjectionService.SourceLimitExceededException.class)
    public ResponseEntity<AuthoringProblem> sourceLimitExceeded(
            AuthoringFactProjectionService.SourceLimitExceededException failure) {
        AuthoringProblem problem = AuthoringProblem.of(
                "RG.AUTHORING.DISCOVERY_SOURCE_LIMIT_EXCEEDED",
                failure.getMessage(),
                413,
                List.of(AuthoringDiagnostic.error(
                        "RG.AUTHORING.DISCOVERY_SOURCE_LIMIT_EXCEEDED",
                        failure.getMessage(),
                        "/source")));
        return ResponseEntity.status(problem.status()).body(problem);
    }

    public record CapabilityCatalogDiscoveryRequest(
            String sourceId,
            Map<String, Object> catalog
    ) {
        public CapabilityCatalogDiscoveryRequest {
            sourceId = sourceId == null ? "" : sourceId.trim();
            catalog = catalog == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(catalog));
        }
    }
}
