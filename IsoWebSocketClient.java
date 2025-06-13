package utilities;

import javax.websocket.*;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ClientEndpoint
public class IsoWebSocketClient {
    private Session session;
    private static CountDownLatch messageLatch;
    private String receivedMessage;
    private static final int TIMEOUT_SECONDS = 30;

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        System.out.println("WebSocket connection established");
    }

    @OnMessage
    public void onMessage(String message) {
        System.out.println("Received message: " + message);
        this.receivedMessage = message;
        if (messageLatch != null) {
            messageLatch.countDown();
        }
    }

    @OnError
    public void onError(Throwable error) {
        System.err.println("WebSocket error occurred: " + error.getMessage());
        error.printStackTrace();
    }

    @OnClose
    public void onClose(CloseReason reason) {
        System.out.println("WebSocket connection closed: " + reason);
        this.session = null;
    }

    /**
     * Sends an ISO message via WebSocket and waits for the response
     * @param wsUrl The WebSocket URL to connect to
     * @param isoMessage The ISO message to send
     * @return The response received from the server
     * @throws Exception if any error occurs during the process
     */
    public String sendIsoMessage(String wsUrl, String isoMessage) throws Exception {
        try {
            // Create WebSocket container and connect
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxSessionIdleTimeout(TIMEOUT_SECONDS * 1000);
            
            // Connect to the WebSocket server
            session = container.connectToServer(this, new URI(wsUrl));
            
            // Reset the latch for this message
            messageLatch = new CountDownLatch(1);
            
            // Send the message
            System.out.println("Sending ISO message: " + isoMessage);
            session.getBasicRemote().sendText(isoMessage);
            
            // Wait for response with timeout
            boolean received = messageLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!received) {
                throw new RuntimeException("Timeout waiting for WebSocket response after " + TIMEOUT_SECONDS + " seconds");
            }
            
            return receivedMessage;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to send message via WebSocket: " + e.getMessage(), e);
        } finally {
            // Clean up
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
    }

    /**
     * Sends an ISO message via WebSocket with custom headers
     * @param wsUrl The WebSocket URL to connect to
     * @param isoMessage The ISO message to send
     * @param headers Custom headers for the WebSocket connection
     * @return The response received from the server
     * @throws Exception if any error occurs during the process
     */
    public String sendIsoMessageWithHeaders(String wsUrl, String isoMessage, ClientEndpointConfig.Builder configBuilder) throws Exception {
        try {
            // Create WebSocket container and connect with custom config
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxSessionIdleTimeout(TIMEOUT_SECONDS * 1000);
            
            // Connect to the WebSocket server with custom config
            ClientEndpointConfig config = configBuilder.build();
            session = container.connectToServer(this, config, new URI(wsUrl));
            
            // Reset the latch for this message
            messageLatch = new CountDownLatch(1);
            
            // Send the message
            System.out.println("Sending ISO message with custom headers: " + isoMessage);
            session.getBasicRemote().sendText(isoMessage);
            
            // Wait for response with timeout
            boolean received = messageLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!received) {
                throw new RuntimeException("Timeout waiting for WebSocket response after " + TIMEOUT_SECONDS + " seconds");
            }
            
            return receivedMessage;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to send message via WebSocket: " + e.getMessage(), e);
        } finally {
            // Clean up
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
    }
} 