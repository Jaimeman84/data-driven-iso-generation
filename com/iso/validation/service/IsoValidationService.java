package com.iso.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iso.validation.core.ValidationResult;
import com.iso.validation.validators.ValidatorFactory;
import org.apache.poi.ss.usermodel.Row;
import java.io.IOException;
import java.util.Map;

public class IsoValidationService {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final ValidatorFactory validatorFactory;
    private final Map<String, JsonNode> fieldConfig;

    public IsoValidationService(Map<String, JsonNode> fieldConfig) {
        this.validatorFactory = ValidatorFactory.getInstance();
        this.fieldConfig = fieldConfig;
    }

    public ValidationResult validateIsoMessageCanonical(String isoMessage, Row excelRow, 
            String parserUrl, String canonicalUrl) throws IOException {
        ValidationResult result = new ValidationResult();

        // Get the parsed ISO message
        String parsedResponse = sendIsoMessageToParser(isoMessage, parserUrl);
        JsonNode parsedArray = objectMapper.readTree(parsedResponse);

        // Get the canonical form
        String canonicalResponse = sendIsoMessageToCanonical(isoMessage, canonicalUrl);
        JsonNode canonicalJson = objectMapper.readTree(canonicalResponse);

        // Extract DE values from Excel
        Map<String, String> excelValues = extractDEValuesFromExcel(excelRow);

        // Validate each field from the parsed ISO message
        if (parsedArray.isArray()) {
            for (JsonNode element : parsedArray) {
                String de = element.get("dataElementId").asText();
                String expected = element.get("value").asText();
                
                // Skip validation if the field is not in Excel
                if (!excelValues.containsKey(de)) {
                    continue;
                }

                // Skip non-canonicalized fields
                if (isNonCanonicalized(de)) {
                    result.addSkippedField(de, expected, getSkipReason(de));
                    continue;
                }

                // Get validation rules if they exist
                JsonNode fieldRules = fieldConfig.get(de);
                JsonNode rules = fieldRules != null && fieldRules.has("validation") ? 
                    fieldRules.get("validation").get("rules") : null;

                // Validate the field
                validatorFactory.validate(de, expected, canonicalResponse, result, rules);
            }
        }

        return result;
    }

    private String sendIsoMessageToParser(String isoMessage, String parserUrl) throws IOException {
        // Implementation moved from CreateIsoMessage
        return ""; // Placeholder
    }

    private String sendIsoMessageToCanonical(String isoMessage, String canonicalUrl) throws IOException {
        // Implementation moved from CreateIsoMessage
        return ""; // Placeholder
    }

    private boolean isNonCanonicalized(String de) {
        JsonNode config = fieldConfig.get(de);
        return config != null && 
               config.has("validation") && 
               config.get("validation").has("skip") &&
               config.get("validation").get("skip").asBoolean();
    }

    private String getSkipReason(String de) {
        JsonNode config = fieldConfig.get(de);
        if (config != null && 
            config.has("validation") && 
            config.get("validation").has("reason")) {
            return config.get("validation").get("reason").asText();
        }
        return "No reason specified";
    }

    private Map<String, String> extractDEValuesFromExcel(Row row) {
        // Implementation moved from CreateIsoMessage
        return null; // Placeholder
    }
} 