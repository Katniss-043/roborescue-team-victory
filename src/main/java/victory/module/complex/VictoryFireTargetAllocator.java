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
import adf.core.component.module.complex.FireTargetAllocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/** Only issues fire-centre autonomy commands for buried emergency responders.
 * Fire suppression remains local so agents can react to newly observed fires immediately. */
public class VictoryFireTargetAllocator extends FireTargetAllocator {

  private final Map<EntityID, EntityID> assignments = new HashMap<>();

  public VictoryFireTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }

  @Override
  public FireTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
      if (message instanceof StandardMessage) {
        MessageUtil.reflectMessage(worldInfo, (StandardMessage) message);
      }
    }
    return this;
  }

  @Override
  public FireTargetAllocator calc() {
    pruneAssignments();
    List<FireBrigade> brigades = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
      FireBrigade brigade = (FireBrigade) entity;
      if (isAvailable(brigade)) {
        brigades.add(brigade);
      }
    }
    List<Human> trappedResponders = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM,
        StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.POLICE_FORCE)) {
      if (entity instanceof Human && isBuried((Human) entity)) {
        trappedResponders.add((Human) entity);
      }
    }
    trappedResponders.sort(Comparator.comparingInt(this::buriedness).reversed()
        .thenComparingInt(target -> target.getID().getValue()));
    brigades.removeIf(brigade -> assignments.containsKey(brigade.getID()));
    Set<EntityID> assignedTargets = new HashSet<>(assignments.values());
    trappedResponders.removeIf(target -> assignedTargets.contains(target.getID()));
    for (Human target : trappedResponders) {
      FireBrigade best = null;
      int bestDistance = Integer.MAX_VALUE;
      for (FireBrigade brigade : brigades) {
        int distance = distance(brigade, target);
        if (distance < bestDistance
            || (distance == bestDistance && best != null
                && brigade.getID().getValue() < best.getID().getValue())) {
          best = brigade;
          bestDistance = distance;
        }
      }
      if (best != null) {
        assignments.put(best.getID(), target.getID());
        brigades.remove(best);
      }
    }
    return this;
  }

  private void pruneAssignments() {
    assignments.entrySet().removeIf(entry -> {
      StandardEntity brigade = worldInfo.getEntity(entry.getKey());
      StandardEntity target = worldInfo.getEntity(entry.getValue());
      return !(brigade instanceof FireBrigade) || !isAvailable((FireBrigade) brigade)
          || !(target instanceof Human) || !isBuried((Human) target);
    });
  }

  private boolean isAvailable(FireBrigade brigade) {
    return brigade.isPositionDefined()
        && (!brigade.isBuriednessDefined() || brigade.getBuriedness() == 0);
  }

  private boolean isBuried(Human human) {
    return human.isPositionDefined() && human.isHPDefined() && human.getHP() > 0
        && human.isBuriednessDefined() && human.getBuriedness() > 0;
  }

  private int buriedness(Human human) {
    return human.isBuriednessDefined() ? human.getBuriedness() : 0;
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
