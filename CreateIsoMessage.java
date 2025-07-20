package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;
import lombok.Getter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static utilities.CustomTestData.generateCustomValue;
import static utilities.CustomTestData.generateRandomText;
import static utilities.DataElementSpecialCaseValidator.*;
import static utilities.ValidationResultManager.*;
import static utilities.CanonicalValidator.*;
import static utilities.IsoMessageNetworkClient.*;

public class CreateIsoMessage {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static Map<String, JsonNode> fieldConfig;
    static final Map<Integer, String> isoFields = new TreeMap<>();
    private static final boolean[] primaryBitmap = new boolean[64];
    private static final boolean[] secondaryBitmap = new boolean[64];
    private static final Set<String> manuallyUpdatedFields = new HashSet<>(); // Tracks modified fields
    private static final String DEFAULT_MTI = "0100"; // Default MTI value

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

                // Extract DE values from the row
                Map<String, String> deValues = extractDEValuesFromExcel(dataRow);

                if (!deValues.isEmpty()) {
                    System.out.println("\n=== Row " + (rowIndex + 1) + " Processing Summary ===");
                    System.out.println("Total fields processed: " + deValues.size());

                    // Generate default fields and build ISO message
                    generateDefaultFields();
                    String isoMessage = buildIsoMessage();
                    System.out.println("\nGenerated ISO Message for Row " + (rowIndex + 1) + ":");
                    System.out.println(isoMessage);

                    // Write the ISO message to the spreadsheet
                    Cell messageCell = dataRow.createCell(89); // Column CL
                    messageCell.setCellValue(isoMessage);

                    try {
                        // Send message via WebSocket and get response
                        String wsResponse = sendWebSocketMessage(isoMessage);

                        // Parse response to get DE39
                        String parsedResponse = sendIsoMessageToParser(wsResponse);
                        JsonNode responseArray = objectMapper.readTree(parsedResponse);

                        // Extract DE39 (Response Code) from array
                        String responseCode = extractResponseCode(responseArray);
                        writeResponseCode(dataRow, responseCode);

                        // Validate against canonical form
                        ValidationResult validationResult = validateIsoMessageCanonical(isoMessage, deValues, fieldConfig, isoFields);
                        validationResult.printResults();

                        // Export validation results to Excel
                        exportValidationResultsToExcel(workbook, validationResult, rowIndex);

                        // Write validation summary to spreadsheet
                        writeValidationSummary(dataRow, validationResult);

                    } catch (Exception e) {
                        System.out.println("\nError during processing: " + e.getMessage());
                        e.printStackTrace();
                        Cell responseCell = dataRow.createCell(91); // Column CN
                        responseCell.setCellValue("Error: " + e.getMessage());
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
        Row headerRow = row.getSheet().getRow(0); // Get header row from the sheet

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
     * Extracts response code (DE39) from parser response
     */
    private static String extractResponseCode(JsonNode responseArray) {
        if (responseArray.isArray()) {
            for (int i = 0; i < responseArray.size(); i++) {
                JsonNode element = responseArray.get(i);
                String elementId = element.get("dataElementId").asText();
                if ("39".equals(elementId)) {
                    System.out.println("Found DE39 with value: " + element.get("value").asText());
                    return element.get("value").asText();
                }
            }
        }
        return null;
    }

    /**
     * Writes response code to Excel
     */
    private static void writeResponseCode(Row row, String responseCode) {
        Cell responseCell = row.createCell(91); // Column CN
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
    }

    /**
     * Writes validation summary to Excel
     */
    private static void writeValidationSummary(Row row, ValidationResult validationResult) {
        Cell validationCell = row.createCell(90); // Column CM
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
    }

    // Update the call in generateIsoFromSpreadsheet
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

                // Extract DE values from the row
                Map<String, String> deValues = extractDEValuesFromExcel(dataRow);

                if (!deValues.isEmpty()) {
                    System.out.println("\n=== Row " + (rowIndex + 1) + " Processing Summary ===");
                    System.out.println("Total fields processed: " + deValues.size());

                    // Generate default fields and build ISO message
                    generateDefaultFields();
                    String isoMessage = buildIsoMessage();
                    System.out.println("\nGenerated ISO Message for Row " + (rowIndex + 1) + ":");
                    System.out.println(isoMessage);

                    // Write the ISO message to the spreadsheet
                    Cell messageCell = dataRow.createCell(89); // Column CL
                    messageCell.setCellValue(isoMessage);

                    try {
                        // Send message via WebSocket and get response
                        String wsResponse = sendWebSocketMessage(isoMessage);

                        // Parse response to get DE39
                        String parsedResponse = sendIsoMessageToParser(wsResponse);
                        JsonNode responseArray = objectMapper.readTree(parsedResponse);

                        // Extract DE39 (Response Code) from array
                        String responseCode = extractResponseCode(responseArray);
                        writeResponseCode(dataRow, responseCode);

                        // Validate against canonical form
                        ValidationResult validationResult = validateIsoMessageCanonical(isoMessage, deValues, fieldConfig, isoFields);
                        validationResult.printResults();

                        // Export validation results to Excel
                        exportValidationResultsToExcel(workbook, validationResult, rowIndex);

                        // Write validation summary to spreadsheet
                        writeValidationSummary(dataRow, validationResult);

                    } catch (Exception e) {
                        System.out.println("\nError during processing: " + e.getMessage());
                        e.printStackTrace();
                        Cell responseCell = dataRow.createCell(91); // Column CN
                        responseCell.setCellValue("Error: " + e.getMessage());
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
}