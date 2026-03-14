package ve.edu.unimet.so.project2.coordinator.snapshot;

import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.journal.JournalStatus;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.process.ProcessState;
import ve.edu.unimet.so.project2.process.ResultStatus;
import ve.edu.unimet.so.project2.process.WaitReason;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public final class SimulationSnapshot {

    private final DiskSchedulingPolicy policy;
    private final int headBlock;
    private final DiskHeadDirection headDirection;
    private final int totalSeekDistance;
    private final int maxConcurrentDiskTasksObserved;
    private final String runningProcessId;
    private final ProcessSnapshot[] newProcesses;
    private final ProcessSnapshot[] readyProcesses;
    private final ProcessSnapshot[] blockedProcesses;
    private final ProcessSnapshot[] terminatedProcesses;
    private final LockSummary[] locks;
    private final JournalEntrySummary[] journalEntries;
    private final DispatchRecord[] dispatchHistory;

    public SimulationSnapshot(
            DiskSchedulingPolicy policy,
            int headBlock,
            DiskHeadDirection headDirection,
            int totalSeekDistance,
            int maxConcurrentDiskTasksObserved,
            String runningProcessId,
            ProcessSnapshot[] newProcesses,
            ProcessSnapshot[] readyProcesses,
            ProcessSnapshot[] blockedProcesses,
            ProcessSnapshot[] terminatedProcesses,
            LockSummary[] locks,
            JournalEntrySummary[] journalEntries,
            DispatchRecord[] dispatchHistory) {
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
        this.policy = policy;
        this.headBlock = headBlock;
        this.headDirection = headDirection;
        this.totalSeekDistance = totalSeekDistance;
        this.maxConcurrentDiskTasksObserved = maxConcurrentDiskTasksObserved;
        this.runningProcessId = normalizeOptional(runningProcessId);
        this.newProcesses = copyProcessSnapshots(newProcesses);
        this.readyProcesses = copyProcessSnapshots(readyProcesses);
        this.blockedProcesses = copyProcessSnapshots(blockedProcesses);
        this.terminatedProcesses = copyProcessSnapshots(terminatedProcesses);
        this.locks = copyLockSummaries(locks);
        this.journalEntries = copyJournalEntries(journalEntries);
        this.dispatchHistory = copyDispatchRecords(dispatchHistory);
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

    public DispatchRecord[] getDispatchHistorySnapshot() {
        return copyDispatchRecords(dispatchHistory);
    }

    private static ProcessSnapshot[] copyProcessSnapshots(ProcessSnapshot[] source) {
        if (source == null) {
            return new ProcessSnapshot[0];
        }
        ProcessSnapshot[] copy = new ProcessSnapshot[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static LockSummary[] copyLockSummaries(LockSummary[] source) {
        if (source == null) {
            return new LockSummary[0];
        }
        LockSummary[] copy = new LockSummary[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static JournalEntrySummary[] copyJournalEntries(JournalEntrySummary[] source) {
        if (source == null) {
            return new JournalEntrySummary[0];
        }
        JournalEntrySummary[] copy = new JournalEntrySummary[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static DispatchRecord[] copyDispatchRecords(DispatchRecord[] source) {
        if (source == null) {
            return new DispatchRecord[0];
        }
        DispatchRecord[] copy = new DispatchRecord[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static final class ProcessSnapshot {

        private final String processId;
        private final String requestId;
        private final ProcessState state;
        private final WaitReason waitReason;
        private final ResultStatus resultStatus;
        private final String targetPath;
        private final int targetBlock;
        private final String blockedByProcessId;
        private final String errorMessage;

        public ProcessSnapshot(
                String processId,
                String requestId,
                ProcessState state,
                WaitReason waitReason,
                ResultStatus resultStatus,
                String targetPath,
                int targetBlock,
                String blockedByProcessId,
                String errorMessage) {
            this.processId = requireNonBlank(processId, "processId");
            this.requestId = requireNonBlank(requestId, "requestId");
            if (state == null) {
                throw new IllegalArgumentException("state cannot be null");
            }
            if (waitReason == null) {
                throw new IllegalArgumentException("waitReason cannot be null");
            }
            this.state = state;
            this.waitReason = waitReason;
            this.resultStatus = resultStatus;
            this.targetPath = requireNonBlank(targetPath, "targetPath");
            if (targetBlock < 0) {
                throw new IllegalArgumentException("targetBlock cannot be negative");
            }
            this.targetBlock = targetBlock;
            this.blockedByProcessId = normalizeOptional(blockedByProcessId);
            this.errorMessage = normalizeOptional(errorMessage);
        }

        public String getProcessId() {
            return processId;
        }

        public String getRequestId() {
            return requestId;
        }

        public ProcessState getState() {
            return state;
        }

        public WaitReason getWaitReason() {
            return waitReason;
        }

        public ResultStatus getResultStatus() {
            return resultStatus;
        }

        public String getTargetPath() {
            return targetPath;
        }

        public int getTargetBlock() {
            return targetBlock;
        }

        public String getBlockedByProcessId() {
            return blockedByProcessId;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    public static final class LockSummary {

        private final String fileId;
        private final int activeLockCount;
        private final int waitingCount;
        private final int pendingGrantCount;

        public LockSummary(String fileId, int activeLockCount, int waitingCount, int pendingGrantCount) {
            this.fileId = requireNonBlank(fileId, "fileId");
            if (activeLockCount < 0 || waitingCount < 0 || pendingGrantCount < 0) {
                throw new IllegalArgumentException("lock counters cannot be negative");
            }
            this.activeLockCount = activeLockCount;
            this.waitingCount = waitingCount;
            this.pendingGrantCount = pendingGrantCount;
        }

        public String getFileId() {
            return fileId;
        }

        public int getActiveLockCount() {
            return activeLockCount;
        }

        public int getWaitingCount() {
            return waitingCount;
        }

        public int getPendingGrantCount() {
            return pendingGrantCount;
        }
    }

    public static final class JournalEntrySummary {

        private final String transactionId;
        private final IoOperationType operationType;
        private final String targetPath;
        private final JournalStatus status;

        public JournalEntrySummary(
                String transactionId,
                IoOperationType operationType,
                String targetPath,
                JournalStatus status) {
            this.transactionId = requireNonBlank(transactionId, "transactionId");
            if (operationType == null) {
                throw new IllegalArgumentException("operationType cannot be null");
            }
            this.targetPath = requireNonBlank(targetPath, "targetPath");
            if (status == null) {
                throw new IllegalArgumentException("status cannot be null");
            }
            this.operationType = operationType;
            this.status = status;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public IoOperationType getOperationType() {
            return operationType;
        }

        public String getTargetPath() {
            return targetPath;
        }

        public JournalStatus getStatus() {
            return status;
        }
    }

    public static final class DispatchRecord {

        private final String processId;
        private final String requestId;
        private final int previousHeadBlock;
        private final int newHeadBlock;
        private final int traveledDistance;
        private final DiskHeadDirection direction;

        public DispatchRecord(
                String processId,
                String requestId,
                int previousHeadBlock,
                int newHeadBlock,
                int traveledDistance,
                DiskHeadDirection direction) {
            this.processId = requireNonBlank(processId, "processId");
            this.requestId = requireNonBlank(requestId, "requestId");
            if (previousHeadBlock < 0 || newHeadBlock < 0 || traveledDistance < 0) {
                throw new IllegalArgumentException("dispatch numeric values cannot be negative");
            }
            if (direction == null) {
                throw new IllegalArgumentException("direction cannot be null");
            }
            this.previousHeadBlock = previousHeadBlock;
            this.newHeadBlock = newHeadBlock;
            this.traveledDistance = traveledDistance;
            this.direction = direction;
        }

        public String getProcessId() {
            return processId;
        }

        public String getRequestId() {
            return requestId;
        }

        public int getPreviousHeadBlock() {
            return previousHeadBlock;
        }

        public int getNewHeadBlock() {
            return newHeadBlock;
        }

        public int getTraveledDistance() {
            return traveledDistance;
        }

        public DiskHeadDirection getDirection() {
            return direction;
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
