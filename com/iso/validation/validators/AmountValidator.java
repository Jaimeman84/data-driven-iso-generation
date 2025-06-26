package com.iso.validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import com.iso.validation.core.ValidationResult;

public class AmountValidator extends BaseValidator {
    @Override
    public boolean validate(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        switch (de) {
            case "4":  // Amount, Transaction
            case "5":  // Amount, Settlement
            case "6":  // Amount, Cardholder Billing
            case "28": // Amount, Transaction Fee
            case "30": // Amount, Transaction Processing Fee
            case "31": // Amount, Settlement Fee
            case "32": // Amount, Cardholder Billing Fee
                return validateAmount(de, expected, actual, result, rules);
            case "54": // Additional Amounts
                return validateAdditionalAmounts(de, expected, actual, result, rules);
            case "46": // Additional Fees
                return validateAdditionalFees(de, expected, actual, result, rules);
            case "95": // Replacement Amounts
                return validateReplacementAmounts(de, expected, actual, result, rules);
            default:
                return false;
        }
    }

    private boolean validateAmount(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            double expectedAmount = Double.parseDouble(expected);
            double actualAmount = Double.parseDouble(actual);
            
            if (expectedAmount == actualAmount) {
                result.addPassedField(de, expected, actual);
                return true;
            } else {
                result.addFailedField(de, expected, actual);
                return false;
            }
        } catch (NumberFormatException e) {
            result.addFailedField(de, expected, actual);
            return false;
        }
    }

    private boolean validateAdditionalAmounts(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Implement the additional amounts validation logic
        // This would be moved from CreateIsoMessage.java
        return true; // Placeholder
    }

    private boolean validateAdditionalFees(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Implement the additional fees validation logic
        // This would be moved from CreateIsoMessage.java
        return true; // Placeholder
    }

    private boolean validateReplacementAmounts(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Implement the replacement amounts validation logic
        // This would be moved from CreateIsoMessage.java
        return true; // Placeholder
    }
} 