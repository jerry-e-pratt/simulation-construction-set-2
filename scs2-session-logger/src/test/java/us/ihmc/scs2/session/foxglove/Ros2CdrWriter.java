package us.ihmc.scs2.session.foxglove;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Writes ROS 2 CDR little-endian payloads with encapsulation, for {@link FoxgloveLoggerEmulator}.
 */
public class Ros2CdrWriter
{
   private final ByteBuffer buffer;

   public Ros2CdrWriter(int capacity)
   {
      buffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
      buffer.put((byte) 0x00).put((byte) 0x01).put((byte) 0x00).put((byte) 0x00);
   }

   public void align(int alignment)
   {
      int remainder = buffer.position() % alignment;
      if (remainder != 0)
         buffer.position(buffer.position() + (alignment - remainder));
   }

   public void writeInt32(int value)
   {
      align(4);
      buffer.putInt(value);
   }

   public void writeUint32(long value)
   {
      writeInt32((int) value);
   }

   public void writeFloat64(double value)
   {
      align(8);
      buffer.putDouble(value);
   }

   public void writeString(String value)
   {
      byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
      align(4);
      buffer.putInt(bytes.length + 1);
      buffer.put(bytes);
      buffer.put((byte) 0);
   }

   public void writeHeader(int sec, long nanosec, String frameId)
   {
      writeInt32(sec);
      writeUint32(nanosec);
      writeString(frameId);
   }

   public void writeStringSequence(String... values)
   {
      align(4);
      buffer.putInt(values.length);
      for (String value : values)
         writeString(value);
   }

   public void writeFloat64Sequence(double... values)
   {
      align(4);
      buffer.putInt(values.length);
      for (double value : values)
         writeFloat64(value);
   }

   public byte[] toByteArray()
   {
      byte[] out = new byte[buffer.position()];
      System.arraycopy(buffer.array(), 0, out, 0, out.length);
      return out;
   }

   public static byte[] stdString(String data)
   {
      Ros2CdrWriter writer = new Ros2CdrWriter(32 + data.length());
      writer.writeString(data);
      return writer.toByteArray();
   }

   public static byte[] jointState(int sec, long nanosec, String[] names, double[] position, double[] velocity, double[] effort)
   {
      Ros2CdrWriter writer = new Ros2CdrWriter(512);
      writer.writeHeader(sec, nanosec, "base_link");
      writer.writeStringSequence(names);
      writer.writeFloat64Sequence(position);
      writer.writeFloat64Sequence(velocity);
      writer.writeFloat64Sequence(effort);
      return writer.toByteArray();
   }

   public static byte[] imu(int sec, long nanosec, double rollRate)
   {
      Ros2CdrWriter writer = new Ros2CdrWriter(512);
      writer.writeHeader(sec, nanosec, "imu_link");
      writer.writeFloat64(0);
      writer.writeFloat64(0);
      writer.writeFloat64(Math.sin(rollRate * 0.5));
      writer.writeFloat64(Math.cos(rollRate * 0.5));
      writer.writeFloat64Sequence(new double[9]);
      writer.writeFloat64(0);
      writer.writeFloat64(0);
      writer.writeFloat64(rollRate);
      writer.writeFloat64Sequence(new double[9]);
      writer.writeFloat64(0);
      writer.writeFloat64(0);
      writer.writeFloat64(9.81);
      writer.writeFloat64Sequence(new double[9]);
      return writer.toByteArray();
   }

   public static byte[] tf(int sec, long nanosec, double x, double yaw)
   {
      Ros2CdrWriter writer = new Ros2CdrWriter(256);
      writer.writeUint32(1);
      writer.writeHeader(sec, nanosec, "odom");
      writer.writeString("base_link");
      writer.writeFloat64(x);
      writer.writeFloat64(0);
      writer.writeFloat64(0);
      writer.writeFloat64(0);
      writer.writeFloat64(0);
      writer.writeFloat64(Math.sin(yaw * 0.5));
      writer.writeFloat64(Math.cos(yaw * 0.5));
      return writer.toByteArray();
   }
}
