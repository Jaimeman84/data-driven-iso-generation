package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import websocket.WebSocketClient;
import websocket.WebSocketManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class IsoMessageTransport {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String PARSER_URL = "replace with actual URL"; // Replace with actual URL
    private static final String CANONICAL_URL = "replace with actual URL"; // Replace with actual URL
    private static final String WS_URL = "replace with actual URL"; // Replace with actual URL

    /**
     * Sends an ISO message to the parser endpoint
     * @param isoMessage The ISO message to parse
     * @return The parsed response
     */
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
     * Sends a message via WebSocket and returns the response
     * @param message The message to send
     * @return The WebSocket response
     */
    public static String sendWebSocketMessage(String message) throws Exception {
        WebSocketManager.init(WS_URL);
        WebSocketManager.sendMessage(message);
        String wsResponse = WebSocketClient.getResponseMessage();
        WebSocketManager.close();
        return wsResponse;
    }
} 