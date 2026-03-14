package ve.edu.unimet.so.project2.coordinator;

import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ve.edu.unimet.so.project2.application.CreateFileIntent;
import ve.edu.unimet.so.project2.application.DeleteIntent;
import ve.edu.unimet.so.project2.application.ReadIntent;
import ve.edu.unimet.so.project2.application.RenameIntent;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.application.SwitchSessionIntent;
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
import ve.edu.unimet.so.project2.journal.undo.UpdateRenameUndoData;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;
import ve.edu.unimet.so.project2.session.Role;
import ve.edu.unimet.so.project2.session.SessionContext;
import ve.edu.unimet.so.project2.session.User;
import ve.edu.unimet.so.project2.session.UserStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void userCanReadPublicForeignFileButCannotDeleteIt() {
        coordinator = createCoordinator(new LockTable(), new JournalManager());
        coordinator.start();

        coordinator.submitIntent(new SwitchSessionIntent("user-2"));
        waitForSnapshot(s -> "user-2".equals(s.getSessionSummary().getCurrentUserId()));
        coordinator.submitIntent(new CreateFileIntent("/home-user-2", "shared.txt", 1, true, false));
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
