package utilities;

public class IsoBitmapManager {
    private static final boolean[] primaryBitmap = new boolean[64];
    private static final boolean[] secondaryBitmap = new boolean[64];

    /**
     * Resets both primary and secondary bitmaps
     */
    public static void resetBitmaps() {
        for (int i = 0; i < 64; i++) {
            primaryBitmap[i] = false;
            secondaryBitmap[i] = false;
        }
    }

    /**
     * Sets a bit in the appropriate bitmap based on the field number
     * @param fieldNumber The field number (1-128)
     */
    public static void setBit(int fieldNumber) {
        if (fieldNumber <= 0 || fieldNumber > 128) {
            throw new IllegalArgumentException("Field number must be between 1 and 128");
        }

        if (fieldNumber <= 64) {
            primaryBitmap[fieldNumber - 1] = true;
        } else {
            secondaryBitmap[fieldNumber - 65] = true;
            primaryBitmap[0] = true; // Ensure secondary bitmap is marked active
        }
    }

    /**
     * Checks if any fields in the primary bitmap (1-64) are active
     */
    public static boolean hasActivePrimaryFields() {
        for (int i = 0; i < 64; i++) {
            if (primaryBitmap[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if any fields in the secondary bitmap (65-128) are active
     */
    public static boolean hasActiveSecondaryFields() {
        for (int i = 0; i < 64; i++) {
            if (secondaryBitmap[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a bitmap array to its hexadecimal representation
     */
    public static String bitmapToHex(boolean[] bitmap) {
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
     */
    public static String hexToBinary(String hex) {
        StringBuilder binary = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            String bin = String.format("%4s", Integer.toBinaryString(Integer.parseInt(hex.substring(i, i + 1), 16)))
                    .replace(' ', '0');
            binary.append(bin);
        }
        return binary.toString();
    }

    /**
     * Gets the primary bitmap as a hex string
     */
    public static String getPrimaryBitmapHex() {
        return bitmapToHex(primaryBitmap);
    }

    /**
     * Gets the secondary bitmap as a hex string
     */
    public static String getSecondaryBitmapHex() {
        return bitmapToHex(secondaryBitmap);
    }

    /**
     * Checks if a specific bit is set in either bitmap
     * @param fieldNumber The field number (1-128)
     */
    public static boolean isBitSet(int fieldNumber) {
        if (fieldNumber <= 0 || fieldNumber > 128) {
            return false;
        }

        if (fieldNumber <= 64) {
            return primaryBitmap[fieldNumber - 1];
        } else {
            return secondaryBitmap[fieldNumber - 65];
        }
    }
} 