package us.ihmc.scs2.session.foxglove;

import us.ihmc.commons.Conversions;
import us.ihmc.euclid.tuple3D.Vector3D;
import us.ihmc.euclid.tuple4D.Quaternion;
import us.ihmc.log.LogTools;
import us.ihmc.mecano.multiBodySystem.interfaces.OneDoFJointBasics;
import us.ihmc.mecano.multiBodySystem.interfaces.SixDoFJointBasics;
import us.ihmc.mecano.multiBodySystem.iterators.SubtreeStreams;
import us.ihmc.scs2.definition.robot.RobotDefinition;
import us.ihmc.scs2.definition.robot.RobotStateDefinition;
import us.ihmc.scs2.definition.terrain.TerrainObjectDefinition;
import us.ihmc.scs2.definition.yoGraphic.YoGraphicDefinition;
import us.ihmc.scs2.session.Session;
import us.ihmc.scs2.session.SessionMode;
import us.ihmc.scs2.session.foxglove.FoxgloveYoBindings.ChannelBinding;
import us.ihmc.scs2.session.foxglove.FoxgloveYoBindings.JointStateBinding;
import us.ihmc.scs2.session.foxglove.FoxgloveYoBindings.StringBinding;
import us.ihmc.scs2.session.foxglove.FoxgloveYoBindings.TfBinding;
import us.ihmc.scs2.session.foxglove.Ros2CdrMessages.JointState;
import us.ihmc.scs2.session.foxglove.Ros2CdrMessages.TransformStamped;
import us.ihmc.scs2.session.tools.RobotModelLoader;
import us.ihmc.scs2.simulation.robot.Robot;
import us.ihmc.yoVariables.registry.YoRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Live {@link Session} backed by a persona_logger Foxglove WebSocket ({@code foxglove.sdk.v1}).
 */
public class FoxgloveRemoteSession extends Session
{
   private final FoxgloveWsClient client;
   private final String sessionName;
   private final FoxgloveYoBindings bindings;
   private final List<Robot> robots = new ArrayList<>();
   private final List<RobotDefinition> robotDefinitions = new ArrayList<>();
   private final AtomicLong latestDataTimestamp = new AtomicLong(-1);
   private final AtomicLong lastTickTimestamp = new AtomicLong(-1);
   private volatile boolean robotLoaded;
   private Map<String, OneDoFJointBasics> jointsByName = new HashMap<>();
   private SixDoFJointBasics floatingJoint;

   public static FoxgloveRemoteSession connect(String host, int port) throws Exception
   {
      FoxgloveWsClient client = new FoxgloveWsClient();
      client.connect(host, port);
      FoxgloveRemoteSession session = new FoxgloveRemoteSession(client, host + ":" + port);
      client.subscribeAll();
      long deadline = System.currentTimeMillis() + 3000;
      while (System.currentTimeMillis() < deadline && session.getRobotDefinitions().isEmpty())
         Thread.sleep(50);
      return session;
   }

   public FoxgloveRemoteSession(FoxgloveWsClient client, String fallbackName)
   {
      super();
      this.client = client;
      String advertisedName = client.getServerName();
      sessionName = advertisedName == null || advertisedName.isEmpty() ? fallbackName : advertisedName;

      YoRegistry foxgloveRegistry = new YoRegistry("foxglove");
      bindings = new FoxgloveYoBindings(foxgloveRegistry);
      bindings.bindAdvertised(client.getChannels());
      bindings.mapSubscriptionsInAdvertiseOrder();
      rootRegistry.addChild(foxgloveRegistry);

      client.setMessageListener(this::receivedMessage);

      setSessionMode(SessionMode.RUNNING);
      setSessionDTSeconds(0.01);
      setSessionModeTask(SessionMode.RUNNING, () ->
      {
         if (!this.client.isOpen())
            setSessionMode(SessionMode.PAUSE);
      });
      setDesiredBufferPublishPeriod(Conversions.secondsToNanoseconds(1.0 / 60.0));
   }

   private void receivedMessage(int subscriptionId, FoxgloveWsClient.FoxgloveBinaryMessage message)
   {
      ChannelBinding binding = bindings.bindingForSubscription(subscriptionId);
      if (binding == null)
         return;
      binding.apply(message.logTime, message.payload);
      latestDataTimestamp.set(message.logTime);

      if (!robotLoaded)
         tryLoadRobot(binding);

      if (hasSessionStarted() && getActiveMode() == SessionMode.RUNNING)
         runTick();
   }

   private void tryLoadRobot(ChannelBinding binding)
   {
      if (!(binding instanceof StringBinding stringBinding))
         return;
      if (!binding.getChannel().getTopic().contains("robot_description"))
         return;
      String urdf = stringBinding.getLatest();
      if (urdf == null || urdf.isBlank() || !urdf.contains("<robot"))
         return;
      try
      {
         RobotDefinition definition = RobotModelLoader.loadModel("persona",
                                                                 "urdf",
                                                                 new String[0],
                                                                 urdf.getBytes(StandardCharsets.UTF_8),
                                                                 null);
         if (definition == null)
            return;
         robotDefinitions.add(definition);
         Robot robot = new Robot(definition, getInertialFrame());
         robots.add(robot);
         jointsByName = SubtreeStreams.fromChildren(OneDoFJointBasics.class, robot.getRootBody())
                                      .collect(Collectors.toMap(OneDoFJointBasics::getName, joint -> joint, (a, b) -> a));
         if (!robot.getRootBody().getChildrenJoints().isEmpty()
             && robot.getRootBody().getChildrenJoints().get(0) instanceof SixDoFJointBasics sixDoFJoint)
            floatingJoint = sixDoFJoint;
         try
         {
            rootRegistry.addChild(robot.getRegistry());
         }
         catch (RuntimeException ignored)
         {
         }
         robotLoaded = true;
         LogTools.info("Loaded URDF robot from Foxglove /robot_description ({} joints)", jointsByName.size());
      }
      catch (Exception e)
      {
         LogTools.warn("Could not load URDF from Foxglove robot_description: {}", e.getMessage());
      }
   }

   public long getDelay()
   {
      long last = lastTickTimestamp.get();
      long latest = latestDataTimestamp.get();
      if (last < 0 || latest < 0)
         return 0;
      return latest - last;
   }

   public List<FoxgloveCameraFrame> getLatestCameraFrames()
   {
      List<FoxgloveCameraFrame> frames = new ArrayList<>();
      for (ChannelBinding binding : bindings.getBindings())
      {
         FoxgloveCameraFrame frame = binding.getLatestCameraFrame();
         if (frame != null)
            frames.add(frame);
      }
      return frames;
   }

   public FoxgloveWsClient getClient()
   {
      return client;
   }

   @Override
   public void addGraphicsAddedCallback(Consumer<List<YoGraphicDefinition>> addedGraphicsConsumer)
   {
   }

   @Override
   protected long computeRunTaskPeriod()
   {
      return Conversions.secondsToNanoseconds(0.01);
   }

   @Override
   protected double doSpecificRunTick()
   {
      updateRobotFromJointStates();
      updateFloatingJointFromTf();
      lastTickTimestamp.set(latestDataTimestamp.get());
      long timestamp = latestDataTimestamp.get();
      if (timestamp > 1_000_000_000_000L)
         return Conversions.nanosecondsToSeconds(timestamp);
      return timestamp * 1.0e-9;
   }

   private void updateRobotFromJointStates()
   {
      if (jointsByName.isEmpty())
         return;
      for (ChannelBinding binding : bindings.getBindings())
      {
         if (!(binding instanceof JointStateBinding jointBinding))
            continue;
         JointState jointState = jointBinding.getLatest();
         if (jointState == null)
            continue;
         int n = Math.min(jointState.name.length, jointState.position.length);
         for (int i = 0; i < n; i++)
         {
            OneDoFJointBasics joint = jointsByName.get(jointState.name[i]);
            if (joint == null)
               continue;
            joint.setQ(jointState.position[i]);
            if (i < jointState.velocity.length)
               joint.setQd(jointState.velocity[i]);
         }
      }
      for (Robot robot : robots)
         robot.getRootBody().updateFramesRecursively();
   }

   private void updateFloatingJointFromTf()
   {
      if (floatingJoint == null)
         return;
      for (ChannelBinding binding : bindings.getBindings())
      {
         if (!(binding instanceof TfBinding tfBinding))
            continue;
         Ros2CdrMessages.TfMessage tf = tfBinding.getLatest();
         if (tf == null)
            continue;
         for (TransformStamped transform : tf.transforms)
         {
            if (transform.childFrameId == null)
               continue;
            if (floatingJoint.getSuccessor() != null && transform.childFrameId.contains(floatingJoint.getSuccessor().getName())
                || transform.childFrameId.contains("pelvis") || transform.childFrameId.contains("base"))
            {
               floatingJoint.getJointPose()
                            .set(new Vector3D(transform.tx, transform.ty, transform.tz),
                                 new Quaternion(transform.qx, transform.qy, transform.qz, transform.qw));
               return;
            }
         }
      }
   }

   public void close()
   {
      client.close();
   }

   @Override
   public String getSessionName()
   {
      return sessionName;
   }

   @Override
   public List<RobotDefinition> getRobotDefinitions()
   {
      return robotDefinitions;
   }

   @Override
   public List<TerrainObjectDefinition> getTerrainObjectDefinitions()
   {
      return Collections.emptyList();
   }

   @Override
   public List<YoGraphicDefinition> getYoGraphicDefinitions()
   {
      return Collections.emptyList();
   }

   @Override
   public List<RobotStateDefinition> getCurrentRobotStateDefinitions(boolean initialState)
   {
      return robots.stream().map(Robot::getCurrentRobotStateDefinition).collect(Collectors.toList());
   }
}
