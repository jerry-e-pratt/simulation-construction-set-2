package us.ihmc.scs2.sessionVisualizer.jfx.session.remote;

import us.ihmc.log.LogTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists Foxglove static hosts separately from the YoVariable {@code StaticHostListLoader}.
 */
public final class FoxgloveHostListStore
{
   private FoxgloveHostListStore()
   {
   }

   public static Path storePath()
   {
      return Path.of(System.getProperty("user.home"), ".ihmc", "scs2FoxgloveHosts.txt");
   }

   public static List<HostPort> load()
   {
      Path path = storePath();
      List<HostPort> hosts = new ArrayList<>();
      if (!Files.isRegularFile(path))
         return hosts;
      try
      {
         for (String line : Files.readAllLines(path))
         {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
               continue;
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0)
               continue;
            hosts.add(new HostPort(trimmed.substring(0, colon), Integer.parseInt(trimmed.substring(colon + 1))));
         }
      }
      catch (IOException e)
      {
         LogTools.warn("Cannot load Foxglove host list: {}", e.getMessage());
      }
      return hosts;
   }

   public static void save(List<HostPort> hosts)
   {
      Path path = storePath();
      try
      {
         Files.createDirectories(path.getParent());
         List<String> lines = new ArrayList<>();
         for (HostPort host : hosts)
            lines.add(host.host + ":" + host.port);
         Files.write(path, lines);
      }
      catch (IOException e)
      {
         LogTools.warn("Cannot save Foxglove host list: {}", e.getMessage());
      }
   }

   public static final class HostPort
   {
      public final String host;
      public final int port;

      public HostPort(String host, int port)
      {
         this.host = host;
         this.port = port;
      }
   }
}
