package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import java.io.IOException;
import java.util.*;

/**
 * Handles ISO message validation against canonical format
 */
public class IsoMessageValidator {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final IsoMessageConfig config;
    private final IsoCanonicalService canonicalService;

    public IsoMessageValidator(IsoMessageConfig config, IsoCanonicalService canonicalService) {
        this.config = config;
        this.canonicalService = canonicalService;
    }

    public ValidationResult validateMessage(String isoMessage, Row excelRow) throws IOException {
        ValidationResult result = new ValidationResult();
        
        // Get canonical response
        String canonicalResponse = canonicalService.getCanonicalResponse(isoMessage);
        JsonNode canonicalJson = objectMapper.readTree(canonicalResponse);
        
        // Extract values from Excel row
        Map<String, String> deValues = extractDEValuesFromExcel(excelRow);
        
        // Validate each field
        for (Map.Entry<String, String> entry : deValues.entrySet()) {
            String de = entry.getKey();
            String expectedValue = entry.getValue();
            
            List<String> canonicalPaths = config.getCanonicalPaths(de);
            if (!canonicalPaths.isEmpty()) {
                boolean foundMatch = false;
                for (String jsonPath : canonicalPaths) {
                    // Skip comments or placeholder paths
                    if (jsonPath.contains("-->") || jsonPath.startsWith("Tag :") || 
                        jsonPath.contains("Need to discuss") || jsonPath.contains("not canonicalize")) {
                        continue;
                    }
                    
                    JsonNode actualNode = getValueFromJsonPath(canonicalJson, jsonPath.trim());
                    if (actualNode != null) {
                        String actualValue = actualNode.asText();
                        if (expectedValue.equals(actualValue)) {
                            result.addPassedField(de, expectedValue, actualValue);
                            foundMatch = true;
                            break;
                        }
                    }
                }
                
                if (!foundMatch) {
                    result.addFailedField(de, expectedValue, 
                        "No matching value found in canonical paths: " + String.join(", ", canonicalPaths));
                }
            } else {
                result.addFailedField(de, expectedValue, "No canonical mapping found for DE " + de);
            }
        }
        
        return result;
    }

    private Map<String, String> extractDEValuesFromExcel(Row row) {
        Map<String, String> deValues = new HashMap<>();
        Row headerRow = row.getSheet().getRow(0);
        
        for (int colNum = 1; colNum <= 81; colNum++) {
            Cell headerCell = headerRow.getCell(colNum);
            Cell dataCell = row.getCell(colNum);
            
            if (headerCell != null && dataCell != null) {
                String de = getCellValueAsString(headerCell).trim();
                String value = getCellValueAsString(dataCell).trim();
                
                if (!de.isEmpty() && !value.isEmpty()) {
                    deValues.put(de, value);
                }
            }
        }
        
        return deValues;
    }

    private JsonNode getValueFromJsonPath(JsonNode rootNode, String path) {
        String[] parts = path.split("\\.");
        JsonNode current = rootNode;
        
        for (String part : parts) {
            current = current.path(part);
            if (current.isMissingNode()) {
                return null;
            }
        }
        
        return current;
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        
        switch (cell.getCellType()) {
            case NUMERIC:
                return String.valueOf((long)cell.getNumericCellValue());
            case STRING:
                return cell.getStringCellValue();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }
} 