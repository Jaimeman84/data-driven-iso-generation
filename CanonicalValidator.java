package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

import static utilities.ValidationResultManager.*;

public class CanonicalValidator {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Gets the canonical path(s) for a given DE from the config
     */
    public static List<String> getCanonicalPaths(String de, Map<String, JsonNode> fieldConfig) {
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("canonical")) {
            List<String> paths = new ArrayList<>();
            JsonNode canonical = config.get("canonical");
            if (canonical.isArray()) {
                canonical.forEach(path -> paths.add(path.asText()));
            }
            return paths;
        }
        return Collections.emptyList();
    }

    /**
     * Helper method to safely get value from JSON path, with case-insensitive field matching
     */
    public static String getJsonValue(JsonNode node, String path) {
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
     * Gets a value from a JSON node using a dot-notation path
     */
    public static JsonNode getValueFromJsonPath(JsonNode rootNode, String path) {
        String[] parts = path.split("\\.");
        JsonNode current = rootNode;

        for (String part : parts) {
            // Handle array access notation [n]
            if (part.contains("[") && part.contains("]")) {
                String fieldName = part.substring(0, part.indexOf("["));
                String indexStr = part.substring(part.indexOf("[") + 1, part.indexOf("]"));
                int index = Integer.parseInt(indexStr);

                // Get the array field first
                current = current.path(fieldName);
                if (current.isMissingNode()) {
                    return null;
                }

                // Then access the array element
                if (current.isArray() && current.size() > index) {
                    current = current.get(index);
                } else {
                    return null;
                }
            } else {
                current = current.path(part);
                if (current.isMissingNode()) {
                    return null;
                }
            }
        }

        return current;
    }

    /**
     * Validates an ISO message against its canonical form
     */
    public static ValidationResult validateIsoMessageCanonical(String isoMessage, Map<String, String> deValues, 
            Map<String, JsonNode> fieldConfig, Map<Integer, String> isoFields) throws IOException {
        ValidationResult result = new ValidationResult();

        // Get canonical response
        String canonicalResponse = IsoMessageNetworkClient.sendIsoMessageToCanonical(isoMessage);

        // Parse the canonical response once
        JsonNode canonicalJson = objectMapper.readTree(canonicalResponse);

        // Validate each field
        for (Map.Entry<String, String> entry : deValues.entrySet()) {
            String de = entry.getKey();
            String expectedValue = entry.getValue();

            // Skip validation for non-canonicalized fields
            if (isNonCanonicalized(de, fieldConfig, isoFields)) {
                result.addSkippedField(de, expectedValue, getSkipReason(de, fieldConfig));
                continue;
            }

            List<String> canonicalPaths = getCanonicalPaths(de, fieldConfig);
            if (!canonicalPaths.isEmpty()) {
                boolean allPathsValid = true;
                StringBuilder validationDetails = new StringBuilder();

                for (String jsonPath : canonicalPaths) {
                    // Skip comments or placeholder paths
                    if (jsonPath.contains("-->") || jsonPath.startsWith("Tag :") ||
                            jsonPath.contains("Need to discuss") || jsonPath.contains("not canonicalize")) {
                        continue;
                    }

                    JsonNode actualNode = getValueFromJsonPath(canonicalJson, jsonPath.trim());
                    if (actualNode != null) {
                        String actualValue = actualNode.asText();

                        if (!expectedValue.equals(actualValue)) {
                            allPathsValid = false;
                            String elementName = jsonPath.substring(jsonPath.lastIndexOf(".") + 1);
                            if (elementName.contains("[")) {
                                elementName = elementName.substring(0, elementName.indexOf("["));
                            }
                            validationDetails.append(elementName)
                                    .append(" mismatch; ");
                        }
                    } else {
                        allPathsValid = false;
                        String elementName = jsonPath.substring(jsonPath.lastIndexOf(".") + 1);
                        if (elementName.contains("[")) {
                            elementName = elementName.substring(0, elementName.indexOf("["));
                        }
                        validationDetails.append(elementName)
                                .append(" missing; ");
                    }
                }

                if (allPathsValid) {
                    result.addPassedField(de, expectedValue, "All paths validated successfully");
                } else {
                    result.addFailedField(de, expectedValue, validationDetails.toString());
                }
            } else {
                result.addFailedField(de, expectedValue, "No canonical mapping found for DE " + de);
            }
        }

        return result;
    }

    /**
     * Checks if a DE should not be canonicalized based on config
     */
    private static boolean isNonCanonicalized(String de, Map<String, JsonNode> fieldConfig, Map<Integer, String> isoFields) {
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("validation")) {
            JsonNode validation = config.get("validation");

            // Check for explicit skip flag
            if (validation.has("skip") && validation.get("skip").asBoolean()) {
                return true;
            }

            // Check for MTI-dependent validation
            if (validation.has("type") && validation.get("type").asText().equals("incremental_auth_data")) {
                JsonNode rules = validation.get("rules");
                if (rules != null && rules.has("mti") && rules.get("mti").has("required")) {
                    String requiredMti = rules.get("mti").get("required").asText();
                    return !requiredMti.equals(isoFields.get(0));
                }
            }
        }
        return false;
    }

    /**
     * Gets the validation reason for skipped fields
     */
    private static String getSkipReason(String de, Map<String, JsonNode> fieldConfig) {
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("validation")) {
            JsonNode validation = config.get("validation");
            if (validation.has("reason")) {
                return validation.get("reason").asText();
            }

            if (de.equals("124")) {
                JsonNode rule = validation.get("rules").get("mti");
                if (rule.has("skipReason")){
                    return rule.get("skipReason").asText();
                }
            }
        }
        return "Field is not canonicalized";
    }
} 