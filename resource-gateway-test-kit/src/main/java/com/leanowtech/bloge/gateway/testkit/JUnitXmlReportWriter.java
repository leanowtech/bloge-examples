package com.leanowtech.bloge.gateway.testkit;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Writes payload-free test-run summaries as CI-compatible JUnit XML. */
public final class JUnitXmlReportWriter {

    /**
     * Report counters and process-exit projection.
     *
     * @param tests emitted testcase count
     * @param failures emitted failure count
     */
    public record Report(int tests, int failures) {
        /**
         * Projects the report outcome to a conventional process exit code.
         *
         * @return zero when every reported run passed, otherwise one
         */
        public int exitCode() {
            return failures == 0 ? 0 : 1;
        }
    }

    private JUnitXmlReportWriter() {
    }

    /**
     * Writes one testcase per run. Only ids, states, and fingerprints are emitted; diagnostics,
     * node input/output, and the raw response are intentionally excluded.
     *
     * @param output destination XML file
     * @param suiteName CI-visible suite name
     * @param runs ordered run results
     * @return emitted report counters
     * @throws IOException when directories or XML cannot be written
     */
    public static Report write(Path output, String suiteName, List<TestRun> runs) throws IOException {
        Objects.requireNonNull(output, "output");
        List<TestRun> values = runs == null ? List.of() : List.copyOf(runs);
        int failures = (int) values.stream().filter(run -> !run.passed()).count();
        writeDocument(output, suiteName, values.size(), failures, xml -> {
            for (int index = 0; index < values.size(); index++) {
                writeRun(xml, values.get(index), index);
            }
        });
        return new Report(values.size(), failures);
    }

    /**
     * Writes payload-free governed-suite testcases plus one fail-closed aggregate gate. Mutation
     * suites additionally emit one informational testcase per mutant; a survivor is not itself a
     * JUnit failure because the immutable score policy, rather than an individual classification,
     * owns the gate verdict. Business suites require structural coverage; schema-admission suites
     * require exact validator matches and never imply business execution. Promotion eligibility is
     * optional only when the caller explicitly disables it.
     *
     * @param output destination XML file
     * @param run immutable suite-run projection
     * @param requirePromotionEligible whether {@code ELIGIBLE} is required for a zero exit code
     * @return emitted report counters
     * @throws IOException when directories or XML cannot be written
     */
    public static Report writeSuite(Path output, TestSuiteRun run,
                                    boolean requirePromotionEligible) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(run, "run");
        int caseFailures = (int) run.caseResults().stream().filter(result -> !result.passed()).count();
        boolean gatePassed = run.gateFailureCodes(requirePromotionEligible).isEmpty();
        int failures = caseFailures + (gatePassed ? 0 : 1);
        boolean mutation = run.evaluationMode() == TestSuiteRun.EvaluationMode.PURE_DSL_MUTATION;
        int mutantTests = mutation ? run.mutantResults().size() : 0;
        writeDocument(output, "resource-gateway suite " + run.suiteId(),
                run.caseResults().size() + mutantTests + 1, failures, xml -> {
            for (TestSuiteRun.CaseResult result : run.caseResults()) {
                writeSuiteCase(xml, result, run.evaluationMode());
            }
            if (mutation) {
                for (TestSuiteRun.MutantResult result : run.mutantResults()) {
                    writeMutationResult(xml, result);
                }
            }
            writeSuiteGate(xml, run, requirePromotionEligible, gatePassed);
        });
        return new Report(run.caseResults().size() + mutantTests + 1, failures);
    }

    /**
     * Writes one payload-free testcase per stability case, one pinned-trust verification row, and
     * one fail-closed aggregate gate. The aggregate passes only when the analysis is stable,
     * promotion eligible, and independently verified against the caller's trust policy.
     *
     * @param output destination XML file
     * @param run immutable stability analysis projection
     * @param verification offline signature and key-policy verification result
     * @return emitted report counters
     * @throws IOException when directories or XML cannot be written
     */
    public static Report writeStability(
            Path output,
            TestSuiteStabilityRun run,
            TestSuiteStabilityEvidenceVerifier.VerificationResult verification)
            throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(verification, "verification");
        boolean identityMatches = run.stabilityRunId().equals(verification.stabilityRunId());
        boolean trustVerified = identityMatches && verification.verified();
        boolean gatePassed = trustVerified && run.stable() && run.promotionEligible();
        int caseFailures = (int) run.caseResults().stream()
                .filter(result -> result.status()
                        != TestSuiteStabilityRun.CaseStatus.STABLE_PASS)
                .count();
        int failures = caseFailures + (trustVerified ? 0 : 1) + (gatePassed ? 0 : 1);
        int tests = run.caseResults().size() + 2;
        writeDocument(output, "resource-gateway stability " + run.suiteRef().suiteId(),
                tests, failures, xml -> {
            for (TestSuiteStabilityRun.CaseStabilityResult result : run.caseResults()) {
                writeStabilityCase(xml, result);
            }
            writeStabilityVerification(xml, run, verification, trustVerified);
            writeStabilityGate(xml, run, verification, trustVerified, gatePassed);
        });
        return new Report(tests, failures);
    }

    /**
     * Writes a one-test infrastructure failure report when the CI adapter cannot obtain suite
     * evidence. The caller must provide an already sanitized stable code and summary.
     *
     * @param output destination XML file
     * @param suiteName requested suite name
     * @param code stable failure code
     * @param summary bounded payload-free summary
     * @return one-test failing report
     * @throws IOException when directories or XML cannot be written
     */
    public static Report writeInfrastructureFailure(Path output, String suiteName,
                                                    String code, String summary) throws IOException {
        Objects.requireNonNull(output, "output");
        writeDocument(output, suiteName, 1, 1, xml -> {
            xml.writeStartElement("testcase");
            xml.writeAttribute("classname", "resource-gateway.governed-suite");
            xml.writeAttribute("name", "suite-infrastructure");
            xml.writeStartElement("failure");
            xml.writeAttribute("type", bounded(code, 255));
            xml.writeAttribute("message", bounded(summary, 512));
            xml.writeCharacters("The suite adapter did not obtain governed terminal evidence.");
            xml.writeEndElement();
            xml.writeEndElement();
        });
        return new Report(1, 1);
    }

    private static void writeDocument(Path output, String suiteName, int tests, int failures,
                                      XmlBody body) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        XMLOutputFactory factory = XMLOutputFactory.newFactory();
        try (var stream = Files.newOutputStream(output)) {
            XMLStreamWriter xml = factory.createXMLStreamWriter(stream, StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("testsuite");
            xml.writeAttribute("name", bounded(suiteName, 512));
            xml.writeAttribute("tests", Integer.toString(tests));
            xml.writeAttribute("failures", Integer.toString(failures));
            xml.writeAttribute("errors", "0");
            xml.writeAttribute("skipped", "0");
            body.write(xml);
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            xml.close();
        } catch (XMLStreamException failure) {
            throw new IOException("Unable to write Resource Gateway JUnit XML", failure);
        }
    }

    @FunctionalInterface
    private interface XmlBody {
        void write(XMLStreamWriter xml) throws XMLStreamException;
    }

    private static void writeRun(XMLStreamWriter xml, TestRun run, int index) throws XMLStreamException {
        xml.writeStartElement("testcase");
        xml.writeAttribute("classname", "resource-gateway.graph-contract");
        xml.writeAttribute("name", bounded(run.runId().isBlank() ? "run-" + index : run.runId(), 512));
        if (!run.passed()) {
            xml.writeStartElement("failure");
            xml.writeAttribute("type", "ResourceGateway." + run.status());
            xml.writeAttribute("message", "Run " + bounded(run.runId(), 256)
                    + " completed with status " + run.status());
            xml.writeCharacters("Inspect the authorized testing API using runId="
                    + bounded(run.runId(), 256) + ".");
            xml.writeEndElement();
        }
        xml.writeStartElement("system-out");
        xml.writeCharacters("runId=" + bounded(run.runId(), 256)
                + "; status=" + run.status()
                + "; evidenceClass=" + run.evidenceClass()
                + "; targetFingerprint=" + bounded(run.targetFingerprint(), 80)
                + "; fixtureBundleFingerprint=" + bounded(run.fixtureBundleFingerprint(), 80)
                + "; planFingerprint=" + bounded(run.planFingerprint(), 80));
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void writeSuiteCase(XMLStreamWriter xml, TestSuiteRun.CaseResult result,
                                       TestSuiteRun.EvaluationMode evaluationMode)
            throws XMLStreamException {
        xml.writeStartElement("testcase");
        xml.writeAttribute("classname", "resource-gateway.governed-suite");
        xml.writeAttribute("name", bounded("case-" + result.caseId(), 512));
        if (!result.passed()) {
            xml.writeStartElement("failure");
            xml.writeAttribute("type", "ResourceGateway." + result.status());
            String code = result.diagnosticCode().isBlank() ? result.status().name() : result.diagnosticCode();
            xml.writeAttribute("message", bounded(code, 512));
            if (evaluationMode == TestSuiteRun.EvaluationMode.SCHEMA_ADMISSION) {
                xml.writeCharacters("Inspect the authorized suite admission result using caseId="
                        + bounded(result.caseId(), 256) + ".");
            } else {
                xml.writeCharacters("Inspect the authorized testing API using child runId="
                        + bounded(result.runId(), 256) + ".");
            }
            xml.writeEndElement();
        }
        xml.writeStartElement("system-out");
        xml.writeCharacters("evaluationMode=" + evaluationMode
                + "; caseType=" + bounded(result.caseType(), 64)
                + "; status=" + result.status()
                + "; runId=" + bounded(result.runId(), 256)
                + "; evidenceStatus=" + bounded(result.evidenceStatus(), 64)
                + "; evidenceClass=" + bounded(result.evidenceClass(), 64)
                + "; fixture=" + bounded(result.fixtureBundleId(), 256) + "@" + result.fixtureRevision()
                + "; fixtureFingerprint=" + bounded(result.fixtureFingerprint(), 80)
                + "; assertions=" + result.assertionsPassed() + "/" + result.assertionsEvaluated());
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void writeMutationResult(XMLStreamWriter xml,
                                            TestSuiteRun.MutantResult result)
            throws XMLStreamException {
        TestSuiteRun.MutantRef mutant = result.mutant();
        xml.writeStartElement("testcase");
        xml.writeAttribute("classname", "resource-gateway.mutation-suite");
        xml.writeAttribute("name", bounded(mutant.mutantId(), 512));
        xml.writeStartElement("system-out");
        xml.writeCharacters("mutantId=" + bounded(mutant.mutantId(), 256)
                + "; kind=" + mutant.kind()
                + "; status=" + result.status()
                + "; targetFingerprint=" + bounded(mutant.mutantTargetFingerprint(), 80)
                + "; cases=" + result.caseResults().size()
                + "; killingCases=" + result.killingCaseIds().size());
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void writeSuiteGate(XMLStreamWriter xml, TestSuiteRun run,
                                       boolean requirePromotionEligible, boolean gatePassed)
            throws XMLStreamException {
        xml.writeStartElement("testcase");
        xml.writeAttribute("classname", "resource-gateway.governed-suite");
        xml.writeAttribute("name", "suite-gate");
        if (!gatePassed) {
            String codes = String.join(",", run.gateFailureCodes(requirePromotionEligible));
            xml.writeStartElement("failure");
            xml.writeAttribute("type", "ResourceGateway.SUITE_GATE_BLOCKED");
            xml.writeAttribute("message", bounded(codes, 512));
            xml.writeCharacters("Inspect the authorized testing API using suiteRunId="
                    + bounded(run.suiteRunId(), 256) + ".");
            xml.writeEndElement();
        }
        xml.writeStartElement("system-out");
        xml.writeCharacters("suiteRunId=" + bounded(run.suiteRunId(), 256)
                + "; evaluationMode=" + run.evaluationMode()
                + "; status=" + run.status()
                + "; suite=" + bounded(run.suiteId(), 256) + "@" + run.suiteRevision()
                + "; suiteFingerprint=" + bounded(run.suiteFingerprint(), 80)
                + "; target=" + bounded(run.targetKind(), 32) + ":" + bounded(run.targetId(), 256)
                + "; targetFingerprint=" + bounded(run.targetFingerprint(), 80)
                + "; evidenceFingerprint=" + bounded(run.evidenceFingerprint(), 80)
                + "; coverage=" + run.coverageStatus()
                + "; admissionCoverage=" + run.admissionCoverage()
                .map(value -> value.status().name()).orElse("NOT_APPLICABLE")
                + "; promotion=" + run.promotionStatus()
                + "; promotionRequired=" + requirePromotionEligible);
        var mutationScore = run.mutationScore();
        if (mutationScore.isPresent()) {
            TestSuiteRun.MutationScore score = mutationScore.orElseThrow();
            xml.writeCharacters("; mutationBaseline=" + run.mutationBaselineStatus()
                    .map(Enum::name).orElse("UNAVAILABLE")
                    + "; mutationScore=" + score.scoreBasisPoints()
                    + "; mutationScoreStatus=" + score.status()
                    + "; killed=" + score.killedMutants()
                    + "; survived=" + score.survivedMutants()
                    + "; inconclusive=" + score.inconclusiveMutants()
                    + "; unclassified=" + score.unclassifiedMutants());
        }
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void writeStabilityCase(
            XMLStreamWriter xml,
            TestSuiteStabilityRun.CaseStabilityResult result) throws XMLStreamException {
        xml.writeStartElement("testcase");
        xml.writeAttribute("classname", "resource-gateway.stability");
        xml.writeAttribute("name", bounded("case-" + result.caseId(), 512));
        if (result.status() != TestSuiteStabilityRun.CaseStatus.STABLE_PASS) {
            String message = result.diagnosticCodes().isEmpty()
                    ? result.status().name() : String.join(",", result.diagnosticCodes());
            xml.writeStartElement("failure");
            xml.writeAttribute("type", "ResourceGateway." + result.status());
            xml.writeAttribute("message", bounded(message, 512));
            xml.writeCharacters("Inspect the authorized stability evidence using caseId="
                    + bounded(result.caseId(), 256) + ".");
            xml.writeEndElement();
        }
        long verified = result.observations().stream().filter(value ->
                value.status() == TestSuiteStabilityRun.ObservationStatus.VERIFIED).count();
        xml.writeStartElement("system-out");
        xml.writeCharacters("caseType=" + bounded(result.caseType(), 64)
                + "; status=" + result.status()
                + "; fixture=" + bounded(result.fixtureRef().fixtureBundleId(), 256)
                + "@" + result.fixtureRef().revision()
                + "; fixtureFingerprint=" + bounded(result.fixtureRef().fingerprint(), 80)
                + "; observations=" + result.observations().size()
                + "; verifiedObservations=" + verified
                + "; distinctVerifiedOutcomes=" + result.distinctVerifiedOutcomes());
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void writeStabilityVerification(
            XMLStreamWriter xml,
            TestSuiteStabilityRun run,
            TestSuiteStabilityEvidenceVerifier.VerificationResult verification,
            boolean trustVerified) throws XMLStreamException {
        xml.writeStartElement("testcase");
        xml.writeAttribute("classname", "resource-gateway.stability");
        xml.writeAttribute("name", "stability-attestation");
        if (!trustVerified) {
            String reason = run.stabilityRunId().equals(verification.stabilityRunId())
                    ? verification.reasonCode() : "STABILITY_VERIFICATION_IDENTITY_MISMATCH";
            xml.writeStartElement("failure");
            xml.writeAttribute("type", "ResourceGateway.STABILITY_ATTESTATION_UNVERIFIED");
            xml.writeAttribute("message", bounded(reason, 512));
            xml.writeCharacters("The stability signature or pinned key policy did not verify.");
            xml.writeEndElement();
        }
        xml.writeStartElement("system-out");
        xml.writeCharacters("stabilityRunId=" + bounded(run.stabilityRunId(), 256)
                + "; verification=" + verification.outcome()
                + "; reason=" + bounded(verification.reasonCode(), 255)
                + "; keyId=" + bounded(verification.keyId(), 512)
                + "; evidenceFingerprint=" + bounded(run.evidenceFingerprint(), 80));
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void writeStabilityGate(
            XMLStreamWriter xml,
            TestSuiteStabilityRun run,
            TestSuiteStabilityEvidenceVerifier.VerificationResult verification,
            boolean trustVerified,
            boolean gatePassed) throws XMLStreamException {
        xml.writeStartElement("testcase");
        xml.writeAttribute("classname", "resource-gateway.stability");
        xml.writeAttribute("name", "stability-gate");
        if (!gatePassed) {
            List<String> reasons = new ArrayList<>();
            if (!trustVerified) {
                reasons.add(run.stabilityRunId().equals(verification.stabilityRunId())
                        ? verification.reasonCode() : "STABILITY_VERIFICATION_IDENTITY_MISMATCH");
            }
            if (!run.stable()) {
                reasons.add("STABILITY_" + run.status());
            }
            if (!run.promotionEligible()) {
                if (!run.sourcePromotionClosureAvailable()) {
                    reasons.add("SOURCE_PROMOTION_CLOSURE_UNAVAILABLE");
                }
                reasons.addAll(run.promotion().reasons());
            }
            xml.writeStartElement("failure");
            xml.writeAttribute("type", "ResourceGateway.STABILITY_GATE_BLOCKED");
            xml.writeAttribute("message", bounded(String.join(",", reasons), 512));
            xml.writeCharacters("Inspect the authorized stability evidence using stabilityRunId="
                    + bounded(run.stabilityRunId(), 256) + ".");
            xml.writeEndElement();
        }
        long verifiedAttempts = run.attempts().stream().filter(value ->
                value.status() == TestSuiteStabilityRun.AttemptStatus.VERIFIED).count();
        xml.writeStartElement("system-out");
        xml.writeCharacters("stabilityRunId=" + bounded(run.stabilityRunId(), 256)
                + "; status=" + run.status()
                + "; suite=" + bounded(run.suiteRef().suiteId(), 256)
                + "@" + run.suiteRef().revision()
                + "; suiteFingerprint=" + bounded(run.suiteRef().fingerprint(), 80)
                + "; target=" + bounded(run.target().kind(), 32)
                + ":" + bounded(run.target().id(), 256)
                + "; targetFingerprint=" + bounded(run.target().fingerprint(), 80)
                + "; attempts=" + run.requestedAttempts()
                + "; verifiedAttempts=" + verifiedAttempts
                + "; promotion=" + run.promotion().status()
                + "; quarantine=" + run.quarantine().status()
                + "; trustVerified=" + trustVerified);
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
