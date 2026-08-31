package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Materializes the only non-recursive whole-flow Fixture shape accepted by simulation. */
public final class WholeFlowFixtureMaterializer {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /**
     * Creates revision one of a private Fixture Set for one exact immutable Flow Version.
     *
     * <p>Each Case must contain exactly one {@code SUBJECT + RETURN/INLINE} control. Node
     * controls, nested APPLY_CASE, real execution and transport fidelity are deliberately
     * rejected, so applying this Case never executes or recursively expands the Flow graph.</p>
     */
    public GeneratedDefaultFixture generate(String fixtureSetId, ReusableFlowVersion version,
                                             FixtureSetCommand command) {
        return generate(fixtureSetId, 1, version, command);
    }

    /** Creates one exact private Fixture revision for one immutable Flow Version. */
    public GeneratedDefaultFixture generate(String fixtureSetId, int revision,
                                             ReusableFlowVersion version, FixtureSetCommand command) {
        if (revision < 1) throw new IllegalArgumentException("Fixture revision is invalid");
        requireHeader(fixtureSetId, version, command);
        List<FixtureSetCommand.Case> cases = command.cases();
        Set<String> caseIds = new HashSet<>();
        for (FixtureSetCommand.Case fixtureCase : cases) {
            requireCase(version, fixtureCase, caseIds);
        }
        String fingerprint = FixtureSetFingerprints.of(
                command.displayName(), command.subject(), cases);
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, fixtureSetId, revision,
                fingerprint, 1, command.displayName(), command.subject(), cases,
                FixtureSetView.Status.PRIVATE_DRAFT);
        List<String> orderedCaseIds = cases.stream().map(FixtureSetCommand.Case::caseId).toList();
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION,
                fixtureSetId, revision, fingerprint, command.subject(), orderedCaseIds,
                FixtureSetView.Status.PRIVATE_DRAFT, 1);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                fixtureSetId, revision, fingerprint, command.displayName(), command.subject(),
                cases.stream().map(value -> new FixtureSetSummary.CaseSummary(
                        value.caseId(), value.name())).toList(), FixtureSetView.Status.PRIVATE_DRAFT, 1);
        List<GeneratedDefaultFixture.CaseMapping> identityMappings = cases.stream()
                .map(value -> new GeneratedDefaultFixture.CaseMapping(value.caseId(), value.caseId()))
                .toList();
        return new GeneratedDefaultFixture(view, receipt, summary, identityMappings);
    }

    private static void requireHeader(String fixtureSetId, ReusableFlowVersion version,
                                      FixtureSetCommand command) {
        if (fixtureSetId == null || !IDENTIFIER.matcher(fixtureSetId).matches() || version == null
                || command == null || !FixtureSetCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.displayName() == null || command.displayName().isBlank()
                || command.displayName().length() > 200 || command.cases().isEmpty()
                || !(command.subject() instanceof FixtureSubjectRef.FlowVersion subject)
                || !version.subject().equals(subject)) {
            throw new IllegalArgumentException("whole-flow Fixture command is invalid");
        }
    }

    private static void requireCase(ReusableFlowVersion version, FixtureSetCommand.Case fixtureCase,
                                    Set<String> caseIds) {
        if (fixtureCase == null || fixtureCase.caseId() == null
                || !IDENTIFIER.matcher(fixtureCase.caseId()).matches()
                || !caseIds.add(fixtureCase.caseId()) || fixtureCase.name() == null
                || fixtureCase.name().isBlank() || fixtureCase.name().length() > 200
                || fixtureCase.controls().size() != 1
                || !valid(version.contract().input(), fixtureCase.input())) {
            throw new IllegalArgumentException("whole-flow Fixture Case is invalid");
        }
        FixtureSetCommand.Control control = fixtureCase.controls().getFirst();
        if (!(control.target() instanceof FixtureSetCommand.Target.Subject)
                || !(control.behavior() instanceof FixtureSetCommand.Behavior.Return returned)
                || !(returned.material() instanceof FixtureSetCommand.Material.Inline inline)
                || control.fidelity() != null && control.fidelity() != FixtureSetCommand.Fidelity.OUTPUT_LEVEL
                || !valid(version.contract().output(), inline.value())
                || fixtureCase.expect() != null
                && !valid(version.contract().output(), fixtureCase.expect().output())) {
            throw new IllegalArgumentException("whole-flow Fixture Case control is invalid");
        }
    }

    private static boolean valid(SchemaEnvelope schema, JsonNode value) {
        Objects.requireNonNull(schema, "schema");
        return VisualSchemaValidator.validateValue(
                schema, JSON.convertValue(value, Object.class), "/value").isEmpty();
    }
}
