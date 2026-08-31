package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class WholeFlowFixtureMaterializerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void materializesExactWholeFlowReturnCases() {
        ReusableFlowVersion version = version();
        FixtureSetCommand command = command(version.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null);

        GeneratedDefaultFixture generated = new WholeFlowFixtureMaterializer()
                .generate("eligibility-cases", version, command);

        assertThat(generated.view().fixtureSetId()).isEqualTo("eligibility-cases");
        assertThat(generated.view().subject()).isEqualTo(version.subject());
        assertThat(generated.view().cases()).containsExactlyElementsOf(command.cases());
        assertThat(generated.receipt().caseIds()).containsExactly("approved");
        assertThat(generated.summary().cases()).containsExactly(
                new FixtureSetSummary.CaseSummary("approved", "Approved customer"));
        assertThat(generated.caseMappings()).containsExactly(
                new GeneratedDefaultFixture.CaseMapping("approved", "approved"));
    }

    @Test
    void rejectsNodeControlsAdvancedFidelityAndContractDrift() {
        ReusableFlowVersion version = version();
        WholeFlowFixtureMaterializer materializer = new WholeFlowFixtureMaterializer();

        assertThatThrownBy(() -> materializer.generate("eligibility-cases", version,
                command(version.subject(), FixtureSetCommand.Target.node("profile"),
                        FixtureSetCommand.Behavior.returned(
                                FixtureSetCommand.Material.inline(output())), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> materializer.generate("eligibility-cases", version,
                command(version.subject(), FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(
                                FixtureSetCommand.Material.inline(output())),
                        FixtureSetCommand.Fidelity.TRANSPORT_LEVEL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> materializer.generate("eligibility-cases", version,
                command(version.subject(), FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                                JSON.createObjectNode().put("eligible", "yes"))), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> materializer.generate("eligibility-cases", version,
                command(new FixtureSubjectRef.FlowVersion("other", 1, version.fingerprint()),
                        FixtureSetCommand.Target.subject(), FixtureSetCommand.Behavior.returned(
                                FixtureSetCommand.Material.inline(output())), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    public static ReusableFlowVersion version() {
        SchemaEnvelope input = SchemaEnvelope.object(
                Map.of("customerId", Map.of("type", "string")), List.of("customerId"));
        SchemaEnvelope output = SchemaEnvelope.object(
                Map.of("eligible", Map.of("type", "boolean")), List.of("eligible"));
        ReusableFlowCommand.Contract contract = new ReusableFlowCommand.Contract(input, output);
        ReusableFlowCommand.Graph graph = new ReusableFlowCommand.Graph(List.of(
                new ReusableFlowCommand.Node("decision", "Decision",
                        new ReusableFlowCommand.ComposableRef.ApiResource("decision-api", 1,
                                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                        List.of())), new ReusableFlowCommand.Output("decision", "$"));
        return new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION, "eligibility-v1", 1,
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                new ReusableFlowVersion.Source("eligibility-draft", 3,
                        "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
                "eligibility", "Eligibility", ReusableFlowCommand.Kind.TOOL, "Checks eligibility",
                contract, graph, Instant.parse("2030-01-01T00:00:00Z"), "author",
                ReusableFlowVersion.Status.PUBLISHED);
    }

    public static FixtureSetCommand command(FixtureSubjectRef subject, FixtureSetCommand.Target target,
                                            FixtureSetCommand.Behavior behavior,
                                            FixtureSetCommand.Fidelity fidelity) {
        FixtureSetCommand.Case fixtureCase = new FixtureSetCommand.Case("approved", "Approved customer",
                JSON.createObjectNode().put("customerId", "customer-1"),
                List.of(new FixtureSetCommand.Control(target, behavior, fidelity)),
                new FixtureSetCommand.Expect(output()));
        return new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION, "Eligibility cases",
                subject, List.of(fixtureCase));
    }

    public static com.fasterxml.jackson.databind.JsonNode output() {
        return JSON.createObjectNode().put("eligible", true);
    }
}
