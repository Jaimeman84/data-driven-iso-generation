package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import websocket.WebSocketClient;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.stream.Collectors;

import static utilities.CustomTestData.generateCustomValue;
import static utilities.CustomTestData.generateRandomText;
import static websocket.WebSocketClient.responseMessage;

public class CreateIsoMessage  {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static Map<String, JsonNode> fieldConfig;
    private static Map<Integer, String> isoFields = new TreeMap<>();
    private static boolean[] primaryBitmap = new boolean[64];
    private static boolean[] secondaryBitmap = new boolean[64];
    private static Set<String> manuallyUpdatedFields = new HashSet<>(); // Tracks modified fields
    private static final String DEFAULT_MTI = "0100"; // Default MTI value
    private static final String PARSER_URL = "enter url here"; // Replace with actual URL
    private static final String CANONICAL_URL = "enter url here"; // Replace with actual URL
    private static final String WS_URL = "ws://localhost:8080/ws"; // Replace with actual URL

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

        if (System.getProperty("os.name").startsWith("Windows")) {
            if (filename.contains("/")) {
                filename = filename.split("/")[0] + "\\" + filename.split("/")[1];
            }
            pathName = Path.of(filepath + "\\src\\test\\resources\\" + filename);
        } else {
            if (filename.contains("/")) {
                filename = filename.split("/")[0] + "/" + filename.split("/")[1];
            }
            pathName = Path.of(filepath + "/src/test/resources/" + filename);
        }

        String s = Files.readString(pathName);
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
            isoFields.put(0, DEFAULT_MTI);
        }

        for (String field : fieldConfig.keySet()) {
            JsonNode config = fieldConfig.get(field);
            boolean active = config.get("active").asBoolean();

            if (active && !manuallyUpdatedFields.contains(field)) {
                if (!field.contains("MTI")) {
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
            System.out.println("Warning: Value- " + value + " for field " + fieldNumber + " exceeds max length " + maxLength + " (Truncated)");
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
        message.append(isoFields.getOrDefault(0, DEFAULT_MTI));

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
            if (secondaryBitmap[i] && isoFields.containsKey(i + 65)) { // Check fields 65-128
                return true; // Secondary bitmap is required
            }
        }
        return false; // No active fields in DE 65-128
    }

    public static String buildJsonMessage() throws IOException {
        Map<String, Object> outputJson = new HashMap<>();

        // Ensure MTI is correctly stored and printed
        if (!isoFields.containsKey(0) && !manuallyUpdatedFields.contains("MTI")) {
            outputJson.put("MTI", isoFields.getOrDefault(0, DEFAULT_MTI));
        } else {
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

    public static String getFieldNumberFromJsonPath(String jsonPath) {
        return fieldConfig.entrySet().stream()
            .filter(entry -> {
                JsonNode nameNode = entry.getValue().get("name");
                return nameNode != null && jsonPath.equals(nameNode.asText());
            })
            .findFirst()
            .map(Map.Entry::getKey)
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
                if (cell.getColumnIndex() == 10) { // DE 11 System Trace Audit Number
                    return String.format("%06d", (int) cell.getNumericCellValue());
                }
                if (cell.getColumnIndex() == 35) { // DE 37 Retrieval Reference Number
                    return cell.getStringCellValue();
                }
                if (cell.getCachedFormulaResultType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                    Date date = cell.getDateCellValue();
                    SimpleDateFormat sdf;
                    if (cell.getColumnIndex() == 6) { // DE 7 Transaction Date/Time
                        sdf = new SimpleDateFormat("MMddHHmmss");
                    } else {
                        sdf = new SimpleDateFormat("MMdd");
                    }
                    return sdf.format(date);
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

            Sheet sheet = workbook.getSheetAt(4);
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

            // Process from Row 6 (index 5) onwards
            int totalRows = sheet.getLastRowNum();
            System.out.println("\nProcessing rows 6 to " + (totalRows + 1));

            // Create headers for ISO Message and Validation Results
            Cell isoHeaderCell = headerRow.createCell(89); // Column CL
            isoHeaderCell.setCellValue("Generated ISO Message");
            Cell validationHeaderCell = headerRow.createCell(90); // Column CM
            validationHeaderCell.setCellValue("Validation Results");
            Cell de39HeaderCell = headerRow.createCell(91); // Column CN
            de39HeaderCell.setCellValue("DE39 Response Code");

            // Process each row starting from row 6
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

                // Start from Column B (index 1) and go to Column CK (index 88)
                for (int colNum = 1; colNum <= 88; colNum++) {
                    // Get the Data Element Key from Row 1
                    Cell headerCell = headerRow.getCell(colNum);
                    String dataElementKey = getCellValueAsString(headerCell).trim();
                    if (dataElementKey.isEmpty()) {
                        continue;
                    }

                    // Get the data from current row
                    Cell dataCell = dataRow.getCell(colNum);
                    String cellValue;

                    // Special handling for DE 60
                    if (colNum == 60) {
                        cellValue = getCellValueAsString(dataCell);
                    } else {
                        cellValue = getCellValueAsString(dataCell).trim();
                    }

                    if (cellValue.isEmpty()) {
                        continue;
                    }

                    // Determine the data type from the configuration
                    String dataType = "String"; // Default type
                    JsonNode config = fieldConfig.get(dataElementKey);
                    if (config != null && config.has("type")) {
                        dataType = config.get("type").asText();
                    }

                    try {
                        // Get the field name from configuration
                        String fieldName;
                        if (config != null && config.has("name")) {
                            fieldName = config.get("name").asText();
                        } else {
                            System.out.println(" Warning: No field name found in configuration for key " + dataElementKey);
                            fieldName = "Field_" + dataElementKey; // Fallback
                        }

                        // Apply the field update using the same logic as i_create_iso_message
                        applyBddUpdate(fieldName, cellValue, dataType);
                        processedFields++;
                    } catch (Exception e) {
                        System.out.println(" Status: Failed to process - " + e.getMessage());
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
                    Cell messageCell = dataRow.createCell(89); // Column CL
                    messageCell.setCellValue(isoMessage);

                    try {
                        WebSocketManager.init(WS_URL);
                        WebSocketManager.sendMessage(isoMessage);
                        String wsResponse = WebSocketClient.getResponseMessage();
                        WebSocketManager.close();

                        // Parse response to get DE39
                        String parsedResponse = sendIsoMessageToParser(wsResponse);
                        JsonNode responseArray = objectMapper.readTree(parsedResponse);

                        // Extract DE39 (Response Code) from array
                        String responseCode = null;
                        if (responseArray.isArray()) {
                            // Iterate through array indices
                            for (int i = 0; i < responseArray.size(); i++) {
                                JsonNode element = responseArray.get(i);
                                String elementId = element.get("dataElementId").asText();
                                if ("39".equals(elementId)) {
                                    responseCode = element.get("value").asText();
                                    System.out.println("Found DE39 with value: " + responseCode);
                                    break;
                                }
                            }
                        }

                        // Write response code to column CN
                        Cell responseCell = dataRow.createCell(91); // Column CN
                        if (responseCode != null) {
                            JsonNode de39Config = fieldConfig.get("39");
                            if (de39Config != null && de39Config.has("validation")) {
                                JsonNode mapping = de39Config.get("validation").get("rules").get("mapping").get(responseCode);
                                if (mapping != null) {
                                    String description = mapping.get("description").asText();
                                    String domain = mapping.get("domain").asText();
                                    responseCell.setCellValue(String.format("%s - %s (%s)", responseCode, description, domain));
                                } else {
                                    responseCell.setCellValue(responseCode + " - Unknown response code");
                                }
                            } else {
                                responseCell.setCellValue(responseCode);
                            }
                        } else {
                            responseCell.setCellValue("No DE39 found in response");
                        }
                    } catch (Exception e) {
                        System.out.println("\nWebSocket/Parser Error: " + e.getMessage());
                        e.printStackTrace(); // Print full stack trace for debugging
                        Cell responseCell = dataRow.createCell(92); // Column CN
                        responseCell.setCellValue("Error: " + e.getMessage());
                    }

                    try {
                        // Validate against canonical form
                        ValidationResult validationResult = validateIsoMessageCanonical(isoMessage, dataRow);
                        validationResult.printResults();

                        // Export validation results to Excel
                        exportValidationResultsToExcel(workbook, validationResult, rowIndex);

                        // Write validation results to the spreadsheet
                        Cell validationCell = dataRow.createCell(90); // Column CM
                        long passCount = validationResult.getResults().values().stream()
                            .filter(r -> r.getStatus() == FieldStatus.PASSED)
                            .count();
                        long failCount = validationResult.getResults().values().stream()
                            .filter(r -> r.getStatus() == FieldStatus.FAILED)
                            .count();
                        long skipCount = validationResult.getResults().values().stream()
                            .filter(r -> r.getStatus() == FieldStatus.SKIPPED)
                            .count();

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
                        Cell validationCell = dataRow.createCell(90); // Column CM
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

            // Special handling for DE 95 (Replacement Amounts) when MTI is not 0420
            if (de.equals("95") && !isoFields.getOrDefault(0, "").equals("0420")) {
                result.addSkippedField(de, expectedValue, "DE 95 is not applicable for MTI " + isoFields.get(0));
                continue;
            }

            // Special handling for DE 60 (Advice/Reversal Reason Code)
            if (de.equals("60")) {
                String typeIndicator = expectedValue.substring(0, 2);
                List<String> canonicalPaths = getCanonicalPaths(de);
                
                // For reversal (80), only keep the reversalReason path
                if ("80".equals(typeIndicator)) {
                    canonicalPaths.removeIf(path -> path.contains("adviceReason"));
                }
                // For advice (40), only keep the adviceReason path
                else if ("40".equals(typeIndicator)) {
                    canonicalPaths.removeIf(path -> path.contains("reversalReason"));
                }
                
                // Update the config with filtered paths
                JsonNode config = fieldConfig.get(de);
                ((ObjectNode) config).put("canonical", objectMapper.valueToTree(canonicalPaths));
            }

            List<String> canonicalPaths = getCanonicalPaths(de);
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

                        // For special validation cases, pass the entire canonical response
                        if (hasSpecialValidation(de)) {
                            allPathsValid &= validateSpecialCase(de, expectedValue, canonicalResponse, result);
                        } else {
                            if (!expectedValue.equals(actualValue)) {
                                allPathsValid = false;
                                // Get just the final element name
                                String elementName = jsonPath.substring(jsonPath.lastIndexOf(".") + 1);
                                if (elementName.contains("[")) {
                                    elementName = elementName.substring(0, elementName.indexOf("["));
                                }
                                validationDetails.append(elementName)
                                               .append(" mismatch; ");
                            }
                        }
                    } else {
                        allPathsValid = false;
                        // Get just the final element name
                        String elementName = jsonPath.substring(jsonPath.lastIndexOf(".") + 1);
                        if (elementName.contains("[")) {
                            elementName = elementName.substring(0, elementName.indexOf("["));
                        }
                        validationDetails.append(elementName)
                                       .append(" missing; ");
                    }
                }

                if (allPathsValid) {
                    if (canonicalPaths.size() > 1) {
                        // Only for multiple paths, show the success message
                        result.addPassedField(de, expectedValue, "All paths validated successfully");
                    } else {
                        // For single path, just show the actual value
                        JsonNode actualNode = getValueFromJsonPath(canonicalJson, canonicalPaths.get(0).trim());
                        String actualValue = actualNode != null ? actualNode.asText() : "";
                        // Let the formatCanonicalValue method handle the formatting
                        result.addPassedField(de, expectedValue, actualValue);
                    }
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
     * Check if a DE requires special validation
     */
    private static boolean hasSpecialValidation(String de) {
        JsonNode config = fieldConfig.get(de);
        if (config != null && config.has("validation")) {
            JsonNode validation = config.get("validation");
            return validation.has("type");
        }
        return false;
    }

    /**
     * Checks if a DE should not be canonicalized based on config
     */
    private static boolean isNonCanonicalized(String de) {
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
                    case "additional_amounts":
                        System.out.println("Processing additional_amounts validation for DE " + de);
                        return validateAdditionalAmounts(de, expected, actual, result, validation.get("rules"));
                    case "national_pos_geographic_data":
                        System.out.println("Processing national_pos_geographic_data validation for DE " + de);
                        return validateNationalPosGeographicData(de, expected, actual, result, validation.get("rules"));
                    case "network_data":
                        System.out.println("Processing network_data validation for DE " + de);
                        return validateNetworkData(de, expected, actual, result, validation.get("rules"));
                    case "avs_data":
                        System.out.println("Processing AVS data validation for DE " + de);
                        return validateAvsData(de, expected, actual, result, validation.get("rules"));
                    case "acquirer_trace_data":
                        System.out.println("Processing acquirer trace data validation for DE " + de);
                        return validateAcquirerTraceData(de, expected, actual, result, validation.get("rules"));
                    case "issuer_trace_data":
                        System.out.println("Processing issuer trace data validation for DE " + de);
                        return validateIssuerTraceData(de, expected, actual, result, validation.get("rules"));
                    case "incremental_auth_data":
                        System.out.println("Processing incremental authorization data validation for DE " + de);
                        return validateIncrementalAuthData(de, expected, actual, result, validation.get("rules"));
                    case "advice_reversal_code":
                        return validateAdviceReversalCode(de, expected, actual, result, validation.get("rules"));
                    case "replacement_amounts":
                        System.out.println("Processing replacement amounts validation for DE " + de);
                        return validateReplacementAmounts(de, expected, actual, result, validation.get("rules"));
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
                if (currentMti == null || !requiredMti.equals(currentMti)) {
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
            if (!String.valueOf(expectedCount).equals(String.valueOf(actualCount))) {
                details.append("Count mismatch: expected ").append(expectedCount)
                      .append(", got ").append(actualCount).append("; ");
                allValid = false;
            }

            // Validate sequence (SN tag)
            String expectedSequence = expectedValues.get("SN");
            String actualSequence = getJsonValue(actualJson, "transaction.incrementalAuthorization.sequence");
            if (!String.valueOf(expectedSequence).equals(String.valueOf(actualSequence))) {
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
            if (!String.valueOf(expectedAuthType).equals(String.valueOf(actualAuthType))) {
                details.append("Authorization type mismatch: expected ").append(expectedAuthType)
                      .append(", got ").append(actualAuthType);
                allValid = false;
            }

            if (allValid) {
                result.addPassedField(de, String.valueOf(expected), actual);
            } else {
                result.addFailedField(de, String.valueOf(expected), actual + " [" + details.toString() + "]");
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
     * Extracts DE values from an Excel row
     */
    private static Map<String, String> extractDEValuesFromExcel(Row row) {
        Map<String, String> deValues = new HashMap<>();
        Row headerRow = row.getSheet().getRow(0);

        for (int colNum = 1; colNum <= 88; colNum++) {
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
            System.out.printf("%-6s | %-15s | %-40s | %-40s | %s%n", "DE", "Status", "ISO Value", "Canonical Value", "Mapping");
            System.out.println("-".repeat(120));

            // Create a sorted map with custom comparator for numeric DE sorting
            Map<String, FieldResult> sortedResults = new TreeMap<>((de1, de2) -> {
                // Handle MTI specially
                if (de1.equals("MTI")) return -1;
                if (de2.equals("MTI")) return 1;

                // Convert DEs to integers for numeric comparison
                try {
                    int num1 = Integer.parseInt(de1);
                    int num2 = Integer.parseInt(de2);
                    return Integer.compare(num1, num2);
                } catch (NumberFormatException e) {
                    // Fallback to string comparison if parsing fails
                    return de1.compareTo(de2);
                }
            });
            sortedResults.putAll(results);

            // Print the sorted results
            sortedResults.forEach((de, result) -> {
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

                    System.out.printf("%-6s | %-15s | %-40s | %-40s | %s%n",
                        de,
                        result.getStatus().toString(),
                        truncateOrPad(isoValue, 40),
                        truncateOrPad(canonicalValue, 40),
                        canonicalPath
                    );
                } catch (Exception e) {
                    // If there's an error formatting a specific row, print it with error info
                    System.out.printf("%-6s | %-15s | %-40s | %-40s | %s%n",
                        de,
                        "ERROR",
                        "Error formatting result",
                        e.getMessage(),
                        "Error"
                    );
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
            System.out.println("Skipped: " + skipCount + (skipCount > 0 ? " (Fields not canonicalized or requiring special handling)" : ""));

            // If there are skipped fields, show them and their reasons
            if (skipCount > 0) {
                System.out.println("\nSkipped Fields:");
                results.entrySet().stream()
                    .filter(e -> e.getValue().getStatus() == FieldStatus.SKIPPED)
                    .forEach(e -> System.out.printf("DE %s: %s%n", e.getKey(), e.getValue().getActual()));
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
            if (config != null) {
                // For multiple paths that failed, show detailed mismatch info
                if (config.has("canonical")) {
                    JsonNode canonical = config.get("canonical");
                    if (canonical.size() > 1 && result.getStatus() == FieldStatus.FAILED) {
                        return result.getActual(); // Contains the detailed mismatch info
                    }
                }

                // For single path fields or special validations, show detailed formatting
                if (config.has("validation")) {
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

                        // For currency codes and amounts
                        if (validation.has("type")) {
                            String type = validation.get("type").asText();
                            if ("currency".equals(type)) {
                                return result.getActual() + " (ISO format)";
                            } else if ("amount".equals(type)) {
                                return result.getActual() + " (Decimal format)";
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("Warning: Error formatting canonical value for DE " + de + ": " + e.getMessage());
                    }
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
            String expectedIndicator = amountComponent.get("components").get("debitCreditIndicator").get("mapping").get(indicator).asText();
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

    /**
     * Gets the expected response MTI based on the request MTI
     */
    private static String getExpectedResponseMti(String requestMti) {
        // Common MTI response mappings
        switch (requestMti) {
            case "0100": return "0110"; // Authorization Request -> Response
            case "0200": return "0210"; // Financial Request -> Response
            case "0400": return "0410"; // Reversal Request -> Response
            case "0420": return "0430"; // Reversal Advice -> Acknowledgment
            case "0800": return "0810"; // Network Management Request -> Response
            default: return requestMti.substring(0, 2) + "10"; // Generic response
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
                result.addFailedField(de, expected, actual + " [" + details.toString() + "]");
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
                result.addFailedField(de, expected, actual + " [" + details.toString() + "]");
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
                result.addFailedField(de, expected, actual + " [" + details.toString() + "]");
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
                result.addFailedField(de, expected, actual + " [" + details.toString() + "]");
            }

            return true;
        } catch (Exception e) {
            result.addFailedField(de, expected, "Failed to parse issuer trace data: " + e.getMessage());
            return false;
        }
    }

    /**
     * Exports validation results to a new sheet in the Excel workbook
     * @param workbook The Excel workbook to add the sheet to
     * @param results The validation results to export
     * @param rowIndex The current row being processed
     */
    private static void exportValidationResultsToExcel(Workbook workbook, ValidationResult results, int rowIndex) {
        // Get or create the Validation Results sheet
        Sheet validationSheet = workbook.getSheet("Validation Results");
        if (validationSheet == null) {
            validationSheet = workbook.createSheet("Validation Results");
            
            // Create header row
            Row headerRow = validationSheet.createRow(0);
            String[] headers = {"Row #", "DE", "Status", "ISO Value", "Canonical Value", "Mapping", "Details"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            // Set column widths
            validationSheet.setColumnWidth(0, 10 * 256);  // Row #
            validationSheet.setColumnWidth(1, 8 * 256);   // DE
            validationSheet.setColumnWidth(2, 10 * 256);  // Status
            validationSheet.setColumnWidth(3, 40 * 256);  // ISO Value
            validationSheet.setColumnWidth(4, 40 * 256);  // Canonical Value
            validationSheet.setColumnWidth(5, 50 * 256);  // Mapping
            validationSheet.setColumnWidth(6, 60 * 256);  // Details
        }

        // Create a sorted map with custom comparator for numeric DE sorting
        Map<String, FieldResult> sortedResults = new TreeMap<>((de1, de2) -> {
            // Handle MTI specially
            if (de1.equals("MTI")) return -1;
            if (de2.equals("MTI")) return 1;
            
            // Convert DEs to integers for numeric comparison
            try {
                int num1 = Integer.parseInt(de1);
                int num2 = Integer.parseInt(de2);
                return Integer.compare(num1, num2);
            } catch (NumberFormatException e) {
                return de1.compareTo(de2);
            }
        });
        sortedResults.putAll(results.getResults());

        // Add results for each DE
        int currentRow = validationSheet.getLastRowNum() + 1;
        for (Map.Entry<String, FieldResult> entry : sortedResults.entrySet()) {
            String de = entry.getKey();
            FieldResult result = entry.getValue();
            
            Row row = validationSheet.createRow(currentRow++);
            
            // Row number from original sheet
            row.createCell(0).setCellValue("Row " + (rowIndex + 1));
            
            // DE number
            row.createCell(1).setCellValue(de);
            
            // Status
            Cell statusCell = row.createCell(2);
            statusCell.setCellValue(result.getStatus().toString());
            
            // ISO Value
            row.createCell(3).setCellValue(result.getExpected());
            
            // Canonical Value
            String canonicalValue = result.getActual();
            row.createCell(4).setCellValue(canonicalValue);
            
            // Mapping
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
            String mapping = paths.isEmpty() ? "No mapping" : String.join(", ", paths);
            row.createCell(5).setCellValue(mapping);
            
            // Additional Details
            StringBuilder details = new StringBuilder();
            if (result.getStatus() == FieldStatus.FAILED) {
                details.append("Validation failed: ").append(canonicalValue);
            } else if (result.getStatus() == FieldStatus.SKIPPED) {
                details.append("Skipped: ").append(canonicalValue);
            }
            row.createCell(6).setCellValue(details.toString());
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
                String actualValue = getJsonValue(actualJson, "transaction.reversalReasonCode");
                
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
                String actualValue = getJsonValue(actualJson, "transaction.adviceReasonCode");
                
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
                if (currentMti == null || !requiredMti.equals(currentMti)) {
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
}