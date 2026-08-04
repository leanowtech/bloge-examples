package com.leanowtech.bloge.gateway.authoring.scenario;

/** Executes one exact Scenario and returns payload-free physical-attempt evidence. */
@FunctionalInterface
public interface TableSuiteCaseRunner {

    TableSuiteRunBatch.AttemptEvidence run(
            TableSuiteRunCommand command,
            String caseId,
            int attempt);
}
