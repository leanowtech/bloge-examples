package com.leanowtech.bloge.gateway.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Built-in demo upstream APIs that back the README curl examples.
 *
 * <p>The gateway's seeded descriptors point here by default so the example can be run and
 * exercised locally without standing up a second server. Real upstreams can still be used
 * by overriding {@code gateway.base-url}.
 */
@RestController
@RequestMapping("/demo-upstream/api")
public class DemoUpstreamController {

    @GetMapping("/users/{userId}/profile")
    public Map<String, Object> userProfile(@PathVariable String userId) {
        return Map.of(
                "code", 0,
                "message", "ok",
                "data", Map.of(
                        "userId", userId,
                        "name", "Alice",
                        "email", "alice@example.com",
                        "tier", "premium"
                )
        );
    }

    @GetMapping("/orders")
    public Map<String, Object> listOrders(@RequestParam(value = "userId", required = false) String userId) {
        return Map.of(
                "success", true,
                "data", Map.of(
                        "userId", userId == null ? "u1" : userId,
                        "orders", List.of(
                                order("ord-1", 29.99, "shipped"),
                                order("ord-2", 59.00, "processing")
                        )
                )
        );
    }

    @GetMapping("/recommendations/{userId}")
    public Map<String, Object> recommendations(@PathVariable String userId) {
        return Map.of("entries", List.of("top-pick-for-" + userId, "bundle-discount"));
    }

    @GetMapping("/wallet/{userId}/balance")
    public Map<String, Object> walletBalance(@PathVariable String userId) {
        return Map.of(
                "userId", userId,
                "balance", 100.50,
                "currency", "USD"
        );
    }

    @GetMapping("/notifications/{userId}/unread")
    public Map<String, Object> unreadNotifications(@PathVariable String userId) {
        return Map.of(
                "userId", userId,
                "unread", 3,
                "entries", List.of(
                        Map.of("id", "n1", "title", "Shipment update"),
                        Map.of("id", "n2", "title", "Invoice ready"),
                        Map.of("id", "n3", "title", "New recommendation")
                )
        );
    }

    @GetMapping("/products/{productId}")
    public Map<String, Object> product(@PathVariable String productId) {
        return switch (productId) {
            case "p2" -> Map.of(
                    "productId", "p2",
                    "name", "Photo Editor Pro",
                    "type", "digital",
                    "price", 49.99
            );
            default -> Map.of(
                    "productId", "p1",
                    "name", "Wireless Mouse",
                    "type", "physical",
                    "price", 29.99
            );
        };
    }

    @GetMapping("/shipping/{productId}")
    public Map<String, Object> shipping(@PathVariable String productId) {
        return switch (productId) {
            case "ord-1" -> Map.of("status", "shipped", "trackingNumber", "TRK-001");
            case "ord-2" -> Map.of("status", "processing");
            default -> Map.of("shippable", true, "estimatedDays", 3, "carrier", "FedEx");
        };
    }

    @GetMapping("/licenses/{productId}")
    public Map<String, Object> license(@PathVariable String productId) {
        return Map.of(
                "valid", true,
                "license", Map.of(
                        "productId", productId,
                        "licenseType", "perpetual",
                        "downloadUrl", "https://cdn.example.com/photo-editor-pro"
                )
        );
    }

    @GetMapping("/invoices")
    public Map<String, Object> invoice(@RequestParam("orderId") String orderId) {
        return Map.of(
                "status", "OK",
                "invoice", switch (orderId) {
                    case "ord-2" -> Map.of("invoiceId", "inv-2", "amount", 59.00);
                    default -> Map.of("invoiceId", "inv-1", "amount", 29.99);
                }
        );
    }

    @GetMapping("/credit/primary/{userId}")
    public Map<String, Object> primaryCreditScore(@PathVariable String userId) {
        return Map.of(
                "userId", userId,
                "score", 750,
                "provider", "equifax",
                "reportDate", "2024-01-15"
        );
    }

    @GetMapping("/credit/secondary/{userId}")
    public Map<String, Object> secondaryCreditScore(@PathVariable String userId) {
        return Map.of(
                "userId", userId,
                "score", 740,
                "provider", "transunion",
                "reportDate", "2024-01-14"
        );
    }

    private static Map<String, Object> order(String orderId, double total, String status) {
        return Map.of(
                "orderId", orderId,
                "total", total,
                "status", status
        );
    }
}
