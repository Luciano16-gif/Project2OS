package ve.edu.unimet.so.project2.coordinator.snapshot;

import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.coordinator.log.SystemEventLog;
import ve.edu.unimet.so.project2.coordinator.log.SystemEventLogEntry;
import ve.edu.unimet.so.project2.coordinator.state.CoordinatorProcessStore;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.DiskHead;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.journal.JournalEntry;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.locking.FileLock;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.locking.LockWaitEntry;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public final class SimulationSnapshotFactory {

    public SimulationSnapshot build(
            DiskSchedulingPolicy policy,
            SimulatedDisk disk,
            SimulationApplicationState applicationState,
            CoordinatorProcessStore processStore,
            LockTable lockTable,
            JournalManager journalManager,
            SystemEventLog eventLog,
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
                buildRunningProcessSnapshot(processStore.getRunningProcess()),
                processStore.getNewProcessSnapshots(),
                processStore.getReadyProcessSnapshots(),
                processStore.getBlockedProcessSnapshots(),
                processStore.getTerminatedProcessSnapshots(),
                buildLockSummaries(lockTable),
                buildJournalSummaries(journalManager),
                buildEventLogSummaries(eventLog),
                processStore.getDispatchHistorySnapshot(),
                buildSessionSummary(applicationState),
                buildFileSystemSummaries(applicationState),
                buildDiskBlockSummaries(disk));
    }

    private SimulationSnapshot.ProcessSnapshot buildRunningProcessSnapshot(ProcessControlBlock process) {
        if (process == null) {
            return null;
        }
        return new SimulationSnapshot.ProcessSnapshot(
                process.getProcessId(),
                process.getRequest().getRequestId(),
                process.getState(),
                process.getWaitReason(),
                process.getResultStatus(),
                process.getRequest().getOperationType(),
                process.getOwnerUserId(),
                toLockTypeSummary(process.getRequiredLockType()),
                process.getTargetPath(),
                process.getTargetBlock(),
                process.getBlockedByProcessId(),
                process.getErrorMessage());
    }

    private SimulationSnapshot.LockSummary[] buildLockSummaries(LockTable lockTable) {
        String[] trackedFileIds = lockTable.getTrackedFileIdsSnapshot();
        SimulationSnapshot.LockSummary[] summaries = new SimulationSnapshot.LockSummary[trackedFileIds.length];
        for (String fileId : trackedFileIds) {
            SimulationSnapshot.ActiveLockSummary[] activeLocks = buildActiveLockSummaries(
                    lockTable.getActiveLocksSnapshot(fileId));
            SimulationSnapshot.WaitingLockSummary[] waitingEntries = buildWaitingLockSummaries(
                    lockTable.getWaitingQueueSnapshot(fileId));
            SimulationSnapshot.WaitingLockSummary[] pendingGrantEntries = buildWaitingLockSummaries(
                    lockTable.getPendingGrantSnapshot(fileId));
            summaries[findTargetIndex(summaries)] = new SimulationSnapshot.LockSummary(
                    fileId,
                    activeLocks,
                    waitingEntries,
                    pendingGrantEntries,
                    lockTable.getActiveLockCount(fileId),
                    lockTable.getWaitingCount(fileId),
                    lockTable.getPendingGrantCount(fileId));
        }
        return summaries;
    }

    private int findTargetIndex(SimulationSnapshot.LockSummary[] summaries) {
        for (int i = 0; i < summaries.length; i++) {
            if (summaries[i] == null) {
                return i;
            }
        }
        throw new IllegalStateException("lock summary array is unexpectedly full");
    }

    private SimulationSnapshot.ActiveLockSummary[] buildActiveLockSummaries(Object[] source) {
        SimulationSnapshot.ActiveLockSummary[] summaries =
                new SimulationSnapshot.ActiveLockSummary[source.length];
        for (int i = 0; i < source.length; i++) {
            FileLock lock = (FileLock) source[i];
            summaries[i] = new SimulationSnapshot.ActiveLockSummary(
                    toLockTypeSummary(lock.getType()),
                    lock.getOwnerProcessId());
        }
        return summaries;
    }

    private SimulationSnapshot.WaitingLockSummary[] buildWaitingLockSummaries(Object[] source) {
        SimulationSnapshot.WaitingLockSummary[] summaries =
                new SimulationSnapshot.WaitingLockSummary[source.length];
        for (int i = 0; i < source.length; i++) {
            LockWaitEntry entry = (LockWaitEntry) source[i];
            summaries[i] = new SimulationSnapshot.WaitingLockSummary(
                    entry.getProcessId(),
                    toLockTypeSummary(entry.getRequestedLockType()));
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

    private SimulationSnapshot.EventLogEntrySummary[] buildEventLogSummaries(SystemEventLog eventLog) {
        SystemEventLogEntry[] entries = eventLog.getEntriesSnapshot();
        SimulationSnapshot.EventLogEntrySummary[] summaries =
                new SimulationSnapshot.EventLogEntrySummary[entries.length];
        for (int i = 0; i < entries.length; i++) {
            summaries[i] = new SimulationSnapshot.EventLogEntrySummary(
                    entries[i].getSequenceNumber(),
                    entries[i].getTick(),
                    entries[i].getCategory(),
                    entries[i].getMessage());
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
                    block.getOccupiedByProcessId(),
                    block.getNextBlockIndex(),
                    block.isSystemReserved());
        }
        return summaries;
    }

    private SimulationSnapshot.LockTypeSummary toLockTypeSummary(LockType lockType) {
        if (lockType == null) {
            return null;
        }
        return lockType == LockType.SHARED
                ? SimulationSnapshot.LockTypeSummary.SHARED
                : SimulationSnapshot.LockTypeSummary.EXCLUSIVE;
    }
}
