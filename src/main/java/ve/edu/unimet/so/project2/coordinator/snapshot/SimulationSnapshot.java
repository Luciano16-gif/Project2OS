package ve.edu.unimet.so.project2.coordinator.snapshot;

import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.journal.JournalStatus;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.process.ProcessState;
import ve.edu.unimet.so.project2.process.ResultStatus;
import ve.edu.unimet.so.project2.process.WaitReason;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;
import ve.edu.unimet.so.project2.session.Role;

public final class SimulationSnapshot {

    private final DiskSchedulingPolicy policy;
    private final int headBlock;
    private final DiskHeadDirection headDirection;
    private final int totalSeekDistance;
    private final int maxConcurrentDiskTasksObserved;
    private final String runningProcessId;
    private final ProcessSnapshot runningProcess;
    private final ProcessSnapshot[] newProcesses;
    private final ProcessSnapshot[] readyProcesses;
    private final ProcessSnapshot[] blockedProcesses;
    private final ProcessSnapshot[] terminatedProcesses;
    private final LockSummary[] locks;
    private final JournalEntrySummary[] journalEntries;
    private final EventLogEntrySummary[] eventLogEntries;
    private final DispatchRecord[] dispatchHistory;
    private final SessionSummary sessionSummary;
    private final FileSystemNodeSummary[] fileSystemNodes;
    private final DiskBlockSummary[] diskBlocks;

    public SimulationSnapshot(
            DiskSchedulingPolicy policy,
            int headBlock,
            DiskHeadDirection headDirection,
            int totalSeekDistance,
            int maxConcurrentDiskTasksObserved,
            String runningProcessId,
            ProcessSnapshot runningProcess,
            ProcessSnapshot[] newProcesses,
            ProcessSnapshot[] readyProcesses,
            ProcessSnapshot[] blockedProcesses,
            ProcessSnapshot[] terminatedProcesses,
            LockSummary[] locks,
            JournalEntrySummary[] journalEntries,
            EventLogEntrySummary[] eventLogEntries,
            DispatchRecord[] dispatchHistory,
            SessionSummary sessionSummary,
            FileSystemNodeSummary[] fileSystemNodes,
            DiskBlockSummary[] diskBlocks) {
        if (policy == null) {
            throw new IllegalArgumentException("policy cannot be null");
        }
        if (headBlock < 0) {
            throw new IllegalArgumentException("headBlock cannot be negative");
        }
        if (headDirection == null) {
            throw new IllegalArgumentException("headDirection cannot be null");
        }
        if (totalSeekDistance < 0) {
            throw new IllegalArgumentException("totalSeekDistance cannot be negative");
        }
        if (maxConcurrentDiskTasksObserved < 0) {
            throw new IllegalArgumentException("maxConcurrentDiskTasksObserved cannot be negative");
        }
        if (sessionSummary == null) {
            throw new IllegalArgumentException("sessionSummary cannot be null");
        }
        this.policy = policy;
        this.headBlock = headBlock;
        this.headDirection = headDirection;
        this.totalSeekDistance = totalSeekDistance;
        this.maxConcurrentDiskTasksObserved = maxConcurrentDiskTasksObserved;
        this.runningProcessId = normalizeOptional(runningProcessId);
        this.runningProcess = runningProcess;
        this.newProcesses = copyProcessSnapshots(newProcesses);
        this.readyProcesses = copyProcessSnapshots(readyProcesses);
        this.blockedProcesses = copyProcessSnapshots(blockedProcesses);
        this.terminatedProcesses = copyProcessSnapshots(terminatedProcesses);
        this.locks = copyLockSummaries(locks);
        this.journalEntries = copyJournalEntries(journalEntries);
        this.eventLogEntries = copyEventLogEntries(eventLogEntries);
        this.dispatchHistory = copyDispatchRecords(dispatchHistory);
        this.sessionSummary = sessionSummary;
        this.fileSystemNodes = copyFileSystemNodes(fileSystemNodes);
        this.diskBlocks = copyDiskBlocks(diskBlocks);
    }

    public DiskSchedulingPolicy getPolicy() {
        return policy;
    }

    public int getHeadBlock() {
        return headBlock;
    }

    public DiskHeadDirection getHeadDirection() {
        return headDirection;
    }

    public int getTotalSeekDistance() {
        return totalSeekDistance;
    }

    public int getMaxConcurrentDiskTasksObserved() {
        return maxConcurrentDiskTasksObserved;
    }

    public String getRunningProcessId() {
        return runningProcessId;
    }

    public ProcessSnapshot getRunningProcessSnapshot() {
        return runningProcess;
    }

    public ProcessSnapshot[] getNewProcessesSnapshot() {
        return copyProcessSnapshots(newProcesses);
    }

    public ProcessSnapshot[] getReadyProcessesSnapshot() {
        return copyProcessSnapshots(readyProcesses);
    }

    public ProcessSnapshot[] getBlockedProcessesSnapshot() {
        return copyProcessSnapshots(blockedProcesses);
    }

    public ProcessSnapshot[] getTerminatedProcessesSnapshot() {
        return copyProcessSnapshots(terminatedProcesses);
    }

    public LockSummary[] getLocksSnapshot() {
        return copyLockSummaries(locks);
    }

    public JournalEntrySummary[] getJournalEntriesSnapshot() {
        return copyJournalEntries(journalEntries);
    }

    public EventLogEntrySummary[] getEventLogEntriesSnapshot() {
        return copyEventLogEntries(eventLogEntries);
    }

    public DispatchRecord[] getDispatchHistorySnapshot() {
        return copyDispatchRecords(dispatchHistory);
    }

    public SessionSummary getSessionSummary() {
        return sessionSummary;
    }

    public FileSystemNodeSummary[] getFileSystemNodesSnapshot() {
        return copyFileSystemNodes(fileSystemNodes);
    }

    public DiskBlockSummary[] getDiskBlocksSnapshot() {
        return copyDiskBlocks(diskBlocks);
    }

    public record ProcessSnapshot(
            String processId,
            String requestId,
            ProcessState state,
            WaitReason waitReason,
            ResultStatus resultStatus,
            IoOperationType operationType,
            String ownerUserId,
            LockTypeSummary requiredLockType,
            String targetPath,
            int targetBlock,
            String blockedByProcessId,
            String errorMessage) {

        public ProcessSnapshot {
            processId = requireNonBlank(processId, "processId");
            requestId = requireNonBlank(requestId, "requestId");
            ownerUserId = requireNonBlank(ownerUserId, "ownerUserId");
            targetPath = requireNonBlank(targetPath, "targetPath");
            blockedByProcessId = normalizeOptional(blockedByProcessId);
            errorMessage = normalizeOptional(errorMessage);
            if (state == null) {
                throw new IllegalArgumentException("state cannot be null");
            }
            if (waitReason == null) {
                throw new IllegalArgumentException("waitReason cannot be null");
            }
            if (targetBlock < 0) {
                throw new IllegalArgumentException("targetBlock cannot be negative");
            }
        }

        public String getProcessId() { return processId; }
        public String getRequestId() { return requestId; }
        public ProcessState getState() { return state; }
        public WaitReason getWaitReason() { return waitReason; }
        public ResultStatus getResultStatus() { return resultStatus; }
        public IoOperationType getOperationType() { return operationType; }
        public String getOwnerUserId() { return ownerUserId; }
        public LockTypeSummary getRequiredLockType() { return requiredLockType; }
        public String getTargetPath() { return targetPath; }
        public int getTargetBlock() { return targetBlock; }
        public String getBlockedByProcessId() { return blockedByProcessId; }
        public String getErrorMessage() { return errorMessage; }
    }

    public record LockSummary(
            String fileId,
            ActiveLockSummary[] activeLocks,
            WaitingLockSummary[] waitingEntries,
            WaitingLockSummary[] pendingGrantEntries,
            int activeLockCount,
            int waitingCount,
            int pendingGrantCount) {

        public LockSummary {
            fileId = requireNonBlank(fileId, "fileId");
            activeLocks = copyActiveLocks(activeLocks);
            waitingEntries = copyWaitingEntries(waitingEntries);
            pendingGrantEntries = copyWaitingEntries(pendingGrantEntries);
            if (activeLockCount < 0 || waitingCount < 0 || pendingGrantCount < 0) {
                throw new IllegalArgumentException("lock counters cannot be negative");
            }
        }

        public String getFileId() { return fileId; }
        public ActiveLockSummary[] getActiveLocksSnapshot() { return copyActiveLocks(activeLocks); }
        public WaitingLockSummary[] getWaitingEntriesSnapshot() { return copyWaitingEntries(waitingEntries); }
        public WaitingLockSummary[] getPendingGrantEntriesSnapshot() { return copyWaitingEntries(pendingGrantEntries); }
        public int getActiveLockCount() { return activeLockCount; }
        public int getWaitingCount() { return waitingCount; }
        public int getPendingGrantCount() { return pendingGrantCount; }
    }

    public record ActiveLockSummary(LockTypeSummary type, String ownerProcessId) {
        public ActiveLockSummary {
            ownerProcessId = requireNonBlank(ownerProcessId, "ownerProcessId");
            if (type == null) {
                throw new IllegalArgumentException("type cannot be null");
            }
        }

        public LockTypeSummary getType() { return type; }
        public String getOwnerProcessId() { return ownerProcessId; }
    }

    public record WaitingLockSummary(String processId, LockTypeSummary requestedLockType) {
        public WaitingLockSummary {
            processId = requireNonBlank(processId, "processId");
            if (requestedLockType == null) {
                throw new IllegalArgumentException("requestedLockType cannot be null");
            }
        }

        public String getProcessId() { return processId; }
        public LockTypeSummary getRequestedLockType() { return requestedLockType; }
    }

    public record JournalEntrySummary(
            String transactionId,
            IoOperationType operationType,
            String targetPath,
            String ownerUserId,
            JournalStatus status) {

        public JournalEntrySummary {
            transactionId = requireNonBlank(transactionId, "transactionId");
            targetPath = requireNonBlank(targetPath, "targetPath");
            ownerUserId = normalizeOptional(ownerUserId);
            if (operationType == null) {
                throw new IllegalArgumentException("operationType cannot be null");
            }
            if (status == null) {
                throw new IllegalArgumentException("status cannot be null");
            }
        }

        public String getTransactionId() { return transactionId; }
        public IoOperationType getOperationType() { return operationType; }
        public String getTargetPath() { return targetPath; }
        public String getOwnerUserId() { return ownerUserId; }
        public JournalStatus getStatus() { return status; }
    }

    public record EventLogEntrySummary(long sequenceNumber, long tick, String category, String message) {
        public EventLogEntrySummary {
            category = requireNonBlank(category, "category");
            message = requireNonBlank(message, "message");
            if (sequenceNumber <= 0) {
                throw new IllegalArgumentException("sequenceNumber must be positive");
            }
            if (tick < 0) {
                throw new IllegalArgumentException("tick cannot be negative");
            }
        }

        public long getSequenceNumber() { return sequenceNumber; }
        public long getTick() { return tick; }
        public String getCategory() { return category; }
        public String getMessage() { return message; }
    }

    public record DispatchRecord(
            String processId,
            String requestId,
            int previousHeadBlock,
            int newHeadBlock,
            int traveledDistance,
            DiskHeadDirection direction) {

        public DispatchRecord {
            processId = requireNonBlank(processId, "processId");
            requestId = requireNonBlank(requestId, "requestId");
            if (previousHeadBlock < 0 || newHeadBlock < 0 || traveledDistance < 0) {
                throw new IllegalArgumentException("dispatch numeric values cannot be negative");
            }
            if (direction == null) {
                throw new IllegalArgumentException("direction cannot be null");
            }
        }

        public String getProcessId() { return processId; }
        public String getRequestId() { return requestId; }
        public int getPreviousHeadBlock() { return previousHeadBlock; }
        public int getNewHeadBlock() { return newHeadBlock; }
        public int getTraveledDistance() { return traveledDistance; }
        public DiskHeadDirection getDirection() { return direction; }
    }

    public record SessionSummary(String currentUserId, String currentUsername, Role currentRole) {
        public SessionSummary {
            currentUserId = requireNonBlank(currentUserId, "currentUserId");
            currentUsername = requireNonBlank(currentUsername, "currentUsername");
            if (currentRole == null) {
                throw new IllegalArgumentException("currentRole cannot be null");
            }
        }

        public String getCurrentUserId() { return currentUserId; }
        public String getCurrentUsername() { return currentUsername; }
        public Role getCurrentRole() { return currentRole; }
    }

    public record FileSystemNodeSummary(
            String nodeId,
            String parentNodeId,
            String path,
            String name,
            FsNodeType type,
            String ownerUserId,
            boolean publicReadable,
            int sizeInBlocks,
            int firstBlockIndex,
            String colorId,
            boolean systemFile,
            boolean root) {

        public FileSystemNodeSummary {
            nodeId = requireNonBlank(nodeId, "nodeId");
            parentNodeId = normalizeOptional(parentNodeId);
            path = requireNonBlank(path, "path");
            name = requireNonBlank(name, "name");
            ownerUserId = requireNonBlank(ownerUserId, "ownerUserId");
            colorId = normalizeOptional(colorId);
            if (type == null) {
                throw new IllegalArgumentException("type cannot be null");
            }
            if (sizeInBlocks < 0) {
                throw new IllegalArgumentException("sizeInBlocks cannot be negative");
            }
            if (firstBlockIndex < -1) {
                throw new IllegalArgumentException("firstBlockIndex cannot be less than -1");
            }
        }

        public String getNodeId() { return nodeId; }
        public String getParentNodeId() { return parentNodeId; }
        public String getPath() { return path; }
        public String getName() { return name; }
        public FsNodeType getType() { return type; }
        public String getOwnerUserId() { return ownerUserId; }
        public boolean isPublicReadable() { return publicReadable; }
        public int getSizeInBlocks() { return sizeInBlocks; }
        public int getFirstBlockIndex() { return firstBlockIndex; }
        public String getColorId() { return colorId; }
        public boolean isSystemFile() { return systemFile; }
        public boolean isRoot() { return root; }
    }

    public record DiskBlockSummary(
            int index,
            boolean free,
            String ownerFileId,
            String occupiedByProcessId,
            int nextBlockIndex,
            boolean systemReserved) {

        public DiskBlockSummary {
            ownerFileId = normalizeOptional(ownerFileId);
            occupiedByProcessId = normalizeOptional(occupiedByProcessId);
            if (index < 0) {
                throw new IllegalArgumentException("index cannot be negative");
            }
            if (nextBlockIndex < -1) {
                throw new IllegalArgumentException("nextBlockIndex cannot be less than -1");
            }
        }

        public int getIndex() { return index; }
        public boolean isFree() { return free; }
        public String getOwnerFileId() { return ownerFileId; }
        public String getOccupiedByProcessId() { return occupiedByProcessId; }
        public int getNextBlockIndex() { return nextBlockIndex; }
        public boolean isSystemReserved() { return systemReserved; }
    }

    public enum LockTypeSummary {
        SHARED,
        EXCLUSIVE
    }

    private static ProcessSnapshot[] copyProcessSnapshots(ProcessSnapshot[] source) {
        return source == null ? new ProcessSnapshot[0] : source.clone();
    }

    private static LockSummary[] copyLockSummaries(LockSummary[] source) {
        return source == null ? new LockSummary[0] : source.clone();
    }

    private static JournalEntrySummary[] copyJournalEntries(JournalEntrySummary[] source) {
        return source == null ? new JournalEntrySummary[0] : source.clone();
    }

    private static DispatchRecord[] copyDispatchRecords(DispatchRecord[] source) {
        return source == null ? new DispatchRecord[0] : source.clone();
    }

    private static EventLogEntrySummary[] copyEventLogEntries(EventLogEntrySummary[] source) {
        return source == null ? new EventLogEntrySummary[0] : source.clone();
    }

    private static FileSystemNodeSummary[] copyFileSystemNodes(FileSystemNodeSummary[] source) {
        return source == null ? new FileSystemNodeSummary[0] : source.clone();
    }

    private static DiskBlockSummary[] copyDiskBlocks(DiskBlockSummary[] source) {
        return source == null ? new DiskBlockSummary[0] : source.clone();
    }

    private static ActiveLockSummary[] copyActiveLocks(ActiveLockSummary[] source) {
        return source == null ? new ActiveLockSummary[0] : source.clone();
    }

    private static WaitingLockSummary[] copyWaitingEntries(WaitingLockSummary[] source) {
        return source == null ? new WaitingLockSummary[0] : source.clone();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
