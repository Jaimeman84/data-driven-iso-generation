package stepDefinitions;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static utilities.CreateIsoMessage.*;

//import static qa.CreateIsoMessage.*;

public class ISO8583MessageGenerator {

    @When("^I update iso file \"([^\"]*)\" and send the request$")
    public void i_update_iso_file_and_send_the_request(String requestName, DataTable dt) throws IOException {
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

    @When("^I generate ISO message from spreadsheet \"([^\"]*)\"$")
    public void i_generate_iso_message_from_spreadsheet(String filePath) throws IOException {
        generateIsoFromSpreadsheet(filePath);
    }

}
