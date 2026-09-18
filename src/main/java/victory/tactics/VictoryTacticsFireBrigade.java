package victory.tactics;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.action.fire.ActionExtinguish;
import adf.core.agent.action.fire.ActionRefill;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.complex.BuildingDetector;
import adf.core.component.module.complex.HumanDetector;
import adf.core.component.module.complex.Search;
import adf.core.component.tactics.TacticsFireBrigade;
import adf.impl.tactics.utils.MessageTool;
import java.util.List;
import java.util.Objects;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/**
 * Preserves the server-specific fire-brigade rescue role while using otherwise
 * idle turns to suppress fires.
 */
public class VictoryTacticsFireBrigade extends TacticsFireBrigade {

  private HumanDetector humanDetector;
  private BuildingDetector buildingDetector;
  private Search search;
  private ExtAction actionFireRescue;
  private ExtAction actionFireFighting;
  private ExtAction actionMove;
  private CommandExecutor<CommandFire> commandExecutorFire;
  private CommandExecutor<CommandScout> commandExecutorScout;
  private MessageTool messageTool;
  private CommunicationMessage recentCommand;

  @Override
  public void initialize(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, ModuleManager moduleManager,
      MessageManager messageManager, DevelopData developData) {
    messageManager.setChannelSubscriber(moduleManager.getChannelSubscriber(
        "MessageManager.PlatoonChannelSubscriber",
        "adf.impl.module.comm.DefaultChannelSubscriber"));
    messageManager.setMessageCoordinator(moduleManager.getMessageCoordinator(
        "MessageManager.PlatoonMessageCoordinator",
        "adf.impl.module.comm.DefaultMessageCoordinator"));

    worldInfo.indexClass(StandardEntityURN.CIVILIAN,
        StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.POLICE_FORCE,
        StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.ROAD,
        StandardEntityURN.HYDRANT, StandardEntityURN.BUILDING,
        StandardEntityURN.REFUGE, StandardEntityURN.GAS_STATION,
        StandardEntityURN.AMBULANCE_CENTRE, StandardEntityURN.FIRE_STATION,
        StandardEntityURN.POLICE_OFFICE);

    messageTool = new MessageTool(scenarioInfo, developData);
    recentCommand = null;
    humanDetector = moduleManager.getModule(
        "VictoryTacticsFireBrigade.HumanDetector",
        "victory.module.complex.VictoryHumanDetector");
    buildingDetector = moduleManager.getModule(
        "VictoryTacticsFireBrigade.BuildingDetector",
        "victory.module.complex.VictoryBuildingDetector");
    search = moduleManager.getModule("VictoryTacticsFireBrigade.Search",
        "victory.module.complex.VictorySearch");
    actionFireRescue = moduleManager.getExtAction(
        "VictoryTacticsFireBrigade.ExtActionFireRescue",
        "adf.impl.extaction.DefaultExtActionFireRescue");
    actionFireFighting = moduleManager.getExtAction(
        "VictoryTacticsFireBrigade.ExtActionFireFighting",
        "adf.impl.extaction.DefaultExtActionFireFighting");
    actionMove = moduleManager.getExtAction(
        "VictoryTacticsFireBrigade.ExtActionMove",
        "adf.impl.extaction.DefaultExtActionMove");
    commandExecutorFire = moduleManager.getCommandExecutor(
        "VictoryTacticsFireBrigade.CommandExecutorFire",
        "adf.impl.centralized.DefaultCommandExecutorFire");
    commandExecutorScout = moduleManager.getCommandExecutor(
        "VictoryTacticsFireBrigade.CommandExecutorScout",
        "adf.impl.centralized.DefaultCommandExecutorScout");

    registerModule(humanDetector);
    registerModule(buildingDetector);
    registerModule(search);
    registerModule(actionFireRescue);
    registerModule(actionFireFighting);
    registerModule(actionMove);
    registerModule(commandExecutorFire);
    registerModule(commandExecutorScout);
  }

  @Override
  public void precompute(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, ModuleManager moduleManager,
      PrecomputeData precomputeData, DevelopData developData) {
    modulesPrecompute(precomputeData);
  }

  @Override
  public void resume(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, ModuleManager moduleManager,
      PrecomputeData precomputeData, DevelopData developData) {
    modulesResume(precomputeData);
  }

  @Override
  public void preparate(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, ModuleManager moduleManager,
      DevelopData developData) {
    modulesPreparate();
  }

  @Override
  public Action think(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, ModuleManager moduleManager,
      MessageManager messageManager, DevelopData developData) {
    messageTool.reflectMessage(agentInfo, worldInfo, scenarioInfo, messageManager);
    messageTool.sendRequestMessages(agentInfo, worldInfo, scenarioInfo, messageManager);
    messageTool.sendInformationMessages(agentInfo, worldInfo, scenarioInfo, messageManager);
    modulesUpdateInfo(messageManager);

    FireBrigade agent = (FireBrigade) agentInfo.me();
    receiveCommands(agentInfo.getID(), messageManager);
    Action action = commandAction();
    if (action != null) {
      sendActionMessage(messageManager, agent, action);
      return action;
    }

    EntityID target = humanDetector.calc().getTarget();
    action = actionFireRescue.setTarget(target).calc().getAction();
    if (action != null) {
      sendActionMessage(messageManager, agent, action);
      return action;
    }

    target = buildingDetector.calc().getTarget();
    action = actionFireFighting.setTarget(target).calc().getAction();
    if (action != null) {
      sendActionMessage(messageManager, agent, action);
      return action;
    }

    target = search.calc().getTarget();
    action = actionMove.setTarget(target).calc().getAction();
    if (action != null) {
      sendActionMessage(messageManager, agent, action);
      return action;
    }

    messageManager.addMessage(new MessageFireBrigade(true, agent,
        MessageFireBrigade.ACTION_REST, agent.getPosition()));
    return new ActionRest();
  }

  private void receiveCommands(EntityID agentID, MessageManager messageManager) {
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(CommandScout.class)) {
      CommandScout command = (CommandScout) message;
      if (command.isToIDDefined()
          && Objects.requireNonNull(command.getToID()).equals(agentID)) {
        recentCommand = command;
        commandExecutorScout.setCommand(command);
      }
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(CommandFire.class)) {
      CommandFire command = (CommandFire) message;
      if (command.isToIDDefined()
          && Objects.requireNonNull(command.getToID()).equals(agentID)) {
        recentCommand = command;
        commandExecutorFire.setCommand(command);
      }
    }
  }

  private Action commandAction() {
    if (recentCommand instanceof CommandFire) {
      return commandExecutorFire.calc().getAction();
    }
    if (recentCommand instanceof CommandScout) {
      return commandExecutorScout.calc().getAction();
    }
    return null;
  }

  private void sendActionMessage(MessageManager messageManager,
      FireBrigade brigade, Action action) {
    int actionIndex;
    EntityID target;
    if (action instanceof ActionMove) {
      List<EntityID> path = ((ActionMove) action).getPath();
      actionIndex = MessageFireBrigade.ACTION_MOVE;
      target = path.isEmpty() ? brigade.getPosition() : path.get(path.size() - 1);
    } else if (action instanceof ActionRescue) {
      actionIndex = MessageFireBrigade.ACTION_RESCUE;
      target = ((ActionRescue) action).getTarget();
    } else if (action instanceof ActionExtinguish) {
      actionIndex = MessageFireBrigade.ACTION_EXTINGUISH;
      target = ((ActionExtinguish) action).getTarget();
    } else if (action instanceof ActionRefill) {
      actionIndex = MessageFireBrigade.ACTION_REFILL;
      target = brigade.getPosition();
    } else if (action instanceof ActionRest) {
      actionIndex = MessageFireBrigade.ACTION_REST;
      target = brigade.getPosition();
    } else {
      return;
    }
    messageManager.addMessage(new MessageFireBrigade(true, brigade,
        actionIndex, target));
  }
}
