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

public class CreateIsoMessage  {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static Map<String, JsonNode> fieldConfig;
    private static Map<Integer, String> isoFields = new TreeMap<>();
    private static boolean[] primaryBitmap = new boolean[64];
    private static boolean[] secondaryBitmap = new boolean[64];
    private static Set<String> manuallyUpdatedFields = new HashSet<>(); // Tracks modified fields

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
        System.out.println("\n=== Starting ISO message generation from spreadsheet ===");
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

            // Create a cell for the ISO Message header in column CE
            Cell headerCell = headerRow.createCell(82); // Column CE
            headerCell.setCellValue("Generated ISO Message");

            // Process each row starting from row 4
            for (int rowIndex = 3; rowIndex <= totalRows; rowIndex++) {
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
                // Start from Column B (index 1) and go to Column CD (index 81)
                for (int colNum = 1; colNum <= 81; colNum++) {
                    // Get the Data Element Key from Row 1
                    Cell headerCell2 = headerRow.getCell(colNum);
                    String dataElementKey = getCellValueAsString(headerCell2).trim();
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

                    // Write the ISO message back to the spreadsheet in column CE
                    Cell messageCell = dataRow.createCell(82);
                    messageCell.setCellValue(isoMessage);
                } else {
                    System.out.println("\nNo fields processed for Row " + (rowIndex + 1) + " - skipping ISO message generation");
                }
            }

            // Save the workbook
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
                System.out.println("\nSuccessfully wrote all ISO messages to spreadsheet in column CE");
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

}