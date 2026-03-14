package ve.edu.unimet.so.project2.coordinator;

import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ve.edu.unimet.so.project2.coordinator.core.OperationApplyResult;
import ve.edu.unimet.so.project2.coordinator.core.PreparedJournalData;
import ve.edu.unimet.so.project2.coordinator.core.PreparedOperationCommand;
import ve.edu.unimet.so.project2.coordinator.core.SimulationCoordinator;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.journal.JournalStatus;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.journal.undo.CreateDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.UpdateRenameUndoData;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationCoordinatorTest {

    private SimulationCoordinator coordinator;

    @AfterEach
    @SuppressWarnings("unused")
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    @Test
    void readOperationRunsWithCoordinatorHandlerOnCoordinatorThread() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        coordinator = createCoordinator(lockTable, journalManager);

        AtomicReference<String> handlerThreadName = new AtomicReference<>();
        AtomicReference<String> diskThreadName = new AtomicReference<>();

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-1",
                "PROC-1",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/file-a",
                "file-a",
                40,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> {
                    handlerThreadName.set(Thread.currentThread().getName());
                    diskThreadName.set(diskResult.getServiceThreadName());
                    return OperationApplyResult.success();
                }));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot terminated = snapshot.getTerminatedProcessesSnapshot()[0];

        assertEquals("SimulationCoordinatorThread", handlerThreadName.get());
        assertEquals("DiskExecutionThread", diskThreadName.get());
        assertEquals(0, snapshot.getJournalEntriesSnapshot().length);
        assertEquals("PROC-1", terminated.getProcessId());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS, terminated.getResultStatus());
        assertEquals(1, snapshot.getDispatchHistorySnapshot().length);
        assertEquals(1, snapshot.getMaxConcurrentDiskTasksObserved());
    }

    @Test
    void readIsCompatibleWithExistingSharedReader() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        lockTable.tryAcquire("shared-file", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, journalManager);

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-2",
                "PROC-2",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/shared-file",
                "shared-file",
                22,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);

        assertEquals(1, snapshot.getTerminatedProcessesSnapshot().length);
        assertEquals(1, snapshot.getLocksSnapshot().length);
        assertEquals(1, snapshot.getLocksSnapshot()[0].getActiveLockCount());
        assertEquals(1, lockTable.getActiveLockCount("shared-file"));
    }

    @Test
    void updateOnFileBlocksWhenSharedReaderExists() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        lockTable.tryAcquire("file-locked", "reader-1", LockType.SHARED);
        coordinator = createCoordinator(lockTable, journalManager);

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-3",
                "PROC-3",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/file-locked",
                "file-locked",
                18,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("file-locked", "root", "/", "old.txt", "new.txt"),
                        "file-locked",
                        "user-1",
                        "rename locked file"),
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot blocked = snapshot.getBlockedProcessesSnapshot()[0];

        assertEquals("PROC-3", blocked.getProcessId());
        assertEquals(ve.edu.unimet.so.project2.process.WaitReason.WAITING_LOCK, blocked.getWaitReason());
        assertEquals("reader-1", blocked.getBlockedByProcessId());
        assertEquals(0, snapshot.getTerminatedProcessesSnapshot().length);
    }

    @Test
    void updateOnDirectorySkipsFileLockTableAndCommitsJournal() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        coordinator = createCoordinator(lockTable, journalManager);

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-4",
                "PROC-4",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.DIRECTORY,
                "/docs",
                "dir-1",
                9,
                0,
                null,
                new PreparedJournalData(
                        new UpdateRenameUndoData("dir-1", "root", "/", "docs", "docs-renamed"),
                        "dir-1",
                        "user-1",
                        "rename directory"),
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);

        assertEquals(0, snapshot.getLocksSnapshot().length);
        assertEquals(1, snapshot.getJournalEntriesSnapshot().length);
        assertEquals(JournalStatus.COMMITTED, snapshot.getJournalEntriesSnapshot()[0].getStatus());
    }

    @Test
    void failingHandlerLeavesJournalPendingUntilUndoExists() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        coordinator = createCoordinator(lockTable, journalManager);

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-5",
                "PROC-5",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.DIRECTORY,
                "/reports",
                "dir-2",
                12,
                0,
                null,
                new PreparedJournalData(
                        new UpdateRenameUndoData("dir-2", "root", "/", "reports", "reports-2026"),
                        "dir-2",
                        "user-1",
                        "rename reports"),
                (command, process, diskResult) -> OperationApplyResult.failed("simulated failure")));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot terminated = snapshot.getTerminatedProcessesSnapshot()[0];

        assertEquals(1, snapshot.getJournalEntriesSnapshot().length);
        assertEquals(JournalStatus.PENDING, snapshot.getJournalEntriesSnapshot()[0].getStatus());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.FAILED, terminated.getResultStatus());
        assertEquals("simulated failure", terminated.getErrorMessage());
        assertEquals(1, snapshot.getMaxConcurrentDiskTasksObserved());
    }

    @Test
    void blockedProcessWakesAfterExternalLockRelease() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        lockTable.tryAcquire("file-external", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, journalManager);

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-EXT",
                "PROC-EXT",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/file-external",
                "file-external",
                21,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("file-external", "root", "/", "locked.txt", "updated.txt"),
                        "file-external",
                        "user-1",
                        "update after external release"),
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot blockedSnapshot = waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);
        assertEquals("PROC-EXT", blockedSnapshot.getBlockedProcessesSnapshot()[0].getProcessId());

        lockTable.releaseByProcess("file-external", "external-reader");

        SimulationSnapshot completedSnapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot terminated = completedSnapshot.getTerminatedProcessesSnapshot()[0];

        assertEquals("PROC-EXT", terminated.getProcessId());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS, terminated.getResultStatus());
        assertEquals(0, completedSnapshot.getBlockedProcessesSnapshot().length);
        assertEquals(0, lockTable.getActiveLockCount("file-external"));
    }

    @Test
    void invalidQueuedCommandDoesNotKillCoordinatorLoop() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        coordinator = createCoordinator(lockTable, journalManager);

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-BAD",
                "PROC-BAD",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.DIRECTORY,
                "/dir-as-read",
                "dir-bad",
                15,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success()));
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-GOOD",
                "PROC-GOOD",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/good-file",
                "file-good",
                16,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);

        SimulationSnapshot.ProcessSnapshot first = snapshot.getTerminatedProcessesSnapshot()[0];
        SimulationSnapshot.ProcessSnapshot second = snapshot.getTerminatedProcessesSnapshot()[1];

        assertEquals("PROC-BAD", first.getProcessId());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.FAILED, first.getResultStatus());
        assertEquals("PROC-GOOD", second.getProcessId());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS, second.getResultStatus());
    }

    @Test
    void duplicateProcessIdIsRejectedBeforeAmbiguousContextLookup() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        coordinator = createCoordinator(lockTable, journalManager);

        AtomicInteger firstHandlerCalls = new AtomicInteger();
        AtomicInteger secondHandlerCalls = new AtomicInteger();

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-DUP-1",
                "PROC-DUP",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/dup-1",
                "file-dup-1",
                30,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> {
                    firstHandlerCalls.incrementAndGet();
                    return OperationApplyResult.success();
                }));
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-DUP-2",
                "PROC-DUP",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/dup-2",
                "file-dup-2",
                31,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> {
                    secondHandlerCalls.incrementAndGet();
                    return OperationApplyResult.success();
                }));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);
        SimulationSnapshot.ProcessSnapshot firstTerminated = findTerminatedByRequestId(snapshot, "REQ-DUP-1");
        SimulationSnapshot.ProcessSnapshot secondTerminated = findTerminatedByRequestId(snapshot, "REQ-DUP-2");

        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS, firstTerminated.getResultStatus());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.FAILED, secondTerminated.getResultStatus());
        assertTrue(secondTerminated.getErrorMessage().contains("duplicate processId"));
        assertEquals(1, firstHandlerCalls.get());
        assertEquals(0, secondHandlerCalls.get());
    }

    @Test
    void incompatibleUndoDataIsRejectedEarly() {
        assertThrows(IllegalArgumentException.class, () -> new PreparedOperationCommand(
                "REQ-UNDO",
                "PROC-UNDO",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.DIRECTORY,
                "/docs",
                "dir-undo",
                7,
                0,
                null,
                new PreparedJournalData(
                        new CreateDirectoryUndoData("dir-undo", "root", "/"),
                        "dir-undo",
                        "user-1",
                        "wrong undo type"),
                (command, process, diskResult) -> OperationApplyResult.success()));
    }

    @Test
    void outOfRangeTargetBlockIsRejectedBeforeDispatch() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        coordinator = createCoordinator(lockTable, journalManager);

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-RANGE",
                "PROC-RANGE",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/bad-block",
                "file-range",
                999,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot terminated = snapshot.getTerminatedProcessesSnapshot()[0];

        assertEquals("PROC-RANGE", terminated.getProcessId());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.FAILED, terminated.getResultStatus());
        assertEquals(0, snapshot.getDispatchHistorySnapshot().length);
        assertEquals(0, snapshot.getLocksSnapshot().length);
        assertEquals(999, terminated.getTargetBlock());
    }

    private SimulationCoordinator createCoordinator(LockTable lockTable, JournalManager journalManager) {
        return new SimulationCoordinator(
                new SimulatedDisk(200, 10, DiskHeadDirection.UP),
                lockTable,
                journalManager,
                DiskSchedulingPolicy.FIFO);
    }

    private SimulationSnapshot.ProcessSnapshot findTerminatedByRequestId(
            SimulationSnapshot snapshot,
            String requestId) {
        for (SimulationSnapshot.ProcessSnapshot process : snapshot.getTerminatedProcessesSnapshot()) {
            if (process.getRequestId().equals(requestId)) {
                return process;
            }
        }
        throw new AssertionError("terminated process not found for requestId: " + requestId);
    }

    private SimulationSnapshot waitForSnapshot(Predicate<SimulationSnapshot> predicate) {
        long deadline = System.currentTimeMillis() + 5000L;
        SimulationSnapshot latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = coordinator.getLatestSnapshot();
            if (latest != null && predicate.test(latest)) {
                return latest;
            }
            LockSupport.parkNanos(10_000_000L);
        }
        assertNotNull(latest);
        assertTrue(predicate.test(latest), "timeout waiting for coordinator snapshot condition");
        return latest;
    }
}
