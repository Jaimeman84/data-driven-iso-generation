package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.stream.Collectors;

import static utilities.CustomTestData.generateCustomValue;
import static utilities.CustomTestData.generateRandomText;

public class CreateIsoMessage  {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static Map<String, JsonNode> fieldConfig;
    private static Map<Integer, String> isoFields = new TreeMap<>();
    private static boolean[] primaryBitmap = new boolean[64];
    private static boolean[] secondaryBitmap = new boolean[64];
    private static Set<String> manuallyUpdatedFields = new HashSet<>(); // Tracks modified fields
    private static final String PARSER_URL = "enter url here"; // Replace with actual URL

    public void i_create_iso_message(String requestName, DataTable dt) throws IOException {
        loadConfig("iso_config.json");

        List<Map<String, String>> rows = dt.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            String jsonPath = row.get("JSONPATH");
            String value = row.get("Value");
            String dataType = row.get("DataType");

            applyBddUpdate(jsonPath, value, dataType);
        }

        // Generate default fields, ensuring Primary Bitmap is correct
        generateDefaultFields();

        // Build ISO message & JSON output
        String isoMessage = buildIsoMessage();
        String jsonOutput = buildJsonMessage();

        // Print Outputs
        System.out.println("Generated ISO8583 Message:");
        System.out.println(isoMessage);
        System.out.println("\nGenerated JSON Output:");
        System.out.println(jsonOutput);

    }

    public static void loadConfig(String filename) throws IOException {

        String filepath = System.getProperty("user.dir");
        Path pathName;

        if(System.getProperty("os.name").startsWith("Windows")) {
            if(filename.contains("/")) {
                filename=filename.split("/")[0]+"\\"+filename.split("/")[1];
            }
            pathName =Path.of(filepath + "\\src\\test\\resources\\" + filename);
        }
        else {
            if(filename.contains("/")) {
                filename=filename.split("/")[0]+"/"+filename.split("/")[1];
            }
            pathName = Path.of(filepath + "/src/test/resources/" + filename);
        }


        String s=Files.readString(pathName);
        JsonNode jsonNode = objectMapper.readTree(s);
        fieldConfig = new HashMap<>();
        for (Iterator<String> it = jsonNode.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            fieldConfig.put(field, jsonNode.get(field));
        }
    }

    public static void generateDefaultFields() {
        // Ensure MTI defaults to "0100" if not manually set by the user

        if (!isoFields.containsKey(0) && !manuallyUpdatedFields.contains("MTI")) {

            isoFields.put(0, "0100");
        }

        for (String field : fieldConfig.keySet()) {
            JsonNode config = fieldConfig.get(field);
            boolean active = config.get("active").asBoolean();

            if (active && !manuallyUpdatedFields.contains(field)) {
                if(!field.contains("MTI")) {
                    addField(field, generateRandomValue(config));
                }
            }
        }
    }


    public static void applyBddUpdate(String jsonPath, String value, String dataType) {
        String fieldNumber = getFieldNumberFromJsonPath(jsonPath);
        if (fieldNumber == null) {
            System.out.println("Warning: No field found for JSONPath " + jsonPath);
            return;
        }

        JsonNode config = fieldConfig.get(fieldNumber);
        int maxLength = config.has("max_length") ? config.get("max_length").asInt() : config.get("length").asInt();
        String type = config.get("type").asText();

        value = generateCustomValue(value, type);

        // Validate length & type (WARN, not stop execution)
        if (value.length() > maxLength) {
            System.out.println("Warning: Value- "+value+"  for field " + fieldNumber + " exceeds max length " + maxLength + " (Truncated)");
            value = value.substring(0, maxLength);
        }
        if (!type.equalsIgnoreCase(dataType)) {
            System.out.println("Warning: Data type mismatch for field " + fieldNumber + ". Expected: " + type + ", Provided: " + dataType);
        }

        // Store the manually updated field & add to ISO message
        manuallyUpdatedFields.add(fieldNumber);
        addField(fieldNumber, value);
    }

    private static void addField(String field, String value) {
        // Handle MTI separately as a string
        if (field.equalsIgnoreCase("MTI")) {
            isoFields.put(0, value);
            return;
        }

        // Handle Primary Bitmap separately
        if (field.equalsIgnoreCase("PrimaryBitmap") || field.equalsIgnoreCase("SecondaryBitmap")) {
            return; // Bitmaps are automatically generated, do not parse as numeric
        }

        // Convert field number to integer, handling errors
        int fieldNumber;
        try {
            fieldNumber = Integer.parseInt(field);
        } catch (NumberFormatException e) {
            System.out.println("Warning: Invalid field number encountered: " + field);
            return;
        }

        // Store field value and update bitmap
        isoFields.put(fieldNumber, value);
        if (fieldNumber <= 64) {
            primaryBitmap[fieldNumber - 1] = true;
        } else {
            secondaryBitmap[fieldNumber - 65] = true;
            primaryBitmap[0] = true; // Ensure secondary bitmap is marked active
        }
    }



    private static String generateRandomValue(JsonNode config) {
        String type = config.get("type").asText();
        int maxLength = config.has("max_length") ? config.get("max_length").asInt() : config.get("length").asInt();
        return generateRandomText(type, maxLength);
    }

    public static String buildIsoMessage() {
        StringBuilder message = new StringBuilder();

        // Ensure MTI is included, default to "0100" if not manually set
        if (!isoFields.containsKey(0)) {

            message.append("0100");
        } else {
            System.out.println(isoFields.get(0));

            message.append(isoFields.get(0));
        }

        // Ensure bitmap is only generated if at least one field is present in DE 1-64
        boolean hasPrimaryFields = hasActivePrimaryFields();
        if (hasPrimaryFields) {
            message.append(bitmapToHex(primaryBitmap));
        }

        // Only include Secondary Bitmap if DE 65-128 are present
        if (hasActiveSecondaryFields()) {
            message.append(bitmapToHex(secondaryBitmap));
        }

        // Append each field value
        for (int field : isoFields.keySet()) {
            JsonNode config = fieldConfig.get(String.valueOf(field));
            if (config == null) continue;

            // LLVAR and LLLVAR handling
            if ("llvar".equals(config.get("format").asText())) {
                message.append(String.format("%02d", isoFields.get(field).length()));
            } else if ("lllvar".equals(config.get("format").asText())) {
                message.append(String.format("%03d", isoFields.get(field).length()));
            }
            message.append(isoFields.get(field));
        }
        return message.toString();
    }

    private static boolean hasActiveSecondaryFields() {
        for (int i = 0; i < 64; i++) {
            if (secondaryBitmap[i] && isoFields.containsKey(i + 65)) {  // Check fields 65-128
                return true; // Secondary bitmap is required
            }
        }
        return false; // No active fields in DE 65-128
    }

    public static String buildJsonMessage() throws IOException {
        Map<String, Object> outputJson = new HashMap<>();

        // Ensure MTI is correctly stored and printed
        if (!isoFields.containsKey(0) && !manuallyUpdatedFields.contains("MTI")) {

            outputJson.put("MTI", isoFields.getOrDefault(0, "0100"));
        }
        else{
            System.out.println(isoFields.get(0));
            outputJson.put("MTI", isoFields.get(0));
        }

        // Print Primary Bitmap only if active
        if (hasActivePrimaryFields()) {
            outputJson.put("PrimaryBitmap", bitmapToHex(primaryBitmap));
        }

        // Print Secondary Bitmap only if required
        if (hasActiveSecondaryFields()) {
            outputJson.put("SecondaryBitmap", bitmapToHex(secondaryBitmap));
        }
        // Loop through all fields except MTI (Field_0)
        for (int field : isoFields.keySet()) {
            if (field == 0) continue; // Skip MTI from being printed as Field_0

            JsonNode config = fieldConfig.get(String.valueOf(field));
            if (config == null) continue;

            String value = isoFields.get(field);
            String formattedValue = value;

            // Append LLVAR/LLLVAR length values before the actual data
            if ("llvar".equals(config.get("format").asText())) {
                formattedValue = String.format("%02d", value.length()) + value;
            } else if ("lllvar".equals(config.get("format").asText())) {
                formattedValue = String.format("%03d", value.length()) + value;
            }

            // Store correctly formatted field value in JSON output
            outputJson.put("Field_" + field, formattedValue);
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(outputJson);
    }

    private static String bitmapToHex(boolean[] bitmap) {
        StringBuilder binary = new StringBuilder();
        for (boolean bit : bitmap) {
            binary.append(bit ? "1" : "0");
        }

        // Convert binary string to hex
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 64; i += 4) {
            hex.append(Integer.toHexString(Integer.parseInt(binary.substring(i, i + 4), 2)).toUpperCase());
        }

        return hex.toString();
    }

    private static boolean hasActivePrimaryFields() {
        // Check if any fields 1-64 are present
        for (int field : isoFields.keySet()) {
            if (field > 0 && field <= 64) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends an ISO8583 message to the parser service
     * @param isoMessage The ISO8583 message to send
     * @return The JSON response from the parser
     */
    public static String sendIsoMessageToParser(String isoMessage) throws IOException {
        URL url = new URL(PARSER_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/plain");
        connection.setDoOutput(true);

        // Send the request
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = isoMessage.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        // Get response code
        int responseCode = connection.getResponseCode();
        StringBuilder response = new StringBuilder();

        // Use error stream for 400 responses, input stream for successful responses
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(responseCode == 400 
                        ? connection.getErrorStream()
                        : connection.getInputStream(), "utf-8"))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
        }

        // For 400 responses, try to parse the error message
        if (responseCode == 400) {
            try {
                JsonNode errorNode = objectMapper.readTree(response.toString());
                if (errorNode.has("message")) {
                    return "Error: " + errorNode.get("message").asText();
                } else if (errorNode.has("error")) {
                    return "Error: " + errorNode.get("error").asText();
                }
            } catch (Exception e) {
                // If can't parse as JSON, return raw response with Error prefix
                return "Error: " + response.toString();
            }
        }

        return response.toString();
    }

    /**
     * Sends an ISO8583 message to the canonical endpoint for validation
     * @param isoMessage The ISO8583 message to convert to canonical form
     * @return The canonical JSON response
     */
    public static String sendIsoMessageToCanonical(String isoMessage) throws IOException {
        String CANONICAL_URL = "http://localhost:8080/iso8583/mapToCanonicalObject"; // Replace with actual URL
        
        URL url = new URL(CANONICAL_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/plain");
        connection.setDoOutput(true);

        // Send the request
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = isoMessage.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        // Get response code
        int responseCode = connection.getResponseCode();
        StringBuilder response = new StringBuilder();

        // Use error stream for 400 responses, input stream for successful responses
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(responseCode == 400 
                        ? connection.getErrorStream()
                        : connection.getInputStream(), "utf-8"))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
        }

        // For 400 responses, try to parse the error message
        if (responseCode == 400) {
            try {
                JsonNode errorNode = objectMapper.readTree(response.toString());
                if (errorNode.has("message")) {
                    return "Error: " + errorNode.get("message").asText();
                } else if (errorNode.has("error")) {
                    return "Error: " + errorNode.get("error").asText();
                }
            } catch (Exception e) {
                return "Error: " + response.toString();
            }
        }

        return response.toString();
    }

    public static String getFieldNumberFromJsonPath(String jsonPath) {

            return fieldConfig.entrySet().stream()
                    .filter(entry -> {
                        JsonNode nameNode = entry.getValue().get("name");
                        return nameNode != null && jsonPath.equals(nameNode.asText());
                    })
                    .findFirst()
                    .map(entry -> {
                        System.out.println("Match found - Key: " + entry.getKey() + ", JSONPath: " + jsonPath);
                        return entry.getKey();
                    })
                    .orElse(null);


    }

    private static String bitmapToHex(boolean[] bitmap) {
        StringBuilder binary = new StringBuilder();
        for (boolean bit : bitmap) {
            binary.append(bit ? "1" : "0");
        }

        // Convert binary string to hex
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 64; i += 4) {
            hex.append(Integer.toHexString(Integer.parseInt(binary.substring(i, i + 4), 2)).toUpperCase());
        }

        return hex.toString();
    }

    private static boolean hasActivePrimaryFields() {
        for (int i = 0; i < 64; i++) {
            if (primaryBitmap[i] && isoFields.containsKey(i + 1)) { // Check fields 1-64
                return true;
            }
        }
        return false;
    }

    // Helper method to get cell value as string, regardless of cell type
    private static String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        
        switch (cell.getCellType()) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    // Use DataFormatter to get the formatted string value exactly as it appears in Excel
                    DataFormatter formatter = new DataFormatter();
                    String value = formatter.formatCellValue(cell);
                    
                    // If the value contains 'E' (scientific notation), convert it to plain number
                    if (value.contains("E")) {
                        // Use BigDecimal to handle large numbers without scientific notation
                        double numericValue = cell.getNumericCellValue();
                        value = new BigDecimal(String.valueOf(numericValue)).toPlainString();
                        // Remove decimal point and trailing zeros if present
                        if (value.contains(".")) {
                            value = value.replaceAll("\\.0*$", "");
                        }
                    }
                    return value;
                }
            case STRING:
                return cell.getStringCellValue();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    // For formula cells, get the cached result
                    DataFormatter formatter = new DataFormatter();
                    return formatter.formatCellValue(cell, new HSSFFormulaEvaluator((HSSFWorkbook) cell.getSheet().getWorkbook()));
                } catch (Exception e) {
                    // If formula evaluation fails, get the raw formula
                    return cell.getCellFormula();
                }
            default:
                return "";
        }
    }

    public static void generateIsoFromSpreadsheet(String filePath) throws IOException {
        System.out.println("\n=== Starting ISO message generation and validation from spreadsheet ===");
        System.out.println("File: " + filePath);

        // Load the ISO configuration
        loadConfig("iso_config.json");

        // Open the Excel workbook
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {
            
            Sheet sheet = workbook.getSheetAt(0);
            String sheetName = sheet.getSheetName();
            System.out.println("Found worksheet: " + sheetName);
            
            if (!"Auth STIP Integration".equals(sheetName)) {
                System.out.println("Warning: Expected sheet name 'Auth STIP Integration' but found '" + sheetName + "'");
                System.out.println("Proceeding with processing anyway...");
            }

            // Get Row 1 for Data Element Keys
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IOException("Header row (Row 1) not found in spreadsheet");
            }

            // Process from Row 4 (index 3) onwards
            int totalRows = sheet.getLastRowNum();
            System.out.println("\nProcessing rows 4 to " + (totalRows + 1));

            // Create headers for ISO Message and Validation Results
            Cell isoHeaderCell = headerRow.createCell(88); // Column CE
            isoHeaderCell.setCellValue("Generated ISO Message");
            Cell validationHeaderCell = headerRow.createCell(89); // Column CF
            validationHeaderCell.setCellValue("Validation Results");

            // Process each row starting from row 4
            for (int rowIndex = 5; rowIndex <= totalRows; rowIndex++) {
                Row dataRow = sheet.getRow(rowIndex);
                if (dataRow == null) {
                    System.out.println("\nSkipping empty row " + (rowIndex + 1));
                    continue;
                }

                System.out.println("\n=== Processing Row " + (rowIndex + 1) + " ===");

                // Clear previous field data for new row
                isoFields.clear();
                manuallyUpdatedFields.clear();
                Arrays.fill(primaryBitmap, false);
                Arrays.fill(secondaryBitmap, false);

                int processedFields = 0;
                // Start from Column B (index 1) and go to Column CJ (index 87)
                for (int colNum = 1; colNum <= 87; colNum++) {
                    // Get the Data Element Key from Row 1
                    Cell headerCell = headerRow.getCell(colNum);
                    String dataElementKey = getCellValueAsString(headerCell).trim();
                    if (dataElementKey.isEmpty()) {
                        continue;
                    }

                    // Get the data from current row
                    Cell dataCell = dataRow.getCell(colNum);
                    String sampleData = getCellValueAsString(dataCell).trim();
                    if (sampleData.isEmpty()) {
                        continue;
                    }

                    System.out.println("\nProcessing Column " + getColumnName(colNum) + ":");
                    System.out.println("  Data Element Key: " + dataElementKey);
                    System.out.println("  Sample Data: " + sampleData);

                    // Determine the data type from the configuration
                    String dataType = "String"; // Default type
                    JsonNode config = fieldConfig.get(dataElementKey);
                    if (config != null && config.has("type")) {
                        dataType = config.get("type").asText();
                    }
                    System.out.println("  Data Type: " + dataType);

                    try {
                        // Get the field name from configuration
                        String fieldName = "";
                        if (config != null && config.has("name")) {
                            fieldName = config.get("name").asText();
                        } else {
                            System.out.println("  Warning: No field name found in configuration for key " + dataElementKey);
                            fieldName = "Field_" + dataElementKey; // Fallback
                        }

                        // Apply the field update using the same logic as i_create_iso_message
                        applyBddUpdate(fieldName, sampleData, dataType);
                        processedFields++;
                        System.out.println("  Status: Processed successfully");
                    } catch (Exception e) {
                        System.out.println("  Status: Failed to process - " + e.getMessage());
                    }
                }

                if (processedFields > 0) {
                    System.out.println("\n=== Row " + (rowIndex + 1) + " Processing Summary ===");
                    System.out.println("Total fields processed: " + processedFields);

                    // Generate default fields and build ISO message
                    generateDefaultFields();
                    String isoMessage = buildIsoMessage();
                    System.out.println("\nGenerated ISO Message for Row " + (rowIndex + 1) + ":");
                    System.out.println(isoMessage);

                    // Write the ISO message to the spreadsheet
                    Cell messageCell = dataRow.createCell(88); // Column CK
                    messageCell.setCellValue(isoMessage);

                    try {
                        // Validate against canonical form
                        ValidationResult validationResult = validateIsoMessageCanonical(isoMessage, dataRow);
                        validationResult.printResults();

                        // Write validation results to the spreadsheet
                        Cell validationCell = dataRow.createCell(89); // Column CF
                        long passCount = validationResult.getResults().values().stream()
                            .filter(r -> r.getStatus() == FieldStatus.PASSED).count();
                        long failCount = validationResult.getResults().values().stream()
                            .filter(r -> r.getStatus() == FieldStatus.FAILED).count();
                        long skipCount = validationResult.getResults().values().stream()
                            .filter(r -> r.getStatus() == FieldStatus.SKIPPED).count();

                        // Get failed and skipped DEs
                        String failedDEs = validationResult.getResults().entrySet().stream()
                            .filter(e -> e.getValue().getStatus() == FieldStatus.FAILED)
                            .map(Map.Entry::getKey)
                            .collect(Collectors.joining(", "));
                        String skippedDEs = validationResult.getResults().entrySet().stream()
                            .filter(e -> e.getValue().getStatus() == FieldStatus.SKIPPED)
                            .map(Map.Entry::getKey)
                            .collect(Collectors.joining(", "));

                        String validationSummary = String.format(
                            "Total Fields: %d, Passed: %d, Failed: %d%s, Skipped: %d%s",
                            validationResult.getResults().size(),
                            passCount,
                            failCount,
                            failCount > 0 ? " (DE " + failedDEs + ")" : "",
                            skipCount,
                            skipCount > 0 ? " (DE " + skippedDEs + ")" : ""
                        );
                        validationCell.setCellValue(validationSummary);
                    } catch (Exception e) {
                        System.out.println("\nValidation failed: " + e.getMessage());
                        Cell validationCell = dataRow.createCell(83);
                        validationCell.setCellValue("Validation Error: " + e.getMessage());
                    }
                } else {
                    System.out.println("\nNo fields processed for Row " + (rowIndex + 1) + " - skipping ISO message generation");
                }
            }

            // Save the workbook
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
                System.out.println("\nSuccessfully wrote all ISO messages and validation results to spreadsheet");
            }
        } catch (Exception e) {
            System.err.println("\nError processing spreadsheet: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Failed to process spreadsheet: " + e.getMessage(), e);
        }
    }

    // Helper method to convert column index to column name (e.g., 0=A, 1=B, etc.)
    private static String getColumnName(int colNum) {
        StringBuilder columnName = new StringBuilder();
        while (colNum >= 0) {
            int remainder = colNum % 26;
            columnName.insert(0, (char)('A' + remainder));
            colNum = (colNum / 26) - 1;
        }
        return columnName.toString();
    }

    /**
     * Gets the canonical path(s) for a given DE from the config
     * @param de The data element number
     * @return List of canonical paths for this DE
     */
    private static List<String> getCanonicalPaths(String de) {
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
     * Validates an ISO message against its canonical form
     * @param isoMessage The ISO message to validate
     * @param excelRow The row containing expected values
     * @return ValidationResult containing pass/fail details
     */
    public static ValidationResult validateIsoMessageCanonical(String isoMessage, Row excelRow) throws IOException {
        ValidationResult result = new ValidationResult();
        
        // Get canonical response
        String canonicalResponse = sendIsoMessageToCanonical(isoMessage);
        
        // Parse the canonical response once
        JsonNode canonicalJson = objectMapper.readTree(canonicalResponse);
        
        // Extract values from Excel row
        Map<String, String> deValues = extractDEValuesFromExcel(excelRow);
        
        // Validate each field
        for (Map.Entry<String, String> entry : deValues.entrySet()) {
            String de = entry.getKey();
            String expectedValue = entry.getValue();
            
            // Skip validation for non-canonicalized fields
            if (isNonCanonicalized(de)) {
                result.addSkippedField(de, expectedValue, getSkipReason(de));
                continue;
            }

            // Special handling for DE 43 (Merchant Location)
            if (de.equals("43")) {
                validateMerchantLocation(de, expectedValue, canonicalJson, result);
                continue;
            }

            List<String> canonicalPaths = getCanonicalPaths(de);
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
                        
                        // For special validation cases, pass the entire canonical response
                        if (hasSpecialValidation(de)) {
                            if (validateSpecialCase(de, expectedValue, canonicalResponse, result)) {
                                foundMatch = true;
                                break;
                            }
                        } else if (expectedValue.equals(actualValue)) {
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

    /**
     * Check if a DE requires special validation
     */
    private static boolean hasSpecialValidation(String de) {
        System.out.println("\nChecking special validation for DE " + de);
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("validation")) {
            JsonNode validation = config.get("validation");
            boolean hasType = validation.has("type");
            System.out.println("DE " + de + " has validation type: " + hasType);
            if (hasType) {
                System.out.println("Validation type: " + validation.get("type").asText());
            }
            return hasType;
        }
        System.out.println("DE " + de + " has no validation config");
        return false;
    }

    /**
     * Checks if a DE should not be canonicalized based on config
     */
    private static boolean isNonCanonicalized(String de) {
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("validation")) {
            JsonNode validation = config.get("validation");
            return validation.has("skip") && validation.get("skip").asBoolean();
        }
        return false;
    }

    /**
     * Gets the validation reason for skipped fields
     */
    private static String getSkipReason(String de) {
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("validation")) {
            JsonNode validation = config.get("validation");
            if (validation.has("reason")) {
                return validation.get("reason").asText();
            }
        }
        return "Field is not canonicalized";
    }

    /**
     * Handles special validation cases for specific DEs based on config
     */
    private static boolean validateSpecialCase(String de, String expected, String actual, ValidationResult result) {
        System.out.println("\nValidating special case for DE " + de);
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("validation")) {
            JsonNode validation = config.get("validation");
            if (validation.has("type")) {
                String validationType = validation.get("type").asText();
                System.out.println("Found validation type: " + validationType);
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
                        System.out.println("Processing additional_fees validation for DE " + de);
                        return validateAdditionalFees(de, expected, actual, result, validation.get("rules"));
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
                    result.addPassedField(de, expected, 
                        String.format("%s (Amount: %s, Type: %s)", 
                            actualValue, normalizedActual, actualIndicatorType));
                    return true;
                } else {
                    result.addFailedField(de, expected, 
                        String.format("%s (Amount matches but expected type %s, got %s)", 
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
            result.addFailedField(de, expected,
                "Failed to validate amount: " + e.getMessage());
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
                JsonNode paired = format.get("format").get("pairedField");
                String otherField = paired.get("field").asText();
                String fieldType = paired.get("type").asText();
                
                // Get the other field's value from the ISO message
                String otherValue = isoFields.get(Integer.parseInt(otherField));
                if (otherValue == null) {
                    result.addFailedField(de, expected,
                        String.format("Paired field DE %s not found - both DE %s and DE %s are needed for datetime validation", 
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
            result.addFailedField(de, expected,
                actual + " (Failed to parse datetime: " + e.getMessage() + ")");
            return false;
        }
    }

    /**
     * Validates paired datetime fields (DE 12 + DE 13)
     */
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
            String expectedDateTime = String.format("%d-%s-%sT%s:%s:%s",
                year, month, day, hour, minute, second);
            
            // Compare ignoring timezone
            if (actual.startsWith(expectedDateTime)) {
                // Add success result for both fields
                String successMessage = String.format("%s (Validated with DE %s and DE %s)", 
                    actual, de1, de2);
                result.addPassedField(de1, combinedValue, successMessage);
                result.addPassedField(de2, combinedValue, successMessage);
                return true;
            }
            
            // Add failure result for both fields
            String failureMessage = String.format("%s (Expected format: %s from DE %s and DE %s)", 
                actual, expectedDateTime, de1, de2);
            result.addFailedField(de1, combinedValue, failureMessage);
            result.addFailedField(de2, combinedValue, failureMessage);
            return false;
        } catch (Exception e) {
            String errorMessage = String.format("%s (Failed to parse paired datetime from DE %s and DE %s: %s)", 
                actual, de1, de2, e.getMessage());
            result.addFailedField(de1, combinedValue, errorMessage);
            result.addFailedField(de2, combinedValue, errorMessage);
            return false;
        }
    }

    /**
     * Validates a single datetime field (like DE 7)
     */
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
            String expectedDateTime = String.format("%d-%s-%sT%s:%s:%s",
                year, month, day, hour, minute, second);
            
            // Compare ignoring timezone
            if (actual.startsWith(expectedDateTime)) {
                result.addPassedField(de, expected, actual);
                return true;
            }
            
            result.addFailedField(de, expected + 
                String.format(" (Expected format: %s)", expectedDateTime), actual);
            return false;
        } catch (Exception e) {
            result.addFailedField(de, expected,
                actual + " (Failed to parse datetime: " + e.getMessage() + ")");
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
            result.addFailedField(de, expected,
                "Failed to validate currency: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates DE 43 merchant location field by parsing its sub-elements
     */
    private static boolean validateMerchantLocation(String de, String expected, JsonNode canonicalJson, ValidationResult result) {
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
            String canonicalValue = String.format("%s, %s, %s %s", 
                actualAddress, actualCity, actualState, actualCountry);
            
            if (allMatch) {
                result.addPassedField(de, expected, canonicalValue);
            } else {
                result.addFailedField(de, expected, canonicalValue);
            }
            return allMatch;

        } catch (Exception e) {
            result.addFailedField(de, expected,
                "Failed to validate merchant location: " + e.getMessage());
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
     * Extracts DE values from an Excel row
     */
    private static Map<String, String> extractDEValuesFromExcel(Row row) {
        Map<String, String> deValues = new HashMap<>();
        Row headerRow = row.getSheet().getRow(0);
        
        // Start from Column B (index 1) and go to Column CJ (index 87)
        for (int colNum = 1; colNum <= 87; colNum++) {
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

    /**
     * Gets a value from a JSON node using a dot-notation path
     */
    private static JsonNode getValueFromJsonPath(JsonNode rootNode, String path) {
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

    /**
     * Class to hold validation results
     */
    public static class ValidationResult {
        private final Map<String, FieldResult> results = new HashMap<>();
        
        public void addPassedField(String de, String expected, String actual) {
            results.put(de, new FieldResult(FieldStatus.PASSED, expected, actual));
        }
        
        public void addFailedField(String de, String expected, String actual) {
            results.put(de, new FieldResult(FieldStatus.FAILED, expected, actual));
        }
        
        public void addSkippedField(String de, String expected, String reason) {
            results.put(de, new FieldResult(FieldStatus.SKIPPED, expected, reason));
        }
        
        public Map<String, FieldResult> getResults() {
            return results;
        }

        public void printResults() {
            System.out.println("\n=== Validation Results ===");
            System.out.println(String.format("%-6s | %-15s | %-40s | %-40s | %s", 
                "DE", "Status", "ISO Value", "Canonical Value", "Mapping"));
            System.out.println("-".repeat(120));
            
            // Sort the results by DE number for consistent display
            new TreeMap<>(results).forEach((de, result) -> {
                try {
                    JsonNode config = fieldConfig.get(de);
                    List<String> paths = new ArrayList<>();
                    if (config != null && config.has("canonical")) {
                        JsonNode canonical = config.get("canonical");
                        if (canonical.isArray()) {
                            canonical.forEach(path -> {
                                if (path != null) {
                                    paths.add(path.asText());
                                }
                            });
                        }
                    }
                    
                    // Special handling for DE 43 mapping display
                    String canonicalPath;
                    if (de.equals("43")) {
                        canonicalPath = "transaction.merchant.address.*";
                    } else {
                        canonicalPath = paths.isEmpty() ? "No mapping" : String.join(", ", paths);
                    }

                    // Format ISO value to show original value and any paired values
                    String isoValue = formatIsoValue(de, result);
                    
                    // Format canonical value with any relevant conversion info
                    String canonicalValue = formatCanonicalValue(de, result);
                    
                    System.out.println(String.format("%-6s | %-15s | %-40s | %-40s | %s",
                        de,
                        result.getStatus().toString(),
                        truncateOrPad(isoValue, 40),
                        truncateOrPad(canonicalValue, 40),
                        canonicalPath
                    ));
                } catch (Exception e) {
                    // If there's an error formatting a specific row, print it with error info
                    System.out.println(String.format("%-6s | %-15s | %-40s | %-40s | %s",
                        de,
                        "ERROR",
                        "Error formatting result",
                        e.getMessage(),
                        "Error"
                    ));
                }
            });
            
            // Print summary
            long passCount = results.values().stream()
                .filter(r -> r.getStatus() == FieldStatus.PASSED)
                .count();
            long failCount = results.values().stream()
                .filter(r -> r.getStatus() == FieldStatus.FAILED)
                .count();
            long skipCount = results.values().stream()
                .filter(r -> r.getStatus() == FieldStatus.SKIPPED)
                .count();

            System.out.println("\nSummary:");
            System.out.println("Total Fields: " + results.size());
            System.out.println("Passed: " + passCount);
            System.out.println("Failed: " + failCount);
            System.out.println("Skipped: " + skipCount + 
                (skipCount > 0 ? " (Fields not canonicalized or requiring special handling)" : ""));

            // If there are skipped fields, show them and their reasons
            if (skipCount > 0) {
                System.out.println("\nSkipped Fields:");
                results.entrySet().stream()
                    .filter(e -> e.getValue().getStatus() == FieldStatus.SKIPPED)
                    .forEach(e -> System.out.println(String.format("DE %s: %s", 
                        e.getKey(), e.getValue().getActual())));
            }
        }

        private String formatIsoValue(String de, FieldResult result) {
            if (result == null || result.getExpected() == null) {
                return "";
            }

            JsonNode config = fieldConfig.get(de);
            if (config != null && config.has("validation")) {
                JsonNode validation = config.get("validation");
                
                try {
                    // For paired datetime fields
                    if (validation.has("format") && validation.get("format").has("pairedField")) {
                        JsonNode paired = validation.get("format").get("pairedField");
                        String fieldType = paired.get("type").asText();
                        String value = result.getExpected();
                        
                        // Extract only the relevant portion based on field type
                        if ("date".equals(fieldType)) {
                            // For date field (DE 13), show only MMDD
                            String dateComponent = value.length() >= 4 ? value.substring(0, 4) : value;
                            return String.format("%s (Date component)", dateComponent);
                        } else if ("time".equals(fieldType)) {
                            // For time field (DE 12), show only HHMMSS
                            String timeComponent = value.length() >= 6 ? value.substring(value.length() - 6) : value;
                            return String.format("%s (Time component)", timeComponent);
                        }
                    }
                    
                    // For currency codes
                    if (validation.has("type") && "currency".equals(validation.get("type").asText())) {
                        return String.format("%s (Numeric code)", result.getExpected());
                    }
                } catch (Exception e) {
                    // If any error occurs during formatting, return the original value
                    System.out.println("Warning: Error formatting ISO value for DE " + de + ": " + e.getMessage());
                }
            }
            
            return result.getExpected();
        }

        private String formatCanonicalValue(String de, FieldResult result) {
            if (result == null || result.getActual() == null) {
                return "";
            }

            JsonNode config = fieldConfig.get(de);
            if (config != null && config.has("validation")) {
                JsonNode validation = config.get("validation");
                
                try {
                    // For paired datetime fields
                    if (validation.has("format") && validation.get("format").has("pairedField")) {
                        JsonNode paired = validation.get("format").get("pairedField");
                        String fieldType = paired.get("type").asText();
                        
                        // Show which component this field contributed to the combined datetime
                        if ("date".equals(fieldType)) {
                            return result.getActual() + " (Combined with DE 12 for full datetime)";
                        } else if ("time".equals(fieldType)) {
                            return result.getActual() + " (Combined with DE 13 for full datetime)";
                        }
                    }
                    
                    // For currency codes
                    if (validation.has("type")) {
                        String type = validation.get("type").asText();
                        if ("currency".equals(type)) {
                            return result.getActual() + " (ISO format)";
                        } else if ("amount".equals(type)) {
                            return result.getActual() + " (Decimal format)";
                        }
                    }
                } catch (Exception e) {
                    // If any error occurs during formatting, return the original value
                    System.out.println("Warning: Error formatting canonical value for DE " + de + ": " + e.getMessage());
                }
            }
            
            return result.getActual();
        }

        private String truncateOrPad(String str, int length) {
            if (str == null) {
                return String.format("%-" + length + "s", "");
            }
            if (str.length() > length) {
                return str.substring(0, length - 3) + "...";
            }
            return String.format("%-" + length + "s", str);
        }
    }
    
    /**
     * Enum to represent field validation status
     */
    public enum FieldStatus {
        PASSED("PASS"),
        FAILED("FAIL"),
        SKIPPED("SKIP"),
        PENDING("PENDING");  // Added for fields waiting for their pair

        private final String display;

        FieldStatus(String display) {
            this.display = display;
        }

        @Override
        public String toString() {
            return display;
        }
    }
    
    /**
     * Class to hold individual field validation results
     */
    public static class FieldResult {
        private final FieldStatus status;
        private final String expected;
        private final String actual;
        
        public FieldResult(FieldStatus status, String expected, String actual) {
            this.status = status;
            this.expected = expected;
            this.actual = actual;
        }
        
        public FieldStatus getStatus() { return status; }
        public String getExpected() { return expected; }
        public String getActual() { return actual; }
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
            boolean dateTimeValid = dateTime.equals(actualDateTime);
            validationDetails.append(String.format("DateTime: %s (%s), ", 
                dateTime, dateTimeValid ? "✓" : "✗"));
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
            result.addFailedField(de, expected,
                "Failed to validate original data elements: " + e.getMessage());
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
            
            // Validate Terminal Class (positions 1-3)
            JsonNode terminalClass = positions.get("terminalClass");
            String terminalClassValue = expected.substring(
                terminalClass.get("start").asInt() - 1, 
                terminalClass.get("end").asInt()
            );
            
            // Validate each terminal class component with enum mappings
            JsonNode components = terminalClass.get("components");
            
            // AttendanceIndicator (position 1)
            Map<String, String> attendanceMapping = new HashMap<>();
            attendanceMapping.put("0", "ATTENDED");
            attendanceMapping.put("1", "UNATTENDED");
            attendanceMapping.put("2", "RESERVED");
            validateComponentWithMapping(components.get("attendanceIndicator"), terminalClassValue.substring(0, 1),
                actualJson, "transaction.nationalPOSConditionCode.terminalClass.AttendanceIndicator",
                "Attendance", validationDetails, allValid, attendanceMapping);
            
            // TerminalOperation (position 2)
            Map<String, String> operationMapping = new HashMap<>();
            operationMapping.put("0", "CUSTOMER_OPERATED");
            operationMapping.put("1", "CARD_ACCEPTOR_OPERATED");
            operationMapping.put("2", "ADMINISTRATIVE");
            operationMapping.put("3", "TERMINAL_OPERATION_RESERVED");
            validateComponentWithMapping(components.get("terminalOperation"), terminalClassValue.substring(1, 2),
                actualJson, "transaction.nationalPOSConditionCode.terminalClass.terminalOperation",
                "Operation", validationDetails, allValid, operationMapping);
            
            // TerminalLocation (position 3)
            Map<String, String> locationMapping = new HashMap<>();
            locationMapping.put("0", "ON_PREMISE");
            locationMapping.put("1", "OFF_PREMISE");
            locationMapping.put("2", "TERMINAL_LOCATION_RESERVED");
            validateComponentWithMapping(components.get("terminalLocation"), terminalClassValue.substring(2, 3),
                actualJson, "transaction.nationalPOSConditionCode.terminalClass.terminalLocation",
                "Location", validationDetails, allValid, locationMapping);

            // Validate Presentation Type (positions 4-7)
            JsonNode presentationType = positions.get("presentationType");
            String presentationValue = expected.substring(
                presentationType.get("start").asInt() - 1,
                presentationType.get("end").asInt()
            );
            
            components = presentationType.get("components");
            
            // CardholderPresence (position 4)
            Map<String, String> cardholderPresenceMapping = new HashMap<>();
            cardholderPresenceMapping.put("0", "CUSTOMER_PRESENT");
            cardholderPresenceMapping.put("1", "CUSTOMER_NOT_PRESENT");
            cardholderPresenceMapping.put("2", "MAIL_OR_FACSIMILE_ORDER");
            cardholderPresenceMapping.put("3", "TELEPHONE_ORDER");
            cardholderPresenceMapping.put("4", "STANDING_ORDER_OR_RECURRING_PAYMENT");
            cardholderPresenceMapping.put("5", "CARD_HOLDER_PRESENCE_RESERVED");
            cardholderPresenceMapping.put("6", "CARD_HOLDER_PRESENCE_PRE_AUTHORIZED_PURCHASE");
            cardholderPresenceMapping.put("7", "DEFERRED_BILLING");
            cardholderPresenceMapping.put("8", "DEFERRED_AUTHORIZATION");
            cardholderPresenceMapping.put("9", "INSTALLMENT_PAYMENT");
            validateComponentWithMapping(components.get("cardHolderPresence"), presentationValue.substring(0, 1),
                actualJson, "transaction.nationalPOSConditionCode.presentationType.cardHolderPresence",
                "CardholderPresence", validationDetails, allValid, cardholderPresenceMapping);
            
            // CardPresence (position 5)
            Map<String, String> cardPresenceMapping = new HashMap<>();
            cardPresenceMapping.put("0", "CARD_PRESENT");
            cardPresenceMapping.put("1", "CARD_NOT_PRESENT");
            cardPresenceMapping.put("2", "CARD_PRESENCE_RESERVED");
            cardPresenceMapping.put("3", "PRE_AUTHORIZED_PURCHASE");
            validateComponentWithMapping(components.get("cardPresence"), presentationValue.substring(1, 2),
                actualJson, "transaction.nationalPOSConditionCode.presentationType.cardPresence",
                "CardPresence", validationDetails, allValid, cardPresenceMapping);
            
            // CardRetentionCapability (position 6)
            Map<String, String> retentionMapping = new HashMap<>();
            retentionMapping.put("0", "NO_CARD_RETENTION");
            retentionMapping.put("1", "HAS_CARD_RETENTION");
            retentionMapping.put("2", "CARD_RETENTION_CAPABILITY_RESERVED");
            validateComponentWithMapping(components.get("cardRetentionCapability"), presentationValue.substring(2, 3),
                actualJson, "transaction.nationalPOSConditionCode.presentationType.cardRetentionCapability",
                "RetentionCapability", validationDetails, allValid, retentionMapping);
            
            // TransactionStatus (position 7)
            Map<String, String> transactionStatusMapping = new HashMap<>();
            transactionStatusMapping.put("0", "ORIGINAL_PRESENTMENT");
            transactionStatusMapping.put("1", "FIRST_REPRESENTMENT");
            transactionStatusMapping.put("2", "SECOND_REPRESENTMENT");
            transactionStatusMapping.put("3", "THIRD_REPRESENTMENT");
            transactionStatusMapping.put("4", "PREVIOUSLY_AUTHORIZED_REQUEST");
            transactionStatusMapping.put("5", "RESUBMISSION");
            transactionStatusMapping.put("6", "TRANSACTION_STATUS_RESERVED");
            transactionStatusMapping.put("7", "ACCOUNT_INQUIRY");
            validateComponentWithMapping(components.get("transactionStatus"), presentationValue.substring(3, 4),
                actualJson, "transaction.nationalPOSConditionCode.presentationType.transactionStatus",
                "TransactionStatus", validationDetails, allValid, transactionStatusMapping);

            // Validate Security Condition (position 8)
            JsonNode securityCondition = positions.get("securityCondition");
            String securityValue = expected.substring(
                securityCondition.get("position").asInt() - 1,
                securityCondition.get("position").asInt()
            );
            Map<String, String> securityMapping = new HashMap<>();
            securityMapping.put("0", "SECURITY_CONDITION_UNKNOWN");
            securityMapping.put("1", "NO_SECURITY_CONCERN");
            securityMapping.put("2", "SUSPECTED_FRAUD");
            securityMapping.put("3", "IDENTIFICATION_VERIFIED");
            securityMapping.put("4", "DIGITAL_SIGNATURE_TRANSACTION");
            securityMapping.put("5", "NON_SECURE_UNKNOWN_TRANSACTION");
            securityMapping.put("6", "SECURE_TRANSACTION_WITH_CARDHOLDER_CERT");
            securityMapping.put("7", "SECURE_TRANSACTION_WITHOUT_CARDHOLDER_CERT");
            securityMapping.put("8", "CHANNEL_ENCRYPTED_ECOMMERCE");
            securityMapping.put("9", "CVC_CVV_VALIDATED_VALID");
            securityMapping.put("10", "CVC_CVV_VALIDATED_INVALID");
            securityMapping.put("11", "INTERNET_PINNED_DEBIT_TRANSACTION");
            securityMapping.put("12", "SECURE_REMOTE_COMMERCE_SRC");
            validateComponentWithMapping(securityCondition, securityValue,
                actualJson, "transaction.nationalPOSConditionCode.SecurityCondition",
                "Security", validationDetails, allValid, securityMapping);

            // Validate Terminal Type (positions 9-10)
            JsonNode terminalType = positions.get("terminalType");
            String terminalTypeValue = expected.substring(
                terminalType.get("start").asInt() - 1,
                terminalType.get("end").asInt()
            );
            Map<String, String> terminalTypeMapping = new HashMap<>();
            terminalTypeMapping.put("00", "TERMINAL_TYPE_UNKNOWN");
            terminalTypeMapping.put("01", "ADMINISTRATIVE_TERMINAL");
            terminalTypeMapping.put("02", "POS_TERMINAL");
            terminalTypeMapping.put("03", "ATM");
            terminalTypeMapping.put("04", "HOME_TERMINAL");
            terminalTypeMapping.put("05", "ELECTRONIC_CASH_REGISTER");
            terminalTypeMapping.put("06", "DIAL_UP_TELEPHONE_TERMINAL");
            terminalTypeMapping.put("07", "TRAVELERS_CHECK_MACHINE");
            terminalTypeMapping.put("08", "AUTOMATED_FUEL_DEVICE");
            terminalTypeMapping.put("09", "SCRIP_MACHINE");
            terminalTypeMapping.put("10", "COUPON_MACHINE");
            terminalTypeMapping.put("11", "TICKET_MACHINE");
            terminalTypeMapping.put("12", "POINT_OF_BANKING_TERMINAL");
            terminalTypeMapping.put("13", "TELLER");
            terminalTypeMapping.put("14", "FRANCHISE_TELLER");
            terminalTypeMapping.put("15", "PERSONAL_BANKING");
            terminalTypeMapping.put("16", "PUBLIC_UTILITY");
            terminalTypeMapping.put("17", "VENDING");
            terminalTypeMapping.put("18", "SELF_SERVICE");
            terminalTypeMapping.put("19", "AUTHORIZATION");
            terminalTypeMapping.put("20", "PAYMENT");
            terminalTypeMapping.put("21", "VRU");
            terminalTypeMapping.put("22", "SMARTPHONE_POS_DEVICE");
            terminalTypeMapping.put("23", "INTERACTIVE_TELEVISION");
            terminalTypeMapping.put("24", "PDA");
            terminalTypeMapping.put("25", "SCREEN_PHONE");
            terminalTypeMapping.put("26", "ELECTRONIC_COMMERCE");
            terminalTypeMapping.put("27", "MICR_TERMINALS_POS");
            validateComponentWithMapping(terminalType, terminalTypeValue,
                actualJson, "transaction.nationalPOSConditionCode.terminalType",
                "TerminalType", validationDetails, allValid, terminalTypeMapping);

            // Validate Card Data Input Capability (position 11)
            JsonNode cardDataInput = positions.get("cardDataInputCapability");
            String cardDataValue = expected.substring(
                cardDataInput.get("position").asInt() - 1,
                cardDataInput.get("position").asInt()
            );
            Map<String, String> cardDataMapping = new HashMap<>();
            cardDataMapping.put("0", "CARD_DATA_INPUT_CAPABILITY_UNKNOWN");
            cardDataMapping.put("1", "MANUAL_NO_TERMINAL");
            cardDataMapping.put("2", "MAGNETIC_STRIPE");
            cardDataMapping.put("3", "BARCODE_QRCODE");
            cardDataMapping.put("4", "OCR");
            cardDataMapping.put("5", "ICC_CHIP");
            cardDataMapping.put("6", "KEY_ENTRY");
            cardDataMapping.put("7", "FILE");
            cardDataMapping.put("8", "CONTACTLESS_MAG_STRIPE_KEY_ENTRY");
            cardDataMapping.put("9", "CONTACTLESS_CHIP_MAG_STRIPE_ICC_KEY_ENTRY");
            cardDataMapping.put("10", "MAG_STRIPE_KEY_ENTRY");
            cardDataMapping.put("11", "MAG_STRIPE_KEY_ENTRY_EMV_ICC");
            cardDataMapping.put("12", "MAG_STRIPE_EMV_ICC");
            cardDataMapping.put("13", "SECURE_CARD_LESS_ENTRY");
            validateComponentWithMapping(cardDataInput, cardDataValue,
                actualJson, "transaction.nationalPOSConditionCode.cardDataInputCapability",
                "InputCapability", validationDetails, allValid, cardDataMapping);

            // Add validation result with detailed breakdown
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

}