package victory.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.BuildingDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/** Chooses a fire by urgency first and distance second, while avoiding other clusters. */
public class VictoryBuildingDetector extends BuildingDetector {

  private final Clustering clustering;
  private EntityID result;

  public VictoryBuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.clustering = moduleManager.getModule("VictoryBuildingDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(this.clustering);
  }

  @Override
  public BuildingDetector updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }

  @Override
  public BuildingDetector calc() {
    List<Building> fires = new ArrayList<>();
    Collection<StandardEntity> entities = worldInfo.getEntitiesOfType(
        StandardEntityURN.BUILDING, StandardEntityURN.GAS_STATION,
        StandardEntityURN.AMBULANCE_CENTRE, StandardEntityURN.FIRE_STATION,
        StandardEntityURN.POLICE_OFFICE);
    for (StandardEntity entity : entities) {
      if (entity instanceof Building && ((Building) entity).isOnFire()) {
        fires.add((Building) entity);
      }
    }
    List<Building> localFires = inMyCluster(fires);
    if (!localFires.isEmpty()) {
      fires = localFires;
    }
    fires.sort(Comparator.comparingLong(this::priority).reversed()
        .thenComparingInt(building -> distance(agentInfo.me(), building)));
    result = fires.isEmpty() ? null : fires.get(0).getID();
    return this;
  }

  private List<Building> inMyCluster(List<Building> candidates) {
    int index = clustering.getClusterIndex(agentInfo.getID());
    Set<StandardEntity> entities = new HashSet<>(clustering.getClusterEntities(index));
    List<Building> local = new ArrayList<>();
    for (Building building : candidates) {
      if (entities.contains(building)) {
        local.add(building);
      }
    }
    return local;
  }

  private long priority(Building building) {
    int fieryness = building.isFierynessDefined() ? building.getFieryness() : 1;
    long stage = fieryness == 1 ? 6_000_000L : fieryness == 2 ? 5_000_000L
        : fieryness == 3 ? 1_000_000L : 3_000_000L;
    long temperature = building.isTemperatureDefined() ? building.getTemperature() : 0;
    // Gas stations must be handled early because their escalation is especially costly.
    long gasBonus = building.getStandardURN() == StandardEntityURN.GAS_STATION ? 2_000_000L : 0;
    return stage + gasBonus + Math.min(temperature, 1_000_000);
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
