package victory.extaction;

import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.impl.extaction.DefaultExtActionTransport;
import victory.util.AmbulancePickupPolicy;

/** Rejects load actions based only on stale radio or centre information. */
public class VictoryExtActionTransport extends DefaultExtActionTransport {

  public VictoryExtActionTransport(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }

  @Override
  public VictoryExtActionTransport calc() {
    super.calc();
    if (result instanceof ActionLoad) {
      ActionLoad load = (ActionLoad) result;
      if (!AmbulancePickupPolicy.canLoad(agentInfo, worldInfo, load.getTarget())) {
        result = null;
      }
    }
    return this;
  }
}
