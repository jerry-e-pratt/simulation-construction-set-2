package us.ihmc.scs2.session.foxglove;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class Ros2CdrBufferTest
{
   @Test
   public void testStdStringWithEncapsulation()
   {
      byte[] payload = encodeCdrString("hello-urdf");
      Ros2CdrMessages.StdString message = Ros2CdrMessages.StdString.deserialize(payload);
      assertEquals("hello-urdf", message.data);
   }

   @Test
   public void testJointStateRoundTrip()
   {
      ByteBuffer buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
      buffer.put((byte) 0x00).put((byte) 0x01).put((byte) 0x00).put((byte) 0x00);
      writeHeader(buffer, 12, 34, "base");
      writeStringSequence(buffer, "j1", "j2");
      writeFloat64Sequence(buffer, 0.1, 0.2);
      writeFloat64Sequence(buffer, 1.0, 2.0);
      writeFloat64Sequence(buffer, 3.0, 4.0);
      byte[] payload = new byte[buffer.position()];
      buffer.flip();
      buffer.get(payload);

      Ros2CdrMessages.JointState jointState = Ros2CdrMessages.JointState.deserialize(payload);
      assertEquals("base", jointState.header.frameId);
      assertArrayEquals(new String[] {"j1", "j2"}, jointState.name);
      assertEquals(0.1, jointState.position[0], 1e-9);
      assertEquals(0.2, jointState.position[1], 1e-9);
      assertEquals(1.0, jointState.velocity[0], 1e-9);
      assertEquals(4.0, jointState.effort[1], 1e-9);
   }

   @Test
   public void testAdvertiseJson()
   {
      String json = """
            {"op":"advertise","channels":[{"id":7,"topic":"/joint_states","encoding":"cdr","schemaName":"sensor_msgs::msg::JointState","schemaEncoding":"ros2msg","schema":"std_msgs/Header header"}]}
            """;
      var channels = FoxgloveWsClient.parseAdvertise(json);
      assertEquals(1, channels.size());
      assertEquals(7, channels.get(0).getChannelId());
      assertEquals("/joint_states", channels.get(0).getTopic());
      assertTrue(channels.get(0).isRosCdr());
      assertTrue(Ros2CdrMessages.isJointState(channels.get(0).getSchemaName()));
   }

   private static byte[] encodeCdrString(String value)
   {
      ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
      buffer.put((byte) 0x00).put((byte) 0x01).put((byte) 0x00).put((byte) 0x00);
      writeString(buffer, value);
      byte[] payload = new byte[buffer.position()];
      buffer.flip();
      buffer.get(payload);
      return payload;
   }

   private static void writeHeader(ByteBuffer buffer, int sec, int nanosec, String frameId)
   {
      align(buffer, 4);
      buffer.putInt(sec);
      buffer.putInt(nanosec);
      writeString(buffer, frameId);
   }

   private static void writeStringSequence(ByteBuffer buffer, String... values)
   {
      align(buffer, 4);
      buffer.putInt(values.length);
      for (String value : values)
         writeString(buffer, value);
   }

   private static void writeFloat64Sequence(ByteBuffer buffer, double... values)
   {
      align(buffer, 4);
      buffer.putInt(values.length);
      for (double value : values)
      {
         align(buffer, 8);
         buffer.putDouble(value);
      }
   }

   private static void writeString(ByteBuffer buffer, String value)
   {
      align(buffer, 4);
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      buffer.putInt(bytes.length + 1);
      buffer.put(bytes);
      buffer.put((byte) 0);
   }

   private static void align(ByteBuffer buffer, int alignment)
   {
      int remainder = buffer.position() % alignment;
      if (remainder != 0)
         buffer.position(buffer.position() + (alignment - remainder));
   }
}
