package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ComponentSimulationAuthorityV2;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Materializes private, whole-subject Fixture revisions for exact executable components. */
public final class ComponentFixtureSetMaterializer {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /**
     * Creates one private Fixture revision for an exact Operator or built-in Function authority.
     *
     * <p>Component Fixtures control only the complete subject. Node controls and APPLY_CASE belong
     * to reusable Flows and are rejected here. Inline Return material and optional expected output
     * are validated against the exact compiler-owned component contracts.</p>
     */
    public GeneratedDefaultFixture generate(
            String fixtureSetId, int revision, FixtureSubjectRef subject,
            ComponentSimulationAuthorityV2.ComponentContract contract,
            FixtureSetCommand command) {
        requireHeader(fixtureSetId, revision, subject, contract, command);
        Set<String> caseIds = new HashSet<>();
        for (FixtureSetCommand.Case fixtureCase : command.cases()) {
            requireCase(contract, fixtureCase, caseIds);
        }
        String fingerprint = FixtureSetFingerprints.of(
                command.displayName(), command.subject(), command.cases());
        FixtureSetView view = new FixtureSetView(
                FixtureSetView.SCHEMA_VERSION, fixtureSetId, revision, fingerprint, 1,
                command.displayName(), subject, command.cases(), FixtureSetView.Status.PRIVATE_DRAFT);
        List<String> orderedCaseIds = command.cases().stream()
                .map(FixtureSetCommand.Case::caseId).toList();
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(
                FixtureSetSaveReceipt.SCHEMA_VERSION, fixtureSetId, revision, fingerprint,
                subject, orderedCaseIds, FixtureSetView.Status.PRIVATE_DRAFT, 1);
        FixtureSetSummary summary = new FixtureSetSummary(
                FixtureSetSummary.SCHEMA_VERSION, fixtureSetId, revision, fingerprint,
                command.displayName(), subject, command.cases().stream()
                .map(value -> new FixtureSetSummary.CaseSummary(value.caseId(), value.name()))
                .toList(), FixtureSetView.Status.PRIVATE_DRAFT, 1);
        return new GeneratedDefaultFixture(view, receipt, summary, orderedCaseIds.stream()
                .map(value -> new GeneratedDefaultFixture.CaseMapping(value, value)).toList());
    }

    private static void requireHeader(
            String fixtureSetId, int revision, FixtureSubjectRef subject,
            ComponentSimulationAuthorityV2.ComponentContract contract,
            FixtureSetCommand command) {
        if (fixtureSetId == null || !FixtureSubjectRef.IDENTIFIER.matcher(fixtureSetId).matches()
                || revision < 1 || contract == null || command == null
                || !FixtureSetCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.displayName() == null || command.displayName().isBlank()
                || command.displayName().length() > 200 || command.cases().isEmpty()
                || !subject.equals(command.subject())
                || !(subject instanceof FixtureSubjectRef.OperatorVersion
                || subject instanceof FixtureSubjectRef.BuiltinFunctionVersion)) {
            throw new IllegalArgumentException("component Fixture command is invalid");
        }
    }

    private static void requireCase(
            ComponentSimulationAuthorityV2.ComponentContract contract,
            FixtureSetCommand.Case fixtureCase, Set<String> caseIds) {
        if (fixtureCase == null || fixtureCase.caseId() == null
                || !FixtureSubjectRef.IDENTIFIER.matcher(fixtureCase.caseId()).matches()
                || !caseIds.add(fixtureCase.caseId()) || fixtureCase.name() == null
                || fixtureCase.name().isBlank() || fixtureCase.name().length() > 200
                || !valid(contract.input(), fixtureCase.input())
                || fixtureCase.controls().size() != 1
                || !(fixtureCase.controls().getFirst().target()
                instanceof FixtureSetCommand.Target.Subject)) {
            throw new IllegalArgumentException("component Fixture Case is invalid");
        }
        FixtureSetCommand.Control control = fixtureCase.controls().getFirst();
        if (control.behavior() instanceof FixtureSetCommand.Behavior.ApplyCase
                || control.fidelity() != null
                && control.fidelity() != FixtureSetCommand.Fidelity.OUTPUT_LEVEL) {
            throw new IllegalArgumentException("component Fixture behavior is invalid");
        }
        if (control.behavior() instanceof FixtureSetCommand.Behavior.Return returned
                && (!(returned.material() instanceof FixtureSetCommand.Material.Inline inline)
                || !valid(contract.output(), inline.value()))) {
            throw new IllegalArgumentException("component Fixture output is invalid");
        }
        if (fixtureCase.expect() != null
                && !valid(contract.output(), fixtureCase.expect().output())) {
            throw new IllegalArgumentException("component Fixture expectation is invalid");
        }
    }

    private static boolean valid(SchemaEnvelope schema, JsonNode value) {
        Objects.requireNonNull(schema, "schema");
        return VisualSchemaValidator.validateValue(
                schema, JSON.convertValue(value, Object.class), "/value").isEmpty();
    }
}
