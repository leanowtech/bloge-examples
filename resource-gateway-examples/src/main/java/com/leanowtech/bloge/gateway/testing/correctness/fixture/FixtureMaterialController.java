package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Material;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Dedicated no-store transport boundary for payload-bearing Fixture material. */
@RestController
@ConditionalOnBean(FixtureMaterialService.class)
@RequestMapping("/api/visual/fixture-materials")
public final class FixtureMaterialController {

    private final FixtureMaterialService service;
    private final IntegrationRequestAuthenticator authenticator;

    public FixtureMaterialController(
            FixtureMaterialService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping
    public ResponseEntity<Receipt> write(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) WriteRequest request) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_WRITE);
        try {
            if (request == null) {
                throw new FixtureMaterialCommandException(
                        400, "RG.CORRECTNESS.FIXTURE_MATERIAL_REQUEST_INVALID",
                        "Fixture material write request is required");
            }
            Receipt receipt = service.write(request, identity);
            return noStore(ResponseEntity.status(HttpStatus.CREATED)).body(receipt);
        } catch (FixtureMaterialCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @GetMapping("/{fixtureAssetId}")
    public ResponseEntity<Material> read(
            @PathVariable String fixtureAssetId,
            @RequestParam long revision,
            @RequestParam String fingerprint,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ);
        try {
            Material material = service.read(fixtureAssetId, revision, fingerprint, identity);
            return noStore(ResponseEntity.ok()).body(material);
        } catch (FixtureMaterialCommandException failure) {
            throw problem(failure, identity);
        }
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder response) {
        return response.cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache");
    }

    private static IntegrationProblemException problem(
            FixtureMaterialCommandException failure,
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:fixture-material", failure.getMessage(),
                failure.status(), failure.code(), false, identity.correlationId(), Map.of()));
    }
}
