package com.leanowtech.bloge.gateway.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.exception.ResourceDescriptorException;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class DatabaseResourceRegistryTest {

    private DatabaseResourceRegistry registry;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(ds);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        registry = new DatabaseResourceRegistry(jdbc, mapper, new BlgeExpressionEvaluator());
        registry.init();
    }

    @Test
    void register_thenResolve() {
        var descriptor = simpleDescriptor("test.api", "http://example.com/api/{id}", "GET");
        registry.register(descriptor);

        ResourceDescriptor resolved = registry.resolve("test.api");
        assertThat(resolved.resourceId()).isEqualTo("test.api");
        assertThat(resolved.urlTemplate()).isEqualTo("http://example.com/api/{id}");
        assertThat(resolved.method()).isEqualTo("GET");
    }

    @Test
    void register_duplicate_throws() {
        var descriptor = simpleDescriptor("dup.api", "http://example.com/api", "GET");
        registry.register(descriptor);

        assertThatThrownBy(() -> registry.register(descriptor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void resolve_missing_throws() {
        assertThatThrownBy(() -> registry.resolve("missing.api"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void contains_trueAfterRegister() {
        registry.register(simpleDescriptor("exists.api", "http://x.com", "GET"));
        assertThat(registry.contains("exists.api")).isTrue();
        assertThat(registry.contains("nonexistent")).isFalse();
    }

    @Test
    void update_changesDescriptor() {
        registry.register(simpleDescriptor("upd.api", "http://old.com", "GET"));

        var updated = simpleDescriptor("upd.api", "http://new.com", "POST");
        registry.update(updated);

        ResourceDescriptor resolved = registry.resolve("upd.api");
        assertThat(resolved.urlTemplate()).isEqualTo("http://new.com");
        assertThat(resolved.method()).isEqualTo("POST");
    }

    @Test
    void update_missing_throws() {
        assertThatThrownBy(() -> registry.update(simpleDescriptor("ghost.api", "http://x.com", "GET")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deregister_removesDescriptor() {
        registry.register(simpleDescriptor("del.api", "http://x.com", "GET"));
        registry.deregister("del.api");
        assertThat(registry.contains("del.api")).isFalse();
    }

    @Test
    void deregister_missing_throws() {
        assertThatThrownBy(() -> registry.deregister("noone"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void all_returnsAllRegistered() {
        registry.register(simpleDescriptor("a.api", "http://a.com", "GET"));
        registry.register(simpleDescriptor("b.api", "http://b.com", "POST"));
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void persistence_survivesReInit() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        registry.register(simpleDescriptor("persist.api", "http://p.com", "GET"));

        var registry2 = new DatabaseResourceRegistry(jdbc, mapper, new BlgeExpressionEvaluator());
        registry2.init();
        assertThat(registry2.contains("persist.api")).isTrue();
    }

    @Test
    void register_withAllProtocolVariants() {
        registry.register(new ResourceDescriptor(
                "bc.api", "http://x.com/bc", "GET", Map.of(), null,
                Duration.ofSeconds(5),
                new ParameterMapping(Map.of("id", "ctx.params.id"), Map.of(), null),
                new ResponseProtocol.BodyCode("code", Set.of(0), "msg"),
                "data"
        ));
        assertThat(registry.resolve("bc.api").responseProtocol()).isInstanceOf(ResponseProtocol.BodyCode.class);

        registry.register(new ResourceDescriptor(
                "bf.api", "http://x.com/bf", "GET", Map.of(), null,
                Duration.ofSeconds(5), ParameterMapping.empty(),
                new ResponseProtocol.BodyFlag("ok"),
                null
        ));
        assertThat(registry.resolve("bf.api").responseProtocol()).isInstanceOf(ResponseProtocol.BodyFlag.class);

        registry.register(new ResourceDescriptor(
                "sc.api", "http://x.com/sc", "GET", Map.of(), null,
                Duration.ofSeconds(5), ParameterMapping.empty(),
                new ResponseProtocol.StatusCodes(Set.of(200, 204)),
                null
        ));
        assertThat(registry.resolve("sc.api").responseProtocol()).isInstanceOf(ResponseProtocol.StatusCodes.class);

        registry.register(new ResourceDescriptor(
                "blge.api", "http://x.com/blge", "GET", Map.of(), null,
                Duration.ofSeconds(5), ParameterMapping.empty(),
                new ResponseProtocol.BlgeExpression("ctx.statusCode == 200", null, null),
                null
        ));
        assertThat(registry.resolve("blge.api").responseProtocol()).isInstanceOf(ResponseProtocol.BlgeExpression.class);
    }

    @Test
    void register_rejectsInvalidHeaderExpression() {
        var descriptor = new ResourceDescriptor(
                "bad-header.api",
                "http://x.com/header",
                "GET",
                Map.of(),
                null,
                Duration.ofSeconds(5),
                new ParameterMapping(Map.of(), Map.of(), Map.of("X-Request-Id", "==== garbage @@"), null),
                new ResponseProtocol.HttpStatus(),
                null
        );

        assertThatThrownBy(() -> registry.register(descriptor))
                .isInstanceOf(ResourceDescriptorException.class)
                .hasMessageContaining("==== garbage @@");
        assertThat(registry.contains("bad-header.api")).isFalse();
    }

    @Test
    void register_rejectsInvalidCookieExpression() {
        var descriptor = new ResourceDescriptor(
                "bad-cookie.api",
                "http://x.com/cookie",
                "GET",
                Map.of(),
                null,
                Duration.ofSeconds(5),
                new ParameterMapping(Map.of(), Map.of(), Map.of(),
                        Map.of("SESSION", "==== garbage @@"), null),
                new ResponseProtocol.HttpStatus(),
                null
        );

        assertThatThrownBy(() -> registry.register(descriptor))
                .isInstanceOf(ResourceDescriptorException.class)
                .hasMessageContaining("==== garbage @@");
        assertThat(registry.contains("bad-cookie.api")).isFalse();
    }

    private static ResourceDescriptor simpleDescriptor(String id, String url, String method) {
        return new ResourceDescriptor(id, url, method, Map.of(), null,
                Duration.ofSeconds(5), ParameterMapping.empty(),
                new ResponseProtocol.HttpStatus(), null);
    }
}
