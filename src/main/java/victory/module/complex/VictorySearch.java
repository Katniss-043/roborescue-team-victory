package victory.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/** Searches each cluster systematically and does not repeatedly revisit a reached target. */
public class VictorySearch extends Search {

  private final PathPlanning pathPlanning;
  private final Clustering clustering;
  private final Set<EntityID> visited = new HashSet<>();
  private EntityID result;

  public VictorySearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.pathPlanning = moduleManager.getModule("VictorySearch.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    this.clustering = moduleManager.getModule("VictorySearch.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(pathPlanning);
    registerModule(clustering);
  }

  @Override
  public Search updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    visited.addAll(worldInfo.getChanged().getChangedEntities());
    if (result != null && agentInfo.getPosition().equals(result)) {
      visited.add(result);
      result = null;
    }
    return this;
  }

  @Override
  public Search calc() {
    if (result != null && !visited.contains(result)) {
      return this;
    }
    Set<EntityID> candidates = candidates();
    candidates.removeAll(visited);
    if (candidates.isEmpty()) {
      visited.clear();
      candidates = candidates();
      candidates.remove(agentInfo.getPosition());
    }
    result = null;
    if (!candidates.isEmpty()) {
      pathPlanning.setFrom(agentInfo.getPosition());
      pathPlanning.setDestination(candidates);
      List<EntityID> path = pathPlanning.calc().getResult();
      if (path != null && !path.isEmpty()) {
        result = path.get(path.size() - 1);
      }
    }
    return this;
  }

  private Set<EntityID> candidates() {
    Set<EntityID> candidates = new HashSet<>();
    Collection<StandardEntity> cluster = clustering.getClusterEntities(
        clustering.getClusterIndex(agentInfo.getID()));
    if (cluster != null) {
      for (StandardEntity entity : cluster) {
        if (isSearchable(entity)) {
          candidates.add(entity.getID());
        }
      }
    }
    if (candidates.isEmpty()) {
      for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BUILDING,
          StandardEntityURN.GAS_STATION, StandardEntityURN.AMBULANCE_CENTRE,
          StandardEntityURN.FIRE_STATION, StandardEntityURN.POLICE_OFFICE)) {
        if (isSearchable(entity)) {
          candidates.add(entity.getID());
        }
      }
    }
    return candidates;
  }

  private boolean isSearchable(StandardEntity entity) {
    return entity instanceof Building && entity.getStandardURN() != StandardEntityURN.REFUGE;
  }

  @Override
  public EntityID getTarget() {
    return result;
  }
}
