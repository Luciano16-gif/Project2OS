package ve.edu.unimet.so.project2.persistence;

import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public record LoadedSystemState(
        SimulatedDisk disk,
        SimulationApplicationState applicationState,
        JournalManager journalManager,
        DiskSchedulingPolicy policy) {
}
