package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import us.ihmc.scs2.session.SessionPropertiesHelper;

/**
 * Opt-in per-reader trace of decoded and served video frames, used to diagnose jerky playback
 * (in particular suspected backward jumps in {@code currentVideoTimestamp}).
 * <p>
 * Enable with system property {@code -Dscs2.session.gui.logger.video.trace=true} or environment
 * variable {@code SCS2_GUI_LOGGER_VIDEO_TRACE=true}. The trace destination directory can be
 * overridden with {@code SCS2_GUI_LOGGER_VIDEO_TRACE_DIR} / {@code scs2.session.gui.logger.video.trace.dir};
 * the default is {@code java.io.tmpdir}.
 * <p>
 * Each enabled reader gets its own TSV file. {@code logDecode} is invoked from the background
 * decode thread after a successful decode; {@code logServe} is invoked from the FX thread when
 * the served frame's PTS changes. A regression in {@code currentVideoTimestamp} relative to the
 * previous decode (or serve) is flagged in the {@code event} column as {@code DECODE_BACK} /
 * {@code SERVE_BACK} so backward jumps can be located with a single grep.
 */
public final class VideoPlaybackTracer
{
   private static final String PROPERTY_KEY = "scs2.session.gui.logger.video.trace";
   private static final String ENV_VAR = "SCS2_GUI_LOGGER_VIDEO_TRACE";
   private static final String DIR_PROPERTY_KEY = "scs2.session.gui.logger.video.trace.dir";
   private static final String DIR_ENV_VAR = "SCS2_GUI_LOGGER_VIDEO_TRACE_DIR";

   private static final boolean ENABLED = SessionPropertiesHelper.loadBooleanPropertyOrEnvironment(PROPERTY_KEY, ENV_VAR, false);
   private static final Path OUTPUT_DIR = resolveOutputDir();

   private static final VideoPlaybackTracer DISABLED = new VideoPlaybackTracer(null);

   private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());
   private static final String HEADER = "wall_ns\tevent\tslot_id\tquery_robot_ts\tcurrent_robot_ts\tcurrent_video_ts\tcurrent_demuxer_ts\tdelta_video_ts\tdecode_ms\n";

   private final BufferedWriter writer;
   private long lastDecodedVideoTimestamp = Long.MIN_VALUE;
   private long lastServedVideoTimestamp = Long.MIN_VALUE;

   private VideoPlaybackTracer(BufferedWriter writer)
   {
      this.writer = writer;
   }

   /**
    * Returns a tracer for {@code readerName}. When tracing is disabled or the file cannot be opened,
    * returns a no-op instance whose {@code logDecode}/{@code logServe} return immediately.
    */
   public static VideoPlaybackTracer create(String readerName)
   {
      if (!ENABLED)
         return DISABLED;

      try
      {
         Files.createDirectories(OUTPUT_DIR);
         String safeName = readerName == null ? "video" : readerName.replaceAll("[^A-Za-z0-9_.-]", "_");
         String filename = "scs2-video-trace-" + safeName + "-" + FILE_TIMESTAMP_FORMAT.format(Instant.now()) + ".tsv";
         Path traceFile = OUTPUT_DIR.resolve(filename);
         BufferedWriter writer = Files.newBufferedWriter(traceFile, StandardCharsets.UTF_8);
         writer.write(HEADER);
         writer.flush();
         System.err.println("[VideoPlaybackTracer] Writing video frame trace for '" + readerName + "' to " + traceFile.toAbsolutePath());
         return new VideoPlaybackTracer(writer);
      }
      catch (IOException e)
      {
         System.err.println("[VideoPlaybackTracer] Failed to open trace file for '" + readerName + "': " + e.getMessage());
         return DISABLED;
      }
   }

   public boolean isEnabled()
   {
      return writer != null;
   }

   public synchronized void logDecode(Object slot, long queryRobotTimestamp, long currentRobotTimestamp, long currentVideoTimestamp,
                                      long currentDemuxerTimestamp, double decodeMillis)
   {
      if (writer == null)
         return;
      long delta = lastDecodedVideoTimestamp == Long.MIN_VALUE ? 0L : currentVideoTimestamp - lastDecodedVideoTimestamp;
      String event = lastDecodedVideoTimestamp != Long.MIN_VALUE && currentVideoTimestamp < lastDecodedVideoTimestamp ? "DECODE_BACK" : "DECODE";
      lastDecodedVideoTimestamp = currentVideoTimestamp;
      writeRow(event, slot, queryRobotTimestamp, currentRobotTimestamp, currentVideoTimestamp, currentDemuxerTimestamp, delta, decodeMillis);
   }

   public synchronized void logServe(Object slot, long queryRobotTimestamp, long currentRobotTimestamp, long currentVideoTimestamp,
                                     long currentDemuxerTimestamp)
   {
      if (writer == null)
         return;
      if (currentVideoTimestamp == lastServedVideoTimestamp)
         return;
      long delta = lastServedVideoTimestamp == Long.MIN_VALUE ? 0L : currentVideoTimestamp - lastServedVideoTimestamp;
      String event = lastServedVideoTimestamp != Long.MIN_VALUE && currentVideoTimestamp < lastServedVideoTimestamp ? "SERVE_BACK" : "SERVE";
      lastServedVideoTimestamp = currentVideoTimestamp;
      writeRow(event, slot, queryRobotTimestamp, currentRobotTimestamp, currentVideoTimestamp, currentDemuxerTimestamp, delta, Double.NaN);
   }

   private void writeRow(String event, Object slot, long queryRobotTimestamp, long currentRobotTimestamp, long currentVideoTimestamp,
                         long currentDemuxerTimestamp, long deltaVideoTimestamp, double decodeMillis)
   {
      try
      {
         writer.write(Long.toString(System.nanoTime()));
         writer.write('\t');
         writer.write(event);
         writer.write('\t');
         writer.write(slot == null ? "-" : Integer.toHexString(System.identityHashCode(slot)));
         writer.write('\t');
         writer.write(Long.toString(queryRobotTimestamp));
         writer.write('\t');
         writer.write(Long.toString(currentRobotTimestamp));
         writer.write('\t');
         writer.write(Long.toString(currentVideoTimestamp));
         writer.write('\t');
         writer.write(Long.toString(currentDemuxerTimestamp));
         writer.write('\t');
         writer.write(Long.toString(deltaVideoTimestamp));
         writer.write('\t');
         writer.write(Double.isNaN(decodeMillis) ? "" : String.format("%.3f", decodeMillis));
         writer.write('\n');
         writer.flush();
      }
      catch (IOException e)
      {
         System.err.println("[VideoPlaybackTracer] Write failed: " + e.getMessage());
      }
   }

   private static Path resolveOutputDir()
   {
      String configured = SessionPropertiesHelper.loadStringPropertyOrEnvironment(DIR_PROPERTY_KEY, DIR_ENV_VAR, null);
      if (configured != null && !configured.isEmpty())
         return Paths.get(configured);
      return Paths.get(System.getProperty("java.io.tmpdir", new File(".").getAbsolutePath()));
   }
}
