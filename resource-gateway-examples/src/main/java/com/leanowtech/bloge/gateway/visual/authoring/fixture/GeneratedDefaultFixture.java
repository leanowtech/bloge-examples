package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed result of materializing one Resource revision's selected examples. */
public record GeneratedDefaultFixture(FixtureSetView view, FixtureSetSaveReceipt receipt,
                                      FixtureSetSummary summary, List<CaseMapping> caseMappings) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public GeneratedDefaultFixture {
        caseMappings = caseMappings == null ? List.of() : List.copyOf(caseMappings);
        if (view == null || receipt == null || summary == null
                || !FixtureSetView.SCHEMA_VERSION.equals(view.schemaVersion())
                || !FixtureSetSaveReceipt.SCHEMA_VERSION.equals(receipt.schemaVersion())
                || !FixtureSetSummary.SCHEMA_VERSION.equals(summary.schemaVersion())
                || !identifier(view.fixtureSetId()) || !fingerprint(view.fingerprint())
                || view.revision() < 1 || view.statusRevision() != 1
                || view.status() != FixtureSetView.Status.PRIVATE_DRAFT || view.cases().isEmpty()
                || !view.fixtureSetId().equals(receipt.fixtureSetId())
                || view.revision() != receipt.revision()
                || !view.fingerprint().equals(receipt.fingerprint())
                || !view.subject().equals(receipt.subject())
                || !view.fixtureSetId().equals(summary.fixtureSetId())
                || view.revision() != summary.revision()
                || !view.fingerprint().equals(summary.fingerprint())
                || !Objects.equals(view.displayName(), summary.displayName())
                || !view.subject().equals(summary.subject())
                || view.status() != receipt.status() || view.status() != summary.status()
                || view.statusRevision() != receipt.statusRevision()
                || view.statusRevision() != summary.statusRevision()
                || !exactCases(view, receipt, summary, caseMappings)) {
            throw new IllegalArgumentException("generated default fixture is incomplete");
        }
        requireContentFingerprint(view);
    }

    /** Keeps the embedded full View out of diagnostics. */
    @Override public String toString() {
        return "GeneratedDefaultFixture[fixtureSetId=" + view.fixtureSetId() + ", revision="
                + view.revision() + ", cases=" + caseMappings.size() + "]";
    }

    private static void requireContentFingerprint(FixtureSetView view) {
        if (!view.fingerprint().equals(FixtureSetFingerprints.of(
                view.displayName(), view.subject(), view.cases()))) {
            throw new IllegalArgumentException("generated default fixture fingerprint drift");
        }
    }

    /** Stable mapping preserved in the compound Resource receipt. */
    public record CaseMapping(String exampleName, String caseId) { }

    private static boolean exactCases(FixtureSetView view, FixtureSetSaveReceipt receipt,
                                      FixtureSetSummary summary, List<CaseMapping> mappings) {
        List<FixtureSetCommand.Case> cases = view.cases();
        if (receipt.caseIds().size() != cases.size() || summary.cases().size() != cases.size()
                || mappings.size() != cases.size()) return false;
        Set<String> exampleNames = new HashSet<>();
        Set<String> caseIds = new HashSet<>();
        for (int index = 0; index < cases.size(); index++) {
            FixtureSetCommand.Case fixtureCase = cases.get(index);
            FixtureSetSummary.CaseSummary caseSummary = summary.cases().get(index);
            CaseMapping mapping = mappings.get(index);
            if (!identifier(fixtureCase.caseId()) || !fixtureCase.caseId().equals(receipt.caseIds().get(index))
                    || !fixtureCase.caseId().equals(caseSummary.caseId())
                    || !Objects.equals(fixtureCase.name(), caseSummary.name())
                    || !fixtureCase.caseId().equals(mapping.caseId()) || !identifier(mapping.exampleName())
                    || !exampleNames.add(mapping.exampleName()) || !caseIds.add(mapping.caseId())) return false;
        }
        return true;
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }
}
