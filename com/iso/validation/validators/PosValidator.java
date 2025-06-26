package com.iso.validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import com.iso.validation.core.ValidationResult;
import java.util.Iterator;

/**
 * Validator for POS-related fields (DE 22, 58, 59).
 */
public class PosValidator extends BaseValidator {
    
    @Override
    public boolean validate(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            switch (de) {
                case "22":
                    return validatePosEntryMode(de, expected, actual, result);
                case "58":
                    return validatePosConditionCode(de, expected, actual, result, rules);
                case "59":
                    return validateNationalPosGeographicData(de, expected, actual, result, rules);
                default:
                    return false;
            }
        } catch (Exception e) {
            result.addFailedField(de, expected, "Validation failed: " + e.getMessage());
            return false;
        }
    }

    private boolean validatePosEntryMode(String de, String expected, String actual, ValidationResult result) {
        try {
            JsonNode actualJson = objectMapper.readTree(actual);
            String actualPosEntryMode = getJsonValue(actualJson, "transaction.posEntryMode");
            
            if (expected.equals(actualPosEntryMode)) {
                result.addPassedField(de, expected, actualPosEntryMode);
                return true;
            } else {
                result.addFailedField(de, expected, actualPosEntryMode != null ? actualPosEntryMode : "null");
                return false;
            }
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate POS entry mode: " + e.getMessage());
            return false;
        }
    }

    private boolean validatePosConditionCode(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            JsonNode actualJson = objectMapper.readTree(actual);
            JsonNode positions = rules.get("positions");
            boolean allValid = true;
            StringBuilder validationDetails = new StringBuilder();

            // Validate Terminal Class
            JsonNode terminalClass = positions.get("terminalClass");
            String terminalClassValue = expected.substring(
                terminalClass.get("start").asInt() - 1,
                terminalClass.get("end").asInt()
            );

            // Validate each component using config mappings
            JsonNode components = terminalClass.get("components");
            for (Iterator<String> it = components.fieldNames(); it.hasNext();) {
                String componentName = it.next();
                JsonNode component = components.get(componentName);
                // Calculate position relative to the section start
                int relativePosition = component.get("position").asInt() - terminalClass.get("start").asInt();
                String value = terminalClassValue.substring(relativePosition, relativePosition + 1);
                String canonicalPath = String.format("transaction.nationalPOSConditionCode.terminalClass.%s", componentName);
                validateComponentWithMapping(component, value, actualJson, canonicalPath, 
                    componentName, validationDetails, allValid, createMappingFromJson(component.get("mapping")));
            }

            // Validate Presentation Type
            JsonNode presentationType = positions.get("presentationType");
            String presentationValue = expected.substring(
                presentationType.get("start").asInt() - 1,
                presentationType.get("end").asInt()
            );

            components = presentationType.get("components");
            for (Iterator<String> it = components.fieldNames(); it.hasNext();) {
                String componentName = it.next();
                JsonNode component = components.get(componentName);
                // Calculate position relative to the section start
                int relativePosition = component.get("position").asInt() - presentationType.get("start").asInt();
                String value = presentationValue.substring(relativePosition, relativePosition + 1);
                String canonicalPath = String.format("transaction.nationalPOSConditionCode.presentationType.%s", componentName);
                validateComponentWithMapping(component, value, actualJson, canonicalPath, 
                    componentName, validationDetails, allValid, createMappingFromJson(component.get("mapping")));
            }

            // Validate Security Condition
            JsonNode securityCondition = positions.get("securityCondition");
            String securityValue = expected.substring(
                securityCondition.get("position").asInt() - 1,
                securityCondition.get("position").asInt()
            );
            validateComponentWithMapping(securityCondition, securityValue, actualJson,
                "transaction.nationalPOSConditionCode.SecurityCondition", "Security",
                validationDetails, allValid, createMappingFromJson(securityCondition.get("mapping")));

            // Validate Terminal Type
            JsonNode terminalType = positions.get("terminalType");
            String terminalTypeValue = expected.substring(
                terminalType.get("start").asInt() - 1,
                terminalType.get("end").asInt()
            );
            validateComponentWithMapping(terminalType, terminalTypeValue, actualJson,
                "transaction.nationalPOSConditionCode.terminalType", "TerminalType",
                validationDetails, allValid, createMappingFromJson(terminalType.get("mapping")));

            // Validate Card Data Input Capability
            JsonNode cardDataInput = positions.get("cardDataInputCapability");
            String cardDataValue = expected.substring(
                cardDataInput.get("position").asInt() - 1,
                cardDataInput.get("position").asInt()
            );
            validateComponentWithMapping(cardDataInput, cardDataValue, actualJson,
                "transaction.nationalPOSConditionCode.cardDataInputCapability", "InputCapability",
                validationDetails, allValid, createMappingFromJson(cardDataInput.get("mapping")));

            if (allValid) {
                result.addPassedField(de, expected, validationDetails.toString());
            } else {
                result.addFailedField(de, expected, validationDetails.toString());
            }
            return allValid;

        } catch (Exception e) {
            result.addFailedField(de, expected,
                    "Failed to validate POS condition code: " + e.getMessage());
            return false;
        }
    }

    private boolean validateNationalPosGeographicData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            JsonNode actualJson = objectMapper.readTree(actual);
            JsonNode positions = rules.get("positions");
            boolean allValid = true;
            StringBuilder validationDetails = new StringBuilder();

            // Validate State (positions 1-2)
            String state = expected.substring(0, 2);
            String actualState = getJsonValue(actualJson, "transaction.nationalPOSGeographicData.state");
            boolean stateValid = state.equals(actualState);
            validationDetails.append(String.format("State: %s (%s), ",
                    state, stateValid ? "✓" : "✗"));
            allValid &= stateValid;

            // Validate County (positions 3-5)
            String county = expected.substring(2, 5);
            String actualCounty = getJsonValue(actualJson, "transaction.nationalPOSGeographicData.county");
            boolean countyValid = county.equals(actualCounty);
            validationDetails.append(String.format("County: %s (%s), ",
                    county, countyValid ? "✓" : "✗"));
            allValid &= countyValid;

            // Validate Postal Code (positions 6-14)
            String postalCode = expected.substring(5, 14);
            String actualPostalCode = getJsonValue(actualJson, "transaction.nationalPOSGeographicData.postalCode");
            boolean postalCodeValid = postalCode.equals(actualPostalCode);
            validationDetails.append(String.format("Postal Code: %s (%s), ",
                    postalCode, postalCodeValid ? "✓" : "✗"));
            allValid &= postalCodeValid;

            // Validate Country Code (positions 15-17)
            String countryCode = expected.substring(14, 17);
            String actualCountryCode = getJsonValue(actualJson, "transaction.nationalPOSGeographicData.countryCode");
            boolean countryCodeValid = countryCode.equals(actualCountryCode);
            validationDetails.append(String.format("Country Code: %s (%s)",
                    countryCode, countryCodeValid ? "✓" : "✗"));
            allValid &= countryCodeValid;

            if (allValid) {
                result.addPassedField(de, expected, validationDetails.toString());
            } else {
                result.addFailedField(de, expected, validationDetails.toString());
            }
            return allValid;

        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate national POS geographic data: " + e.getMessage());
            return false;
        }
    }
} 