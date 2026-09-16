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
import adf.core.component.module.complex.PoliceTargetAllocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/** Allocates known blocked roads once, with refuge access ahead of ordinary roads. */
public class VictoryPoliceTargetAllocator extends PoliceTargetAllocator {

  private final Map<EntityID, EntityID> assignments = new HashMap<>();

  public VictoryPoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }

  @Override
  public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
      if (message instanceof StandardMessage) {
        MessageUtil.reflectMessage(worldInfo, (StandardMessage) message);
      }
    }
    return this;
  }

  @Override
  public PoliceTargetAllocator calc() {
    pruneAssignments();
    List<PoliceForce> police = policeForces();
    List<Road> roads = blockedRoads();
    police.removeIf(force -> assignments.containsKey(force.getID()));
    Set<EntityID> assignedTargets = new HashSet<>(assignments.values());
    roads.removeIf(road -> assignedTargets.contains(road.getID()));
    roads.sort(Comparator.comparingLong(this::priority).reversed()
        .thenComparingInt(road -> distance(agentInfo.me(), road))
        .thenComparingInt(road -> road.getID().getValue()));
    for (Road road : roads) {
      PoliceForce best = null;
      int bestDistance = Integer.MAX_VALUE;
      for (PoliceForce force : police) {
        int distance = distance(force, road);
        if (distance < bestDistance
            || (distance == bestDistance && best != null
            && force.getID().getValue() < best.getID().getValue())) {
          best = force;
          bestDistance = distance;
        }
      }
      if (best != null) {
        assignments.put(best.getID(), road.getID());
        police.remove(best);
      }
    }
    return this;
  }

  private List<PoliceForce> policeForces() {
    List<PoliceForce> police = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
      PoliceForce force = (PoliceForce) entity;
      if (isAvailable(force)) {
        police.add(force);
      }
    }
    return police;
  }

  private void pruneAssignments() {
    assignments.entrySet().removeIf(entry -> {
      StandardEntity force = worldInfo.getEntity(entry.getKey());
      StandardEntity road = worldInfo.getEntity(entry.getValue());
      return !(force instanceof PoliceForce) || !isAvailable((PoliceForce) force)
          || !(road instanceof Road) || !((Road) road).isBlockadesDefined()
          || ((Road) road).getBlockades().isEmpty();
    });
  }

  private boolean isAvailable(PoliceForce force) {
    return force.isPositionDefined()
        && (!force.isBuriednessDefined() || force.getBuriedness() == 0);
  }

  private List<Road> blockedRoads() {
    List<Road> roads = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
      Road road = (Road) entity;
      if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
        roads.add(road);
      }
    }
    return roads;
  }

  private long priority(Road road) {
    long score = 1;
    for (EntityID neighbourId : road.getNeighbours()) {
      StandardEntity neighbour = worldInfo.getEntity(neighbourId);
      if (neighbour == null) {
        continue;
      }
      if (neighbour.getStandardURN() == StandardEntityURN.REFUGE) {
        score += 1_000_000L;
      }
      if (containsCasualty(neighbour)) {
        score += 700_000L;
      }
      if (neighbour instanceof Building && ((Building) neighbour).isOnFire()) {
        score += 200_000L;
      }
    }
    return score;
  }

  private boolean containsCasualty(StandardEntity area) {
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN,
        StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.FIRE_BRIGADE,
        StandardEntityURN.POLICE_FORCE)) {
      if (entity instanceof Human) {
        Human human = (Human) entity;
        if (human.isPositionDefined() && human.getPosition().equals(area.getID())
            && human.isHPDefined() && human.getHP() > 0
            && human.isBuriednessDefined() && human.getBuriedness() > 0) {
          return true;
        }
      }
    }
    return false;
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
