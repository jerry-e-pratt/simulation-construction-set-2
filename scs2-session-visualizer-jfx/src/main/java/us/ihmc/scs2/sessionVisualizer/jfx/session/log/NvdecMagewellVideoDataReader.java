package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import logger_msgs.Camera;
import us.ihmc.scs2.session.log.NvdecMagewellScrubber;

import java.io.File;
import java.io.IOException;

/**
 * {@link MagewellVideoDataReader} variant whose underlying decoder runs on the GPU through
 * {@link NvdecMagewellScrubber}. All rendering, timestamp handling, and frame buffering is inherited
 * from the superclass; only the decode backend differs. The constructor throws {@link IOException}
 * when NVDEC initialization fails so {@link MultiVideoDataReader} can fall back to software decode.
 */
public class NvdecMagewellVideoDataReader extends MagewellVideoDataReader
{
   public NvdecMagewellVideoDataReader(Camera camera, File dataDirectory, boolean hasTimeBase) throws IOException
   {
      super(new NvdecMagewellScrubber(camera, dataDirectory, hasTimeBase));
   }
}
