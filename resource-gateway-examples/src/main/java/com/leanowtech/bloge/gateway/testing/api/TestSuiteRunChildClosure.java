package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;

import java.util.ArrayList;
import java.util.List;

/** Derives and verifies the ordered child identity closure for every suite evidence generation. */
final class TestSuiteRunChildClosure {
    private TestSuiteRunChildClosure() {
    }

    static boolean matches(TestSuiteRunRecord record) {
        if (record == null || record.evidence() == null || record.attestation() == null) {
            return false;
        }
        List<ChildIdentity> expected = new ArrayList<>();
        if (record.evidence() instanceof TestSuiteRunEvidenceV5 mutation) {
            append(expected, "baseline", mutation.caseResults());
            mutation.mutantResults().forEach(result -> appendMutationCases(expected,
                    result.mutant().mutantId(), result.caseResults()));
        } else {
            append(expected, "", record.evidence().caseResults());
        }
        List<TestSuiteRunAttestation.ChildEvidenceRef> actual =
                record.attestation().childEvidenceRefs();
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            ChildIdentity identity = expected.get(index);
            TestSuiteRunAttestation.ChildEvidenceRef child = actual.get(index);
            if (!identity.caseId().equals(child.caseId())
                    || !identity.runId().equals(child.runId())) {
                return false;
            }
        }
        return true;
    }

    private static void append(
            List<ChildIdentity> target,
            String prefix,
            List<TestSuiteRunEvidence.CaseResult> results) {
        for (TestSuiteRunEvidence.CaseResult result : results) {
            if (!result.runId().isBlank()) {
                target.add(new ChildIdentity(prefix.isBlank()
                        ? result.caseId() : prefix + "/" + result.caseId(), result.runId()));
            }
        }
    }

    private static void appendMutationCases(
            List<ChildIdentity> target,
            String prefix,
            List<TestSuiteRunEvidenceV5.MutantCaseResult> results) {
        for (TestSuiteRunEvidenceV5.MutantCaseResult result : results) {
            if (!result.runId().isBlank()) {
                target.add(new ChildIdentity(prefix + "/" + result.caseId(), result.runId()));
            }
        }
    }

    private record ChildIdentity(String caseId, String runId) {
    }
}
