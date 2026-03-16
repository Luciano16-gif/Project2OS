package ve.edu.unimet.so.project2.coordinator.state;

import org.junit.jupiter.api.Test;
import ve.edu.unimet.so.project2.coordinator.core.OperationApplyResult;
import ve.edu.unimet.so.project2.coordinator.core.PreparedOperationCommand;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.process.IoRequest;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;
import ve.edu.unimet.so.project2.process.ResultStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorProcessStoreTest {

    @Test
    void addRejectedTerminatedProcessRemovesStoredContext() {
        CoordinatorProcessStore store = new CoordinatorProcessStore();
        PreparedOperationCommand command = buildReadCommand("REQ-1", "PROC-1");
        ProcessControlBlock process = buildProcess(command, 0L);
        process.markReady(1L);
        store.registerReadyScenarioProcess(process, new ve.edu.unimet.so.project2.scenario.ScenarioOperationIntent(
                5,
                IoOperationType.READ,
                1));

        store.removeReadyProcessById("PROC-1");
        store.addRejectedTerminatedProcess("PROC-1", "REQ-1", "/file-a", 5, "failed resolution");

        assertThrows(IllegalArgumentException.class, () -> store.requireContext("PROC-1"));
        assertTrue(store.containsProcessId("PROC-1"));
        assertEquals(1, store.getTerminatedProcessSnapshots().length);
    }

    @Test
    void addTerminatedProcessRemovesStoredContext() {
        CoordinatorProcessStore store = new CoordinatorProcessStore();
        PreparedOperationCommand command = buildReadCommand("REQ-2", "PROC-2");
        ProcessControlBlock process = buildProcess(command, 0L);
        process.markReady(1L);
        store.registerReadyProcess(process, command);

        ProcessControlBlock running = store.removeReadyProcessById("PROC-2");
        running.markRunning(2L);
        store.setRunningProcess(running);
        running.markTerminated(ResultStatus.SUCCESS, 3L, null);
        store.addTerminatedProcess(running);
        store.clearRunningProcess();

        assertThrows(IllegalArgumentException.class, () -> store.requireContext("PROC-2"));
        assertTrue(store.containsProcessId("PROC-2"));
        assertEquals(1, store.getTerminatedProcessSnapshots().length);
    }

    private PreparedOperationCommand buildReadCommand(String requestId, String processId) {
        return new PreparedOperationCommand(
                requestId,
                processId,
                "user-1",
                IoOperationType.READ,
                FsNodeType.FILE,
                "/file-a",
                "FILE-1",
                5,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success());
    }

    private ProcessControlBlock buildProcess(PreparedOperationCommand command, long creationTick) {
        IoRequest request = new IoRequest(
                command.getRequestId(),
                command.getProcessId(),
                command.getOperationType(),
                command.getTargetNodeType(),
                command.getTargetPath(),
                command.getTargetNodeId(),
                command.getTargetBlock(),
                command.getRequestedSizeInBlocks(),
                command.getOwnerUserId(),
                0L);
        return new ProcessControlBlock(
                command.getProcessId(),
                command.getOwnerUserId(),
                request,
                command.getTargetNodeType(),
                command.getTargetNodeId(),
                command.getTargetPath(),
                command.getTargetBlock(),
                command.getRequiredLockType(),
                creationTick);
    }
}
