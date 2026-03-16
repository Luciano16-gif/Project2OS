package ve.edu.unimet.so.project2.scenario;

import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;

public record LoadedScenarioState(
        SimulatedDisk disk,
        SimulationApplicationState applicationState,
        ScenarioOperationIntent[] scenarioIntents) {
}
