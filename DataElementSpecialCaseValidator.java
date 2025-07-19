package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import static utilities.CreateIsoMessage.*;

public class DataElementSpecialCaseValidator {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Handles special validation cases for specific DEs based on config
     */
    static boolean validateSpecialCase(String de, String expected, String actual, ValidationResult result, Map<String, JsonNode> fieldConfig) {
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("validation")) {
            JsonNode validation = config.get("validation");
            if (validation.has("type")) {
                String validationType = validation.get("type").asText();
                switch (validationType) {
                    case "amount":
                        return validateAmount(de, expected, actual, result, validation.get("rules"));
                    case "datetime":
                        try {
                            JsonNode actualJson = objectMapper.readTree(actual);
                            String actualValue = getJsonValue(actualJson, getCanonicalPaths(de).get(0));
                            return validateDateTime(de, expected, actualValue, result, validation.get("format"));
                        } catch (Exception e) {
                            result.addFailedField(de, expected, "Failed to parse datetime from canonical response: " + e.getMessage());
                            return false;
                        }
                    case "currency":
                        return validateCurrency(de, expected, actual, result, validation.get("format"));
                    case "merchant_location":
                        try {
                            JsonNode actualJson = objectMapper.readTree(actual);
                            return validateMerchantLocation(de, expected, actualJson, result);
                        } catch (Exception e) {
                            result.addFailedField(de, expected, "Failed to parse canonical response: " + e.getMessage());
                            return false;
                        }
                    case "pos_entry_mode":
                        return validatePosEntryMode(de, expected, actual, result);
                    case "original_data":
                        return validateOriginalData(de, expected, actual, result, validation.get("rules"));
                    case "pos_condition_code":
                        return validatePosConditionCode(de, expected, actual, result, validation.get("rules"));
                    case "additional_fees":
                        return validateAdditionalFees(de, expected, actual, result, validation.get("rules"));
                    case "additional_amounts":
                        return validateAdditionalAmounts(de, expected, actual, result, validation.get("rules"));
                    case "national_pos_geographic_data":
                        return validateNationalPosGeographicData(de, expected, actual, result, validation.get("rules"));
                    case "network_data":
                        return validateNetworkData(de, expected, actual, result, validation.get("rules"));
                    case "avs_data":
                        return validateAvsData(de, expected, actual, result, validation.get("rules"));
                    case "acquirer_trace_data":
                        return validateAcquirerTraceData(de, expected, actual, result, validation.get("rules"));
                    case "issuer_trace_data":
                        return validateIssuerTraceData(de, expected, actual, result, validation.get("rules"));
                    case "incremental_auth_data":
                        return validateIncrementalAuthData(de, expected, actual, result, validation.get("rules"));
                    case "advice_reversal_code":
                        return validateAdviceReversalCode(de, expected, actual, result, validation.get("rules"));
                    case "replacement_amounts":
                        return validateReplacementAmounts(de, expected, actual, result, validation.get("rules"));
                    case "additional_data":
                        return validateAdditionalData(de, expected, actual, result, fieldConfig);
                    default:
                        System.out.println("Unknown validation type: " + validationType);
                }
            } else {
                System.out.println("No validation type found for DE " + de);
            }
        } else {
            System.out.println("No validation config found for DE " + de);
        }

        // Default comparison for fields without special validation
        if (expected.equals(actual)) {
            result.addPassedField(de, expected, actual);
            return true;
        }
        result.addFailedField(de, expected, actual);
        return false;
    }

    private static boolean validateIncrementalAuthData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            // Only validate for MTI 0220, skip otherwise
            if (!"0220".equals(isoFields.get(0))) {
                result.addSkippedField(de, expected, "DE " + de + " validation only applicable for MTI 0220");
                return true;
            }

            // Check MTI requirement from configuration
            if (rules.has("mti") && rules.get("mti").has("required")) {
                String requiredMti = rules.get("mti").get("required").asText();
                String skipReason = rules.get("mti").has("skipReason") ?
                        rules.get("mti").get("skipReason").asText() :
                        "DE " + de + " validation only applicable for MTI " + requiredMti;

                // Safely get MTI value with null check
                String currentMti = isoFields.get(0);
                if (!requiredMti.equals(currentMti)) {
                    result.addSkippedField(de, expected, skipReason);
                    return true;
                }
            }

            if (expected == null || actual == null || expected.length() != 12) {
                result.addFailedField(de, String.valueOf(expected), "Invalid incremental authorization data length");
                return true;
            }

            JsonNode actualJson = objectMapper.readTree(actual);
            StringBuilder details = new StringBuilder();
            boolean allValid = true;

            // Parse TLV data from expected string
            Map<String, String> expectedValues = new HashMap<>();
            int position = 0;
            while (position < expected.length()) {
                String tag = expected.substring(position, position + 2);
                String length = expected.substring(position + 2, position + 4);
                String value = expected.substring(position + 4, position + 4 + Integer.parseInt(length));
                expectedValues.put(tag, value);
                position += 4 + Integer.parseInt(length);
            }

            // Validate count (CN tag)
            String expectedCount = expectedValues.get("CN");
            String actualCount = getJsonValue(actualJson, "transaction.incrementalAuthorization.count");
            if (!String.valueOf(expectedCount).equals(actualCount)) {
                details.append("Count mismatch: expected ").append(expectedCount)
                        .append(", got ").append(actualCount).append("; ");
                allValid = false;
            }

            // Validate sequence (SN tag)
            String expectedSequence = expectedValues.get("SN");
            String actualSequence = getJsonValue(actualJson, "transaction.incrementalAuthorization.sequence");
            if (!String.valueOf(expectedSequence).equals(actualSequence)) {
                details.append("Sequence mismatch: expected ").append(expectedSequence)
                        .append(", got ").append(actualSequence).append("; ");
                allValid = false;
            }

            // Validate authorization type (always MULTIPLE_COMPLETION)
            String actualAuthType = getJsonValue(actualJson, "transaction.incrementalAuthorization.incrementalAuthorizationType");
            String expectedAuthType = rules.get("authorizationType")
                    .get("mapping")
                    .get("default")
                    .asText();
            if (!String.valueOf(expectedAuthType).equals(actualAuthType)) {
                details.append("Authorization type mismatch: expected ").append(expectedAuthType)
                        .append(", got ").append(actualAuthType);
                allValid = false;
            }

            if (allValid) {
                result.addPassedField(de, expected, actual);
            } else {
                result.addFailedField(de, expected, actual + " [" + details + "]");
            }

            return true;
        } catch (Exception e) {
            result.addFailedField(de, String.valueOf(expected), "Failed to parse incremental authorization data: " + e.getMessage());
            return false;
        }
    }

    private static boolean validateNetworkData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            if (expected == null || actual == null) {
                result.addFailedField(de, expected, actual);
                return true;
            }

            JsonNode actualJson = objectMapper.readTree(actual);
            StringBuilder details = new StringBuilder();
            boolean allValid = true;

            // Validate pseudoTerminal
            String expectedPseudoTerminal = expected.substring(2, 8);
            String actualPseudoTerminal = getJsonValue(actualJson, "transaction.Network.pseudoTerminal");
            if (!expectedPseudoTerminal.equals(actualPseudoTerminal)) {
                details.append("Pseudo Terminal mismatch: expected ").append(expectedPseudoTerminal)
                        .append(", got ").append(actualPseudoTerminal).append("; ");
                allValid = false;
            }

            // Validate acquirerNetworkId
            String expectedNetworkId = expected.substring(8, 10);
            String actualNetworkId = getJsonValue(actualJson, "transaction.Network.acquirerNetworkId");
            if (!expectedNetworkId.equals(actualNetworkId)) {
                details.append("Acquirer Network ID mismatch: expected ").append(expectedNetworkId)
                        .append(", got ").append(actualNetworkId).append("; ");
                allValid = false;
            }

            // Validate processorId
            String expectedProcessorId = expected.substring(11, 17);
            String actualProcessorId = getJsonValue(actualJson, "transaction.Network.processorId");
            if (!expectedProcessorId.equals(actualProcessorId)) {
                details.append("Processor ID mismatch: expected ").append(expectedProcessorId)
                        .append(", got ").append(actualProcessorId).append("; ");
                allValid = false;
            }

            // Validate isExternallySettled flag
            char externallySettledFlag = expected.charAt(17);
            String expectedSettlement = externallySettledFlag == 'Y' ? "SETTLED_BETWEEN_ACQUIRER_AND_ISSUER" : "SETTLED_THROUGH_NETWORK_EXCHANGE";
            String actualSettlement = getJsonValue(actualJson, "transaction.Network.ProcessingFlag.isExternallySettled");
            if (!expectedSettlement.equals(actualSettlement)) {
                details.append("Settlement flag mismatch: expected ").append(expectedSettlement)
                        .append(", got ").append(actualSettlement).append("; ");
                allValid = false;
            }

            // Validate partialAuthTerminalSupportIndicator
            char partialAuthFlag = expected.charAt(19);
            String expectedPartialAuth;
            switch (partialAuthFlag) {
                case '1':
                    expectedPartialAuth = "TERMINAL_SUPPPORT_PARTIAL_APPROVAL";
                    break;
                case '2':
                    expectedPartialAuth = "RETURNS_BALANCES_IN_RESPONSE";
                    break;
                default:
                    expectedPartialAuth = "TERMINAL_DOES_NOT_SUPPPORT_PARTIAL_APPROVAL";
            }
            String actualPartialAuth = getJsonValue(actualJson, "transaction.Network.ProcessingFlag.partialAuthTerminalSupportIndicator");
            if (!expectedPartialAuth.equals(actualPartialAuth)) {
                details.append("Partial Auth Support mismatch: expected ").append(expectedPartialAuth)
                        .append(", got ").append(actualPartialAuth);
                allValid = false;
            }

            if (allValid) {
                result.addPassedField(de, expected, actual);
            } else {
                result.addFailedField(de, expected, actual + " [" + details + "]");
            }
            return true;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to parse network data: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates amount fields using config rules
     */
    private static boolean validateAmount(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            // Parse the canonical JSON response
            JsonNode actualJson = objectMapper.readTree(actual);
            String actualValue = getJsonValue(actualJson, getCanonicalPaths(de).get(0));

            // Remove leading zeros from expected value
            String normalizedExpected = expected;
            String debitCreditIndicator = "";

            // For DEs 28-31, handle debit/credit indicator
            if (Arrays.asList("28", "29", "30", "31").contains(de)) {
                // Extract first character as indicator
                debitCreditIndicator = expected.substring(0, 1);
                // Remove indicator for amount comparison
                normalizedExpected = expected.substring(1);
            }

            // Remove leading zeros
            normalizedExpected = String.valueOf(Long.parseLong(normalizedExpected));
            String normalizedActual = actualValue;

            boolean amountMatches = normalizedExpected.equals(normalizedActual);

            // For DEs 28-31, also validate debit/credit indicator
            if (amountMatches && !debitCreditIndicator.isEmpty() && rules.has("debitCreditIndicator")) {
                String expectedIndicatorType = rules.get("debitCreditIndicator")
                        .get(debitCreditIndicator).asText();

                // Get the debitCreditIndicatorType from canonical response
                String actualIndicatorType = getJsonValue(actualJson, getCanonicalPaths(de).get(1));

                if (expectedIndicatorType.equals(actualIndicatorType)) {
                    result.addPassedField(de, expected, String.format("%s (Amount: %s, Type: %s)",
                            actualValue, normalizedActual, actualIndicatorType));
                    return true;
                } else {
                    result.addFailedField(de, expected, String.format("%s (Amount matches but expected type %s, got %s)",
                            actualValue, expectedIndicatorType, actualIndicatorType));
                    return false;
                }
            }

            if (amountMatches) {
                result.addPassedField(de, expected, actualValue);
                return true;
            }

            result.addFailedField(de, expected, actualValue);
            return false;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate amount: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates datetime fields using config format
     */
    private static boolean validateDateTime(String de, String expected, String actual, ValidationResult result, JsonNode format) {
        try {
            String inputFormat = format.get("input").asText();
            String canonicalFormat = format.get("canonical").asText();

            // Check if this field is part of a paired datetime
            if (format.has("pairedField")) {
                JsonNode paired = format.get("pairedField");
                String otherField = paired.get("field").asText();
                String fieldType = paired.get("type").asText();

                // Get the other field's value from the ISO message
                String otherValue = isoFields.get(Integer.parseInt(otherField));
                if (otherValue == null) {
                    result.addFailedField(de, expected, String.format("Paired field DE %s not found - both DE %s and DE %s are needed for datetime validation",
                            otherField, de, otherField));
                    return false;
                }

                // Combine the values based on their types
                String dateValue = "date".equals(fieldType) ? expected : otherValue;
                String timeValue = "time".equals(fieldType) ? expected : otherValue;
                String combinedValue = dateValue + timeValue;

                // Validate the combined datetime
                return validatePairedDateTime(de, otherField, combinedValue, actual, result);
            }

            // Regular datetime validation for non-paired fields
            return validateSingleDateTime(de, expected, actual, result);
        } catch (Exception e) {
            result.addFailedField(de, expected, actual + " (Failed to parse datetime: " + e.getMessage() + ")");
            return false;
        }
    }

    private static boolean validatePairedDateTime(String de1, String de2, String combinedValue, String actual, ValidationResult result) {
        try {
            // Parse the combined MMDD + hhmmss format
            String month = combinedValue.substring(0, 2);
            String day = combinedValue.substring(2, 4);
            String hour = combinedValue.substring(4, 6);
            String minute = combinedValue.substring(6, 8);
            String second = combinedValue.substring(8, 10);

            // Get current year
            int year = Calendar.getInstance().get(Calendar.YEAR);

            // Create expected datetime string
            String expectedDateTime = String.format("%d-%s-%sT%s:%s:%s", year, month, day, hour, minute, second);

            // Compare ignoring timezone
            if (actual.startsWith(expectedDateTime)) {
                // Add success result for both fields
                String successMessage = String.format("%s (Validated with DE %s and DE %s)", actual, de1, de2);
                result.addPassedField(de1, combinedValue, successMessage);
                result.addPassedField(de2, combinedValue, successMessage);
                return true;
            }

            // Add failure result for both fields
            String failureMessage = String.format("%s (Expected format: %s from DE %s and DE %s)", actual, expectedDateTime, de1, de2);
            result.addFailedField(de1, combinedValue, failureMessage);
            result.addFailedField(de2, combinedValue, failureMessage);
            return false;
        } catch (Exception e) {
            String errorMessage = String.format("%s (Failed to parse paired datetime from DE %s and DE %s: %s)", actual, de1, de2, e.getMessage());
            result.addFailedField(de1, combinedValue, errorMessage);
            result.addFailedField(de2, combinedValue, errorMessage);
            return false;
        }
    }

    private static boolean validateSingleDateTime(String de, String expected, String actual, ValidationResult result) {
        try {
            // Parse the expected MMDDhhmmss format
            String month = expected.substring(0, 2);
            String day = expected.substring(2, 4);
            String hour = expected.substring(4, 6);
            String minute = expected.substring(6, 8);
            String second = expected.substring(8, 10);

            // Get current year
            int year = Calendar.getInstance().get(Calendar.YEAR);

            // Create expected datetime string
            String expectedDateTime = String.format("%d-%s-%sT%s:%s:%s", year, month, day, hour, minute, second);

            // Compare ignoring timezone
            if (actual.startsWith(expectedDateTime)) {
                result.addPassedField(de, expected, actual);
                return true;
            }

            result.addFailedField(de, expected + String.format(" (Expected format: %s)", expectedDateTime), actual);
            return false;
        } catch (Exception e) {
            result.addFailedField(de, expected, actual + " (Failed to parse datetime: " + e.getMessage() + ")");
            return false;
        }
    }

    /**
     * Validates currency codes using config format
     */
    private static boolean validateCurrency(String de, String expected, String actual, ValidationResult result, JsonNode format) {
        try {
            // Parse the canonical JSON response
            JsonNode actualJson = objectMapper.readTree(actual);
            String actualValue = getJsonValue(actualJson, getCanonicalPaths(de).get(0));

            // For currency code, just compare the numeric values directly
            String normalizedExpected = expected.replaceFirst("^0+", ""); // Remove leading zeros
            if (normalizedExpected.equals(actualValue)) {
                result.addPassedField(de, expected, actualValue);
                return true;
            }

            result.addFailedField(de, expected, actualValue);
            return false;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate currency: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates DE 43 merchant location field by parsing its sub-elements
     */
    static boolean validateMerchantLocation(String de, String expected, JsonNode canonicalJson, ValidationResult result) {
        try {
            // Ensure the expected value is padded to full length if shorter
            String paddedExpected = String.format("%-40s", expected).substring(0, 40);

            // Parse the expected value into sub-elements based on fixed positions
            String nameAndAddress = paddedExpected.substring(0, 23).trim();
            String city = paddedExpected.substring(23, 36).trim();
            String state = paddedExpected.substring(36, 38).trim();
            String country = paddedExpected.substring(38, 40).trim();

            // Extract values from canonical format
            String actualAddress = getJsonValue(canonicalJson, "transaction.merchant.address.addressLine1");
            String actualCity = getJsonValue(canonicalJson, "transaction.merchant.address.city");
            String actualState = getJsonValue(canonicalJson, "transaction.merchant.address.state");
            String actualCountry = getJsonValue(canonicalJson, "transaction.merchant.address.country.countryCode");

            // Compare each component
            boolean addressMatch = nameAndAddress.equalsIgnoreCase(actualAddress);
            boolean cityMatch = city.equalsIgnoreCase(actualCity);
            boolean stateMatch = state.equalsIgnoreCase(actualState);
            boolean countryMatch = country.equalsIgnoreCase(actualCountry);
            boolean allMatch = addressMatch && cityMatch && stateMatch && countryMatch;

            // Just show the canonical values
            String canonicalValue = String.format("%s, %s, %s %s", actualAddress, actualCity, actualState, actualCountry);

            if (allMatch) {
                result.addPassedField(de, expected, canonicalValue);
            } else {
                result.addFailedField(de, expected, canonicalValue);
            }

            return allMatch;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate merchant location: " + e.getMessage());
            return false;
        }
    }

    /**
     * Helper method to safely get value from JSON path, with case-insensitive field matching
     */
    private static String getJsonValue(JsonNode node, String path) {
        try {
            String[] parts = path.split("\\.");
            JsonNode current = node;
            for (String part : parts) {
                // Try exact match first
                JsonNode next = current.path(part);
                if (next.isMissingNode()) {
                    // If not found, try case-insensitive match
                    Iterator<String> fieldNames = current.fieldNames();
                    boolean found = false;
                    while (fieldNames.hasNext()) {
                        String fieldName = fieldNames.next();
                        if (fieldName.equalsIgnoreCase(part)) {
                            next = current.path(fieldName);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        return ""; // Path not found
                    }
                }
                current = next;
            }
            return current.isNull() ? "" : current.asText().trim();
        } catch (Exception e) {
            System.out.println("Warning: Error getting JSON value for path " + path + ": " + e.getMessage());
            return "";
        }
    }

    /**
     * Validates DE 22 (Point of Service Entry Mode) and maps to canonical values
     */
    private static boolean validatePosEntryMode(String de, String expected, String actual, ValidationResult result) {
        try {
            JsonNode actualJson = objectMapper.readTree(actual);
            String actualValue = getJsonValue(actualJson, "transaction.channel.channelType");

            // Determine expected canonical value based on ISO value
            String expectedCanonical;
            switch (expected) {
                case "020":
                case "022":
                case "029":
                case "060":
                    expectedCanonical = "POS";
                    break;
                case "010":
                case "012":
                case "090":
                case "091":
                    expectedCanonical = "ONLINE";
                    break;
                default:
                    expectedCanonical = expected; // Keep original for unmatched values
            }

            if (expectedCanonical.equals(actualValue)) {
                result.addPassedField(de, expected, actualValue);
                return true;
            } else {
                result.addFailedField(de, expected, actualValue);
                return false;
            }
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate POS entry mode: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates original data elements (DE 90) with position-based validation
     */
    private static boolean validateOriginalData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            JsonNode actualJson = objectMapper.readTree(actual);
            JsonNode positions = rules.get("positions");
            boolean allValid = true;
            StringBuilder validationDetails = new StringBuilder();

            // Validate Message Type (positions 1-4)
            String messageType = expected.substring(0, 4);
            String expectedType = positions.get("messageType").get("mapping").get(messageType).asText();
            String actualType = getJsonValue(actualJson, "transaction.originalTransaction.transactionType");
            boolean messageTypeValid = expectedType.equals(actualType);
            validationDetails.append(String.format("Message Type: %s->%s (%s), ",
                    messageType, expectedType, messageTypeValid ? "✓" : "✗"));
            allValid &= messageTypeValid;

            // Validate System Trace Audit Number (positions 5-10)
            String stan = expected.substring(4, 10).replaceFirst("^0+", ""); // Remove leading zeros
            String actualStan = getJsonValue(actualJson, "transaction.originalTransaction.systemTraceAuditNumber");
            boolean stanValid = stan.equals(actualStan);
            validationDetails.append(String.format("STAN: %s (%s), ",
                    stan, stanValid ? "✓" : "✗"));
            allValid &= stanValid;

            // Validate Transmission Date Time (positions 11-20)
            String dateTime = expected.substring(10, 20);
            String actualDateTime = getJsonValue(actualJson, "transaction.originalTransaction.transmissionDateTime");

            // Parse the expected date time (MMDDhhmmss format)
            String month = dateTime.substring(0, 2);
            String day = dateTime.substring(2, 4);
            String hour = dateTime.substring(4, 6);
            String minute = dateTime.substring(6, 8);
            String second = dateTime.substring(8, 10);

            // Get current year
            int year = Calendar.getInstance().get(Calendar.YEAR);

            // Create expected datetime string in UTC format
            String expectedDateTime = String.format("%d-%s-%sT%s:%s:%s", year, month, day, hour, minute, second);

            // Compare ignoring timezone and any additional precision
            boolean dateTimeValid = actualDateTime.startsWith(expectedDateTime);
            validationDetails.append(String.format("DateTime: %s->%s (%s), ",
                    dateTime, actualDateTime, dateTimeValid ? "✓" : "✗"));
            allValid &= dateTimeValid;

            // Validate Acquirer ID (positions 21-31)
            String acquirerId = expected.substring(20, 31).replaceFirst("^0+", ""); // Remove leading zeros
            String actualAcquirerId = getJsonValue(actualJson, "transaction.originalTransaction.acquirer.acquirerId");
            boolean acquirerIdValid = acquirerId.equals(actualAcquirerId);
            validationDetails.append(String.format("AcquirerID: %s (%s), ",
                    acquirerId, acquirerIdValid ? "✓" : "✗"));
            allValid &= acquirerIdValid;

            // Validate Forwarding Institution ID (positions 32-42)
            String forwardingId = expected.substring(31, 42).replaceFirst("^0+", ""); // Remove leading zeros
            String actualForwardingId = getJsonValue(actualJson, "transaction.originalTransaction.forwardingInstitution.forwardingInstitutionId");
            boolean forwardingIdValid = forwardingId.equals(actualForwardingId);
            validationDetails.append(String.format("ForwardingID: %s (%s)",
                    forwardingId, forwardingIdValid ? "✓" : "✗"));
            allValid &= forwardingIdValid;

            // Add validation result with detailed breakdown
            if (allValid) {
                result.addPassedField(de, expected, validationDetails.toString());
            } else {
                result.addFailedField(de, expected, validationDetails.toString());
            }

            return allValid;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate original data elements: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates POS condition code (DE 58) with position-based validation
     */
    private static boolean validatePosConditionCode(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
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

    private static Map<String, String> createMappingFromJson(JsonNode mappingNode) {
        Map<String, String> mapping = new HashMap<>();
        for (Iterator<String> it = mappingNode.fieldNames(); it.hasNext();) {
            String key = it.next();
            mapping.put(key, mappingNode.get(key).asText());
        }
        return mapping;
    }

    /**
     * Helper method to validate a single component with enum mapping
     */
    private static void validateComponentWithMapping(JsonNode component, String value,
                                                     JsonNode actualJson, String canonicalPath, String componentName,
                                                     StringBuilder details, boolean allValid, Map<String, String> mapping) {
        try {
            String actualValue = getJsonValue(actualJson, canonicalPath);
            String expectedValue = mapping.getOrDefault(value, value);

            boolean isValid = expectedValue.equals(actualValue);
            allValid &= isValid;
            details.append(String.format("%s: %s->%s (%s), ",
                    componentName, value, expectedValue, isValid ? "✓" : "✗"));
        } catch (Exception e) {
            allValid = false;
            details.append(String.format("%s: Error (%s), ", componentName, e.getMessage()));
        }
    }

    /**
     * Validates DE 46 (Additional Fees) with position-based validation
     */
    private static boolean validateAdditionalFees(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            JsonNode actualJson = objectMapper.readTree(actual);
            JsonNode positions = rules.get("positions");
            boolean allValid = true;
            StringBuilder validationDetails = new StringBuilder();

            // Get the first additional fee from the array
            JsonNode additionalFees = actualJson.path("transaction").path("fees").path("additionalFees");
            if (!additionalFees.isArray() || additionalFees.size() == 0) {
                result.addFailedField(de, expected, "No additional fees found in canonical response");
                return false;
            }
            JsonNode fee = additionalFees.get(0);

            // Validate Fee Type (positions 1-2)
            String feeType = expected.substring(0, 2);
            String expectedFeeType = positions.get("feeType").get("mapping").get(feeType).asText();
            String actualFeeType = fee.path("feeType").asText();
            boolean feeTypeValid = expectedFeeType.equals(actualFeeType);
            validationDetails.append(String.format("Fee Type: %s->%s (%s), ",
                    feeType, expectedFeeType, feeTypeValid ? "✓" : "✗"));
            allValid &= feeTypeValid;

            // Validate Settlement Memo Indicator (position 3)
            String settleMemo = expected.substring(2, 3);
            String expectedSettleMemo = positions.get("settleMemoIndicator").get("mapping").get(settleMemo).asText();
            String actualSettleMemo = fee.path("settleMemoIndicator").asText();
            boolean settleMemoValid = expectedSettleMemo.equals(actualSettleMemo);
            validationDetails.append(String.format("Settle Memo: %s->%s (%s), ",
                    settleMemo, expectedSettleMemo, settleMemoValid ? "✓" : "✗"));
            allValid &= settleMemoValid;

            // Validate Decimalization Indicator (position 4)
            String decimalization = expected.substring(3, 4);
            String expectedDecimalization = positions.get("decimalizationIndicator").get("mapping").get(decimalization).asText();
            String actualDecimalization = fee.path("decimalizationIndicator").asText();
            boolean decimalizationValid = expectedDecimalization.equals(actualDecimalization);
            validationDetails.append(String.format("Decimalization: %s (%s), ",
                    decimalization, decimalizationValid ? "✓" : "✗"));
            allValid &= decimalizationValid;

            // Validate Fee Amount (positions 5-13)
            JsonNode feeAmount = positions.get("feeAmount");
            String feeAmountValue = expected.substring(4, 13);

            // Validate Debit/Credit Indicator (position 5)
            String feeIndicator = feeAmountValue.substring(0, 1);
            String expectedFeeIndicator = feeAmount.get("components").get("debitCreditIndicator").get("mapping").get(feeIndicator).asText();
            String actualFeeIndicator = fee.path("fee").path("amount").path("debitCreditIndicatorType").asText();
            boolean feeIndicatorValid = expectedFeeIndicator.equals(actualFeeIndicator);
            validationDetails.append(String.format("Fee Indicator: %s->%s (%s), ",
                    feeIndicator, expectedFeeIndicator, feeIndicatorValid ? "✓" : "✗"));
            allValid &= feeIndicatorValid;

            // Validate Fee Amount (positions 6-13)
            String feeAmountStr = feeAmountValue.substring(1);
            String normalizedFeeAmount = String.valueOf(Long.parseLong(feeAmountStr)); // Remove leading zeros
            String actualFeeAmount = fee.path("fee").path("amount").path("amount").asText();
            boolean feeAmountValid = normalizedFeeAmount.equals(actualFeeAmount);
            validationDetails.append(String.format("Fee Amount: %s->%s (%s), ",
                    feeAmountStr, normalizedFeeAmount, feeAmountValid ? "✓" : "✗"));
            allValid &= feeAmountValid;

            // Validate Settlement Amount (positions 14-22)
            JsonNode settlementAmount = positions.get("settlementAmount");
            String settlementValue = expected.substring(13, 22);

            // Validate Settlement Debit/Credit Indicator (position 14)
            String settlementIndicator = settlementValue.substring(0, 1);
            String expectedSettlementIndicator = settlementAmount.get("components").get("debitCreditIndicator").get("mapping").get(settlementIndicator).asText();
            String actualSettlementIndicator = fee.path("settlement").path("settlementAmount").path("debitCreditIndicatorType").asText();
            boolean settlementIndicatorValid = expectedSettlementIndicator.equals(actualSettlementIndicator);
            validationDetails.append(String.format("Settlement Indicator: %s->%s (%s), ",
                    settlementIndicator, expectedSettlementIndicator, settlementIndicatorValid ? "✓" : "✗"));
            allValid &= settlementIndicatorValid;

            // Validate Settlement Amount (positions 15-22)
            String settlementAmountStr = settlementValue.substring(1);
            String normalizedSettlementAmount = String.valueOf(Long.parseLong(settlementAmountStr)); // Remove leading zeros
            String actualSettlementAmount = fee.path("settlement").path("settlementAmount").path("amount").asText();
            boolean settlementAmountValid = normalizedSettlementAmount.equals(actualSettlementAmount);
            validationDetails.append(String.format("Settlement Amount: %s->%s (%s)",
                    settlementAmountStr, normalizedSettlementAmount, settlementAmountValid ? "✓" : "✗"));
            allValid &= settlementAmountValid;

            if (allValid) {
                result.addPassedField(de, expected, validationDetails.toString());
            } else {
                result.addFailedField(de, expected, validationDetails.toString());
            }
            return allValid;

        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate additional fees: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates DE 54 (Additional Amounts) with position-based validation
     */
    private static boolean validateAdditionalAmounts(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            JsonNode actualJson = objectMapper.readTree(actual);
            JsonNode positions = rules.get("positions");
            boolean allValid = true;
            StringBuilder validationDetails = new StringBuilder();

            // Get the first additional amount from the array
            JsonNode additionalAmounts = actualJson.path("transaction").path("additionalAmounts");
            if (!additionalAmounts.isArray() || additionalAmounts.size() == 0) {
                result.addFailedField(de, expected, "No additional amounts found in canonical response");
                return false;
            }
            JsonNode amount = additionalAmounts.get(0);

            // Validate Account Type (positions 1-2)
            String accountType = expected.substring(0, 2);
            String expectedAccountType = positions.get("accountType").get("mapping").get(accountType).asText();
            String actualAccountType = amount.path("accountType").asText();
            boolean accountTypeValid = expectedAccountType.equals(actualAccountType);
            validationDetails.append(String.format("Account Type: %s->%s (%s), ",
                    accountType, expectedAccountType, accountTypeValid ? "✓" : "✗"));
            allValid &= accountTypeValid;

            // Validate Amount Type (positions 3-4)
            String amountType = expected.substring(2, 4);
            String expectedAmountType = positions.get("amountType").get("mapping").get(amountType).asText();
            String actualAmountType = amount.path("amountType").asText();
            boolean amountTypeValid = expectedAmountType.equals(actualAmountType);
            validationDetails.append(String.format("Amount Type: %s->%s (%s), ",
                    amountType, expectedAmountType, amountTypeValid ? "✓" : "✗"));
            allValid &= amountTypeValid;

            // Validate Currency Code (positions 5-7)
            String currencyCode = expected.substring(4, 7);
            String actualCurrencyCode = amount.path("amount").path("currencyCode").asText();
            boolean currencyCodeValid = currencyCode.equals(actualCurrencyCode);
            validationDetails.append(String.format("Currency Code: %s (%s), ",
                    currencyCode, currencyCodeValid ? "✓" : "✗"));
            allValid &= currencyCodeValid;

            // Validate Amount (positions 8-20)
            JsonNode amountComponent = positions.get("amount");

            // Validate Debit/Credit Indicator (position 8)
            String indicator = expected.substring(7, 8);
            JsonNode components = amountComponent.path("components");
            JsonNode debitCreditIndicatorType = components.path("debitCreditIndicatorType");
            JsonNode mapping = debitCreditIndicatorType.path("mapping");
            String expectedIndicator = mapping.path(indicator).asText();
            String actualIndicator = amount.path("amount").path("debitCreditIndicatorType").asText();
            boolean indicatorValid = expectedIndicator.equals(actualIndicator);
            validationDetails.append(String.format("D/C Indicator: %s->%s (%s), ",
                    indicator, expectedIndicator, indicatorValid ? "✓" : "✗"));
            allValid &= indicatorValid;

            // Validate Amount Value (positions 9-20)
            String amountStr = expected.substring(8, 20);
            String normalizedAmount = String.valueOf(Long.parseLong(amountStr)); // Remove leading zeros
            String actualAmount = amount.path("amount").path("amount").asText();
            boolean amountValid = normalizedAmount.equals(actualAmount);
            validationDetails.append(String.format("Amount: %s->%s (%s)",
                    amountStr, normalizedAmount, amountValid ? "✓" : "✗"));
            allValid &= amountValid;

            if (allValid) {
                result.addPassedField(de, expected, validationDetails.toString());
            } else {
                result.addFailedField(de, expected, validationDetails.toString());
            }
            return allValid;

        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate additional amounts: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates DE 59 (National POS Geographic Data) with position-based validation
     */
    private static boolean validateNationalPosGeographicData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            JsonNode actualJson = objectMapper.readTree(actual);
            JsonNode positions = rules.get("positions");
            boolean allValid = true;
            StringBuilder validationDetails = new StringBuilder();

            // Validate State (positions 1-2)
            String state = expected.substring(0, 2);
            String actualState = getJsonValue(actualJson, "transaction.nationalPOSGeographicData.posGeographicData.state");
            boolean stateValid = state.equals(actualState);
            validationDetails.append(String.format("State: %s (%s), ",
                    state, stateValid ? "✓" : "✗"));
            allValid &= stateValid;

            // Validate County (positions 3-5)
            String county = expected.substring(2, 5);
            String actualCounty = getJsonValue(actualJson, "transaction.nationalPOSGeographicData.posGeographicData.countyCode");
            boolean countyValid = county.equals(actualCounty);
            validationDetails.append(String.format("County: %s (%s), ",
                    county, countyValid ? "✓" : "✗"));
            allValid &= countyValid;

            // Validate Postal Code (positions 6-14)
            String postalCode = expected.substring(5, 14);
            String actualPostalCode = getJsonValue(actualJson, "transaction.nationalPOSGeographicData.posGeographicData.zipCode");
            boolean postalCodeValid = postalCode.trim().equals(actualPostalCode);
            validationDetails.append(String.format("Postal Code: %s (%s), ",
                    postalCode, postalCodeValid ? "✓" : "✗"));
            allValid &= postalCodeValid;

            // Validate Country Code (positions 15-17)
            String countryCode = expected.substring(14, 17);
            String actualCountryCode = getJsonValue(actualJson, "transaction.nationalPOSGeographicData.posGeographicData.country.countryCode");
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

    /**
     * Gets the description for a response code
     * This can be expanded with more response codes and descriptions
     */
    private static String getResponseCodeDescription(String responseCode) {
        // This is a placeholder - you can add more response codes and descriptions
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("00", "Approved");
        descriptions.put("01", "Refer to card issuer");
        descriptions.put("05", "Do not honor");
        descriptions.put("13", "Invalid amount");
        descriptions.put("14", "Invalid card number");
        descriptions.put("51", "Insufficient funds");
        descriptions.put("54", "Expired card");
        descriptions.put("55", "Invalid PIN");
        descriptions.put("75", "Allowable number of PIN tries exceeded");
        descriptions.put("91", "Issuer or switch is inoperative");

        return descriptions.getOrDefault(responseCode, "Unknown response code");
    }

    private static boolean validateAvsData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            if (expected == null || actual == null) {
                result.addFailedField(de, expected, actual);
                return true;
            }

            JsonNode actualJson = objectMapper.readTree(actual);
            StringBuilder details = new StringBuilder();
            boolean allValid = true;

            // Validate prefix (should be "TDAV")
            String prefix = expected.substring(0, 4);
            if (!"TDAV".equals(prefix)) {
                details.append("Invalid prefix: expected TDAV, got ").append(prefix).append("; ");
                allValid = false;
            }

            // Extract and validate zip code length
            String zipLengthStr = expected.substring(4, 6);
            int zipLength;
            try {
                zipLength = Integer.parseInt(zipLengthStr);
            } catch (NumberFormatException e) {
                details.append("Invalid zip code length format: ").append(zipLengthStr).append("; ");
                result.addFailedField(de, expected, actual + " [" + details + "]");
                return true;
            }

            // Extract and validate zip code
            String expectedZipCode = expected.substring(6, 6 + zipLength);
            String actualZipCode = getJsonValue(actualJson, "transaction.member.address.zipCode");

            if (!expectedZipCode.equals(actualZipCode)) {
                details.append("Zip code mismatch: expected ").append(expectedZipCode)
                        .append(", got ").append(actualZipCode);
                allValid = false;
            }

            if (allValid) {
                result.addPassedField(de, expected, actual);
            } else {
                result.addFailedField(de, expected, actual + " [" + details + "]");
            }

            return true;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to parse AVS data: " + e.getMessage());
            return false;
        }
    }

    private static boolean validateAcquirerTraceData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            if (expected == null || actual == null || expected.length() < 93) {
                result.addFailedField(de, expected, "Invalid acquirer trace data length");
                return true;
            }

            JsonNode actualJson = objectMapper.readTree(actual);
            StringBuilder details = new StringBuilder();
            boolean allValid = true;

            // Validate format code
            String formatCode = expected.substring(0, 1);
            String expectedFormatCode = "DUAL_MESSAGE_CLEARING_FORMAT_CODE";
            String actualFormatCode = getJsonValue(actualJson, "transaction.acquirerTraceData.formatCode");
            if (!expectedFormatCode.equals(actualFormatCode)) {
                details.append("Format code mismatch: expected ").append(expectedFormatCode)
                        .append(", got ").append(actualFormatCode).append("; ");
                allValid = false;
            }

            // Validate acquirer reference number object
            validateAcquirerReferenceNumber(expected, actualJson, details);

            // Validate terminal type
            String terminalType = expected.substring(24, 27);
            String actualTerminalType = getJsonValue(actualJson, "transaction.acquirerTraceData.terminalType");
            if (!terminalType.equals(actualTerminalType)) {
                details.append("Terminal type mismatch: expected ").append(terminalType)
                        .append(", got ").append(actualTerminalType).append("; ");
                allValid = false;
            }

            // Validate acquirer institution ID
            String acquirerId = expected.substring(27, 38);
            String actualAcquirerId = getJsonValue(actualJson, "transaction.acquirerTraceData.acquirerInstituionId");
            if (!acquirerId.equals(actualAcquirerId)) {
                details.append("Acquirer ID mismatch: expected ").append(acquirerId)
                        .append(", got ").append(actualAcquirerId).append("; ");
                allValid = false;
            }

            // Validate transaction lifecycle
            validateTransactionLifeCycle(expected, actualJson, details);

            // Validate business activity
            validateBusinessActivity(expected, actualJson, details);

            // Validate settlement indicator
            String settlementInd = expected.substring(64, 65);
            String actualSettlementInd = getJsonValue(actualJson, "transaction.acquirerTraceData.settlementIndicator");
            if (!settlementInd.equals(actualSettlementInd)) {
                details.append("Settlement indicator mismatch: expected ").append(settlementInd)
                        .append(", got ").append(actualSettlementInd).append("; ");
                allValid = false;
            }

            // Validate interchange rate designator
            String interchangeRate = expected.substring(65, 67);
            String actualInterchangeRate = getJsonValue(actualJson, "transaction.acquirerTraceData.interchangeRateDesignator");
            if (!interchangeRate.equals(actualInterchangeRate)) {
                details.append("Interchange rate mismatch: expected ").append(interchangeRate)
                        .append(", got ").append(actualInterchangeRate).append("; ");
                allValid = false;
            }

            // Validate dates
            validateDate("businessDate", expected.substring(67, 73), actualJson, "transaction.acquirerTraceData.businessDate", details);
            validateDate("settlementDate", expected.substring(78, 84), actualJson, "transaction.acquirerTraceData.settlementDate", details);
            validateDate("currencyConversionDate", expected.substring(86, 92), actualJson, "transaction.acquirerTraceData.currencyConversionDate", details);

            // Validate other fields
            validateSimpleField("productIdentifier", expected.substring(73, 76), actualJson, "transaction.acquirerTraceData.productIdentifier", details);
            validateSimpleField("businessCycle", expected.substring(76, 78), actualJson, "transaction.acquirerTraceData.businessCycle", details);
            validateSimpleField("mastercardRateIndicator", expected.substring(84, 85), actualJson, "transaction.acquirerTraceData.mastercardRateIndicator", details);

            // Validate settlement service level code with mapping
            String settlementServiceLevel = expected.substring(85, 86);
            String expectedServiceLevel = settlementServiceLevel.equals("1") ? "Regional" :
                    settlementServiceLevel.equals("3") ? "Intracurrency" : settlementServiceLevel;
            String actualServiceLevel = getJsonValue(actualJson, "transaction.acquirerTraceData.settlementServiceLevelCode");
            if (!expectedServiceLevel.equals(actualServiceLevel)) {
                details.append("Settlement service level mismatch: expected ").append(expectedServiceLevel)
                        .append(", got ").append(actualServiceLevel).append("; ");
                allValid = false;
            }

            // Validate currency conversion indicator with mapping
            String conversionInd = expected.substring(92, 93);
            String expectedConversionInd = mapCurrencyConversionIndicator(conversionInd);
            String actualConversionInd = getJsonValue(actualJson, "transaction.acquirerTraceData.currencyConversionIndicator");
            if (!expectedConversionInd.equals(actualConversionInd)) {
                details.append("Currency conversion indicator mismatch: expected ").append(expectedConversionInd)
                        .append(", got ").append(actualConversionInd);
                allValid = false;
            }

            if (allValid) {
                result.addPassedField(de, expected, actual);
            } else {
                result.addFailedField(de, expected, actual + " [" + details + "]");
            }

            return true;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to parse acquirer trace data: " + e.getMessage());
            return false;
        }
    }

    private static void validateAcquirerReferenceNumber(String expected, JsonNode actualJson, StringBuilder details) {
        String mixedUse = expected.substring(1, 2);
        String refId = expected.substring(2, 8);
        String julianDate = expected.substring(8, 12);
        String sequence = expected.substring(12, 23);
        String checkDigit = expected.substring(23, 24);

        String actualMixedUse = getJsonValue(actualJson, "transaction.acquirerTraceData.acquirerReferenceNumberObject.mixedUse");
        String actualRefId = getJsonValue(actualJson, "transaction.acquirerTraceData.acquirerReferenceNumberObject.acquirerReferenceId");
        String actualJulianDate = getJsonValue(actualJson, "transaction.acquirerTraceData.acquirerReferenceNumberObject.julianDate");
        String actualSequence = getJsonValue(actualJson, "transaction.acquirerTraceData.acquirerReferenceNumberObject.acquirerSequence");
        String actualCheckDigit = getJsonValue(actualJson, "transaction.acquirerTraceData.acquirerReferenceNumberObject.checkDigit");

        if (!mixedUse.equals(actualMixedUse)) {
            details.append("Mixed use mismatch: expected ").append(mixedUse)
                    .append(", got ").append(actualMixedUse).append("; ");
        }
        if (!refId.equals(actualRefId)) {
            details.append("Reference ID mismatch: expected ").append(refId)
                    .append(", got ").append(actualRefId).append("; ");
        }
        if (!julianDate.equals(actualJulianDate)) {
            details.append("Julian date mismatch: expected ").append(julianDate)
                    .append(", got ").append(actualJulianDate).append("; ");
        }
        if (!sequence.equals(actualSequence)) {
            details.append("Sequence mismatch: expected ").append(sequence)
                    .append(", got ").append(actualSequence).append("; ");
        }
        if (!checkDigit.equals(actualCheckDigit)) {
            details.append("Check digit mismatch: expected ").append(checkDigit)
                    .append(", got ").append(actualCheckDigit).append("; ");
        }
    }

    private static void validateTransactionLifeCycle(String expected, JsonNode actualJson, StringBuilder details) {
        String indicator = expected.substring(38, 39);
        String traceId = expected.substring(39, 54);

        String actualIndicator = getJsonValue(actualJson, "transaction.acquirerTraceData.transactionLifeCycle.lifeCycleSupportIndicator");
        String actualTraceId = getJsonValue(actualJson, "transaction.acquirerTraceData.transactionLifeCycle.traceId");

        if (!indicator.equals(actualIndicator)) {
            details.append("Lifecycle indicator mismatch: expected ").append(indicator)
                    .append(", got ").append(actualIndicator).append("; ");
        }
        if (!traceId.equals(actualTraceId)) {
            details.append("Trace ID mismatch: expected ").append(traceId)
                    .append(", got ").append(actualTraceId).append("; ");
        }
    }

    private static void validateBusinessActivity(String expected, JsonNode actualJson, StringBuilder details) {
        String brandId = expected.substring(54, 57);
        String serviceLevel = expected.substring(57, 58);
        String serviceId = expected.substring(58, 64);

        String actualBrandId = getJsonValue(actualJson, "transaction.acquirerTraceData.businessActivity.acceptanceBrandId");
        String actualServiceLevel = getJsonValue(actualJson, "transaction.acquirerTraceData.businessActivity.businessServiceLevelCode");
        String actualServiceId = getJsonValue(actualJson, "transaction.acquirerTraceData.businessActivity.businessServiceIdCode");

        if (!brandId.equals(actualBrandId)) {
            details.append("Brand ID mismatch: expected ").append(brandId)
                    .append(", got ").append(actualBrandId).append("; ");
        }
        if (!serviceLevel.equals(actualServiceLevel)) {
            details.append("Service level mismatch: expected ").append(serviceLevel)
                    .append(", got ").append(actualServiceLevel).append("; ");
        }
        if (!serviceId.equals(actualServiceId)) {
            details.append("Service ID mismatch: expected ").append(serviceId)
                    .append(", got ").append(actualServiceId).append("; ");
        }
    }

    private static void validateDate(String fieldName, String expected, JsonNode actualJson, String jsonPath, StringBuilder details) {
        String actual = getJsonValue(actualJson, jsonPath);
        if (!expected.equals(actual)) {
            details.append(fieldName).append(" mismatch: expected ").append(expected)
                    .append(", got ").append(actual).append("; ");
        }
    }

    private static void validateSimpleField(String fieldName, String expected, JsonNode actualJson, String jsonPath, StringBuilder details) {
        String actual = getJsonValue(actualJson, jsonPath);
        if (!expected.equals(actual)) {
            details.append(fieldName).append(" mismatch: expected ").append(expected)
                    .append(", got ").append(actual).append("; ");
        }
    }

    private static String mapCurrencyConversionIndicator(String indicator) {
        switch (indicator) {
            case "0": return "Not Applicable";
            case "1": return "Matched with authorization";
            case "2": return "No match found";
            default: return indicator;
        }
    }

    private static boolean validateIssuerTraceData(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            if (expected == null || actual == null || expected.length() < 52) {
                result.addFailedField(de, expected, "Invalid issuer trace data length");
                return true;
            }

            JsonNode actualJson = objectMapper.readTree(actual);
            StringBuilder details = new StringBuilder();
            boolean allValid = true;

            // Validate format code
            String formatCode = expected.substring(0, 1);
            if (!"6".equals(formatCode)) {
                details.append("Invalid format code: expected 6, got ").append(formatCode).append("; ");
                allValid = false;
            }
            String actualFormatCode = getJsonValue(actualJson, "transaction.issuerTraceData.formatCode");
            String expectedFormatCode = "DUAL_MESSAGE_AUTH_FORMAT_CODE";
            if (!expectedFormatCode.equals(actualFormatCode)) {
                details.append("Format code mismatch: expected ").append(expectedFormatCode)
                        .append(", got ").append(actualFormatCode).append("; ");
                allValid = false;
            }

            // Validate system trace audit number
            String stan = expected.substring(1, 7);
            String actualStan = getJsonValue(actualJson, "transaction.issuerTraceData.systemTraceAuditNumber");
            if (!stan.equals(actualStan)) {
                details.append("System trace audit number mismatch: expected ").append(stan)
                        .append(", got ").append(actualStan).append("; ");
                allValid = false;
            }

            // Validate transmission date time
            String transmissionDateTime = expected.substring(7, 17);
            String actualTransmissionDateTime = getJsonValue(actualJson, "transaction.issuerTraceData.transmissionDateTime");
            if (!transmissionDateTime.equals(actualTransmissionDateTime)) {
                details.append("Transmission date time mismatch: expected ").append(transmissionDateTime)
                        .append(", got ").append(actualTransmissionDateTime).append("; ");
                allValid = false;
            }

            // Validate settlement date
            String settlementDate = expected.substring(17, 21);
            String actualSettlementDate = getJsonValue(actualJson, "transaction.issuerTraceData.settlementDate");
            if (!settlementDate.equals(actualSettlementDate)) {
                details.append("Settlement date mismatch: expected ").append(settlementDate)
                        .append(", got ").append(actualSettlementDate).append("; ");
                allValid = false;
            }

            // Validate financial network code
            String networkCode = expected.substring(21, 24);
            String actualNetworkCode = getJsonValue(actualJson, "transaction.issuerTraceData.financialNetworkCode");
            if (!networkCode.equals(actualNetworkCode)) {
                details.append("Financial network code mismatch: expected ").append(networkCode)
                        .append(", got ").append(actualNetworkCode).append("; ");
                allValid = false;
            }

            // Validate banknet reference number and merchant type
            String banknetRef = expected.substring(24, 33);
            String actualBanknetRef = getJsonValue(actualJson, "transaction.issuerTraceData.banknetReferenceNumber");

            // Check if merchant type is present (banknet ref ends with 3 spaces)
            boolean hasMerchantType = banknetRef.endsWith("   ");
            String expectedBanknetRef = hasMerchantType ? banknetRef : banknetRef.substring(0, 6);

            if (!expectedBanknetRef.equals(actualBanknetRef)) {
                details.append("Banknet reference number mismatch: expected ").append(expectedBanknetRef)
                        .append(", got ").append(actualBanknetRef).append("; ");
                allValid = false;
            }

            if (hasMerchantType) {
                String merchantType = expected.substring(33, 37);
                String actualMerchantType = getJsonValue(actualJson, "transaction.issuerTraceData.merchantType");
                if (!merchantType.equals(actualMerchantType)) {
                    details.append("Merchant type mismatch: expected ").append(merchantType)
                            .append(", got ").append(actualMerchantType).append("; ");
                    allValid = false;
                }
            }

            // Validate trace ID
            String traceId = expected.substring(37, 52);
            String actualTraceId = getJsonValue(actualJson, "transaction.issuerTraceData.traceId");
            if (!traceId.equals(actualTraceId)) {
                details.append("Trace ID mismatch: expected ").append(traceId)
                        .append(", got ").append(actualTraceId);
                allValid = false;
            }

            if (allValid) {
                result.addPassedField(de, expected, actual);
            } else {
                result.addFailedField(de, expected, actual + " [" + details + "]");
            }

            return true;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to parse issuer trace data: " + e.getMessage());
            return false;
        }
    }

    private static boolean validateAdviceReversalCode(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            if (expected == null || actual == null || expected.length() != 4) {
                result.addFailedField(de, expected, "Invalid advice/reversal code length");
                return false;
            }

            JsonNode actualJson = objectMapper.readTree(actual);
            StringBuilder details = new StringBuilder();

            // Get positions 1-2 to determine type (80=reversal, 40=advice)
            String typeIndicator = expected.substring(0, 2);
            // Get positions 3-4 for the reason code
            String reasonCode = expected.substring(2, 4);

            // Validate only the relevant path based on type
            if ("80".equals(typeIndicator)) {
                // Only validate reversal path
                JsonNode reversalReasons = rules.get("positions").get("reversalReasons");

                if (!reversalReasons.has(reasonCode)) {
                    details.append("Invalid reversal reason code: ").append(reasonCode);
                    result.addFailedField(de, expected, details.toString());
                    return false;
                }

                String expectedEnum = reversalReasons.get(reasonCode).asText();
                String actualValue = getJsonValue(actualJson, "transaction.reversalReason");

                if (actualValue == null) {
                    details.append("Missing reversal reason code in response");
                    result.addFailedField(de, expected, details.toString());
                    return false;
                }

                if (!expectedEnum.equals(actualValue)) {
                    details.append("Reversal reason mismatch: expected ")
                            .append(expectedEnum)
                            .append(", got ")
                            .append(actualValue);
                    result.addFailedField(de, expected, details.toString());
                    return false;
                }

                result.addPassedField(de, expected, actualValue);
                return true;
            } else if ("40".equals(typeIndicator)) {
                // Only validate advice path
                JsonNode adviceReasons = rules.get("positions").get("adviceReasons");

                if (!adviceReasons.has(reasonCode)) {
                    details.append("Invalid advice reason code: ").append(reasonCode);
                    result.addFailedField(de, expected, details.toString());
                    return false;
                }

                String expectedEnum = adviceReasons.get(reasonCode).asText();
                String actualValue = getJsonValue(actualJson, "transaction.adviceReason");

                if (actualValue == null) {
                    details.append("Missing advice reason code in response");
                    result.addFailedField(de, expected, details.toString());
                    return false;
                }

                if (!expectedEnum.equals(actualValue)) {
                    details.append("Advice reason mismatch: expected ")
                            .append(expectedEnum)
                            .append(", got ")
                            .append(actualValue);
                    result.addFailedField(de, expected, details.toString());
                    return false;
                }

                result.addPassedField(de, expected, actualValue);
                return true;
            } else {
                details.append("Invalid message type indicator: ").append(typeIndicator)
                        .append(" (must be 40 or 80)");
                result.addFailedField(de, expected, details.toString());
                return false;
            }
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate advice/reversal code: " + e.getMessage());
            return false;
        }
    }

    private static boolean validateReplacementAmounts(String de, String expected, String actual, ValidationResult result, JsonNode rules) {
        try {
            // Check MTI requirement from configuration
            if (rules.has("mti") && rules.get("mti").has("required")) {
                String requiredMti = rules.get("mti").get("required").asText();
                String skipReason = rules.get("mti").has("skipReason") ?
                        rules.get("mti").get("skipReason").asText() :
                        "DE " + de + " validation only applicable for MTI " + requiredMti;

                // Safely get MTI value with null check
                String currentMti = isoFields.get(0);
                if (!requiredMti.equals(currentMti)) {
                    result.addSkippedField(de, expected, skipReason);
                    return true;
                }
            }

            if (expected == null || actual == null || expected.length() != 42) {
                result.addFailedField(de, expected, "Invalid replacement amounts length");
                return false;
            }

            JsonNode actualJson = objectMapper.readTree(actual);
            StringBuilder details = new StringBuilder();
            boolean allValid = true;

            JsonNode positions = rules.get("positions");

            // Validate Transaction Amount (positions 1-12)
            String transactionAmount = expected.substring(0, 12);
            String normalizedTransactionAmount = String.valueOf(Long.parseLong(transactionAmount)); // Remove leading zeros
            String actualTransactionAmount = getJsonValue(actualJson, "transaction.replacementAmount.transactionAmount.amount");
            boolean transactionAmountValid = normalizedTransactionAmount.equals(actualTransactionAmount);
            details.append(String.format("Transaction Amount: %s->%s (%s), ",
                    transactionAmount, normalizedTransactionAmount, transactionAmountValid ? "✓" : "✗"));
            allValid &= transactionAmountValid;

            // Validate Settlement Amount (positions 13-24)
            String settlementAmount = expected.substring(12, 24);
            String normalizedSettlementAmount = String.valueOf(Long.parseLong(settlementAmount)); // Remove leading zeros
            String actualSettlementAmount = getJsonValue(actualJson, "transaction.replacementAmount.settlementAmount.amount");
            boolean settlementAmountValid = normalizedSettlementAmount.equals(actualSettlementAmount);
            details.append(String.format("Settlement Amount: %s->%s (%s), ",
                    settlementAmount, normalizedSettlementAmount, settlementAmountValid ? "✓" : "✗"));
            allValid &= settlementAmountValid;

            // Validate Transaction Fees (positions 24-33)
            JsonNode transactionFees = positions.get("transactionFees");

            // Validate Transaction Fees D/C Indicator (position 24)
            String transactionFeesIndicator = expected.substring(23, 24);
            String expectedTransactionFeesIndicator = transactionFees.get("components")
                    .get("debitCreditIndicator").get("mapping").get(transactionFeesIndicator).asText();
            String actualTransactionFeesIndicator = getJsonValue(actualJson,
                    "transaction.replacementAmount.transactionFees.transactionFees.debitCreditIndicatorType");
            boolean transactionFeesIndicatorValid = expectedTransactionFeesIndicator.equals(actualTransactionFeesIndicator);
            details.append(String.format("Transaction Fees D/C: %s->%s (%s), ",
                    transactionFeesIndicator, expectedTransactionFeesIndicator, transactionFeesIndicatorValid ? "✓" : "✗"));
            allValid &= transactionFeesIndicatorValid;

            // Validate Transaction Fees Amount (positions 25-33)
            String transactionFeesAmount = expected.substring(24, 33);
            String normalizedTransactionFeesAmount = String.valueOf(Long.parseLong(transactionFeesAmount)); // Remove leading zeros
            String actualTransactionFeesAmount = getJsonValue(actualJson,
                    "transaction.replacementAmount.transactionFees.transactionFees.amount");
            boolean transactionFeesAmountValid = normalizedTransactionFeesAmount.equals(actualTransactionFeesAmount);
            details.append(String.format("Transaction Fees Amount: %s->%s (%s), ",
                    transactionFeesAmount, normalizedTransactionFeesAmount, transactionFeesAmountValid ? "✓" : "✗"));
            allValid &= transactionFeesAmountValid;

            // Validate Settlement Fees (positions 34-42)
            JsonNode settlementFees = positions.get("settlementFees");

            // Validate Settlement Fees D/C Indicator (position 34)
            String settlementFeesIndicator = expected.substring(33, 34);
            String expectedSettlementFeesIndicator = settlementFees.get("components")
                    .get("debitCreditIndicator").get("mapping").get(settlementFeesIndicator).asText();
            String actualSettlementFeesIndicator = getJsonValue(actualJson,
                    "transaction.replacementAmount.settlementFees.settlementFees.debitCreditIndicatorType");
            boolean settlementFeesIndicatorValid = expectedSettlementFeesIndicator.equals(actualSettlementFeesIndicator);
            details.append(String.format("Settlement Fees D/C: %s->%s (%s), ",
                    settlementFeesIndicator, expectedSettlementFeesIndicator, settlementFeesIndicatorValid ? "✓" : "✗"));
            allValid &= settlementFeesIndicatorValid;

            // Validate Settlement Fees Amount (positions 35-42)
            String settlementFeesAmount = expected.substring(34, 42);
            String normalizedSettlementFeesAmount = String.valueOf(Long.parseLong(settlementFeesAmount)); // Remove leading zeros
            String actualSettlementFeesAmount = getJsonValue(actualJson,
                    "transaction.replacementAmount.settlementFees.settlementFees.amount");
            boolean settlementFeesAmountValid = normalizedSettlementFeesAmount.equals(actualSettlementFeesAmount);
            details.append(String.format("Settlement Fees Amount: %s->%s (%s)",
                    settlementFeesAmount, normalizedSettlementFeesAmount, settlementFeesAmountValid ? "✓" : "✗"));
            allValid &= settlementFeesAmountValid;

            if (allValid) {
                result.addPassedField(de, expected, details.toString());
            } else {
                result.addFailedField(de, expected, details.toString());
            }
            return allValid;

        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to validate replacement amounts: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates DE 111 (Additional Data) based on format identifier MC or MD
     *
     * @param de          The data element number (111)
     * @param expected    The expected DE 111 value
     * @param actual      The actual canonical JSON response
     * @param result      The validation result object
     * @param fieldConfig
     * @return true if validation passes, false otherwise
     */
    private static boolean validateAdditionalData(String de, String expected, String actual, ValidationResult result, Map<String, JsonNode> fieldConfig) {
        try {
            if (expected == null || expected.length() < 14) { // Must have at least format identifier, length, and bitmap
                result.addFailedField(de, expected, "Invalid DE 111 length");
                return false;
            }

            JsonNode actualJson = objectMapper.readTree(actual);
            StringBuilder details = new StringBuilder();
            boolean allValid = true;

            // Get format identifier (MC or MD) - positions 1-2
            String formatIdentifier = expected.substring(0, 2);
            String actualFormatIdentifier = getJsonValue(actualJson, "transaction.additionalData.formatIdentifier");

            if (!formatIdentifier.equals(actualFormatIdentifier)) {
                result.addFailedField(de, formatIdentifier, actualFormatIdentifier);
                return false;
            }

            // Get config for format
            JsonNode formatConfig = fieldConfig.get("111").get("validation").get("rules").get("formatIdentifiers").get(formatIdentifier);
            if (formatConfig == null) {
                result.addFailedField(de, expected, "Unsupported format identifier: " + formatIdentifier);
                return false;
            }

            // Get the list of valid paths for this format
            JsonNode validPaths = formatConfig.get("paths");
            if (validPaths == null || !validPaths.isArray()) {
                result.addFailedField(de, expected, "Invalid format configuration - missing paths array");
                return false;
            }

            // Create a map of field names to their canonical paths
            Map<String, String> fieldPaths = new HashMap<>();
            validPaths.forEach(pathNode -> {
                String path = pathNode.asText();
                String fieldName = path.substring(path.lastIndexOf(".") + 1);
                fieldPaths.put(fieldName, path);
            });

            // Get primary bitmap (positions 6-13)
            String primaryBitmapHex = expected.substring(5, 13);
            String primaryBitmapBinary = hexToBinary(primaryBitmapHex);

            // Start processing from position 14 (after format identifier, length, and primary bitmap)
            int currentPos = 13;

            // Process primary bitmap bits (32 bits)
            for (int bit = 1; bit <= 32; bit++) {
                JsonNode bitConfig = formatConfig.get("primaryBitmap").get("fields").get(String.valueOf(bit));
                if (bitConfig != null) {
                    int fieldLength = bitConfig.get("length").asInt();
                    String fieldName = bitConfig.get("name").asText();

                    // If bit is set (1), validate the field
                    if (primaryBitmapBinary.charAt(bit - 1) == '1') {
                        String fieldValue = expected.substring(currentPos, currentPos + fieldLength);

                        // Only validate if this field has a path in the paths array
                        if (fieldPaths.containsKey(fieldName)) {
                            String canonicalPath = fieldPaths.get(fieldName);

                            // Special handling for isCnp
                            if ((formatIdentifier.equals("MD") && bit == 5) || (formatIdentifier.equals("MC") && bit == 7)
                                    && "transaction.additionalData.isCnp".equals(canonicalPath)) {
                                // For isCnp: 0 = true, 1 = not present
                                if (fieldValue.equals("0")) {
                                    String actualValue = getJsonValue(actualJson, canonicalPath);
                                    if (!"true".equals(actualValue)) {
                                        details.append(String.format("Field %d (isCnp) mismatch: expected=true, actual=%s; ",
                                                bit, actualValue));
                                        allValid = false;
                                    } else {
                                        // This is correct - field not should be present
                                        allValid = true;
                                    }
                                } else if (fieldValue.equals("1")) {
                                    // isCnp should not be present in the object
                                    if (actualJson.at(canonicalPath).isNull() || actualJson.at(canonicalPath).isMissingNode()) {
                                        // This is correct - field should not be present
                                        allValid = true;
                                    } else {
                                        details.append(String.format("Field %d (isCnp) error: should not be present when value is 1; ", bit));
                                        allValid = false;
                                    }
                                } else {
                                    details.append(String.format("Field %d (isCnp) invalid value: %s; ", bit, fieldValue));
                                    allValid = false;
                                }
                            } else {
                                // Normal validation for other fields
                                String actualValue = getJsonValue(actualJson, canonicalPath);
                                if (!fieldValue.equals(actualValue)) {
                                    details.append(String.format("Field %d (%s) mismatch: expected=%s, actual=%s; ",
                                            bit, fieldName, fieldValue, actualValue));
                                    allValid = false;
                                }
                            }
                        }
                        // Only advance position if it's not bit 32 (secondary bitmap indicator)
                        if (bit != 32) {
                            currentPos += fieldLength;
                        }
                    }
                }
            }

            // Check if secondary bitmap is present (bit 32 of primary bitmap)
            if (primaryBitmapBinary.charAt(31) == '1') {
                String secondaryBitmapHex = expected.substring(currentPos, currentPos + 8);
                String secondaryBitmapBinary = hexToBinary(secondaryBitmapHex);
                currentPos += 8;  // Advance by 8 hex digits after reading secondary bitmap

                // Process secondary bitmap bits (32 bits)
                for (int bit = 1; bit <= 32; bit++) {
                    int actualBit = bit + 32;  // Fields 33-64 for secondary bitmap
                    JsonNode bitConfig = formatConfig.get("secondaryBitmap").get("fields").get(String.valueOf(actualBit));
                    if (bitConfig != null) {
                        int fieldLength = bitConfig.get("length").asInt();
                        String fieldName = bitConfig.get("name").asText();

                        // If bit is set (1), process the field
                        if (secondaryBitmapBinary.charAt(bit - 1) == '1') {
                            String fieldValue = expected.substring(currentPos, currentPos + fieldLength);

                            // Validate if this field has a path in the paths array
                            if (fieldPaths.containsKey(fieldName)) {
                                String canonicalPath = fieldPaths.get(fieldName);
                                String actualValue = getJsonValue(actualJson, canonicalPath);

                                if (!fieldValue.equals(actualValue)) {
                                    details.append(String.format("Field %d (%s) mismatch: expected=%s, actual=%s; ",
                                            actualBit, fieldName, fieldValue, actualValue));
                                    allValid = false;
                                }
                            }
                            currentPos += fieldLength;
                        }
                    }
                }
            }

            if (!allValid) {
                result.addFailedField(de, expected, details.toString());
            } else {
                result.addPassedField(de, expected, actual);
            }

            return allValid;

        } catch (Exception e) {
            result.addFailedField(de, expected, "Error validating DE 111: " + e.getMessage());
            return false;
        }
    }
}