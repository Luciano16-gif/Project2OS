package ve.edu.unimet.so.project2.coordinator.core;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
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
import ve.edu.unimet.so.project2.coordinator.log.SystemEventLog;
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
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.persistence.JournalRecoveryService;
import ve.edu.unimet.so.project2.persistence.LoadedSystemState;
import ve.edu.unimet.so.project2.persistence.SystemPersistenceService;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.process.IoRequest;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;
import ve.edu.unimet.so.project2.process.ResultStatus;
import ve.edu.unimet.so.project2.process.WaitReason;
import ve.edu.unimet.so.project2.scenario.LoadedScenarioState;
import ve.edu.unimet.so.project2.scenario.ScenarioLoader;
import ve.edu.unimet.so.project2.scenario.ScenarioOperationIntent;
import ve.edu.unimet.so.project2.scheduler.DiskScheduleDecision;
import ve.edu.unimet.so.project2.scheduler.DiskScheduler;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public final class SimulationCoordinator {

    private SimulatedDisk disk;
    private final LockTable lockTable;
    private JournalManager journalManager;
    private final DiskScheduler diskScheduler;
    private final CoordinatorChannels channels;
    private final CoordinatorProcessStore processStore;
    private final SimulationSnapshotFactory snapshotFactory;
    private SimulationApplicationState applicationState;
    private ApplicationIntentPlanner applicationIntentPlanner;
    private final LinkedQueue<PendingSubmission> pendingSubmissions;
    private final SystemPersistenceService systemPersistenceService;
    private final JournalRecoveryService journalRecoveryService;
    private final ScenarioLoader scenarioLoader;
    private final SystemEventLog eventLog;

    private volatile SimulationSnapshot latestSnapshot;

    private DiskSchedulingPolicy activePolicy;
    private long nextArrivalOrder;
    private long nextTick;
    private long nextRequestNumber;
    private long nextProcessNumber;
    private int totalSeekDistance;
    private int activeDiskTasks;
    private int maxConcurrentDiskTasksObserved;
    private volatile long executionDelayMillis;

    private volatile boolean shutdownRequested;
    private volatile boolean started;
    private volatile boolean acceptingCommands;
    private volatile boolean simulatedFailureArmed;
    private volatile boolean recoveryQuarantineActive;
    private String queuedIntentCancellationUserId;
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
        this.systemPersistenceService = new SystemPersistenceService();
        this.journalRecoveryService = new JournalRecoveryService();
        this.scenarioLoader = new ScenarioLoader();
        this.eventLog = new SystemEventLog();
        this.activePolicy = initialPolicy;
        this.nextArrivalOrder = 0L;
        this.nextTick = 0L;
        this.nextRequestNumber = 1L;
        this.nextProcessNumber = 1L;
        this.totalSeekDistance = 0;
        this.activeDiskTasks = 0;
        this.maxConcurrentDiskTasksObserved = 0;
        this.executionDelayMillis = 0L;
        this.shutdownRequested = false;
        this.started = false;
        this.acceptingCommands = false;
        this.simulatedFailureArmed = false;
        this.recoveryQuarantineActive = false;
        this.queuedIntentCancellationUserId = null;
        recordEvent("SYSTEM", "coordinator initialized");
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
        recordEvent("SYSTEM", "coordinator started");
    }

    public synchronized void shutdown() {
        if (!started) {
            return;
        }

        acceptingCommands = false;
        shutdownRequested = true;
        channels.releaseForShutdown();
        recordEvent("SYSTEM", "shutdown requested");

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
        ensureNotAwaitingRecovery();
        channels.enqueueCommand(new SubmitOperationCoordinatorCommand(command));
    }

    public void submitIntent(ApplicationOperationIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent cannot be null");
        }
        requireStartedAndAcceptingCommands();
        ensureNotAwaitingRecovery();
        channels.enqueueCommand(new SubmitApplicationIntentCoordinatorCommand(intent));
    }

    public void switchSession(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be blank");
        }
        submitIntent(new SwitchSessionIntent(userId));
    }

    public void changePolicy(DiskSchedulingPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy cannot be null");
        }
        requireStartedAndAcceptingCommands();
        ensureNotAwaitingRecovery();
        channels.enqueueCommand(new ChangePolicyCoordinatorCommand(policy));
    }

    public void changeHeadDirection(DiskHeadDirection direction) {
        if (direction == null) {
            throw new IllegalArgumentException("direction cannot be null");
        }
        requireStartedAndAcceptingCommands();
        ensureNotAwaitingRecovery();
        channels.enqueueCommand(new ChangeDirectionCoordinatorCommand(direction));
    }

    public void changeExecutionDelay(long delayMillis) {
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis cannot be negative");
        }
        requireStartedAndAcceptingCommands();
        ensureNotAwaitingRecovery();
        channels.enqueueCommand(new ChangeExecutionDelayCoordinatorCommand(delayMillis));
    }

    public long getExecutionDelayMillis() {
        return executionDelayMillis;
    }

    public SimulationSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public void saveSystem(Path path) {
        requireStartedAndAcceptingCommands();
        runSynchronously(new SaveSystemCoordinatorCommand(path));
    }

    public void loadSystem(Path path) {
        requireStartedAndAcceptingCommands();
        runSynchronously(new LoadSystemCoordinatorCommand(path));
    }

    public void loadScenario(Path path) {
        requireStartedAndAcceptingCommands();
        runSynchronously(new LoadScenarioCoordinatorCommand(path));
    }

    public void armSimulatedFailure() {
        requireStartedAndAcceptingCommands();
        runSynchronously(new ArmSimulatedFailureCoordinatorCommand());
    }

    public void recoverPendingJournalEntries() {
        requireStartedAndAcceptingCommands();
        runSynchronously(new RecoverPendingJournalCoordinatorCommand());
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

            if (worked && executionDelayMillis > 0L) {
                sleepQuietly(executionDelayMillis);
            } else if (!worked) {
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

            recordEvent("DISK", "serviced request " + task.getRequestId() + " for process " + task.getProcessId());
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

        selected = resolveDeferredReadyProcess(selected);
        if (selected == null) {
            return true;
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
        recordEvent(
                "JOURNAL",
                "registered pending entry " + journalEntry.getTransactionId() + " for process " + command.getProcessId());
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
        recordEvent(
                "DISPATCH",
                "process " + selected.getProcessId()
                        + " dispatched from block " + decision.getPreviousHeadBlock()
                        + " to " + decision.getNewHeadBlock());
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
        ProcessControlBlock process = buildProcess(command, nextArrivalOrder++, nextTick++);
        processStore.registerSubmittedProcess(process, command);
        recordEvent(
                "PROCESS",
                "submitted process " + process.getProcessId() + " for " + command.getOperationType()
                        + " on " + command.getTargetPath());
        admitNextNewProcess();
    }

    private ProcessControlBlock buildProcess(
            PreparedOperationCommand command,
            long arrivalOrder,
            long creationTick) {
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

    private ProcessControlBlock resolveDeferredReadyProcess(ProcessControlBlock process) {
        ProcessExecutionContext context = processStore.requireContext(process.getProcessId());
        if (!context.hasDeferredIntent()) {
            return process;
        }

        ApplicationOperationIntent deferredIntent = context.getDeferredIntent();
        try {
            ApplicationOperationIntent resolvedIntent = deferredIntent;
            int resolvedTargetBlock = process.getTargetBlock();
            if (resolvedIntent instanceof ScenarioOperationIntent scenarioOperationIntent) {
                resolvedIntent = scenarioOperationIntent.resolve(applicationState, disk);
            }

            PreparedOperationCommand resolvedCommand = applicationIntentPlanner.plan(
                    resolvedIntent,
                    process.getRequest().getRequestId(),
                    process.getProcessId(),
                    context.getDeferredActorUserId() == null
                            ? process.getOwnerUserId()
                            : context.getDeferredActorUserId());
            if (resolvedCommand.getTargetBlock() != resolvedTargetBlock) {
                resolvedCommand = withTargetBlock(resolvedCommand, resolvedTargetBlock);
            }
            ProcessControlBlock resolvedProcess = buildProcess(
                    resolvedCommand,
                    process.getRequest().getArrivalOrder(),
                    process.getCreationTick());
            resolvedProcess.markReady(process.getReadyTick());
            context.resolveDeferredIntent(resolvedProcess, resolvedCommand);
            return resolvedProcess;
        } catch (RuntimeException exception) {
            processStore.addRejectedTerminatedProcess(
                    process.getProcessId(),
                    process.getRequest().getRequestId(),
                    inferIntentOperationType(deferredIntent),
                    process.getOwnerUserId(),
                    process.getRequiredLockType(),
                    describeIntentTargetPath(deferredIntent),
                    process.getTargetBlock(),
                    exception.getMessage());
            recordEvent(
                    "PROCESS",
                    "rejected deferred scenario process " + process.getProcessId() + ": " + exception.getMessage());
            return null;
        }
    }

    private PreparedOperationCommand withTargetBlock(PreparedOperationCommand command, int targetBlock) {
        return new PreparedOperationCommand(
                command.getRequestId(),
                command.getProcessId(),
                command.getOwnerUserId(),
                command.getOperationType(),
                command.getTargetNodeType(),
                command.getTargetPath(),
                command.getTargetNodeId(),
                targetBlock,
                command.getRequestedSizeInBlocks(),
                command.getRequiredLockType(),
                command.getPreparedJournalData(),
                command.getOperationHandler());
    }

    private void admitNextNewProcess() {
        ProcessControlBlock process = processStore.admitNextNewProcess();
        if (process == null) {
            return;
        }
        process.markReady(nextTick++);
        processStore.addReadyProcess(process);
        recordEvent("PROCESS", "process " + process.getProcessId() + " moved to READY");
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
            recordEvent(
                    "LOCK",
                    "process " + process.getProcessId() + " blocked waiting for "
                            + process.getRequiredLockType() + " lock on " + process.getTargetNodeId());
            return false;
        }

        if (acquireResult.isGranted()) {
            recordEvent(
                    "LOCK",
                    "process " + process.getProcessId() + " acquired "
                            + process.getRequiredLockType() + " lock on " + process.getTargetNodeId());
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
        applyResult = completeJournalTransition(context, applyResult);
        releaseProcessLock(runningProcess);
        releaseReservedBlocksIfNeeded(context);

        runningProcess.markTerminated(applyResult.getResultStatus(), nextTick++, applyResult.getErrorMessage());
        processStore.addTerminatedProcess(runningProcess);
        processStore.clearRunningProcess();
        activeDiskTasks = Math.max(0, activeDiskTasks - 1);
        recordEvent(
                "PROCESS",
                "process " + runningProcess.getProcessId() + " terminated with " + applyResult.getResultStatus());
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

    private OperationApplyResult completeJournalTransition(ProcessExecutionContext context, OperationApplyResult applyResult) {
        if (!context.getCommand().requiresJournal()) {
            return applyResult;
        }
        if (context.getTransactionId() == null) {
            throw new IllegalStateException("missing transactionId for modifying operation");
        }

        if (applyResult.getResultStatus() != ResultStatus.SUCCESS) {
            recordEvent(
                    "JOURNAL",
                    "entry " + context.getTransactionId() + " remains PENDING after " + applyResult.getResultStatus());
            return applyResult;
        }

        if (simulatedFailureArmed) {
            simulatedFailureArmed = false;
            enterRecoveryQuarantine(context.getCommand(), "simulated crash before journal commit");
            recordEvent(
                    "CRASH",
                    "simulated failure injected before COMMIT for transaction " + context.getTransactionId());
            return OperationApplyResult.cancelled("simulated crash before journal commit");
        }

        journalManager.markCommitted(context.getTransactionId());
        recordEvent("JOURNAL", "entry " + context.getTransactionId() + " marked COMMITTED");
        return applyResult;
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
        if (releaseResult.isReleased()) {
            recordEvent("LOCK", "process " + process.getProcessId() + " released lock on " + process.getTargetNodeId());
        }
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
        recordEvent("LOCK", "process " + process.getProcessId() + " reactivated from lock wait");
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
        refreshDiskOccupancyMarkers();
        latestSnapshot = snapshotFactory.build(
                activePolicy,
                disk,
                applicationState,
                processStore,
                lockTable,
                journalManager,
                eventLog,
                totalSeekDistance,
                maxConcurrentDiskTasksObserved);
    }

    private void saveSystemState(Path path) {
        ensureCoordinatorIdleForAdminOperation();
        systemPersistenceService.save(
                path,
                activePolicy,
                disk,
                applicationState,
                journalManager);
        recordEvent("PERSISTENCE", "system saved to " + path);
    }

    private void loadPersistedSystemState(Path path) {
        ensureCoordinatorIdleForAdminOperation();
        LoadedSystemState loadedState = systemPersistenceService.load(path);
        journalRecoveryService.recoverPendingEntries(
                loadedState.applicationState(),
                loadedState.disk(),
                loadedState.journalManager());
        loadedState.applicationState().realignGeneratedIdsToCatalog();
        validatePersistedStateCoherence(
                loadedState.disk(),
                loadedState.applicationState());
        applyLoadedState(
                loadedState.disk(),
                loadedState.applicationState(),
                loadedState.journalManager(),
                loadedState.policy());
        recordEvent("PERSISTENCE", "system loaded from " + path);
        publishSnapshot();
    }

    private void loadExternalScenario(Path path) {
        ensureCoordinatorIdleForAdminOperation();
        LoadedScenarioState loadedScenarioState =
                scenarioLoader.load(path, disk.getTotalBlocks(), disk.getHead().getDirection());
        ScenarioOperationIntent[] scenarioIntents = loadedScenarioState.scenarioIntents();
        validateScenarioIntents(scenarioIntents, loadedScenarioState.disk());
        ScenarioReadyRegistration[] stagedRegistrations = buildScenarioReadyRegistrations(
                scenarioIntents,
                loadedScenarioState.applicationState(),
                loadedScenarioState.disk());
        applyLoadedState(
                loadedScenarioState.disk(),
                loadedScenarioState.applicationState(),
                new JournalManager(),
                activePolicy);
        for (ScenarioReadyRegistration stagedRegistration : stagedRegistrations) {
            processStore.registerReadyScenarioProcess(
                    stagedRegistration.process(),
                    stagedRegistration.intent());
        }
        nextArrivalOrder = stagedRegistrations.length;
        nextTick = stagedRegistrations.length * 2L;
        recordEvent("SCENARIO", "scenario loaded from " + path + " with " + stagedRegistrations.length + " requests");
        publishSnapshot();
    }

    private void recoverPendingJournalEntriesInMemory() {
        ensureCoordinatorIdleForAdminOperation();
        if (recoveryQuarantineActive && !lockTable.isEmpty()) {
            lockTable.clear();
            recordEvent("LOCK", "invalidated remaining lock state before recovery");
        }
        journalRecoveryService.recoverPendingEntries(applicationState, disk, journalManager);
        recoveryQuarantineActive = false;
        simulatedFailureArmed = false;
        queuedIntentCancellationUserId = null;
        recordEvent("RECOVERY", "recovered in-memory pending journal entries");
        publishSnapshot();
    }

    private void validateScenarioIntents(ScenarioOperationIntent[] intents, SimulatedDisk targetDisk) {
        for (int i = 0; i < intents.length; i++) {
            ScenarioOperationIntent intent = intents[i];
            if (intent == null) {
                throw new IllegalArgumentException("intent at index " + i + " cannot be null");
            }
            if (!targetDisk.isValidIndex(intent.getStartBlock())) {
                throw new IllegalArgumentException("targetBlock is out of disk range: " + intent.getStartBlock());
            }
        }
    }

    private ScenarioReadyRegistration[] buildScenarioReadyRegistrations(
            ScenarioOperationIntent[] intents,
            SimulationApplicationState targetApplicationState,
            SimulatedDisk targetDisk) {
        ScenarioReadyRegistration[] registrations = new ScenarioReadyRegistration[intents.length];
        long arrivalOrder = 0L;
        long creationTick = 0L;
        for (int i = 0; i < intents.length; i++) {
            registrations[i] = new ScenarioReadyRegistration(
                    createReadyScenarioProcess(
                            intents[i],
                            "SCN-REQ-" + (i + 1),
                            "SCN-PROC-" + (i + 1),
                            targetApplicationState,
                            targetDisk,
                            arrivalOrder++,
                            creationTick),
                    intents[i]);
            creationTick += 2L;
        }
        return registrations;
    }

    private ProcessControlBlock createReadyScenarioProcess(
            ScenarioOperationIntent intent,
            String requestId,
            String processId,
            SimulationApplicationState targetApplicationState,
            SimulatedDisk targetDisk,
            long arrivalOrder,
            long creationTick) {
        if (intent == null) {
            throw new IllegalArgumentException("intent cannot be null");
        }
        if (!targetDisk.isValidIndex(intent.getStartBlock())) {
            throw new IllegalArgumentException("targetBlock is out of disk range: " + intent.getStartBlock());
        }

        String ownerUserId = targetApplicationState.getSessionContext().getCurrentUserId();
        String placeholderTargetPath = intent.describeTargetPath(targetApplicationState, targetDisk);
        String placeholderTargetNodeId = "SCENARIO-POS-" + intent.getStartBlock();
        LockType requiredLockType = intent.getOperationType() == IoOperationType.READ
                ? LockType.SHARED
                : LockType.EXCLUSIVE;
        IoRequest request = new IoRequest(
                requestId,
                processId,
                intent.getOperationType(),
                ve.edu.unimet.so.project2.filesystem.FsNodeType.FILE,
                placeholderTargetPath,
                placeholderTargetNodeId,
                intent.getStartBlock(),
                0,
                ownerUserId,
                arrivalOrder);
        ProcessControlBlock process = new ProcessControlBlock(
                processId,
                ownerUserId,
                request,
                ve.edu.unimet.so.project2.filesystem.FsNodeType.FILE,
                placeholderTargetNodeId,
                placeholderTargetPath,
                intent.getStartBlock(),
                requiredLockType,
                creationTick);
        process.markReady(creationTick + 1L);
        return process;
    }

    private void applyLoadedState(
            SimulatedDisk newDisk,
            SimulationApplicationState newApplicationState,
            JournalManager newJournalManager,
            DiskSchedulingPolicy newPolicy) {
        this.disk = newDisk;
        this.applicationState = newApplicationState;
        this.journalManager = newJournalManager;
        this.applicationIntentPlanner = new ApplicationIntentPlanner(
                this.applicationState,
                this.disk,
                new PermissionService());
        this.activePolicy = newPolicy;
        this.totalSeekDistance = 0;
        this.maxConcurrentDiskTasksObserved = 0;
        this.activeDiskTasks = 0;
        this.nextTick = 0L;
        this.nextArrivalOrder = 0L;
        this.nextRequestNumber = 1L;
        this.nextProcessNumber = 1L;
        processStore.clear();
        lockTable.clear();
        pendingSubmissions.clear();
        eventLog.clear();
        simulatedFailureArmed = false;
        recoveryQuarantineActive = false;
        queuedIntentCancellationUserId = null;
        recordEvent("SYSTEM", "runtime state reinitialized from loaded source");
    }

    private void ensureCoordinatorIdleForAdminOperation() {
        if (processStore.hasActiveProcesses()) {
            throw new IllegalStateException("coordinator must be idle before admin state operations");
        }
        if (!pendingSubmissions.isEmpty()) {
            throw new IllegalStateException("coordinator has pending submissions");
        }
        if (activeDiskTasks != 0 || channels.hasPendingDiskItems()) {
            throw new IllegalStateException("coordinator has active disk work");
        }
        if (!recoveryQuarantineActive && !lockTable.isEmpty()) {
            throw new IllegalStateException("coordinator has active lock state");
        }
    }

    private void synchronizeDiskWithApplicationState() {
        FileNode[] files = getFileSnapshot(applicationState);
        boolean[] claimedBlocks = new boolean[disk.getTotalBlocks()];
        boolean[] reservedFirstBlocks = buildReservedFirstBlocks(files, disk);
        for (FileNode file : files) {
            synchronizeFileAllocation(file, claimedBlocks, reservedFirstBlocks, disk);
        }
        ensureNoOrphanedOccupiedBlocks(claimedBlocks, disk);
    }

    private void validatePersistedStateCoherence(
            SimulatedDisk persistedDisk,
            SimulationApplicationState persistedApplicationState) {
        FileNode[] files = getFileSnapshot(persistedApplicationState);
        boolean[] claimedBlocks = new boolean[persistedDisk.getTotalBlocks()];
        buildReservedFirstBlocks(files, persistedDisk);
        for (FileNode file : files) {
            validateExistingFileAllocation(file, claimedBlocks, persistedDisk);
        }
        ensureNoOrphanedOccupiedBlocks(claimedBlocks, persistedDisk);
    }

    private FileNode[] getFileSnapshot(SimulationApplicationState sourceApplicationState) {
        FsNode[] nodes = sourceApplicationState.getFileSystemCatalog().getAllNodesSnapshot();
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

    private boolean[] buildReservedFirstBlocks(FileNode[] files, SimulatedDisk targetDisk) {
        boolean[] reservedFirstBlocks = new boolean[targetDisk.getTotalBlocks()];
        for (FileNode file : files) {
            int firstBlockIndex = file.getFirstBlockIndex();
            if (!targetDisk.isValidIndex(firstBlockIndex)) {
                throw new IllegalArgumentException("file firstBlockIndex is out of disk range: " + file.getId());
            }
            if (reservedFirstBlocks[firstBlockIndex]) {
                throw new IllegalArgumentException("duplicate file firstBlockIndex detected: " + firstBlockIndex);
            }
            reservedFirstBlocks[firstBlockIndex] = true;
        }
        return reservedFirstBlocks;
    }

    private void synchronizeFileAllocation(
            FileNode file,
            boolean[] claimedBlocks,
            boolean[] reservedFirstBlocks,
            SimulatedDisk targetDisk) {
        int firstBlockIndex = file.getFirstBlockIndex();
        DiskBlock firstBlock = targetDisk.getBlock(firstBlockIndex);
        if (firstBlock.isFree()) {
            hydrateFileAllocation(file, claimedBlocks, reservedFirstBlocks, targetDisk);
            return;
        }
        validateExistingFileAllocation(file, claimedBlocks, targetDisk);
    }

    private void hydrateFileAllocation(
            FileNode file,
            boolean[] claimedBlocks,
            boolean[] reservedFirstBlocks,
            SimulatedDisk targetDisk) {
        int[] chain = new int[file.getSizeInBlocks()];
        chain[0] = file.getFirstBlockIndex();
        markClaimed(chain[0], file, claimedBlocks);

        int searchStart = (chain[0] + 1) % targetDisk.getTotalBlocks();
        for (int i = 1; i < chain.length; i++) {
            int blockIndex = findNextFreeUnclaimedBlock(searchStart, claimedBlocks, reservedFirstBlocks, targetDisk);
            if (blockIndex == SimulatedDisk.NO_FREE_BLOCK) {
                throw new IllegalArgumentException("unable to hydrate disk allocation for file: " + file.getId());
            }
            chain[i] = blockIndex;
            markClaimed(blockIndex, file, claimedBlocks);
            searchStart = (blockIndex + 1) % targetDisk.getTotalBlocks();
        }

        for (int i = 0; i < chain.length; i++) {
            int nextBlockIndex = (i == chain.length - 1) ? DiskBlock.NO_NEXT_BLOCK : chain[i + 1];
            targetDisk.allocateBlock(chain[i], file.getId(), nextBlockIndex, file.isSystemFile());
        }
    }

    private void validateExistingFileAllocation(
            FileNode file,
            boolean[] claimedBlocks,
            SimulatedDisk targetDisk) {
        int currentBlockIndex = file.getFirstBlockIndex();
        for (int i = 0; i < file.getSizeInBlocks(); i++) {
            if (!targetDisk.isValidIndex(currentBlockIndex)) {
                throw new IllegalArgumentException("disk chain points outside disk for file: " + file.getId());
            }

            DiskBlock block = targetDisk.getBlock(currentBlockIndex);
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

    private int findNextFreeUnclaimedBlock(
            int startIndex,
            boolean[] claimedBlocks,
            boolean[] reservedFirstBlocks,
            SimulatedDisk targetDisk) {
        for (int offset = 0; offset < targetDisk.getTotalBlocks(); offset++) {
            int candidate = (startIndex + offset) % targetDisk.getTotalBlocks();
            if (!claimedBlocks[candidate]
                    && !reservedFirstBlocks[candidate]
                    && targetDisk.getBlock(candidate).isFree()) {
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

    private void ensureNoOrphanedOccupiedBlocks(boolean[] claimedBlocks, SimulatedDisk targetDisk) {
        for (int blockIndex = 0; blockIndex < targetDisk.getTotalBlocks(); blockIndex++) {
            if (!claimedBlocks[blockIndex] && !targetDisk.getBlock(blockIndex).isFree()) {
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
                recordEvent("SESSION", "switched session to " + switchSessionIntent.getTargetUserId());
                return;
            }

            ApplicationOperationIntent intent = pendingIntent.intent;
            if (intent instanceof ScenarioOperationIntent scenarioOperationIntent) {
                intent = scenarioOperationIntent.resolve(applicationState, disk);
            }

            PreparedOperationCommand command = applicationIntentPlanner.plan(
                    intent,
                    pendingIntent.requestId,
                    pendingIntent.processId);
            handleSubmitOperation(command);
        } catch (RuntimeException exception) {
            processStore.addRejectedTerminatedProcess(
                    pendingIntent.processId,
                    pendingIntent.requestId,
                    inferIntentOperationType(pendingIntent.intent),
                    inferIntentOwnerUserId(pendingIntent.intent),
                    inferIntentRequiredLockType(pendingIntent.intent),
                    describeIntentTargetPath(pendingIntent.intent),
                    0,
                    exception.getMessage());
            recordEvent("PROCESS", "rejected intent " + pendingIntent.processId + ": " + exception.getMessage());
        }
    }

    private void executeCommandSafely(CoordinatorCommand command) {
        try {
            command.execute();
        } catch (RuntimeException exception) {
            if (command instanceof SubmitOperationCoordinatorCommand submitCommand) {
                processStore.addRejectedTerminatedProcess(submitCommand.command, exception.getMessage());
                recordEvent("PROCESS", "rejected submitted operation "
                        + submitCommand.command.getProcessId() + ": " + exception.getMessage());
            }
        }
    }

    private String describeIntentTargetPath(ApplicationOperationIntent intent) {
        if (intent instanceof ScenarioOperationIntent scenarioOperationIntent) {
            return scenarioOperationIntent.describeTargetPath(applicationState, disk);
        }
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

    private IoOperationType inferIntentOperationType(ApplicationOperationIntent intent) {
        if (intent instanceof CreateFileIntent || intent instanceof CreateDirectoryIntent) {
            return IoOperationType.CREATE;
        }
        if (intent instanceof ReadIntent) {
            return IoOperationType.READ;
        }
        if (intent instanceof RenameIntent) {
            return IoOperationType.UPDATE;
        }
        if (intent instanceof DeleteIntent) {
            return IoOperationType.DELETE;
        }
        if (intent instanceof ScenarioOperationIntent scenarioOperationIntent) {
            return scenarioOperationIntent.getOperationType();
        }
        return null;
    }

    private LockType inferIntentRequiredLockType(ApplicationOperationIntent intent) {
        IoOperationType operationType = inferIntentOperationType(intent);
        if (operationType == IoOperationType.READ) {
            return LockType.SHARED;
        }
        if (operationType == IoOperationType.UPDATE || operationType == IoOperationType.DELETE) {
            return LockType.EXCLUSIVE;
        }
        return null;
    }

    private String inferIntentOwnerUserId(ApplicationOperationIntent intent) {
        if (intent instanceof SwitchSessionIntent switchSessionIntent) {
            return switchSessionIntent.getTargetUserId();
        }
        return applicationState.getSessionContext().getCurrentUserId();
    }

    private void refreshDiskOccupancyMarkers() {
        disk.clearAllBlockOccupants();
        ProcessControlBlock runningProcess = processStore.getRunningProcess();
        if (runningProcess != null && disk.isValidIndex(runningProcess.getTargetBlock())) {
            disk.setBlockOccupantProcessId(runningProcess.getTargetBlock(), runningProcess.getProcessId());
        }
    }

    private synchronized void recordEvent(String category, String message) {
        eventLog.record(nextTick, category, message);
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

    private void ensureNotAwaitingRecovery() {
        if (recoveryQuarantineActive) {
            throw new IllegalStateException("coordinator requires recovery or load before accepting new work");
        }
    }

    private void enterRecoveryQuarantine(PreparedOperationCommand crashedCommand, String reason) {
        recoveryQuarantineActive = true;
        queuedIntentCancellationUserId = applicationState.getSessionContext().getCurrentUserId();
        cancelPendingSubmissionsDueToCrash(reason);
        releaseReservedBlocksForQueuedProcesses();
        lockTable.clear();
        processStore.cancelAllNonRunningProcesses(reason);
        recordEvent(
                "CRASH",
                "coordinator quarantined after simulated crash in process " + crashedCommand.getProcessId());
    }

    private void cancelPendingSubmissionsDueToCrash(String reason) {
        Object[] pendingSnapshot = pendingSubmissions.toArray();
        pendingSubmissions.clear();
        for (Object object : pendingSnapshot) {
            PendingSubmission submission = (PendingSubmission) object;
            if (submission.command != null) {
                releaseReservedBlocksForCommand(submission.command);
                processStore.addCancelledTerminatedProcess(submission.command, reason);
                continue;
            }
            processStore.addCancelledTerminatedProcess(
                    submission.processId,
                    submission.requestId,
                    inferIntentOperationType(submission.intent),
                    resolveCancelledIntentOwnerUserId(submission.intent),
                    inferIntentRequiredLockType(submission.intent),
                    describeIntentTargetPath(submission.intent),
                    0,
                    reason);
            advanceQueuedIntentCancellationState(submission.intent);
        }
    }

    private void releaseReservedBlocksForQueuedProcesses() {
        ProcessControlBlock[] queuedProcesses = processStore.getAllNonRunningProcessesSnapshot();
        for (ProcessControlBlock process : queuedProcesses) {
            ProcessExecutionContext context = processStore.requireContext(process.getProcessId());
            if (!context.hasDeferredIntent()) {
                releaseReservedBlocksForCommand(context.getCommand());
            }
        }
    }

    private void releaseReservedBlocksForCommand(PreparedOperationCommand command) {
        if (command == null || !command.requiresJournal()) {
            return;
        }
        if (!(command.getPreparedJournalData().getUndoData() instanceof CreateFileUndoData undoData)) {
            return;
        }
        applicationState.releaseReservedBlockIndexes(undoData.getAllocatedBlockIndexesSnapshot());
    }

    private String resolveCancelledIntentOwnerUserId(ApplicationOperationIntent intent) {
        if (intent instanceof SwitchSessionIntent switchSessionIntent) {
            return switchSessionIntent.getTargetUserId();
        }
        if (queuedIntentCancellationUserId != null) {
            return queuedIntentCancellationUserId;
        }
        return inferIntentOwnerUserId(intent);
    }

    private void advanceQueuedIntentCancellationState(ApplicationOperationIntent intent) {
        if (intent instanceof SwitchSessionIntent switchSessionIntent) {
            queuedIntentCancellationUserId = switchSessionIntent.getTargetUserId();
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

    private void runSynchronously(CoordinatorCommand command) {
        if (Thread.currentThread() == coordinatorThread) {
            command.execute();
            return;
        }
        CoordinatorCommandCompletion completion = new CoordinatorCommandCompletion(command);
        channels.enqueueCommand(completion);
        completion.awaitCompletion();
    }

    private final class SubmitOperationCoordinatorCommand implements CoordinatorCommand {

        private final PreparedOperationCommand command;

        private SubmitOperationCoordinatorCommand(PreparedOperationCommand command) {
            this.command = command;
        }

        @Override
        public void execute() {
            if (recoveryQuarantineActive) {
                releaseReservedBlocksForCommand(command);
                processStore.addCancelledTerminatedProcess(command, "cancelled due to pending crash recovery");
                recordEvent("CRASH", "cancelled queued operation " + command.getProcessId() + " during recovery quarantine");
                return;
            }
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
            String requestId = nextRequestId();
            String processId = nextProcessId();
            if (recoveryQuarantineActive) {
                processStore.addCancelledTerminatedProcess(
                        processId,
                        requestId,
                        inferIntentOperationType(intent),
                        resolveCancelledIntentOwnerUserId(intent),
                        inferIntentRequiredLockType(intent),
                        describeIntentTargetPath(intent),
                        0,
                        "cancelled due to pending crash recovery");
                advanceQueuedIntentCancellationState(intent);
                recordEvent("CRASH", "cancelled queued intent during recovery quarantine");
                return;
            }
            pendingSubmissions.enqueue(PendingSubmission.forIntent(
                    intent,
                    requestId,
                    processId));
        }
    }

    private final class ChangePolicyCoordinatorCommand implements CoordinatorCommand {

        private final DiskSchedulingPolicy policy;

        private ChangePolicyCoordinatorCommand(DiskSchedulingPolicy policy) {
            this.policy = policy;
        }

        @Override
        public void execute() {
            if (recoveryQuarantineActive) {
                recordEvent("CRASH", "ignored policy change during recovery quarantine");
                return;
            }
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
            if (recoveryQuarantineActive) {
                recordEvent("CRASH", "ignored head direction change during recovery quarantine");
                return;
            }
            disk.setHeadDirection(direction);
        }
    }

    private final class ChangeExecutionDelayCoordinatorCommand implements CoordinatorCommand {

        private final long delayMillis;

        private ChangeExecutionDelayCoordinatorCommand(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public void execute() {
            if (recoveryQuarantineActive) {
                recordEvent("CRASH", "ignored execution delay change during recovery quarantine");
                return;
            }
            executionDelayMillis = delayMillis;
            recordEvent("PLAYBACK", "execution delay set to " + delayMillis + " ms");
        }
    }

    private final class SaveSystemCoordinatorCommand implements CoordinatorCommand {

        private final Path path;

        private SaveSystemCoordinatorCommand(Path path) {
            this.path = path;
        }

        @Override
        public void execute() {
            saveSystemState(path);
        }
    }

    private final class LoadSystemCoordinatorCommand implements CoordinatorCommand {

        private final Path path;

        private LoadSystemCoordinatorCommand(Path path) {
            this.path = path;
        }

        @Override
        public void execute() {
            loadPersistedSystemState(path);
        }
    }

    private final class LoadScenarioCoordinatorCommand implements CoordinatorCommand {

        private final Path path;

        private LoadScenarioCoordinatorCommand(Path path) {
            this.path = path;
        }

        @Override
        public void execute() {
            loadExternalScenario(path);
        }
    }

    private final class ArmSimulatedFailureCoordinatorCommand implements CoordinatorCommand {

        @Override
        public void execute() {
            simulatedFailureArmed = true;
            recordEvent("CRASH", "simulated failure armed for next successful journaled operation");
        }
    }

    private final class RecoverPendingJournalCoordinatorCommand implements CoordinatorCommand {

        @Override
        public void execute() {
            recoverPendingJournalEntriesInMemory();
        }
    }

    private static final class CoordinatorCommandCompletion implements CoordinatorCommand {

        private final CoordinatorCommand delegate;
        private final CountDownLatch completionLatch;
        private RuntimeException failure;

        private CoordinatorCommandCompletion(CoordinatorCommand delegate) {
            this.delegate = delegate;
            this.completionLatch = new CountDownLatch(1);
            this.failure = null;
        }

        @Override
        public void execute() {
            try {
                delegate.execute();
            } catch (RuntimeException exception) {
                failure = exception;
            } finally {
                completionLatch.countDown();
            }
        }

        private void awaitCompletion() {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        completionLatch.await();
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
            if (failure != null) {
                throw failure;
            }
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

    private record ScenarioReadyRegistration(
            ProcessControlBlock process,
            ScenarioOperationIntent intent) {
    }
}
