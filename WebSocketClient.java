package websocket;

import lombok.Getter;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public class WebSocketClient extends org.java_websocket.client.WebSocketClient {

   private final CountDownLatch latch;
   private static final Logger logger = Logger.getLogger(WebSocketClient.class.getName());

   @Getter
   public static String responseMessage;

   public WebSocketClient(URI serverUri, CountDownLatch latch) {
       super(serverUri);
       this.latch = latch;
   }

   @Override
   public void onOpen(ServerHandshake handshake) {
       logger.info("Connected to the WebSocket server.");
       send("Test message from client.");
   }

   @Override
   public void onMessage(String message) {
       responseMessage = message;
       latch.countDown();  // Signal that the message was received
   }

   @Override
   public void onClose(int code, String reason, boolean remote) {
       logger.info("Closed connection to the WebSocket server." + reason);
   }

   @Override
   public void onError(Exception ex) {
       logger.info("Error: " + ex.getMessage());
   }

}

