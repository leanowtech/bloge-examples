package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationReceipt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Protected non-production transport for governed capability-observation ingest.
 *
 * <p>Authentication completes before strict JSON decoding. The route is physically absent from
 * production composition and publishes only an atomic admitted-or-quarantined receipt after the
 * receipt and mandatory operation audit have committed.</p>
 */
@RestController
@RequestMapping("/api/mirror/observations")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class CapabilityObservationController {
    private final CapabilityObservationAdmissionService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final CapabilityObservationDecoder decoder;

    /**
     * Creates the authenticated observation transport.
     *
     * @param service governed observation admission boundary
     * @param authenticator integration workload authenticator
     * @param decoder strict bounded observation decoder
     */
    public CapabilityObservationController(
            CapabilityObservationAdmissionService service,
            IntegrationRequestAuthenticator authenticator,
            CapabilityObservationDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Admits or quarantines one signed payload-free capability observation.
     *
     * @param request untrusted buffered observation JSON
     * @param headers authenticated integration request headers
     * @return versioned atomic observation receipt
     */
    @PostMapping
    public IntegrationEnvelope<CapabilityObservationReceipt> ingest(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_OBSERVATION_INGEST);
        CapabilityObservationReceipt receipt = CapabilityObservationReceipt.from(
                service.ingest(decoder.decode(request, identity), identity));
        return IntegrationEnvelope.of(
                CapabilityObservationReceipt.ARTIFACT_KIND,
                CapabilityObservationReceipt.SCHEMA_VERSION,
                receipt);
    }
}
