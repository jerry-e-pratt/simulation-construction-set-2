package us.ihmc.scs2.session.foxglove;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ROS 2 CDR reader for Foxglove {@code ros_log} payloads.
 * <p>
 * Understands the 4-byte encapsulation header used on the wire, then reads humble-style
 * CDR little/big endian fields. Does not start DDS or depend on Fast-DDS transport.
 * Field layout matches jros2 / Fast-CDR ({@code https://github.com/ihmcrobotics/jros2}).
 */
public class Ros2CdrBuffer
{
   private final ByteBuffer buffer;

   public Ros2CdrBuffer(byte[] data)
   {
      this(ByteBuffer.wrap(data));
   }

   public Ros2CdrBuffer(ByteBuffer data)
   {
      buffer = data.slice();
      if (buffer.remaining() >= 4)
      {
         int b0 = buffer.get() & 0xFF;
         int b1 = buffer.get() & 0xFF;
         buffer.get();
         buffer.get();
         if (b0 == 0x00 && b1 == 0x01)
            buffer.order(ByteOrder.LITTLE_ENDIAN);
         else if (b0 == 0x00 && b1 == 0x00)
            buffer.order(ByteOrder.BIG_ENDIAN);
         else
         {
            buffer.position(0);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
         }
      }
      else
      {
         buffer.order(ByteOrder.LITTLE_ENDIAN);
      }
   }

   public void align(int alignment)
   {
      int position = buffer.position();
      int remainder = position % alignment;
      if (remainder != 0)
         buffer.position(position + (alignment - remainder));
   }

   public boolean hasRemaining()
   {
      return buffer.hasRemaining();
   }

   public int remaining()
   {
      return buffer.remaining();
   }

   public byte readByte()
   {
      return buffer.get();
   }

   public boolean readBoolean()
   {
      return buffer.get() != 0;
   }

   public int readInt16()
   {
      align(2);
      return buffer.getShort();
   }

   public int readUint16()
   {
      return readInt16() & 0xFFFF;
   }

   public int readInt32()
   {
      align(4);
      return buffer.getInt();
   }

   public long readUint32()
   {
      return readInt32() & 0xFFFFFFFFL;
   }

   public long readInt64()
   {
      align(8);
      return buffer.getLong();
   }

   public float readFloat32()
   {
      align(4);
      return buffer.getFloat();
   }

   public double readFloat64()
   {
      align(8);
      return buffer.getDouble();
   }

   public String readString()
   {
      align(4);
      int length = buffer.getInt();
      if (length <= 0)
         return "";
      byte[] bytes = new byte[length];
      buffer.get(bytes);
      int end = bytes[length - 1] == 0 ? length - 1 : length;
      return new String(bytes, 0, end, StandardCharsets.UTF_8);
   }

   public double[] readFloat64Sequence()
   {
      align(4);
      int length = buffer.getInt();
      double[] values = new double[Math.max(0, length)];
      for (int i = 0; i < values.length; i++)
         values[i] = readFloat64();
      return values;
   }

   public String[] readStringSequence()
   {
      align(4);
      int length = buffer.getInt();
      String[] values = new String[Math.max(0, length)];
      for (int i = 0; i < values.length; i++)
         values[i] = readString();
      return values;
   }

   public byte[] readByteSequence()
   {
      align(4);
      int length = buffer.getInt();
      byte[] values = new byte[Math.max(0, length)];
      if (values.length > 0)
         buffer.get(values);
      return values;
   }

   public Header readHeader()
   {
      Header header = new Header();
      header.stampSec = readInt32();
      header.stampNanosec = readUint32();
      header.frameId = readString();
      return header;
   }

   public static class Header
   {
      public int stampSec;
      public long stampNanosec;
      public String frameId = "";
   }
}
