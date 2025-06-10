package utilities;

import java.util.*;

/**
 * Handles building ISO messages
 */
public class IsoMessageBuilder {
    private Map<Integer, String> isoFields;
    private boolean[] primaryBitmap;
    private boolean[] secondaryBitmap;
    private Set<String> manuallyUpdatedFields;
    private IsoMessageConfig config;

    public IsoMessageBuilder(IsoMessageConfig config) {
        this.config = config;
        this.isoFields = new TreeMap<>();
        this.primaryBitmap = new boolean[64];
        this.secondaryBitmap = new boolean[64];
        this.manuallyUpdatedFields = new HashSet<>();
    }

    public void addField(String field, String value) {
        // Handle MTI separately as a string
        if (field.equalsIgnoreCase("MTI")) {
            isoFields.put(0, value);
            return;
        }

        // Handle Primary Bitmap separately
        if (field.equalsIgnoreCase("PrimaryBitmap") || field.equalsIgnoreCase("SecondaryBitmap")) {
            return; // Bitmaps are automatically generated
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

    public void clear() {
        isoFields.clear();
        manuallyUpdatedFields.clear();
        Arrays.fill(primaryBitmap, false);
        Arrays.fill(secondaryBitmap, false);
    }

    private boolean hasActivePrimaryFields() {
        for (int i = 0; i < 64; i++) {
            if (primaryBitmap[i] && isoFields.containsKey(i + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveSecondaryFields() {
        for (int i = 0; i < 64; i++) {
            if (secondaryBitmap[i] && isoFields.containsKey(i + 65)) {
                return true;
            }
        }
        return false;
    }

    private String bitmapToHex(boolean[] bitmap) {
        StringBuilder binary = new StringBuilder();
        for (boolean bit : bitmap) {
            binary.append(bit ? "1" : "0");
        }

        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 64; i += 4) {
            hex.append(Integer.toHexString(Integer.parseInt(binary.substring(i, i + 4), 2)).toUpperCase());
        }

        return hex.toString();
    }

    public String buildMessage() {
        StringBuilder message = new StringBuilder();

        // Ensure MTI is included, default to "0100" if not manually set
        if (!isoFields.containsKey(0)) {
            message.append("0100");
        } else {
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
        for (Map.Entry<Integer, String> entry : isoFields.entrySet()) {
            int field = entry.getKey();
            if (field == 0) continue; // Skip MTI as it's already added

            String value = entry.getValue();
            String fieldStr = String.valueOf(field);
            var fieldConfig = config.getFieldConfig(fieldStr);
            
            if (fieldConfig != null) {
                String format = fieldConfig.get("format").asText();
                // LLVAR and LLLVAR handling
                if ("llvar".equals(format)) {
                    message.append(String.format("%02d", value.length()));
                } else if ("lllvar".equals(format)) {
                    message.append(String.format("%03d", value.length()));
                }
            }
            message.append(value);
        }

        return message.toString();
    }
} 