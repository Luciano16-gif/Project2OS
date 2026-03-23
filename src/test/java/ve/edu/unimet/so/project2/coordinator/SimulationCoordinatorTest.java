package ve.edu.unimet.so.project2.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ve.edu.unimet.so.project2.application.ApplicationIntentPlanner;
import ve.edu.unimet.so.project2.application.CreateFileIntent;
import ve.edu.unimet.so.project2.application.DeleteIntent;
import ve.edu.unimet.so.project2.application.PermissionService;
import ve.edu.unimet.so.project2.application.ReadIntent;
import ve.edu.unimet.so.project2.application.RenameIntent;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.application.SwitchSessionIntent;
import ve.edu.unimet.so.project2.coordinator.channel.CoordinatorChannels;
import ve.edu.unimet.so.project2.coordinator.command.CoordinatorCommand;
import ve.edu.unimet.so.project2.coordinator.core.OperationApplyResult;
import ve.edu.unimet.so.project2.coordinator.core.PreparedJournalData;
import ve.edu.unimet.so.project2.coordinator.core.PreparedOperationCommand;
import ve.edu.unimet.so.project2.coordinator.core.SimulationCoordinator;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.AccessPermissions;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FileSystemCatalog;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.journal.JournalStatus;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.journal.undo.CreateDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.JournalBlockSnapshot;
import ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot;
import ve.edu.unimet.so.project2.journal.undo.UpdateRenameUndoData;
import ve.edu.unimet.so.project2.persistence.JournalRecoveryService;
import ve.edu.unimet.so.project2.persistence.PersistedSystemState;
import ve.edu.unimet.so.project2.persistence.SystemPersistenceService;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.process.ResultStatus;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;
import ve.edu.unimet.so.project2.session.Role;
import ve.edu.unimet.so.project2.session.SessionContext;
import ve.edu.unimet.so.project2.session.User;
import ve.edu.unimet.so.project2.session.UserStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void processSnapshotsExposeOperationOwnerAndRequiredLock() {
        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("file-meta", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-META",
                "PROC-META",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/file-meta",
                "file-meta",
                44,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("file-meta", "root", "/", "meta.txt", "meta-renamed.txt"),
                        "file-meta",
                        "user-1",
                        "rename metadata file"),
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot blocked = snapshot.getBlockedProcessesSnapshot()[0];

        assertEquals(ve.edu.unimet.so.project2.process.IoOperationType.UPDATE, blocked.getOperationType());
        assertEquals("user-1", blocked.getOwnerUserId());
        assertEquals(SimulationSnapshot.LockTypeSummary.EXCLUSIVE, blocked.getRequiredLockType());
    }

    @Test
    void lockSnapshotsExposeOwnersAndWaitingEntries() {
        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("file-lock-view", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());

        coordinator.start();
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-LOCK-VIEW",
                "PROC-LOCK-VIEW",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/file-lock-view",
                "file-lock-view",
                41,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("file-lock-view", "root", "/", "locked.txt", "renamed.txt"),
                        "file-lock-view",
                        "user-1",
                        "rename lock view file"),
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);
        SimulationSnapshot.LockSummary lockSummary = snapshot.getLocksSnapshot()[0];

        assertEquals("file-lock-view", lockSummary.getFileId());
        assertEquals(1, lockSummary.getActiveLocksSnapshot().length);
        assertEquals(SimulationSnapshot.LockTypeSummary.SHARED, lockSummary.getActiveLocksSnapshot()[0].getType());
        assertEquals("external-reader", lockSummary.getActiveLocksSnapshot()[0].getOwnerProcessId());
        assertEquals(1, lockSummary.getWaitingEntriesSnapshot().length);
        assertEquals("PROC-LOCK-VIEW", lockSummary.getWaitingEntriesSnapshot()[0].getProcessId());
        assertEquals(
                SimulationSnapshot.LockTypeSummary.EXCLUSIVE,
                lockSummary.getWaitingEntriesSnapshot()[0].getRequestedLockType());
    }

    @Test
    void runningProcessSnapshotMarksDiskOccupancyByProcess() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-OCC",
                "PROC-OCC",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/file-occupy",
                "file-occupy",
                44,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> {
                    LockSupport.parkNanos(50_000_000L);
                    return OperationApplyResult.success();
                }));

        SimulationSnapshot runningSnapshot = waitForSnapshot(s -> s.getRunningProcessSnapshot() != null);
        assertEquals("PROC-OCC", runningSnapshot.getRunningProcessSnapshot().getProcessId());
        assertEquals("PROC-OCC", runningSnapshot.getDiskBlocksSnapshot()[44].getOccupiedByProcessId());

        waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
    }

    @Test
    void simulatedFailureLeavesPendingJournalUntilRecoveredInMemory() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.armSimulatedFailure();
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "crash-file.txt", 1, false, false));

        SimulationSnapshot crashedSnapshot = waitForSnapshot(s -> {
            SimulationSnapshot.ProcessSnapshot terminated = findTerminatedByPath(s, "/home-user-1/crash-file.txt");
            return terminated != null
                    && terminated.getResultStatus() == ResultStatus.CANCELLED
                    && findFileSystemNodeByPath(s, "/home-user-1/crash-file.txt") != null
                    && s.getJournalEntriesSnapshot().length == 1
                    && s.getJournalEntriesSnapshot()[0].getStatus() == JournalStatus.PENDING;
        });

        SimulationSnapshot.ProcessSnapshot crashed =
                findTerminatedByPath(crashedSnapshot, "/home-user-1/crash-file.txt");
        assertEquals(ResultStatus.CANCELLED, crashed.getResultStatus());
        assertTrue(crashed.getErrorMessage().contains("simulated crash"));

        coordinator.recoverPendingJournalEntries();
        SimulationSnapshot recoveredSnapshot = waitForSnapshot(s ->
                findFileSystemNodeByPath(s, "/home-user-1/crash-file.txt") == null
                        && s.getJournalEntriesSnapshot().length == 1
                        && s.getJournalEntriesSnapshot()[0].getStatus() == JournalStatus.UNDONE);

        assertNull(findFileSystemNodeByPath(recoveredSnapshot, "/home-user-1/crash-file.txt"));
    }

    @Test
    void simulatedFailureStateCanBeSavedAndRecoveredOnLoad() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.armSimulatedFailure();
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "persisted-crash.txt", 1, false, false));
        waitForSnapshot(s -> {
            SimulationSnapshot.ProcessSnapshot terminated = findTerminatedByPath(s, "/home-user-1/persisted-crash.txt");
            return terminated != null
                    && terminated.getResultStatus() == ResultStatus.CANCELLED
                    && findFileSystemNodeByPath(s, "/home-user-1/persisted-crash.txt") != null
                    && s.getJournalEntriesSnapshot().length == 1
                    && s.getJournalEntriesSnapshot()[0].getStatus() == JournalStatus.PENDING;
        });

        Path savePath = Files.createTempFile("project2os-simulated-crash", ".json");
        coordinator.saveSystem(savePath);
        coordinator.loadSystem(savePath);

        SimulationSnapshot recoveredSnapshot = waitForSnapshot(s ->
                findFileSystemNodeByPath(s, "/home-user-1/persisted-crash.txt") == null
                        && s.getJournalEntriesSnapshot().length == 1
                        && s.getJournalEntriesSnapshot()[0].getStatus() == JournalStatus.UNDONE);

        assertNull(findFileSystemNodeByPath(recoveredSnapshot, "/home-user-1/persisted-crash.txt"));
    }

    @Test
    void simulatedCrashQuarantinesLaterQueuedWorkUntilRecovery() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.armSimulatedFailure();
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "first-crash.txt", 1, false, false));
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "second-queued.txt", 1, false, false));

        SimulationSnapshot crashedSnapshot = waitForSnapshot(s -> {
            SimulationSnapshot.ProcessSnapshot first = findTerminatedByPath(s, "/home-user-1/first-crash.txt");
            SimulationSnapshot.ProcessSnapshot second = findTerminatedByPath(s, "/home-user-1/second-queued.txt");
            return first != null
                    && first.getResultStatus() == ResultStatus.CANCELLED
                    && second != null
                    && second.getResultStatus() == ResultStatus.CANCELLED;
        });

        assertNotNull(findFileSystemNodeByPath(crashedSnapshot, "/home-user-1/first-crash.txt"));
        assertNull(findFileSystemNodeByPath(crashedSnapshot, "/home-user-1/second-queued.txt"));
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.submitIntent(new CreateFileIntent("/home-user-1", "blocked-after-crash.txt", 1, false, false)));

        coordinator.recoverPendingJournalEntries();
        waitForSnapshot(s -> findFileSystemNodeByPath(s, "/home-user-1/first-crash.txt") == null);
    }

    @Test
    void simulatedCrashInvalidatesExternalLocksSoRecoveryCanProceed() {
        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("other-file", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());
        coordinator.start();

        coordinator.armSimulatedFailure();
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "external-lock-crash.txt", 1, false, false));

        waitForSnapshot(s -> {
            SimulationSnapshot.ProcessSnapshot terminated =
                    findTerminatedByPath(s, "/home-user-1/external-lock-crash.txt");
            return terminated != null && terminated.getResultStatus() == ResultStatus.CANCELLED;
        });

        coordinator.recoverPendingJournalEntries();
        SimulationSnapshot recoveredSnapshot = waitForSnapshot(s ->
                findFileSystemNodeByPath(s, "/home-user-1/external-lock-crash.txt") == null
                        && s.getJournalEntriesSnapshot().length == 1
                        && s.getJournalEntriesSnapshot()[0].getStatus() == JournalStatus.UNDONE);

        assertEquals(0, recoveredSnapshot.getLocksSnapshot().length);
        assertNull(findFileSystemNodeByPath(recoveredSnapshot, "/home-user-1/external-lock-crash.txt"));
    }

    @Test
    void queuedIntentCancellationUsesEffectiveSessionAfterQueuedSwitch() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        ApplicationIntentPlanner planner = new ApplicationIntentPlanner(
                getPrivateField(coordinator, "applicationState", SimulationApplicationState.class),
                getPrivateField(coordinator, "disk", SimulatedDisk.class),
                new PermissionService());
        PreparedOperationCommand originalCrashCommand = planner.plan(
                new CreateFileIntent("/home-user-1", "crash-owner-base.txt", 1, false, false),
                "REQ-OWNER-CRASH-1",
                "PROC-OWNER-CRASH-1");
        PreparedOperationCommand slowCrashCommand = wrapCommandWithDelay(originalCrashCommand, 50_000_000L);

        coordinator.armSimulatedFailure();
        coordinator.submitOperation(slowCrashCommand);
        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "queued-after-switch-owner.txt", 1, false, false));

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                findTerminatedByPath(s, "/home-user-1/queued-after-switch-owner.txt") != null);
        SimulationSnapshot.ProcessSnapshot cancelled =
                findTerminatedByPath(snapshot, "/home-user-1/queued-after-switch-owner.txt");

        assertNotNull(cancelled);
        assertEquals(ResultStatus.CANCELLED, cancelled.getResultStatus());
        assertEquals("user-1", cancelled.getOwnerUserId());
    }

    @Test
    void eventLogCapturesCoordinatorLifecycleEvents() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-LOG",
                "PROC-LOG",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/file-log",
                "file-log",
                30,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success()));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        assertTrue(snapshot.getEventLogEntriesSnapshot().length > 0);
        assertTrue(hasEventWithCategory(snapshot, "PROCESS"));
        assertTrue(hasEventMessageContaining(snapshot, "submitted process PROC-LOG"));
        assertTrue(hasEventWithCategory(snapshot, "DISPATCH"));
    }

    @Test
    void switchSessionConvenienceMethodUpdatesSnapshot() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.switchSession("user-1");

        SimulationSnapshot snapshot = waitForSnapshot(
                s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));
        assertEquals("user-1", snapshot.getSessionSummary().getCurrentUserId());
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

    @Test
    void switchSessionIntentUpdatesSnapshot() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());

        coordinator.start();
        coordinator.submitIntent(new SwitchSessionIntent("user-1"));

        SimulationSnapshot snapshot = waitForSnapshot(
                s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));

        assertEquals("user-1", snapshot.getSessionSummary().getCurrentUserId());
        assertEquals(Role.USER, snapshot.getSessionSummary().getCurrentRole());
    }

    @Test
    void invalidSessionSwitchProducesRejectedProcessAndKeepsCurrentSession() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("missing-user"));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot rejected = snapshot.getTerminatedProcessesSnapshot()[0];

        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.FAILED, rejected.getResultStatus());
        assertTrue(rejected.getErrorMessage().contains("user not found"));
        assertEquals("admin", snapshot.getSessionSummary().getCurrentUserId());
    }

    @Test
    void injectedApplicationStateIsDefensivelyCopied() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);

        SimulationApplicationState injectedState = new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(admin),
                2L,
                1L);
        coordinator = new SimulationCoordinator(
                new SimulatedDisk(200, 10, DiskHeadDirection.UP),
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO,
                injectedState);
        coordinator.start();

        injectedState.getSessionContext().switchTo(user1);
        injectedState.getFileSystemCatalog().addNode(
                injectedState.getFileSystemCatalog().requireDirectoryByPath("/home-user-1"),
                new DirectoryNode("NODE-EXTERNAL", "external-dir", user1.getUserId(), AccessPermissions.privateAccess()));

        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();

        assertEquals("admin", snapshot.getSessionSummary().getCurrentUserId());
        assertNull(findFileSystemNodeByPath(snapshot, "/home-user-1/external-dir"));
        assertEquals(0, snapshot.getTerminatedProcessesSnapshot().length);
    }

    @Test
    void deepCopiedInjectedStateDropsTransientBlockReservations() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);

        SimulationApplicationState injectedState = new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(user1),
                2L,
                1L);
        injectedState.reserveBlockIndexes(new int[] {0, 1, 2, 3});

        coordinator = new SimulationCoordinator(
                new SimulatedDisk(4, 0, DiskHeadDirection.UP),
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO,
                injectedState);
        coordinator.start();

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "copied.txt", 4, false, false));

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                s.getTerminatedProcessesSnapshot().length == 1
                        && findFileSystemNodeByPath(s, "/home-user-1/copied.txt") != null);

        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS,
                snapshot.getTerminatedProcessesSnapshot()[0].getResultStatus());
        assertEquals(4, countOwnedBlocks(
                snapshot,
                findFileSystemNodeByPath(snapshot, "/home-user-1/copied.txt").getNodeId()));
    }

    @Test
    void injectedFilesystemFilesHydrateFreshDiskBeforeNewCreates() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);
        homeUser1.addChild(new FileNode(
                "NODE-FILE-1",
                "seed.txt",
                user1.getUserId(),
                AccessPermissions.privateAccess(),
                1,
                0,
                "COLOR-1",
                false));

        SimulationApplicationState injectedState = new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(user1),
                3L,
                2L);
        coordinator = new SimulationCoordinator(
                new SimulatedDisk(4, 0, DiskHeadDirection.UP),
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO,
                injectedState);
        coordinator.start();

        SimulationSnapshot initialSnapshot = coordinator.getLatestSnapshot();
        SimulationSnapshot.FileSystemNodeSummary seededFile =
                findFileSystemNodeByPath(initialSnapshot, "/home-user-1/seed.txt");
        assertNotNull(seededFile);
        assertEquals(1, countOwnedBlocks(initialSnapshot, seededFile.getNodeId()));
        assertEquals(0, seededFile.getFirstBlockIndex());

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "fresh.txt", 1, false, false));

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                s.getTerminatedProcessesSnapshot().length == 1
                        && findFileSystemNodeByPath(s, "/home-user-1/fresh.txt") != null);
        SimulationSnapshot.FileSystemNodeSummary createdFile =
                findFileSystemNodeByPath(snapshot, "/home-user-1/fresh.txt");

        assertNotNull(createdFile);
        assertEquals(1, countOwnedBlocks(snapshot, seededFile.getNodeId()));
        assertEquals(1, countOwnedBlocks(snapshot, createdFile.getNodeId()));
        assertTrue(createdFile.getFirstBlockIndex() != seededFile.getFirstBlockIndex());
    }

    @Test
    void hydrationPreservesOtherFilesDeclaredFirstBlocks() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);
        homeUser1.addChild(new FileNode(
                "FILE-A",
                "a.txt",
                user1.getUserId(),
                AccessPermissions.privateAccess(),
                2,
                0,
                "COLOR-A",
                false));
        homeUser1.addChild(new FileNode(
                "FILE-B",
                "b.txt",
                user1.getUserId(),
                AccessPermissions.privateAccess(),
                2,
                1,
                "COLOR-B",
                false));

        coordinator = new SimulationCoordinator(
                new SimulatedDisk(6, 0, DiskHeadDirection.UP),
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO,
                new SimulationApplicationState(
                        new FileSystemCatalog(root),
                        userStore,
                        new SessionContext(user1),
                        2L,
                        1L));
        coordinator.start();

        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        SimulationSnapshot.FileSystemNodeSummary fileA = findFileSystemNodeByPath(snapshot, "/home-user-1/a.txt");
        SimulationSnapshot.FileSystemNodeSummary fileB = findFileSystemNodeByPath(snapshot, "/home-user-1/b.txt");

        assertNotNull(fileA);
        assertNotNull(fileB);
        assertEquals(0, fileA.getFirstBlockIndex());
        assertEquals(1, fileB.getFirstBlockIndex());
        assertEquals(2, countOwnedBlocks(snapshot, "FILE-A"));
        assertEquals(2, countOwnedBlocks(snapshot, "FILE-B"));
    }

    @Test
    void injectedDiskCannotContainOrphanedOccupiedBlocks() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);

        SimulatedDisk injectedDisk = new SimulatedDisk(8, 0, DiskHeadDirection.UP);
        injectedDisk.allocateBlock(3, "orphan-file", DiskBlock.NO_NEXT_BLOCK, false);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new SimulationCoordinator(
                injectedDisk,
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO,
                new SimulationApplicationState(
                        new FileSystemCatalog(root),
                        userStore,
                        new SessionContext(user1),
                        2L,
                        1L)));

        assertTrue(exception.getMessage().contains("occupied block without filesystem owner"));
    }

    @Test
    void failedStateSyncDoesNotMutateCallerDisk() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);
        homeUser1.addChild(new FileNode(
                "FILE-A",
                "a.txt",
                user1.getUserId(),
                AccessPermissions.privateAccess(),
                1,
                0,
                "COLOR-A",
                false));
        homeUser1.addChild(new FileNode(
                "FILE-B",
                "b.txt",
                user1.getUserId(),
                AccessPermissions.privateAccess(),
                1,
                99,
                "COLOR-B",
                false));

        SimulatedDisk callerDisk = new SimulatedDisk(8, 0, DiskHeadDirection.UP);

        assertThrows(IllegalArgumentException.class, () -> new SimulationCoordinator(
                callerDisk,
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO,
                new SimulationApplicationState(
                        new FileSystemCatalog(root),
                        userStore,
                        new SessionContext(user1),
                        2L,
                        1L)));

        assertTrue(callerDisk.getBlock(0).isFree());
        assertEquals(8, callerDisk.countFreeBlocks());
    }

    @Test
    void createFileIntentAllocatesBlocksAndPublishesDomainSnapshots() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "notes.txt", 2, true, false));

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                s.getTerminatedProcessesSnapshot().length == 1
                        && findFileSystemNodeByPath(s, "/home-user-1/notes.txt") != null);
        SimulationSnapshot.FileSystemNodeSummary fileNode =
                findFileSystemNodeByPath(snapshot, "/home-user-1/notes.txt");

        assertNotNull(fileNode);
        assertEquals("user-1", fileNode.getOwnerUserId());
        assertEquals(2, fileNode.getSizeInBlocks());
        assertEquals(2, countOwnedBlocks(snapshot, fileNode.getNodeId()));
        assertEquals(JournalStatus.COMMITTED, snapshot.getJournalEntriesSnapshot()[0].getStatus());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS,
                snapshot.getTerminatedProcessesSnapshot()[0].getResultStatus());
    }

    @Test
    void invalidCreateFileNameIsRejectedWithoutAllocatingBlocks() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "..", 2, true, false));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot rejected = snapshot.getTerminatedProcessesSnapshot()[0];

        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.FAILED, rejected.getResultStatus());
        assertTrue(rejected.getErrorMessage().contains("fileName cannot be . or .."));
        assertNull(findFileSystemNodeByPath(snapshot, "/home-user-1/.."));
        assertEquals(0, snapshot.getJournalEntriesSnapshot().length);
        assertEquals(0, countOccupiedBlocks(snapshot));
    }

    @Test
    void queuedCreateFileIntentsReserveDifferentBlocks() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "alpha.txt", 2, false, false));
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "beta.txt", 2, false, false));

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                s.getTerminatedProcessesSnapshot().length == 2
                        && findFileSystemNodeByPath(s, "/home-user-1/alpha.txt") != null
                        && findFileSystemNodeByPath(s, "/home-user-1/beta.txt") != null);

        SimulationSnapshot.FileSystemNodeSummary alpha =
                findFileSystemNodeByPath(snapshot, "/home-user-1/alpha.txt");
        SimulationSnapshot.FileSystemNodeSummary beta =
                findFileSystemNodeByPath(snapshot, "/home-user-1/beta.txt");

        assertNotNull(alpha);
        assertNotNull(beta);
        assertEquals(2, countOwnedBlocks(snapshot, alpha.getNodeId()));
        assertEquals(2, countOwnedBlocks(snapshot, beta.getNodeId()));
        assertTrue(alpha.getFirstBlockIndex() != beta.getFirstBlockIndex());
    }

    @Test
    void dependentIntentPlanningWaitsForEarlierCreateToFinish() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "queued.txt", 1, false, false));
        coordinator.submitIntent(new RenameIntent("/home-user-1/queued.txt", "renamed.txt"));

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                s.getTerminatedProcessesSnapshot().length == 2
                        && findFileSystemNodeByPath(s, "/home-user-1/renamed.txt") != null);

        assertNull(findFileSystemNodeByPath(snapshot, "/home-user-1/queued.txt"));
        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/renamed.txt"));
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS,
                snapshot.getTerminatedProcessesSnapshot()[0].getResultStatus());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS,
                snapshot.getTerminatedProcessesSnapshot()[1].getResultStatus());
    }

    @Test
    void manualIntentCanRunWhileAnotherProcessIsBlocked() {
        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("file-locked-intent", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());
        coordinator.start();

        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-BLOCK-INTENT",
                "PROC-BLOCK-INTENT",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/file-locked-intent",
                "file-locked-intent",
                10,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("file-locked-intent", "root", "/", "old.txt", "new.txt"),
                        "file-locked-intent",
                        "user-1",
                        "blocked operation"),
                (command, process, diskResult) -> OperationApplyResult.success()));

        waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "created-while-blocked.txt", 1, false, false));

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                findFileSystemNodeByPath(s, "/home-user-1/created-while-blocked.txt") != null
                        && s.getBlockedProcessesSnapshot().length == 1
                        && s.getTerminatedProcessesSnapshot().length >= 1);

        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/created-while-blocked.txt"));
    }

        @Test
        void userCanReadPrivateForeignFileButCannotDeleteIt() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-2"));
        waitForSnapshot(s -> "user-2".equals(s.getSessionSummary().getCurrentUserId()));
                coordinator.submitIntent(new CreateFileIntent("/home-user-2", "shared.txt", 1, false, false));
        waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));

        coordinator.submitIntent(new ReadIntent("/home-user-2/shared.txt"));
        SimulationSnapshot afterRead = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS,
                afterRead.getTerminatedProcessesSnapshot()[1].getResultStatus());

        coordinator.submitIntent(new DeleteIntent("/home-user-2/shared.txt"));
        SimulationSnapshot afterDelete = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 3);
        SimulationSnapshot.ProcessSnapshot failedDelete = afterDelete.getTerminatedProcessesSnapshot()[2];

        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.FAILED, failedDelete.getResultStatus());
        assertTrue(failedDelete.getErrorMessage().contains("cannot modify"));
        assertNotNull(findFileSystemNodeByPath(afterDelete, "/home-user-2/shared.txt"));
    }

    @Test
    void userCanCreateSystemFileDirectlyUnderRoot() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));

        coordinator.submitIntent(new CreateFileIntent("/", "user-owned-system.bin", 1, false, true));

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                s.getTerminatedProcessesSnapshot().length == 1
                        && findFileSystemNodeByPath(s, "/user-owned-system.bin") != null);
        SimulationSnapshot.ProcessSnapshot createResult = snapshot.getTerminatedProcessesSnapshot()[0];
        SimulationSnapshot.FileSystemNodeSummary created =
                findFileSystemNodeByPath(snapshot, "/user-owned-system.bin");

        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS, createResult.getResultStatus());
        assertNotNull(created);
        assertEquals("user-1", created.getOwnerUserId());
        assertTrue(created.isSystemFile());
    }

    @Test
    void userCannotDeleteOwnedDirectoryContainingProtectedDescendant() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        DirectoryNode docs = new DirectoryNode("NODE-2", "docs", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);
        homeUser1.addChild(docs);
        docs.addChild(new FileNode(
                "NODE-SYS",
                "kernel.bin",
                admin.getUserId(),
                AccessPermissions.privateAccess(),
                1,
                0,
                "COLOR-SYS",
                true));

        coordinator = new SimulationCoordinator(
                new SimulatedDisk(16, 0, DiskHeadDirection.UP),
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO,
                new SimulationApplicationState(
                        new FileSystemCatalog(root),
                        userStore,
                        new SessionContext(user1),
                        3L,
                        2L));
        coordinator.start();

        coordinator.submitIntent(new DeleteIntent("/home-user-1/docs"));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot failedDelete = snapshot.getTerminatedProcessesSnapshot()[0];

        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.FAILED, failedDelete.getResultStatus());
        assertTrue(failedDelete.getErrorMessage().contains("cannot modify"));
        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/docs"));
        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/docs/kernel.bin"));
        assertEquals(1, countOwnedBlocks(snapshot, "NODE-SYS"));
    }

    @Test
    void renameIntentUpdatesFilesystemPathAndCommitsJournal() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "draft.txt", 1, false, false));
        waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);

        coordinator.submitIntent(new RenameIntent("/home-user-1/draft.txt", "final.txt"));
        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);

        assertNull(findFileSystemNodeByPath(snapshot, "/home-user-1/draft.txt"));
        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/final.txt"));
        assertEquals(JournalStatus.COMMITTED, snapshot.getJournalEntriesSnapshot()[1].getStatus());
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS,
                snapshot.getTerminatedProcessesSnapshot()[1].getResultStatus());
    }

    @Test
    void deleteIntentRemovesFileAndFreesAllocatedBlocks() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "temp.txt", 3, false, false));
        SimulationSnapshot createdSnapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.FileSystemNodeSummary createdNode =
                findFileSystemNodeByPath(createdSnapshot, "/home-user-1/temp.txt");
        assertNotNull(createdNode);
        assertEquals(3, countOwnedBlocks(createdSnapshot, createdNode.getNodeId()));

        coordinator.submitIntent(new DeleteIntent("/home-user-1/temp.txt"));
        SimulationSnapshot deletedSnapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);

        assertNull(findFileSystemNodeByPath(deletedSnapshot, "/home-user-1/temp.txt"));
        assertEquals(0, countOwnedBlocks(deletedSnapshot, createdNode.getNodeId()));
        assertEquals(JournalStatus.COMMITTED, deletedSnapshot.getJournalEntriesSnapshot()[1].getStatus());
    }

    @Test
    void generatedIntentIdsDoNotCollideWithManualOperationIds() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-1",
                "PROC-1",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/manual-file",
                "manual-file",
                14,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success()));

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "id-safe.txt", 1, false, false));

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);
        SimulationSnapshot.ProcessSnapshot generated =
                findTerminatedByPath(snapshot, "/home-user-1/id-safe.txt");

        assertNotNull(generated);
        assertTrue(generated.getRequestId().startsWith("INTENT-REQ-"));
        assertTrue(generated.getProcessId().startsWith("INTENT-PROC-"));
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS, generated.getResultStatus());
    }

    @Test
    void generatedIntentIdsAvoidPendingManualOperationIds() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        lockTable.tryAcquire("file-blocked-generated-ids", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, journalManager);
        coordinator.start();

        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-BLOCK-IDS",
                "PROC-BLOCK-IDS",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/file-blocked-generated-ids",
                "file-blocked-generated-ids",
                27,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("file-blocked-generated-ids", "root", "/", "old.txt", "new.txt"),
                        "file-blocked-generated-ids",
                        "user-1",
                        "blocked id ordering op"),
                (command, process, diskResult) -> OperationApplyResult.success()));
        waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);

        coordinator.submitOperation(new PreparedOperationCommand(
                "INTENT-REQ-1",
                "INTENT-PROC-1",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/manual-intent-like",
                "manual-intent-like",
                28,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success()));
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "pending-id-safe.txt", 1, false, false));

        lockTable.releaseByProcess("file-blocked-generated-ids", "external-reader");

        SimulationSnapshot snapshot = waitForSnapshot(s ->
                s.getTerminatedProcessesSnapshot().length == 3
                        && findFileSystemNodeByPath(s, "/home-user-1/pending-id-safe.txt") != null);
        SimulationSnapshot.ProcessSnapshot generated =
                findTerminatedByPath(snapshot, "/home-user-1/pending-id-safe.txt");

        assertNotNull(generated);
        assertEquals(ve.edu.unimet.so.project2.process.ResultStatus.SUCCESS, generated.getResultStatus());
        assertEquals("INTENT-REQ-2", generated.getRequestId());
        assertEquals("INTENT-PROC-2", generated.getProcessId());
    }

    @Test
    void intentDoesNotGetOvertakenByLaterManualOperation() {
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        lockTable.tryAcquire("file-blocked-order", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, journalManager);
        coordinator.start();

        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-BLOCK",
                "PROC-BLOCK",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/file-blocked-order",
                "file-blocked-order",
                25,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("file-blocked-order", "root", "/", "old.txt", "new.txt"),
                        "file-blocked-order",
                        "user-1",
                        "blocked ordering op"),
                (command, process, diskResult) -> OperationApplyResult.success()));
        waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "ordered.txt", 1, false, false));
        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-LATE",
                "PROC-LATE",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.READ,
                FsNodeType.FILE,
                "/late-manual",
                "late-manual",
                26,
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success()));

        lockTable.releaseByProcess("file-blocked-order", "external-reader");

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 3);

        assertEquals("/file-blocked-order", snapshot.getTerminatedProcessesSnapshot()[0].getTargetPath());
        assertEquals("/home-user-1/ordered.txt", snapshot.getTerminatedProcessesSnapshot()[1].getTargetPath());
        assertEquals("/late-manual", snapshot.getTerminatedProcessesSnapshot()[2].getTargetPath());
    }

    @Test
    void saveAndLoadSystemRoundTripRestoresStableState() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "persisted.txt", 2, true, false));
        waitForSnapshot(s -> findFileSystemNodeByPath(s, "/home-user-1/persisted.txt") != null
                && s.getTerminatedProcessesSnapshot().length == 1);

        Path savePath = Files.createTempFile("project2os-system", ".json");
        coordinator.saveSystem(savePath);

        coordinator.submitIntent(new SwitchSessionIntent("user-2"));
        waitForSnapshot(s -> "user-2".equals(s.getSessionSummary().getCurrentUserId()));
        coordinator.submitIntent(new SwitchSessionIntent("user-1"));
        waitForSnapshot(s -> "user-1".equals(s.getSessionSummary().getCurrentUserId()));
        coordinator.submitIntent(new RenameIntent("/home-user-1/persisted.txt", "mutated.txt"));
        waitForSnapshot(s -> findFileSystemNodeByPath(s, "/home-user-1/mutated.txt") != null
                && s.getTerminatedProcessesSnapshot().length == 2);

        coordinator.loadSystem(savePath);
        SimulationSnapshot loadedSnapshot = coordinator.getLatestSnapshot();

        assertEquals("user-1", loadedSnapshot.getSessionSummary().getCurrentUserId());
        assertNotNull(findFileSystemNodeByPath(loadedSnapshot, "/home-user-1/persisted.txt"));
        assertNull(findFileSystemNodeByPath(loadedSnapshot, "/home-user-1/mutated.txt"));
        assertEquals(1, loadedSnapshot.getJournalEntriesSnapshot().length);
        assertEquals(JournalStatus.COMMITTED, loadedSnapshot.getJournalEntriesSnapshot()[0].getStatus());
        assertEquals(0, loadedSnapshot.getTerminatedProcessesSnapshot().length);
    }

    @Test
    void saveSystemIsRejectedWhileCoordinatorIsNotIdle() throws IOException {
        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("busy-file", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());
        coordinator.start();

        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-SAVE-BUSY",
                "PROC-SAVE-BUSY",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/busy-file",
                "busy-file",
                12,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("busy-file", "root", "/", "old.txt", "new.txt"),
                        "busy-file",
                        "user-1",
                        "busy update"),
                (command, process, diskResult) -> OperationApplyResult.success()));
        waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);

        Path savePath = Files.createTempFile("project2os-save-idle-check", ".json");
        assertThrows(IllegalStateException.class, () -> coordinator.saveSystem(savePath));
    }

    @Test
    void saveSystemIsRejectedWhenExternalLockStateExists() throws IOException {
        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("external-save-file", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());
        coordinator.start();

        Path savePath = Files.createTempFile("project2os-save-lock-state", ".json");
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> coordinator.saveSystem(savePath));

        assertTrue(exception.getMessage().contains("lock state"));
        assertEquals(1, lockTable.getActiveLockCount("external-save-file"));
    }

    @Test
    void loadSystemRecoversPendingRenameEntries() throws IOException {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);
        homeUser1.addChild(new FileNode(
                "FILE-1",
                "new.txt",
                user1.getUserId(),
                AccessPermissions.privateAccess(),
                1,
                0,
                "COLOR-1",
                false));

        SimulationApplicationState persistedState = new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(user1),
                2L,
                2L);
        SimulatedDisk persistedDisk = new SimulatedDisk(8, 0, DiskHeadDirection.UP);
        persistedDisk.allocateBlock(0, "FILE-1", DiskBlock.NO_NEXT_BLOCK, false);

        JournalManager persistedJournal = new JournalManager();
        persistedJournal.restoreEntry(
                "TX-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                "/home-user-1/new.txt",
                JournalStatus.PENDING,
                new UpdateRenameUndoData("FILE-1", "NODE-1", "/home-user-1", "old.txt", "new.txt"),
                "FILE-1",
                "user-1",
                "pending rename recovery");

        Path savePath = Files.createTempFile("project2os-recovery", ".json");
        new SystemPersistenceService().save(
                savePath,
                DiskSchedulingPolicy.FIFO,
                persistedDisk,
                persistedState,
                persistedJournal);

        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();
        coordinator.loadSystem(savePath);

        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/old.txt"));
        assertNull(findFileSystemNodeByPath(snapshot, "/home-user-1/new.txt"));
        assertEquals(JournalStatus.UNDONE, snapshot.getJournalEntriesSnapshot()[0].getStatus());
    }

    @Test
    void loadSystemRecoversPendingDeleteDirectoryEntriesWithNestedChildren() throws IOException {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);

        SimulationApplicationState persistedState = new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(user1),
                4L,
                2L);
        SimulatedDisk persistedDisk = new SimulatedDisk(8, 0, DiskHeadDirection.UP);
        JournalManager persistedJournal = new JournalManager();
        persistedJournal.restoreEntry(
                "TX-DEL-DIR-1",
                ve.edu.unimet.so.project2.process.IoOperationType.DELETE,
                "/home-user-1/docs",
                JournalStatus.PENDING,
                new DeleteDirectoryUndoData(
                        "NODE-2",
                        "NODE-1",
                        "/home-user-1",
                        new ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot[] {
                                new ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot(
                                        "NODE-2",
                                        FsNodeType.DIRECTORY,
                                        "docs",
                                        user1.getUserId(),
                                        "NODE-1",
                                        "/home-user-1/docs",
                                        false,
                                        0,
                                        ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot.NO_BLOCK,
                                        null,
                                        false),
                                new ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot(
                                        "FILE-2",
                                        FsNodeType.FILE,
                                        "nested.txt",
                                        user1.getUserId(),
                                        "NODE-2",
                                        "/home-user-1/docs/nested.txt",
                                        false,
                                        1,
                                        3,
                                        "COLOR-2",
                                        false)
                        },
                        new ve.edu.unimet.so.project2.journal.undo.JournalBlockSnapshot[] {
                                new ve.edu.unimet.so.project2.journal.undo.JournalBlockSnapshot(
                                        3,
                                        "FILE-2",
                                        DiskBlock.NO_NEXT_BLOCK,
                                        false)
                        }),
                "NODE-2",
                "user-1",
                "pending delete directory recovery");

        Path savePath = Files.createTempFile("project2os-recovery-dir", ".json");
        new SystemPersistenceService().save(
                savePath,
                DiskSchedulingPolicy.FIFO,
                persistedDisk,
                persistedState,
                persistedJournal);

        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();
        coordinator.loadSystem(savePath);

        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/docs"));
        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/docs/nested.txt"));
        assertEquals(JournalStatus.UNDONE, snapshot.getJournalEntriesSnapshot()[0].getStatus());
    }

    @Test
    void loadSystemRealignsNextIdsAfterRecovery() throws IOException {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);

        SimulationApplicationState persistedState = new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(user1),
                2L,
                1L);
        SimulatedDisk persistedDisk = new SimulatedDisk(12, 0, DiskHeadDirection.UP);
        JournalManager persistedJournal = new JournalManager();
        persistedJournal.restoreEntry(
                "TX-DEL-DIR-2",
                ve.edu.unimet.so.project2.process.IoOperationType.DELETE,
                "/home-user-1/archive",
                JournalStatus.PENDING,
                new DeleteDirectoryUndoData(
                        "NODE-50",
                        "NODE-1",
                        "/home-user-1",
                        new ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot[] {
                                new ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot(
                                        "NODE-50",
                                        FsNodeType.DIRECTORY,
                                        "archive",
                                        user1.getUserId(),
                                        "NODE-1",
                                        "/home-user-1/archive",
                                        false,
                                        0,
                                        ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot.NO_BLOCK,
                                        null,
                                        false),
                                new ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot(
                                        "NODE-60",
                                        FsNodeType.FILE,
                                        "restored.txt",
                                        user1.getUserId(),
                                        "NODE-50",
                                        "/home-user-1/archive/restored.txt",
                                        false,
                                        1,
                                        4,
                                        "COLOR-60",
                                        false)
                        },
                        new ve.edu.unimet.so.project2.journal.undo.JournalBlockSnapshot[] {
                                new ve.edu.unimet.so.project2.journal.undo.JournalBlockSnapshot(
                                        4,
                                        "NODE-60",
                                        DiskBlock.NO_NEXT_BLOCK,
                                        false)
                        }),
                "NODE-50",
                "user-1",
                "pending delete directory recovery high ids");

        Path savePath = Files.createTempFile("project2os-recovery-id-realign", ".json");
        new SystemPersistenceService().save(
                savePath,
                DiskSchedulingPolicy.FIFO,
                persistedDisk,
                persistedState,
                persistedJournal);

        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();
        coordinator.loadSystem(savePath);

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "fresh-after-recovery.txt", 1, false, false));
        SimulationSnapshot snapshot = waitForSnapshot(s ->
                findFileSystemNodeByPath(s, "/home-user-1/fresh-after-recovery.txt") != null
                        && s.getTerminatedProcessesSnapshot().length == 1);

        SimulationSnapshot.FileSystemNodeSummary recoveredFile =
                findFileSystemNodeByPath(snapshot, "/home-user-1/archive/restored.txt");
        SimulationSnapshot.FileSystemNodeSummary createdFile =
                findFileSystemNodeByPath(snapshot, "/home-user-1/fresh-after-recovery.txt");

        assertNotNull(recoveredFile);
        assertNotNull(createdFile);
        assertEquals("NODE-60", recoveredFile.getNodeId());
        assertEquals("COLOR-60", recoveredFile.getColorId());
        assertEquals("NODE-61", createdFile.getNodeId());
        assertEquals("COLOR-61", createdFile.getColorId());
    }

    @Test
    void loadSystemRejectsPersistedDiskChainMismatch() throws IOException {
        PersistedSystemState persistedSystemState = new PersistedSystemState(
                DiskSchedulingPolicy.FIFO,
                0,
                DiskHeadDirection.UP,
                new PersistedSystemState.FileSystemNodeData[] {
                        new PersistedSystemState.FileSystemNodeData(
                                "root",
                                null,
                                FsNodeType.DIRECTORY,
                                "",
                                "/",
                                "admin",
                                true,
                                0,
                                0,
                                null,
                                false),
                        new PersistedSystemState.FileSystemNodeData(
                                "NODE-1",
                                "root",
                                FsNodeType.DIRECTORY,
                                "home-user-1",
                                "/home-user-1",
                                "user-1",
                                false,
                                0,
                                0,
                                null,
                                false),
                        new PersistedSystemState.FileSystemNodeData(
                                "FILE-1",
                                "NODE-1",
                                FsNodeType.FILE,
                                "broken.txt",
                                "/home-user-1/broken.txt",
                                "user-1",
                                false,
                                2,
                                0,
                                "COLOR-1",
                                false)
                },
                new PersistedSystemState.DiskBlockData[] {
                        new PersistedSystemState.DiskBlockData(0, false, "FILE-1", DiskBlock.NO_NEXT_BLOCK, false),
                        new PersistedSystemState.DiskBlockData(1, true, null, DiskBlock.NO_NEXT_BLOCK, false),
                        new PersistedSystemState.DiskBlockData(2, true, null, DiskBlock.NO_NEXT_BLOCK, false),
                        new PersistedSystemState.DiskBlockData(3, true, null, DiskBlock.NO_NEXT_BLOCK, false)
                },
                new PersistedSystemState.UserData[] {
                        new PersistedSystemState.UserData("admin", "Administrator", Role.ADMIN),
                        new PersistedSystemState.UserData("user-1", "User One", Role.USER)
                },
                "user-1",
                new PersistedSystemState.JournalEntryData[0]);

        Path savePath = Files.createTempFile("project2os-invalid-load", ".json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(savePath.toFile(), persistedSystemState);

        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> coordinator.loadSystem(savePath));
        assertTrue(exception.getMessage().contains("disk chain"));
    }

    @Test
    void loadSystemRejectsNullPersistedPolicyWithoutResettingLiveState() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "baseline-before-bad-load.txt", 1, false, false));
        waitForSnapshot(s -> findFileSystemNodeByPath(s, "/home-user-1/baseline-before-bad-load.txt") != null);

        PersistedSystemState persistedSystemState = new PersistedSystemState(
                null,
                0,
                DiskHeadDirection.UP,
                new PersistedSystemState.FileSystemNodeData[] {
                        new PersistedSystemState.FileSystemNodeData(
                                "root",
                                null,
                                FsNodeType.DIRECTORY,
                                "",
                                "/",
                                "admin",
                                true,
                                0,
                                0,
                                null,
                                false),
                        new PersistedSystemState.FileSystemNodeData(
                                "NODE-1",
                                "root",
                                FsNodeType.DIRECTORY,
                                "home-user-1",
                                "/home-user-1",
                                "user-1",
                                false,
                                0,
                                0,
                                null,
                                false)
                },
                new PersistedSystemState.DiskBlockData[] {
                        new PersistedSystemState.DiskBlockData(0, true, null, DiskBlock.NO_NEXT_BLOCK, false),
                        new PersistedSystemState.DiskBlockData(1, true, null, DiskBlock.NO_NEXT_BLOCK, false)
                },
                new PersistedSystemState.UserData[] {
                        new PersistedSystemState.UserData("admin", "Administrator", Role.ADMIN),
                        new PersistedSystemState.UserData("user-1", "User One", Role.USER)
                },
                "admin",
                new PersistedSystemState.JournalEntryData[0]);

        Path savePath = Files.createTempFile("project2os-invalid-policy-load", ".json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(savePath.toFile(), persistedSystemState);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> coordinator.loadSystem(savePath));
        assertTrue(exception.getMessage().contains("policy"));

        SimulationSnapshot afterFailureSnapshot = coordinator.getLatestSnapshot();
        assertNotNull(findFileSystemNodeByPath(afterFailureSnapshot, "/home-user-1/baseline-before-bad-load.txt"));

        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "after-bad-load.txt", 1, false, false));
        SimulationSnapshot recoveredSnapshot =
                waitForSnapshot(s -> findFileSystemNodeByPath(s, "/home-user-1/after-bad-load.txt") != null);
        assertNotNull(findFileSystemNodeByPath(recoveredSnapshot, "/home-user-1/baseline-before-bad-load.txt"));
    }

    @Test
    void loadSystemRecoversPendingJournalEntriesInReverseOrder() throws IOException {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);
        homeUser1.addChild(new FileNode(
                "NODE-10",
                "renamed-after-create.txt",
                user1.getUserId(),
                AccessPermissions.privateAccess(),
                1,
                5,
                "COLOR-10",
                false));

        SimulationApplicationState persistedState = new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(user1),
                11L,
                11L);
        SimulatedDisk persistedDisk = new SimulatedDisk(12, 0, DiskHeadDirection.UP);
        persistedDisk.allocateBlock(5, "NODE-10", DiskBlock.NO_NEXT_BLOCK, false);

        JournalManager persistedJournal = new JournalManager();
        persistedJournal.restoreEntry(
                "TX-CREATE-1",
                ve.edu.unimet.so.project2.process.IoOperationType.CREATE,
                "/home-user-1/renamed-after-create.txt",
                JournalStatus.PENDING,
                new ve.edu.unimet.so.project2.journal.undo.CreateFileUndoData(
                        "NODE-10",
                        "NODE-1",
                        "/home-user-1",
                        new int[] {5}),
                "NODE-10",
                user1.getUserId(),
                "pending create");
        persistedJournal.restoreEntry(
                "TX-RENAME-2",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                "/home-user-1/renamed-after-create.txt",
                JournalStatus.PENDING,
                new UpdateRenameUndoData(
                        "NODE-10",
                        "NODE-1",
                        "/home-user-1",
                        "draft-before-rename.txt",
                        "renamed-after-create.txt"),
                "NODE-10",
                user1.getUserId(),
                "pending rename after create");

        Path savePath = Files.createTempFile("project2os-reverse-recovery", ".json");
        new SystemPersistenceService().save(
                savePath,
                DiskSchedulingPolicy.FIFO,
                persistedDisk,
                persistedState,
                persistedJournal);

        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();
        coordinator.loadSystem(savePath);

        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        assertNull(findFileSystemNodeByPath(snapshot, "/home-user-1/renamed-after-create.txt"));
        assertEquals(2, snapshot.getJournalEntriesSnapshot().length);
        assertEquals(JournalStatus.UNDONE, snapshot.getJournalEntriesSnapshot()[0].getStatus());
        assertEquals(JournalStatus.UNDONE, snapshot.getJournalEntriesSnapshot()[1].getStatus());
    }

    @Test
    void failedDeleteRecoveryRollsBackRestoredBlocks() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);
        homeUser1.addChild(new FileNode(
                "EXISTING-NODE",
                "conflict.txt",
                user1.getUserId(),
                AccessPermissions.privateAccess(),
                1,
                1,
                "COLOR-EXISTING",
                false));

        SimulationApplicationState recoveryState = new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(user1),
                2L,
                2L);
        SimulatedDisk recoveryDisk = new SimulatedDisk(8, 0, DiskHeadDirection.UP);
        recoveryDisk.allocateBlock(1, "EXISTING-NODE", DiskBlock.NO_NEXT_BLOCK, false);

        JournalManager journalManager = new JournalManager();
        journalManager.restoreEntry(
                "TX-DELETE-ROLLBACK-1",
                ve.edu.unimet.so.project2.process.IoOperationType.DELETE,
                "/home-user-1/conflict.txt",
                JournalStatus.PENDING,
                new DeleteFileUndoData(
                        new JournalNodeSnapshot(
                                "RECOVERED-NODE",
                                FsNodeType.FILE,
                                "conflict.txt",
                                user1.getUserId(),
                                "NODE-1",
                                "/home-user-1/conflict.txt",
                                false,
                                1,
                                3,
                                "COLOR-RECOVERED",
                                false),
                        "NODE-1",
                        "/home-user-1",
                        new JournalBlockSnapshot[] {
                                new JournalBlockSnapshot(3, "RECOVERED-NODE", DiskBlock.NO_NEXT_BLOCK, false)
                        }),
                "RECOVERED-NODE",
                user1.getUserId(),
                "rollback delete recovery");

        JournalRecoveryService recoveryService = new JournalRecoveryService();
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> recoveryService.recoverPendingEntries(recoveryState, recoveryDisk, journalManager));

        assertTrue(exception.getMessage().contains("conflict"));
        assertTrue(recoveryDisk.getBlock(3).isFree());
        assertEquals(JournalStatus.PENDING, journalManager.getEntryAt(0).getStatus());
        assertNull(recoveryState.getFileSystemCatalog().findById("RECOVERED-NODE"));
    }

    @Test
    void loadSystemIsRejectedWhileCoordinatorIsNotIdle() throws IOException {
        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("busy-file", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());
        coordinator.start();

        coordinator.submitOperation(new PreparedOperationCommand(
                "REQ-BUSY",
                "PROC-BUSY",
                "user-1",
                ve.edu.unimet.so.project2.process.IoOperationType.UPDATE,
                FsNodeType.FILE,
                "/busy-file",
                "busy-file",
                12,
                0,
                LockType.EXCLUSIVE,
                new PreparedJournalData(
                        new UpdateRenameUndoData("busy-file", "root", "/", "old.txt", "new.txt"),
                        "busy-file",
                        "user-1",
                        "busy update"),
                (command, process, diskResult) -> OperationApplyResult.success()));
        waitForSnapshot(s -> s.getBlockedProcessesSnapshot().length == 1);

        Path savePath = Files.createTempFile("project2os-idle-check", ".json");
        assertThrows(IllegalStateException.class, () -> coordinator.loadSystem(savePath));
    }

    @Test
    void loadSystemIsRejectedWhenExternalLockStateExists() throws IOException {
        Path savePath = Files.createTempFile("project2os-load-lock-state", ".json");
        new SystemPersistenceService().save(
                savePath,
                DiskSchedulingPolicy.FIFO,
                new SimulatedDisk(200, 10, DiskHeadDirection.UP),
                SimulationApplicationState.createDefault(),
                new JournalManager());

        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("external-load-file", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());
        coordinator.start();

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> coordinator.loadSystem(savePath));

        assertTrue(exception.getMessage().contains("lock state"));
        assertEquals(1, lockTable.getActiveLockCount("external-load-file"));
    }

    @Test
    void loadScenarioBuildsFilesAndScenarioRequests() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        Path scenarioPath = Files.createTempFile("project2os-scenario", ".json");
        Files.writeString(scenarioPath, """
                {
                  "test_id": "SCN-1",
                  "initial_head": 4,
                  "system_files": {
                    "1": { "name": "sys-a.bin", "blocks": 2 },
                    "6": { "name": "sys-b.bin", "blocks": 1 }
                  },
                  "requests": [
                    { "pos": 1, "op": "READ" },
                    { "pos": 6, "op": "DELETE" }
                  ]
                }
                """);

        coordinator.loadScenario(scenarioPath);
        SimulationSnapshot loadedSnapshot = coordinator.getLatestSnapshot();
        assertNotNull(findFileSystemNodeByPath(loadedSnapshot, "/sys-a.bin"));
        assertNotNull(findFileSystemNodeByPath(loadedSnapshot, "/sys-b.bin"));

        SimulationSnapshot completedSnapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);
        assertNull(findFileSystemNodeByPath(completedSnapshot, "/sys-b.bin"));
        assertEquals(2, completedSnapshot.getTerminatedProcessesSnapshot().length);
        assertEquals(4, completedSnapshot.getDispatchHistorySnapshot()[0].getPreviousHeadBlock());
    }

    @Test
    void loadScenarioResolvesRequestsFromNonLeadingFileBlocks() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        Path scenarioPath = Files.createTempFile("project2os-scenario-non-leading", ".json");
        Files.writeString(scenarioPath, """
                {
                  "test_id": "SCN-NON-LEADING-1",
                  "initial_head": 2,
                  "system_files": {
                    "1": { "name": "wide.bin", "blocks": 2 }
                  },
                  "requests": [
                    { "pos": 2, "op": "READ" }
                  ]
                }
                """);

        coordinator.loadScenario(scenarioPath);

        SimulationSnapshot completedSnapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot readProcess = completedSnapshot.getTerminatedProcessesSnapshot()[0];
        assertEquals(ResultStatus.SUCCESS, readProcess.getResultStatus());
        assertEquals("/wide.bin", readProcess.getTargetPath());
        assertEquals(2, readProcess.getTargetBlock());
    }

    @Test
    void loadScenarioResolvesLaterRequestsAgainstCurrentFilesystemState() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        Path scenarioPath = Files.createTempFile("project2os-scenario-stale-check", ".json");
        Files.writeString(scenarioPath, """
                {
                  "test_id": "SCN-STALE-1",
                  "initial_head": 2,
                  "system_files": {
                    "5": { "name": "volatile.bin", "blocks": 1 }
                  },
                  "requests": [
                    { "pos": 5, "op": "DELETE" },
                    { "pos": 5, "op": "READ" }
                  ]
                }
                """);

        coordinator.loadScenario(scenarioPath);

        SimulationSnapshot completedSnapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);
        SimulationSnapshot.ProcessSnapshot deleteProcess =
                findTerminatedByRequestId(completedSnapshot, "SCN-REQ-1");
        SimulationSnapshot.ProcessSnapshot readProcess =
                findTerminatedByRequestId(completedSnapshot, "SCN-REQ-2");

        assertEquals(ResultStatus.SUCCESS, deleteProcess.getResultStatus());
        assertEquals(ResultStatus.FAILED, readProcess.getResultStatus());
        assertTrue(readProcess.getErrorMessage().contains("unknown block position"));
        assertNull(findFileSystemNodeByPath(completedSnapshot, "/volatile.bin"));
    }

    @Test
    void loadScenarioRejectsRequestsThatDoNotMapToReconstructedFileBlocks() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "baseline.txt", 1, false, false));
        SimulationSnapshot baselineSnapshot =
                waitForSnapshot(s -> findFileSystemNodeByPath(s, "/home-user-1/baseline.txt") != null);

        Path scenarioPath = Files.createTempFile("project2os-scenario-invalid-pos", ".json");
        Files.writeString(scenarioPath, """
                {
                  "test_id": "SCN-BAD-POS-1",
                  "initial_head": 2,
                  "system_files": {
                    "5": { "name": "valid.bin", "blocks": 1 }
                  },
                  "requests": [
                    { "pos": 6, "op": "READ" }
                  ]
                }
                """);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> coordinator.loadScenario(scenarioPath));
        assertTrue(exception.getMessage().contains("does not belong to any reconstructed file block"));

        SimulationSnapshot afterFailureSnapshot = coordinator.getLatestSnapshot();
        assertNotNull(findFileSystemNodeByPath(afterFailureSnapshot, "/home-user-1/baseline.txt"));
        assertNull(findFileSystemNodeByPath(afterFailureSnapshot, "/valid.bin"));
        assertEquals(
                baselineSnapshot.getTerminatedProcessesSnapshot().length,
                afterFailureSnapshot.getTerminatedProcessesSnapshot().length);
    }

    @Test
    void loadScenarioAllowsSchedulerToChooseClosestReadyRequest() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();
        coordinator.changePolicy(DiskSchedulingPolicy.SSTF);

        Path scenarioPath = Files.createTempFile("project2os-scenario-scheduler", ".json");
        Files.writeString(scenarioPath, """
                {
                  "test_id": "SCN-SCHED-1",
                  "initial_head": 10,
                  "system_files": {
                    "100": { "name": "far.bin", "blocks": 1 },
                    "20": { "name": "near.bin", "blocks": 1 }
                  },
                  "requests": [
                    { "pos": 100, "op": "READ" },
                    { "pos": 20, "op": "READ" }
                  ]
                }
                """);

        coordinator.loadScenario(scenarioPath);

        SimulationSnapshot completedSnapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 2);
        assertEquals("/near.bin", completedSnapshot.getTerminatedProcessesSnapshot()[0].getTargetPath());
        assertEquals("/far.bin", completedSnapshot.getTerminatedProcessesSnapshot()[1].getTargetPath());
    }

    @Test
    void loadScenarioIsRejectedWhenExternalLockStateExists() throws IOException {
        LockTable lockTable = new LockTable();
        lockTable.tryAcquire("external-scenario-file", "external-reader", LockType.SHARED);
        coordinator = createCoordinator(lockTable, new JournalManager());
        coordinator.start();

        Path scenarioPath = Files.createTempFile("project2os-scenario-lock-state", ".json");
        Files.writeString(scenarioPath, """
                {
                  "test_id": "SCN-LOCK-STATE-1",
                  "initial_head": 10,
                  "system_files": {},
                  "requests": []
                }
                """);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> coordinator.loadScenario(scenarioPath));

        assertTrue(exception.getMessage().contains("lock state"));
        assertEquals(1, lockTable.getActiveLockCount("external-scenario-file"));
    }

    @Test
    void loadScenarioCanReplaceQuarantinedCrashState() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.armSimulatedFailure();
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "quarantined-crash.txt", 1, false, false));
        waitForSnapshot(s -> {
            SimulationSnapshot.ProcessSnapshot terminated =
                    findTerminatedByPath(s, "/home-user-1/quarantined-crash.txt");
            return terminated != null && terminated.getResultStatus() == ResultStatus.CANCELLED;
        });

        Path scenarioPath = Files.createTempFile("project2os-scenario-after-crash", ".json");
        Files.writeString(scenarioPath, """
                {
                  "test_id": "SCN-AFTER-CRASH-1",
                  "initial_head": 7,
                  "system_files": {
                    "3": { "name": "scenario-reset.bin", "blocks": 1 }
                  },
                  "requests": []
                }
                """);

        coordinator.loadScenario(scenarioPath);

        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        assertNull(findFileSystemNodeByPath(snapshot, "/home-user-1/quarantined-crash.txt"));
        assertNotNull(findFileSystemNodeByPath(snapshot, "/scenario-reset.bin"));
        assertEquals(7, snapshot.getHeadBlock());
    }

    @Test
    void loadScenarioKeepsOriginalScenarioActorAfterSessionSwitch() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        Path scenarioPath = Files.createTempFile("project2os-scenario-session-stability", ".json");
        Files.writeString(scenarioPath, """
                {
                  "test_id": "SCN-SESSION-STABLE-1",
                  "initial_head": 11,
                  "system_files": {
                    "11": { "name": "private-system.bin", "blocks": 1 }
                  },
                  "requests": [
                    { "pos": 11, "op": "READ" }
                  ]
                }
                """);

        coordinator.loadScenario(scenarioPath);
        coordinator.switchSession("user-1");

        SimulationSnapshot snapshot = waitForSnapshot(s -> s.getTerminatedProcessesSnapshot().length == 1);
        SimulationSnapshot.ProcessSnapshot terminated = snapshot.getTerminatedProcessesSnapshot()[0];

        assertEquals(ResultStatus.SUCCESS, terminated.getResultStatus());
        assertEquals("admin", terminated.getOwnerUserId());
    }

    @Test
    void crashQuarantineReleasesReservedBlocksFromCancelledQueuedCreates() {
        coordinator = new SimulationCoordinator(
                new SimulatedDisk(2, 0, DiskHeadDirection.UP),
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO,
                SimulationApplicationState.createDefault());
        coordinator.start();

        ApplicationIntentPlanner planner = new ApplicationIntentPlanner(
                getPrivateField(coordinator, "applicationState", SimulationApplicationState.class),
                getPrivateField(coordinator, "disk", SimulatedDisk.class),
                new PermissionService());
        PreparedOperationCommand first = planner.plan(
                new CreateFileIntent("/home-user-1", "first-reserved.txt", 1, false, false),
                "REQ-RES-1",
                "PROC-RES-1");
        PreparedOperationCommand second = planner.plan(
                new CreateFileIntent("/home-user-1", "second-reserved.txt", 1, false, false),
                "REQ-RES-2",
                "PROC-RES-2");

        coordinator.armSimulatedFailure();
        coordinator.submitOperation(first);
        coordinator.submitOperation(second);

        waitForSnapshot(s -> {
            SimulationSnapshot.ProcessSnapshot firstTerminated = findTerminatedByPath(s, "/home-user-1/first-reserved.txt");
            SimulationSnapshot.ProcessSnapshot secondTerminated = findTerminatedByPath(s, "/home-user-1/second-reserved.txt");
            return firstTerminated != null
                    && secondTerminated != null
                    && firstTerminated.getResultStatus() == ResultStatus.CANCELLED
                    && secondTerminated.getResultStatus() == ResultStatus.CANCELLED;
        });

        coordinator.recoverPendingJournalEntries();
        coordinator.submitIntent(new CreateFileIntent("/home-user-1", "after-recovery-two-blocks.txt", 2, false, false));

        SimulationSnapshot snapshot = waitForSnapshot(
                s -> findFileSystemNodeByPath(s, "/home-user-1/after-recovery-two-blocks.txt") != null);
        assertNotNull(findFileSystemNodeByPath(snapshot, "/home-user-1/after-recovery-two-blocks.txt"));
    }

    @Test
    void saveStateIgnoresLaterQueuedCommandsWhenSystemIsOtherwiseIdle() throws IOException {
        coordinator = createCoordinator(new LockTable(), new JournalManager());

        CoordinatorChannels channels = getPrivateField(coordinator, "channels", CoordinatorChannels.class);
        channels.enqueueCommand(new CoordinatorCommand() {
            @Override
            public void execute() {
            }
        });

        Path savePath = Files.createTempFile("project2os-admin-queue", ".json");
        assertDoesNotThrow(() -> invokePrivateVoidMethod(coordinator, "saveSystemState", Path.class, savePath));
    }

        @Test
        void stepModeRequiresManualPermitsToAdvanceSimulation() {
                coordinator = createCoordinator(new LockTable(), new JournalManager());
                coordinator.start();

                coordinator.setStepModeEnabled(true);
                coordinator.submitIntent(new CreateFileIntent("/home-user-1", "manual-step.txt", 1, false, false));

                LockSupport.parkNanos(250_000_000L);
                SimulationSnapshot pausedSnapshot = coordinator.getLatestSnapshot();
                assertNotNull(pausedSnapshot);
                assertNull(findFileSystemNodeByPath(pausedSnapshot, "/home-user-1/manual-step.txt"));
                assertEquals(0, pausedSnapshot.getTerminatedProcessesSnapshot().length);

                coordinator.stepSimulationOnce();
                LockSupport.parkNanos(150_000_000L);
                SimulationSnapshot afterFirstStep = coordinator.getLatestSnapshot();
                assertNotNull(afterFirstStep);
                assertEquals(0, afterFirstStep.getTerminatedProcessesSnapshot().length);

                coordinator.stepSimulationOnce();
                SimulationSnapshot completedSnapshot = waitForSnapshot(
                                s -> findFileSystemNodeByPath(s, "/home-user-1/manual-step.txt") != null);

                assertNotNull(findFileSystemNodeByPath(completedSnapshot, "/home-user-1/manual-step.txt"));
        }

        @Test
        void stepCommandForcesManualModeEvenIfPlaybackWasAutomatic() {
                coordinator = createCoordinator(new LockTable(), new JournalManager());
                coordinator.start();

                coordinator.setStepModeEnabled(false);
                coordinator.stepSimulationOnce();

                long deadline = System.currentTimeMillis() + 2000L;
                while (System.currentTimeMillis() < deadline && !coordinator.isStepModeEnabled()) {
                        LockSupport.parkNanos(10_000_000L);
                }

                assertTrue(coordinator.isStepModeEnabled(), "step command should switch coordinator to manual mode");
        }

        @Test
        void executionDelayCanBeUpdatedAndRejectsNegativeValues() {
                coordinator = createCoordinator(new LockTable(), new JournalManager());
                coordinator.start();

                assertThrows(IllegalArgumentException.class, () -> coordinator.changeExecutionDelay(-1L));

                coordinator.changeExecutionDelay(25L);
                long deadline = System.currentTimeMillis() + 2000L;
                while (System.currentTimeMillis() < deadline && coordinator.getExecutionDelayMillis() != 25L) {
                        LockSupport.parkNanos(10_000_000L);
                }

                assertEquals(25L, coordinator.getExecutionDelayMillis());
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

    private SimulationSnapshot.FileSystemNodeSummary findFileSystemNodeByPath(
            SimulationSnapshot snapshot,
            String path) {
        for (SimulationSnapshot.FileSystemNodeSummary node : snapshot.getFileSystemNodesSnapshot()) {
            if (node.getPath().equals(path)) {
                return node;
            }
        }
        return null;
    }

    private SimulationSnapshot.ProcessSnapshot findTerminatedByPath(
            SimulationSnapshot snapshot,
            String targetPath) {
        for (SimulationSnapshot.ProcessSnapshot process : snapshot.getTerminatedProcessesSnapshot()) {
            if (process.getTargetPath().equals(targetPath)) {
                return process;
            }
        }
        return null;
    }

    private int countOwnedBlocks(SimulationSnapshot snapshot, String nodeId) {
        int count = 0;
        for (SimulationSnapshot.DiskBlockSummary block : snapshot.getDiskBlocksSnapshot()) {
            if (nodeId.equals(block.getOwnerFileId())) {
                count++;
            }
        }
        return count;
    }

    private int countOccupiedBlocks(SimulationSnapshot snapshot) {
        int count = 0;
        for (SimulationSnapshot.DiskBlockSummary block : snapshot.getDiskBlocksSnapshot()) {
            if (!block.isFree()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasEventWithCategory(SimulationSnapshot snapshot, String category) {
        for (SimulationSnapshot.EventLogEntrySummary entry : snapshot.getEventLogEntriesSnapshot()) {
            if (entry.getCategory().equals(category)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEventMessageContaining(SimulationSnapshot snapshot, String text) {
        for (SimulationSnapshot.EventLogEntrySummary entry : snapshot.getEventLogEntriesSnapshot()) {
            if (entry.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private PreparedOperationCommand wrapCommandWithDelay(PreparedOperationCommand command, long nanosDelay) {
        return new PreparedOperationCommand(
                command.getRequestId(),
                command.getProcessId(),
                command.getOwnerUserId(),
                command.getOperationType(),
                command.getTargetNodeType(),
                command.getTargetPath(),
                command.getTargetNodeId(),
                command.getTargetBlock(),
                command.getRequestedSizeInBlocks(),
                command.getRequiredLockType(),
                command.getPreparedJournalData(),
                (preparedCommand, process, diskResult) -> {
                    LockSupport.parkNanos(nanosDelay);
                    return command.getOperationHandler().apply(command, process, diskResult);
                });
    }

    private <T> T getPrivateField(Object target, String fieldName, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("failed to access field: " + fieldName, exception);
        }
    }

    private void invokePrivateVoidMethod(Object target, String methodName, Class<?> parameterType, Object argument) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterType);
            method.setAccessible(true);
            method.invoke(target, argument);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("unexpected checked exception from " + methodName, cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("failed to invoke method: " + methodName, exception);
        }
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
