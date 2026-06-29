package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResourceDescriptorBootstrap}.
 *
 * <p>Verifies the number of seeded descriptors, protocol coverage, refresh
 * behavior, and that all embedded bloge expressions compile successfully.
 */
class ResourceDescriptorBootstrapTest {

    private InMemoryRegistry registry;
    private BlgeExpressionEvaluator evaluator;
    private ResourceDescriptorBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        registry = new InMemoryRegistry();
        evaluator = new BlgeExpressionEvaluator();

        var props = new GatewayProperties();
        props.setBaseUrl("http://test-host:1234");
        props.setSeedDescriptors(true);

        bootstrap = new ResourceDescriptorBootstrap(registry, props);
        bootstrap.seedDescriptors();
    }

    @Test
    void seededDescriptorCount() {
        assertThat(registry.all()).hasSize(12);
    }

    @Test
    void protocolCoverage() {
        Set<String> protocolTypes = registry.all().stream()
                .map(d -> d.responseProtocol().getClass().getSimpleName())
                .collect(Collectors.toSet());

        assertThat(protocolTypes).containsExactlyInAnyOrder(
                "HttpStatus", "BodyCode", "BodyFlag", "StatusCodes", "BlgeExpression"
        );
    }

    @Test
    void allUrlsUseConfiguredBaseUrl() {
        registry.all().forEach(d ->
                assertThat(d.urlTemplate()).startsWith("http://test-host:1234/")
        );
    }

    @Test
    void seededExpressionsCompile() {
        for (ResourceDescriptor d : registry.all()) {
            // Parameter mapping expressions
            d.parameterMapping().pathExpressions().values()
                    .forEach(expr -> assertThat(evaluator.canCompile(expr))
                            .as("path expr '%s' in %s", expr, d.resourceId()).isTrue());
            d.parameterMapping().queryExpressions().values()
                    .forEach(expr -> assertThat(evaluator.canCompile(expr))
                            .as("query expr '%s' in %s", expr, d.resourceId()).isTrue());

            // BlgeExpression protocol expressions
            if (d.responseProtocol() instanceof ResponseProtocol.BlgeExpression blge) {
                assertThat(evaluator.canCompile(blge.successExpr()))
                        .as("successExpr in %s", d.resourceId()).isTrue();
                if (blge.messageExpr() != null) {
                    assertThat(evaluator.canCompile(blge.messageExpr()))
                            .as("messageExpr in %s", d.resourceId()).isTrue();
                }
                if (blge.payloadExpr() != null) {
                    assertThat(evaluator.canCompile(blge.payloadExpr()))
                            .as("payloadExpr in %s", d.resourceId()).isTrue();
                }
            }
        }
    }

    @Test
    void repeatSeedingDoesNotDuplicate() {
        // Seed again
        bootstrap.seedDescriptors();
        assertThat(registry.all()).hasSize(12);
    }

    @Test
    void reseedingRefreshesBuiltInDescriptorsToNewBaseUrl() {
        var updatedProps = new GatewayProperties();
        updatedProps.setBaseUrl("http://updated-host:5678");
        updatedProps.setSeedDescriptors(true);

        var updatedBootstrap = new ResourceDescriptorBootstrap(registry, updatedProps);
        updatedBootstrap.seedDescriptors();

        assertThat(registry.all()).hasSize(12);
        assertThat(registry.all())
                .extracting(ResourceDescriptor::urlTemplate)
                .allMatch(url -> url.startsWith("http://updated-host:5678/"));
    }

    @Test
    void seedingDisabledLeavesRegistryEmpty() {
        var emptyRegistry = new InMemoryRegistry();
        var props = new GatewayProperties();
        props.setSeedDescriptors(false);
        var disabledBootstrap = new ResourceDescriptorBootstrap(emptyRegistry, props);
        disabledBootstrap.seedDescriptors();
        assertThat(emptyRegistry.all()).isEmpty();
    }

    // ── In-memory test double ───────────────────────────────────────────

    private static class InMemoryRegistry implements WritableResourceRegistry {
        private final ConcurrentHashMap<String, ResourceDescriptor> map = new ConcurrentHashMap<>();

        @Override
        public void register(ResourceDescriptor d) {
            if (map.containsKey(d.resourceId())) {
                throw new IllegalArgumentException("Already registered: " + d.resourceId());
            }
            map.put(d.resourceId(), d);
        }

        @Override
        public void update(ResourceDescriptor d) {
            map.put(d.resourceId(), d);
        }

        @Override
        public void deregister(String id) {
            map.remove(id);
        }

        @Override
        public ResourceDescriptor resolve(String id) {
            return map.get(id);
        }

        @Override
        public boolean contains(String id) {
            return map.containsKey(id);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return map.values();
        }
    }
}
