package com.leanowtech.bloge.gateway.testing.correctness.demo;

import com.leanowtech.bloge.gateway.visual.reference.ReferenceCandidate;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceCandidateContributor;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Test/staging-only metadata catalog for the enterprise customer-service loan-decision demo.
 * It intentionally exposes business asset coordinates only; no business payload or credential is
 * part of this catalog.
 */
public final class LoanDecisionReferenceCandidateContributor implements ReferenceCandidateContributor {
    private static final String AUTHORITY = "resource-gateway://demo/business-catalog/loan-decision";
    private static final ReferenceCandidate.Owner OWNER = new ReferenceCandidate.Owner(
            "credit-service-design", "Credit Service Design");

    @Override
    public String contributorId() {
        return "resource-gateway-demo.loan-decision-business-catalog";
    }

    @Override
    public List<ReferenceCandidate> contribute(ReferenceScope scope) {
        return List.of(
                candidate(scope, "BUSINESS_DOMAIN", "credit-decision", "Credit decision",
                        "Customer-service business domain for policy-compliant lending decisions.",
                        List.of("loan", "customer-service", "domain")),
                candidate(scope, "PROBLEM_TAXONOMY", "loan-decision-problems", "Loan decision problems",
                        "Taxonomy for approval, rejection, fallback, timeout, and manual-review problems.",
                        List.of("problem", "taxonomy", "loan")),
                candidate(scope, "OWNER", "credit-service-design", "Credit Service Design",
                        "Business owner responsible for the loan decision service assets.",
                        List.of("owner", "accountable")),
                candidate(scope, "PACKAGE_CONTRACT", "loan-decision-contract-v1", "Loan decision package contract",
                        "Stable contract boundary for the loan-decision resource package.",
                        List.of("contract", "package", "v1")),
                candidate(scope, "STATE_MODEL", "loan-decision-state-v1", "Loan decision state model",
                        "Decision lifecycle states used by service and customer-support workflows.",
                        List.of("state", "lifecycle")),
                candidate(scope, "EFFECT_MODEL", "loan-decision-effect-v1", "Loan decision effect model",
                        "Allowed read, review, and write-effect classification for the decision flow.",
                        List.of("effect", "governance", "read-only")),
                candidate(scope, "SOLUTION", "loan-decision-fallback-solution", "Loan decision fallback solution",
                        "Reviewed solution for primary credit timeout and double-failure degradation.",
                        List.of("solution", "fallback", "manual-review")),
                candidate(scope, "SERVICE_CARRIER", "loan-policy-agent", "Loan policy service carrier",
                        "Service carrier that presents the verified decision solution to support channels.",
                        List.of("agent", "service-carrier")),
                candidate(scope, "CHANNEL", "customer-support-chat", "Customer support chat",
                        "Customer-support interaction channel used by the demonstration workflow.",
                        List.of("channel", "chat", "customer-service")),
                candidate(scope, "SCENARIO_INVENTORY", "loan-policy-obligations", "Loan policy scenario inventory",
                        "Frozen inventory of correctness obligations for the loan decision flow.",
                        List.of("inventory", "obligation", "correctness")),
                candidate(scope, "SCENARIO_PACK", "loan-policy-regression", "Loan policy regression pack",
                        "Scenario pack covering golden, negative, boundary, and fallback behavior.",
                        List.of("scenario", "regression", "golden")),
                candidate(scope, "FIDELITY_INVENTORY", "loan-policy-fidelity", "Loan policy fidelity inventory",
                        "Inventory of business assumptions and evidence needed for high-fidelity rehearsal.",
                        List.of("fidelity", "evidence", "simulation")),
                candidate(scope, "OUTCOME_DEFINITION", "loan-decision-outcomes", "Loan decision outcomes",
                        "Expected business outcomes for approval, rejection, fallback, and manual review.",
                        List.of("outcome", "assertion", "business-correctness")));
    }

    private static ReferenceCandidate candidate(ReferenceScope scope,
                                                String kind,
                                                String id,
                                                String displayName,
                                                String description,
                                                List<String> labels) {
        return new ReferenceCandidate(
                ReferenceCandidate.SCHEMA_VERSION,
                kind,
                id,
                displayName,
                description,
                1,
                fingerprint(kind + "\u0000" + id + "\u0000" + displayName),
                AUTHORITY,
                scope,
                ReferenceCandidate.Lifecycle.ACTIVE,
                OWNER,
                labels,
                ReferenceCandidate.Compatibility.COMPATIBLE,
                "");
    }

    private static String fingerprint(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("sha256:");
            for (byte item : digest) {
                value.append("%02x".formatted(item & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }
}
