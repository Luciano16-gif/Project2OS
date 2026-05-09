package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.process.ProcessControlBlock;
import ve.edu.unimet.so.project2.scenario.ScenarioOperationIntent;

record ScenarioReadyRegistration(
        ProcessControlBlock process,
        ScenarioOperationIntent intent) {
}
