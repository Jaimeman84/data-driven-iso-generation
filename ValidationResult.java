package utilities;

import java.util.*;

/**
 * Holds validation results and provides formatting
 */
public class ValidationResult {
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
            System.out.println(String.format("%-6s | %-15s | %-30s | %-30s | %s",
                de,
                result.isPassed() ? "PASS" : "FAIL",
                truncateOrPad(result.getExpected(), 30),
                truncateOrPad(result.getActual(), 30),
                result.getCanonicalPath()
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

    /**
     * Represents a single field validation result
     */
    public static class FieldResult {
        private final boolean passed;
        private final String expected;
        private final String actual;
        private String canonicalPath;
        
        public FieldResult(boolean passed, String expected, String actual) {
            this.passed = passed;
            this.expected = expected;
            this.actual = actual;
        }

        public void setCanonicalPath(String path) {
            this.canonicalPath = path;
        }
        
        public boolean isPassed() { return passed; }
        public String getExpected() { return expected; }
        public String getActual() { return actual; }
        public String getCanonicalPath() { return canonicalPath; }
    }
} 