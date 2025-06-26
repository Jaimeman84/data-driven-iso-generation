package com.iso.validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import com.iso.validation.core.ValidationResult;
import java.util.Map;
import java.util.HashMap;

public class SpecialCasesValidator extends BaseValidator {
    @Override
    public boolean validate(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        switch (de) {
            case "45": // Track 1 Data
            case "35": // Track 2 Data
                return validateTrackData(de, expected, actual, result, rules);
            case "48": // Additional Data
                return validateAdditionalData(de, expected, actual, result);
            case "90": // Original Data Elements
                return validateOriginalData(de, expected, actual, result, rules);
            case "44": // Additional Response Data
                return validateAdditionalResponseData(de, expected, actual, result, rules);
            case "56": // Original Transaction Data
                return validateOriginalTransactionData(de, expected, actual, result, rules);
            case "123": // POS Data Code
                return validatePosDataCode(de, expected, actual, result, rules);
            case "125": // Supporting Information
                return validateSupportingInfo(de, expected, actual, result, rules);
            default:
                return false;
        }
    }

    private boolean validateTrackData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Track data validation logic
        if (expected.equals(actual)) {
            result.addPassedField(de, expected, actual);
            return true;
        }
        result.addFailedField(de, expected, actual);
        return false;
    }

    private boolean validateAdditionalData(String de, String expected, String actual, ValidationResult result) {
        StringBuilder details = new StringBuilder();
        boolean allValid = true;

        // Parse the expected and actual values into a map
        Map<String, String> expectedPairs = parseAdditionalData(expected);
        Map<String, String> actualPairs = parseAdditionalData(actual);

        // Compare each key-value pair
        for (Map.Entry<String, String> entry : expectedPairs.entrySet()) {
            String key = entry.getKey();
            String expectedValue = entry.getValue();
            String actualValue = actualPairs.get(key);

            if (actualValue == null || !actualValue.equals(expectedValue)) {
                allValid = false;
                details.append(String.format("Mismatch in %s: expected=%s, actual=%s\n", 
                    key, expectedValue, actualValue != null ? actualValue : "missing"));
            }
        }

        if (allValid) {
            result.addPassedField(de, expected, actual);
            return true;
        } else {
            result.addFailedField(de, expected, actual);
            return false;
        }
    }

    private Map<String, String> parseAdditionalData(String data) {
        Map<String, String> pairs = new HashMap<>();
        // Implementation of parsing logic for additional data
        // This would handle the specific format of DE48
        return pairs;
    }

    private boolean validateOriginalData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        if (rules == null || !rules.has("components")) {
            // If no specific rules, do direct comparison
            if (expected.equals(actual)) {
                result.addPassedField(de, expected, actual);
                return true;
            }
            result.addFailedField(de, expected, actual);
            return false;
        }

        StringBuilder details = new StringBuilder();
        boolean allValid = true;

        JsonNode components = rules.get("components");
        for (JsonNode component : components) {
            String name = component.get("name").asText();
            int start = component.get("start").asInt();
            int length = component.get("length").asInt();

            String expectedValue = expected.substring(start, start + length);
            String actualValue = actual.substring(start, start + length);

            if (!expectedValue.equals(actualValue)) {
                allValid = false;
                details.append(String.format("Mismatch in %s: expected=%s, actual=%s\n", 
                    name, expectedValue, actualValue));
            }
        }

        if (allValid) {
            result.addPassedField(de, expected, actual);
            return true;
        } else {
            result.addFailedField(de, expected, actual);
            return false;
        }
    }

    private boolean validateAdditionalResponseData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Implementation for DE44 validation
        return validateWithComponents(de, expected, actual, result, rules);
    }

    private boolean validateOriginalTransactionData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Implementation for DE56 validation
        return validateWithComponents(de, expected, actual, result, rules);
    }

    private boolean validatePosDataCode(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Implementation for DE123 validation
        return validateWithComponents(de, expected, actual, result, rules);
    }

    private boolean validateSupportingInfo(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Implementation for DE125 validation
        return validateWithComponents(de, expected, actual, result, rules);
    }

    private boolean validateWithComponents(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        if (rules == null || !rules.has("components")) {
            if (expected.equals(actual)) {
                result.addPassedField(de, expected, actual);
                return true;
            }
            result.addFailedField(de, expected, actual);
            return false;
        }

        StringBuilder details = new StringBuilder();
        boolean allValid = true;

        JsonNode components = rules.get("components");
        Map<String, String> mapping = rules.has("mapping") ? 
            createMappingFromJson(rules.get("mapping")) : new HashMap<>();

        for (JsonNode component : components) {
            validateComponentWithMapping(
                component, 
                expected, 
                objectMapper.createObjectNode().put("value", actual), 
                "value", 
                component.get("name").asText(),
                details,
                allValid,
                mapping
            );
        }

        if (allValid) {
            result.addPassedField(de, expected, actual);
            return true;
        } else {
            result.addFailedField(de, expected, actual);
            return false;
        }
    }
} 