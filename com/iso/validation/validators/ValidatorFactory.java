package com.iso.validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import com.iso.validation.core.ValidationResult;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory class to manage and provide access to all field validators.
 */
public class ValidatorFactory {
    private static final Map<String, BaseValidator> validators = new HashMap<>();
    private static ValidatorFactory instance;

    private ValidatorFactory() {
        // Register all validators
        validators.put("pos", new PosValidator());
        validators.put("amount", new AmountValidator());
        validators.put("datetime", new DateTimeValidator());
        validators.put("special", new SpecialCasesValidator());
    }

    public static ValidatorFactory getInstance() {
        if (instance == null) {
            instance = new ValidatorFactory();
        }
        return instance;
    }

    public boolean validate(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        // Determine which validator to use based on DE
        BaseValidator validator = getValidatorForDE(de);
        if (validator != null) {
            return validator.validate(de, expected, actual, result, rules);
        }
        
        // If no specific validator found, do basic equality check
        if (expected.equals(actual)) {
            result.addPassedField(de, expected, actual);
            return true;
        } else {
            result.addFailedField(de, expected, actual);
            return false;
        }
    }

    private BaseValidator getValidatorForDE(String de) {
        switch (de) {
            case "22":
            case "58":
            case "59":
                return validators.get("pos");
            case "4":
            case "5":
            case "6":
            case "28":
            case "30":
            case "31":
            case "32":
            case "54":
            case "46":
            case "95":
                return validators.get("amount");
            case "7":
            case "12":
            case "13":
            case "14":
            case "15":
            case "17":
                return validators.get("datetime");
            case "35":
            case "44":
            case "45":
            case "48":
            case "56":
            case "90":
            case "123":
            case "125":
                return validators.get("special");
            default:
                return null;
        }
    }
} 