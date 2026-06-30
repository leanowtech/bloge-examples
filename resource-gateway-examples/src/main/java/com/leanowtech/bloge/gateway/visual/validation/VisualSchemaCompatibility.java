package com.leanowtech.bloge.gateway.visual.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared schema compatibility helpers for visual authoring.
 */
public final class VisualSchemaCompatibility {

    private static final Pattern INTEGER_LITERAL = Pattern.compile("[-+]?\\d+");
    private static final Pattern NUMBER_LITERAL = Pattern.compile(
            "[-+]?(?:\\d+\\.\\d*|\\d*\\.\\d+|\\d+[eE][-+]?\\d+|\\d+\\.\\d*[eE][-+]?\\d+|\\d*\\.\\d+[eE][-+]?\\d+)");
    private static final Set<String> SUPPORTED_STRING_FORMATS = Set.of(
            "date",
            "date-time",
            "duration",
            "email",
            "uri",
            "uuid"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private VisualSchemaCompatibility() {
    }

    /**
     * @param sourceSchema source schema
     * @param targetSchema target schema
     * @return true when the source can safely feed the target
     */
    public static boolean schemasCompatible(Map<String, Object> sourceSchema, Map<String, Object> targetSchema) {
        return schemaCompatibilityIssue(sourceSchema, targetSchema).isEmpty();
    }

    /**
     * @param sourceSchema source schema
     * @param targetSchema target schema
     * @return optional reason when the source cannot safely feed the target
     */
    public static Optional<String> schemaCompatibilityIssue(Map<String, Object> sourceSchema,
                                                            Map<String, Object> targetSchema) {
        return schemaCompatibilityIssue(sourceSchema, targetSchema, "");
    }

    private static Optional<String> schemaCompatibilityIssue(Map<String, Object> sourceSchema,
                                                             Map<String, Object> targetSchema,
                                                             String path) {
        String sourceType = schemaType(sourceSchema);
        String targetType = schemaType(targetSchema);
        if (sourceType.isBlank() || targetType.isBlank()
                || "any".equals(sourceType) || "any".equals(targetType)
                || "opaque".equals(sourceType) || "opaque".equals(targetType)) {
            return Optional.empty();
        }
	        if ("array".equals(sourceType) && "array".equals(targetType)) {
	            Optional<String> prefixItemsIssue = arrayPrefixItemsCompatibilityIssue(sourceSchema, targetSchema, path);
	            if (prefixItemsIssue.isPresent()) {
	                return prefixItemsIssue;
	            }
	            Optional<String> itemIssue = arrayItemsCompatibilityIssue(sourceSchema, targetSchema, path);
	            if (itemIssue.isPresent()) {
	                return itemIssue;
	            }
	            Optional<String> itemBoundsIssue = arrayItemBoundsCompatibilityIssue(sourceSchema, targetSchema, path);
	            if (itemBoundsIssue.isPresent()) {
	                return itemBoundsIssue;
	            }
	            Optional<String> uniqueItemsIssue = arrayUniqueItemsCompatibilityIssue(sourceSchema, targetSchema, path);
	            return uniqueItemsIssue.isPresent()
	                    ? uniqueItemsIssue
	                    : arrayContainsCompatibilityIssue(sourceSchema, targetSchema, path);
	        }
        if ("object".equals(sourceType) && "object".equals(targetType)) {
            return objectSchemaCompatibilityIssue(sourceSchema, targetSchema, path);
        }
        List<Object> targetEnumValues = enumValues(targetSchema);
	        if (!targetEnumValues.isEmpty()) {
	            List<Object> sourceEnumValues = enumValues(sourceSchema);
	            if (sourceEnumValues.isEmpty()) {
                return Optional.of(reasonAt(path,
                        "target enum %s requires a finite source enum domain, but source is %s"
                                .formatted(valueDomainLabel(targetEnumValues), schemaTypeLabel(sourceSchema))));
            }
            List<Object> outside = sourceEnumValues.stream()
                    .filter(value -> !targetEnumValues.contains(value))
                    .toList();
            return outside.isEmpty()
                    ? Optional.empty()
                    : Optional.of(reasonAt(path,
                    "source enum value(s) %s are outside target enum %s"
	                            .formatted(valueDomainLabel(outside), valueDomainLabel(targetEnumValues))));
	        }
	        List<Object> sourceEnumValues = enumValues(sourceSchema);
	        if (!sourceEnumValues.isEmpty()) {
	            List<Object> incompatible = sourceEnumValues.stream()
	                    .filter(value -> !valueMatchesSchema(value, targetSchema))
	                    .toList();
            return incompatible.isEmpty()
                    ? Optional.empty()
                    : Optional.of(reasonAt(path,
                    "source enum value(s) %s do not match target schema %s"
	                            .formatted(valueDomainLabel(incompatible), schemaTypeLabel(targetSchema))));
	        }
	        if (numeric(sourceType) && numeric(targetType)) {
	            Optional<String> boundsIssue = numericBoundsCompatibilityIssue(sourceSchema, targetSchema, path);
	            return boundsIssue.isPresent()
	                    ? boundsIssue
	                    : numericMultipleOfCompatibilityIssue(sourceSchema, targetSchema, path);
	        }
		        if (stringLike(sourceType) && stringLike(targetType)) {
		            Optional<String> formatIssue = stringFormatCompatibilityIssue(sourceSchema, targetSchema, path);
		            if (formatIssue.isPresent()) {
		                return formatIssue;
		            }
		            Optional<String> patternIssue = stringPatternCompatibilityIssue(sourceSchema, targetSchema, path);
		            return patternIssue.isPresent()
		                    ? patternIssue
		                    : stringLengthCompatibilityIssue(sourceSchema, targetSchema, path);
	        }
	        if (sourceType.equals(targetType)) {
	            return Optional.empty();
	        }
	        return Optional.of(reasonAt(path,
	                "source type %s cannot feed target type %s"
	                        .formatted(schemaTypeLabel(sourceSchema), schemaTypeLabel(targetSchema))));
    }

	    private static Optional<String> numericBoundsCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                    Map<String, Object> targetSchema,
                                                                    String path) {
        if (!numeric(schemaType(sourceSchema)) || !numeric(schemaType(targetSchema))) {
            return Optional.empty();
        }
        NumericBoundary targetLower = lowerBound(targetSchema);
        if (targetLower != null) {
            NumericBoundary sourceLower = lowerBound(sourceSchema);
            if (sourceLower == null) {
                return Optional.of(reasonAt(path,
                        "target requires %s but source has no lower bound".formatted(targetLower.lowerLabel())));
            }
            if (!lowerBoundAtLeast(sourceLower, targetLower)) {
                return Optional.of(reasonAt(path,
                        "source lower bound %s is weaker than target lower bound %s"
                                .formatted(sourceLower.lowerLabel(), targetLower.lowerLabel())));
            }
        }
        NumericBoundary targetUpper = upperBound(targetSchema);
        if (targetUpper != null) {
            NumericBoundary sourceUpper = upperBound(sourceSchema);
            if (sourceUpper == null) {
                return Optional.of(reasonAt(path,
                        "target requires %s but source has no upper bound".formatted(targetUpper.upperLabel())));
            }
            if (!upperBoundAtMost(sourceUpper, targetUpper)) {
                return Optional.of(reasonAt(path,
                        "source upper bound %s is weaker than target upper bound %s"
                                .formatted(sourceUpper.upperLabel(), targetUpper.upperLabel())));
            }
        }
	        return Optional.empty();
	    }

	    private static Optional<String> numericMultipleOfCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                        Map<String, Object> targetSchema,
	                                                                        String path) {
	        if (!numeric(schemaType(sourceSchema)) || !numeric(schemaType(targetSchema))) {
	            return Optional.empty();
	        }
	        Double targetMultipleOf = numericMultipleOf(targetSchema.get("multipleOf"));
	        if (targetMultipleOf == null) {
	            return Optional.empty();
	        }
	        Double sourceMultipleOf = numericMultipleOf(sourceSchema.get("multipleOf"));
	        if (sourceMultipleOf == null) {
	            return Optional.of(reasonAt(path,
	                    "target requires multipleOf %s but source has no multipleOf"
	                            .formatted(numberLabel(targetMultipleOf))));
	        }
	        if (!numericValueIsMultipleOf(sourceMultipleOf, targetMultipleOf)) {
	            return Optional.of(reasonAt(path,
	                    "source multipleOf %s is weaker than target multipleOf %s"
	                            .formatted(numberLabel(sourceMultipleOf), numberLabel(targetMultipleOf))));
	        }
	        return Optional.empty();
	    }

	    private static Optional<String> stringLengthCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                   Map<String, Object> targetSchema,
	                                                                   String path) {
	        if (!stringLike(schemaType(sourceSchema)) || !stringLike(schemaType(targetSchema))) {
	            return Optional.empty();
	        }
	        Long targetMinimum = stringMinLength(targetSchema);
	        if (targetMinimum != null) {
	            Long sourceMinimum = stringMinLength(sourceSchema);
	            if (sourceMinimum == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires length >= %d but source has no minLength".formatted(targetMinimum)));
	            }
	            if (sourceMinimum < targetMinimum) {
	                return Optional.of(reasonAt(path,
	                        "source minLength %d is weaker than target minLength %d"
	                                .formatted(sourceMinimum, targetMinimum)));
	            }
	        }
	        Long targetMaximum = stringMaxLength(targetSchema);
	        if (targetMaximum != null) {
	            Long sourceMaximum = stringMaxLength(sourceSchema);
	            if (sourceMaximum == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires length <= %d but source has no maxLength".formatted(targetMaximum)));
	            }
	            if (sourceMaximum > targetMaximum) {
	                return Optional.of(reasonAt(path,
	                        "source maxLength %d is weaker than target maxLength %d"
	                                .formatted(sourceMaximum, targetMaximum)));
	            }
	        }
	        return Optional.empty();
	    }

		    private static Optional<String> stringPatternCompatibilityIssue(Map<String, Object> sourceSchema,
		                                                                    Map<String, Object> targetSchema,
		                                                                    String path) {
	        if (!stringLike(schemaType(sourceSchema)) || !stringLike(schemaType(targetSchema))) {
	            return Optional.empty();
	        }
	        String targetPattern = stringPattern(targetSchema);
	        if (targetPattern == null) {
	            return Optional.empty();
	        }
	        String sourcePattern = stringPattern(sourceSchema);
	        if (targetPattern.equals(sourcePattern)) {
	            return Optional.empty();
	        }
	        if (sourcePattern == null) {
	            return Optional.of(reasonAt(path,
	                    "target requires pattern '%s' but source has no pattern".formatted(targetPattern)));
	        }
		        return Optional.of(reasonAt(path,
		                "source pattern '%s' cannot be proven compatible with target pattern '%s'"
		                        .formatted(sourcePattern, targetPattern)));
		    }

	    private static Optional<String> stringFormatCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                   Map<String, Object> targetSchema,
	                                                                   String path) {
	        if (!stringLike(schemaType(sourceSchema)) || !stringLike(schemaType(targetSchema))) {
	            return Optional.empty();
	        }
	        String targetFormat = stringFormat(targetSchema);
	        if (targetFormat == null) {
	            return Optional.empty();
	        }
	        String sourceFormat = stringFormat(sourceSchema);
	        if (targetFormat.equals(sourceFormat)) {
	            return Optional.empty();
	        }
	        if (sourceFormat == null) {
	            return Optional.of(reasonAt(path,
	                    "target requires format '%s' but source has no format".formatted(targetFormat)));
	        }
	        return Optional.of(reasonAt(path,
	                "source format '%s' cannot feed target format '%s'"
	                        .formatted(sourceFormat, targetFormat)));
	    }

	    private static Optional<String> arrayItemBoundsCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                      Map<String, Object> targetSchema,
	                                                                      String path) {
	        if (!"array".equals(schemaType(sourceSchema)) || !"array".equals(schemaType(targetSchema))) {
	            return Optional.empty();
	        }
	        Long targetMinimum = arrayMinItems(targetSchema);
	        if (targetMinimum != null) {
	            Long sourceMinimum = arrayMinItems(sourceSchema);
	            if (sourceMinimum == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires item count >= %d but source has no minItems".formatted(targetMinimum)));
	            }
	            if (sourceMinimum < targetMinimum) {
	                return Optional.of(reasonAt(path,
	                        "source minItems %d is weaker than target minItems %d"
	                                .formatted(sourceMinimum, targetMinimum)));
	            }
	        }
	        Long targetMaximum = arrayMaxItems(targetSchema);
	        if (targetMaximum != null) {
	            Long sourceMaximum = arrayMaxItems(sourceSchema);
	            if (sourceMaximum == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires item count <= %d but source has no maxItems".formatted(targetMaximum)));
	            }
	            if (sourceMaximum > targetMaximum) {
	                return Optional.of(reasonAt(path,
	                        "source maxItems %d is weaker than target maxItems %d"
	                                .formatted(sourceMaximum, targetMaximum)));
	            }
	        }
	        return Optional.empty();
	    }

	    private static Optional<String> arrayItemsCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                 Map<String, Object> targetSchema,
	                                                                 String path) {
	        Map<String, Object> targetItems = objectProperty(targetSchema.get("items"));
	        if (targetItems == null) {
	            return Optional.empty();
	        }
	        int firstUniformIndex = Math.max(prefixItemsOf(sourceSchema).size(), prefixItemsOf(targetSchema).size());
	        Long sourceMaximum = arrayMaxItems(sourceSchema);
	        if (sourceMaximum != null && sourceMaximum <= firstUniformIndex) {
	            return Optional.empty();
	        }
	        Map<String, Object> sourceItems = objectProperty(sourceSchema.get("items"));
	        if (sourceItems == null) {
	            return Optional.of(reasonAt(path,
	                    "target requires items but source does not constrain additional array items"));
	        }
	        return schemaCompatibilityIssue(sourceItems, targetItems, appendCompatibilityPath(path, "items"));
	    }

	    private static Optional<String> arrayPrefixItemsCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                       Map<String, Object> targetSchema,
	                                                                       String path) {
	        if (!"array".equals(schemaType(sourceSchema)) || !"array".equals(schemaType(targetSchema))) {
	            return Optional.empty();
	        }
	        List<Map<String, Object>> targetPrefixItems = prefixItemsOf(targetSchema);
	        List<Map<String, Object>> sourcePrefixItems = prefixItemsOf(sourceSchema);
	        Map<String, Object> sourceItems = objectProperty(sourceSchema.get("items"));
	        Map<String, Object> targetItems = objectProperty(targetSchema.get("items"));
	        if (targetPrefixItems.isEmpty() && sourcePrefixItems.size() <= targetPrefixItems.size()) {
	            return Optional.empty();
	        }
	        List<Object> sourceValues = enumValues(sourceSchema);
	        if (!sourceValues.isEmpty()
	                && sourceValues.stream().allMatch(List.class::isInstance)
	                && sourceValues.stream().allMatch(value -> arrayValueMatchesSchema(value, targetSchema))) {
	            return Optional.empty();
	        }
	        Long sourceMaximum = arrayMaxItems(sourceSchema);
	        for (int i = 0; i < targetPrefixItems.size(); i++) {
	            if (sourceMaximum != null && sourceMaximum <= i) {
	                continue;
	            }
	            Map<String, Object> sourceItem = i < sourcePrefixItems.size()
	                    ? sourcePrefixItems.get(i)
	                    : sourceItems;
	            if (sourceItem == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires prefixItems[%d] but source does not constrain that array item"
	                                .formatted(i)));
	            }
	            Optional<String> nestedIssue = schemaCompatibilityIssue(sourceItem, targetPrefixItems.get(i),
	                    appendCompatibilityPath(path, "prefixItems/" + i));
	            if (nestedIssue.isPresent()) {
	                return nestedIssue;
	            }
	        }
	        if (targetItems != null) {
	            for (int i = targetPrefixItems.size(); i < sourcePrefixItems.size(); i++) {
	                if (sourceMaximum != null && sourceMaximum <= i) {
	                    continue;
	                }
	                Optional<String> nestedIssue = schemaCompatibilityIssue(sourcePrefixItems.get(i), targetItems,
	                        appendCompatibilityPath(path, "prefixItems/" + i));
	                if (nestedIssue.isPresent()) {
	                    return nestedIssue;
	                }
	            }
	        }
	        return Optional.empty();
	    }

	    private static Optional<String> arrayUniqueItemsCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                       Map<String, Object> targetSchema,
	                                                                       String path) {
	        if (!"array".equals(schemaType(sourceSchema)) || !"array".equals(schemaType(targetSchema))
	                || !Boolean.TRUE.equals(targetSchema.get("uniqueItems"))) {
	            return Optional.empty();
	        }
	        if (Boolean.TRUE.equals(sourceSchema.get("uniqueItems"))) {
	            return Optional.empty();
	        }
	        List<Object> sourceValues = enumValues(sourceSchema);
	        if (!sourceValues.isEmpty()
	                && sourceValues.stream().allMatch(List.class::isInstance)
	                && sourceValues.stream().allMatch(value -> arrayItemsUnique((List<?>) value))) {
	            return Optional.empty();
	        }
	        return Optional.of(reasonAt(path, "target requires uniqueItems=true but source does not guarantee uniqueness"));
	    }

	    private static Optional<String> arrayContainsCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                    Map<String, Object> targetSchema,
	                                                                    String path) {
	        if (!"array".equals(schemaType(sourceSchema)) || !"array".equals(schemaType(targetSchema))) {
	            return Optional.empty();
	        }
	        Map<String, Object> targetContains = objectProperty(targetSchema.get("contains"));
	        if (targetContains == null) {
	            return Optional.empty();
	        }
	        Long targetMinimum = arrayMinContains(targetSchema);
	        Long targetMaximum = arrayMaxContains(targetSchema);
	        if ((targetMinimum == null || targetMinimum == 0) && targetMaximum == null) {
	            return Optional.empty();
	        }
	        List<Object> sourceValues = enumValues(sourceSchema);
	        if (!sourceValues.isEmpty()
	                && sourceValues.stream().allMatch(List.class::isInstance)
	                && sourceValues.stream().allMatch(value -> arrayValueMatchesSchema(value, targetSchema))) {
	            return Optional.empty();
	        }
	        Map<String, Object> sourceContains = objectProperty(sourceSchema.get("contains"));
	        if (sourceContains == null) {
	            return Optional.of(reasonAt(path,
	                    "target requires contains but source does not guarantee matching array items"));
	        }
	        Optional<String> containsIssue = schemaCompatibilityIssue(sourceContains, targetContains,
	                appendCompatibilityPath(path, "contains"));
	        if (containsIssue.isPresent()) {
	            return containsIssue;
	        }
	        if (targetMinimum != null) {
	            Long sourceMinimum = arrayMinContains(sourceSchema);
	            if (sourceMinimum == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires contains count >= %d but source has no minContains"
	                                .formatted(targetMinimum)));
	            }
	            if (sourceMinimum < targetMinimum) {
	                return Optional.of(reasonAt(path,
	                        "source minContains %d is weaker than target minContains %d"
	                                .formatted(sourceMinimum, targetMinimum)));
	            }
	        }
	        if (targetMaximum != null) {
	            Long sourceMaximum = arrayMaxContains(sourceSchema);
	            if (sourceMaximum == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires contains count <= %d but source has no maxContains"
	                                .formatted(targetMaximum)));
	            }
	            if (sourceMaximum > targetMaximum) {
	                return Optional.of(reasonAt(path,
	                        "source maxContains %d is weaker than target maxContains %d"
	                                .formatted(sourceMaximum, targetMaximum)));
	            }
	        }
	        return Optional.empty();
	    }

	    private static Optional<String> objectSchemaCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                   Map<String, Object> targetSchema,
	                                                                   String path) {
        Map<String, Object> sourceProperties = propertiesOf(sourceSchema);
        Map<String, Object> targetProperties = propertiesOf(targetSchema);
        Set<String> sourceRequired = new LinkedHashSet<>(requiredNamesOf(sourceSchema));
        for (String required : requiredNamesOf(targetSchema)) {
            String childPath = appendCompatibilityPath(path, required);
            Map<String, Object> sourceProperty = objectProperty(sourceProperties.get(required));
            Map<String, Object> targetProperty = objectProperty(targetProperties.get(required));
            if (sourceProperty == null) {
                return Optional.of(reasonAt(childPath,
                        "source object does not declare required field '%s'".formatted(required)));
            }
            if (targetProperty == null) {
                return Optional.of(reasonAt(childPath,
                        "target schema requires undeclared field '%s'".formatted(required)));
            }
            if (!sourceRequired.contains(required)) {
                return Optional.of(reasonAt(childPath,
                        "source object does not guarantee required field '%s'".formatted(required)));
            }
            Optional<String> nested = schemaCompatibilityIssue(sourceProperty, targetProperty, childPath);
            if (nested.isPresent()) {
                return nested;
            }
        }

        Object targetResidual = residualPropertiesPolicy(targetSchema);
        String targetResidualKeyword = residualPropertiesKeyword(targetSchema);
        for (Map.Entry<String, Object> entry : sourceProperties.entrySet()) {
            String propertyName = entry.getKey();
            String childPath = appendCompatibilityPath(path, propertyName);
            Map<String, Object> sourceProperty = objectProperty(entry.getValue());
            if (sourceProperty == null) {
                continue;
            }
            Map<String, Object> targetProperty = objectProperty(targetProperties.get(propertyName));
            List<Map<String, Object>> targetPatternSchemas = matchingPatternPropertySchemas(targetSchema, propertyName);
            if (targetProperty != null) {
                Optional<String> nested = schemaCompatibilityIssue(sourceProperty, targetProperty, childPath);
                if (nested.isPresent()) {
                    return nested;
                }
            }
            for (Map<String, Object> targetPatternSchema : targetPatternSchemas) {
                Optional<String> nested = schemaCompatibilityIssue(sourceProperty, targetPatternSchema, childPath);
                if (nested.isPresent()) {
                    return nested;
                }
            }
            if (targetProperty != null || !targetPatternSchemas.isEmpty()) {
                continue;
            } else if (Boolean.FALSE.equals(targetResidual)) {
                return Optional.of(reasonAt(childPath,
                        "source object declares additional field '%s' but target %s=false"
                                .formatted(propertyName, targetResidualKeyword)));
            } else if (targetResidual instanceof Map<?, ?> residualSchema) {
                Optional<String> nested = schemaCompatibilityIssue(sourceProperty, objectProperty(residualSchema),
                        childPath);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
	        Optional<String> additionalIssue =
	                residualPropertiesCompatibilityIssue(sourceSchema, targetResidual, targetResidualKeyword, path);
	        if (additionalIssue.isPresent()) {
	            return additionalIssue;
	        }
	        Optional<String> patternPropertiesIssue =
	                objectPatternPropertiesCompatibilityIssue(sourceSchema, targetSchema, path);
	        if (patternPropertiesIssue.isPresent()) {
	            return patternPropertiesIssue;
	        }
	        Optional<String> propertyNamesIssue = objectPropertyNamesCompatibilityIssue(sourceSchema, targetSchema, path);
	        if (propertyNamesIssue.isPresent()) {
	            return propertyNamesIssue;
	        }
	        Optional<String> dependentRequiredIssue =
	                objectDependentRequiredCompatibilityIssue(sourceSchema, targetSchema, path);
	        if (dependentRequiredIssue.isPresent()) {
	            return dependentRequiredIssue;
	        }
	        Optional<String> dependentSchemasIssue =
	                objectDependentSchemasCompatibilityIssue(sourceSchema, targetSchema, path);
	        return dependentSchemasIssue.isPresent()
	                ? dependentSchemasIssue
	                : objectPropertyBoundsCompatibilityIssue(sourceSchema, targetSchema, path);
	    }

    private static Optional<String> residualPropertiesCompatibilityIssue(Map<String, Object> sourceSchema,
                                                                         Object targetResidual,
                                                                         String targetResidualKeyword,
                                                                         String path) {
        Object sourceResidual = residualPropertiesPolicy(sourceSchema);
        if (Boolean.FALSE.equals(targetResidual)) {
            return Boolean.FALSE.equals(sourceResidual)
                    ? Optional.empty()
                    : Optional.of(reasonAt(path,
                    "source object allows undeclared additional fields but target %s=false"
                            .formatted(targetResidualKeyword)));
        }
        if (targetResidual instanceof Map<?, ?> targetResidualSchema) {
            if (sourceResidual == null || Boolean.TRUE.equals(sourceResidual)) {
                return Optional.of(reasonAt(path,
                        "source object allows unconstrained additional fields but target %s requires %s"
                                .formatted(targetResidualKeyword, schemaTypeLabel(objectProperty(targetResidualSchema)))));
            }
            if (sourceResidual instanceof Map<?, ?> sourceResidualSchema) {
                return schemaCompatibilityIssue(objectProperty(sourceResidualSchema),
                        objectProperty(targetResidualSchema), appendCompatibilityPath(path, targetResidualKeyword));
            }
        }
        return Optional.empty();
    }

	    private static Optional<String> objectPatternPropertiesCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                              Map<String, Object> targetSchema,
	                                                                              String path) {
	        Map<String, Object> targetPatterns = patternPropertiesOf(targetSchema);
	        if (targetPatterns == null || targetPatterns.isEmpty()) {
	            return Optional.empty();
	        }
	        List<Object> sourceValues = enumValues(sourceSchema);
	        if (!sourceValues.isEmpty()
	                && sourceValues.stream().allMatch(Map.class::isInstance)
	                && sourceValues.stream().allMatch(value -> objectValueMatchesSchema(value, targetSchema))) {
	            return Optional.empty();
	        }
	        Map<String, Object> sourcePatterns = patternPropertiesOf(sourceSchema);
	        if ((sourcePatterns == null || sourcePatterns.isEmpty())
	                && Boolean.FALSE.equals(residualPropertiesPolicy(sourceSchema))) {
	            return Optional.empty();
	        }
	        if (sourcePatterns != null && Objects.equals(sourcePatterns, targetPatterns)) {
	            return Optional.empty();
	        }
	        return Optional.of(reasonAt(path,
	                "target requires patternProperties but source does not guarantee matching dynamic fields"));
	    }

	    private static Optional<String> objectPropertyNamesCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                          Map<String, Object> targetSchema,
	                                                                          String path) {
	        Map<String, Object> targetPropertyNames = propertyNameSchema(targetSchema);
	        if (targetPropertyNames == null) {
	            return Optional.empty();
	        }
	        List<Object> sourceValues = enumValues(sourceSchema);
	        if (!sourceValues.isEmpty()
	                && sourceValues.stream().allMatch(Map.class::isInstance)
	                && sourceValues.stream().allMatch(value -> objectValueMatchesPropertyNames((Map<?, ?>) value,
	                        targetSchema))) {
	            return Optional.empty();
	        }
	        if (Boolean.FALSE.equals(residualPropertiesPolicy(sourceSchema))
	                && propertiesOf(sourceSchema).keySet().stream()
	                .allMatch(name -> valueMatchesSchema(name, effectivePropertyNameSchema(targetPropertyNames)))) {
	            return Optional.empty();
	        }
	        Map<String, Object> sourcePropertyNames = propertyNameSchema(sourceSchema);
	        if (sourcePropertyNames != null
	                && Objects.equals(effectivePropertyNameSchema(sourcePropertyNames),
	                effectivePropertyNameSchema(targetPropertyNames))) {
	            return Optional.empty();
	        }
	        return Optional.of(reasonAt(path,
	                "target requires propertyNames but source does not guarantee matching property names"));
	    }

	    private static Optional<String> objectDependentRequiredCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                              Map<String, Object> targetSchema,
	                                                                              String path) {
	        Map<String, List<String>> targetDependencies = dependentRequiredOf(targetSchema);
	        if (targetDependencies.isEmpty()) {
	            return Optional.empty();
	        }
	        List<Object> sourceValues = enumValues(sourceSchema);
	        if (!sourceValues.isEmpty()
	                && sourceValues.stream().allMatch(Map.class::isInstance)
	                && sourceValues.stream().allMatch(value -> objectValueMatchesSchema(value, targetSchema))) {
	            return Optional.empty();
	        }
	        Set<String> sourceRequired = new LinkedHashSet<>(requiredNamesOf(sourceSchema));
	        Map<String, List<String>> sourceDependencies = dependentRequiredOf(sourceSchema);
	        for (Map.Entry<String, List<String>> entry : targetDependencies.entrySet()) {
	            String trigger = entry.getKey();
	            if (sourceCannotContainProperty(sourceSchema, trigger)) {
	                continue;
	            }
	            List<String> sourceTriggerDependencies = sourceDependencies.getOrDefault(trigger, List.of());
	            for (String dependency : entry.getValue()) {
	                if (!sourceRequired.contains(dependency)
	                        && !sourceTriggerDependencies.contains(dependency)) {
	                    return Optional.of(reasonAt(path,
	                            "target requires dependentRequired '%s' -> '%s' but source does not guarantee the dependency"
	                                    .formatted(trigger, dependency)));
	                }
	            }
	        }
	        return Optional.empty();
	    }

	    private static Optional<String> objectDependentSchemasCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                             Map<String, Object> targetSchema,
	                                                                             String path) {
	        Map<String, Map<String, Object>> targetDependencies = dependentSchemasOf(targetSchema);
	        if (targetDependencies.isEmpty()) {
	            return Optional.empty();
	        }
	        List<Object> sourceValues = enumValues(sourceSchema);
	        if (!sourceValues.isEmpty()
	                && sourceValues.stream().allMatch(Map.class::isInstance)
	                && sourceValues.stream().allMatch(value -> objectValueMatchesSchema(value, targetSchema))) {
	            return Optional.empty();
	        }
	        Map<String, Map<String, Object>> sourceDependencies = dependentSchemasOf(sourceSchema);
	        for (Map.Entry<String, Map<String, Object>> entry : targetDependencies.entrySet()) {
	            String trigger = entry.getKey();
	            if (sourceCannotContainProperty(sourceSchema, trigger)) {
	                continue;
	            }
	            Map<String, Object> targetDependentSchema = effectiveDependentObjectSchema(entry.getValue());
	            Optional<String> globalIssue = schemaCompatibilityIssue(sourceSchema, targetDependentSchema,
	                    appendCompatibilityPath(path, "dependentSchemas/" + trigger));
	            if (globalIssue.isEmpty()) {
	                continue;
	            }
	            Map<String, Object> sourceDependentSchema = sourceDependencies.get(trigger);
	            if (sourceDependentSchema != null
	                    && schemaCompatibilityIssue(effectiveDependentObjectSchema(sourceDependentSchema),
	                    targetDependentSchema,
	                    appendCompatibilityPath(path, "dependentSchemas/" + trigger)).isEmpty()) {
	                continue;
	            }
	            return Optional.of(reasonAt(path,
	                    "target requires dependentSchemas '%s' but source does not guarantee the dependent schema"
	                            .formatted(trigger)));
	        }
	        return Optional.empty();
	    }

	    private static Optional<String> objectPropertyBoundsCompatibilityIssue(Map<String, Object> sourceSchema,
	                                                                           Map<String, Object> targetSchema,
	                                                                           String path) {
	        if (!"object".equals(schemaType(sourceSchema)) || !"object".equals(schemaType(targetSchema))) {
	            return Optional.empty();
	        }
	        Long targetMinimum = objectMinProperties(targetSchema);
	        if (targetMinimum != null) {
	            Long sourceMinimum = objectMinProperties(sourceSchema);
	            if (sourceMinimum == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires property count >= %d but source has no minProperties"
	                                .formatted(targetMinimum)));
	            }
	            if (sourceMinimum < targetMinimum) {
	                return Optional.of(reasonAt(path,
	                        "source minProperties %d is weaker than target minProperties %d"
	                                .formatted(sourceMinimum, targetMinimum)));
	            }
	        }
	        Long targetMaximum = objectMaxProperties(targetSchema);
	        if (targetMaximum != null) {
	            Long sourceMaximum = objectMaxProperties(sourceSchema);
	            if (sourceMaximum == null) {
	                return Optional.of(reasonAt(path,
	                        "target requires property count <= %d but source has no maxProperties"
	                                .formatted(targetMaximum)));
	            }
	            if (sourceMaximum > targetMaximum) {
	                return Optional.of(reasonAt(path,
	                        "source maxProperties %d is weaker than target maxProperties %d"
	                                .formatted(sourceMaximum, targetMaximum)));
	            }
	        }
	        return Optional.empty();
	    }

    /**
     * @param schema schema
     * @return readable type label used by diagnostics
     */
    public static String schemaTypeLabel(Map<String, Object> schema) {
        List<Object> values = enumValues(schema);
        if (!values.isEmpty()) {
            return "enum<" + String.join("|", values.stream().map(String::valueOf).toList()) + ">";
        }
        String type = schemaType(schema);
        if ("array".equals(type)) {
            Map<String, Object> items = objectProperty(schema.get("items"));
            return items == null ? "array" : "array<" + schemaTypeLabel(items) + ">";
        }
        return type.isBlank() ? "unknown" : type;
    }

    /**
     * @param reason compatibility reason
     * @return sentence suffix for diagnostics
     */
    public static String compatibilityReason(String reason) {
        return reason == null || reason.isBlank() ? "" : " Reason: " + reason + ".";
    }

    /**
     * @param expression raw expression
     * @return literal schema when the expression is statically known to be a literal value
     */
    public static Optional<StaticExpressionLiteral> staticExpressionLiteral(String expression) {
        String value = expression == null ? "" : expression.trim();
        if (value.isBlank()) {
            return Optional.empty();
        }
        if ("null".equals(value)) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(null)));
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(Boolean.valueOf(value))));
        }
        Optional<String> stringLiteral = parseStringLiteral(value);
        if (stringLiteral.isPresent()) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(stringLiteral.get())));
        }
        if (INTEGER_LITERAL.matcher(value).matches()) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(parseIntegerLiteral(value))));
        }
        if (NUMBER_LITERAL.matcher(value).matches()) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(Double.valueOf(value))));
        }
        return Optional.empty();
    }

    private static Optional<String> parseStringLiteral(String value) {
        if (value.length() < 2) {
            return Optional.empty();
        }
        char quote = value.charAt(0);
        if ((quote != '"' && quote != '\'') || value.charAt(value.length() - 1) != quote) {
            return Optional.empty();
        }
        StringBuilder result = new StringBuilder(value.length() - 2);
        boolean escaped = false;
        for (int i = 1; i < value.length() - 1; i++) {
            char current = value.charAt(i);
            if (escaped) {
                result.append(unescapedChar(current));
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                return Optional.empty();
            } else {
                result.append(current);
            }
        }
        return escaped ? Optional.empty() : Optional.of(result.toString());
    }

    private static char unescapedChar(char value) {
        return switch (value) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> value;
        };
    }

    private static Object parseIntegerLiteral(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                return (int) parsed;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            return Double.valueOf(value);
        }
    }

    private static Map<String, Object> literalEnumSchema(Object value) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "enum");
        List<Object> values = new ArrayList<>();
        values.add(value);
        schema.put("values", values);
        return schema;
    }

    private static List<Object> enumValues(Map<String, Object> schema) {
        if (schema.containsKey("const")) {
            List<Object> values = new ArrayList<>();
            values.add(schema.get("const"));
            return values;
        }
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values) {
            return values.stream().map(Object.class::cast).distinct().toList();
        }
        if ("enum".equals(schemaType(schema)) && schema.get("values") instanceof List<?> values) {
            return values.stream().map(Object.class::cast).distinct().toList();
        }
        return List.of();
    }

    private static String appendCompatibilityPath(String path, String segment) {
        if (path == null || path.isBlank()) {
            return segment;
        }
        return path + "." + segment;
    }

    private static String reasonAt(String path, String reason) {
        return path == null || path.isBlank() ? reason : "at '%s': %s".formatted(path, reason);
    }

    private static String valueDomainLabel(List<Object> values) {
        return values.stream().map(String::valueOf).toList().toString();
    }

	    private static boolean numeric(String type) {
	        return "number".equals(type) || "integer".equals(type) || "decimal".equals(type);
	    }

	    private static boolean stringLike(String type) {
	        return "string".equals(type) || "duration".equals(type) || "datetime".equals(type);
	    }

    private static boolean valueMatchesType(Object value, String type) {
        return switch (type) {
            case "string", "duration", "datetime" -> value instanceof String;
            case "integer" -> isIntegerValue(value);
            case "number", "decimal" -> value instanceof Number;
	            case "boolean" -> value instanceof Boolean;
	            case "null" -> value == null;
	            case "array" -> value instanceof List<?>;
	            case "object" -> value instanceof Map<?, ?>;
	            default -> true;
	        };
	    }

    private static boolean valueMatchesSchema(Object value, Map<String, Object> schema) {
        String type = schemaType(schema);
        if (!valueMatchesType(value, type)) {
            return false;
        }
        List<Object> values = enumValues(schema);
        if (!values.isEmpty() && !values.contains(value)) {
            return false;
        }
		        return numericValueMatchesBounds(value, schema)
		                && numericValueMatchesMultipleOf(value, schema)
		                && stringValueMatchesLengthBounds(value, schema)
		                && stringValueMatchesPattern(value, schema)
		                && stringValueMatchesFormat(value, schema)
		                && arrayValueMatchesSchema(value, schema)
		                && objectValueMatchesSchema(value, schema);
		    }

	    private static boolean numericValueMatchesBounds(Object value, Map<String, Object> schema) {
	        if (!(value instanceof Number number)) {
	            return true;
        }
        double numericValue = number.doubleValue();
        NumericBoundary lower = lowerBound(schema);
        if (lower != null && !lower.acceptsLower(numericValue)) {
            return false;
        }
	        NumericBoundary upper = upperBound(schema);
	        return upper == null || upper.acceptsUpper(numericValue);
	    }

	    private static boolean numericValueMatchesMultipleOf(Object value, Map<String, Object> schema) {
	        if (!(value instanceof Number number)) {
	            return true;
	        }
	        Double multipleOf = numericMultipleOf(schema.get("multipleOf"));
	        return multipleOf == null || numericValueIsMultipleOf(number.doubleValue(), multipleOf);
	    }

	    private static boolean stringValueMatchesLengthBounds(Object value, Map<String, Object> schema) {
	        if (!(value instanceof String string)) {
	            return true;
	        }
	        long length = string.codePoints().count();
	        Long minimum = stringMinLength(schema);
	        if (minimum != null && length < minimum) {
	            return false;
	        }
	        Long maximum = stringMaxLength(schema);
	        return maximum == null || length <= maximum;
	    }

		    private static boolean stringValueMatchesPattern(Object value, Map<String, Object> schema) {
		        if (!(value instanceof String string)) {
		            return true;
	        }
		        String rawPattern = stringPattern(schema);
	        if (rawPattern == null) {
	            return true;
	        }
	        try {
	            return Pattern.compile(rawPattern).matcher(string).find();
	        } catch (PatternSyntaxException ex) {
	            return true;
	        }
		    }

	    private static boolean stringValueMatchesFormat(Object value, Map<String, Object> schema) {
	        if (!(value instanceof String string)) {
	            return true;
	        }
	        String format = stringFormat(schema);
	        return format == null || stringMatchesFormat(string, format);
	    }

	    @SuppressWarnings("unchecked")
		    private static boolean arrayValueMatchesSchema(Object value, Map<String, Object> schema) {
		        if (!(value instanceof List<?> list) || !"array".equals(schemaType(schema))) {
		            return true;
	        }
	        if (!arrayValueMatchesItemBounds(list, schema)) {
	            return false;
	        }
	        if (!arrayValueMatchesUniqueItems(list, schema)) {
	            return false;
	        }
	        if (!arrayValueMatchesContains(list, schema)) {
	            return false;
	        }
		        for (int i = 0; i < list.size(); i++) {
		            Map<String, Object> itemSchema = arrayItemSchemaForIndex(schema, i);
		            if (itemSchema != null && !valueMatchesSchema(list.get(i), itemSchema)) {
		                return false;
		            }
	        }
		        return true;
		    }

	    private static Map<String, Object> arrayItemSchemaForIndex(Map<String, Object> schema, int index) {
	        List<Map<String, Object>> prefixItems = prefixItemsOf(schema);
	        if (index < prefixItems.size()) {
	            return prefixItems.get(index);
	        }
	        return objectProperty(schema.get("items"));
	    }

	    private static List<Map<String, Object>> prefixItemsOf(Map<String, Object> schema) {
	        Object raw = schema.get("prefixItems");
	        if (!(raw instanceof List<?> values)) {
	            return List.of();
	        }
	        List<Map<String, Object>> prefixItems = new ArrayList<>();
	        for (Object value : values) {
	            Map<String, Object> itemSchema = objectProperty(value);
	            if (itemSchema != null) {
	                prefixItems.add(itemSchema);
	            }
	        }
	        return prefixItems;
	    }

	    @SuppressWarnings("unchecked")
	    private static boolean objectValueMatchesSchema(Object value, Map<String, Object> schema) {
	        if (!(value instanceof Map<?, ?> rawMap) || !"object".equals(schemaType(schema))) {
	            return true;
	        }
	        Map<String, Object> object = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> object.put(String.valueOf(key), item));
	        if (!objectValueMatchesPropertyBounds(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesPropertyNames(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesPatternProperties(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesDependentRequired(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesDependentSchemas(object, schema)) {
	            return false;
	        }
	        Map<String, Object> properties = propertiesOf(schema);
	        for (String required : requiredNamesOf(schema)) {
	            if (!object.containsKey(required) || object.get(required) == null) {
	                return false;
	            }
	        }
	        Object residual = residualPropertiesPolicy(schema);
	        for (Map.Entry<String, Object> entry : object.entrySet()) {
	            Map<String, Object> property = objectProperty(properties.get(entry.getKey()));
	            List<Map<String, Object>> patternSchemas = matchingPatternPropertySchemas(schema, entry.getKey());
	            if (property != null) {
	                if (!valueMatchesSchema(entry.getValue(), property)) {
	                    return false;
	                }
	            }
	            for (Map<String, Object> patternSchema : patternSchemas) {
	                if (!valueMatchesSchema(entry.getValue(), patternSchema)) {
	                    return false;
	                }
	            }
	            if (property != null || !patternSchemas.isEmpty()) {
	                continue;
	            } else if (Boolean.FALSE.equals(residual)) {
	                return false;
	            } else if (residual instanceof Map<?, ?> residualSchema
	                    && !valueMatchesSchema(entry.getValue(), (Map<String, Object>) residualSchema)) {
	                return false;
	            }
	        }
	        return true;
	    }

    private static boolean isIntegerValue(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return true;
        }
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            return Double.isFinite(doubleValue) && Math.rint(doubleValue) == doubleValue;
        }
        return false;
    }

    private static List<String> requiredNamesOf(Map<String, Object> schema) {
        Object raw = schema.get("required");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                required.add(String.valueOf(item));
            }
        }
        return required;
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        Object nested = schema.get("properties");
        if (!(nested instanceof Map<?, ?> rawNested)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        rawNested.forEach((key, item) -> properties.put(String.valueOf(key), item));
        return properties;
    }

    private static Map<String, Object> objectProperty(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static String schemaType(Map<String, Object> property) {
        if (property == null) {
            return "";
        }
        Object type = property.get("kind");
        if (type == null) {
            type = property.get("type");
        }
        if (type == null && property.containsKey("properties")) {
            return "object";
        }
        if (type == null && property.containsKey("items")) {
            return "array";
        }
        if (type == null && property.containsKey("const")) {
            return schemaTypeForValue(property.get("const"));
        }
        return type == null ? "" : String.valueOf(type);
    }

	    private static String schemaTypeForValue(Object value) {
	        if (value == null) {
	            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (isIntegerValue(value)) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
	        return "";
	    }

	    private static Long stringMinLength(Map<String, Object> schema) {
	        return stringLengthBoundary(schema.get("minLength"));
	    }

	    private static Long stringMaxLength(Map<String, Object> schema) {
	        return stringLengthBoundary(schema.get("maxLength"));
	    }

		    private static String stringPattern(Map<String, Object> schema) {
		        Object rawPattern = schema.get("pattern");
		        return rawPattern instanceof String pattern ? pattern : null;
		    }

	    private static String stringFormat(Map<String, Object> schema) {
	        Object rawFormat = schema.get("format");
	        return rawFormat instanceof String format && SUPPORTED_STRING_FORMATS.contains(format) ? format : null;
	    }

	    private static boolean stringMatchesFormat(String value, String format) {
	        try {
	            switch (format) {
	                case "date" -> LocalDate.parse(value);
	                case "date-time" -> OffsetDateTime.parse(value);
	                case "duration" -> Duration.parse(value);
	                case "email" -> {
	                    return EMAIL_PATTERN.matcher(value).matches();
	                }
	                case "uri" -> {
	                    URI uri = new URI(value);
	                    return uri.isAbsolute();
	                }
	                case "uuid" -> UUID.fromString(value);
	                default -> {
	                    return true;
	                }
	            }
	            return true;
	        } catch (DateTimeParseException | IllegalArgumentException | URISyntaxException ex) {
	            return false;
	        }
	    }

	    private static Long stringLengthBoundary(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
	            return null;
	        }
	        return (long) numericValue;
	    }

	    private static Long arrayMinItems(Map<String, Object> schema) {
	        Long explicit = arrayItemBoundary(schema.get("minItems"));
	        if (explicit != null) {
	            return explicit;
	        }
	        List<Object> values = enumValues(schema);
	        if (!values.isEmpty() && values.stream().allMatch(List.class::isInstance)) {
	            return values.stream()
	                    .map(value -> (long) ((List<?>) value).size())
	                    .min(Long::compareTo)
	                    .orElse(null);
	        }
	        return null;
	    }

	    private static Long arrayMaxItems(Map<String, Object> schema) {
	        Long explicit = arrayItemBoundary(schema.get("maxItems"));
	        if (explicit != null) {
	            return explicit;
	        }
	        List<Object> values = enumValues(schema);
	        if (!values.isEmpty() && values.stream().allMatch(List.class::isInstance)) {
	            return values.stream()
	                    .map(value -> (long) ((List<?>) value).size())
	                    .max(Long::compareTo)
	                    .orElse(null);
	        }
	        return null;
	    }

	    private static Long arrayMinContains(Map<String, Object> schema) {
	        if (!schema.containsKey("contains")) {
	            return null;
	        }
	        Long explicit = arrayItemBoundary(schema.get("minContains"));
	        return explicit == null ? 1L : explicit;
	    }

	    private static Long arrayMaxContains(Map<String, Object> schema) {
	        return arrayItemBoundary(schema.get("maxContains"));
	    }

	    private static boolean arrayValueMatchesItemBounds(List<?> value, Map<String, Object> schema) {
	        long size = value.size();
	        Long minimum = arrayItemBoundary(schema.get("minItems"));
	        if (minimum != null && size < minimum) {
	            return false;
	        }
	        Long maximum = arrayItemBoundary(schema.get("maxItems"));
	        return maximum == null || size <= maximum;
	    }

		    private static boolean arrayValueMatchesUniqueItems(List<?> value, Map<String, Object> schema) {
		        return !Boolean.TRUE.equals(schema.get("uniqueItems")) || arrayItemsUnique(value);
		    }

	    private static boolean arrayValueMatchesContains(List<?> value, Map<String, Object> schema) {
	        Map<String, Object> contains = objectProperty(schema.get("contains"));
	        if (contains == null) {
	            return true;
	        }
	        long matches = value.stream()
	                .filter(item -> valueMatchesSchema(item, contains))
	                .count();
	        Long minimum = arrayMinContains(schema);
	        if (minimum != null && matches < minimum) {
	            return false;
	        }
	        Long maximum = arrayMaxContains(schema);
	        return maximum == null || matches <= maximum;
	    }

	    private static boolean arrayItemsUnique(List<?> value) {
	        return new LinkedHashSet<>(value).size() == value.size();
	    }

		    private static Long arrayItemBoundary(Object value) {
		        if (!(value instanceof Number number)) {
		            return null;
	        }
		        double numericValue = number.doubleValue();
		        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
		            return null;
	        }
		        return (long) numericValue;
		    }

	    private static Long objectMinProperties(Map<String, Object> schema) {
	        Long explicit = objectPropertyBoundary(schema.get("minProperties"));
	        if (explicit != null) {
	            return explicit;
	        }
	        List<Object> values = enumValues(schema);
	        if (!values.isEmpty() && values.stream().allMatch(Map.class::isInstance)) {
	            return values.stream()
	                    .map(value -> (long) ((Map<?, ?>) value).size())
	                    .min(Long::compareTo)
	                    .orElse(null);
	        }
	        return null;
	    }

	    private static Long objectMaxProperties(Map<String, Object> schema) {
	        Long explicit = objectPropertyBoundary(schema.get("maxProperties"));
	        if (explicit != null) {
	            return explicit;
	        }
	        List<Object> values = enumValues(schema);
	        if (!values.isEmpty() && values.stream().allMatch(Map.class::isInstance)) {
	            return values.stream()
	                    .map(value -> (long) ((Map<?, ?>) value).size())
	                    .max(Long::compareTo)
	                    .orElse(null);
	        }
	        return null;
	    }

	    private static boolean objectValueMatchesPropertyBounds(Map<?, ?> value, Map<String, Object> schema) {
	        long size = value.size();
	        Long minimum = objectPropertyBoundary(schema.get("minProperties"));
	        if (minimum != null && size < minimum) {
	            return false;
	        }
	        Long maximum = objectPropertyBoundary(schema.get("maxProperties"));
	        return maximum == null || size <= maximum;
	    }

	    private static boolean objectValueMatchesPropertyNames(Map<?, ?> value, Map<String, Object> schema) {
	        Map<String, Object> propertyNameSchema = propertyNameSchema(schema);
	        if (propertyNameSchema == null) {
	            return true;
	        }
	        Map<String, Object> effectiveSchema = effectivePropertyNameSchema(propertyNameSchema);
	        return value.keySet().stream()
	                .map(String::valueOf)
	                .allMatch(name -> valueMatchesSchema(name, effectiveSchema));
	    }

	    private static boolean objectValueMatchesPatternProperties(Map<?, ?> value, Map<String, Object> schema) {
	        for (Map.Entry<?, ?> entry : value.entrySet()) {
	            for (Map<String, Object> patternSchema : matchingPatternPropertySchemas(schema,
	                    String.valueOf(entry.getKey()))) {
	                if (!valueMatchesSchema(entry.getValue(), patternSchema)) {
	                    return false;
	                }
	            }
	        }
	        return true;
	    }

	    private static boolean objectValueMatchesDependentRequired(Map<?, ?> value, Map<String, Object> schema) {
	        Map<String, List<String>> dependencies = dependentRequiredOf(schema);
	        if (dependencies.isEmpty()) {
	            return true;
	        }
	        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
	            if (!presentObjectProperty(value, entry.getKey())) {
	                continue;
	            }
	            for (String dependency : entry.getValue()) {
	                if (!presentObjectProperty(value, dependency)) {
	                    return false;
	                }
	            }
	        }
	        return true;
	    }

	    private static boolean objectValueMatchesDependentSchemas(Map<?, ?> value, Map<String, Object> schema) {
	        Map<String, Map<String, Object>> dependencies = dependentSchemasOf(schema);
	        if (dependencies.isEmpty()) {
	            return true;
	        }
	        for (Map.Entry<String, Map<String, Object>> entry : dependencies.entrySet()) {
	            if (!presentObjectProperty(value, entry.getKey())) {
	                continue;
	            }
	            if (!valueMatchesSchema(value, effectiveDependentObjectSchema(entry.getValue()))) {
	                return false;
	            }
	        }
	        return true;
	    }

	    private static Map<String, List<String>> dependentRequiredOf(Map<String, Object> schema) {
	        Object raw = schema.get("dependentRequired");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return Map.of();
	        }
	        Map<String, List<String>> dependencies = new LinkedHashMap<>();
	        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
	            if (!(entry.getValue() instanceof List<?> rawDependencies)) {
	                continue;
	            }
	            List<String> names = new ArrayList<>();
	            for (Object dependency : rawDependencies) {
	                if (dependency instanceof String name && !name.isBlank()) {
	                    names.add(name);
	                }
	            }
	            dependencies.put(String.valueOf(entry.getKey()), names);
	        }
	        return dependencies;
	    }

	    private static Map<String, Map<String, Object>> dependentSchemasOf(Map<String, Object> schema) {
	        Object raw = schema.get("dependentSchemas");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return Map.of();
	        }
	        Map<String, Map<String, Object>> dependencies = new LinkedHashMap<>();
	        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
	            if (!(entry.getValue() instanceof Map<?, ?> rawSchema)) {
	                continue;
	            }
	            Map<String, Object> copy = new LinkedHashMap<>();
	            rawSchema.forEach((key, item) -> copy.put(String.valueOf(key), item));
	            dependencies.put(String.valueOf(entry.getKey()), effectiveDependentObjectSchema(copy));
	        }
	        return dependencies;
	    }

	    private static Map<String, Object> effectiveDependentObjectSchema(Map<String, Object> schema) {
	        Map<String, Object> effective = new LinkedHashMap<>(schema);
	        if (schemaType(effective).isBlank()
	                && (effective.containsKey("required")
	                || effective.containsKey("dependentRequired")
	                || effective.containsKey("dependentSchemas")
	                || effective.containsKey("minProperties")
	                || effective.containsKey("maxProperties")
	                || effective.containsKey("propertyNames")
	                || effective.containsKey("patternProperties")
	                || effective.containsKey("unevaluatedProperties"))) {
	            effective.put("type", "object");
	        }
	        return effective;
	    }

	    private static boolean presentObjectProperty(Map<?, ?> value, String property) {
	        return value.containsKey(property) && value.get(property) != null;
	    }

	    private static boolean sourceCannotContainProperty(Map<String, Object> sourceSchema, String property) {
	        return Boolean.FALSE.equals(residualPropertiesPolicy(sourceSchema))
	                && !propertiesOf(sourceSchema).containsKey(property)
	                && matchingPatternPropertySchemas(sourceSchema, property).isEmpty();
	    }

	    private static Object residualPropertiesPolicy(Map<String, Object> schema) {
	        if (schema.containsKey("additionalProperties")) {
	            return schema.get("additionalProperties");
	        }
	        return schema.get("unevaluatedProperties");
	    }

	    private static String residualPropertiesKeyword(Map<String, Object> schema) {
	        return schema.containsKey("additionalProperties")
	                ? "additionalProperties"
	                : "unevaluatedProperties";
	    }

	    private static List<Map<String, Object>> matchingPatternPropertySchemas(Map<String, Object> schema,
	                                                                            String propertyName) {
	        Map<String, Object> patternProperties = patternPropertiesOf(schema);
	        if (patternProperties == null || patternProperties.isEmpty()) {
	            return List.of();
	        }
	        List<Map<String, Object>> matches = new ArrayList<>();
	        for (Map.Entry<String, Object> entry : patternProperties.entrySet()) {
	            if (patternMatches(entry.getKey(), propertyName) && entry.getValue() instanceof Map<?, ?> nested) {
	                Map<String, Object> copy = new LinkedHashMap<>();
	                nested.forEach((key, item) -> copy.put(String.valueOf(key), item));
	                matches.add(copy);
	            }
	        }
	        return matches;
	    }

	    private static Map<String, Object> patternPropertiesOf(Map<String, Object> schema) {
	        Object raw = schema.get("patternProperties");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return null;
	        }
	        Map<String, Object> patternProperties = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> patternProperties.put(String.valueOf(key), item));
	        return patternProperties;
	    }

	    private static boolean patternMatches(String pattern, String value) {
	        try {
	            return Pattern.compile(pattern).matcher(value).find();
	        } catch (PatternSyntaxException ex) {
	            return false;
	        }
	    }

	    private static Map<String, Object> propertyNameSchema(Map<String, Object> schema) {
	        Object raw = schema.get("propertyNames");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return null;
	        }
	        Map<String, Object> propertyNameSchema = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> propertyNameSchema.put(String.valueOf(key), item));
	        return propertyNameSchema;
	    }

	    private static Map<String, Object> effectivePropertyNameSchema(Map<String, Object> propertyNameSchema) {
	        Map<String, Object> effective = new LinkedHashMap<>(propertyNameSchema);
	        if (schemaType(effective).isBlank()) {
	            effective.put("type", "string");
	        }
	        return effective;
	    }

	    private static Long objectPropertyBoundary(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
	            return null;
	        }
	        return (long) numericValue;
	    }

	    private static NumericBoundary lowerBound(Map<String, Object> schema) {
        NumericBoundary minimum = numericBoundary(schema.get("minimum"), false);
        NumericBoundary exclusiveMinimum = numericBoundary(schema.get("exclusiveMinimum"), true);
        if (minimum == null) {
            return exclusiveMinimum;
        }
        if (exclusiveMinimum == null) {
            return minimum;
        }
        int comparison = Double.compare(minimum.value(), exclusiveMinimum.value());
        if (comparison > 0) {
            return minimum;
        }
        if (comparison < 0) {
            return exclusiveMinimum;
        }
        return exclusiveMinimum.exclusive() ? exclusiveMinimum : minimum;
    }

    private static NumericBoundary upperBound(Map<String, Object> schema) {
        NumericBoundary maximum = numericBoundary(schema.get("maximum"), false);
        NumericBoundary exclusiveMaximum = numericBoundary(schema.get("exclusiveMaximum"), true);
        if (maximum == null) {
            return exclusiveMaximum;
        }
        if (exclusiveMaximum == null) {
            return maximum;
        }
        int comparison = Double.compare(maximum.value(), exclusiveMaximum.value());
        if (comparison < 0) {
            return maximum;
        }
        if (comparison > 0) {
            return exclusiveMaximum;
        }
        return exclusiveMaximum.exclusive() ? exclusiveMaximum : maximum;
    }

	    private static NumericBoundary numericBoundary(Object value, boolean exclusive) {
	        if (!(value instanceof Number number)) {
	            return null;
        }
        double numericValue = number.doubleValue();
	        return Double.isFinite(numericValue) ? new NumericBoundary(numericValue, exclusive) : null;
	    }

	    private static Double numericMultipleOf(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        return Double.isFinite(numericValue) && numericValue > 0 ? numericValue : null;
	    }

	    private static boolean numericValueIsMultipleOf(double value, double multipleOf) {
	        if (!Double.isFinite(value) || !Double.isFinite(multipleOf) || multipleOf <= 0) {
	            return true;
	        }
	        double quotient = value / multipleOf;
	        double nearest = Math.rint(quotient);
	        double tolerance = 1.0e-9 * Math.max(1.0, Math.abs(quotient));
	        return Math.abs(quotient - nearest) <= tolerance;
	    }

	    private static String numberLabel(double value) {
	        long whole = (long) value;
	        return value == whole ? Long.toString(whole) : Double.toString(value);
	    }

    private static boolean lowerBoundAtLeast(NumericBoundary source, NumericBoundary target) {
        int comparison = Double.compare(source.value(), target.value());
        return comparison > 0 || comparison == 0 && (source.exclusive() || !target.exclusive());
    }

    private static boolean upperBoundAtMost(NumericBoundary source, NumericBoundary target) {
        int comparison = Double.compare(source.value(), target.value());
        return comparison < 0 || comparison == 0 && (source.exclusive() || !target.exclusive());
    }

    private record NumericBoundary(double value, boolean exclusive) {

        private boolean acceptsLower(double candidate) {
            return exclusive ? candidate > value : candidate >= value;
        }

        private boolean acceptsUpper(double candidate) {
            return exclusive ? candidate < value : candidate <= value;
        }

        private String lowerLabel() {
            return exclusive ? "value > " + trimNumber(value) : "value >= " + trimNumber(value);
        }

        private String upperLabel() {
            return exclusive ? "value < " + trimNumber(value) : "value <= " + trimNumber(value);
        }

        private static String trimNumber(double value) {
            if (Math.rint(value) == value) {
                return String.valueOf((long) value);
            }
            return String.valueOf(value);
        }
    }

    /**
     * Static expression literal with its derived schema.
     *
     * @param label literal expression text
     * @param schema single-value schema
     */
    public record StaticExpressionLiteral(String label, Map<String, Object> schema) {
    }
}
