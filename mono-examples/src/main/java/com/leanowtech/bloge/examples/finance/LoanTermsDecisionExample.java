package com.leanowtech.bloge.examples.finance;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.exception.DecisionTableViolationException;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.CompiledDecisionRule;
import com.leanowtech.bloge.core.operator.DecisionTableInput;
import com.leanowtech.bloge.core.operator.DecisionTableOperator;
import com.leanowtech.bloge.core.operator.HitPolicy;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates a UNIQUE decision table for loan terms.
 *
 * <p>The example models a small underwriting matrix where credit score and requested amount map
 * to a structured output containing interest rate and maximum term. The direct violation helper
 * deliberately feeds overlapping rows to show BLOGE's stable ambiguous-match error code.</p>
 */
public final class LoanTermsDecisionExample {

    static final String NODE_FETCH_APPLICANT = "fetchApplicant";
    static final String NODE_FETCH_LOAN = "fetchLoan";
    static final String NODE_LOAN_TERMS = "loanTerms";

    private LoanTermsDecisionExample() {
    }

    public record ApplicantQuery(String applicantId) {}
    public record Applicant(String applicantId, int score) {}
    public record LoanQuery(String loanId) {}
    public record Loan(String loanId, double requestedAmount) {}

    static final Operator<ApplicantQuery, Applicant> FETCH_APPLICANT = (input, ctx) -> switch (input.applicantId()) {
        case "prime" -> new Applicant(input.applicantId(), 780);
        case "standard" -> new Applicant(input.applicantId(), 720);
        default -> new Applicant(input.applicantId(), 610);
    };

    static final Operator<LoanQuery, Loan> FETCH_LOAN = (input, ctx) -> switch (input.loanId()) {
        case "large" -> new Loan(input.loanId(), 450_000.0);
        case "small" -> new Loan(input.loanId(), 250_000.0);
        default -> new Loan(input.loanId(), 650_000.0);
    };

    /**
     * Builds the loan-term matrix graph.
     *
     * @return graph containing a UNIQUE decision-table node
     */
    public static Graph buildGraph() {
        return Graph.builder("loanTermsDecision")
                .node(NODE_FETCH_APPLICANT, FETCH_APPLICANT)
                    .input((results, ctx) -> new ApplicantQuery(ctx.get("applicantId", String.class)))
                .node(NODE_FETCH_LOAN, FETCH_LOAN)
                    .input((results, ctx) -> new LoanQuery(ctx.get("loanId", String.class)))
                .node(NODE_LOAN_TERMS, DecisionTableOperator.INSTANCE)
                    .dependsOn(NODE_FETCH_APPLICANT, NODE_FETCH_LOAN)
                    .input((results, ctx) -> decisionInput(
                            results.get(NODE_FETCH_APPLICANT, Applicant.class).score(),
                            results.get(NODE_FETCH_LOAN, Loan.class).requestedAmount()))
                .build();
    }

    /**
     * Executes the loan-term decision table.
     *
     * @param applicantId sample applicant id
     * @param loanId sample loan id
     * @return result containing {@code loanTerms.output.rate} and {@code loanTerms.output.maxTerm}
     */
    public static GraphResult execute(String applicantId, String loanId) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(buildGraph(), new GraphContext(Map.of(
                "applicantId", applicantId,
                "loanId", loanId
        )), Map.of(
                NODE_FETCH_APPLICANT, FETCH_APPLICANT,
                NODE_FETCH_LOAN, FETCH_LOAN,
                NODE_LOAN_TERMS, DecisionTableOperator.INSTANCE
        ));
    }

    static DecisionTableInput decisionInput(int score, double amount) {
        return new DecisionTableInput(HitPolicy.UNIQUE, loanTermRules(), Map.of(
                "score", score,
                "amount", amount
        ));
    }

    static DecisionTableViolationException ambiguousMatch() {
        try {
            DecisionTableOperator.INSTANCE.execute(
                    decisionInput(780, 250_000.0),
                    OperatorContext.builder()
                            .nodeId(NODE_LOAN_TERMS)
                            .graphName("loanTermsDecision")
                            .graphContext(new GraphContext(Map.of()))
                            .build());
            throw new AssertionError("Expected ambiguous loan-term rules to fail");
        } catch (DecisionTableViolationException violation) {
            return violation;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> terms(GraphResult result) {
        return (Map<String, Object>) result.results().getRaw(NODE_LOAN_TERMS);
    }

    private static List<CompiledDecisionRule> loanTermRules() {
        return List.of(
                new CompiledDecisionRule(0, params -> intParam(params, "score") >= 750
                        && doubleParam(params, "amount") <= 500_000.0,
                        params -> Map.of("rate", 3.5, "maxTerm", 30), false),
                new CompiledDecisionRule(1, params -> intParam(params, "score") >= 700
                        && doubleParam(params, "amount") <= 300_000.0,
                        params -> Map.of("rate", 4.5, "maxTerm", 25), false),
                new CompiledDecisionRule(2, params -> intParam(params, "score") >= 650
                        && doubleParam(params, "amount") <= 200_000.0,
                        params -> Map.of("rate", 5.5, "maxTerm", 20), false),
                new CompiledDecisionRule(3, null,
                        params -> Map.of("rate", 7.0, "maxTerm", 10), true)
        );
    }

    private static int intParam(Map<String, Object> params, String name) {
        return ((Number) params.get(name)).intValue();
    }

    private static double doubleParam(Map<String, Object> params, String name) {
        return ((Number) params.get(name)).doubleValue();
    }
}