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

import static utilities.CustomTestData.generateCustomValue;
import static utilities.CustomTestData.generateRandomText;

public class CreateIsoMessage {
    private final IsoMessageConfig config;
    private final IsoMessageBuilder builder;
    private final IsoMessageValidator validator;
    private final IsoCanonicalService canonicalService;

    public CreateIsoMessage() {
        this.config = new IsoMessageConfig();
        this.canonicalService = new IsoCanonicalService("http://localhost:8080/iso8583/mapToCanonicalObject");
        this.builder = new IsoMessageBuilder(config);
        this.validator = new IsoMessageValidator(config, canonicalService);
    }

    public void i_create_iso_message(String requestName, DataTable dt) throws IOException {
        config.loadConfig("iso_config.json");

        List<Map<String, String>> rows = dt.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            String jsonPath = row.get("JSONPATH");
            String value = row.get("Value");
            String dataType = row.get("DataType");

            applyBddUpdate(jsonPath, value, dataType);
        }

        // Generate default fields
        generateDefaultFields();

        // Build ISO message & JSON output
        String isoMessage = builder.buildMessage();
        
        // Print Outputs
        System.out.println("Generated ISO8583 Message:");
        System.out.println(isoMessage);
    }

    public void generateIsoFromSpreadsheet(String filePath) throws IOException {
        System.out.println("\n=== Starting ISO message generation and validation from spreadsheet ===");
        System.out.println("File: " + filePath);

        // Load the ISO configuration
        config.loadConfig("iso_config.json");

        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {
            
            Sheet sheet = workbook.getSheetAt(0);
            processSheet(sheet, filePath);
        } catch (Exception e) {
            System.err.println("\nError processing spreadsheet: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Failed to process spreadsheet: " + e.getMessage(), e);
        }
    }

    private void processSheet(Sheet sheet, String filePath) throws IOException {
        String sheetName = sheet.getSheetName();
        System.out.println("Found worksheet: " + sheetName);
        
        if (!"Auth STIP Integration".equals(sheetName)) {
            System.out.println("Warning: Expected sheet name 'Auth STIP Integration' but found '" + sheetName + "'");
            System.out.println("Proceeding with processing anyway...");
        }

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new IOException("Header row (Row 1) not found in spreadsheet");
        }

        // Create headers
        createHeaders(headerRow);

        // Process rows
        int totalRows = sheet.getLastRowNum();
        System.out.println("\nProcessing rows 4 to " + (totalRows + 1));

        for (int rowIndex = 3; rowIndex <= totalRows; rowIndex++) {
            processRow(sheet.getRow(rowIndex), rowIndex + 1);
        }

        // Save the workbook
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            sheet.getWorkbook().write(fos);
            System.out.println("\nSuccessfully wrote all ISO messages and validation results to spreadsheet");
        }
    }

    private void createHeaders(Row headerRow) {
        Cell isoHeaderCell = headerRow.createCell(82); // Column CE
        isoHeaderCell.setCellValue("Generated ISO Message");
        Cell validationHeaderCell = headerRow.createCell(83); // Column CF
        validationHeaderCell.setCellValue("Validation Results");
    }

    private void processRow(Row dataRow, int rowNum) throws IOException {
        if (dataRow == null) {
            System.out.println("\nSkipping empty row " + rowNum);
            return;
        }

        System.out.println("\n=== Processing Row " + rowNum + " ===");
        builder.clear();

        // Process fields
        processFields(dataRow);

        // Generate ISO message
        String isoMessage = builder.buildMessage();
        System.out.println("\nGenerated ISO Message for Row " + rowNum + ":");
        System.out.println(isoMessage);

        // Write results to spreadsheet
        writeResults(dataRow, isoMessage);
    }

    private void processFields(Row dataRow) {
        Row headerRow = dataRow.getSheet().getRow(0);
        
        for (int colNum = 1; colNum <= 81; colNum++) {
            Cell headerCell = headerRow.getCell(colNum);
            Cell dataCell = dataRow.getCell(colNum);
            
            if (headerCell != null && dataCell != null) {
                String de = getCellValueAsString(headerCell).trim();
                String value = getCellValueAsString(dataCell).trim();
                
                if (!de.isEmpty() && !value.isEmpty()) {
                    builder.addField(de, value);
                }
            }
        }
    }

    private void writeResults(Row dataRow, String isoMessage) {
        // Write ISO message
        Cell messageCell = dataRow.createCell(82);
        messageCell.setCellValue(isoMessage);

        try {
            // Validate and write results
            ValidationResult validationResult = validator.validateMessage(isoMessage, dataRow);
            validationResult.printResults();

            Cell validationCell = dataRow.createCell(83);
            String validationSummary = String.format(
                "Passed: %d, Failed: %d", 
                validationResult.getResults().values().stream().filter(ValidationResult.FieldResult::isPassed).count(),
                validationResult.getResults().values().stream().filter(r -> !r.isPassed()).count()
            );
            validationCell.setCellValue(validationSummary);
        } catch (Exception e) {
            System.out.println("\nValidation failed: " + e.getMessage());
            Cell validationCell = dataRow.createCell(83);
            validationCell.setCellValue("Validation Error: " + e.getMessage());
        }
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
        JsonNode canonicalJson = objectMapper.readTree(canonicalResponse);
        
        // Extract values from Excel row
        Map<String, String> deValues = extractDEValuesFromExcel(excelRow);
        
        // Validate each field
        for (Map.Entry<String, String> entry : deValues.entrySet()) {
            String de = entry.getKey();
            String expectedValue = entry.getValue();
            
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
                        if (expectedValue.equals(actualValue)) {
                            result.addPassedField(de, expectedValue, actualValue);
                            foundMatch = true;
                            break;
                        }
                    }
                }
                
                if (!foundMatch) {
                    // If we tried all paths and none matched, record as a failure
                    result.addFailedField(de, expectedValue, "No matching value found in canonical paths: " + String.join(", ", canonicalPaths));
                }
            } else {
                result.addFailedField(de, expectedValue, "No canonical mapping found for DE " + de);
            }
        }
        
        return result;
    }

    /**
     * Extracts DE values from an Excel row
     */
    private static Map<String, String> extractDEValuesFromExcel(Row row) {
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
            results.put(de, new FieldResult(true, expected, actual));
        }
        
        public void addFailedField(String de, String expected, String actual) {
            results.put(de, new FieldResult(false, expected, actual));
        }
        
        public Map<String, FieldResult> getResults() {
            return results;
        }
        
        public boolean isAllPassed() {
            return results.values().stream().allMatch(FieldResult::isPassed);
        }
        
        public void printResults() {
            System.out.println("\n=== Validation Results ===");
            System.out.println(String.format("%-6s | %-15s | %-30s | %-30s | %s", 
                "DE", "Status", "Expected Value", "Actual Value", "Canonical Path"));
            System.out.println("-".repeat(100));
            
            results.forEach((de, result) -> {
                JsonNode config = fieldConfig.get(de);
                List<String> paths = new ArrayList<>();
                if (config != null && config.has("canonical")) {
                    JsonNode canonical = config.get("canonical");
                    if (canonical.isArray()) {
                        canonical.forEach(path -> paths.add(path.asText()));
                    }
                }
                String canonicalPath = paths.isEmpty() ? "No mapping" : String.join(", ", paths);
                
                System.out.println(String.format("%-6s | %-15s | %-30s | %-30s | %s",
                    de,
                    result.isPassed() ? "PASS" : "FAIL",
                    truncateOrPad(result.getExpected(), 30),
                    truncateOrPad(result.getActual(), 30),
                    canonicalPath
                ));
            });
            
            // Print summary
            long passCount = results.values().stream().filter(FieldResult::isPassed).count();
            long failCount = results.size() - passCount;
            System.out.println("\nSummary:");
            System.out.println("Total Fields: " + results.size());
            System.out.println("Passed: " + passCount);
            System.out.println("Failed: " + failCount);
        }
        
        private String truncateOrPad(String str, int length) {
            if (str == null) {
                return String.format("%-" + length + "s", "null");
            }
            if (str.length() > length) {
                return str.substring(0, length - 3) + "...";
            }
            return String.format("%-" + length + "s", str);
        }
    }
    
    /**
     * Class to hold individual field validation results
     */
    public static class FieldResult {
        private final boolean passed;
        private final String expected;
        private final String actual;
        
        public FieldResult(boolean passed, String expected, String actual) {
            this.passed = passed;
            this.expected = expected;
            this.actual = actual;
        }
        
        public boolean isPassed() { return passed; }
        public String getExpected() { return expected; }
        public String getActual() { return actual; }
    }
}