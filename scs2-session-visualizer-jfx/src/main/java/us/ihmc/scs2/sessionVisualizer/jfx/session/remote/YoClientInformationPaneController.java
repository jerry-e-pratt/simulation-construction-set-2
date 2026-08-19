package us.ihmc.scs2.sessionVisualizer.jfx.session.remote;

import java.util.function.Supplier;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import us.ihmc.commons.Conversions;
import us.ihmc.robotDataLogger.websocket.command.DataServerCommand;
import us.ihmc.scs2.session.Session;
import us.ihmc.scs2.session.foxglove.FoxgloveCameraFrame;
import us.ihmc.scs2.session.foxglove.FoxgloveRemoteSession;
import us.ihmc.scs2.session.remote.LoggerStatusUpdater;
import us.ihmc.scs2.session.remote.RemoteSession;
import us.ihmc.scs2.sessionVisualizer.jfx.session.SessionInfoController;
import us.ihmc.scs2.sessionVisualizer.jfx.tools.ObservedAnimationTimer;

public class YoClientInformationPaneController extends ObservedAnimationTimer implements SessionInfoController
{
   @FXML
   private AnchorPane mainPane;
   @FXML
   private Label delayLabel;
   @FXML
   private Label logDurationLabel;
   @FXML
   private Label cameraLabel;

   private final long refreshPeriod = Conversions.secondsToNanoseconds(0.1);
   private long lastRefreshTime = -1;

   private Supplier<String> delayValueSupplier;
   private Supplier<String> logDurationValueSupplier;
   private Supplier<String> cameraValueSupplier;

   private final ObjectProperty<Session> activeSessionProperty = new SimpleObjectProperty<>(this, "activeSession", null);

   public void initialize()
   {
      lastRefreshTime = -1;

      delayValueSupplier = () ->
      {
         Session activeSession = activeSessionProperty.get();
         if (activeSession instanceof RemoteSession remoteSession)
            return Conversions.nanosecondsToMilliseconds(remoteSession.getDelay()) + "ms";
         if (activeSession instanceof FoxgloveRemoteSession foxgloveSession)
            return Conversions.nanosecondsToMilliseconds(foxgloveSession.getDelay()) + "ms";
         return null;
      };

      logDurationValueSupplier = () ->
      {
         Session activeSession = activeSessionProperty.get();
         if (activeSession instanceof RemoteSession remoteSession)
         {
            LoggerStatusUpdater loggerStatusUpdater = remoteSession.getLoggerStatusUpdater();
            if (loggerStatusUpdater.isLogging())
               return loggerStatusUpdater.getCurrentLogDuration() + "sec";
            return "Logger offline";
         }
         if (activeSession instanceof FoxgloveRemoteSession)
            return "Foxglove live";
         return null;
      };

      cameraValueSupplier = () ->
      {
         Session activeSession = activeSessionProperty.get();
         if (activeSession instanceof RemoteSession remoteSession)
         {
            LoggerStatusUpdater loggerStatusUpdater = remoteSession.getLoggerStatusUpdater();
            return loggerStatusUpdater.isCameraRecording() ? "Recording" : "Off";
         }
         if (activeSession instanceof FoxgloveRemoteSession foxgloveSession)
         {
            int cameras = 0;
            for (FoxgloveCameraFrame frame : foxgloveSession.getLatestCameraFrames())
            {
               if (frame.getData().length > 0)
                  cameras++;
            }
            return cameras == 0 ? "No frames" : cameras + " live";
         }
         return null;
      };
   }

   public void setLiveSession(Session session)
   {
      activeSessionProperty.set(session);
   }

   public ObjectProperty<Session> activeSessionProperty()
   {
      return activeSessionProperty;
   }

   @Override
   public void handleImpl(long now)
   {
      if (lastRefreshTime != -1 && (now - lastRefreshTime) < refreshPeriod)
         return;

      updateLabel(delayLabel, delayValueSupplier, "N/D");
      updateLabel(logDurationLabel, logDurationValueSupplier, "N/D");
      updateLabel(cameraLabel, cameraValueSupplier, "N/D");
      lastRefreshTime = now;
   }

   @Override
   public Pane getMainPane()
   {
      return mainPane;
   }

   @FXML
   public void requestRestartLog()
   {
      if (activeSessionProperty.get() instanceof RemoteSession remoteSession)
         remoteSession.sendCommandToYoVariableServer(DataServerCommand.RESTART_LOG, 0);
   }

   private void updateLabel(Label label, Supplier<String> textSupplier, String defaultText)
   {
      if (textSupplier == null)
      {
         label.setText(defaultText);
         return;
      }

      String text = textSupplier.get();

      if (text == null)
      {
         label.setText(defaultText);
         return;
      }

      label.setText(text);
   }
}
