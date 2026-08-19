package us.ihmc.scs2.session.foxglove;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class FoxgloveLoggerEmulatorTest
{
   @Test
   public void testCdrWriterEncodesStdStringAndJointState()
   {
      String urdf = FoxgloveLoggerEmulator.demoUrdf();
      byte[] stringPayload = Ros2CdrWriter.stdString(urdf);
      assertEquals(0x00, stringPayload[0]);
      assertEquals(0x01, stringPayload[1]);
      assertTrue(new String(stringPayload, StandardCharsets.UTF_8).contains("persona_emulator"));

      byte[] joints = Ros2CdrWriter.jointState(1, 2, new String[] {"shoulder", "elbow"}, new double[] {0.1, 0.2}, new double[] {1, 2}, new double[] {0, 0});
      String jointBytes = new String(joints, StandardCharsets.UTF_8);
      assertTrue(jointBytes.contains("shoulder"));
      assertTrue(jointBytes.contains("elbow"));
   }

   @Test
   public void testClientReceivesAdvertiseAndJointStates() throws Exception
   {
      FoxgloveLoggerEmulator emulator = new FoxgloveLoggerEmulator(0);
      emulator.start();
      ProbeClient client = new ProbeClient();
      try
      {
         client.connect("127.0.0.1", emulator.getPort());
         assertTrue(client.serverName.get(5, TimeUnit.SECONDS).contains("emulator"));
         String advertise = client.advertise.get(5, TimeUnit.SECONDS);
         assertTrue(advertise.contains("/robot_description"));
         assertTrue(advertise.contains("/joint_states"));

         CountDownLatch latch = new CountDownLatch(1);
         AtomicReference<byte[]> jointPayload = new AtomicReference<>();
         client.jointPayload = jointPayload;
         client.jointLatch = latch;
         client.subscribe();
         assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for /joint_states");
         String jointBytes = new String(jointPayload.get(), StandardCharsets.UTF_8);
         assertTrue(jointBytes.contains("shoulder"));
         assertTrue(jointBytes.contains("elbow"));
      }
      finally
      {
         client.close();
         emulator.stop();
      }
   }

   /**
    * Minimal {@code foxglove.sdk.v1} client so this test source set does not depend on the
    * (not-yet-committed) production WebSocket client.
    */
   private static final class ProbeClient implements WebSocket.Listener
   {
      private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      private final CompletableFuture<String> serverName = new CompletableFuture<>();
      private final CompletableFuture<String> advertise = new CompletableFuture<>();
      private final StringBuilder text = new StringBuilder();
      private volatile WebSocket webSocket;
      volatile CountDownLatch jointLatch;
      volatile AtomicReference<byte[]> jointPayload;

      void connect(String host, int port) throws Exception
      {
         webSocket = httpClient.newWebSocketBuilder()
                               .subprotocols(FoxgloveLoggerEmulator.SUBPROTOCOL)
                               .connectTimeout(Duration.ofSeconds(5))
                               .buildAsync(URI.create("ws://" + host + ":" + port), this)
                               .get(10, TimeUnit.SECONDS);
      }

      void subscribe()
      {
         webSocket.sendText("{\"op\":\"subscribe\",\"subscriptions\":["
                            + "{\"id\":1,\"channelId\":1},{\"id\":2,\"channelId\":2},"
                            + "{\"id\":3,\"channelId\":3},{\"id\":4,\"channelId\":4}]}", true);
      }

      void close()
      {
         if (webSocket != null)
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
      }

      @Override
      public void onOpen(WebSocket webSocket)
      {
         webSocket.request(1);
      }

      @Override
      public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
      {
         text.append(data);
         if (last)
         {
            String json = text.toString();
            text.setLength(0);
            if (json.contains("\"serverInfo\""))
               serverName.complete(json);
            else if (json.contains("\"advertise\""))
               advertise.complete(json);
         }
         webSocket.request(1);
         return null;
      }

      @Override
      public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
      {
         ByteBuffer buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
         if (buffer.remaining() >= 13 && buffer.get() == 1)
         {
            int subscriptionId = buffer.getInt();
            buffer.getLong();
            if (subscriptionId == 2 && jointLatch != null)
            {
               byte[] payload = new byte[buffer.remaining()];
               buffer.get(payload);
               jointPayload.set(payload);
               jointLatch.countDown();
            }
         }
         webSocket.request(1);
         return null;
      }
   }
}
