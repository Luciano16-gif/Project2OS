package ve.edu.unimet.so.project2.coordinator.state;

import ve.edu.unimet.so.project2.coordinator.core.PreparedOperationCommand;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot;
import ve.edu.unimet.so.project2.datastructures.LinkedQueue;
import ve.edu.unimet.so.project2.datastructures.SimpleList;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;
import ve.edu.unimet.so.project2.process.ProcessState;
import ve.edu.unimet.so.project2.process.ResultStatus;
import ve.edu.unimet.so.project2.process.WaitReason;

public final class CoordinatorProcessStore {

    private final LinkedQueue<ProcessControlBlock> newProcesses;
    private final SimpleList<ProcessControlBlock> readyProcesses;
    private final SimpleList<ProcessControlBlock> blockedProcesses;
    private final SimpleList<SimulationSnapshot.ProcessSnapshot> terminatedProcesses;
    private final SimpleList<ProcessExecutionContext> processContexts;
    private final SimpleList<SimulationSnapshot.DispatchRecord> dispatchHistory;
    private final SimpleList<String> trackedLockFileIds;

    private ProcessControlBlock runningProcess;

    public CoordinatorProcessStore() {
        this.newProcesses = new LinkedQueue<>();
        this.readyProcesses = new SimpleList<>();
        this.blockedProcesses = new SimpleList<>();
        this.terminatedProcesses = new SimpleList<>();
        this.processContexts = new SimpleList<>();
        this.dispatchHistory = new SimpleList<>();
        this.trackedLockFileIds = new SimpleList<>();
        this.runningProcess = null;
    }

    public void registerSubmittedProcess(ProcessControlBlock process, PreparedOperationCommand command) {
        processContexts.add(new ProcessExecutionContext(process, command));
        newProcesses.enqueue(process);
    }

    public boolean containsProcessId(String processId) {
        for (int i = 0; i < processContexts.size(); i++) {
            if (processContexts.get(i).getProcess().getProcessId().equals(processId)) {
                return true;
            }
        }
        for (int i = 0; i < terminatedProcesses.size(); i++) {
            if (terminatedProcesses.get(i).getProcessId().equals(processId)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsRequestId(String requestId) {
        for (int i = 0; i < processContexts.size(); i++) {
            if (processContexts.get(i).getProcess().getRequest().getRequestId().equals(requestId)) {
                return true;
            }
        }
        for (int i = 0; i < terminatedProcesses.size(); i++) {
            if (terminatedProcesses.get(i).getRequestId().equals(requestId)) {
                return true;
            }
        }
        return false;
    }

    public ProcessControlBlock admitNextNewProcess() {
        return newProcesses.dequeue();
    }

    public void addReadyProcess(ProcessControlBlock process) {
        readyProcesses.add(process);
    }

    public ProcessControlBlock[] getReadySnapshot() {
        return toProcessArray(readyProcesses.toArray());
    }

    public ProcessControlBlock removeReadyProcessById(String processId) {
        for (int i = 0; i < readyProcesses.size(); i++) {
            ProcessControlBlock process = readyProcesses.get(i);
            if (process.getProcessId().equals(processId)) {
                readyProcesses.removeAt(i);
                return process;
            }
        }
        return null;
    }

    public void addBlockedProcess(ProcessControlBlock process) {
        blockedProcesses.add(process);
    }

    public ProcessControlBlock removeBlockedProcessById(String processId) {
        for (int i = 0; i < blockedProcesses.size(); i++) {
            ProcessControlBlock process = blockedProcesses.get(i);
            if (process.getProcessId().equals(processId)) {
                blockedProcesses.removeAt(i);
                return process;
            }
        }
        return null;
    }

    public void addTerminatedProcess(ProcessControlBlock process) {
        terminatedProcesses.add(toProcessSnapshot(process));
    }

    public void addRejectedTerminatedProcess(PreparedOperationCommand command, String errorMessage) {
        addRejectedTerminatedProcess(
                command.getProcessId(),
                command.getRequestId(),
                command.getTargetPath(),
                command.getTargetBlock(),
                errorMessage);
    }

    public void addRejectedTerminatedProcess(
            String processId,
            String requestId,
            String targetPath,
            int targetBlock,
            String errorMessage) {
        terminatedProcesses.add(new SimulationSnapshot.ProcessSnapshot(
                processId,
                requestId,
                ProcessState.TERMINATED,
                WaitReason.NONE,
                ResultStatus.FAILED,
                targetPath,
                targetBlock,
                null,
                normalizeErrorMessage(errorMessage)));
    }

    public ProcessControlBlock getRunningProcess() {
        return runningProcess;
    }

    public boolean hasActiveProcesses() {
        return runningProcess != null
                || !newProcesses.isEmpty()
                || !readyProcesses.isEmpty()
                || !blockedProcesses.isEmpty();
    }

    public void setRunningProcess(ProcessControlBlock runningProcess) {
        this.runningProcess = runningProcess;
    }

    public void clearRunningProcess() {
        this.runningProcess = null;
    }

    public ProcessExecutionContext requireContext(String processId) {
        for (int i = 0; i < processContexts.size(); i++) {
            ProcessExecutionContext context = processContexts.get(i);
            if (context.getProcess().getProcessId().equals(processId)) {
                return context;
            }
        }
        throw new IllegalArgumentException("process context not found for processId: " + processId);
    }

    public void registerDispatch(SimulationSnapshot.DispatchRecord record) {
        dispatchHistory.add(record);
    }

    public SimulationSnapshot.DispatchRecord[] getDispatchHistorySnapshot() {
        SimulationSnapshot.DispatchRecord[] snapshot =
                new SimulationSnapshot.DispatchRecord[dispatchHistory.size()];
        for (int i = 0; i < dispatchHistory.size(); i++) {
            snapshot[i] = dispatchHistory.get(i);
        }
        return snapshot;
    }

    public void trackLockFileId(String fileId) {
        if (fileId == null) {
            return;
        }
        for (int i = 0; i < trackedLockFileIds.size(); i++) {
            if (trackedLockFileIds.get(i).equals(fileId)) {
                return;
            }
        }
        trackedLockFileIds.add(fileId);
    }

    public String[] getTrackedLockFileIdsSnapshot() {
        Object[] snapshot = trackedLockFileIds.toArray();
        String[] result = new String[snapshot.length];
        for (int i = 0; i < snapshot.length; i++) {
            result[i] = (String) snapshot[i];
        }
        return result;
    }

    public SimulationSnapshot.ProcessSnapshot[] getNewProcessSnapshots() {
        return toSnapshotArray(newProcesses.toArray());
    }

    public SimulationSnapshot.ProcessSnapshot[] getReadyProcessSnapshots() {
        return toSnapshotArray(readyProcesses.toArray());
    }

    public SimulationSnapshot.ProcessSnapshot[] getBlockedProcessSnapshots() {
        return toSnapshotArray(blockedProcesses.toArray());
    }

    public ProcessControlBlock[] getBlockedSnapshot() {
        return toProcessArray(blockedProcesses.toArray());
    }

    public SimulationSnapshot.ProcessSnapshot[] getTerminatedProcessSnapshots() {
        Object[] source = terminatedProcesses.toArray();
        SimulationSnapshot.ProcessSnapshot[] result = new SimulationSnapshot.ProcessSnapshot[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = (SimulationSnapshot.ProcessSnapshot) source[i];
        }
        return result;
    }

    private SimulationSnapshot.ProcessSnapshot[] toSnapshotArray(Object[] source) {
        SimulationSnapshot.ProcessSnapshot[] result = new SimulationSnapshot.ProcessSnapshot[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = toProcessSnapshot((ProcessControlBlock) source[i]);
        }
        return result;
    }

    private ProcessControlBlock[] toProcessArray(Object[] source) {
        ProcessControlBlock[] result = new ProcessControlBlock[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = (ProcessControlBlock) source[i];
        }
        return result;
    }

    private SimulationSnapshot.ProcessSnapshot toProcessSnapshot(ProcessControlBlock process) {
        return new SimulationSnapshot.ProcessSnapshot(
                process.getProcessId(),
                process.getRequest().getRequestId(),
                process.getState(),
                process.getWaitReason(),
                process.getResultStatus(),
                process.getTargetPath(),
                process.getTargetBlock(),
                process.getBlockedByProcessId(),
                process.getErrorMessage());
    }

    private String normalizeErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return "rejected operation";
        }
        String normalized = errorMessage.trim();
        return normalized.isEmpty() ? "rejected operation" : normalized;
    }
}
