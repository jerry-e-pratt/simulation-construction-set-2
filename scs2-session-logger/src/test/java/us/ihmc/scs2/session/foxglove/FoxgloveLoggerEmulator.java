package us.ihmc.scs2.session.foxglove;

import us.ihmc.log.LogTools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local stand-in for {@code persona_logger --stream --port 9090}.
 * <p>
 * Speaks {@code foxglove.sdk.v1} and publishes made-up {@code /robot_description},
 * {@code /joint_states}, {@code /imu}, and {@code /tf} so SCS2 can be exercised without a robot.
 * Lives in the session-logger <i>test</i> source set. Run {@link #main} from IntelliJ or:
 * {@code run-main :scs2-session-logger:scs2-session-logger-test us.ihmc.scs2.session.foxglove.FoxgloveLoggerEmulator}
 * then Session → Connect to remote session… → Type Foxglove → {@code 127.0.0.1:9090}.
 */
public class FoxgloveLoggerEmulator
{
   public static final int DEFAULT_PORT = 9090;
   public static final String SUBPROTOCOL = "foxglove.sdk.v1";
   private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
   private static final byte OPCODE_MESSAGE_DATA = 1;

   private static final String[] JOINT_NAMES = {"shoulder", "elbow"};

   private int port;
   private final AtomicBoolean running = new AtomicBoolean(false);
   private final ExecutorService executor = Executors.newCachedThreadPool(r ->
   {
      Thread thread = new Thread(r, "foxglove-emulator");
      thread.setDaemon(true);
      return thread;
   });
   private ServerSocket serverSocket;

   public FoxgloveLoggerEmulator()
   {
      this(DEFAULT_PORT);
   }

   public FoxgloveLoggerEmulator(int port)
   {
      this.port = port;
   }

   public int getPort()
   {
      return port;
   }

   public void start() throws IOException
   {
      if (!running.compareAndSet(false, true))
         return;
      serverSocket = new ServerSocket();
      serverSocket.setReuseAddress(true);
      serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
      port = serverSocket.getLocalPort();
      LogTools.info("Foxglove logger emulator listening on ws://127.0.0.1:{} ({})", port, SUBPROTOCOL);
      executor.execute(this::acceptLoop);
   }

   public void stop()
   {
      running.set(false);
      try
      {
         if (serverSocket != null)
            serverSocket.close();
      }
      catch (IOException ignored)
      {
      }
      executor.shutdownNow();
   }

   private void acceptLoop()
   {
      while (running.get())
      {
         try
         {
            Socket socket = serverSocket.accept();
            executor.execute(() -> handleClient(socket));
         }
         catch (IOException e)
         {
            if (running.get())
               LogTools.warn("Emulator accept failed: {}", e.getMessage());
         }
      }
   }

   private void handleClient(Socket socket)
   {
      try (socket)
      {
         InputStream in = socket.getInputStream();
         OutputStream out = socket.getOutputStream();
         if (!upgrade(in, out))
            return;

         sendText(out, "{\"op\":\"serverInfo\",\"name\":\"persona_logger emulator\",\"capabilities\":[],\"supportedEncodings\":[\"cdr\"]}");
         sendText(out, advertiseJson());

         Map<Integer, Long> subscriptionToChannel = new ConcurrentHashMap<>();
         AtomicBoolean subscribed = new AtomicBoolean(false);
         executor.execute(() -> publishLoop(out, subscriptionToChannel, subscribed, socket));

         while (running.get() && !socket.isClosed())
         {
            Frame frame = readFrame(in);
            if (frame == null)
               break;
            if (frame.opcode == 0x8)
               break;
            if (frame.opcode == 0x1)
               handleSubscribe(new String(frame.payload, StandardCharsets.UTF_8), subscriptionToChannel, subscribed);
         }
      }
      catch (Exception e)
      {
         if (running.get())
            LogTools.debug("Emulator client disconnected: {}", e.getMessage());
      }
   }

   private void handleSubscribe(String json, Map<Integer, Long> subscriptionToChannel, AtomicBoolean subscribed)
   {
      if (!json.contains("\"subscribe\""))
         return;
      int index = 0;
      while ((index = json.indexOf("\"id\"", index)) >= 0)
      {
         long subscriptionId = jsonLong(json.substring(index), "id");
         int channelKey = json.indexOf("\"channelId\"", index);
         long channelId = channelKey >= 0 ? jsonLong(json.substring(channelKey), "channelId") : -1;
         if (subscriptionId > 0 && channelId > 0)
            subscriptionToChannel.put((int) subscriptionId, channelId);
         index += 4;
      }
      subscribed.set(!subscriptionToChannel.isEmpty());
      LogTools.info("Emulator subscribed: {}", subscriptionToChannel);
   }

   private void publishLoop(OutputStream out, Map<Integer, Long> subscriptionToChannel, AtomicBoolean subscribed, Socket socket)
   {
      long startNs = System.nanoTime();
      while (running.get() && !socket.isClosed())
      {
         try
         {
            if (subscribed.get())
            {
               long nowNs = System.nanoTime();
               int sec = (int) ((nowNs - startNs) / 1_000_000_000L);
               long nanosec = (nowNs - startNs) % 1_000_000_000L;
               double t = (nowNs - startNs) * 1.0e-9;
               for (Map.Entry<Integer, Long> entry : subscriptionToChannel.entrySet())
               {
                  byte[] payload = payloadForChannel(entry.getValue(), sec, nanosec, t);
                  if (payload != null)
                     sendBinary(out, messageData(entry.getKey(), nowNs, payload));
               }
            }
            Thread.sleep(33);
         }
         catch (Exception e)
         {
            return;
         }
      }
   }

   private static byte[] payloadForChannel(long channelId, int sec, long nanosec, double t)
   {
      if (channelId == 1)
         return Ros2CdrWriter.stdString(demoUrdf());
      if (channelId == 2)
      {
         double[] q = {0.4 * Math.sin(t), 0.7 * Math.sin(1.7 * t)};
         double[] qd = {0.4 * Math.cos(t), 0.7 * 1.7 * Math.cos(1.7 * t)};
         return Ros2CdrWriter.jointState(sec, nanosec, JOINT_NAMES, q, qd, new double[] {0, 0});
      }
      if (channelId == 3)
         return Ros2CdrWriter.imu(sec, nanosec, 0.2 * Math.sin(t));
      if (channelId == 4)
         return Ros2CdrWriter.tf(sec, nanosec, 0.05 * Math.sin(0.3 * t), 0.15 * Math.sin(0.2 * t));
      return null;
   }

   private static byte[] messageData(int subscriptionId, long logTime, byte[] payload)
   {
      ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
      buffer.put(OPCODE_MESSAGE_DATA);
      buffer.putInt(subscriptionId);
      buffer.putLong(logTime);
      buffer.put(payload);
      return buffer.array();
   }

   private static String advertiseJson()
   {
      return "{\"op\":\"advertise\",\"channels\":["
             + channel(1, "/robot_description", "std_msgs::msg::String") + ","
             + channel(2, "/joint_states", "sensor_msgs::msg::JointState") + ","
             + channel(3, "/imu", "sensor_msgs::msg::Imu") + ","
             + channel(4, "/tf", "tf2_msgs::msg::TFMessage")
             + "]}";
   }

   private static String channel(int id, String topic, String schemaName)
   {
      return "{\"id\":" + id + ",\"topic\":\"" + topic + "\",\"encoding\":\"cdr\",\"schemaName\":\""
             + schemaName + "\",\"schemaEncoding\":\"ros2msg\",\"schema\":\"\"}";
   }

   static String demoUrdf()
   {
      return """
            <?xml version="1.0"?>
            <robot name="persona_emulator">
              <link name="base_link">
                <visual><geometry><cylinder length="0.12" radius="0.06"/></geometry></visual>
                <inertial><mass value="1"/><inertia ixx="0.01" ixy="0" ixz="0" iyy="0.01" iyz="0" izz="0.01"/></inertial>
              </link>
              <link name="upper">
                <visual><geometry><cylinder length="0.25" radius="0.03"/></geometry></visual>
                <inertial><mass value="0.4"/><inertia ixx="0.01" ixy="0" ixz="0" iyy="0.01" iyz="0" izz="0.01"/></inertial>
              </link>
              <link name="forearm">
                <visual><geometry><cylinder length="0.2" radius="0.025"/></geometry></visual>
                <inertial><mass value="0.3"/><inertia ixx="0.01" ixy="0" ixz="0" iyy="0.01" iyz="0" izz="0.01"/></inertial>
              </link>
              <joint name="shoulder" type="revolute">
                <parent link="base_link"/><child link="upper"/>
                <origin xyz="0 0 0.06" rpy="0 0 0"/>
                <axis xyz="0 1 0"/><limit lower="-2" upper="2" effort="10" velocity="5"/>
              </joint>
              <joint name="elbow" type="revolute">
                <parent link="upper"/><child link="forearm"/>
                <origin xyz="0 0 0.25" rpy="0 0 0"/>
                <axis xyz="0 1 0"/><limit lower="-2" upper="2" effort="10" velocity="5"/>
              </joint>
            </robot>
            """;
   }

   static long jsonLong(String json, String key)
   {
      String pattern = "\"" + key + "\"";
      int keyIndex = json.indexOf(pattern);
      if (keyIndex < 0)
         return -1;
      int colon = json.indexOf(':', keyIndex + pattern.length());
      if (colon < 0)
         return -1;
      int i = colon + 1;
      while (i < json.length() && Character.isWhitespace(json.charAt(i)))
         i++;
      int end = i;
      while (end < json.length() && Character.isDigit(json.charAt(end)))
         end++;
      if (end == i)
         return -1;
      return Long.parseLong(json.substring(i, end));
   }

   private static boolean upgrade(InputStream in, OutputStream out) throws Exception
   {
      String header = readHttpHeader(in);
      if (!header.contains("GET ") || !header.toLowerCase().contains("upgrade: websocket"))
         return false;
      String key = headerLine(header, "Sec-WebSocket-Key");
      if (key == null)
         return false;
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      String accept = Base64.getEncoder().encodeToString(sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
      String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n"
                        + "Sec-WebSocket-Protocol: " + SUBPROTOCOL + "\r\n\r\n";
      out.write(response.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return true;
   }

   private static String readHttpHeader(InputStream in) throws IOException
   {
      ByteArrayOutputStream header = new ByteArrayOutputStream();
      int prev = 0, count = 0;
      while (count < 8192)
      {
         int next = in.read();
         if (next < 0)
            break;
         header.write(next);
         count++;
         if (prev == '\r' && next == '\n' && header.size() >= 4)
         {
            byte[] bytes = header.toByteArray();
            if (bytes[bytes.length - 4] == '\r' && bytes[bytes.length - 3] == '\n')
               return header.toString(StandardCharsets.US_ASCII);
         }
         prev = next;
      }
      return header.toString(StandardCharsets.US_ASCII);
   }

   private static String headerLine(String header, String name)
   {
      for (String line : header.split("\r\n"))
      {
         if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
            return line.substring(name.length() + 1).trim();
      }
      return null;
   }

   private static void sendText(OutputStream out, String text) throws IOException
   {
      sendFrame(out, (byte) 0x1, text.getBytes(StandardCharsets.UTF_8));
   }

   private static void sendBinary(OutputStream out, byte[] payload) throws IOException
   {
      sendFrame(out, (byte) 0x2, payload);
   }

   private static synchronized void sendFrame(OutputStream out, byte opcode, byte[] payload) throws IOException
   {
      ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
      frame.write(0x80 | opcode);
      if (payload.length < 126)
         frame.write(payload.length);
      else if (payload.length <= 0xFFFF)
      {
         frame.write(126);
         frame.write((payload.length >> 8) & 0xFF);
         frame.write(payload.length & 0xFF);
      }
      else
      {
         frame.write(127);
         for (int i = 7; i >= 0; i--)
            frame.write((int) ((payload.length >> (8 * i)) & 0xFF));
      }
      frame.write(payload);
      out.write(frame.toByteArray());
      out.flush();
   }

   private static Frame readFrame(InputStream in) throws IOException
   {
      int b0 = in.read();
      int b1 = in.read();
      if (b0 < 0 || b1 < 0)
         return null;
      int opcode = b0 & 0x0F;
      boolean masked = (b1 & 0x80) != 0;
      long length = b1 & 0x7F;
      if (length == 126)
      {
         int hi = in.read(), lo = in.read();
         if (hi < 0 || lo < 0)
            return null;
         length = (hi << 8) | lo;
      }
      else if (length == 127)
      {
         length = 0;
         for (int i = 0; i < 8; i++)
         {
            int next = in.read();
            if (next < 0)
               return null;
            length = (length << 8) | next;
         }
      }
      byte[] mask = new byte[4];
      if (masked && in.readNBytes(mask, 0, 4) < 4)
         return null;
      if (length > 1_000_000)
         return null;
      byte[] payload = in.readNBytes((int) length);
      if (masked)
      {
         for (int i = 0; i < payload.length; i++)
            payload[i] ^= mask[i % 4];
      }
      return new Frame(opcode, payload);
   }

   private record Frame(int opcode, byte[] payload)
   {
   }

   public static void main(String[] args) throws Exception
   {
      int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
      FoxgloveLoggerEmulator emulator = new FoxgloveLoggerEmulator(port);
      emulator.start();
      LogTools.info("Connect SCS2: Session → Connect to remote session… → Foxglove → 127.0.0.1:{}", emulator.getPort());
      Thread.currentThread().join();
   }
}
