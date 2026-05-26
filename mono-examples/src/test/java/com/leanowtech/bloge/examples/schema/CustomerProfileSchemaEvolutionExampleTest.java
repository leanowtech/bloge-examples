package com.leanowtech.bloge.examples.schema;

import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerProfileSchemaEvolutionExampleTest {

    @Test
    void optionalMarketingFieldsAreFullyCompatible() {
        var assessment = CustomerProfileSchemaEvolutionExample.addOptionalMarketingFields();

        assertEquals("publish", assessment.status());
        assertTrue(assessment.publishable());
        assertEquals("1.0.0", assessment.fromVersion());
        assertEquals("2.0.0", assessment.toVersion());
        assertTrue(assessment.warnings().isEmpty());
        assertTrue(assessment.violations().isEmpty());
        assertInstanceOf(SchemaCompatibility.FullyCompatible.class, assessment.compatibility());
    }

    @Test
    void legacyContactSunsetRequiresReviewWarnings() {
        var assessment = CustomerProfileSchemaEvolutionExample.sunsetLegacyContactFields();

        assertEquals("review", assessment.status());
        assertTrue(assessment.publishable());
        assertTrue(assessment.violations().isEmpty());
        assertInstanceOf(SchemaCompatibility.BackwardCompatible.class, assessment.compatibility());
        assertTrue(assessment.warnings().stream().anyMatch(warning -> warning.contains("schema.faxNumber")));
        assertTrue(assessment.warnings().stream().anyMatch(warning -> warning.contains("deprecated")));
    }

    @Test
    void requiredKycFieldAndReservedNameReuseAreBlocked() {
        var assessment = CustomerProfileSchemaEvolutionExample.requireGovernmentIdAndReuseReservedName();

        assertEquals("block", assessment.status());
        assertTrue(!assessment.publishable());
        assertTrue(assessment.warnings().isEmpty());
        assertInstanceOf(SchemaCompatibility.BreakingChange.class, assessment.compatibility());
        assertTrue(assessment.violations().stream().anyMatch(violation -> violation.contains("schema.governmentId")));
        assertTrue(assessment.violations().stream().anyMatch(violation -> violation.contains("reserved field name")));
    }

    @Test
    void releaseMatrixOrdersScenariosFromSafeToBlocked() {
        var matrix = CustomerProfileSchemaEvolutionExample.releaseMatrix();

        assertEquals(3, matrix.size());
        assertEquals("publish", matrix.get(0).status());
        assertEquals("review", matrix.get(1).status());
        assertEquals("block", matrix.get(2).status());
    }
}