package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventoryRegistrationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Protected transport for Domain Fidelity denominator governance and signed-profile reads.
 *
 * <p>Every route is physically absent from production and authenticates before body decoding or
 * repository lookup. Profile projection is intentionally absent: only server-side source adapters
 * may call the trusted projection service boundary.</p>
 */
@RestController
@RequestMapping("/api/mirror/domain-fidelity")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class DomainFidelityController {
    private final DomainFidelityService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final DomainFidelityRequestDecoder decoder;

    /**
     * Creates the protected Fidelity transport.
     *
     * @param service governed application boundary
     * @param authenticator trusted workload identity boundary
     * @param decoder strict post-authentication command decoder
     */
    public DomainFidelityController(
            DomainFidelityService service,
            IntegrationRequestAuthenticator authenticator,
            DomainFidelityRequestDecoder decoder) {
        this.service = Objects.requireNonNull(
                service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(
                decoder, "decoder");
    }

    /** Registers one server-approved immutable inventory revision. */
    @PostMapping("/inventories")
    public IntegrationEnvelope<DomainFidelityInventory>
    registerInventory(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_FIDELITY_INVENTORY_WRITE);
        DomainFidelityInventoryRegistrationRequest command =
                decoder.decodeInventoryRegistration(
                        request, identity);
        DomainFidelityInventory value =
                service.registerInventory(
                        command, identity);
        return IntegrationEnvelope.of(
                "DOMAIN_FIDELITY_INVENTORY",
                value.schemaVersion(),
                value);
    }

    /** Reads one exact inventory revision in the authenticated enterprise scope. */
    @GetMapping(
            "/inventories/{inventoryId}/revisions/{revision}")
    public IntegrationEnvelope<DomainFidelityInventory>
    findInventory(
            @PathVariable String inventoryId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_FIDELITY_INVENTORY_READ);
        DomainFidelityInventory value =
                service.findInventory(
                        inventoryId, revision, identity);
        return IntegrationEnvelope.of(
                "DOMAIN_FIDELITY_INVENTORY",
                value.schemaVersion(),
                value);
    }

    /** Reads the current owner-approved inventory head. */
    @GetMapping("/inventories/{inventoryId}/latest")
    public IntegrationEnvelope<DomainFidelityInventory>
    findLatestInventory(
            @PathVariable String inventoryId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_FIDELITY_INVENTORY_READ);
        DomainFidelityInventory value =
                service.findLatestInventory(
                        inventoryId, identity);
        return IntegrationEnvelope.of(
                "DOMAIN_FIDELITY_INVENTORY",
                value.schemaVersion(),
                value);
    }

    /** Reads one exact signed profile by content address. */
    @GetMapping("/profiles/{profileFingerprint}")
    public IntegrationEnvelope<DomainFidelityProfile>
    findProfile(
            @PathVariable String profileFingerprint,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_FIDELITY_PROFILE_READ);
        DomainFidelityProfile value =
                service.findProfile(
                        profileFingerprint, identity);
        return IntegrationEnvelope.of(
                "DOMAIN_FIDELITY_PROFILE",
                value.schemaVersion(),
                value);
    }

    /** Reads the newest signed profile for one customer-business domain. */
    @GetMapping("/domains/{domainId}/profiles/latest")
    public IntegrationEnvelope<DomainFidelityProfile>
    findLatestProfile(
            @PathVariable String domainId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_FIDELITY_PROFILE_READ);
        DomainFidelityProfile value =
                service.findLatestProfile(
                        domainId, identity);
        return IntegrationEnvelope.of(
                "DOMAIN_FIDELITY_PROFILE",
                value.schemaVersion(),
                value);
    }
}
