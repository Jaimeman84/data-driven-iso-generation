# ISO8583 Message Generator and Validator

This project provides functionality to generate and validate ISO8583 messages using Excel spreadsheets as input/output. The core functionality revolves around converting between spreadsheet data and ISO8583 messages while ensuring proper validation against canonical JSON formats.

## Key Features

### 1. Spreadsheet to ISO Message Generation (`generateIsoFromSpreadsheet`)

The `generateIsoFromSpreadsheet` method takes an Excel spreadsheet as input and generates an ISO8583 message. Here's how it works:

```java
public static void generateIsoFromSpreadsheet(String filePath) throws IOException
```

#### Process Flow:
1. Loads configuration from `iso_config.json`
2. Reads the Excel spreadsheet
3. Processes each field according to the ISO8583 specification
4. Generates bitmaps (primary and secondary)
5. Constructs the final ISO message

### 2. Field Validation Types

1. **Simple Fields**
   - Direct one-to-one mapping between ISO and canonical values
   - Example: Account numbers, processing codes
   - Fixed-length fields with straightforward validation

2. **Composite Fields**
   - Fields containing multiple data elements
   - Require parsing and individual component validation
   - Examples: DE 48, DE 63, DE 111
   - Variable length fields (LLVAR, LLLVAR)

3. **Bitmap-Controlled Fields**
   - Fields whose presence is controlled by bitmap indicators
   - Only validate fields marked as present
   - Examples: DE 111, DE 127

4. **Format-Specific Fields**
   - Different validation rules based on message format
   - Support for multiple format identifiers (MC, MD, etc.)
   - Format-specific field positions and mappings

### 3. Validation Features

#### Configuration Structure Sample
```json
{
  "fields": {
    "111": {
      "validation": {
        "rules": {
          "formatIdentifiers": {
            "MC": {
              "paths": [
                "transaction.additionalData.formatIdentifier",
                "transaction.additionalData.address.zipCode"
              ],
              "primaryBitmap": {
                "fields": {
                  "6": {
                    "name": "postalCode",
                    "length": 10,
                    "path": "transaction.additionalData.address.zipCode"
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
```

#### Validation Capabilities
- **Dynamic Path Mapping**: Fields can map to different JSON paths based on context
- **Conditional Validation**: Rules that change based on other field values or MTI
- **Special Value Handling**: Support for boolean flags, indicators, and presence checks
- **Format Handling**:
  - Format identification and validation
  - Format-specific field mappings
  - Bitmap processing for field presence
  - Special field handling (e.g., isCnp: 0=true, 1=not present)

### 4. Error Handling
- Field-level validation results
- Detailed error messages for mismatches
- Support for skipped fields with reasons

## Usage Example
```java
// Generate ISO message from spreadsheet
generateIsoFromSpreadsheet("input.xlsx");

// Validate ISO message against canonical format
ValidationResult result = validateIsoMessageCanonical(isoMessage, excelRow);
result.printResults();
```

## Best Practices
1. **Validation Process**:
   - Validate format identifiers before field validation
   - Handle missing or optional fields gracefully
   - Maintain clear mapping between ISO fields and canonical paths
   - Document special cases and transformations

2. **Configuration Management**:
   - Keep field configurations in separate JSON files
   - Use clear naming conventions for paths and fields
   - Handle special cases explicitly
   - Maintain bitmap consistency

## TODO: Future Improvements

### 1. Enhanced Testing Framework
- **Data Matrix Testing**:
  - Expand test scenarios in isoData matrix
  - Add field combinations and edge cases
  - Test different MTI combinations

- **Parameterized BDD Testing**:
  - Enable selective tab testing
  - Support MTI-specific scenarios
  - Example:
    ```gherkin
    Scenario Outline: Test specific MTI with varying data
    Given I am testing tab "<tabName>"
    When I process MTI "<mtiValue>"
    Then the ISO message should be generated correctly
    ```

### 2. Code Refactoring
- **Apply SOLID Principles**:
  - Split large classes into focused components
  - Make validation rules extensible
  - Improve class hierarchy and dependencies