package victory.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.AmbulanceTargetAllocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/** Centre-side assignment: one viable casualty per available ambulance team. */
public class VictoryAmbulanceTargetAllocator extends AmbulanceTargetAllocator {

  private final Map<EntityID, EntityID> assignments = new HashMap<>();

  public VictoryAmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }

  @Override
  public AmbulanceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
      if (message instanceof StandardMessage) {
        MessageUtil.reflectMessage(worldInfo, (StandardMessage) message);
      }
    }
    return this;
  }

  @Override
  public AmbulanceTargetAllocator calc() {
    pruneAssignments();
    List<AmbulanceTeam> teams = ambulances();
    List<Human> targets = casualties();
    teams.removeIf(team -> assignments.containsKey(team.getID()));
    Set<EntityID> assignedTargets = new HashSet<>(assignments.values());
    targets.removeIf(target -> assignedTargets.contains(target.getID()));
    targets.sort(Comparator.comparingLong(this::survivalWindow)
        .thenComparingInt(this::civilianPenalty)
        .thenComparingInt(target -> target.getID().getValue()));
    for (Human target : targets) {
      AmbulanceTeam best = null;
      int bestDistance = Integer.MAX_VALUE;
      for (AmbulanceTeam team : teams) {
        int travelDistance = distance(team, target);
        if (travelDistance < bestDistance
            || (travelDistance == bestDistance && best != null
            && team.getID().getValue() < best.getID().getValue())) {
          best = team;
          bestDistance = travelDistance;
        }
      }
      if (best != null) {
        assignments.put(best.getID(), target.getID());
        teams.remove(best);
      }
    }
    return this;
  }

  private List<AmbulanceTeam> ambulances() {
    List<AmbulanceTeam> teams = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)) {
      AmbulanceTeam team = (AmbulanceTeam) entity;
      if (isAvailable(team)) {
        teams.add(team);
      }
    }
    return teams;
  }

  private void pruneAssignments() {
    assignments.entrySet().removeIf(entry -> {
      StandardEntity team = worldInfo.getEntity(entry.getKey());
      StandardEntity target = worldInfo.getEntity(entry.getValue());
      if (!(team instanceof AmbulanceTeam) || !isOperational((AmbulanceTeam) team)
          || !(target instanceof Human)) {
        return true;
      }
      return !isValidAssignment((AmbulanceTeam) team, (Human) target);
    });
  }

  private boolean isAvailable(AmbulanceTeam team) {
    return isOperational(team) && !hasPassenger(team);
  }

  private boolean isOperational(AmbulanceTeam team) {
    return team.isPositionDefined()
        && (!team.isBuriednessDefined() || team.getBuriedness() == 0);
  }

  private boolean isValidAssignment(AmbulanceTeam team, Human target) {
    if (!target.isHPDefined() || target.getHP() <= 0 || !target.isPositionDefined()) {
      return false;
    }
    StandardEntity position = worldInfo.getPosition(target);
    if (position == null || position.getStandardURN() == StandardEntityURN.REFUGE) {
      return false;
    }
    if (position.getStandardURN() == StandardEntityURN.AMBULANCE_TEAM) {
      return position.getID().equals(team.getID());
    }
    return isTransportReady(target);
  }

  private boolean hasPassenger(AmbulanceTeam team) {
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN,
        StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.FIRE_BRIGADE,
        StandardEntityURN.POLICE_FORCE)) {
      if (entity instanceof Human) {
        Human human = (Human) entity;
        if (human.isPositionDefined() && team.getID().equals(human.getPosition())) {
          return true;
        }
      }
    }
    return false;
  }

  private List<Human> casualties() {
    List<Human> targets = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN,
        StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.FIRE_BRIGADE,
        StandardEntityURN.POLICE_FORCE)) {
      if (entity instanceof Human && isTransportReady((Human) entity)) {
        targets.add((Human) entity);
      }
    }
    return targets;
  }

  /**
   * The current rules assign digging to fire brigades. Keep ambulance-centre
   * commands limited to civilians an ambulance can load immediately; otherwise
   * an impossible command can remain active while newly freed civilians wait.
   */
  private boolean isTransportReady(Human human) {
    if (!human.isHPDefined() || human.getHP() <= 0 || !human.isPositionDefined()) {
      return false;
    }
    if (human.getStandardURN() != StandardEntityURN.CIVILIAN) {
      return false;
    }
    StandardEntity position = worldInfo.getPosition(human);
    if (position == null || position.getStandardURN() == StandardEntityURN.REFUGE
        || position.getStandardURN() == StandardEntityURN.AMBULANCE_TEAM) {
      return false;
    }
    int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
    int damage = human.isDamageDefined() ? human.getDamage() : 0;
    return buriedness == 0 && damage > 0;
  }

  private long survivalWindow(Human human) {
    int damage = human.isDamageDefined() ? human.getDamage() : 0;
    return damage <= 0 ? Long.MAX_VALUE / 4 : (long) human.getHP() * 1000L / damage;
  }

  private int civilianPenalty(Human human) {
    return human.getStandardURN() == StandardEntityURN.CIVILIAN ? 0 : 1;
  }

  private int distance(StandardEntity from, StandardEntity to) {
    int value = worldInfo.getDistance(from, to);
    return value < 0 ? Integer.MAX_VALUE : value;
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    return new HashMap<>(assignments);
  }
}
