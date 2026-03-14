package ve.edu.unimet.so.project2.coordinator.snapshot;

import ve.edu.unimet.so.project2.coordinator.state.CoordinatorProcessStore;
import ve.edu.unimet.so.project2.disk.DiskHead;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.journal.JournalEntry;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public final class SimulationSnapshotFactory {

    public SimulationSnapshot build(
            DiskSchedulingPolicy policy,
            SimulatedDisk disk,
            CoordinatorProcessStore processStore,
            LockTable lockTable,
            JournalManager journalManager,
            int totalSeekDistance,
            int maxConcurrentDiskTasksObserved) {
        DiskHead head = disk.getHead();
        return new SimulationSnapshot(
                policy,
                head.getCurrentBlock(),
                head.getDirection(),
                totalSeekDistance,
                maxConcurrentDiskTasksObserved,
                processStore.getRunningProcess() == null ? null : processStore.getRunningProcess().getProcessId(),
                processStore.getNewProcessSnapshots(),
                processStore.getReadyProcessSnapshots(),
                processStore.getBlockedProcessSnapshots(),
                processStore.getTerminatedProcessSnapshots(),
                buildLockSummaries(processStore, lockTable),
                buildJournalSummaries(journalManager),
                processStore.getDispatchHistorySnapshot());
    }

    private SimulationSnapshot.LockSummary[] buildLockSummaries(
            CoordinatorProcessStore processStore,
            LockTable lockTable) {
        String[] trackedFileIds = processStore.getTrackedLockFileIdsSnapshot();
        int activeCount = 0;
        for (String fileId : trackedFileIds) {
            if (lockTable.hasStateForFile(fileId)) {
                activeCount++;
            }
        }

        SimulationSnapshot.LockSummary[] summaries = new SimulationSnapshot.LockSummary[activeCount];
        int index = 0;
        for (String fileId : trackedFileIds) {
            if (!lockTable.hasStateForFile(fileId)) {
                continue;
            }
            summaries[index++] = new SimulationSnapshot.LockSummary(
                    fileId,
                    lockTable.getActiveLockCount(fileId),
                    lockTable.getWaitingCount(fileId),
                    lockTable.getPendingGrantCount(fileId));
        }
        return summaries;
    }

    private SimulationSnapshot.JournalEntrySummary[] buildJournalSummaries(JournalManager journalManager) {
        SimulationSnapshot.JournalEntrySummary[] summaries =
                new SimulationSnapshot.JournalEntrySummary[journalManager.getEntryCount()];
        for (int i = 0; i < journalManager.getEntryCount(); i++) {
            JournalEntry entry = journalManager.getEntryAt(i);
            summaries[i] = new SimulationSnapshot.JournalEntrySummary(
                    entry.getTransactionId(),
                    entry.getOperationType(),
                    entry.getTargetPath(),
                    entry.getStatus());
        }
        return summaries;
    }
}
