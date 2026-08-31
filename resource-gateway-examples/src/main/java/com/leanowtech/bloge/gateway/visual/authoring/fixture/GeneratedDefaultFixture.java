package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import java.util.List;

/** Closed result of materializing one Resource revision's selected examples. */
public record GeneratedDefaultFixture(FixtureSetView view, FixtureSetSaveReceipt receipt,
                                      FixtureSetSummary summary, List<CaseMapping> caseMappings) {
    public GeneratedDefaultFixture {
        if (view == null || receipt == null || summary == null) {
            throw new IllegalArgumentException("generated default fixture is incomplete");
        }
        caseMappings = caseMappings == null ? List.of() : List.copyOf(caseMappings);
    }

    /** Keeps the embedded full View out of diagnostics. */
    @Override public String toString() {
        return "GeneratedDefaultFixture[fixtureSetId=" + view.fixtureSetId() + ", revision="
                + view.revision() + ", cases=" + caseMappings.size() + "]";
    }

    /** Stable mapping preserved in the compound Resource receipt. */
    public record CaseMapping(String exampleName, String caseId) { }
}
