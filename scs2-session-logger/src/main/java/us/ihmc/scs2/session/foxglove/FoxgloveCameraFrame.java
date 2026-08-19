package us.ihmc.scs2.session.foxglove;

/**
 * Latest decoded camera image from a Foxglove channel (ROS Image or persona_proto.CameraImage).
 */
public class FoxgloveCameraFrame
{
   private final String topic;
   private final int width;
   private final int height;
   private final String encoding;
   private final byte[] data;
   private final boolean compressedHint;

   public FoxgloveCameraFrame(String topic, int width, int height, String encoding, byte[] data, boolean compressedHint)
   {
      this.topic = topic;
      this.width = width;
      this.height = height;
      this.encoding = encoding != null ? encoding : "";
      this.data = data != null ? data : new byte[0];
      this.compressedHint = compressedHint;
   }

   public String getTopic()
   {
      return topic;
   }

   public int getWidth()
   {
      return width;
   }

   public int getHeight()
   {
      return height;
   }

   public String getEncoding()
   {
      return encoding;
   }

   public byte[] getData()
   {
      return data;
   }

   public boolean isCompressedHint()
   {
      return compressedHint || encoding.toLowerCase().contains("jpeg") || encoding.toLowerCase().contains("jpg")
             || encoding.toLowerCase().contains("png");
   }
}
