package victory.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.RoadDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/** Clears currently blocked access roads, prioritising refuges and trapped people. */
public class VictoryRoadDetector extends RoadDetector {

  private final PathPlanning pathPlanning;
  private final Clustering clustering;
  private EntityID result;

  public VictoryRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.pathPlanning = moduleManager.getModule("VictoryRoadDetector.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    this.clustering = moduleManager.getModule("VictoryRoadDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(pathPlanning);
    registerModule(clustering);
  }

  @Override
  public RoadDetector updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (result != null && !isBlocked(worldInfo.getEntity(result))) {
      result = null;
    }
    return this;
  }

  @Override
  public RoadDetector calc() {
    if (result != null && isBlocked(worldInfo.getEntity(result))) {
      return this;
    }
    List<Road> targets = blockedRoads();
    List<Road> local = inMyCluster(targets);
    if (!local.isEmpty()) {
      targets = local;
    }
    targets.sort(Comparator.comparingLong(this::priority).reversed()
        .thenComparingInt(road -> distance(agentInfo.me(), road))
        .thenComparingInt(road -> road.getID().getValue()));
    result = null;
    for (Road target : targets) {
      pathPlanning.setFrom(agentInfo.getPosition());
      pathPlanning.setDestination(java.util.Collections.singleton(target.getID()));
      List<EntityID> path = pathPlanning.calc().getResult();
      if (path != null && !path.isEmpty()) {
        result = target.getID();
        break;
      }
    }
    return this;
  }

  private List<Road> blockedRoads() {
    List<Road> roads = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
      if (isBlocked(entity)) {
        roads.add((Road) entity);
      }
    }
    return roads;
  }

  private List<Road> inMyCluster(List<Road> roads) {
    Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(
        clustering.getClusterIndex(agentInfo.getID()));
    Set<StandardEntity> cluster = clusterEntities == null ? new HashSet<>()
        : new HashSet<>(clusterEntities);
    List<Road> local = new ArrayList<>();
    for (Road road : roads) {
      if (cluster.contains(road)) {
        local.add(road);
      }
    }
    return local;
  }

  private boolean isBlocked(StandardEntity entity) {
    return entity instanceof Road && ((Road) entity).isBlockadesDefined()
        && !((Road) entity).getBlockades().isEmpty();
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
      if (neighbour instanceof Building && ((Building) neighbour).isOnFire()) {
        score += 300_000L;
      }
      if (containsCasualty(neighbour)) {
        score += 700_000L;
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
  public EntityID getTarget() {
    return result;
  }
}
