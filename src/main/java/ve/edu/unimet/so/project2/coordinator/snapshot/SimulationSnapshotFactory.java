package ve.edu.unimet.so.project2.coordinator.snapshot;

import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.coordinator.state.CoordinatorProcessStore;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.DiskHead;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.journal.JournalEntry;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public final class SimulationSnapshotFactory {

    public SimulationSnapshot build(
            DiskSchedulingPolicy policy,
            SimulatedDisk disk,
            SimulationApplicationState applicationState,
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
                processStore.getDispatchHistorySnapshot(),
                buildSessionSummary(applicationState),
                buildFileSystemSummaries(applicationState),
                buildDiskBlockSummaries(disk));
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

    private SimulationSnapshot.SessionSummary buildSessionSummary(SimulationApplicationState applicationState) {
        return new SimulationSnapshot.SessionSummary(
                applicationState.getSessionContext().getCurrentUserId(),
                applicationState.getSessionContext().getCurrentUser().getUsername(),
                applicationState.getSessionContext().getCurrentRole());
    }

    private SimulationSnapshot.FileSystemNodeSummary[] buildFileSystemSummaries(
            SimulationApplicationState applicationState) {
        FsNode[] nodes = applicationState.getFileSystemCatalog().getAllNodesSnapshot();
        SimulationSnapshot.FileSystemNodeSummary[] summaries =
                new SimulationSnapshot.FileSystemNodeSummary[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            FsNode node = nodes[i];
            if (node instanceof FileNode file) {
                summaries[i] = new SimulationSnapshot.FileSystemNodeSummary(
                        file.getId(),
                        file.getParent() == null ? null : file.getParent().getId(),
                        file.getPath(),
                        file.getName(),
                        file.getType(),
                        file.getOwnerUserId(),
                        file.getPermissions().isPublicReadable(),
                        file.getSizeInBlocks(),
                        file.getFirstBlockIndex(),
                        file.getColorId(),
                        file.isSystemFile(),
                        false);
                continue;
            }

            summaries[i] = new SimulationSnapshot.FileSystemNodeSummary(
                    node.getId(),
                    node.getParent() == null ? null : node.getParent().getId(),
                    node.getPath(),
                    node.getName(),
                    node.getType(),
                    node.getOwnerUserId(),
                    node.getPermissions().isPublicReadable(),
                    0,
                    -1,
                    null,
                    false,
                    node.isRoot());
        }
        return summaries;
    }

    private SimulationSnapshot.DiskBlockSummary[] buildDiskBlockSummaries(SimulatedDisk disk) {
        SimulationSnapshot.DiskBlockSummary[] summaries =
                new SimulationSnapshot.DiskBlockSummary[disk.getTotalBlocks()];
        for (int i = 0; i < disk.getTotalBlocks(); i++) {
            DiskBlock block = disk.getBlock(i);
            summaries[i] = new SimulationSnapshot.DiskBlockSummary(
                    block.getIndex(),
                    block.isFree(),
                    block.getOwnerFileId(),
                    block.getNextBlockIndex(),
                    block.isSystemReserved());
        }
        return summaries;
    }
}
