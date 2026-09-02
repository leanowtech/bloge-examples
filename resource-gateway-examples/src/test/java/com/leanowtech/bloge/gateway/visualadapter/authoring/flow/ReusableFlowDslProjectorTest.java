package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReusableFlowDslProjectorTest {

    @Test
    void projectsBlogeDslIntoTheCanonicalReusableFlowCommand() {
        ReusableFlowDslProjector projector = new ReusableFlowDslProjector(importer());
        ReusableFlowCommand.ComposableRef profile = resource("customer-profile", "a");
        ReusableFlowCommand.ComposableRef account = resource("account-summary", "b");
        ReusableFlowCommand.ComposableRef offer = resource("retention-offer", "c");

        ReusableFlowCommand projected = projector.project(command(dsl(), Map.of(
                "resource:customer-profile", profile,
                "resource:account-summary", account,
                "resource:retention-offer", offer)));

        assertThat(projected.schemaVersion()).isEqualTo(ReusableFlowCommand.SCHEMA_VERSION);
        assertThat(projected.flow().contract().input().required()).containsExactly("customerId");
        assertThat(projected.flow().contract().output().required())
                .containsExactly("customerId", "offerCode", "discountPercent", "message");
        assertThat(projected.flow().graph().nodes()).containsExactly(
                new ReusableFlowCommand.Node("profile", "profile", profile, List.of(
                        new ReusableFlowCommand.Input("$.customerId",
                                new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")))),
                new ReusableFlowCommand.Node("account", "account", account, List.of(
                        new ReusableFlowCommand.Input("$.customerId",
                                new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")))),
                new ReusableFlowCommand.Node("offer", "offer", offer, List.of(
                        new ReusableFlowCommand.Input("$.customerId",
                                new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")),
                        new ReusableFlowCommand.Input("$.segment",
                                new ReusableFlowCommand.MappingSource.NodeOutput("profile", "$.segment")),
                        new ReusableFlowCommand.Input("$.monthlySpend",
                                new ReusableFlowCommand.MappingSource.NodeOutput("account", "$.monthlySpend"))
                )));
        assertThat(projected.flow().graph().output())
                .isEqualTo(new ReusableFlowCommand.Output("offer", "$"));
        assertThat(projected.flow().layout().nodes()).containsOnlyKeys("profile", "account", "offer");
    }

    @Test
    void rejectsUnpinnedOrLossyDslInsteadOfSilentlyChangingTheFlow() {
        ReusableFlowDslProjector projector = new ReusableFlowDslProjector(importer());

        assertThatThrownBy(() -> projector.project(command(dsl(), Map.of(
                        "resource:unused", resource("unused", "d")))))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(failure -> ((ReusableFlowFailure) failure).code())
                .isEqualTo(ReusableFlowFailure.Code.VALIDATION);
        assertThatThrownBy(() -> projector.project(command("""
                graph lossy {
                  input { customerId: String }
                  output { customerId: String }
                  node profile : "resource:customer-profile" {
                    input { customerId = ctx.customerId }
                    timeout = 3s
                  }
                }
                """, Map.of("resource:customer-profile", resource("customer-profile", "a")))))
                .isInstanceOf(ReusableFlowFailure.class);

        assertThatThrownBy(() -> projector.project(command("""
                import "shared.bloge" as shared
                graph lossyImport {
                  input { customerId: String }
                  output { customerId: String }
                  node profile : "resource:customer-profile" {
                    input { customerId = ctx.customerId }
                  }
                }
                """, Map.of("resource:customer-profile", resource("customer-profile", "a")))))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(failure -> ((ReusableFlowFailure) failure).code())
                .isEqualTo(ReusableFlowFailure.Code.VALIDATION);
    }

    @Test
    void commandEnforcesTheFrozenDslWireBounds() {
        ReusableFlowCommand.ComposableRef resource = resource("customer-profile", "a");
        Map<String, ReusableFlowCommand.ComposableRef> pins = Map.of(
                "resource:customer-profile", resource);

        assertThatThrownBy(() -> new ReusableFlowDslCommand(null, "Flow",
                ReusableFlowCommand.Kind.TOOL, "description",
                new ReusableFlowDslCommand.Source("flow.bloge", dsl()), pins))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReusableFlowDslCommand.Source(" ", dsl()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReusableFlowDslCommand.Source("flow.bloge", "x".repeat(524_289)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReusableFlowDslCommand(ReusableFlowDslCommand.SCHEMA_VERSION,
                "Flow", ReusableFlowCommand.Kind.TOOL, "description",
                new ReusableFlowDslCommand.Source("flow.bloge", dsl()), Map.of("bad key!", resource)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReusableFlowDslCommand command(
            String dsl, Map<String, ReusableFlowCommand.ComposableRef> pins) {
        return new ReusableFlowDslCommand(ReusableFlowDslCommand.SCHEMA_VERSION,
                "Customer retention offer tool",
                ReusableFlowCommand.Kind.TOOL,
                "Combines customer profile, account value and offer recommendation APIs.",
                new ReusableFlowDslCommand.Source("customer-retention.bloge", dsl), pins);
    }

    private static ReusableFlowCommand.ComposableRef resource(String id, String fingerprintCharacter) {
        return new ReusableFlowCommand.ComposableRef.ApiResource(
                id, 1, "sha256:" + fingerprintCharacter.repeat(64));
    }

    private static DslImportService importer() {
        VisualOperatorCatalog emptyCatalog = new VisualOperatorCatalog() {
            @Override public List<OperatorDefinition> list(OperatorCatalogQuery query) { return List.of(); }
            @Override public Optional<OperatorDefinition> find(String operatorRef) { return Optional.empty(); }
        };
        return new DslImportService(emptyCatalog, new OperatorLibraryValidator());
    }

    private static String dsl() {
        return """
                graph customerRetentionOffer {
                  input {
                    customerId: String
                  }
                  output {
                    customerId: String
                    offerCode: String
                    discountPercent: Decimal
                    message: String
                  }
                  node profile : "resource:customer-profile" {
                    input { customerId = ctx.customerId }
                  }
                  node account : "resource:account-summary" {
                    input { customerId = ctx.customerId }
                  }
                  node offer : "resource:retention-offer" {
                    input {
                      customerId = ctx.customerId
                      segment = profile.output.segment
                      monthlySpend = account.output.monthlySpend
                    }
                  }
                }
                """;
    }
}
