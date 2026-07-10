package us.ihmc.scs2.examples.simulations;

import java.util.ArrayList;
import java.util.List;

import us.ihmc.scs2.SimulationConstructionSet2;
import us.ihmc.scs2.sessionVisualizer.jfx.properties.YoDoubleProperty;
import us.ihmc.yoVariables.registry.YoRegistry;
import us.ihmc.yoVariables.variable.YoDouble;

/**
 * A physics-free SCS2 simulation used as a demo and a profiling harness for a chart playback-rate
 * performance problem.
 * <p>
 * The simulation runs no robot and no physics (it uses {@link SimulationConstructionSet2#doNothingPhysicsEngine()}).
 * It simply exposes a handful of sine-wave {@link YoDouble}s that are updated every tick, launches the
 * Session Visualizer, and programmatically creates a configurable number of chart panels. The goal is to
 * reproduce and investigate a playback-rate regression where adding more chart panels slows playback even
 * when the charts contain no plotted series &mdash; the moving vertical time-bar cursor drawn by every chart
 * is the suspected cost. The {@code --empty} flag exists precisely for that investigation: it creates chart
 * panels with no data series so the only per-frame work is the time-bar cursor, isolating that cost from the
 * cost of actually rendering series data.
 * </p>
 * <p>
 * Two run modes:
 * </p>
 * <ul>
 * <li><b>Interactive</b> (no {@code --auto}): launches the GUI and streams sine waves indefinitely; closing
 * the window exits the JVM. A human watches the sine waves and playback smoothness. Example:
 *
 * <pre>
 * SineWaveChartPerformanceDemo --charts 6 --vars 6
 * SineWaveChartPerformanceDemo --charts 20 --empty
 * </pre>
 *
 * </li>
 * <li><b>Auto</b> ({@code --auto S}): runs headlessly-driven for {@code S} seconds, sampling the achieved
 * real-time rate every 250&nbsp;ms, prints a summary (chart count, vars, empty?, mean/min real-time rate),
 * then shuts the session down and calls {@link System#exit(int)}. Intended to be launched under a JVM
 * profiler. Example:
 *
 * <pre>
 * SineWaveChartPerformanceDemo --charts 6 --vars 6 --auto 30
 * SineWaveChartPerformanceDemo --charts 20 --empty --auto 30
 * </pre>
 *
 * </li>
 * </ul>
 */
public class SineWaveChartPerformanceDemo
{
   /** Base frequencies (Hz) cycled through for the sine waves so the user sees a variety of shapes. */
   private static final double[] FREQUENCIES_HZ = {0.25, 0.5, 1.0, 2.0, 3.0, 5.0};

   public static void main(String[] args)
   {
      int numberOfCharts = 6;
      int numberOfVars = 6;
      boolean emptyCharts = false;
      double autoDurationSeconds = Double.NaN; // NaN => interactive mode

      for (int i = 0; i < args.length; i++)
      {
         String arg = args[i];
         switch (arg)
         {
            case "--charts":
               numberOfCharts = Integer.parseInt(args[++i]);
               break;
            case "--vars":
               numberOfVars = Integer.parseInt(args[++i]);
               break;
            case "--empty":
               emptyCharts = true;
               break;
            case "--auto":
               autoDurationSeconds = Double.parseDouble(args[++i]);
               break;
            default:
               System.err.println("Unknown argument: " + arg);
               System.err.println("Usage: SineWaveChartPerformanceDemo [--charts N] [--vars M] [--empty] [--auto S]");
               System.exit(1);
         }
      }

      numberOfCharts = Math.max(0, numberOfCharts);
      numberOfVars = Math.max(1, numberOfVars);
      boolean autoMode = !Double.isNaN(autoDurationSeconds);

      System.out.println("========================================================================");
      System.out.println("SineWaveChartPerformanceDemo");
      System.out.println("  mode        : " + (autoMode ? "AUTO (" + autoDurationSeconds + " s then exit)" : "INTERACTIVE (close window to exit)"));
      System.out.println("  chart panels: " + numberOfCharts + (emptyCharts ? " (EMPTY - time-bar only)" : ""));
      System.out.println("  sine vars   : " + numberOfVars);
      System.out.println("========================================================================");

      // Physics-free session: no robot, no dynamics, just YoVariables changing over time.
      SimulationConstructionSet2 scs = new SimulationConstructionSet2(SimulationConstructionSet2.doNothingPhysicsEngine());

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

      scs.setRealTimeRateSimulation(true);

      // In auto mode we own shutdown, so disable JavaFX implicit exit; in interactive mode let the window
      // close exit the JVM. Keep the simulation thread running in both cases.
      scs.start(true, false, autoMode);
      scs.waitUntilVisualizerFullyUp();

      // Create the chart panels in the main window's default chart group. NOTE: we must use the
      // default-group overload addYoChart(names) -> addYoChart(null, names). Passing a non-null group name
      // routes charts to a secondary chart window tab, which does not exist at startup, so the charts would
      // be silently dropped (see MainWindowController's YoChartListAdd listener, which only handles a null
      // group key). Each call adds one new chart (a new row) to the main window's visible chart dock;
      // multiple variable names in a single call overlay as series within that one chart.
      for (int chartIndex = 0; chartIndex < numberOfCharts; chartIndex++)
      {
         List<String> variableNames = new ArrayList<>();
         if (!emptyCharts)
         {
            // Distribute the sine variables across the charts, cycling if there are more charts than vars,
            // so the user visibly sees a sine wave in every chart.
            variableNames.add(sines[chartIndex % sines.length].getName());
         }
         // When emptyCharts is true, variableNames stays empty. An empty collection produces a
         // YoChartConfigurationDefinition with an empty yoVariables list (see
         // YoChartConfigurationDefinition(Collection)), i.e. a chart panel with no plotted series - exactly
         // the time-bar-only case we want to profile.
         scs.addYoChart(variableNames);
      }
      scs.requestChartsForceUpdate();

      // Start streaming data continuously in real time. simulate() (no-arg) requests an indefinite
      // simulation asynchronously on the already-running sim thread.
      scs.simulate();

      if (autoMode)
         runAutoModeThenExit(scs, autoDurationSeconds, numberOfCharts, numberOfVars, emptyCharts);
      // else: interactive mode - main returns, the GUI stays up, and closing the window exits the JVM.
   }

   /**
    * Samples the achieved real-time rate for {@code durationSeconds}, prints a summary, then shuts down and
    * exits the JVM.
    */
   private static void runAutoModeThenExit(SimulationConstructionSet2 scs,
                                           double durationSeconds,
                                           int numberOfCharts,
                                           int numberOfVars,
                                           boolean emptyCharts)
   {
      // runRealtimeRate is a YoDouble created in Session; the property gives thread-safe reads.
      YoDoubleProperty realTimeRateProperty = scs.newYoDoubleProperty("runRealtimeRate");

      long sampleIntervalMillis = 250L;
      long endTimeMillis = System.currentTimeMillis() + (long) (durationSeconds * 1000.0);

      double sum = 0.0;
      double min = Double.POSITIVE_INFINITY;
      double max = Double.NEGATIVE_INFINITY;
      int sampleCount = 0;

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

         double rate = realTimeRateProperty != null ? realTimeRateProperty.get() : Double.NaN;
         if (!Double.isNaN(rate))
         {
            sum += rate;
            min = Math.min(min, rate);
            max = Math.max(max, rate);
            sampleCount++;
         }
      }

      double mean = sampleCount > 0 ? sum / sampleCount : Double.NaN;

      System.out.println("======================== AUTO-MODE SUMMARY =============================");
      System.out.println("  chart panels     : " + numberOfCharts + (emptyCharts ? " (EMPTY - time-bar only)" : ""));
      System.out.println("  sine vars        : " + numberOfVars);
      System.out.println("  empty charts     : " + emptyCharts);
      System.out.println("  duration (s)     : " + durationSeconds);
      System.out.println("  samples          : " + sampleCount);
      System.out.println("  real-time rate   : mean=" + format(mean) + "  min=" + format(min) + "  max=" + format(max));
      System.out.println("========================================================================");

      // Immediate shutdown (no dialog). Remote/visualizer threads can linger, so force the JVM to exit.
      scs.shutdownSession();
      System.exit(0);
   }

   private static String format(double value)
   {
      if (Double.isNaN(value) || Double.isInfinite(value))
         return "n/a";
      return String.format("%.3f", value);
   }
}
