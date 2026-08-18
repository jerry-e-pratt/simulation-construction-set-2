package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import us.ihmc.scs2.session.SessionPropertiesHelper;
import us.ihmc.scs2.sessionVisualizer.jfx.SessionVisualizerIOTools;
import us.ihmc.scs2.sessionVisualizer.jfx.session.log.VideoFrameBufferPool.FrameBuffer;
import us.ihmc.scs2.sessionVisualizer.jfx.tools.JavaFXMissingTools;

public class VideoViewer
{

   private static final boolean LOGGER_VIDEO_DEBUG = SessionPropertiesHelper.loadBooleanPropertyOrEnvironment("scs2.session.gui.logger.video.debug",
                                                                                                              "SCS2_GUI_LOGGER_VIDEO_DEBUG",
                                                                                                              false);
   private static final double THUMBNAIL_HIGHLIGHT_SCALE = 1.05;

   private final ImageView thumbnail = new ImageView();
   private final StackPane thumbnailContainer = new StackPane(thumbnail);
   private final ImageView videoView = new ImageView();
   private final Label queryRobotTimestampLabel = new Label();
   private final Label currentDemuxerTimestampLabel = new Label();
   private final Label currentVideoTimestampLabel = new Label();
   private final Label currentRobotTimestampLabel = new Label();

   // Always-on performance overlay: served fps is computed from currentVideoTimestamp transitions
   // observed by the FX-thread update() loop, decode rate / time / source fps are forwarded from
   // the reader. Lets the user distinguish session-tick bottlenecks from decode-pipeline bottlenecks.
   private final Label perfOverlayLabel = new Label();
   private static final long SERVED_FPS_WINDOW_NANOS = 1_000_000_000L;
   private long lastSeenVideoTimestamp = Long.MIN_VALUE;
   private long servedWindowStartNanos = 0L;
   private int servedFramesInWindow = 0;
   private double servedFpsHz = Double.NaN;

   private final BooleanProperty updateVideoView = new SimpleBooleanProperty(this, "updateVideoView", false);
   private final ObjectProperty<Stage> videoWindowProperty = new SimpleObjectProperty<>(this, "videoWindow", null);
   private final VideoDataReader reader;
   private final double defaultThumbnailSize;

   private final ObjectProperty<Pane> imageViewRootPane = new SimpleObjectProperty<>(this, "imageViewRootPane", null);

   // FX-side ref-count holders for pooled FrameBuffers. We keep the two most-recently-served buffers alive: when a new
   // buffer arrives at update() #N+2, we release the one served at #N -- by then the JavaFX render pulse for #N has
   // long since completed, so the writer can safely recycle the buffer without tearing the displayed frame. Null for
   // readers that don't go through VideoFrameBufferPool (BlackMagic, ZED), in which case no retain/release happens.
   private FrameBuffer fxHeldCurrent = null;
   private FrameBuffer fxHeldPrevious = null;

   public VideoViewer(Window owner, VideoDataReader reader, double defaultThumbnailSize)
   {
      this.reader = reader;
      this.defaultThumbnailSize = defaultThumbnailSize;
      thumbnail.setPreserveRatio(true);
      videoView.setPreserveRatio(true);
      thumbnail.setFitWidth(defaultThumbnailSize);
      thumbnail.setOnMouseEntered(e ->
                                  {
                                     Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(0.1),
                                                                                   new KeyValue(thumbnail.fitWidthProperty(),
                                                                                                THUMBNAIL_HIGHLIGHT_SCALE * defaultThumbnailSize,
                                                                                                Interpolator.EASE_BOTH)));
                                     timeline.playFromStart();
                                  });
      thumbnail.setOnMouseExited(e ->
                                 {
                                    Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(0.1),
                                                                                  new KeyValue(thumbnail.fitWidthProperty(),
                                                                                               defaultThumbnailSize,
                                                                                               Interpolator.EASE_BOTH)));
                                    timeline.playFromStart();
                                 });

      thumbnail.addEventHandler(MouseEvent.MOUSE_CLICKED, e ->
      {
         if (e.getClickCount() != 2)
            return;

         videoView.setImage(thumbnail.getImage());

         Stage stage;

         if (videoWindowProperty.get() != null)
         {
            stage = videoWindowProperty.get();
         }
         else
         {
            stage = new Stage();
            AnchorPane anchorPane = new AnchorPane();
            Pane root = createImageViewPane(videoView);
            anchorPane.getChildren().add(root);
            JavaFXMissingTools.setAnchorConstraints(root, 0);
            imageViewRootPane.set(root);

            setupVideoStatistics(anchorPane);
            setupPerformanceOverlay(anchorPane);

            videoWindowProperty.set(stage);
            stage.getIcons().add(SessionVisualizerIOTools.LOG_SESSION_IMAGE);
            stage.setTitle(reader.getName());
            owner.setOnHiding(e2 -> stage.close());
            Scene scene = new Scene(anchorPane);
            stage.setScene(scene);
            updateVideoView.bind(stage.showingProperty());
         }

         Screen screen = Screen.getScreensForRectangle(e.getScreenX(), e.getScreenY(), 1, 1).get(0);
         Rectangle2D visualBounds = screen.getVisualBounds();
         double width = 0.5 * visualBounds.getWidth();
         double height = 0.5 * visualBounds.getHeight();
         double x = visualBounds.getMinX() + 0.5 * (visualBounds.getWidth() - width);
         double y = visualBounds.getMinY() + 0.5 * (visualBounds.getHeight() - height);
         stage.setX(x);
         stage.setY(y);
         stage.setWidth(width);
         stage.setHeight(height);

         stage.toFront();
         stage.show();
      });
   }

   private void setupVideoStatistics(AnchorPane anchorPane)
   {
      Label videoStatisticTitle = new Label("Video Statistics");
      videoStatisticTitle.setFont(Font.font("Arial", FontWeight.BOLD, 14));

      Background generalBackground = new Background(new BackgroundFill(Color.WHITE, CornerRadii.EMPTY, Insets.EMPTY));
      Border noRightBorder = new Border(new BorderStroke(Color.BLACK,
                                                         null,
                                                         Color.BLACK,
                                                         Color.BLACK,
                                                         BorderStrokeStyle.SOLID,
                                                         BorderStrokeStyle.NONE,
                                                         BorderStrokeStyle.SOLID,
                                                         BorderStrokeStyle.SOLID,
                                                         CornerRadii.EMPTY,
                                                         BorderWidths.DEFAULT,
                                                         Insets.EMPTY));
      Border noLeftBorder = new Border(new BorderStroke(Color.BLACK,
                                                        Color.BLACK,
                                                        Color.BLACK,
                                                        null,
                                                        BorderStrokeStyle.SOLID,
                                                        BorderStrokeStyle.SOLID,
                                                        BorderStrokeStyle.SOLID,
                                                        BorderStrokeStyle.NONE,
                                                        CornerRadii.EMPTY,
                                                        BorderWidths.DEFAULT,
                                                        Insets.EMPTY));

      Border generalBorder = new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT));
      Insets textInsets = new Insets(0, 2, 0, 2);

      if (LOGGER_VIDEO_DEBUG)
      {
         VBox videoStatisticBox = new VBox(videoStatisticTitle);
         videoStatisticBox.setAlignment(Pos.CENTER);
         videoStatisticBox.setBackground(generalBackground);
         videoStatisticBox.setBorder(generalBorder);

         VBox videoStatisticLabels = new VBox(new Label("queryRobotTimestamp"),
                                              new Label("currentRobotTimestamp"),
                                              new Label("currentVideoTimestamp"),
                                              new Label("currentDemuxerTimestamp"));
         videoStatisticLabels.setBackground(generalBackground);
         videoStatisticLabels.setBorder(noRightBorder);
         videoStatisticLabels.setPadding(textInsets);

         VBox videoStatistics = new VBox(queryRobotTimestampLabel, currentRobotTimestampLabel, currentVideoTimestampLabel, currentDemuxerTimestampLabel);
         videoStatistics.setBackground(generalBackground);
         videoStatistics.setBorder(noLeftBorder);
         videoStatistics.setPadding(textInsets);

         HBox labelsContainer = new HBox(0, videoStatisticLabels, videoStatistics);
         VBox videoStatisticsDisplay = new VBox(0, videoStatisticBox, labelsContainer);
         anchorPane.getChildren().add(videoStatisticsDisplay);
         AnchorPane.setLeftAnchor(videoStatisticsDisplay, 0.0);
         AnchorPane.setBottomAnchor(videoStatisticsDisplay, 0.0);
      }
   }

   private void setupPerformanceOverlay(AnchorPane anchorPane)
   {
      perfOverlayLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
      perfOverlayLabel.setTextFill(Color.LIME);
      perfOverlayLabel.setBackground(new Background(new BackgroundFill(Color.color(0, 0, 0, 0.55), CornerRadii.EMPTY, Insets.EMPTY)));
      perfOverlayLabel.setPadding(new Insets(2, 6, 2, 6));
      perfOverlayLabel.setText("served --  decode -- @ -- ms  source -- fps");
      anchorPane.getChildren().add(perfOverlayLabel);
      AnchorPane.setTopAnchor(perfOverlayLabel, 4.0);
      AnchorPane.setRightAnchor(perfOverlayLabel, 4.0);
   }

   private void updateServedFps(long currentVideoTimestamp)
   {
      if (currentVideoTimestamp == lastSeenVideoTimestamp)
         return;
      long nowNanos = System.nanoTime();
      if (servedWindowStartNanos == 0L)
         servedWindowStartNanos = nowNanos;
      servedFramesInWindow++;
      long elapsedNanos = nowNanos - servedWindowStartNanos;
      if (elapsedNanos >= SERVED_FPS_WINDOW_NANOS)
      {
         servedFpsHz = servedFramesInWindow * 1_000_000_000.0 / elapsedNanos;
         servedWindowStartNanos = nowNanos;
         servedFramesInWindow = 0;
      }
      lastSeenVideoTimestamp = currentVideoTimestamp;
   }

   private static String formatFps(double hz)
   {
      return Double.isNaN(hz) ? "--" : String.format("%.1f", hz);
   }

   private static String formatMillis(double ms)
   {
      return Double.isNaN(ms) ? "--" : String.format("%.1f", ms);
   }

   private static Pane createImageViewPane(ImageView imageView)
   {
      return new Pane(imageView)
      {
         @Override
         protected void layoutChildren()
         {
            Image image = imageView.getImage();
            if (image == null)
               return;
            double imageRatio = image.getWidth() / image.getHeight();
            Rectangle2D imageViewport = imageView.getViewport();
            if (imageViewport != null)
               imageRatio = imageViewport.getWidth() / imageViewport.getHeight();
            double paneWidth = getWidth() - getPadding().getLeft() - getPadding().getRight();
            double paneHeight = getHeight() - getPadding().getTop() - getPadding().getBottom();
            double width = Math.min(paneWidth, paneHeight * imageRatio);
            double height = width / imageRatio;

            double x = 0.5 * (paneWidth - width);
            double y = 0.5 * (paneHeight - height);
            imageView.setFitWidth(width);
            imageView.setX(x + getPadding().getLeft());
            imageView.setY(y + getPadding().getTop());
         }
      };
   }

   public void update()
   {
      FrameData currentFrameData = reader.pollCurrentFrame();

      if (currentFrameData.frame == null)
         return;

      // Hold the new pooled buffer alive past the JavaFX render pulse: retain it now and release the previously held
      // "previous" buffer (served two updates ago, so its pulse has long since completed). Skipped when the polled slot
      // hasn't advanced and when the reader doesn't use the pool.
      retainPooledFrameBuffer(currentFrameData.frameBuffer);

      WritableImage currentFrame = currentFrameData.frame;

      // PixelBuffer-backed images (e.g. MagewellVideoDataReader) need an FX-thread updateBuffer to mark the dirty
      // region for the next pulse; setPixels-based readers leave frameBuffer null and rely on JavaFX's own dirty
      // tracking.
      if (currentFrameData.frameBuffer != null)
         currentFrameData.frameBuffer.pixelBuffer.updateBuffer(b -> null);

      thumbnailContainer.setPrefWidth(THUMBNAIL_HIGHLIGHT_SCALE * defaultThumbnailSize);
      thumbnailContainer.setPrefHeight(THUMBNAIL_HIGHLIGHT_SCALE * defaultThumbnailSize * currentFrame.getHeight() / currentFrame.getWidth());

      thumbnail.setImage(currentFrame);

      if (updateVideoView.get())
      {
         videoView.setImage(currentFrame);
         queryRobotTimestampLabel.setText(Long.toString(currentFrameData.queryRobotTimestamp));
         currentRobotTimestampLabel.setText(Long.toString(currentFrameData.currentRobotTimestamp));
         currentVideoTimestampLabel.setText(Long.toString(currentFrameData.currentVideoTimestamp));
         currentDemuxerTimestampLabel.setText(Long.toString(currentFrameData.currentDemuxerTimestamp));

         updateServedFps(currentFrameData.currentVideoTimestamp);
         perfOverlayLabel.setText(String.format("served %s  decode %s @ %s ms  source %s fps",
                                                formatFps(servedFpsHz),
                                                formatFps(reader.getDecodeRateHz()),
                                                formatMillis(reader.getDecodeTimeMillis()),
                                                formatFps(reader.getSourceFrameRateHz())));

         if (imageViewRootPane.get() != null)
         {
            imageViewRootPane.get().setPadding(new Insets(16, 16, 16, 16));

            if (reader.replacedRobotTimestampsContainsIndex(reader.getCurrentIndex()))
            {
               imageViewRootPane.get().setBackground(new Background(new BackgroundFill(Color.DARKORANGE, CornerRadii.EMPTY, Insets.EMPTY)));
            }
            else
            {
               imageViewRootPane.get().setBackground(new Background(new BackgroundFill(Color.WHITE, CornerRadii.EMPTY, Insets.EMPTY)));
            }
         }
      }
   }

   public void stop()
   {
      if (videoWindowProperty.get() != null)
      {
         videoWindowProperty.get().close();
         videoWindowProperty.set(null);
      }
      releaseHeldFrameBuffers();
   }

   /**
    * Advances the two-deep ring of FX-held pooled buffers when a new buffer arrives. No-op when {@code newBuffer} is
    * null (non-pooled reader) or identical to the currently held buffer (poll returned the same slot we already hold).
    */
   private void retainPooledFrameBuffer(FrameBuffer newBuffer)
   {
      if (newBuffer == null || newBuffer == fxHeldCurrent)
         return;
      if (fxHeldPrevious != null)
         fxHeldPrevious.release();
      fxHeldPrevious = fxHeldCurrent;
      fxHeldCurrent = newBuffer;
      fxHeldCurrent.retain();
   }

   private void releaseHeldFrameBuffers()
   {
      if (fxHeldPrevious != null)
      {
         fxHeldPrevious.release();
         fxHeldPrevious = null;
      }
      if (fxHeldCurrent != null)
      {
         fxHeldCurrent.release();
         fxHeldCurrent = null;
      }
   }

   public Node getThumbnail()
   {
      return thumbnailContainer;
   }
}
