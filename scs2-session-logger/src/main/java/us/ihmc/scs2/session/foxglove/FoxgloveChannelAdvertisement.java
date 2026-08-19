package us.ihmc.scs2.session.foxglove;

/**
 * One channel from a Foxglove {@code advertise} JSON message ({@code foxglove.sdk.v1}).
 */
public class FoxgloveChannelAdvertisement
{
   private final long channelId;
   private final String topic;
   private final String encoding;
   private final String schemaName;
   private final String schemaEncoding;
   private final String schema;

   public FoxgloveChannelAdvertisement(long channelId,
                                       String topic,
                                       String encoding,
                                       String schemaName,
                                       String schemaEncoding,
                                       String schema)
   {
      this.channelId = channelId;
      this.topic = topic;
      this.encoding = encoding != null ? encoding : "";
      this.schemaName = schemaName != null ? schemaName : "";
      this.schemaEncoding = schemaEncoding != null ? schemaEncoding : "";
      this.schema = schema != null ? schema : "";
   }

   public long getChannelId()
   {
      return channelId;
   }

   public String getTopic()
   {
      return topic;
   }

   public String getEncoding()
   {
      return encoding;
   }

   public String getSchemaName()
   {
      return schemaName;
   }

   public String getSchemaEncoding()
   {
      return schemaEncoding;
   }

   public String getSchema()
   {
      return schema;
   }

   public boolean isRosCdr()
   {
      return encoding.equalsIgnoreCase("cdr") || schemaEncoding.equalsIgnoreCase("ros2msg");
   }

   public boolean isProtobuf()
   {
      return encoding.equalsIgnoreCase("protobuf") || schemaEncoding.equalsIgnoreCase("protobuf");
   }
}
