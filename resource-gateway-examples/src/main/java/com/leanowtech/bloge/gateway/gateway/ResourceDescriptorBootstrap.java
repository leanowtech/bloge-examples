package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Registers a curated set of example {@link ResourceDescriptor} entries on application
 * startup so that the gateway graphs have valid {@code resourceId} values to resolve.
 *
 * <p>Each descriptor uses a configurable {@link GatewayProperties#getBaseUrl() baseUrl}
 * so integration tests can easily redirect traffic to WireMock. The descriptors exercise
 * multiple {@link ResponseProtocol} variants and {@link ParameterMapping} styles:
 *
 * <ul>
 *   <li><strong>BodyCode</strong> — {@code user-service.getProfile}</li>
 *   <li><strong>BodyFlag</strong> — {@code order-service.listOrders}</li>
 *   <li><strong>HttpStatus</strong> — {@code recommendation-service.forUser},
 *       {@code logistics-service.getShipping}</li>
 *   <li><strong>StatusCodes</strong> — {@code wallet-service.getBalance}</li>
 *   <li><strong>BlgeExpression</strong> — {@code credit-provider.primary}</li>
 * </ul>
 *
 * <p>Seeding is gated by {@link GatewayProperties#isSeedDescriptors()}. When enabled, the
 * built-in demo descriptors are kept in sync with the current {@link GatewayProperties#getBaseUrl()
 * baseUrl} on every startup so the example remains runnable even if an older H2 file
 * already contains stale seeded entries.
 */
@Component
public class ResourceDescriptorBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ResourceDescriptorBootstrap.class);

    private final WritableResourceRegistry registry;
    private final GatewayProperties properties;

    /**
     * @param registry   the writable resource registry
     * @param properties gateway configuration properties
     */
    public ResourceDescriptorBootstrap(WritableResourceRegistry registry,
                                       GatewayProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * Seeds or refreshes the built-in demo descriptors once the application is fully
     * started. Skips seeding if {@link GatewayProperties#isSeedDescriptors()} is
     * {@code false}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedDescriptors() {
        if (!properties.isSeedDescriptors()) {
            log.info("Descriptor seeding disabled (gateway.seed-descriptors=false)");
            return;
        }
        log.info("Seeding resource descriptors with baseUrl={}", properties.getBaseUrl());

        String base = properties.getBaseUrl();

        // ── User service ── BodyCode protocol ──────────────────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "user-service.getProfile",
                base + "/api/users/{userId}/profile",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of("userId", "ctx.params.userId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.BodyCode("code", Set.of(0, "0", "SUCCESS"), "message"),
                "data"
        ));

        // ── Order service ── BodyFlag protocol ─────────────────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "order-service.listOrders",
                base + "/api/orders",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of(),
                        Map.of("userId", "ctx.params.userId ?? ctx.params.orderId"),
                        null
                ),
                new ResponseProtocol.BodyFlag("success"),
                "data"
        ));

        // ── Recommendation service ── HttpStatus protocol ──────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "recommendation-service.forUser",
                base + "/api/recommendations/{userId}",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(3),
                new ParameterMapping(
                        Map.of("userId", "ctx.params.userId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.HttpStatus(),
                null
        ));

        // ── Wallet service ── StatusCodes protocol ─────────────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "wallet-service.getBalance",
                base + "/api/wallet/{userId}/balance",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(3),
                new ParameterMapping(
                        Map.of("userId", "ctx.params.userId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.StatusCodes(Set.of(200, 204)),
                "balance"
        ));

        // ── Notification service ── HttpStatus protocol ────────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "notification-service.unread",
                base + "/api/notifications/{userId}/unread",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(3),
                new ParameterMapping(
                        Map.of("userId", "ctx.params.userId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.HttpStatus(),
                null
        ));

        // ── Catalog service ── HttpStatus protocol ─────────────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "catalog-service.getProduct",
                base + "/api/products/{productId}",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of("productId", "ctx.params.productId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.HttpStatus(),
                null
        ));

        // ── Logistics service ── HttpStatus protocol ───────────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "logistics-service.getShipping",
                base + "/api/shipping/{productId}",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(3),
                new ParameterMapping(
                        Map.of("productId", "ctx.params.productId ?? ctx.params.orderId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.HttpStatus(),
                null
        ));

        // ── License service ── BodyFlag protocol ───────────────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "license-service.getLicense",
                base + "/api/licenses/{productId}",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(3),
                new ParameterMapping(
                        Map.of("productId", "ctx.params.productId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.BodyFlag("valid"),
                "license"
        ));

        // ── Invoice service ── BodyCode protocol ───────────────────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "invoice-service.getInvoice",
                base + "/api/invoices",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(3),
                new ParameterMapping(
                        Map.of(),
                        Map.of("orderId", "ctx.params.orderId"),
                        null
                ),
                new ResponseProtocol.BodyCode("status", Set.of("OK", 0), "error"),
                "invoice"
        ));

        // ── Credit provider (primary) ── BlgeExpression protocol ───────────
        syncSeededDescriptor(new ResourceDescriptor(
                "credit-provider.primary",
                base + "/api/credit/primary/{userId}",
                "GET",
                Map.of("Accept", "application/json", "X-Provider", "equifax"),
                null,
                Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of("userId", "ctx.params.userId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.BlgeExpression(
                        "ctx.statusCode == 200 && ctx.body.score != null",
                        "ctx.body.errorMessage ?? \"Unknown error\"",
                        "ctx.body"
                ),
                null
        ));

        // ── Credit provider (secondary) ── HttpStatus protocol ─────────────
        syncSeededDescriptor(new ResourceDescriptor(
                "credit-provider.secondary",
                base + "/api/credit/secondary/{userId}",
                "GET",
                Map.of("Accept", "application/json", "X-Provider", "transunion"),
                null,
                Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of("userId", "ctx.params.userId"),
                        Map.of(),
                        null
                ),
                new ResponseProtocol.HttpStatus(),
                null
        ));

        log.info("Descriptor seeding complete");
    }

    /**
     * Registers or refreshes one of the built-in demo descriptors.
     *
     * @param descriptor the descriptor to register
     */
    private void syncSeededDescriptor(ResourceDescriptor descriptor) {
        if (!registry.contains(descriptor.resourceId())) {
            registry.register(descriptor);
            log.info("Seeded descriptor: {} → {}", descriptor.resourceId(), descriptor.urlTemplate());
            return;
        }

        ResourceDescriptor existing = registry.resolve(descriptor.resourceId());
        if (existing.equals(descriptor)) {
            log.debug("Seeded descriptor '{}' already up to date", descriptor.resourceId());
            return;
        }

        registry.update(descriptor);
        log.info("Refreshed seeded descriptor: {} → {}", descriptor.resourceId(), descriptor.urlTemplate());
    }
}
