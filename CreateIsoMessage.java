package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;
import lombok.Getter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import websocket.WebSocketClient;
import websocket.WebSocketManager;

import java.io.*;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static utilities.CustomTestData.generateCustomValue;
import static utilities.CustomTestData.generateRandomText;
import static utilities.DataElementSpecialCaseValidator.*;
import static utilities.ValidationResultManager.*;

public class CreateIsoMessage {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static Map<String, JsonNode> fieldConfig;
    static final Map<Integer, String> isoFields = new TreeMap<>();
    private static final boolean[] primaryBitmap = new boolean[64];
    private static final boolean[] secondaryBitmap = new boolean[64];
    private static final Set<String> manuallyUpdatedFields = new HashSet<>(); // Tracks modified fields
    private static final String DEFAULT_MTI = "0100"; // Default MTI value
    private static final String PARSER_URL = "replace with actual URL"; // Replace with actual URL
    private static final String CANONICAL_URL = "replace with actual URL"; // Replace with actual URL
    private static final String WS_URL = "replace with actual URL"; // Replace with actual URL

    public static void createIsoMessage(String requestName, DataTable dt) throws IOException {
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

    /**
     * Converts a hexadecimal string to its binary representation
     * @param hex The hexadecimal string to convert
     * @return The binary string representation
     */
    static String hexToBinary(String hex) {
        StringBuilder binary = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            String bin = String.format("%4s", Integer.toBinaryString(Integer.parseInt(hex.substring(i, i + 1), 16)))
                    .replace(' ', '0');
            binary.append(bin);
        }
        return binary.toString();
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
    static String getCellValueAsString(Cell cell) {
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
                    if (colNum == 58 || colNum == 60) {
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
            byte[] input = isoMessage.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // Get response code
        int responseCode = connection.getResponseCode();
        StringBuilder response = new StringBuilder();

        // Use error stream for 400 responses, input stream for successful responses
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(responseCode == 400
                        ? connection.getErrorStream()
                        : connection.getInputStream(), StandardCharsets.UTF_8))) {
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
                return "Error: " + response;
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
            byte[] input = isoMessage.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // Get response code
        int responseCode = connection.getResponseCode();
        StringBuilder response = new StringBuilder();

        // Use error stream for 400 responses, input stream for successful responses
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(responseCode == 400
                        ? connection.getErrorStream()
                        : connection.getInputStream(), StandardCharsets.UTF_8))) {
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
                return "Error: " + response;
            }
        }

        return response.toString();
    }

    /**
     * Gets the canonical path(s) for a given DE from the config
     * @param de The data element number
     * @return List of canonical paths for this DE
     */
    static List<String> getCanonicalPaths(String de) {
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

            // Special handling for DE 111 (Additional Data)
            if (de.equals("111")) {
                String formatIdentifier = expectedValue.substring(0, 2);
                List<String> canonicalPaths = getCanonicalPaths(de);
                JsonNode config = fieldConfig.get(de);
                JsonNode formatRules = config.path("validation").path("rules").path("formatIdentifiers").path(formatIdentifier);

                if (!formatRules.isMissingNode() && formatRules.has("paths")) {
                    // Keep only the paths defined for this format
                    List<String> formatPaths = new ArrayList<>();
                    formatRules.get("paths").forEach(node -> formatPaths.add(node.asText()));
                    canonicalPaths.removeIf(path -> !formatPaths.contains(path));

                    // Update the config with filtered paths
                    ((ObjectNode) config).put("canonical", objectMapper.valueToTree(canonicalPaths));
                }
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
                            allPathsValid &= validateSpecialCase(de, expectedValue, canonicalResponse, result, fieldConfig);
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

            if (de.equals("124")) {
                JsonNode rule = validation.get("rules").get("mti");
                if (rule.has("skipReason")){
                    return rule.get("skipReason").asText();
                }
            }
        }
        return "Field is not canonicalized";
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
     * @param de The data element number (111)
     * @param expected The expected DE 111 value
     * @param actual The actual canonical JSON response
     * @param result The validation result object
     * @return true if validation passes, false otherwise
     */
    private static boolean validateAdditionalData(String de, String expected, String actual, ValidationResult result) {
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

            System.out.println("DE 111 - Total length: " + expected.length());
            System.out.println("DE 111 - Format Identifier: " + formatIdentifier);

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

            System.out.println("DE 111 - Primary Bitmap Hex: " + primaryBitmapHex);
            System.out.println("DE 111 - Primary Bitmap Binary: " + primaryBitmapBinary);

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