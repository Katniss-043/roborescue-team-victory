package victory;

import adf.core.component.tactics.TacticsFireBrigade;
import adf.impl.DefaultLoader;
import victory.tactics.VictoryTacticsFireBrigade;

/** Loads Victory-specific tactics while retaining the ADF defaults elsewhere. */
public class VictoryLoader extends DefaultLoader {

  @Override
  public TacticsFireBrigade getTacticsFireBrigade() {
    return new VictoryTacticsFireBrigade();
  }
}
