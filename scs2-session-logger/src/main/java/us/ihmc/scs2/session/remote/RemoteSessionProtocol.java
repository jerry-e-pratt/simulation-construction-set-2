package us.ihmc.scs2.session.remote;

/**
 * Live remote-session transports listed in Active remote sessions.
 */
public enum RemoteSessionProtocol
{
   YOVARIABLE("YoVariable", 8008),
   FOXGLOVE("Foxglove", 9090);

   private final String displayName;
   private final int defaultPort;

   RemoteSessionProtocol(String displayName, int defaultPort)
   {
      this.displayName = displayName;
      this.defaultPort = defaultPort;
   }

   public String getDisplayName()
   {
      return displayName;
   }

   public int getDefaultPort()
   {
      return defaultPort;
   }

   @Override
   public String toString()
   {
      return displayName;
   }
}
