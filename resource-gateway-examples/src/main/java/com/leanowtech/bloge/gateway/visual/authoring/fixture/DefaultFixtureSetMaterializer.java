package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceSaveCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Materializes selected named Resource examples into one immutable private Fixture Set. */
public final class DefaultFixtureSetMaterializer {
    private final ObjectMapper mapper;

    /** @param mapper mapper used only for canonical Fixture content fingerprinting */
    public DefaultFixtureSetMaterializer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    }

    /**
     * Creates a deterministic new Fixture Set revision for one exact Resource revision.
     * The requested example order is preserved in Cases and the compound receipt mapping.
     */
    public GeneratedDefaultFixture generate(ApiResourceSpec resource,
                                            ApiResourceSaveCommand.DefaultFixture.FromExamples request) {
        if (resource == null || request == null || request.displayName() == null
                || request.displayName().isBlank() || request.displayName().length() > 200
                || request.exampleNames().isEmpty()) {
            throw new IllegalArgumentException("default fixture request is invalid");
        }
        Map<String, ApiResourceCommand.Example> examples = new LinkedHashMap<>();
        for (ApiResourceCommand.Example example : resource.examples()) {
            if (example == null || examples.putIfAbsent(example.name(), example) != null) {
                throw new IllegalArgumentException("resource examples are ambiguous");
            }
        }
        HashSet<String> selected = new HashSet<>();
        List<FixtureSetCommand.Case> cases = request.exampleNames().stream().map(name -> {
            if (name == null || !selected.add(name) || !examples.containsKey(name)) {
                throw new IllegalArgumentException("default fixture example selection is invalid");
            }
            ApiResourceCommand.Example example = examples.get(name);
            FixtureSetCommand.Control control = new FixtureSetCommand.Control(
                    FixtureSetCommand.Target.subject(),
                    FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(example.output())), null);
            return new FixtureSetCommand.Case(name, name, example.input(), List.of(control), null);
        }).toList();
        FixtureSubjectRef subject = FixtureSubjectRef.apiResource(resource.ref());
        FixtureSetCommand command = new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION,
                request.displayName(), subject, cases);
        String fixtureSetId = defaultId(resource);
        String fingerprint = AuthoringFingerprints.of(mapper.valueToTree(command));
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, fixtureSetId, 1,
                fingerprint, 1, request.displayName(), subject, cases, FixtureSetView.Status.PRIVATE_DRAFT);
        List<String> caseIds = cases.stream().map(FixtureSetCommand.Case::caseId).toList();
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION,
                fixtureSetId, 1, fingerprint, subject, caseIds, FixtureSetView.Status.PRIVATE_DRAFT, 1);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION, fixtureSetId, 1,
                fingerprint, request.displayName(), subject, cases.stream()
                .map(value -> new FixtureSetSummary.CaseSummary(value.caseId(), value.name())).toList(),
                FixtureSetView.Status.PRIVATE_DRAFT, 1);
        List<GeneratedDefaultFixture.CaseMapping> mappings = request.exampleNames().stream()
                .map(name -> new GeneratedDefaultFixture.CaseMapping(name, name)).toList();
        return new GeneratedDefaultFixture(view, receipt, summary, mappings);
    }

    private static String defaultId(ApiResourceSpec resource) {
        String suffix = ":r" + resource.revision();
        String candidate = resource.resourceId() + suffix;
        if (candidate.length() <= 128) return candidate;
        return "resource:" + resource.fingerprint().substring("sha256:".length()) + suffix;
    }
}
