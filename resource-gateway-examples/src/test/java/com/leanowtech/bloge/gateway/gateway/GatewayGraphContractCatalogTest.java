package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GatewayGraphContractCatalogTest {

    private static final Pattern GRAPH_DECLARATION = Pattern.compile("(?m)^\\s*graph\\s+([A-Za-z_][A-Za-z0-9_]*)");

    @Test
    void builtInContractsCoverEveryGatewayGraph() throws IOException {
        GatewayGraphContractCatalog catalog = GatewayGraphContractCatalog.builtIn();
        List<String> graphNames = gatewayGraphNames();

        assertThat(catalog.graphNames()).containsExactlyInAnyOrderElementsOf(graphNames);
        for (String graphName : graphNames) {
            GatewayGraphContract contract = catalog.require(graphName);
            assertThat(VisualSchemaValidator.validateEnvelope(contract.inputSchema(), "/inputSchema"))
                    .as("%s inputSchema", graphName)
                    .isEmpty();
            assertThat(VisualSchemaValidator.validateEnvelope(contract.outputSchema(), "/outputSchema"))
                    .as("%s outputSchema", graphName)
                    .isEmpty();
            assertThat(contract.outputNodes()).as("%s output nodes", graphName).isNotEmpty();
        }
    }

    @Test
    void graphContractApiExposesFormalInputAndOutputSchemas() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GatewayGraphContractController(GatewayGraphContractCatalog.builtIn()))
                .build();

        mockMvc.perform(get("/api/gateway/graphs/contracts/loanDecisionPolicy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(GatewayGraphContract.SCHEMA_VERSION))
                .andExpect(jsonPath("$.graphName").value("loanDecisionPolicy"))
                .andExpect(jsonPath("$.inputSchema.schema.properties.applicantId.type").value("string"))
                .andExpect(jsonPath("$.inputSchema.schema.properties.requestedAmount.type").value("number"))
                .andExpect(jsonPath("$.outputSchema.schema.properties.policy.properties.decision.type").value("string"));

        mockMvc.perform(get("/api/gateway/graphs/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(GatewayGraphContractCatalogResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.contracts.length()").value(gatewayGraphNames().size()));
    }

    @Test
    void gatewayGraphServiceRejectsContextThatDoesNotMatchInputSchema() throws IOException {
        Graph graph = loadGatewayGraph("user-dashboard");
        var registry = new DefaultOperatorRegistry();
        registry.registerRaw("httpResource", new StubOperator());
        GraphEngine engine = GraphEngine.builder().registry(registry).build();
        GatewayGraphService service = new GatewayGraphService(engine, List.of(graph), GatewayGraphContractCatalog.builtIn());

        assertThatThrownBy(() -> service.execute("userDashboard", new GraphContext(Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userDashboard")
                .hasMessageContaining("inputSchema")
                .hasMessageContaining("userId");
    }

    private static Graph loadGatewayGraph(String resourceName) throws IOException {
        var registry = new DefaultOperatorRegistry();
        registry.registerRaw("httpResource", new StubOperator());
        var loader = new GraphLoader(registry);
        String dsl = Files.readString(Path.of("src/main/resources/bloge/gateway/" + resourceName + ".bloge"));
        return loader.load(dsl);
    }

    private static List<String> gatewayGraphNames() throws IOException {
        try (var files = Files.list(Path.of("src/main/resources/bloge/gateway"))) {
            return files
                    .filter(path -> path.toString().endsWith(".bloge"))
                    .sorted()
                    .map(GatewayGraphContractCatalogTest::graphName)
                    .toList();
        }
    }

    private static String graphName(Path file) {
        try {
            var matcher = GRAPH_DECLARATION.matcher(Files.readString(file));
            if (!matcher.find()) {
                throw new IllegalStateException("No graph declaration in " + file);
            }
            return matcher.group(1);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + file, exception);
        }
    }

    private static class StubOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            return Map.of();
        }
    }
}
