package victory.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import adf.core.component.communication.CommunicationMessage;
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
import victory.util.AmbulancePickupPolicy;

/** Keeps a viable casualty target and ranks short survival windows ahead of proximity. */
public class VictoryHumanDetector extends HumanDetector {

  private final Clustering clustering;
  private final int staleTargetCooldown;
  private final Map<EntityID, Integer> unavailableUntil = new HashMap<>();
  private EntityID result;

  public VictoryHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.clustering = moduleManager.getModule("VictoryHumanDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    this.staleTargetCooldown = developData.getInteger(
        "victory.humanDetector.staleTargetCooldown", 4);
    registerModule(this.clustering);
  }

  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    int now = agentInfo.getTime();
    unavailableUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
    if (agentInfo.me().getStandardURN() == StandardEntityURN.AMBULANCE_TEAM) {
      for (CommunicationMessage message : messageManager
          .getReceivedMessageList(MessageAmbulanceTeam.class)) {
        MessageAmbulanceTeam ambulanceMessage = (MessageAmbulanceTeam) message;
        EntityID target = ambulanceMessage.getTargetID();
        if (!ambulanceMessage.getAgentID().equals(agentInfo.getID())
            && ambulanceMessage.getAction() == MessageAmbulanceTeam.ACTION_LOAD
            && target != null) {
          unavailableUntil.put(target, now + staleTargetCooldown);
        }
      }
    }
    return this;
  }

  @Override
  public HumanDetector calc() {
    Human onboard = agentInfo.someoneOnBoard();
    if (onboard != null) {
      result = onboard.getID();
      return this;
    }
    StandardEntity existing = result == null ? null : worldInfo.getEntity(result);
    if (isViableTarget(existing)) {
      return this;
    }
    if (existing instanceof Human && isStaleAtMyPosition((Human) existing)) {
      unavailableUntil.put(existing.getID(),
          agentInfo.getTime() + staleTargetCooldown);
    }
    List<Human> targets = collectTargets();
    List<Human> local = inMyCluster(targets);
    if (!local.isEmpty()) {
      targets = local;
    } else if (agentInfo.me().getStandardURN() == StandardEntityURN.AMBULANCE_TEAM) {
      targets = globallyAssignedToMe(targets);
    }
    targets.sort(targetComparator(agentInfo.me()));
    result = targets.isEmpty() ? null : targets.get(0).getID();
    return this;
  }

  private List<Human> collectTargets() {
    List<Human> targets = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN,
        StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.FIRE_BRIGADE,
        StandardEntityURN.POLICE_FORCE)) {
      if (!entity.getID().equals(agentInfo.getID()) && isViableTarget(entity)) {
        targets.add((Human) entity);
      }
    }
    return targets;
  }

  private List<Human> inMyCluster(List<Human> targets) {
    Set<StandardEntity> cluster = new HashSet<>(
        clustering.getClusterEntities(clustering.getClusterIndex(agentInfo.getID())));
    List<Human> local = new ArrayList<>();
    for (Human target : targets) {
      StandardEntity position = worldInfo.getPosition(target);
      if (position != null && cluster.contains(position)) {
        local.add(target);
      }
    }
    return local;
  }

  private List<Human> globallyAssignedToMe(List<Human> targets) {
    List<Human> orderedTargets = new ArrayList<>(targets);
    orderedTargets.sort(Comparator.comparingLong(this::survivalWindow)
        .thenComparingInt(target -> target.getID().getValue()));
    List<AmbulanceTeam> available = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(
        StandardEntityURN.AMBULANCE_TEAM)) {
      AmbulanceTeam team = (AmbulanceTeam) entity;
      if (isOperational(team) && !hasPassenger(team)) {
        available.add(team);
      }
    }
    List<Human> mine = new ArrayList<>();
    for (Human target : orderedTargets) {
      AmbulanceTeam selected = null;
      int selectedDistance = Integer.MAX_VALUE;
      for (AmbulanceTeam team : available) {
        int candidateDistance = distance(team, target);
        if (selected == null || candidateDistance < selectedDistance
            || (candidateDistance == selectedDistance && selected != null
                && team.getID().getValue() < selected.getID().getValue())) {
          selected = team;
          selectedDistance = candidateDistance;
        }
      }
      if (selected == null) {
        break;
      }
      available.remove(selected);
      if (selected.getID().equals(agentInfo.getID())) {
        mine.add(target);
      }
    }
    return mine;
  }

  private boolean isViableTarget(StandardEntity entity) {
    if (!isRescuable(entity)) {
      return false;
    }
    if (agentInfo.me().getStandardURN() != StandardEntityURN.AMBULANCE_TEAM) {
      return true;
    }
    Human human = (Human) entity;
    if (isTemporarilyUnavailable(human)) {
      return false;
    }
    if (human.getPosition().equals(agentInfo.getPosition())) {
      if (!AmbulancePickupPolicy.isFreshlyObserved(worldInfo, human.getID())) {
        return false;
      }
      return AmbulancePickupPolicy.canLoad(agentInfo, worldInfo, human.getID());
    }
    return true;
  }

  private boolean isTemporarilyUnavailable(Human human) {
    Integer until = unavailableUntil.get(human.getID());
    return until != null && until > agentInfo.getTime()
        && !AmbulancePickupPolicy.isFreshlyObserved(worldInfo, human.getID());
  }

  private boolean isStaleAtMyPosition(Human human) {
    return agentInfo.me().getStandardURN() == StandardEntityURN.AMBULANCE_TEAM
        && human.isPositionDefined()
        && human.getPosition().equals(agentInfo.getPosition())
        && !AmbulancePickupPolicy.isFreshlyObserved(worldInfo, human.getID());
  }

  private boolean isOperational(AmbulanceTeam team) {
    return team.isPositionDefined()
        && (!team.isHPDefined() || team.getHP() > 0)
        && (!team.isBuriednessDefined() || team.getBuriedness() == 0);
  }

  private boolean hasPassenger(AmbulanceTeam team) {
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
      Human human = (Human) entity;
      if (human.isPositionDefined() && team.getID().equals(human.getPosition())) {
        return true;
      }
    }
    return false;
  }

  private boolean isRescuable(StandardEntity entity) {
    if (!(entity instanceof Human)) {
      return false;
    }
    Human human = (Human) entity;
    if (!human.isHPDefined() || human.getHP() <= 0 || !human.isPositionDefined()) {
      return false;
    }
    StandardEntity position = worldInfo.getPosition(human);
    if (position == null || position.getStandardURN() == StandardEntityURN.REFUGE
        || position.getStandardURN() == StandardEntityURN.AMBULANCE_TEAM) {
      return false;
    }
    int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
    int damage = human.isDamageDefined() ? human.getDamage() : 0;
    StandardEntityURN agentType = agentInfo.me().getStandardURN();
    // This server delegates digging to fire brigades. Ambulances can only load
    // civilians which have already been unearthed, so assigning any other human
    // makes the transport action return null and leaves the ambulance stranded.
    if (agentType == StandardEntityURN.AMBULANCE_TEAM) {
      return human.getStandardURN() == StandardEntityURN.CIVILIAN
          && buriedness == 0 && damage > 0;
    }
    if (agentType == StandardEntityURN.FIRE_BRIGADE) {
      return buriedness > 0;
    }
    return buriedness > 0 || damage > 0;
  }

  private long survivalWindow(Human human) {
    int damage = human.isDamageDefined() ? human.getDamage() : 0;
    return damage <= 0 ? Long.MAX_VALUE / 4 : (long) human.getHP() * 1000L / damage;
  }

  private Comparator<Human> targetComparator(StandardEntity reference) {
    return Comparator.comparingLong(this::survivalWindow)
        .thenComparingInt(target -> distance(reference, target))
        .thenComparingInt(target -> target.getID().getValue());
  }

  private int distance(StandardEntity from, StandardEntity to) {
    int value = worldInfo.getDistance(from, to);
    return value < 0 ? Integer.MAX_VALUE : value;
  }

  @Override
  public EntityID getTarget() {
    return result;
  }
}
