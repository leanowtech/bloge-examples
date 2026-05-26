package com.leanowtech.bloge.examples.schema;

import com.leanowtech.bloge.core.schema.FieldDescriptor;
import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.SchemaEvolutionChecker;
import com.leanowtech.bloge.core.schema.StructuredSchema;
import com.leanowtech.bloge.core.schema.VersionedSchema;

import java.util.List;
import java.util.Set;

/**
 * Demonstrates BLOGE schema evolution checks for a published customer profile contract.
 *
 * <p>The example models a release gate that compares a currently published schema with a
 * candidate schema. Compatible changes can be published, backward-compatible changes are allowed
 * with review warnings, and breaking changes are blocked before a graph definition is published.</p>
 */
public final class CustomerProfileSchemaEvolutionExample {

    private static final SchemaEvolutionChecker CHECKER = new SchemaEvolutionChecker();

    private CustomerProfileSchemaEvolutionExample() {}

    /**
     * Summarizes a schema release decision in a domain-friendly shape.
     *
     * @param changeName release scenario name
     * @param fromVersion existing published schema version
     * @param toVersion candidate schema version
     * @param compatibility raw BLOGE compatibility result
     * @param publishable whether the release gate should allow publishing
     * @param warnings non-blocking compatibility warnings
     * @param violations blocking compatibility violations
     */
    public record ReleaseAssessment(
            String changeName,
            String fromVersion,
            String toVersion,
            SchemaCompatibility compatibility,
            boolean publishable,
            List<String> warnings,
            List<String> violations
    ) {
        /**
         * Returns a compact release-gate label for dashboards or CI output.
         */
        public String status() {
            if (!publishable) {
                return "block";
            }
            return warnings.isEmpty() ? "publish" : "review";
        }
    }

    /**
     * Evaluates the common safe case: adding optional marketing fields for new consumers.
     *
     * @return a fully compatible publish assessment
     */
    public static ReleaseAssessment addOptionalMarketingFields() {
        return assess("add-optional-marketing-fields", profileV1(), profileV2MarketingOptIn());
    }

    /**
     * Evaluates a publishable but review-worthy change: optional legacy data is removed and a
     * still-readable field is marked as deprecated for a future cleanup.
     *
     * @return a backward-compatible assessment with warnings
     */
    public static ReleaseAssessment sunsetLegacyContactFields() {
        return assess("sunset-legacy-contact-fields", profileV1(), profileV2LegacySunset());
    }

    /**
     * Evaluates a blocked release: old producers cannot supply a new required field and the
     * candidate tries to reuse a reserved legacy field name.
     *
     * @return a breaking-change assessment
     */
    public static ReleaseAssessment requireGovernmentIdAndReuseReservedName() {
        return assess("require-government-id", profileV1(), profileV2BreakingKyc());
    }

    /**
     * Returns all release scenarios as a matrix suitable for printing in a demo or CI check.
     */
    public static List<ReleaseAssessment> releaseMatrix() {
        return List.of(
                addOptionalMarketingFields(),
                sunsetLegacyContactFields(),
                requireGovernmentIdAndReuseReservedName()
        );
    }

    /**
     * Published customer profile schema. The reserved field models an identifier removed in an
     * earlier release that must never be reused with a different meaning.
     */
    public static VersionedSchema profileV1() {
        return new VersionedSchema(
                structured(
                        requiredField("customerId", String.class),
                        requiredField("email", String.class),
                        requiredField("tier", String.class),
                        optionalField("phoneNumber", String.class),
                        optionalField("faxNumber", String.class)
                ),
                "1.0.0",
                Set.of(),
                Set.of("legacyStatus")
        );
    }

    /**
     * Candidate schema that only adds optional fields, so existing producers remain valid.
     */
    public static VersionedSchema profileV2MarketingOptIn() {
        return new VersionedSchema(
                structured(
                        requiredField("customerId", String.class),
                        requiredField("email", String.class),
                        requiredField("tier", String.class),
                        optionalField("phoneNumber", String.class),
                        optionalField("faxNumber", String.class),
                        optionalField("marketingConsent", Boolean.class),
                        optionalField("preferredChannel", String.class)
                ),
                "2.0.0"
        );
    }

    /**
     * Candidate schema that remains publishable but asks reviewers to track migration risk.
     */
    public static VersionedSchema profileV2LegacySunset() {
        return new VersionedSchema(
                structured(
                        requiredField("customerId", String.class),
                        requiredField("email", String.class),
                        requiredField("tier", String.class),
                        optionalField("phoneNumber", String.class),
                        optionalField("marketingConsent", Boolean.class)
                ),
                "2.0.0",
                Set.of("phoneNumber"),
                Set.of()
        );
    }

    /**
     * Candidate schema with two blocking problems: a new required KYC field and reserved-name
     * reuse for data that used to mean something else.
     */
    public static VersionedSchema profileV2BreakingKyc() {
        return new VersionedSchema(
                structured(
                        requiredField("customerId", String.class),
                        requiredField("email", String.class),
                        requiredField("tier", String.class),
                        optionalField("phoneNumber", String.class),
                        optionalField("faxNumber", String.class),
                        requiredField("governmentId", String.class),
                        optionalField("legacyStatus", String.class)
                ),
                "2.0.0"
        );
    }

    private static ReleaseAssessment assess(String changeName, VersionedSchema oldSchema, VersionedSchema newSchema) {
        SchemaCompatibility compatibility = CHECKER.check(oldSchema, newSchema);
        return switch (compatibility) {
            case SchemaCompatibility.FullyCompatible ignored -> new ReleaseAssessment(
                    changeName,
                    oldSchema.schemaVersion(),
                    newSchema.schemaVersion(),
                    compatibility,
                    true,
                    List.of(),
                    List.of()
            );
            case SchemaCompatibility.BackwardCompatible backward -> new ReleaseAssessment(
                    changeName,
                    oldSchema.schemaVersion(),
                    newSchema.schemaVersion(),
                    compatibility,
                    true,
                    backward.warnings(),
                    List.of()
            );
            case SchemaCompatibility.BreakingChange breaking -> new ReleaseAssessment(
                    changeName,
                    oldSchema.schemaVersion(),
                    newSchema.schemaVersion(),
                    compatibility,
                    false,
                    List.of(),
                    breaking.violations()
            );
        };
    }

    private static StructuredSchema structured(FieldDescriptor... fields) {
        return new StructuredSchema(List.of(fields));
    }

    private static FieldDescriptor requiredField(String name, Class<?> type) {
        return field(name, type, true, null);
    }

    private static FieldDescriptor optionalField(String name, Class<?> type) {
        return field(name, type, false, null);
    }

    private static FieldDescriptor field(String name, Class<?> type, boolean required, SchemaDescriptor nested) {
        return new FieldDescriptor(name, type, required, null, nested, List.of(), null);
    }
}