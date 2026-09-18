package victory.util;

import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.WorldInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;

/** Guards ambulance loading with fresh perception and deterministic local ownership. */
public final class AmbulancePickupPolicy {

  private AmbulancePickupPolicy() {
  }

  public static boolean isFreshlyObserved(WorldInfo worldInfo, EntityID id) {
    ChangeSet changed = worldInfo.getChanged();
    return changed != null && changed.getChangedEntities().contains(id);
  }

  /**
   * Assigns freshly visible civilians and ambulances in the current area
   * one-to-one. This prevents a group of ambulances from racing to load the
   * same civilian in a single timestep.
   */
  public static boolean canLoad(AgentInfo agentInfo, WorldInfo worldInfo,
      EntityID targetID) {
    StandardEntity entity = worldInfo.getEntity(targetID);
    if (!(entity instanceof Civilian) || !isTransportReady((Civilian) entity)
        || !isFreshlyObserved(worldInfo, targetID)) {
      return false;
    }
    Civilian target = (Civilian) entity;
    EntityID position = agentInfo.getPosition();
    if (!position.equals(target.getPosition())) {
      return false;
    }

    List<AmbulanceTeam> ambulances = new ArrayList<>();
    for (StandardEntity next : worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)) {
      AmbulanceTeam team = (AmbulanceTeam) next;
      if (isOperational(team) && position.equals(team.getPosition())
          && (team.getID().equals(agentInfo.getID())
              || isFreshlyObserved(worldInfo, team.getID()))
          && !hasPassenger(worldInfo, team.getID())) {
        ambulances.add(team);
      }
    }
    ambulances.sort(Comparator.comparingInt(team -> team.getID().getValue()));

    List<Civilian> civilians = new ArrayList<>();
    Set<EntityID> changed = worldInfo.getChanged().getChangedEntities();
    for (StandardEntity next : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
      Civilian civilian = (Civilian) next;
      if (isTransportReady(civilian) && position.equals(civilian.getPosition())
          && changed.contains(civilian.getID())) {
        civilians.add(civilian);
      }
    }
    civilians.sort(Comparator.comparingLong(AmbulancePickupPolicy::survivalWindow)
        .thenComparingInt(civilian -> civilian.getID().getValue()));

    int targetIndex = civilians.indexOf(target);
    return targetIndex >= 0 && targetIndex < ambulances.size()
        && ambulances.get(targetIndex).getID().equals(agentInfo.getID());
  }

  private static boolean isTransportReady(Civilian civilian) {
    if (!civilian.isHPDefined() || civilian.getHP() <= 0
        || !civilian.isPositionDefined()) {
      return false;
    }
    int buriedness = civilian.isBuriednessDefined() ? civilian.getBuriedness() : 0;
    int damage = civilian.isDamageDefined() ? civilian.getDamage() : 0;
    return buriedness == 0 && damage > 0;
  }

  private static boolean isOperational(AmbulanceTeam team) {
    return team.isPositionDefined()
        && (!team.isHPDefined() || team.getHP() > 0)
        && (!team.isBuriednessDefined() || team.getBuriedness() == 0);
  }

  private static boolean hasPassenger(WorldInfo worldInfo, EntityID teamID) {
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
      Civilian civilian = (Civilian) entity;
      if (civilian.isPositionDefined() && teamID.equals(civilian.getPosition())) {
        return true;
      }
    }
    return false;
  }

  private static long survivalWindow(Human human) {
    int damage = human.isDamageDefined() ? human.getDamage() : 0;
    return damage <= 0 ? Long.MAX_VALUE / 4 : (long) human.getHP() * 1000L / damage;
  }
}
