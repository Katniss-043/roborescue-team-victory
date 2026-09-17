package victory.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/** Keeps a viable casualty target and ranks short survival windows ahead of proximity. */
public class VictoryHumanDetector extends HumanDetector {

  private final Clustering clustering;
  private EntityID result;

  public VictoryHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.clustering = moduleManager.getModule("VictoryHumanDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(this.clustering);
  }

  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
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
    if (isRescuable(existing)) {
      return this;
    }
    List<Human> targets = collectTargets();
    List<Human> local = inMyCluster(targets);
    if (!local.isEmpty()) {
      targets = local;
    }
    targets.sort(Comparator.comparingLong(this::survivalWindow)
        .thenComparingInt(target -> distance(agentInfo.me(), target))
        .thenComparingInt(target -> target.getID().getValue()));
    result = targets.isEmpty() ? null : targets.get(0).getID();
    return this;
  }

  private List<Human> collectTargets() {
    List<Human> targets = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN,
        StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.FIRE_BRIGADE,
        StandardEntityURN.POLICE_FORCE)) {
      if (!entity.getID().equals(agentInfo.getID()) && isRescuable(entity)) {
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
    return buriedness > 0 || damage > 0;
  }

  private long survivalWindow(Human human) {
    int damage = human.isDamageDefined() ? human.getDamage() : 0;
    return damage <= 0 ? Long.MAX_VALUE / 4 : (long) human.getHP() * 1000L / damage;
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
