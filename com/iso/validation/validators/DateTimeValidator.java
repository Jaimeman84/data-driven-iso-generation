package com.iso.validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import com.iso.validation.core.ValidationResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class DateTimeValidator extends BaseValidator {
    @Override
    public boolean validate(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        switch (de) {
            case "7":  // Transmission Date and Time
            case "12": // Local Transaction Time
            case "13": // Local Transaction Date
            case "15": // Settlement Date
            case "17": // Capture Date
                return validateSingleDateTime(de, expected, actual, result);
            case "14": // Expiration Date
                return validateDateTime(de, expected, actual, result, rules);
            default:
                return false;
        }
    }

    private boolean validateSingleDateTime(String de, String expected, String actual, ValidationResult result) {
        if (expected.equals(actual)) {
            result.addPassedField(de, expected, actual);
            return true;
        } else {
            result.addFailedField(de, expected, actual);
            return false;
        }
    }

    private boolean validateDateTime(String de, String expected, String actual, ValidationResult result, JsonNode format) {
        try {
            String pattern = format.get("pattern").asText();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            
            LocalDateTime expectedDate = LocalDateTime.parse(expected, formatter);
            LocalDateTime actualDate = LocalDateTime.parse(actual, formatter);
            
            if (expectedDate.equals(actualDate)) {
                result.addPassedField(de, expected, actual);
                return true;
            } else {
                result.addFailedField(de, expected, actual);
                return false;
            }
        } catch (DateTimeParseException e) {
            result.addFailedField(de, expected, actual);
            return false;
        }
    }

    public boolean validatePairedDateTime(String de1, String de2, String combinedValue, String actual, ValidationResult result) {
        // Implement paired date/time validation logic
        // This would be moved from CreateIsoMessage.java
        return true; // Placeholder
    }
} 