package us.ihmc.scs2.examples.simulations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import us.ihmc.scs2.SimulationConstructionSet2;
import us.ihmc.scs2.definition.configuration.WindowConfigurationDefinition;
import us.ihmc.scs2.definition.yoChart.YoChartConfigurationDefinition;
import us.ihmc.scs2.definition.yoChart.YoChartGroupConfigurationDefinition;
import us.ihmc.scs2.definition.yoChart.YoChartIdentifierDefinition;
import us.ihmc.scs2.sessionVisualizer.jfx.SecondaryWindowController;
import us.ihmc.scs2.sessionVisualizer.jfx.managers.SecondaryWindowManager;
import us.ihmc.scs2.sessionVisualizer.jfx.managers.SessionVisualizerToolkit;
import us.ihmc.yoVariables.registry.YoRegistry;
import us.ihmc.yoVariables.variable.YoDouble;

/**
 * A physics-free SCS2 simulation used as a demo and a profiling harness for a chart <i>playback</i>-rate
 * performance problem that grows with the number of chart <b>windows</b>.
 * <p>
 * The simulation runs no robot and no physics (it uses {@link SimulationConstructionSet2#doNothingPhysicsEngine()}).
 * It exposes a handful of sine-wave {@link YoDouble}s that are updated every tick, launches the Session
 * Visualizer, then opens a configurable number of <b>secondary chart windows</b> spread across the primary
 * screen, each holding several charts. It records a fixed slice of data into the buffer and then <b>plays that
 * buffer back</b>, measuring the achieved playback rate. The goal is to reproduce and investigate a
 * playback-rate regression where opening more chart windows slows playback even when the charts contain no
 * plotted series &mdash; the moving vertical time-bar cursor redrawn by every chart in every window is the
 * suspected cost. The {@code --empty} flag exists precisely for that investigation: it creates chart panels
 * with no data series so the only per-frame work is the time-bar cursor, isolating that cost from the cost of
 * actually rendering series data.
 * </p>
 * <p>
 * Chart windows are driven directly through the {@link SecondaryWindowManager} (rather than
 * {@code addYoChart(...)}, which broadcasts to <em>every</em> open window and so cannot target an individual
 * window). All window creation and population happens on the JavaFX Application Thread.
 * </p>
 * <p>
 * Two run modes:
 * </p>
 * <ul>
 * <li><b>Interactive</b> (no {@code --auto}): launches the GUI, fills the buffer, and starts looping playback
 * indefinitely; closing the window exits the JVM. A human watches the sine waves and playback smoothness.
 * Example:
 *
 * <pre>
 * SineWaveChartPerformanceDemo --windows 12 --charts 12 --vars 12
 * SineWaveChartPerformanceDemo --windows 6 --charts 6 --empty
 * </pre>
 *
 * </li>
 * <li><b>Auto</b> ({@code --auto S}): fills the buffer, starts playback, then samples how fast buffered
 * simulation time advances every 250&nbsp;ms for {@code S} seconds. The rate is measured from the
 * {@code "time"} echo {@link YoDouble}, which is recorded into the buffer every tick and restored from it
 * during playback, so its live value tracks the buffered time at the current playback index; sampling it
 * against wall-clock time gives the achieved playback rate with no dependence on any internal statistics
 * variable. Buffered-time deltas that go backward (playback wrapping around the looping buffer) are skipped.
 * Samples taken during the first {@code --warmup W} seconds (default 3.0) are discarded to exclude the initial
 * catch-up spikes and JIT warm-up; the summary (windows, charts/window, total charts, vars, empty?, fill
 * seconds, buffered-time advanced, wall span, the overall achieved rate, and mean/median/min/max of the valid
 * interval rates) is computed over the post-warm-up window only. It then shuts the session down and calls
 * {@link System#exit(int)}. Intended to be launched under a JVM profiler. Example:
 *
 * <pre>
 * SineWaveChartPerformanceDemo --windows 12 --charts 12 --vars 12 --auto 30 --warmup 3
 * SineWaveChartPerformanceDemo --windows 6 --charts 6 --empty --auto 30
 * </pre>
 *
 * </li>
 * </ul>
 */
public class SineWaveChartPerformanceDemo
{
   /** Base frequencies (Hz) cycled through for the sine waves so the user sees a variety of shapes. */
   private static final double[] FREQUENCIES_HZ = {0.25, 0.5, 1.0, 2.0, 3.0, 5.0};

   /**
    * Max grid dimensions per chart-group tab, enforced by {@code ChartTable2D.maxSize} (a
    * {@code ChartTable2DSize(6, 6)}). Requesting more rows or columns makes {@code loadDefinition} silently
    * fail, so we clamp the per-window grid to at most this many rows / columns.
    */
   private static final int MAX_CHART_ROWS = 6;
   private static final int MAX_CHART_COLUMNS = 6;

   public static void main(String[] args)
   {
      int numberOfWindows = 12;
      int chartsPerWindow = 12;
      int numberOfVars = 12;
      boolean emptyCharts = false;
      double fillSeconds = 8.0;
      double autoDurationSeconds = Double.NaN; // NaN => interactive mode
      double warmupSeconds = 3.0;

      for (int i = 0; i < args.length; i++)
      {
         String arg = args[i];
         switch (arg)
         {
            case "--windows":
               numberOfWindows = Integer.parseInt(args[++i]);
               break;
            case "--charts":
               chartsPerWindow = Integer.parseInt(args[++i]);
               break;
            case "--vars":
               numberOfVars = Integer.parseInt(args[++i]);
               break;
            case "--empty":
               emptyCharts = true;
               break;
            case "--fill":
               fillSeconds = Double.parseDouble(args[++i]);
               break;
            case "--auto":
               autoDurationSeconds = Double.parseDouble(args[++i]);
               break;
            case "--warmup":
               warmupSeconds = Double.parseDouble(args[++i]);
               break;
            default:
               System.err.println("Unknown argument: " + arg);
               System.err.println("Usage: SineWaveChartPerformanceDemo [--windows W] [--charts C] [--vars V] [--empty] "
                                   + "[--fill F] [--auto S] [--warmup W]");
               System.exit(1);
         }
      }

      numberOfWindows = Math.max(0, numberOfWindows);
      chartsPerWindow = Math.max(1, chartsPerWindow);
      numberOfVars = Math.max(1, numberOfVars);
      fillSeconds = Math.max(0.1, fillSeconds);
      boolean autoMode = !Double.isNaN(autoDurationSeconds);
      int totalCharts = numberOfWindows * chartsPerWindow;

      System.out.println("========================================================================");
      System.out.println("SineWaveChartPerformanceDemo (multi-window playback)");
      System.out.println("  mode            : " + (autoMode ? "AUTO (" + autoDurationSeconds + " s then exit)" : "INTERACTIVE (close window to exit)"));
      System.out.println("  chart windows   : " + numberOfWindows);
      System.out.println("  charts / window : " + chartsPerWindow + (emptyCharts ? " (EMPTY - time-bar only)" : ""));
      System.out.println("  total charts    : " + totalCharts);
      System.out.println("  sine vars       : " + numberOfVars);
      System.out.println("  fill (s)        : " + fillSeconds);
      System.out.println("========================================================================");

      // Physics-free session: no robot, no dynamics, just YoVariables changing over time.
      SimulationConstructionSet2 scs = new SimulationConstructionSet2(SimulationConstructionSet2.doNothingPhysicsEngine());

      // One buffer sample per simulation tick so the playback-rate math below is straightforward.
      scs.setBufferRecordTickPeriod(1);

      YoRegistry sineRegistry = new YoRegistry("sineWaves");
      scs.addRegistry(sineRegistry);

      YoDouble timeEcho = new YoDouble("time", sineRegistry);
      YoDouble[] sines = new YoDouble[numberOfVars];
      double[] frequencies = new double[numberOfVars];
      double[] amplitudes = new double[numberOfVars];
      double[] phases = new double[numberOfVars];

      for (int i = 0; i < numberOfVars; i++)
      {
         sines[i] = new YoDouble("sine" + i, sineRegistry);
         frequencies[i] = FREQUENCIES_HZ[i % FREQUENCIES_HZ.length];
         amplitudes[i] = 1.0 + 0.25 * i;
         phases[i] = Math.toRadians(30.0 * i);
      }

      scs.addBeforePhysicsCallback(time ->
      {
         timeEcho.set(time);
         for (int i = 0; i < sines.length; i++)
            sines[i].set(amplitudes[i] * Math.sin(2.0 * Math.PI * frequencies[i] * time + phases[i]));
      });

      // In auto mode we own shutdown, so disable JavaFX implicit exit; in interactive mode let the window
      // close exit the JVM. Do not keep the sim thread blocked here.
      scs.start(true, false, autoMode);
      scs.waitUntilVisualizerFullyUp();

      double dt = scs.getDT();

      // Open and populate the secondary chart windows on the JavaFX Application Thread.
      openChartWindows(scs, numberOfWindows, chartsPerWindow, numberOfVars, emptyCharts, sines);

      // Fill the buffer deterministically and as fast as possible (not real-time). simulateNow runs
      // synchronously on this thread and records each tick into the buffer.
      long fillTicks = Math.round(fillSeconds / dt);
      System.out.println("Filling buffer with " + fillTicks + " ticks (" + fillSeconds + " s at dt=" + dt + ") ...");
      scs.simulateNow(fillTicks);
      System.out.println("Buffer filled. Starting playback.");

      // Play the recorded buffer back at real-time rate; playback loops between the in/out points.
      scs.pause();
      scs.setPlaybackRealTimeRate(1.0);
      scs.play();

      if (autoMode)
         runAutoModeThenExit(scs,
                             timeEcho,
                             autoDurationSeconds,
                             warmupSeconds,
                             numberOfWindows,
                             chartsPerWindow,
                             numberOfVars,
                             emptyCharts,
                             fillSeconds);
      // else: interactive mode - main returns, the GUI stays up looping playback, and closing the window
      // exits the JVM.
   }

   /**
    * Opens {@code numberOfWindows} secondary chart windows, tiled across the primary screen, each carrying a
    * single tab whose chart grid holds {@code chartsPerWindow} charts. All JavaFX work is marshalled onto the
    * FX Application Thread and this method blocks until it completes.
    */
   private static void openChartWindows(SimulationConstructionSet2 scs,
                                        int numberOfWindows,
                                        int chartsPerWindow,
                                        int numberOfVars,
                                        boolean emptyCharts,
                                        YoDouble[] sines)
   {
      if (numberOfWindows == 0)
         return;

      SessionVisualizerToolkit viz = scs.getSessionVisualizerToolkit();
      SecondaryWindowManager windowMgr = viz.getWindowManager();
      Window primary = scs.getPrimaryGUIWindow();

      // Tile the windows across the primary screen so they do not stack on top of each other.
      Rectangle2D vb = Screen.getPrimary().getVisualBounds();
      int windowCols = (int) Math.ceil(Math.sqrt(numberOfWindows));
      int windowRows = (int) Math.ceil((double) numberOfWindows / windowCols);
      double windowWidth = vb.getWidth() / windowCols;
      double windowHeight = vb.getHeight() / windowRows;

      // Per-window chart grid: prefer a single column, spilling into more columns only when needed to keep the
      // row count within the 6x6 max grid.
      int chartCols = Math.min(MAX_CHART_COLUMNS, (int) Math.ceil((double) chartsPerWindow / MAX_CHART_ROWS));
      int chartRows = Math.min(MAX_CHART_ROWS, (int) Math.ceil((double) chartsPerWindow / chartCols));

      final int finalNumberOfWindows = numberOfWindows;
      final int finalChartsPerWindow = chartsPerWindow;
      final int finalChartCols = chartCols;
      final int finalChartRows = chartRows;

      CountDownLatch latch = new CountDownLatch(1);
      Throwable[] failure = new Throwable[1];

      Platform.runLater(() ->
      {
         try
         {
            for (int w = 0; w < finalNumberOfWindows; w++)
            {
               int windowCol = w % windowCols;
               int windowRow = w / windowCols;

               WindowConfigurationDefinition wc = new WindowConfigurationDefinition();
               wc.setShowing(true);
               wc.setMaximized(false);
               wc.setWidth(windowWidth);
               wc.setHeight(windowHeight);
               wc.setPositionX(vb.getMinX() + windowCol * windowWidth);
               wc.setPositionY(vb.getMinY() + windowRow * windowHeight);

               Stage stage = windowMgr.newChartWindow(primary, wc);
               if (stage == null)
                  throw new IllegalStateException("Failed to open chart window " + w);

               // Fresh session: the controllers list is populated in creation order, so index w is this window.
               SecondaryWindowController ctrl = windowMgr.getSecondaryWindowController(w);

               YoChartGroupConfigurationDefinition group = new YoChartGroupConfigurationDefinition();
               group.setName("Window" + w);
               group.setNumberOfRows(finalChartRows);
               group.setNumberOfColumns(finalChartCols);

               List<YoChartConfigurationDefinition> charts = new ArrayList<>();
               for (int c = 0; c < finalChartsPerWindow; c++)
               {
                  int row = c / finalChartCols;
                  int col = c % finalChartCols;
                  if (row >= finalChartRows || col >= finalChartCols)
                     break; // Clamp to the grid; should not happen given the sizing above.

                  List<String> vars = emptyCharts ? List.of()
                                                  : List.of(sines[(w * finalChartsPerWindow + c) % numberOfVars].getName());
                  YoChartConfigurationDefinition chart = new YoChartConfigurationDefinition(vars);
                  // The chart-table loader places each chart at its identifier's row/column and NPEs if the
                  // identifier is null, so it must be set explicitly.
                  chart.setIdentifier(new YoChartIdentifierDefinition(row, col));
                  charts.add(chart);
               }
               group.setChartConfigurations(charts);

               // insertionIndex -1 => append the tab (and its grid) to this window.
               ctrl.loadDefinition(group, -1);
            }
         }
         catch (Throwable t)
         {
            failure[0] = t;
         }
         finally
         {
            latch.countDown();
         }
      });

      try
      {
         latch.await();
      }
      catch (InterruptedException e)
      {
         Thread.currentThread().interrupt();
      }

      if (failure[0] != null)
         throw new RuntimeException("Failed to set up chart windows", failure[0]);
   }

   /**
    * Measures the achieved playback rate for {@code durationSeconds} by watching how fast buffered simulation
    * time advances, discards the samples taken during the first {@code warmupSeconds}, prints a summary over
    * the retained samples, then shuts down and exits the JVM.
    * <p>
    * {@code timeEcho} mirrors the simulation time into the buffer on every tick; during playback it is
    * restored from the buffer, so its live value equals the buffered simulation time at the current playback
    * index. Sampling it against wall-clock time therefore yields the achieved playback rate without relying on
    * any internal statistics variable.
    * </p>
    */
   private static void runAutoModeThenExit(SimulationConstructionSet2 scs,
                                           YoDouble timeEcho,
                                           double durationSeconds,
                                           double warmupSeconds,
                                           int numberOfWindows,
                                           int chartsPerWindow,
                                           int numberOfVars,
                                           boolean emptyCharts,
                                           double fillSeconds)
   {
      // If the warm-up window swallows the whole run, there would be nothing left to measure; fall back to
      // using every sample so the summary is still meaningful.
      boolean warmupCoversWholeRun = warmupSeconds >= durationSeconds;
      if (warmupCoversWholeRun)
         System.out.println("  WARNING: warmup (" + warmupSeconds + " s) >= duration (" + durationSeconds + " s); using all samples.");

      long sampleIntervalMillis = 250L;
      long startTimeMillis = System.currentTimeMillis();
      long warmupEndMillis = startTimeMillis + (long) (warmupSeconds * 1000.0);
      long endTimeMillis = startTimeMillis + (long) (durationSeconds * 1000.0);

      // Retained (post-warm-up) samples: wall-clock seconds and the buffered simulation time (timeEcho) at
      // each sample. Reading a double written by the playback thread is safe enough for this coarse timing.
      List<Double> retainedWallSec = new ArrayList<>();
      List<Double> retainedBufTime = new ArrayList<>();
      int discardedWarmupSamples = 0;

      while (System.currentTimeMillis() < endTimeMillis)
      {
         try
         {
            Thread.sleep(sampleIntervalMillis);
         }
         catch (InterruptedException e)
         {
            Thread.currentThread().interrupt();
            break;
         }

         boolean inWarmup = !warmupCoversWholeRun && System.currentTimeMillis() < warmupEndMillis;
         if (inWarmup)
         {
            discardedWarmupSamples++;
            continue;
         }

         double wallSec = System.nanoTime() * 1e-9;
         double bufTime = timeEcho.getValue();
         retainedWallSec.add(wallSec);
         retainedBufTime.add(bufTime);
      }

      int sampleCount = retainedWallSec.size();

      // Between each pair of consecutive retained samples, the buffered-time delta over the wall-clock delta
      // is an instantaneous playback rate. Playback loops over the buffer, so when it wraps the buffered time
      // jumps backward (delta < 0); those wrap intervals are skipped rather than counted as data points. The
      // overall rate sums only the positive buffered-time deltas over the whole retained wall span, which is
      // robust to the wraps.
      List<Double> intervalRates = new ArrayList<>();
      int wrapIntervalsSkipped = 0;
      double bufTimeAdvanced = 0.0;
      for (int i = 1; i < sampleCount; i++)
      {
         double bufDelta = retainedBufTime.get(i) - retainedBufTime.get(i - 1);
         double wallDelta = retainedWallSec.get(i) - retainedWallSec.get(i - 1);
         if (bufDelta < 0.0)
         {
            wrapIntervalsSkipped++;
            continue;
         }
         bufTimeAdvanced += bufDelta;
         if (wallDelta > 0.0)
            intervalRates.add(bufDelta / wallDelta);
      }

      double wallSpanSeconds = sampleCount >= 2 ? retainedWallSec.get(sampleCount - 1) - retainedWallSec.get(0) : 0.0;
      double overallRate = wallSpanSeconds > 0.0 ? bufTimeAdvanced / wallSpanSeconds : Double.NaN;

      System.out.println("======================== AUTO-MODE SUMMARY =============================");
      System.out.println("  chart windows               : " + numberOfWindows);
      System.out.println("  charts / window             : " + chartsPerWindow + (emptyCharts ? " (EMPTY - time-bar only)" : ""));
      System.out.println("  total charts                : " + (numberOfWindows * chartsPerWindow));
      System.out.println("  sine vars                   : " + numberOfVars);
      System.out.println("  empty charts                : " + emptyCharts);
      System.out.println("  fill (s)                    : " + fillSeconds);
      System.out.println("  duration (s)                : " + durationSeconds);
      System.out.println("  warmup (s)                  : " + warmupSeconds);
      System.out.println("  discarded (warmup samples)  : " + discardedWarmupSamples);
      System.out.println("  retained samples            : " + sampleCount);
      System.out.println("  wrap intervals skipped      : " + wrapIntervalsSkipped);
      System.out.println("  buffered time advanced (s)  : " + format(bufTimeAdvanced));
      System.out.println("  wall span (s)               : " + format(wallSpanSeconds));
      System.out.println("  overall achieved rate       : " + format(overallRate));
      System.out.println("  interval rate        : mean=" + format(mean(intervalRates)) + "  median=" + format(median(intervalRates))
                         + "  min=" + format(min(intervalRates)) + "  max=" + format(max(intervalRates)));
      System.out.println("========================================================================");

      if (bufTimeAdvanced <= 1.0e-9)
         System.out.println("  \033[1mWARNING: buffered time did not advance - playback may not be running\033[0m");

      // Immediate shutdown (no dialog). Remote/visualizer threads can linger, so force the JVM to exit.
      scs.shutdownSession();
      System.exit(0);
   }

   /** Median of the given samples (mean of the two middle values when the count is even). */
   private static double median(List<Double> samples)
   {
      if (samples.isEmpty())
         return Double.NaN;

      List<Double> sorted = new ArrayList<>(samples);
      sorted.sort(null);
      int middle = sorted.size() / 2;
      if (sorted.size() % 2 == 0)
         return 0.5 * (sorted.get(middle - 1) + sorted.get(middle));
      return sorted.get(middle);
   }

   private static double mean(List<Double> samples)
   {
      if (samples.isEmpty())
         return Double.NaN;
      double sum = 0.0;
      for (double value : samples)
         sum += value;
      return sum / samples.size();
   }

   private static double min(List<Double> samples)
   {
      double min = Double.POSITIVE_INFINITY;
      for (double value : samples)
         min = Math.min(min, value);
      return samples.isEmpty() ? Double.NaN : min;
   }

   private static double max(List<Double> samples)
   {
      double max = Double.NEGATIVE_INFINITY;
      for (double value : samples)
         max = Math.max(max, value);
      return samples.isEmpty() ? Double.NaN : max;
   }

   private static String format(double value)
   {
      if (Double.isNaN(value) || Double.isInfinite(value))
         return "n/a";
      return String.format("%.3f", value);
   }
}
