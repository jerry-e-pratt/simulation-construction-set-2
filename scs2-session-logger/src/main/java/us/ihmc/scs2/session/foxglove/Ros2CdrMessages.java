package us.ihmc.scs2.session.foxglove;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written ROS 2 message layouts for the {@code ros_log} types we map into SCS2.
 * Layout matches jros2 / Fast-CDR. See {@code https://github.com/ihmcrobotics/jros2}.
 */
public final class Ros2CdrMessages
{
   private Ros2CdrMessages()
   {
   }

   public static boolean isJointState(String schemaName)
   {
      return contains(schemaName, "JointState");
   }

   public static boolean isStdString(String schemaName)
   {
      return schemaName != null && (schemaName.equals("std_msgs::msg::String") || schemaName.equals("std_msgs/msg/String")
                                    || schemaName.endsWith("std_msgs::msg::dds_::String_") || schemaName.equals("std_msgs/String"));
   }

   public static boolean isImu(String schemaName)
   {
      return contains(schemaName, "Imu") && contains(schemaName, "sensor_msgs");
   }

   public static boolean isTfMessage(String schemaName)
   {
      return contains(schemaName, "TFMessage") || schemaName != null && schemaName.contains("tf2_msgs") && schemaName.contains("TF");
   }

   public static boolean isImage(String schemaName)
   {
      return schemaName != null && (schemaName.contains("sensor_msgs") && (schemaName.endsWith("Image") || schemaName.contains("::Image")));
   }

   private static boolean contains(String schemaName, String token)
   {
      return schemaName != null && schemaName.contains(token);
   }

   public static class JointState
   {
      public Ros2CdrBuffer.Header header = new Ros2CdrBuffer.Header();
      public String[] name = new String[0];
      public double[] position = new double[0];
      public double[] velocity = new double[0];
      public double[] effort = new double[0];

      public static JointState deserialize(byte[] cdr)
      {
         Ros2CdrBuffer buffer = new Ros2CdrBuffer(cdr);
         JointState message = new JointState();
         message.header = buffer.readHeader();
         message.name = buffer.readStringSequence();
         message.position = buffer.readFloat64Sequence();
         message.velocity = buffer.readFloat64Sequence();
         message.effort = buffer.readFloat64Sequence();
         return message;
      }
   }

   public static class StdString
   {
      public String data = "";

      public static StdString deserialize(byte[] cdr)
      {
         Ros2CdrBuffer buffer = new Ros2CdrBuffer(cdr);
         StdString message = new StdString();
         message.data = buffer.readString();
         return message;
      }
   }

   public static class Imu
   {
      public Ros2CdrBuffer.Header header = new Ros2CdrBuffer.Header();
      public double orientationX, orientationY, orientationZ, orientationW;
      public double angularVelocityX, angularVelocityY, angularVelocityZ;
      public double linearAccelerationX, linearAccelerationY, linearAccelerationZ;

      public static Imu deserialize(byte[] cdr)
      {
         Ros2CdrBuffer buffer = new Ros2CdrBuffer(cdr);
         Imu message = new Imu();
         message.header = buffer.readHeader();
         message.orientationX = buffer.readFloat64();
         message.orientationY = buffer.readFloat64();
         message.orientationZ = buffer.readFloat64();
         message.orientationW = buffer.readFloat64();
         buffer.readFloat64Sequence(); // orientation_covariance
         message.angularVelocityX = buffer.readFloat64();
         message.angularVelocityY = buffer.readFloat64();
         message.angularVelocityZ = buffer.readFloat64();
         buffer.readFloat64Sequence(); // angular_velocity_covariance
         message.linearAccelerationX = buffer.readFloat64();
         message.linearAccelerationY = buffer.readFloat64();
         message.linearAccelerationZ = buffer.readFloat64();
         buffer.readFloat64Sequence(); // linear_acceleration_covariance
         return message;
      }
   }

   public static class TransformStamped
   {
      public Ros2CdrBuffer.Header header = new Ros2CdrBuffer.Header();
      public String childFrameId = "";
      public double tx, ty, tz;
      public double qx, qy, qz, qw;
   }

   public static class TfMessage
   {
      public final List<TransformStamped> transforms = new ArrayList<>();

      public static TfMessage deserialize(byte[] cdr)
      {
         Ros2CdrBuffer buffer = new Ros2CdrBuffer(cdr);
         TfMessage message = new TfMessage();
         int length = (int) buffer.readUint32();
         for (int i = 0; i < length; i++)
         {
            TransformStamped transform = new TransformStamped();
            transform.header = buffer.readHeader();
            transform.childFrameId = buffer.readString();
            transform.tx = buffer.readFloat64();
            transform.ty = buffer.readFloat64();
            transform.tz = buffer.readFloat64();
            transform.qx = buffer.readFloat64();
            transform.qy = buffer.readFloat64();
            transform.qz = buffer.readFloat64();
            transform.qw = buffer.readFloat64();
            message.transforms.add(transform);
         }
         return message;
      }
   }

   public static class Image
   {
      public Ros2CdrBuffer.Header header = new Ros2CdrBuffer.Header();
      public int height;
      public int width;
      public String encoding = "";
      public byte[] data = new byte[0];

      public static Image deserialize(byte[] cdr)
      {
         Ros2CdrBuffer buffer = new Ros2CdrBuffer(cdr);
         Image message = new Image();
         message.header = buffer.readHeader();
         message.height = buffer.readInt32();
         message.width = buffer.readInt32();
         message.encoding = buffer.readString();
         buffer.readBoolean(); // is_bigendian
         buffer.readInt32(); // step
         message.data = buffer.readByteSequence();
         return message;
      }
   }
}
