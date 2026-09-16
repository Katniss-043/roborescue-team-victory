package victory.algorithm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/** Deterministic, path-aware static clustering with a workload balancing penalty. */
public class VictoryClustering extends Clustering {

  private static final String KEY_SIZE = "victory.clustering.size";
  private static final String KEY_CLUSTER = "victory.clustering.cluster.";
  private static final Map<String, BaseLayout> BASE_LAYOUTS = new HashMap<>();

  private int clusterSize;
  private List<List<EntityID>> clusterIDs = new ArrayList<>();
  private Map<EntityID, Integer> indexByID = new HashMap<>();

  public VictoryClustering(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.clusterSize = clusterCount();
  }

  @Override
  public VictoryClustering preparate() {
    super.preparate();
    if (getCountPreparate() <= 1) {
      buildFromWorld();
    }
    return this;
  }

  @Override
  public VictoryClustering precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    if (getCountPrecompute() <= 1) {
      buildFromWorld();
      precomputeData.setInteger(KEY_SIZE, clusterSize);
      for (int i = 0; i < clusterSize; i++) {
        precomputeData.setEntityIDList(KEY_CLUSTER + i, clusterIDs.get(i));
      }
    }
    return this;
  }

  @Override
  public VictoryClustering resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (getCountResume() <= 1) {
      int storedSize = precomputeData.getInteger(KEY_SIZE);
      if (storedSize > 0) {
        clusterSize = storedSize;
        clusterIDs = new ArrayList<>(clusterSize);
        for (int i = 0; i < clusterSize; i++) {
          clusterIDs.add(new ArrayList<>(precomputeData.getEntityIDList(KEY_CLUSTER + i)));
        }
        rebuildIndex();
      } else {
        buildFromWorld();
      }
    }
    return this;
  }

  @Override
  public VictoryClustering updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }

  @Override
  public VictoryClustering calc() {
    return this;
  }

  @Override
  public int getClusterNumber() {
    return clusterSize;
  }

  @Override
  public int getClusterIndex(StandardEntity entity) {
    return entity == null ? -1 : getClusterIndex(entity.getID());
  }

  @Override
  public int getClusterIndex(EntityID id) {
    return indexByID.getOrDefault(id, -1);
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int index) {
    if (index < 0 || index >= clusterIDs.size()) {
      return Collections.emptyList();
    }
    List<StandardEntity> entities = new ArrayList<>();
    for (EntityID id : clusterIDs.get(index)) {
      StandardEntity entity = worldInfo.getEntity(id);
      if (entity != null) {
        entities.add(entity);
      }
    }
    return entities;
  }

  @Override
  public Collection<EntityID> getClusterEntityIDs(int index) {
    if (index < 0 || index >= clusterIDs.size()) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(clusterIDs.get(index));
  }

  private void buildFromWorld() {
    clusterSize = clusterCount();
    List<Area> areas = areas();
    if (areas.isEmpty()) {
      clusterIDs = new ArrayList<>();
      indexByID.clear();
      return;
    }
    clusterSize = Math.min(clusterSize, areas.size());
    String key = layoutKey(areas, clusterSize);
    BaseLayout base;
    synchronized (BASE_LAYOUTS) {
      base = BASE_LAYOUTS.get(key);
      if (base == null) {
        base = buildBaseLayout(areas, clusterSize);
        BASE_LAYOUTS.put(key, base);
      }
    }
    clusterIDs = base.copyClusters();
    assignAgents(StandardEntityURN.AMBULANCE_TEAM, base.centres);
    assignAgents(StandardEntityURN.FIRE_BRIGADE, base.centres);
    assignAgents(StandardEntityURN.POLICE_FORCE, base.centres);
    rebuildIndex();
  }

  private List<Area> areas() {
    List<Area> areas = new ArrayList<>();
    for (StandardEntity entity : worldInfo) {
      if (entity instanceof Area) {
        areas.add((Area) entity);
      }
    }
    areas.sort(Comparator.comparingInt(area -> area.getID().getValue()));
    return areas;
  }

  private BaseLayout buildBaseLayout(List<Area> areas, int count) {
    List<Area> centres = chooseCentres(areas, count);
    List<Map<EntityID, Long>> distances = new ArrayList<>();
    for (Area centre : centres) {
      distances.add(shortestDistances(centre.getID()));
    }
    List<List<EntityID>> clusters = new ArrayList<>();
    int[] loads = new int[count];
    int totalWork = 0;
    for (Area area : areas) {
      totalWork += work(area);
      clusters.add(null);
    }
    clusters.clear();
    for (int i = 0; i < count; i++) {
      clusters.add(new ArrayList<>());
    }
    final double averageWork = Math.max(1.0, (double) totalWork / count);
    List<Area> ordered = new ArrayList<>(areas);
    ordered.sort(Comparator.<Area>comparingLong(area -> nearestDistance(area, distances))
        .thenComparingInt(area -> area.getID().getValue()));
    int maxEntities = (int) Math.ceil((double) areas.size() / count) + 2;
    for (Area area : ordered) {
      int selected = -1;
      double selectedScore = Double.MAX_VALUE;
      for (int i = 0; i < count; i++) {
        if (clusters.get(i).size() >= maxEntities) {
          continue;
        }
        long distance = distances.get(i).getOrDefault(area.getID(), Long.MAX_VALUE / 8);
        double overload = Math.max(0.0, (loads[i] + work(area)) / averageWork - 1.0);
        double score = distance + overload * 100_000.0;
        if (score < selectedScore) {
          selected = i;
          selectedScore = score;
        }
      }
      if (selected < 0) {
        selected = nearestCentre(area, distances);
      }
      clusters.get(selected).add(area.getID());
      loads[selected] += work(area);
    }
    List<EntityID> centreIDs = new ArrayList<>();
    for (Area centre : centres) {
      centreIDs.add(centre.getID());
    }
    return new BaseLayout(centreIDs, clusters);
  }

  private List<Area> chooseCentres(List<Area> areas, int count) {
    List<Area> centres = new ArrayList<>();
    centres.add(areas.get(0));
    while (centres.size() < count) {
      Area best = null;
      long farthest = Long.MIN_VALUE;
      for (Area candidate : areas) {
        if (centres.contains(candidate)) {
          continue;
        }
        long nearest = Long.MAX_VALUE;
        for (Area centre : centres) {
          nearest = Math.min(nearest, directDistance(candidate, centre));
        }
        if (nearest > farthest) {
          farthest = nearest;
          best = candidate;
        }
      }
      centres.add(best);
    }
    return centres;
  }

  private Map<EntityID, Long> shortestDistances(EntityID source) {
    Map<EntityID, Long> distance = new HashMap<>();
    PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingLong(node -> node.cost));
    distance.put(source, 0L);
    open.add(new PathNode(source, 0L));
    while (!open.isEmpty()) {
      PathNode current = open.poll();
      if (current.cost != distance.getOrDefault(current.id, Long.MAX_VALUE)) {
        continue;
      }
      StandardEntity entity = worldInfo.getEntity(current.id);
      if (!(entity instanceof Area)) {
        continue;
      }
      for (EntityID next : ((Area) entity).getNeighbours()) {
        StandardEntity neighbour = worldInfo.getEntity(next);
        if (!(neighbour instanceof Area)) {
          continue;
        }
        long candidate = current.cost + directDistance((Area) entity, (Area) neighbour);
        if (candidate < distance.getOrDefault(next, Long.MAX_VALUE)) {
          distance.put(next, candidate);
          open.add(new PathNode(next, candidate));
        }
      }
    }
    return distance;
  }

  private void assignAgents(StandardEntityURN urn, List<EntityID> centres) {
    List<StandardEntity> agents = new ArrayList<>(worldInfo.getEntitiesOfType(urn));
    agents.sort(Comparator.comparingInt(agent -> agent.getID().getValue()));
    Set<Integer> available = new HashSet<>();
    for (int i = 0; i < clusterSize; i++) {
      available.add(i);
    }
    for (StandardEntity agent : agents) {
      int selected = nearestAvailableCluster(agent, centres, available);
      if (selected < 0) {
        selected = Math.floorMod(agent.getID().getValue(), clusterSize);
      }
      clusterIDs.get(selected).add(agent.getID());
      available.remove(selected);
    }
  }

  private int nearestAvailableCluster(StandardEntity agent, List<EntityID> centres,
      Set<Integer> available) {
    if (available.isEmpty()) {
      return -1;
    }
    StandardEntity position = agent instanceof rescuecore2.standard.entities.Human
        ? worldInfo.getPosition((rescuecore2.standard.entities.Human) agent) : agent;
    if (position == null) {
      return available.iterator().next();
    }
    int best = -1;
    long bestDistance = Long.MAX_VALUE;
    for (Integer index : available) {
      StandardEntity centre = worldInfo.getEntity(centres.get(index));
      long distance = directDistance(position, centre);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = index;
      }
    }
    return best;
  }

  private void rebuildIndex() {
    indexByID.clear();
    for (int i = 0; i < clusterIDs.size(); i++) {
      for (EntityID id : clusterIDs.get(i)) {
        indexByID.put(id, i);
      }
    }
  }

  private int clusterCount() {
    StandardEntityURN urn = agentInfo.me().getStandardURN();
    if (urn == StandardEntityURN.AMBULANCE_TEAM) {
      return Math.max(1, scenarioInfo.getScenarioAgentsAt());
    }
    if (urn == StandardEntityURN.FIRE_BRIGADE) {
      return Math.max(1, scenarioInfo.getScenarioAgentsFb());
    }
    if (urn == StandardEntityURN.POLICE_FORCE) {
      return Math.max(1, scenarioInfo.getScenarioAgentsPf());
    }
    return Math.max(1, developData.getInteger("victory.clustering.size", 1));
  }

  private int work(Area area) {
    if (area instanceof Building) {
      return 4;
    }
    if (area.getStandardURN() == StandardEntityURN.REFUGE
        || area.getStandardURN() == StandardEntityURN.GAS_STATION) {
      return 3;
    }
    return 1;
  }

  private int nearestCentre(Area area, List<Map<EntityID, Long>> distances) {
    int selected = 0;
    long best = Long.MAX_VALUE;
    for (int i = 0; i < distances.size(); i++) {
      long value = distances.get(i).getOrDefault(area.getID(), Long.MAX_VALUE);
      if (value < best) {
        best = value;
        selected = i;
      }
    }
    return selected;
  }

  private long nearestDistance(Area area, List<Map<EntityID, Long>> distances) {
    return distances.get(nearestCentre(area, distances)).getOrDefault(area.getID(), Long.MAX_VALUE / 8);
  }

  private long directDistance(StandardEntity first, StandardEntity second) {
    Pair<Integer, Integer> a = worldInfo.getLocation(first);
    Pair<Integer, Integer> b = worldInfo.getLocation(second);
    if (a == null || b == null) {
      return 1L;
    }
    return Math.max(1L, Math.round(Math.hypot((long) a.first() - b.first(),
        (long) a.second() - b.second())));
  }

  private String layoutKey(List<Area> areas, int count) {
    return count + ":" + areas.size() + ":" + areas.get(0).getID().getValue()
        + ":" + areas.get(areas.size() - 1).getID().getValue();
  }

  private static class PathNode {
    private final EntityID id;
    private final long cost;

    private PathNode(EntityID id, long cost) {
      this.id = id;
      this.cost = cost;
    }
  }

  private static class BaseLayout {
    private final List<EntityID> centres;
    private final List<List<EntityID>> clusters;

    private BaseLayout(List<EntityID> centres, List<List<EntityID>> clusters) {
      this.centres = new ArrayList<>(centres);
      this.clusters = new ArrayList<>();
      for (List<EntityID> cluster : clusters) {
        this.clusters.add(new ArrayList<>(cluster));
      }
    }

    private List<List<EntityID>> copyClusters() {
      List<List<EntityID>> copy = new ArrayList<>();
      for (List<EntityID> cluster : clusters) {
        copy.add(new ArrayList<>(cluster));
      }
      return copy;
    }
  }
}
