package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.Objects;

/**
 * One atomically resolved schema-admission target.
 *
 * <p>The private constructor prevents callers from combining artifacts resolved at different
 * points in time. {@link #verified(ObjectMapper, TestExecutionApiRequest.Target, SchemaEnvelope,
 * TestBoundaryCasePlan)} proves both target identity and canonical schema fingerprint before a
 * snapshot can exist.</p>
 */
final class TestSchemaAdmissionTarget {
    private final TestExecutionApiRequest.Target target;
    private final SchemaEnvelope inputSchema;
    private final TestBoundaryCasePlan boundaryPlan;

    private TestSchemaAdmissionTarget(TestExecutionApiRequest.Target target,
                                      SchemaEnvelope inputSchema,
                                      TestBoundaryCasePlan boundaryPlan) {
        this.target = target;
        this.inputSchema = inputSchema;
        this.boundaryPlan = boundaryPlan;
    }

    /**
     * Creates a snapshot only when plan target and schema identity are internally consistent.
     *
     * @param objectMapper canonical protocol mapper
     * @param target exact current graph or operator target
     * @param inputSchema exact schema used by the shared admission validator
     * @param boundaryPlan exact plan regenerated from {@code inputSchema}
     * @return verified internally consistent snapshot
     */
    static TestSchemaAdmissionTarget verified(
            ObjectMapper objectMapper,
            TestExecutionApiRequest.Target target,
            SchemaEnvelope inputSchema,
            TestBoundaryCasePlan boundaryPlan) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        TestExecutionApiRequest.Target safeTarget = Objects.requireNonNull(target, "target");
        SchemaEnvelope safeSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        TestBoundaryCasePlan safePlan = Objects.requireNonNull(boundaryPlan, "boundaryPlan");
        if (!safeTarget.equals(safePlan.target())) {
            throw new IllegalArgumentException(
                    "Schema-admission target and boundary plan identities must match");
        }
        if (!ProtocolFingerprint.of(mapper, safeSchema)
                .equals(safePlan.inputSchemaFingerprint())) {
            throw new IllegalArgumentException(
                    "Schema-admission input schema and boundary plan fingerprints must match");
        }
        return new TestSchemaAdmissionTarget(safeTarget, safeSchema, safePlan);
    }

    /** @return exact current graph or operator target */
    TestExecutionApiRequest.Target target() {
        return target;
    }

    /** @return exact schema used by the admission validator */
    SchemaEnvelope inputSchema() {
        return inputSchema;
    }

    /** @return exact boundary plan regenerated from {@link #inputSchema()} */
    TestBoundaryCasePlan boundaryPlan() {
        return boundaryPlan;
    }
}
