package com.iso.validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iso.validation.core.ValidationResult;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Base class for all ISO field validators.
 */
public abstract class BaseValidator {
    protected static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Validates a field value against its expected canonical form.
     *
     * @param de The data element being validated
     * @param expected The expected ISO value
     * @param actual The actual canonical value
     * @param result The validation result object to update
     * @param rules The validation rules from configuration
     * @return true if validation passed, false otherwise
     */
    public abstract boolean validate(String de, String expected, String actual, ValidationResult result, JsonNode rules);
    
    /**
     * Gets a value from a JSON node using a dot-notation path.
     */
    protected String getJsonValue(JsonNode node, String path) {
        if (node == null || path == null) {
            return null;
        }

        String[] parts = path.split("\\.");
        JsonNode current = node;

        for (String part : parts) {
            if (current == null) {
                return null;
            }
            current = current.path(part);
        }

        return current.isNull() ? null : current.asText();
    }
    
    /**
     * Creates a mapping from a JSON configuration node.
     */
    protected Map<String, String> createMappingFromJson(JsonNode mappingNode) {
        Map<String, String> mapping = new HashMap<>();
        if (mappingNode != null) {
            for (Iterator<String> it = mappingNode.fieldNames(); it.hasNext();) {
                String key = it.next();
                mapping.put(key, mappingNode.get(key).asText());
            }
        }
        return mapping;
    }
    
    /**
     * Validates a component using a mapping.
     */
    protected void validateComponentWithMapping(JsonNode component, String value,
                                           JsonNode actualJson, String canonicalPath, String componentName,
                                           StringBuilder details, boolean allValid, Map<String, String> mapping) {
        String expectedValue = mapping.get(value);
        String actualValue = getJsonValue(actualJson, canonicalPath);
        boolean isValid = expectedValue != null && expectedValue.equals(actualValue);
        details.append(String.format("%s: %s->%s (%s), ",
                componentName, value, expectedValue, isValid ? "✓" : "✗"));
        allValid &= isValid;
    }
} 