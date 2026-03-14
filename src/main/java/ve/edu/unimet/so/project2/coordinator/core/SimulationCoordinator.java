package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.application.ApplicationIntentPlanner;
import ve.edu.unimet.so.project2.application.ApplicationOperationIntent;
import ve.edu.unimet.so.project2.application.CreateDirectoryIntent;
import ve.edu.unimet.so.project2.application.CreateFileIntent;
import ve.edu.unimet.so.project2.application.DeleteIntent;
import ve.edu.unimet.so.project2.application.PermissionService;
import ve.edu.unimet.so.project2.application.ReadIntent;
import ve.edu.unimet.so.project2.application.RenameIntent;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.application.SwitchSessionIntent;
import ve.edu.unimet.so.project2.coordinator.channel.CoordinatorChannels;
import ve.edu.unimet.so.project2.coordinator.channel.DiskServiceResult;
import ve.edu.unimet.so.project2.coordinator.channel.DiskTask;
import ve.edu.unimet.so.project2.coordinator.command.CoordinatorCommand;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshotFactory;
import ve.edu.unimet.so.project2.coordinator.state.CoordinatorProcessStore;
import ve.edu.unimet.so.project2.coordinator.state.ProcessExecutionContext;
import ve.edu.unimet.so.project2.datastructures.LinkedQueue;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.DiskHead;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.journal.JournalEntry;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.journal.undo.CreateFileUndoData;
import ve.edu.unimet.so.project2.locking.LockAcquireResult;
import ve.edu.unimet.so.project2.locking.LockReleaseResult;
import ve.edu.unimet.so.project2.locking.LockWaitEntry;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.process.IoRequest;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;
import ve.edu.unimet.so.project2.process.ResultStatus;
import ve.edu.unimet.so.project2.process.WaitReason;
import ve.edu.unimet.so.project2.scheduler.DiskScheduleDecision;
import ve.edu.unimet.so.project2.scheduler.DiskScheduler;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public final class SimulationCoordinator {

    private final SimulatedDisk disk;
    private final LockTable lockTable;
    private final JournalManager journalManager;
    private final DiskScheduler diskScheduler;
    private final CoordinatorChannels channels;
    private final CoordinatorProcessStore processStore;
    private final SimulationSnapshotFactory snapshotFactory;
    private final SimulationApplicationState applicationState;
    private final ApplicationIntentPlanner applicationIntentPlanner;
    private final LinkedQueue<PendingSubmission> pendingSubmissions;

    private volatile SimulationSnapshot latestSnapshot;

    private DiskSchedulingPolicy activePolicy;
    private long nextArrivalOrder;
    private long nextTick;
    private long nextRequestNumber;
    private long nextProcessNumber;
    private int totalSeekDistance;
    private int activeDiskTasks;
    private int maxConcurrentDiskTasksObserved;

    private volatile boolean shutdownRequested;
    private volatile boolean started;
    private volatile boolean acceptingCommands;
    private Thread coordinatorThread;
    private Thread diskThread;

    public SimulationCoordinator(
            SimulatedDisk disk,
            LockTable lockTable,
            JournalManager journalManager,
            DiskSchedulingPolicy initialPolicy) {
        this(disk, lockTable, journalManager, initialPolicy, SimulationApplicationState.createDefault());
    }

    public SimulationCoordinator(
            SimulatedDisk disk,
            LockTable lockTable,
            JournalManager journalManager,
            DiskSchedulingPolicy initialPolicy,
            SimulationApplicationState applicationState) {
        if (disk == null) {
            throw new IllegalArgumentException("disk cannot be null");
        }
        if (lockTable == null) {
            throw new IllegalArgumentException("lockTable cannot be null");
        }
        if (journalManager == null) {
            throw new IllegalArgumentException("journalManager cannot be null");
        }
        if (initialPolicy == null) {
            throw new IllegalArgumentException("initialPolicy cannot be null");
        }
        if (applicationState == null) {
            throw new IllegalArgumentException("applicationState cannot be null");
        }
        this.disk = copyDisk(disk);
        this.lockTable = lockTable;
        this.journalManager = journalManager;
        this.diskScheduler = new DiskScheduler();
        this.channels = new CoordinatorChannels();
        this.processStore = new CoordinatorProcessStore();
        this.snapshotFactory = new SimulationSnapshotFactory();
        this.applicationState = applicationState.deepCopy();
        synchronizeDiskWithApplicationState();
        this.applicationIntentPlanner = new ApplicationIntentPlanner(
                this.applicationState,
                this.disk,
                new PermissionService());
        this.pendingSubmissions = new LinkedQueue<>();
        this.activePolicy = initialPolicy;
        this.nextArrivalOrder = 0L;
        this.nextTick = 0L;
        this.nextRequestNumber = 1L;
        this.nextProcessNumber = 1L;
        this.totalSeekDistance = 0;
        this.activeDiskTasks = 0;
        this.maxConcurrentDiskTasksObserved = 0;
        this.shutdownRequested = false;
        this.started = false;
        this.acceptingCommands = false;
        publishSnapshot();
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        shutdownRequested = false;
        acceptingCommands = true;
        coordinatorThread = new Thread(this::runCoordinatorLoop, "SimulationCoordinatorThread");
        diskThread = new Thread(this::runDiskLoop, "DiskExecutionThread");
        started = true;
        coordinatorThread.start();
        diskThread.start();
    }

    public synchronized void shutdown() {
        if (!started) {
            return;
        }

        acceptingCommands = false;
        shutdownRequested = true;
        channels.releaseForShutdown();

        joinQuietly(coordinatorThread);
        joinQuietly(diskThread);

        coordinatorThread = null;
        diskThread = null;
        started = false;
        publishSnapshot();
    }

    public void submitOperation(PreparedOperationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command cannot be null");
        }
        requireStartedAndAcceptingCommands();
        channels.enqueueCommand(new SubmitOperationCoordinatorCommand(command));
    }

    public void submitIntent(ApplicationOperationIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent cannot be null");
        }
        requireStartedAndAcceptingCommands();
        channels.enqueueCommand(new SubmitApplicationIntentCoordinatorCommand(intent));
    }

    public void changePolicy(DiskSchedulingPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy cannot be null");
        }
        requireStartedAndAcceptingCommands();
        channels.enqueueCommand(new ChangePolicyCoordinatorCommand(policy));
    }

    public void changeHeadDirection(DiskHeadDirection direction) {
        if (direction == null) {
            throw new IllegalArgumentException("direction cannot be null");
        }
        requireStartedAndAcceptingCommands();
        channels.enqueueCommand(new ChangeDirectionCoordinatorCommand(direction));
    }

    public SimulationSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    private void runCoordinatorLoop() {
        while (true) {
            boolean worked = false;

            DiskServiceResult diskResult = channels.pollCompletedDiskResult();
            if (diskResult != null) {
                finishRunningProcess(diskResult);
                worked = true;
            }

            CoordinatorCommand command = channels.pollCommand();
            if (command != null) {
                executeCommandSafely(command);
                worked = true;
            }

            if (reconcileBlockedLockWaiters()) {
                worked = true;
            }

            if (materializePendingSubmissions()) {
                worked = true;
            }

            if (processStore.getRunningProcess() == null && tryDispatchNextReady()) {
                worked = true;
            }

            publishSnapshot();

            if (shutdownRequested
                    && processStore.getRunningProcess() == null
                    && channels.isCommandQueueEmpty()
                    && !channels.hasPendingDiskItems()) {
                break;
            }

            if (!worked) {
                sleepQuietly(2L);
            }
        }

        publishSnapshot();
    }

    private void runDiskLoop() {
        while (true) {
            DiskTask task = channels.awaitNextDiskTask();
            if (task == null) {
                if (shutdownRequested) {
                    break;
                }
                continue;
            }

            channels.publishCompletedDiskResult(new DiskServiceResult(
                    task.getProcessId(),
                    task.getRequestId(),
                    task.getPreviousHeadBlock(),
                    task.getNewHeadBlock(),
                    task.getTraveledDistance(),
                    Thread.currentThread().getName()));
        }
    }

    private boolean tryDispatchNextReady() {
        ProcessControlBlock[] readySnapshot = processStore.getReadySnapshot();
        if (readySnapshot.length == 0) {
            return false;
        }

        DiskHead headSnapshot = disk.getHead();
        DiskScheduleDecision decision = diskScheduler.selectNext(
                activePolicy,
                headSnapshot,
                readySnapshot,
                disk.getTotalBlocks());
        if (decision == null) {
            return false;
        }

        ProcessControlBlock selected = processStore.removeReadyProcessById(decision.getSelectedProcessId());
        if (selected == null) {
            return false;
        }

        if (!tryAcquireRequiredLock(selected)) {
            return true;
        }

        selected.markRunning(nextTick++);
        processStore.setRunningProcess(selected);

        ProcessExecutionContext context = processStore.requireContext(selected.getProcessId());
        registerPendingJournalIfNeeded(context);
        applyDispatchDecision(selected, decision);
        channels.publishDiskTask(new DiskTask(
                selected.getProcessId(),
                selected.getRequest().getRequestId(),
                decision.getPreviousHeadBlock(),
                decision.getNewHeadBlock(),
                decision.getTraveledDistance()));
        recordActiveDiskTask();
        return true;
    }

    private void registerPendingJournalIfNeeded(ProcessExecutionContext context) {
        PreparedOperationCommand command = context.getCommand();
        if (!command.requiresJournal()) {
            return;
        }

        JournalEntry journalEntry = journalManager.registerPending(
                command.getOperationType(),
                command.getTargetPath(),
                command.getPreparedJournalData().getUndoData(),
                command.getPreparedJournalData().getTargetNodeId(),
                command.getPreparedJournalData().getOwnerUserId(),
                command.getPreparedJournalData().getDescription());
        context.setTransactionId(journalEntry.getTransactionId());
    }

    private void applyDispatchDecision(ProcessControlBlock selected, DiskScheduleDecision decision) {
        disk.moveHeadTo(decision.getNewHeadBlock());
        disk.setHeadDirection(decision.getResultingDirection());
        totalSeekDistance += decision.getTraveledDistance();
        processStore.registerDispatch(new SimulationSnapshot.DispatchRecord(
                selected.getProcessId(),
                selected.getRequest().getRequestId(),
                decision.getPreviousHeadBlock(),
                decision.getNewHeadBlock(),
                decision.getTraveledDistance(),
                decision.getResultingDirection()));
    }

    private void recordActiveDiskTask() {
        activeDiskTasks++;
        if (activeDiskTasks > maxConcurrentDiskTasksObserved) {
            maxConcurrentDiskTasksObserved = activeDiskTasks;
        }
    }

    private void handleSubmitOperation(PreparedOperationCommand command) {
        validateUniqueProcessId(command);
        validateTargetBlockInRange(command);
        long arrivalOrder = nextArrivalOrder++;
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
                arrivalOrder);
        ProcessControlBlock process = new ProcessControlBlock(
                command.getProcessId(),
                command.getOwnerUserId(),
                request,
                command.getTargetNodeType(),
                command.getTargetNodeId(),
                command.getTargetPath(),
                command.getTargetBlock(),
                command.getRequiredLockType(),
                nextTick++);
        processStore.registerSubmittedProcess(process, command);
        admitNextNewProcess();
    }

    private void admitNextNewProcess() {
        ProcessControlBlock process = processStore.admitNextNewProcess();
        if (process == null) {
            return;
        }
        process.markReady(nextTick++);
        processStore.addReadyProcess(process);
    }

    private boolean tryAcquireRequiredLock(ProcessControlBlock process) {
        if (!requiresFileLock(process)) {
            return true;
        }

        processStore.trackLockFileId(process.getTargetNodeId());
        LockAcquireResult acquireResult = lockTable.tryAcquire(
                process.getTargetNodeId(),
                process.getProcessId(),
                process.getRequiredLockType());
        reactivateAwakenedProcesses(acquireResult);

        if (acquireResult.isBlocked()) {
            process.markBlocked(WaitReason.WAITING_LOCK, acquireResult.getBlockingProcessId());
            processStore.addBlockedProcess(process);
            return false;
        }

        return true;
    }

    private void finishRunningProcess(DiskServiceResult diskResult) {
        ProcessControlBlock runningProcess = processStore.getRunningProcess();
        if (runningProcess == null) {
            throw new IllegalStateException("received disk result without a running process");
        }
        if (!runningProcess.getProcessId().equals(diskResult.getProcessId())) {
            throw new IllegalStateException("disk result process mismatch");
        }

        ProcessExecutionContext context = processStore.requireContext(runningProcess.getProcessId());
        OperationApplyResult applyResult = invokeHandler(context.getCommand(), runningProcess, diskResult);
        completeJournalTransition(context, applyResult);
        releaseProcessLock(runningProcess);
        releaseReservedBlocksIfNeeded(context);

        runningProcess.markTerminated(applyResult.getResultStatus(), nextTick++, applyResult.getErrorMessage());
        processStore.addTerminatedProcess(runningProcess);
        processStore.clearRunningProcess();
        activeDiskTasks = Math.max(0, activeDiskTasks - 1);
    }

    private OperationApplyResult invokeHandler(
            PreparedOperationCommand command,
            ProcessControlBlock process,
            DiskServiceResult diskResult) {
        try {
            OperationApplyResult result = command.getOperationHandler().apply(command, process, diskResult);
            if (result == null) {
                return OperationApplyResult.failed("operation handler returned null");
            }
            return result;
        } catch (RuntimeException exception) {
            return OperationApplyResult.failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    private void completeJournalTransition(ProcessExecutionContext context, OperationApplyResult applyResult) {
        if (!context.getCommand().requiresJournal()) {
            return;
        }
        if (context.getTransactionId() == null) {
            throw new IllegalStateException("missing transactionId for modifying operation");
        }

        if (applyResult.getResultStatus() == ResultStatus.SUCCESS) {
            journalManager.markCommitted(context.getTransactionId());
        }
    }

    private void releaseReservedBlocksIfNeeded(ProcessExecutionContext context) {
        if (!context.getCommand().requiresJournal()) {
            return;
        }
        if (!(context.getCommand().getPreparedJournalData().getUndoData() instanceof CreateFileUndoData undoData)) {
            return;
        }
        applicationState.releaseReservedBlockIndexes(undoData.getAllocatedBlockIndexesSnapshot());
    }

    private void releaseProcessLock(ProcessControlBlock process) {
        if (!requiresFileLock(process)) {
            return;
        }

        LockReleaseResult releaseResult = lockTable.releaseByProcess(process.getTargetNodeId(), process.getProcessId());
        reactivateAwakenedProcesses(releaseResult);
    }

    private void reactivateAwakenedProcesses(LockAcquireResult acquireResult) {
        for (int i = 0; i < acquireResult.getAwakenedCount(); i++) {
            reactivateBlockedProcess(acquireResult.getAwakenedEntryAt(i));
        }
    }

    private void reactivateAwakenedProcesses(LockReleaseResult releaseResult) {
        for (int i = 0; i < releaseResult.getAwakenedCount(); i++) {
            reactivateBlockedProcess(releaseResult.getAwakenedEntryAt(i));
        }
    }

    private void reactivateBlockedProcess(LockWaitEntry awakenedEntry) {
        ProcessControlBlock process = processStore.removeBlockedProcessById(awakenedEntry.getProcessId());
        if (process == null) {
            return;
        }
        process.markReady(nextTick++);
        processStore.addReadyProcess(process);
    }

    private boolean reconcileBlockedLockWaiters() {
        ProcessControlBlock[] blockedSnapshot = processStore.getBlockedSnapshot();
        boolean changed = false;

        for (ProcessControlBlock process : blockedSnapshot) {
            if (process.getWaitReason() != WaitReason.WAITING_LOCK || !requiresFileLock(process)) {
                continue;
            }

            LockAcquireResult acquireResult = lockTable.tryAcquire(
                    process.getTargetNodeId(),
                    process.getProcessId(),
                    process.getRequiredLockType());
            reactivateAwakenedProcesses(acquireResult);

            if (acquireResult.isBlocked()) {
                String previousBlockingProcessId = process.getBlockedByProcessId();
                process.markBlocked(WaitReason.WAITING_LOCK, acquireResult.getBlockingProcessId());
                if (!sameOptionalValue(previousBlockingProcessId, process.getBlockedByProcessId())) {
                    changed = true;
                }
                continue;
            }

            if (acquireResult.isGranted() || acquireResult.isAlreadyHeld()) {
                ProcessControlBlock reactivated = processStore.removeBlockedProcessById(process.getProcessId());
                if (reactivated == null) {
                    continue;
                }
                reactivated.markReady(nextTick++);
                processStore.addReadyProcess(reactivated);
                changed = true;
            }
        }

        return changed;
    }

    private void publishSnapshot() {
        latestSnapshot = snapshotFactory.build(
                activePolicy,
                disk,
                applicationState,
                processStore,
                lockTable,
                journalManager,
                totalSeekDistance,
                maxConcurrentDiskTasksObserved);
    }

    private void synchronizeDiskWithApplicationState() {
        FileNode[] files = getFileSnapshot();
        boolean[] claimedBlocks = new boolean[disk.getTotalBlocks()];
        boolean[] reservedFirstBlocks = buildReservedFirstBlocks(files);
        for (FileNode file : files) {
            synchronizeFileAllocation(file, claimedBlocks, reservedFirstBlocks);
        }
        ensureNoOrphanedOccupiedBlocks(claimedBlocks);
    }

    private FileNode[] getFileSnapshot() {
        FsNode[] nodes = applicationState.getFileSystemCatalog().getAllNodesSnapshot();
        int fileCount = 0;
        for (FsNode node : nodes) {
            if (node instanceof FileNode) {
                fileCount++;
            }
        }

        FileNode[] files = new FileNode[fileCount];
        int index = 0;
        for (FsNode node : nodes) {
            if (node instanceof FileNode file) {
                files[index++] = file;
            }
        }
        return files;
    }

    private boolean[] buildReservedFirstBlocks(FileNode[] files) {
        boolean[] reservedFirstBlocks = new boolean[disk.getTotalBlocks()];
        for (FileNode file : files) {
            int firstBlockIndex = file.getFirstBlockIndex();
            if (!disk.isValidIndex(firstBlockIndex)) {
                throw new IllegalArgumentException("file firstBlockIndex is out of disk range: " + file.getId());
            }
            if (reservedFirstBlocks[firstBlockIndex]) {
                throw new IllegalArgumentException("duplicate file firstBlockIndex detected: " + firstBlockIndex);
            }
            reservedFirstBlocks[firstBlockIndex] = true;
        }
        return reservedFirstBlocks;
    }

    private void synchronizeFileAllocation(FileNode file, boolean[] claimedBlocks, boolean[] reservedFirstBlocks) {
        int firstBlockIndex = file.getFirstBlockIndex();
        DiskBlock firstBlock = disk.getBlock(firstBlockIndex);
        if (firstBlock.isFree()) {
            hydrateFileAllocation(file, claimedBlocks, reservedFirstBlocks);
            return;
        }
        validateExistingFileAllocation(file, claimedBlocks);
    }

    private void hydrateFileAllocation(FileNode file, boolean[] claimedBlocks, boolean[] reservedFirstBlocks) {
        int[] chain = new int[file.getSizeInBlocks()];
        chain[0] = file.getFirstBlockIndex();
        markClaimed(chain[0], file, claimedBlocks);

        int searchStart = (chain[0] + 1) % disk.getTotalBlocks();
        for (int i = 1; i < chain.length; i++) {
            int blockIndex = findNextFreeUnclaimedBlock(searchStart, claimedBlocks, reservedFirstBlocks);
            if (blockIndex == SimulatedDisk.NO_FREE_BLOCK) {
                throw new IllegalArgumentException("unable to hydrate disk allocation for file: " + file.getId());
            }
            chain[i] = blockIndex;
            markClaimed(blockIndex, file, claimedBlocks);
            searchStart = (blockIndex + 1) % disk.getTotalBlocks();
        }

        for (int i = 0; i < chain.length; i++) {
            int nextBlockIndex = (i == chain.length - 1) ? DiskBlock.NO_NEXT_BLOCK : chain[i + 1];
            disk.allocateBlock(chain[i], file.getId(), nextBlockIndex, file.isSystemFile());
        }
    }

    private void validateExistingFileAllocation(FileNode file, boolean[] claimedBlocks) {
        int currentBlockIndex = file.getFirstBlockIndex();
        for (int i = 0; i < file.getSizeInBlocks(); i++) {
            if (!disk.isValidIndex(currentBlockIndex)) {
                throw new IllegalArgumentException("disk chain points outside disk for file: " + file.getId());
            }

            DiskBlock block = disk.getBlock(currentBlockIndex);
            if (block.isFree()) {
                throw new IllegalArgumentException("disk chain is incomplete for file: " + file.getId());
            }
            if (!file.getId().equals(block.getOwnerFileId())) {
                throw new IllegalArgumentException("disk block owner mismatch for file: " + file.getId());
            }
            if (block.isSystemReserved() != file.isSystemFile()) {
                throw new IllegalArgumentException("disk block system flag mismatch for file: " + file.getId());
            }

            markClaimed(currentBlockIndex, file, claimedBlocks);

            if (i == file.getSizeInBlocks() - 1) {
                if (block.getNextBlockIndex() != DiskBlock.NO_NEXT_BLOCK) {
                    throw new IllegalArgumentException("disk chain length mismatch for file: " + file.getId());
                }
                return;
            }

            currentBlockIndex = block.getNextBlockIndex();
        }
    }

    private int findNextFreeUnclaimedBlock(int startIndex, boolean[] claimedBlocks, boolean[] reservedFirstBlocks) {
        for (int offset = 0; offset < disk.getTotalBlocks(); offset++) {
            int candidate = (startIndex + offset) % disk.getTotalBlocks();
            if (!claimedBlocks[candidate]
                    && !reservedFirstBlocks[candidate]
                    && disk.getBlock(candidate).isFree()) {
                return candidate;
            }
        }
        return SimulatedDisk.NO_FREE_BLOCK;
    }

    private void markClaimed(int blockIndex, FileNode file, boolean[] claimedBlocks) {
        if (claimedBlocks[blockIndex]) {
            throw new IllegalArgumentException("duplicate block claim detected for file: " + file.getId());
        }
        claimedBlocks[blockIndex] = true;
    }

    private void ensureNoOrphanedOccupiedBlocks(boolean[] claimedBlocks) {
        for (int blockIndex = 0; blockIndex < disk.getTotalBlocks(); blockIndex++) {
            if (!claimedBlocks[blockIndex] && !disk.getBlock(blockIndex).isFree()) {
                throw new IllegalArgumentException(
                        "disk contains occupied block without filesystem owner: " + blockIndex);
            }
        }
    }

    private SimulatedDisk copyDisk(SimulatedDisk sourceDisk) {
        DiskHead sourceHead = sourceDisk.getHead();
        SimulatedDisk copy = new SimulatedDisk(
                sourceDisk.getTotalBlocks(),
                sourceHead.getCurrentBlock(),
                sourceHead.getDirection());
        for (int blockIndex = 0; blockIndex < sourceDisk.getTotalBlocks(); blockIndex++) {
            DiskBlock sourceBlock = sourceDisk.getBlock(blockIndex);
            if (!sourceBlock.isFree()) {
                copy.allocateBlock(
                        blockIndex,
                        sourceBlock.getOwnerFileId(),
                        sourceBlock.getNextBlockIndex(),
                        sourceBlock.isSystemReserved());
            }
        }
        return copy;
    }

    private boolean materializePendingSubmissions() {
        boolean changed = false;

        while (!pendingSubmissions.isEmpty()) {
            PendingSubmission next = pendingSubmissions.peek();
            if (next.intent != null && processStore.hasActiveProcesses()) {
                break;
            }

            pendingSubmissions.dequeue();
            if (next.intent != null) {
                materializeIntentSubmission(next);
                changed = true;
                continue;
            }

            try {
                handleSubmitOperation(next.command);
            } catch (RuntimeException exception) {
                processStore.addRejectedTerminatedProcess(next.command, exception.getMessage());
            }
            changed = true;
        }

        return changed;
    }

    private void materializeIntentSubmission(PendingSubmission pendingIntent) {
        try {
            if (pendingIntent.intent instanceof SwitchSessionIntent switchSessionIntent) {
                applicationState.getSessionContext().switchTo(
                        applicationState.getUserStore().requireById(switchSessionIntent.getTargetUserId()));
                return;
            }

            PreparedOperationCommand command = applicationIntentPlanner.plan(
                    pendingIntent.intent,
                    pendingIntent.requestId,
                    pendingIntent.processId);
            handleSubmitOperation(command);
        } catch (RuntimeException exception) {
            processStore.addRejectedTerminatedProcess(
                    pendingIntent.processId,
                    pendingIntent.requestId,
                    describeIntentTargetPath(pendingIntent.intent),
                    0,
                    exception.getMessage());
        }
    }

    private void executeCommandSafely(CoordinatorCommand command) {
        try {
            command.execute();
        } catch (RuntimeException exception) {
            if (command instanceof SubmitOperationCoordinatorCommand submitCommand) {
                processStore.addRejectedTerminatedProcess(submitCommand.command, exception.getMessage());
            }
        }
    }

    private String describeIntentTargetPath(ApplicationOperationIntent intent) {
        if (intent instanceof CreateFileIntent createFileIntent) {
            return buildIntentChildPath(createFileIntent.getParentDirectoryPath(), createFileIntent.getFileName());
        }
        if (intent instanceof CreateDirectoryIntent createDirectoryIntent) {
            return buildIntentChildPath(createDirectoryIntent.getParentDirectoryPath(), createDirectoryIntent.getDirectoryName());
        }
        if (intent instanceof ReadIntent readIntent) {
            return readIntent.getTargetPath();
        }
        if (intent instanceof RenameIntent renameIntent) {
            return renameIntent.getTargetPath();
        }
        if (intent instanceof DeleteIntent deleteIntent) {
            return deleteIntent.getTargetPath();
        }
        return "/";
    }

    private String buildIntentChildPath(String parentPath, String childName) {
        if ("/".equals(parentPath)) {
            return "/" + childName;
        }
        return parentPath + "/" + childName;
    }

    private void validateTargetBlockInRange(PreparedOperationCommand command) {
        if (!disk.isValidIndex(command.getTargetBlock())) {
            throw new IllegalArgumentException("targetBlock is out of disk range: " + command.getTargetBlock());
        }
    }

    private void validateUniqueProcessId(PreparedOperationCommand command) {
        if (processStore.containsProcessId(command.getProcessId())) {
            throw new IllegalArgumentException("duplicate processId is not allowed: " + command.getProcessId());
        }
    }

    private boolean requiresFileLock(ProcessControlBlock process) {
        return process.getRequiredLockType() != null && process.getTargetNodeId() != null;
    }

    private String nextRequestId() {
        String candidate;
        do {
            candidate = "INTENT-REQ-" + nextRequestNumber++;
        } while (processStore.containsRequestId(candidate) || pendingContainsRequestId(candidate));
        return candidate;
    }

    private String nextProcessId() {
        String candidate;
        do {
            candidate = "INTENT-PROC-" + nextProcessNumber++;
        } while (processStore.containsProcessId(candidate) || pendingContainsProcessId(candidate));
        return candidate;
    }

    private boolean pendingContainsRequestId(String requestId) {
        final boolean[] found = new boolean[] {false};
        pendingSubmissions.forEach(submission -> {
            if (found[0]) {
                return;
            }
            if (submission.command != null && requestId.equals(submission.command.getRequestId())) {
                found[0] = true;
                return;
            }
            if (requestId.equals(submission.requestId)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private boolean pendingContainsProcessId(String processId) {
        final boolean[] found = new boolean[] {false};
        pendingSubmissions.forEach(submission -> {
            if (found[0]) {
                return;
            }
            if (submission.command != null && processId.equals(submission.command.getProcessId())) {
                found[0] = true;
                return;
            }
            if (processId.equals(submission.processId)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private void requireStartedAndAcceptingCommands() {
        if (!started) {
            throw new IllegalStateException("SimulationCoordinator must be started before enqueuing commands");
        }
        if (!acceptingCommands) {
            throw new IllegalStateException("SimulationCoordinator is not accepting commands");
        }
    }

    private void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    thread.join();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean sameOptionalValue(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private final class SubmitOperationCoordinatorCommand implements CoordinatorCommand {

        private final PreparedOperationCommand command;

        private SubmitOperationCoordinatorCommand(PreparedOperationCommand command) {
            this.command = command;
        }

        @Override
        public void execute() {
            pendingSubmissions.enqueue(PendingSubmission.forCommand(command));
        }
    }

    private final class SubmitApplicationIntentCoordinatorCommand implements CoordinatorCommand {

        private final ApplicationOperationIntent intent;

        private SubmitApplicationIntentCoordinatorCommand(ApplicationOperationIntent intent) {
            this.intent = intent;
        }

        @Override
        public void execute() {
            pendingSubmissions.enqueue(PendingSubmission.forIntent(
                    intent,
                    nextRequestId(),
                    nextProcessId()));
        }
    }

    private final class ChangePolicyCoordinatorCommand implements CoordinatorCommand {

        private final DiskSchedulingPolicy policy;

        private ChangePolicyCoordinatorCommand(DiskSchedulingPolicy policy) {
            this.policy = policy;
        }

        @Override
        public void execute() {
            activePolicy = policy;
        }
    }

    private final class ChangeDirectionCoordinatorCommand implements CoordinatorCommand {

        private final DiskHeadDirection direction;

        private ChangeDirectionCoordinatorCommand(DiskHeadDirection direction) {
            this.direction = direction;
        }

        @Override
        public void execute() {
            disk.setHeadDirection(direction);
        }
    }

    private static final class PendingSubmission {

        private final PreparedOperationCommand command;
        private final ApplicationOperationIntent intent;
        private final String requestId;
        private final String processId;

        private PendingSubmission(
                PreparedOperationCommand command,
                ApplicationOperationIntent intent,
                String requestId,
                String processId) {
            this.command = command;
            this.intent = intent;
            this.requestId = requestId;
            this.processId = processId;
        }

        private static PendingSubmission forCommand(PreparedOperationCommand command) {
            return new PendingSubmission(command, null, null, null);
        }

        private static PendingSubmission forIntent(
                ApplicationOperationIntent intent,
                String requestId,
                String processId) {
            return new PendingSubmission(null, intent, requestId, processId);
        }
    }
}
