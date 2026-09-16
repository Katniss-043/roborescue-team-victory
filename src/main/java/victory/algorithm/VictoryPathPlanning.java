package victory.algorithm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.PathPlanning;
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
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

/**
 * Weighted multi-goal Dijkstra with dynamic blockade penalties and a small LRU cache.
 * The framework's default class is breadth-first search despite its Dijkstra name.
 */
public class VictoryPathPlanning extends PathPlanning {

  private static final int CACHE_SIZE = 96;
  private static final long BLOCKED_ROAD_PENALTY = 80_000L;
  private static final int BLOCKED_ROAD_MULTIPLIER = 8;

  private final Map<String, CachedPath> cache = new LinkedHashMap<String, CachedPath>(
      CACHE_SIZE, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, CachedPath> eldest) {
      return size() > CACHE_SIZE;
    }
  };

  private EntityID from;
  private Collection<EntityID> destinations = Collections.emptyList();
  private List<EntityID> result;
  private int roadVersion;

  public VictoryPathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }

  @Override
  public PathPlanning setFrom(EntityID id) {
    this.from = id;
    return this;
  }

  @Override
  public PathPlanning setDestination(Collection<EntityID> targets) {
    this.destinations = targets == null ? Collections.emptyList() : new ArrayList<>(targets);
    return this;
  }

  @Override
  public VictoryPathPlanning updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    boolean changedRoad = false;
    for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
      if (worldInfo.getEntity(id) instanceof Road) {
        changedRoad = true;
        break;
      }
    }
    if (changedRoad) {
      roadVersion++;
      cache.clear();
    }
    return this;
  }

  @Override
  public VictoryPathPlanning calc() {
    if (from == null || destinations.isEmpty()) {
      result = null;
      return this;
    }
    Set<EntityID> targets = new HashSet<>(destinations);
    String key = cacheKey(from, targets);
    CachedPath cached = cache.get(key);
    if (cached != null && cached.roadVersion == roadVersion) {
      result = new ArrayList<>(cached.path);
      return this;
    }
    if (targets.contains(from)) {
      result = Collections.singletonList(from);
      cache.put(key, new CachedPath(roadVersion, result));
      return this;
    }

    PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingLong(node -> node.cost));
    Map<EntityID, Long> distance = new HashMap<>();
    Map<EntityID, EntityID> previous = new HashMap<>();
    distance.put(from, 0L);
    open.add(new Node(from, 0L));
    EntityID goal = null;

    while (!open.isEmpty()) {
      Node current = open.poll();
      long known = distance.getOrDefault(current.id, Long.MAX_VALUE);
      if (current.cost != known) {
        continue;
      }
      if (targets.contains(current.id)) {
        goal = current.id;
        break;
      }
      StandardEntity entity = worldInfo.getEntity(current.id);
      if (!(entity instanceof Area)) {
        continue;
      }
      for (EntityID next : ((Area) entity).getNeighbours()) {
        if (!(worldInfo.getEntity(next) instanceof Area)) {
          continue;
        }
        long candidate = current.cost + edgeCost(current.id, next);
        if (candidate < distance.getOrDefault(next, Long.MAX_VALUE)) {
          distance.put(next, candidate);
          previous.put(next, current.id);
          open.add(new Node(next, candidate));
        }
      }
    }

    result = goal == null ? null : reconstruct(goal, previous);
    if (result != null) {
      cache.put(key, new CachedPath(roadVersion, result));
    }
    return this;
  }

  private long edgeCost(EntityID first, EntityID second) {
    StandardEntity a = worldInfo.getEntity(first);
    StandardEntity b = worldInfo.getEntity(second);
    long length = centreDistance(a, b);
    if (b instanceof Road && isBlocked((Road) b)) {
      return length * BLOCKED_ROAD_MULTIPLIER + BLOCKED_ROAD_PENALTY;
    }
    return length;
  }

  private long centreDistance(StandardEntity first, StandardEntity second) {
    Pair<Integer, Integer> a = worldInfo.getLocation(first);
    Pair<Integer, Integer> b = worldInfo.getLocation(second);
    if (a == null || b == null) {
      return 1L;
    }
    long x = (long) a.first() - b.first();
    long y = (long) a.second() - b.second();
    return Math.max(1L, Math.round(Math.hypot(x, y)));
  }

  private boolean isBlocked(Road road) {
    return road.isBlockadesDefined() && !road.getBlockades().isEmpty();
  }

  private List<EntityID> reconstruct(EntityID goal, Map<EntityID, EntityID> previous) {
    List<EntityID> path = new ArrayList<>();
    EntityID current = goal;
    path.add(current);
    while (!current.equals(from)) {
      current = previous.get(current);
      if (current == null) {
        return null;
      }
      path.add(current);
    }
    Collections.reverse(path);
    return path;
  }

  private String cacheKey(EntityID source, Set<EntityID> targets) {
    List<Integer> ids = new ArrayList<>();
    for (EntityID id : targets) {
      ids.add(id.getValue());
    }
    Collections.sort(ids);
    return source.getValue() + ":" + ids;
  }

  @Override
  public List<EntityID> getResult() {
    return result;
  }

  private static class Node {
    private final EntityID id;
    private final long cost;

    private Node(EntityID id, long cost) {
      this.id = id;
      this.cost = cost;
    }
  }

  private static class CachedPath {
    private final int roadVersion;
    private final List<EntityID> path;

    private CachedPath(int roadVersion, List<EntityID> path) {
      this.roadVersion = roadVersion;
      this.path = new ArrayList<>(path);
    }
  }
}
