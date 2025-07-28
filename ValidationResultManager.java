package utilities;

import lombok.Getter;
import org.apache.poi.ss.usermodel.*;

import java.util.*;
import java.util.stream.Collectors;

public class ValidationResultManager {
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

    @Getter
    public static class RowSummary {
        private final int rowNumber;
        private final int totalFields;
        private final long passCount;
        private final long failCount;
        private final long skipCount;
        private final String failedDEs;
        private final String skippedDEs;

        public RowSummary(int rowNumber, int totalFields, long passCount, long failCount, long skipCount, String failedDEs, String skippedDEs) {
            this.rowNumber = rowNumber;
            this.totalFields = totalFields;
            this.passCount = passCount;
            this.failCount = failCount;
            this.skipCount = skipCount;
            this.failedDEs = failedDEs;
            this.skippedDEs = skippedDEs;
        }

        @Override
        public String toString() {
            return String.format("Row %d - Total Fields: %d, Passed: %d, Failed: %d%s, Skipped: %d%s",
                    rowNumber,
                    totalFields,
                    passCount,
                    failCount,
                    failCount > 0 ? " (DE " + failedDEs + ")" : "",
                    skipCount,
                    skipCount > 0 ? " (DE " + skippedDEs + ")" : "");
        }
    }

    /**
     * Class to hold validation results
     */
    @Getter
    public static class ValidationResult {
        private final Map<String, FieldResult> results = new HashMap<>();
        private RowSummary lastRowSummary;

        /**
         * Clears all validation results
         */
        public void clear() {
            results.clear();
        }

        public void addPassedField(String de, String expected, String actual) {
            results.put(de, new FieldResult(FieldStatus.PASSED, expected, actual));
        }

        public void addFailedField(String de, String expected, String actual) {
            results.put(de, new FieldResult(FieldStatus.FAILED, expected, actual));
        }

        public void addSkippedField(String de, String expected, String reason) {
            results.put(de, new FieldResult(FieldStatus.SKIPPED, expected, reason));
        }

        public RowSummary getLastRowSummary() {
            return lastRowSummary;
        }

        public void printResults() {
            // Get current row index from the thread local storage
            Integer currentRowIndex = CreateIsoMessage.currentRowIndex.get();

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
                    // Format ISO value to show original value and any paired values
                    String isoValue = result.getExpected();

                    // Format canonical value with any relevant conversion info
                    String canonicalValue = result.getActual();

                    System.out.printf("%-6s | %-15s | %-40s | %-40s | %s%n",
                            de,
                            result.getStatus().toString(),
                            truncateOrPad(isoValue, 40),
                            truncateOrPad(canonicalValue, 40),
                            "See mapping in config"
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

            // Calculate summary
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

            // Store the summary
            if (currentRowIndex != null) {
                lastRowSummary = new RowSummary(
                    currentRowIndex + 1,
                    results.size(),
                    passCount,
                    failCount,
                    skipCount,
                    failedDEs,
                    skippedDEs
                );
            }

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
     * Exports validation results to a new sheet in the Excel workbook
     */
    public static void exportValidationResultsToExcel(Workbook workbook, ValidationResult results, int rowIndex) {
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
            row.createCell(5).setCellValue("See config for mapping");

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

    /**
     * Class to hold aggregated validation results across multiple ISO messages
     */
    @Getter
    public static class AggregatedResults {
        private final int totalMessages;
        private final int totalFields;
        private final Map<String, Integer> passedByDE = new HashMap<>();
        private final Map<String, Integer> failedByDE = new HashMap<>();
        private final Map<String, Integer> skippedByDE = new HashMap<>();
        private final Map<String, List<String>> failureReasonsByDE = new HashMap<>();
        private final Map<Integer, Map<String, FieldResult>> resultsByRow = new HashMap<>();
        private final List<RowSummary> rowSummaries = new ArrayList<>();

        public AggregatedResults(int totalMessages, int totalFields) {
            this.totalMessages = totalMessages;
            this.totalFields = totalFields;
        }

        public void addRowSummary(RowSummary summary) {
            if (summary != null) {
                rowSummaries.add(summary);
            }
        }

        public List<RowSummary> getRowSummaries() {
            return Collections.unmodifiableList(rowSummaries);
        }

        public void addResult(int rowNumber, String de, FieldResult result) {
            // Store result by row
            resultsByRow.computeIfAbsent(rowNumber, k -> new HashMap<>()).put(de, result);

            // Update counters based on status
            switch (result.getStatus()) {
                case PASSED:
                    passedByDE.merge(de, 1, Integer::sum);
                    break;
                case FAILED:
                    failedByDE.merge(de, 1, Integer::sum);
                    failureReasonsByDE.computeIfAbsent(de, k -> new ArrayList<>())
                            .add(String.format("Row %d: %s", rowNumber, result.getActual()));
                    break;
                case SKIPPED:
                    skippedByDE.merge(de, 1, Integer::sum);
                    break;
            }
        }

        /**
         * Gets the success rate for a specific DE
         */
        public double getSuccessRate(String de) {
            int total = passedByDE.getOrDefault(de, 0) + failedByDE.getOrDefault(de, 0) + skippedByDE.getOrDefault(de, 0);
            return total > 0 ? (double) passedByDE.getOrDefault(de, 0) / total : 0.0;
        }

        /**
         * Gets DEs sorted by failure rate (highest first), including all processed DEs
         */
        public List<String> getDEsByFailureRate() {
            // Collect all unique DEs from passed, failed, and skipped results
            Set<String> allDEs = new HashSet<>();
            allDEs.addAll(passedByDE.keySet());
            allDEs.addAll(failedByDE.keySet());
            allDEs.addAll(skippedByDE.keySet());

            List<String> des = new ArrayList<>(allDEs);
            des.sort((de1, de2) -> {
                double rate1 = 1.0 - getSuccessRate(de1);
                double rate2 = 1.0 - getSuccessRate(de2);
                return Double.compare(rate2, rate1);
            });
            return des;
        }

        /**
         * Gets a summary of the aggregated results
         */
        public String getSummary() {
            StringBuilder summary = new StringBuilder();
            summary.append("\n=== Aggregated Validation Results ===\n");
            summary.append(String.format("Total ISO Messages: %d\n", totalMessages));
            summary.append(String.format("Total Fields Validated: %d\n", totalFields));

            // Calculate overall statistics from row summaries
            int totalPassed = 0;
            int totalFailed = 0;
            int totalSkipped = 0;

            for (RowSummary rowSummary : rowSummaries) {
                totalPassed += rowSummary.getPassCount();
                totalFailed += rowSummary.getFailCount();
                totalSkipped += rowSummary.getSkipCount();
            }

            summary.append(String.format("\nOverall Results:\n"));
            summary.append(String.format("Passed: %d (%.2f%%)\n", totalPassed, (double) totalPassed / totalFields * 100));
            summary.append(String.format("Failed: %d (%.2f%%)\n", totalFailed, (double) totalFailed / totalFields * 100));
            summary.append(String.format("Skipped: %d (%.2f%%)\n", totalSkipped, (double) totalSkipped / totalFields * 100));

            // Results by DE
            List<String> allDEs = getDEsByFailureRate();
            if (allDEs.isEmpty()) {
                summary.append("\nNo Data Elements were processed.\n");
            } else {
                summary.append("\nResults by Data Element:\n");
                for (String de : allDEs) {
                    int passed = passedByDE.getOrDefault(de, 0);
                    int failed = failedByDE.getOrDefault(de, 0);
                    int skipped = skippedByDE.getOrDefault(de, 0);
                    int total = passed + failed + skipped;
                    double successRate = getSuccessRate(de);

                    summary.append(String.format("\nDE %s:\n", de));
                    summary.append(String.format("  Total: %d\n", total));
                    summary.append(String.format("  Success Rate: %.2f%%\n", successRate * 100));
                    summary.append(String.format("  Passed: %d\n", passed));
                    summary.append(String.format("  Failed: %d\n", failed));
                    summary.append(String.format("  Skipped: %d\n", skipped));

                    if (failed > 0 && failureReasonsByDE.containsKey(de)) {
                        summary.append("  Failure Reasons:\n");
                        failureReasonsByDE.get(de).forEach(reason -> 
                            summary.append("    - ").append(reason).append("\n"));
                    }
                }
            }

            return summary.toString();
        }
    }

    /**
     * Aggregates results from multiple validation runs
     * @param results Map of row numbers to validation results
     * @return Aggregated results object
     */
    public static AggregatedResults aggregateResults(Map<Integer, ValidationResult> results) {
        // Calculate total fields from row summaries to ensure consistency
        int totalFields = results.values().stream()
                .mapToInt(r -> r.getResults().size())
                .sum();

        AggregatedResults aggregated = new AggregatedResults(results.size(), totalFields);

        // Process each row's results
        results.forEach((rowNumber, validationResult) -> {
            // Add the row summary if available
            if (validationResult.getLastRowSummary() != null) {
                aggregated.addRowSummary(validationResult.getLastRowSummary());
                
                // Update per-DE statistics
                Map<String, FieldResult> rowResults = validationResult.getResults();
                rowResults.forEach((de, fieldResult) -> {
                    switch (fieldResult.getStatus()) {
                        case PASSED:
                            aggregated.passedByDE.merge(de, 1, Integer::sum);
                            break;
                        case FAILED:
                            aggregated.failedByDE.merge(de, 1, Integer::sum);
                            aggregated.failureReasonsByDE.computeIfAbsent(de, k -> new ArrayList<>())
                                    .add(String.format("Row %d: %s", rowNumber, fieldResult.getActual()));
                            break;
                        case SKIPPED:
                            aggregated.skippedByDE.merge(de, 1, Integer::sum);
                            break;
                    }
                });
            }
        });

        return aggregated;
    }
} 