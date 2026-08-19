package us.ihmc.scs2.session.foxglove;

import us.ihmc.log.LogTools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Minimal {@code foxglove.sdk.v1} client: JSON control plane plus binary MessageData frames.
 */
public class FoxgloveWsClient implements WebSocket.Listener
{
   public static final String SUBPROTOCOL = "foxglove.sdk.v1";
   private static final byte OPCODE_MESSAGE_DATA = 1;

   private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
   private final List<FoxgloveChannelAdvertisement> channels = new CopyOnWriteArrayList<>();
   private final CompletableFuture<String> serverInfoName = new CompletableFuture<>();
   private final CompletableFuture<Void> advertised = new CompletableFuture<>();
   private final AtomicBoolean closed = new AtomicBoolean(false);
   private final StringBuilder textAssembler = new StringBuilder();
   private volatile WebSocket webSocket;
   private volatile String serverName = "";
   private volatile BiConsumer<Integer, FoxgloveBinaryMessage> messageListener;

   public void connect(String host, int port) throws Exception
   {
      URI uri = URI.create("ws://" + host + ":" + port);
      webSocket = httpClient.newWebSocketBuilder()
                            .subprotocols(SUBPROTOCOL)
                            .connectTimeout(Duration.ofSeconds(10))
                            .buildAsync(uri, this)
                            .get(15, TimeUnit.SECONDS);
      serverName = serverInfoName.get(10, TimeUnit.SECONDS);
      advertised.get(10, TimeUnit.SECONDS);
   }

   public String getServerName()
   {
      return serverName;
   }

   public List<FoxgloveChannelAdvertisement> getChannels()
   {
      return new ArrayList<>(channels);
   }

   public void setMessageListener(BiConsumer<Integer, FoxgloveBinaryMessage> messageListener)
   {
      this.messageListener = messageListener;
   }

   public void subscribeAll()
   {
      if (webSocket == null || channels.isEmpty())
         return;
      StringBuilder json = new StringBuilder("{\"op\":\"subscribe\",\"subscriptions\":[");
      for (int i = 0; i < channels.size(); i++)
      {
         if (i > 0)
            json.append(',');
         FoxgloveChannelAdvertisement channel = channels.get(i);
         json.append("{\"id\":").append(i + 1).append(",\"channelId\":").append(channel.getChannelId()).append('}');
      }
      json.append("]}");
      webSocket.sendText(json.toString(), true);
   }

   public void close()
   {
      if (!closed.compareAndSet(false, true))
         return;
      WebSocket socket = webSocket;
      if (socket != null)
      {
         try
         {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
         }
         catch (Exception ignored)
         {
         }
      }
   }

   public boolean isOpen()
   {
      return webSocket != null && !closed.get();
   }

   @Override
   public void onOpen(WebSocket webSocket)
   {
      webSocket.request(1);
   }

   @Override
   public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
   {
      textAssembler.append(data);
      if (last)
      {
         handleJson(textAssembler.toString());
         textAssembler.setLength(0);
      }
      webSocket.request(1);
      return null;
   }

   @Override
   public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
   {
      handleBinary(data);
      webSocket.request(1);
      return null;
   }

   @Override
   public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
   {
      closed.set(true);
      return null;
   }

   @Override
   public void onError(WebSocket webSocket, Throwable error)
   {
      LogTools.error("Foxglove WebSocket error: {}", error.getMessage());
      closed.set(true);
      serverInfoName.completeExceptionally(error);
      advertised.completeExceptionally(error);
   }

   private void handleJson(String json)
   {
      String op = jsonString(json, "op");
      if ("serverInfo".equals(op))
      {
         String name = jsonString(json, "name");
         serverName = name != null ? name : "";
         serverInfoName.complete(serverName);
      }
      else if ("advertise".equals(op))
      {
         channels.addAll(parseAdvertise(json));
         advertised.complete(null);
      }
   }

   private void handleBinary(ByteBuffer data)
   {
      ByteBuffer buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      if (buffer.remaining() < 13)
         return;
      byte opcode = buffer.get();
      if (opcode != OPCODE_MESSAGE_DATA)
         return;
      int subscriptionId = buffer.getInt();
      long logTime = buffer.getLong();
      byte[] payload = new byte[buffer.remaining()];
      buffer.get(payload);
      BiConsumer<Integer, FoxgloveBinaryMessage> listener = messageListener;
      if (listener != null)
         listener.accept(subscriptionId, new FoxgloveBinaryMessage(subscriptionId, logTime, payload));
   }

   static List<FoxgloveChannelAdvertisement> parseAdvertise(String json)
   {
      List<FoxgloveChannelAdvertisement> parsed = new ArrayList<>();
      int channelsIndex = json.indexOf("\"channels\"");
      if (channelsIndex < 0)
         return parsed;
      int arrayStart = json.indexOf('[', channelsIndex);
      if (arrayStart < 0)
         return parsed;
      int depth = 0;
      int objectStart = -1;
      for (int i = arrayStart; i < json.length(); i++)
      {
         char c = json.charAt(i);
         if (c == '{')
         {
            if (depth == 0)
               objectStart = i;
            depth++;
         }
         else if (c == '}')
         {
            depth--;
            if (depth == 0 && objectStart >= 0)
            {
               String object = json.substring(objectStart, i + 1);
               long id = jsonLong(object, "id");
               parsed.add(new FoxgloveChannelAdvertisement(id,
                                                           jsonString(object, "topic"),
                                                           jsonString(object, "encoding"),
                                                           jsonString(object, "schemaName"),
                                                           jsonString(object, "schemaEncoding"),
                                                           jsonString(object, "schema")));
               objectStart = -1;
            }
         }
         else if (c == ']' && depth == 0)
            break;
      }
      return parsed;
   }

   static String jsonString(String json, String key)
   {
      String pattern = "\"" + key + "\"";
      int keyIndex = json.indexOf(pattern);
      if (keyIndex < 0)
         return null;
      int colon = json.indexOf(':', keyIndex + pattern.length());
      if (colon < 0)
         return null;
      int i = colon + 1;
      while (i < json.length() && Character.isWhitespace(json.charAt(i)))
         i++;
      if (i >= json.length() || json.charAt(i) != '"')
         return null;
      StringBuilder value = new StringBuilder();
      boolean escape = false;
      for (int j = i + 1; j < json.length(); j++)
      {
         char c = json.charAt(j);
         if (escape)
         {
            if (c == 'n')
               value.append('\n');
            else if (c == 't')
               value.append('\t');
            else
               value.append(c);
            escape = false;
         }
         else if (c == '\\')
            escape = true;
         else if (c == '"')
            return value.toString();
         else
            value.append(c);
      }
      return value.toString();
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
      while (end < json.length() && (Character.isDigit(json.charAt(end))))
         end++;
      if (end == i)
         return -1;
      return Long.parseLong(json.substring(i, end));
   }

   public static class FoxgloveBinaryMessage
   {
      public final int subscriptionId;
      public final long logTime;
      public final byte[] payload;

      public FoxgloveBinaryMessage(int subscriptionId, long logTime, byte[] payload)
      {
         this.subscriptionId = subscriptionId;
         this.logTime = logTime;
         this.payload = payload;
      }
   }
}
