package us.ihmc.scs2.session.foxglove;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import us.ihmc.log.LogTools;
import us.ihmc.yoVariables.registry.YoRegistry;
import us.ihmc.yoVariables.variable.YoDouble;
import us.ihmc.yoVariables.variable.YoInteger;
import us.ihmc.yoVariables.variable.YoLong;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a YoVariable tree from advertised Foxglove channels and applies incoming payloads.
 */
public class FoxgloveYoBindings
{
   public static final int MAX_JOINTS = 128;
   public static final int MAX_TRANSFORMS = 64;

   private final YoRegistry root;
   private final List<ChannelBinding> bindings = new ArrayList<>();
   private final Map<Integer, ChannelBinding> bySubscriptionId = new HashMap<>();

   public FoxgloveYoBindings(YoRegistry root)
   {
      this.root = root;
   }

   public void bindAdvertised(List<FoxgloveChannelAdvertisement> channels)
   {
      for (FoxgloveChannelAdvertisement channel : channels)
         bindings.add(createBinding(channel));
   }

   public void mapSubscriptionsInAdvertiseOrder()
   {
      bySubscriptionId.clear();
      for (int i = 0; i < bindings.size(); i++)
         bySubscriptionId.put(i + 1, bindings.get(i));
   }

   public List<ChannelBinding> getBindings()
   {
      return bindings;
   }

   public ChannelBinding bindingForSubscription(int subscriptionId)
   {
      return bySubscriptionId.get(subscriptionId);
   }

   private ChannelBinding createBinding(FoxgloveChannelAdvertisement channel)
   {
      String topic = sanitize(channel.getTopic());
      YoRegistry registry = new YoRegistry(topic);
      root.addChild(registry);
      YoLong stamp = new YoLong("logTime", registry);
      YoInteger count = new YoInteger("messageCount", registry);

      if (channel.isRosCdr() && Ros2CdrMessages.isJointState(channel.getSchemaName()))
         return new JointStateBinding(channel, registry, stamp, count);
      if (channel.isRosCdr() && Ros2CdrMessages.isStdString(channel.getSchemaName()))
         return new StringBinding(channel, registry, stamp, count);
      if (channel.isRosCdr() && Ros2CdrMessages.isImu(channel.getSchemaName()))
         return new ImuBinding(channel, registry, stamp, count);
      if (channel.isRosCdr() && Ros2CdrMessages.isTfMessage(channel.getSchemaName()))
         return new TfBinding(channel, registry, stamp, count);
      if (channel.isRosCdr() && Ros2CdrMessages.isImage(channel.getSchemaName()))
         return new RosImageBinding(channel, registry, stamp, count);
      if (channel.isProtobuf())
         return new ProtobufBinding(channel, registry, stamp, count);
      return new ChannelBinding(channel, registry, stamp, count);
   }

   private static String sanitize(String topic)
   {
      if (topic == null || topic.isEmpty())
         return "channel";
      String name = topic.startsWith("/") ? topic.substring(1) : topic;
      name = name.replace('/', '_').replace('.', '_');
      return name.isEmpty() ? "channel" : name;
   }

   public static class ChannelBinding
   {
      protected final FoxgloveChannelAdvertisement channel;
      protected final YoRegistry registry;
      protected final YoLong logTime;
      protected final YoInteger messageCount;
      protected byte[] latestPayload = new byte[0];
      protected volatile FoxgloveCameraFrame latestCameraFrame;

      ChannelBinding(FoxgloveChannelAdvertisement channel, YoRegistry registry, YoLong logTime, YoInteger messageCount)
      {
         this.channel = channel;
         this.registry = registry;
         this.logTime = logTime;
         this.messageCount = messageCount;
      }

      public FoxgloveChannelAdvertisement getChannel()
      {
         return channel;
      }

      public FoxgloveCameraFrame getLatestCameraFrame()
      {
         return latestCameraFrame;
      }

      public void apply(long logTimeNs, byte[] payload)
      {
         latestPayload = payload;
         logTime.set(logTimeNs);
         messageCount.add(1);
         applyPayload(payload);
      }

      protected void applyPayload(byte[] payload)
      {
      }
   }

   static class JointStateBinding extends ChannelBinding
   {
      private final YoInteger size;
      private final YoDouble[] position;
      private final YoDouble[] velocity;
      private final YoDouble[] effort;
      private volatile Ros2CdrMessages.JointState latest;

      JointStateBinding(FoxgloveChannelAdvertisement channel, YoRegistry registry, YoLong logTime, YoInteger messageCount)
      {
         super(channel, registry, logTime, messageCount);
         size = new YoInteger("size", registry);
         position = new YoDouble[MAX_JOINTS];
         velocity = new YoDouble[MAX_JOINTS];
         effort = new YoDouble[MAX_JOINTS];
         for (int i = 0; i < MAX_JOINTS; i++)
         {
            position[i] = new YoDouble("position_" + i, registry);
            velocity[i] = new YoDouble("velocity_" + i, registry);
            effort[i] = new YoDouble("effort_" + i, registry);
         }
      }

      @Override
      protected void applyPayload(byte[] payload)
      {
         try
         {
            latest = Ros2CdrMessages.JointState.deserialize(payload);
            int n = Math.min(MAX_JOINTS, latest.position.length);
            size.set(n);
            for (int i = 0; i < n; i++)
            {
               position[i].set(latest.position[i]);
               if (i < latest.velocity.length)
                  velocity[i].set(latest.velocity[i]);
               if (i < latest.effort.length)
                  effort[i].set(latest.effort[i]);
            }
         }
         catch (RuntimeException e)
         {
            LogTools.warn("Failed to decode JointState on {}: {}", channel.getTopic(), e.getMessage());
         }
      }

      public Ros2CdrMessages.JointState getLatest()
      {
         return latest;
      }
   }

   static class StringBinding extends ChannelBinding
   {
      private volatile String latest = "";

      StringBinding(FoxgloveChannelAdvertisement channel, YoRegistry registry, YoLong logTime, YoInteger messageCount)
      {
         super(channel, registry, logTime, messageCount);
         new YoInteger("length", registry);
      }

      @Override
      protected void applyPayload(byte[] payload)
      {
         try
         {
            latest = Ros2CdrMessages.StdString.deserialize(payload).data;
            YoInteger length = (YoInteger) registry.getVariable("length");
            if (length != null)
               length.set(latest.length());
         }
         catch (RuntimeException e)
         {
            LogTools.warn("Failed to decode std_msgs/String on {}: {}", channel.getTopic(), e.getMessage());
         }
      }

      public String getLatest()
      {
         return latest;
      }
   }

   static class ImuBinding extends ChannelBinding
   {
      private final YoDouble ox, oy, oz, ow, wx, wy, wz, ax, ay, az;

      ImuBinding(FoxgloveChannelAdvertisement channel, YoRegistry registry, YoLong logTime, YoInteger messageCount)
      {
         super(channel, registry, logTime, messageCount);
         ox = new YoDouble("orientationX", registry);
         oy = new YoDouble("orientationY", registry);
         oz = new YoDouble("orientationZ", registry);
         ow = new YoDouble("orientationW", registry);
         wx = new YoDouble("angularVelocityX", registry);
         wy = new YoDouble("angularVelocityY", registry);
         wz = new YoDouble("angularVelocityZ", registry);
         ax = new YoDouble("linearAccelerationX", registry);
         ay = new YoDouble("linearAccelerationY", registry);
         az = new YoDouble("linearAccelerationZ", registry);
      }

      @Override
      protected void applyPayload(byte[] payload)
      {
         try
         {
            Ros2CdrMessages.Imu imu = Ros2CdrMessages.Imu.deserialize(payload);
            ox.set(imu.orientationX);
            oy.set(imu.orientationY);
            oz.set(imu.orientationZ);
            ow.set(imu.orientationW);
            wx.set(imu.angularVelocityX);
            wy.set(imu.angularVelocityY);
            wz.set(imu.angularVelocityZ);
            ax.set(imu.linearAccelerationX);
            ay.set(imu.linearAccelerationY);
            az.set(imu.linearAccelerationZ);
         }
         catch (RuntimeException e)
         {
            LogTools.warn("Failed to decode Imu on {}: {}", channel.getTopic(), e.getMessage());
         }
      }
   }

   static class TfBinding extends ChannelBinding
   {
      private final YoInteger size;
      private final YoDouble[] tx, ty, tz;
      private volatile Ros2CdrMessages.TfMessage latest;

      TfBinding(FoxgloveChannelAdvertisement channel, YoRegistry registry, YoLong logTime, YoInteger messageCount)
      {
         super(channel, registry, logTime, messageCount);
         size = new YoInteger("size", registry);
         tx = new YoDouble[MAX_TRANSFORMS];
         ty = new YoDouble[MAX_TRANSFORMS];
         tz = new YoDouble[MAX_TRANSFORMS];
         for (int i = 0; i < MAX_TRANSFORMS; i++)
         {
            tx[i] = new YoDouble("t" + i + "_x", registry);
            ty[i] = new YoDouble("t" + i + "_y", registry);
            tz[i] = new YoDouble("t" + i + "_z", registry);
         }
      }

      @Override
      protected void applyPayload(byte[] payload)
      {
         try
         {
            latest = Ros2CdrMessages.TfMessage.deserialize(payload);
            int n = Math.min(MAX_TRANSFORMS, latest.transforms.size());
            size.set(n);
            for (int i = 0; i < n; i++)
            {
               tx[i].set(latest.transforms.get(i).tx);
               ty[i].set(latest.transforms.get(i).ty);
               tz[i].set(latest.transforms.get(i).tz);
            }
         }
         catch (RuntimeException e)
         {
            LogTools.warn("Failed to decode TFMessage on {}: {}", channel.getTopic(), e.getMessage());
         }
      }

      public Ros2CdrMessages.TfMessage getLatest()
      {
         return latest;
      }
   }

   static class RosImageBinding extends ChannelBinding
   {
      private final YoInteger width;
      private final YoInteger height;

      RosImageBinding(FoxgloveChannelAdvertisement channel, YoRegistry registry, YoLong logTime, YoInteger messageCount)
      {
         super(channel, registry, logTime, messageCount);
         width = new YoInteger("width", registry);
         height = new YoInteger("height", registry);
      }

      @Override
      protected void applyPayload(byte[] payload)
      {
         try
         {
            Ros2CdrMessages.Image image = Ros2CdrMessages.Image.deserialize(payload);
            width.set(image.width);
            height.set(image.height);
            latestCameraFrame = new FoxgloveCameraFrame(channel.getTopic(), image.width, image.height, image.encoding, image.data, false);
         }
         catch (RuntimeException e)
         {
            LogTools.warn("Failed to decode Image on {}: {}", channel.getTopic(), e.getMessage());
         }
      }
   }

   static class ProtobufBinding extends ChannelBinding
   {
      private final Descriptor descriptor;
      private final Map<String, YoDouble> doubles = new HashMap<>();
      private final Map<String, YoInteger> ints = new HashMap<>();
      private final Map<String, YoLong> longs = new HashMap<>();

      ProtobufBinding(FoxgloveChannelAdvertisement channel, YoRegistry registry, YoLong logTime, YoInteger messageCount)
      {
         super(channel, registry, logTime, messageCount);
         descriptor = parseDescriptor(channel);
         if (descriptor != null)
            addFields(descriptor, "", registry);
      }

      @Override
      protected void applyPayload(byte[] payload)
      {
         if (descriptor == null)
            return;
         try
         {
            DynamicMessage message = DynamicMessage.parseFrom(descriptor, payload);
            applyMessage(message, "");
            maybeCaptureCamera(message);
         }
         catch (InvalidProtocolBufferException e)
         {
            LogTools.warn("Failed to decode protobuf on {}: {}", channel.getTopic(), e.getMessage());
         }
      }

      private void addFields(Descriptor type, String prefix, YoRegistry registry)
      {
         for (FieldDescriptor field : type.getFields())
         {
            String name = prefix.isEmpty() ? field.getName() : prefix + "_" + field.getName();
            switch (field.getJavaType())
            {
               case DOUBLE, FLOAT -> doubles.put(name, new YoDouble(safeName(name), registry));
               case INT, BOOLEAN -> ints.put(name, new YoInteger(safeName(name), registry));
               case LONG -> longs.put(name, new YoLong(safeName(name), registry));
               case MESSAGE ->
               {
                  if (!field.isRepeated())
                     addFields(field.getMessageType(), name, registry);
               }
               default ->
               {
               }
            }
         }
      }

      private void applyMessage(DynamicMessage message, String prefix)
      {
         for (FieldDescriptor field : message.getDescriptorForType().getFields())
         {
            if (!field.isRepeated() && !message.hasField(field))
               continue;
            String name = prefix.isEmpty() ? field.getName() : prefix + "_" + field.getName();
            Object value = message.getField(field);
            switch (field.getJavaType())
            {
               case DOUBLE ->
               {
                  YoDouble yo = doubles.get(name);
                  if (yo != null)
                     yo.set((Double) value);
               }
               case FLOAT ->
               {
                  YoDouble yo = doubles.get(name);
                  if (yo != null)
                     yo.set(((Float) value).doubleValue());
               }
               case INT ->
               {
                  YoInteger yo = ints.get(name);
                  if (yo != null)
                     yo.set((Integer) value);
               }
               case BOOLEAN ->
               {
                  YoInteger yo = ints.get(name);
                  if (yo != null)
                     yo.set(Boolean.TRUE.equals(value) ? 1 : 0);
               }
               case LONG ->
               {
                  YoLong yo = longs.get(name);
                  if (yo != null)
                     yo.set((Long) value);
               }
               case MESSAGE ->
               {
                  if (!field.isRepeated() && value instanceof DynamicMessage nested)
                     applyMessage(nested, name);
               }
               default ->
               {
               }
            }
         }
      }

      private void maybeCaptureCamera(DynamicMessage message)
      {
         Integer width = findInt(message, "width");
         Integer height = findInt(message, "height");
         byte[] data = findBytes(message, "data");
         if (width == null || height == null || data == null || data.length == 0)
            return;
         String encoding = findString(message, "encoding");
         if (encoding == null)
            encoding = findString(message, "format");
         if (encoding == null)
            encoding = "jpeg";
         latestCameraFrame = new FoxgloveCameraFrame(channel.getTopic(), width, height, encoding, data, true);
      }

      private static Integer findInt(DynamicMessage message, String name)
      {
         FieldDescriptor field = message.getDescriptorForType().findFieldByName(name);
         if (field == null || !message.hasField(field))
            return null;
         Object value = message.getField(field);
         if (value instanceof Integer integer)
            return integer;
         if (value instanceof Long along)
            return along.intValue();
         return null;
      }

      private static String findString(DynamicMessage message, String name)
      {
         FieldDescriptor field = message.getDescriptorForType().findFieldByName(name);
         if (field == null || !message.hasField(field))
            return null;
         return String.valueOf(message.getField(field));
      }

      private static byte[] findBytes(DynamicMessage message, String name)
      {
         FieldDescriptor field = message.getDescriptorForType().findFieldByName(name);
         if (field == null || !message.hasField(field))
            return null;
         Object value = message.getField(field);
         if (value instanceof com.google.protobuf.ByteString bytes)
            return bytes.toByteArray();
         return null;
      }

      private static Descriptor parseDescriptor(FoxgloveChannelAdvertisement channel)
      {
         String schema = channel.getSchema();
         if (schema == null || schema.isEmpty())
            return null;
         try
         {
            byte[] bytes = decodeSchema(schema);
            FileDescriptorSet set = FileDescriptorSet.parseFrom(bytes);
            FileDescriptor[] empty = new FileDescriptor[0];
            FileDescriptor file = FileDescriptor.buildFrom(set.getFile(0), empty);
            if (file.getMessageTypes().isEmpty())
               return null;
            String schemaName = channel.getSchemaName();
            if (schemaName != null)
            {
               String simple = schemaName.contains(".") ? schemaName.substring(schemaName.lastIndexOf('.') + 1) : schemaName;
               for (Descriptor type : file.getMessageTypes())
               {
                  if (type.getName().equals(simple) || type.getFullName().equals(schemaName))
                     return type;
               }
            }
            return file.getMessageTypes().get(0);
         }
         catch (Exception e)
         {
            LogTools.debug("No protobuf descriptor for {}: {}", channel.getTopic(), e.getMessage());
            return null;
         }
      }

      private static byte[] decodeSchema(String schema)
      {
         try
         {
            return Base64.getDecoder().decode(schema);
         }
         catch (IllegalArgumentException ignored)
         {
            return schema.getBytes(StandardCharsets.ISO_8859_1);
         }
      }

      private static String safeName(String name)
      {
         return name.replace('.', '_').replace('/', '_');
      }
   }
}
