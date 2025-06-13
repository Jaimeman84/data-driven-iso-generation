package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public class IsoMessageProcessor {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final String webSocketUrl;
    private final String parserUrl;
    private final IsoWebSocketClient wsClient;

    public IsoMessageProcessor(String webSocketUrl, String parserUrl) {
        this.webSocketUrl = webSocketUrl;
        this.parserUrl = parserUrl;
        this.wsClient = new IsoWebSocketClient();
    }

    /**
     * Process an ISO message: send via WebSocket, parse response, validate MTI and get response code
     * @param isoMessage The ISO message to process
     * @param requestMti The original request MTI
     * @return ProcessedIsoResponse containing all response details
     */
    public ProcessedIsoResponse processIsoMessage(String isoMessage, String requestMti) {
        try {
            // Send message via WebSocket and get response
            String wsResponse = wsClient.sendIsoMessage(webSocketUrl, isoMessage);
            System.out.println("\nWebSocket Response:");
            System.out.println(wsResponse);

            // Send the WebSocket response to the parser
            String parsedResponse = sendToParser(wsResponse);
            JsonNode responseJson = objectMapper.readTree(parsedResponse);

            // Extract MTI and DE 39 from response
            String responseMti = null;
            String responseCode = null;

            // Find MTI and DE 39 in the parsed response
            for (JsonNode element : responseJson) {
                if (element.has("dataElementId")) {
                    String deId = element.get("dataElementId").asText();
                    if ("MTI".equals(deId)) {
                        responseMti = element.get("value").asText();
                    } else if ("39".equals(deId)) {
                        responseCode = element.get("value").asText();
                    }
                }
            }

            // Validate MTI
            String expectedResponseMti = getExpectedResponseMti(requestMti);
            boolean mtiValid = expectedResponseMti.equals(responseMti);

            // Get response code description
            String responseDesc = getResponseCodeDescription(responseCode);

            return new ProcessedIsoResponse(
                responseMti,
                expectedResponseMti,
                mtiValid,
                responseCode,
                responseDesc,
                wsResponse,
                parsedResponse
            );

        } catch (Exception e) {
            return new ProcessedIsoResponse(e);
        }
    }

    /**
     * Send ISO message to parser
     */
    private String sendToParser(String isoMessage) throws Exception {
        // Implementation should be moved from CreateIsoMessage class
        // For now, using a placeholder
        return ""; // Replace with actual implementation
    }

    /**
     * Gets the expected response MTI based on the request MTI
     */
    private String getExpectedResponseMti(String requestMti) {
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
     */
    private String getResponseCodeDescription(String responseCode) {
        if (responseCode == null) return "No response code";

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
        
        return descriptions.getOrDefault(responseCode, "Unknown response code: " + responseCode);
    }

    /**
     * Class to hold the processed ISO response details
     */
    public static class ProcessedIsoResponse {
        private final String responseMti;
        private final String expectedResponseMti;
        private final boolean mtiValid;
        private final String responseCode;
        private final String responseDescription;
        private final String rawResponse;
        private final String parsedResponse;
        private final Exception error;

        public ProcessedIsoResponse(
                String responseMti,
                String expectedResponseMti,
                boolean mtiValid,
                String responseCode,
                String responseDescription,
                String rawResponse,
                String parsedResponse) {
            this.responseMti = responseMti;
            this.expectedResponseMti = expectedResponseMti;
            this.mtiValid = mtiValid;
            this.responseCode = responseCode;
            this.responseDescription = responseDescription;
            this.rawResponse = rawResponse;
            this.parsedResponse = parsedResponse;
            this.error = null;
        }

        public ProcessedIsoResponse(Exception error) {
            this.responseMti = null;
            this.expectedResponseMti = null;
            this.mtiValid = false;
            this.responseCode = null;
            this.responseDescription = null;
            this.rawResponse = null;
            this.parsedResponse = null;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }

        public String getFormattedResponse() {
            if (!isSuccess()) {
                return "Error: " + error.getMessage();
            }

            StringBuilder response = new StringBuilder();
            response.append("MTI: ").append(responseMti)
                   .append(mtiValid ? " (✓)" : " (✗)");
            
            if (responseCode != null) {
                response.append(", DE 39: ").append(responseCode);
                if (responseDescription != null && !responseDescription.isEmpty()) {
                    response.append(" (").append(responseDescription).append(")");
                }
            } else {
                response.append(", DE 39: Not found in response");
            }

            return response.toString();
        }

        // Getters
        public String getResponseMti() { return responseMti; }
        public String getExpectedResponseMti() { return expectedResponseMti; }
        public boolean isMtiValid() { return mtiValid; }
        public String getResponseCode() { return responseCode; }
        public String getResponseDescription() { return responseDescription; }
        public String getRawResponse() { return rawResponse; }
        public String getParsedResponse() { return parsedResponse; }
        public Exception getError() { return error; }
    }
} 