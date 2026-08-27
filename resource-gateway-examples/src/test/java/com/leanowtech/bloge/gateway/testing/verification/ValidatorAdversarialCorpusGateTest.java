package com.leanowtech.bloge.gateway.testing.verification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorAdversarialCorpusGateTest {
    private static final String TENANT = "tenant-a";
    private static final String PURPOSE = "validator-adversarial-corpus";
    private static final String SOURCE = fp('a');
    private static final String POLICY = fp('b');
    private static final String GOLDEN = fp('c');
    private static final String AUTHORITY_RECEIPT = fp('d');
    private static final String PAYLOAD_CANARY = "credential-payload-canary-free-text-identity";

    @Test
    void standardCorpusClosesAllSixCasesWithIndependentReceiptAuthority() {
        Fixture fixture = fixture();

        ValidatorAdversarialGateReport report = fixture.gate.evaluate(
                fixture.corpus, fixture.context, fixture.receipts, fixture.authority);

        assertThat(report.status()).isEqualTo(ValidatorAdversarialGateStatus.PASSED);
        assertThat(report.cases()).hasSize(6)
                .allSatisfy(result -> assertThat(result.status())
                        .isEqualTo(ValidatorAdversarialCaseStatus.REJECTION_VERIFIED));
        assertThat(report.cases()).extracting(ValidatorAdversarialGateReport.CaseResult::caseCode)
                .containsExactly(Arrays.stream(ValidatorAdversarialCaseCode.values())
                        .map(ValidatorAdversarialCaseCode::code).sorted().toArray(String[]::new));
        assertThat(fixture.authority.verificationCalls()).isEqualTo(6);
    }

    @Test
    void reportFingerprintIsVerifiableAndMissingAuthorityFailsClosed() {
        Fixture fixture = fixture();
        ValidatorAdversarialGateReport report = evaluate(fixture, fixture.receipts, fixture.context);

        assertThat(ValidatorAdversarialGateReport.computeFingerprint(report.status(), report.cases()))
                .isEqualTo(report.fingerprint());
        assertThatThrownBy(() -> new ValidatorAdversarialGateReport(report.status(), report.cases(), fp('e')))
                .isInstanceOf(ValidatorVerificationException.class)
                .hasMessage("RG.VERIFICATION.FINGERPRINT_MISMATCH");

        ValidatorAdversarialGateReport withoutAuthority = fixture.gate.evaluate(
                fixture.corpus, fixture.context, fixture.receipts, null);
        assertThat(withoutAuthority.status()).isEqualTo(ValidatorAdversarialGateStatus.BLOCKED);
        assertThat(withoutAuthority.cases()).extracting(ValidatorAdversarialGateReport.CaseResult::diagnosticCode)
                .containsOnly(ValidatorSecurityDiagnostic.RECEIPT_AUTHORITY_UNAVAILABLE);
    }

    @Test
    void corpusIsExactlyTheSixContentAddressedCasesAndTamperingFails() {
        Fixture fixture = fixture();
        assertThat(fixture.corpus.cases()).extracting(value -> value.caseCode())
                .containsExactlyInAnyOrder(ValidatorAdversarialCaseCode.values());
        assertThat(fixture.corpus.fingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThatThrownBy(() -> new ValidatorAdversarialCorpus(
                fixture.corpus.schemaVersion(), fixture.corpus.corpusVersion(), fixture.corpus.tenantId(),
                fixture.corpus.cases(), fp('e')))
                .isInstanceOf(ValidatorVerificationException.class)
                .hasMessage("RG.VERIFICATION.FINGERPRINT_MISMATCH");
    }

    @Test
    void missingAndPassingValidatorOutcomesBlockTheGate() {
        Fixture fixture = fixture();
        List<ValidatorRejectionReceipt> missing = new ArrayList<>(fixture.receipts);
        missing.removeFirst();
        ValidatorAdversarialGateReport missingReport = evaluate(fixture, missing, fixture.context);
        assertThat(missingReport.status()).isEqualTo(ValidatorAdversarialGateStatus.BLOCKED);
        assertThat(result(missingReport, fixture.corpus.cases().getFirst()))
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(ValidatorAdversarialCaseStatus.BLOCKED);
                    assertThat(value.diagnosticCode()).isEqualTo(ValidatorSecurityDiagnostic.MISSING_RECEIPT);
                });

        ValidatorAdversarialCase expected = fixture.corpus.cases().getFirst();
        List<ValidatorRejectionReceipt> allowed = replace(fixture.receipts, expected,
                ValidatorRejectionReceipt.create(fixture.corpus, expected, fixture.authority,
                        ValidatorRejectionReceipt.Decision.ALLOWED, expected.expectedRejectionCode(), AUTHORITY_RECEIPT));
        ValidatorAdversarialGateReport allowedReport = evaluate(fixture, allowed, fixture.context);
        assertThat(allowedReport.status()).isEqualTo(ValidatorAdversarialGateStatus.BLOCKED);
        assertThat(result(allowedReport, expected).diagnosticCode())
                .isEqualTo(ValidatorSecurityDiagnostic.VALIDATOR_DID_NOT_REJECT);
    }

    @Test
    void duplicateAndExtraReceiptsCannotCloseTheSet() {
        Fixture fixture = fixture();
        List<ValidatorRejectionReceipt> duplicate = new ArrayList<>(fixture.receipts);
        duplicate.add(fixture.receipts.getFirst());
        ValidatorAdversarialGateReport duplicateReport = evaluate(fixture, duplicate, fixture.context);
        assertThat(duplicateReport.status()).isEqualTo(ValidatorAdversarialGateStatus.BLOCKED);
        assertThat(duplicateReport.cases()).extracting(ValidatorAdversarialGateReport.CaseResult::diagnosticCode)
                .contains(ValidatorSecurityDiagnostic.RECEIPT_SET_MISMATCH);

        ValidatorRejectionReceipt first = fixture.receipts.getFirst();
        ValidatorRejectionReceipt extra = new ValidatorRejectionReceipt(
                first.tenantId(), "extra.case", first.caseFingerprint(), first.validatorId(), first.purpose(),
                first.sourceSchemaFingerprint(), first.algorithmVersion(), first.policyFingerprint(),
                first.corpusFingerprint(), first.goldenFingerprint(), ValidatorRejectionReceipt.Decision.REJECTED,
                first.diagnosticCode(), first.authorityId(), first.authorityReceiptFingerprint(),
                ValidatorRejectionReceipt.computeFingerprint(first.tenantId(), "extra.case", first.caseFingerprint(),
                        first.validatorId(),
                        first.purpose(), first.sourceSchemaFingerprint(), first.algorithmVersion(),
                        first.policyFingerprint(), first.corpusFingerprint(), first.goldenFingerprint(),
                        ValidatorRejectionReceipt.Decision.REJECTED, first.diagnosticCode(), first.authorityId(),
                        first.authorityReceiptFingerprint()));
        List<ValidatorRejectionReceipt> withExtra = new ArrayList<>(fixture.receipts);
        withExtra.add(extra);
        ValidatorAdversarialGateReport extraReport = evaluate(fixture, withExtra, fixture.context);
        assertThat(extraReport.status()).isEqualTo(ValidatorAdversarialGateStatus.BLOCKED);
        assertThat(extraReport.cases()).hasSize(6)
                .allSatisfy(value -> assertThat(value.diagnosticCode())
                        .isEqualTo(ValidatorSecurityDiagnostic.RECEIPT_SET_MISMATCH));
    }

    @Test
    void wrongBindingAndReceiptTamperAreDifferentClosedFailures() {
        Fixture fixture = fixture();
        ValidatorAdversarialCase expected = fixture.corpus.cases().getFirst();
        ValidatorRejectionReceipt original = fixture.receipts.stream()
                .filter(value -> value.caseCode().equals(expected.caseCode().code())).findFirst().orElseThrow();

        ValidatorRejectionReceipt wrongBinding = new ValidatorRejectionReceipt(
                original.tenantId(), original.caseCode(), original.caseFingerprint(), original.validatorId(),
                original.purpose(), fp('e'),
                original.algorithmVersion(), original.policyFingerprint(), original.corpusFingerprint(),
                original.goldenFingerprint(), original.decision(), original.diagnosticCode(), original.authorityId(),
                original.authorityReceiptFingerprint(), original.receiptFingerprint());
        ValidatorAdversarialGateReport bindingReport = evaluate(fixture, replace(fixture.receipts, expected,
                wrongBinding), fixture.context);
        assertThat(result(bindingReport, expected).diagnosticCode())
                .isEqualTo(ValidatorSecurityDiagnostic.RECEIPT_BINDING_MISMATCH);

        ValidatorRejectionReceipt tampered = new ValidatorRejectionReceipt(
                original.tenantId(), original.caseCode(), original.caseFingerprint(), original.validatorId(),
                original.purpose(),
                original.sourceSchemaFingerprint(), original.algorithmVersion(), original.policyFingerprint(),
                original.corpusFingerprint(), original.goldenFingerprint(), original.decision(),
                original.diagnosticCode(), original.authorityId(), original.authorityReceiptFingerprint(), fp('e'));
        ValidatorAdversarialGateReport tamperReport = evaluate(fixture, replace(fixture.receipts, expected, tampered),
                fixture.context);
        assertThat(result(tamperReport, expected).diagnosticCode())
                .isEqualTo(ValidatorSecurityDiagnostic.RECEIPT_TAMPERED);

        ValidatorRejectionReceipt wrongPurpose = new ValidatorRejectionReceipt(
                original.tenantId(), original.caseCode(), original.caseFingerprint(), original.validatorId(),
                "other-purpose",
                original.sourceSchemaFingerprint(), original.algorithmVersion(), original.policyFingerprint(),
                original.corpusFingerprint(), original.goldenFingerprint(), original.decision(),
                original.diagnosticCode(), original.authorityId(), original.authorityReceiptFingerprint(),
                original.receiptFingerprint());
        ValidatorAdversarialGateReport purposeReport = evaluate(fixture, replace(fixture.receipts, expected,
                wrongPurpose), fixture.context);
        assertThat(result(purposeReport, expected).diagnosticCode())
                .isEqualTo(ValidatorSecurityDiagnostic.PURPOSE_MISMATCH);
    }

    @Test
    void staleGoldenBlocksSourceAlgorithmAndPolicyChanges() {
        Fixture fixture = fixture();
        List<ValidatorAdversarialGateContext> staleContexts = List.of(
                context(fp('e'), fixture.context.algorithmVersion(), fixture.context.policyFingerprint(), GOLDEN),
                context(SOURCE, "validator-adversarial-gate.v2", fixture.context.policyFingerprint(), GOLDEN),
                context(SOURCE, fixture.context.algorithmVersion(), fp('e'), GOLDEN));
        for (ValidatorAdversarialGateContext stale : staleContexts) {
            ValidatorAdversarialGateReport report = evaluate(fixture, fixture.receipts, stale);
            assertThat(report.status()).isEqualTo(ValidatorAdversarialGateStatus.STALE_GOLDEN);
            assertThat(report.cases()).hasSize(6).allSatisfy(value -> {
                assertThat(value.status()).isEqualTo(ValidatorAdversarialCaseStatus.STALE_GOLDEN);
                assertThat(value.diagnosticCode()).isEqualTo(ValidatorSecurityDiagnostic.STALE_GOLDEN);
            });
        }
    }

    @Test
    void crossTenantAndUnauthorizedReceiptAreBlockedWithoutValidatorExecution() {
        Fixture fixture = fixture();
        ValidatorAdversarialGateContext otherTenant = contextForTenant("tenant-b");
        ValidatorAdversarialGateReport crossTenant = evaluate(fixture, fixture.receipts, otherTenant);
        assertThat(crossTenant.status()).isEqualTo(ValidatorAdversarialGateStatus.BLOCKED);
        assertThat(crossTenant.cases()).extracting(ValidatorAdversarialGateReport.CaseResult::diagnosticCode)
                .containsOnly(ValidatorSecurityDiagnostic.TENANT_SCOPE_MISMATCH);

        Fixture unauthorized = fixture();
        unauthorized.authority.rejectAll();
        ValidatorAdversarialGateReport rejected = evaluate(unauthorized, unauthorized.receipts, unauthorized.context);
        assertThat(rejected.status()).isEqualTo(ValidatorAdversarialGateStatus.BLOCKED);
        assertThat(rejected.cases()).extracting(ValidatorAdversarialGateReport.CaseResult::diagnosticCode)
                .containsOnly(ValidatorSecurityDiagnostic.RECEIPT_NOT_AUTHORIZED);
    }

    @Test
    void gateReportIsDeterministicForTwentyRunsAndNeverEmitsPayloadCanary() {
        Fixture fixture = fixture();
        ValidatorAdversarialGateReport first = evaluate(fixture, fixture.receipts, fixture.context);
        IntStream.range(0, 20).forEach(ignored -> {
            ValidatorAdversarialGateReport report = evaluate(fixture, fixture.receipts, fixture.context);
            assertThat(report.fingerprint()).isEqualTo(first.fingerprint());
            assertThat(report.cases()).isEqualTo(first.cases());
            assertThat(report.toString()).doesNotContain(PAYLOAD_CANARY);
            assertThat(report.fingerprint()).doesNotContain(PAYLOAD_CANARY);
        });

        ValidatorAdversarialCase expected = fixture.corpus.cases().getFirst();
        ValidatorRejectionReceipt canaryReceipt = new ValidatorRejectionReceipt(
                fixture.receipts.getFirst().tenantId(), expected.caseCode().code(), expected.fingerprint(),
                expected.validatorId(),
                expected.purpose(), expected.sourceSchemaFingerprint(), expected.algorithmVersion(),
                expected.policyFingerprint(), fixture.corpus.fingerprint(), expected.goldenFingerprint(),
                ValidatorRejectionReceipt.Decision.REJECTED, PAYLOAD_CANARY, fixture.authority.authorityId(),
                AUTHORITY_RECEIPT, fp('e'));
        ValidatorAdversarialGateReport canaryReport = evaluate(fixture,
                replace(fixture.receipts, expected, canaryReceipt), fixture.context);
        assertThat(canaryReport.toString()).doesNotContain(PAYLOAD_CANARY);
    }

    private static ValidatorAdversarialGateReport evaluate(Fixture fixture,
                                                            List<ValidatorRejectionReceipt> receipts,
                                                            ValidatorAdversarialGateContext context) {
        return fixture.gate.evaluate(fixture.corpus, context, receipts, fixture.authority);
    }

    private static ValidatorAdversarialGateReport.CaseResult result(
            ValidatorAdversarialGateReport report, ValidatorAdversarialCaseCode code) {
        return report.cases().stream().filter(value -> value.caseCode().equals(code.code())).findFirst().orElseThrow();
    }

    private static ValidatorAdversarialGateReport.CaseResult result(
            ValidatorAdversarialGateReport report, ValidatorAdversarialCase expected) {
        return report.cases().stream().filter(value -> value.caseCode().equals(expected.caseCode().code()))
                .findFirst().orElseThrow();
    }

    private static List<ValidatorRejectionReceipt> replace(List<ValidatorRejectionReceipt> source,
                                                            ValidatorAdversarialCase expected,
                                                            ValidatorRejectionReceipt replacement) {
        return source.stream().map(value -> value.caseCode().equals(expected.caseCode().code()) ? replacement : value)
                .toList();
    }

    private static Fixture fixture() {
        ValidatorAdversarialCorpus corpus = ValidatorAdversarialCorpus.standard(
                TENANT, PURPOSE, SOURCE, ValidatorVerificationSupport.ALGORITHM_VERSION, POLICY, GOLDEN);
        ValidatorAdversarialGateContext context = context(SOURCE, ValidatorVerificationSupport.ALGORITHM_VERSION,
                POLICY, GOLDEN);
        IndependentReceiptAuthority authority = new IndependentReceiptAuthority("independent-receipt-authority");
        List<ValidatorRejectionReceipt> receipts = corpus.cases().stream()
                .map(expected -> ValidatorRejectionReceipt.create(corpus, expected, authority,
                        ValidatorRejectionReceipt.Decision.REJECTED, expected.expectedRejectionCode(), AUTHORITY_RECEIPT))
                .toList();
        authority.authorize(receipts);
        return new Fixture(corpus, context, receipts, authority, new ValidatorAdversarialCorpusGate());
    }

    private static ValidatorAdversarialGateContext context(String source, String algorithm,
                                                            String policy, String golden) {
        return ValidatorAdversarialGateContext.of(TENANT, PURPOSE, source, algorithm, policy, golden);
    }

    private static ValidatorAdversarialGateContext contextForTenant(String tenant) {
        return ValidatorAdversarialGateContext.of(tenant, PURPOSE, SOURCE,
                ValidatorVerificationSupport.ALGORITHM_VERSION, POLICY, GOLDEN);
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(ValidatorAdversarialCorpus corpus, ValidatorAdversarialGateContext context,
                           List<ValidatorRejectionReceipt> receipts, IndependentReceiptAuthority authority,
                           ValidatorAdversarialCorpusGate gate) {
    }

    /** Deliberately separate from the gate: it represents a receipt authority, not a validator. */
    private static final class IndependentReceiptAuthority implements ValidatorReceiptAuthority {
        private final String authorityId;
        private final Map<String, String> authorized = new HashMap<>();
        private int verificationCalls;

        private IndependentReceiptAuthority(String authorityId) {
            this.authorityId = authorityId;
        }

        @Override
        public String authorityId() {
            return authorityId;
        }

        private void authorize(List<ValidatorRejectionReceipt> receipts) {
            receipts.forEach(receipt -> authorized.put(receipt.receiptFingerprint(), receipt.caseCode()));
        }

        private void rejectAll() {
            authorized.clear();
        }

        private int verificationCalls() {
            return verificationCalls;
        }

        @Override
        public Verification verify(ValidatorAdversarialCase expected, ValidatorRejectionReceipt receipt) {
            verificationCalls++;
            return authorized.getOrDefault(receipt.receiptFingerprint(), "").equals(expected.caseCode().code())
                    ? Verification.verified() : Verification.rejected();
        }
    }
}
