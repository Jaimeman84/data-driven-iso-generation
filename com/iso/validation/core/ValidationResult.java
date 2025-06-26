package com.iso.validation.core;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages validation results for multiple fields.
 */
public class ValidationResult {
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
        System.out.println("\nValidation Results:");
        System.out.println("==================");

        // Count results by status
        long passCount = results.values().stream()
                .filter(r -> r.getStatus() == FieldStatus.PASSED)
                .count();
        long failCount = results.values().stream()
                .filter(r -> r.getStatus() == FieldStatus.FAILED)
                .count();
        long skipCount = results.values().stream()
                .filter(r -> r.getStatus() == FieldStatus.SKIPPED)
                .count();

        // Get failed and skipped DEs
        String failedDEs = results.entrySet().stream()
                .filter(e -> e.getValue().getStatus() == FieldStatus.FAILED)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));
        String skippedDEs = results.entrySet().stream()
                .filter(e -> e.getValue().getStatus() == FieldStatus.SKIPPED)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));

        // Print summary
        System.out.println(String.format("Total Fields: %d", results.size()));
        System.out.println(String.format("Passed: %d", passCount));
        System.out.println(String.format("Failed: %d", failCount));
        System.out.println(String.format("Skipped: %d", skipCount));

        if (!failedDEs.isEmpty()) {
            System.out.println("\nFailed DEs: " + failedDEs);
        }
        if (!skippedDEs.isEmpty()) {
            System.out.println("Skipped DEs: " + skippedDEs);
        }

        // Print detailed results
        System.out.println("\nDetailed Results:");
        System.out.println("================");
        results.forEach((de, result) -> {
            System.out.println(String.format("\nDE %s: %s", de, result.getStatus()));
            System.out.println("Expected: " + formatIsoValue(de, result));
            System.out.println("Actual: " + formatCanonicalValue(de, result));
        });
    }

    private String formatIsoValue(String de, FieldResult result) {
        if (result.getStatus() == FieldStatus.SKIPPED) {
            return result.getExpected() + " [SKIPPED: " + result.getActual() + "]";
        }
        return result.getExpected();
    }

    private String formatCanonicalValue(String de, FieldResult result) {
        if (result.getStatus() == FieldStatus.SKIPPED) {
            return "N/A";
        }
        return result.getActual();
    }

    private String truncateOrPad(String str, int length) {
        if (str == null) {
            return String.format("%-" + length + "s", "");
        }
        if (str.length() > length) {
            return str.substring(0, length);
        }
        return String.format("%-" + length + "s", str);
    }
} 