
package websocket;

import java.net.URI;
import java.util.concurrent.CountDownLatch;

public class WebSocketManager {

   private static WebSocketClient client;
   //private static final String SERVER_URI = "enter ws url"; // Use your actual WebSocket server URI

   public static void init(String SERVER_URI) throws Exception {
       // Set up WebSocket client and connect to the server
       URI serverUri = new URI(SERVER_URI);
       CountDownLatch latch = new CountDownLatch(1);
       client = new WebSocketClient(serverUri, latch);
       client.connect();

       /* Wait for the connection to be established */
       latch.await();
   }

   public static void sendMessage(String request) throws InterruptedException {
       // Send message to the server and wait for the response
       client.send(request);

       // Wait for the response (you can adjust this depending on your server's response time)
       Thread.sleep(1000); // Simulating response time
   }

   public static void close() {
       // Close the WebSocket client after the test
       if (client != null) {
           client.close();
       }
   }
}

