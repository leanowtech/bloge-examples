package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Materializes non-recursive whole-flow and parent node APPLY_CASE Fixture shapes. */
public final class WholeFlowFixtureMaterializer {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /**
     * Creates revision one of a private Fixture Set for one exact immutable Flow Version.
     *
     * <p>A Case either contains one {@code SUBJECT + RETURN/INLINE}, or exactly one
     * {@code NODE + APPLY_CASE} for every parent node. The materializer validates the static
     * shape; {@link ParentFlowApplyCaseCompiler} resolves referenced authorities and proves that
     * every referenced Case terminates in one Subject Return.</p>
     */
    public GeneratedDefaultFixture generate(String fixtureSetId, ReusableFlowVersion version,
                                             FixtureSetCommand command) {
        return generate(fixtureSetId, 1, version, command);
    }

    /** Creates one exact private Fixture revision for one immutable Flow Version. */
    public GeneratedDefaultFixture generate(String fixtureSetId, int revision,
                                             ReusableFlowVersion version, FixtureSetCommand command) {
        Objects.requireNonNull(version, "version");
        return generate(fixtureSetId, revision, new FlowAuthority(
                version.subject(), version.contract(), version.graph(), true), command);
    }

    /** Creates a whole-subject Return Fixture for one exact saved Flow Draft. */
    public GeneratedDefaultFixture generate(String fixtureSetId, int revision,
                                             ReusableFlowDraft draft, FixtureSetCommand command) {
        Objects.requireNonNull(draft, "draft");
        return generate(fixtureSetId, revision, new FlowAuthority(
                draft.subject(), draft.contract(), draft.graph(), false), command);
    }

    private GeneratedDefaultFixture generate(String fixtureSetId, int revision,
                                              FlowAuthority authority, FixtureSetCommand command) {
        if (revision < 1) throw new IllegalArgumentException("Fixture revision is invalid");
        requireHeader(fixtureSetId, authority, command);
        List<FixtureSetCommand.Case> cases = command.cases();
        Set<String> caseIds = new HashSet<>();
        for (FixtureSetCommand.Case fixtureCase : cases) {
            requireCase(authority, fixtureCase, caseIds);
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

    private static void requireHeader(String fixtureSetId, FlowAuthority authority,
                                      FixtureSetCommand command) {
        if (fixtureSetId == null || !IDENTIFIER.matcher(fixtureSetId).matches() || authority == null
                || command == null || !FixtureSetCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.displayName() == null || command.displayName().isBlank()
                || command.displayName().length() > 200 || command.cases().isEmpty()
                || !authority.subject().equals(command.subject())) {
            throw new IllegalArgumentException("whole-flow Fixture command is invalid");
        }
    }

    private static void requireCase(FlowAuthority authority, FixtureSetCommand.Case fixtureCase,
                                    Set<String> caseIds) {
        if (fixtureCase == null || fixtureCase.caseId() == null
                || !IDENTIFIER.matcher(fixtureCase.caseId()).matches()
                || !caseIds.add(fixtureCase.caseId()) || fixtureCase.name() == null
                || fixtureCase.name().isBlank() || fixtureCase.name().length() > 200
                || !valid(authority.contract().input(), fixtureCase.input())) {
            throw new IllegalArgumentException("whole-flow Fixture Case is invalid");
        }
        if (fixtureCase.expect() != null
                && !valid(authority.contract().output(), fixtureCase.expect().output())) {
            throw new IllegalArgumentException("whole-flow Fixture Case control is invalid");
        }
        if (isSubjectReturn(authority, fixtureCase)) return;
        if (!authority.nodeControlsAllowed()) {
            throw new IllegalArgumentException("Flow Draft Fixture controls must target the whole subject");
        }
        requireParentApplyCase(authority, fixtureCase);
    }

    private static boolean isSubjectReturn(FlowAuthority authority,
                                           FixtureSetCommand.Case fixtureCase) {
        if (fixtureCase.controls().size() != 1) return false;
        FixtureSetCommand.Control control = fixtureCase.controls().getFirst();
        if (!(control.target() instanceof FixtureSetCommand.Target.Subject)
                || !(control.behavior() instanceof FixtureSetCommand.Behavior.Return returned)
                || !(returned.material() instanceof FixtureSetCommand.Material.Inline inline)
                || control.fidelity() != null
                && control.fidelity() != FixtureSetCommand.Fidelity.OUTPUT_LEVEL
                || !valid(authority.contract().output(), inline.value())) {
            return false;
        }
        return true;
    }

    private static void requireParentApplyCase(FlowAuthority authority,
                                               FixtureSetCommand.Case fixtureCase) {
        Set<String> nodeIds = authority.graph().nodes().stream()
                .map(com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand.Node::nodeId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> controlled = new HashSet<>();
        if (fixtureCase.controls().size() != nodeIds.size()) {
            throw new IllegalArgumentException("whole-flow Fixture Case control is invalid");
        }
        for (FixtureSetCommand.Control control : fixtureCase.controls()) {
            if (!(control.target() instanceof FixtureSetCommand.Target.Node target)
                    || !(control.behavior() instanceof FixtureSetCommand.Behavior.ApplyCase apply)
                    || control.fidelity() != null || !nodeIds.contains(target.nodeId())
                    || !controlled.add(target.nodeId()) || apply.fixtureSetId() == null
                    || !IDENTIFIER.matcher(apply.fixtureSetId()).matches() || apply.revision() < 1
                    || apply.caseId() == null || !IDENTIFIER.matcher(apply.caseId()).matches()) {
                throw new IllegalArgumentException("whole-flow Fixture Case control is invalid");
            }
        }
    }

    private static boolean valid(SchemaEnvelope schema, JsonNode value) {
        Objects.requireNonNull(schema, "schema");
        return VisualSchemaValidator.validateValue(
                schema, JSON.convertValue(value, Object.class), "/value").isEmpty();
    }

    private record FlowAuthority(FixtureSubjectRef subject, ReusableFlowCommand.Contract contract,
                                 ReusableFlowCommand.Graph graph, boolean nodeControlsAllowed) { }
}
