# Victory team submission notes

## Current delivery state

This delivery is restored to the end of Iteration 3, the last version that
completed map smoke testing. A later, unverified ambulance triage experiment
has been discarded. No source under `adf-core-java-master`,
`rcrs-server-master`, or any `Tactics` package is part of this delivery or was
changed for it.

## Modified code

All active custom code is under `src/main/java/victory/`:

- `VictoryBuildingDetector.java` — selects nearby cluster fires by fire stage,
  temperature, and gas-station risk.
- `VictoryHumanDetector.java` — keeps a valid casualty target and prioritises
  the shortest estimated survival window.
- `VictoryRoadDetector.java` — clears observed blockaded roads, prioritising
  refuge access, trapped people, and burning-building access.
- `VictorySearch.java` — searches a cluster systematically without repeatedly
  returning to an already reached target.
- `VictoryAmbulanceTargetAllocator.java` — assigns available ambulances to
  distinct viable casualties.
- `VictoryFireTargetAllocator.java` — reserves centre-side fire commands for
  buried emergency responders; active fire suppression stays responsive and
  local to each fire brigade.
- `VictoryPoliceTargetAllocator.java` — assigns distinct known blocked roads
  to available police, with refuge roads first.

### Iteration 2

The three centre allocators now preserve an assignment while its agent and
target remain valid. They assign the most urgent remaining target to the
nearest unassigned capable agent, and only reassign after rescue completion,
road clearance, or agent incapacitation. This prevents command thrashing on
large maps.

### Iteration 3

- `VictoryPathPlanning` replaces the framework's hop-count breadth-first
  planner with weighted multi-goal Dijkstra. It prices routes by geometric road
  length, adds a dynamic penalty for observed blockaded roads, invalidates its
  LRU cache when road information changes, and is configured for all movement,
  transport, extinguishing, clearing, and command-execution modules.
- `VictoryClustering` uses deterministic farthest-first centre selection,
  weighted shortest-path distance from centres, and a building-workload penalty
  to create reproducible, less uneven static partitions.

`config/module.cfg` maps only these permitted modules and `config/launch.cfg`
sets `team.name: victory`.

## Original code

The unmodified sample complex-module files are retained in
`original-code/sample_team_complex/`.  The original `module.cfg` and
`launch.cfg` are retained alongside them with an `.original` suffix.
They are evidence only and are not compiled or loaded by Victory.

## Build compatibility

The upstream online `master` artifacts now require Java 21 while the supplied
competition server and ADF source use Java 17. `settings.gradle` therefore
uses the supplied sibling `rcrs-server-master` and `adf-core-java-master`
projects as composite builds. Keep all three folders next to each other and
build with Java 17.

## Verification performed

- `gradlew.bat --no-daemon build` completed successfully using the supplied
  Java 17 server and ADF source.
- A no-GUI RCRS `test` map server and its simulation components were started.
- Victory connected successfully as one Fire Brigade, one Ambulance Team, and
  one Police Force. The runtime log showed all three Victory clustering
  modules initialising and no module-loading exception.
- A Kobe-map regression connected all 93 available agents (30 of each platoon
  type plus the three centres) with no Victory module exception.
- The post-iteration Kobe smoke test connected two of each platoon type plus
  all three centres (9 agents) and ran without an exception in the client log.
- The path-planning and clustering iteration compiled successfully and passed
  a test-map smoke run with Fire Brigade, Ambulance Team, and Police Force
  connected and no client exception.
