package ve.edu.unimet.so.project2.coordinator.core;

import java.nio.file.Path;
import java.util.function.Predicate;
import ve.edu.unimet.so.project2.application.ApplicationIntentPlanner;
import ve.edu.unimet.so.project2.application.ApplicationOperationIntent;
import ve.edu.unimet.so.project2.application.PermissionService;
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
import ve.edu.unimet.so.project2.disk.DiskHead;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.journal.JournalEntry;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.journal.undo.CreateFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.JournalUndoData;
import ve.edu.unimet.so.project2.journal.undo.UpdateRenameUndoData;
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
    private final DiskAllocationConsistencyService diskAllocationConsistencyService;
    private final ScenarioProcessStager scenarioProcessStager;
    private final ApplicationIntentDescriptor applicationIntentDescriptor;

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
    private volatile boolean stepModeEnabled;
    private int pendingStepPermits;

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
        this.diskAllocationConsistencyService = new DiskAllocationConsistencyService();
        this.scenarioProcessStager = new ScenarioProcessStager();
        this.applicationIntentDescriptor = new ApplicationIntentDescriptor();
        this.disk = diskAllocationConsistencyService.copyOf(disk);
        this.lockTable = lockTable;
        this.journalManager = journalManager;
        this.diskScheduler = new DiskScheduler();
        this.channels = new CoordinatorChannels();
        this.processStore = new CoordinatorProcessStore();
        this.snapshotFactory = new SimulationSnapshotFactory();
        this.applicationState = applicationState.deepCopy();
        diskAllocationConsistencyService.synchronizeDiskWithCatalog(this.applicationState, this.disk);
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
        this.stepModeEnabled = false;
        this.pendingStepPermits = 0;
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
        coordinatorThread = new Thread(new CoordinatorLoopWorker(this), "SimulationCoordinatorThread");
        diskThread = new Thread(new DiskExecutionWorker(this), "DiskExecutionThread");
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
        enqueueOperationalCommand(() -> enqueueSubmittedOperation(command));
    }

    public void submitIntent(ApplicationOperationIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent cannot be null");
        }
        enqueueOperationalCommand(() -> enqueueApplicationIntent(intent));
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
        enqueueOperationalCommand(() -> applyPolicyChange(policy));
    }

    public void changeHeadDirection(DiskHeadDirection direction) {
        if (direction == null) {
            throw new IllegalArgumentException("direction cannot be null");
        }
        enqueueOperationalCommand(() -> applyHeadDirectionChange(direction));
    }

    public void changeExecutionDelay(long delayMillis) {
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis cannot be negative");
        }
        enqueueOperationalCommand(() -> applyExecutionDelayChange(delayMillis));
    }

    public long getExecutionDelayMillis() {
        return executionDelayMillis;
    }

    public void setStepModeEnabled(boolean enabled) {
        enqueueOperationalCommand(() -> applyStepModeChange(enabled));
    }

    public boolean isStepModeEnabled() {
        return stepModeEnabled;
    }

    public void stepSimulationOnce() {
        enqueueOperationalCommand(this::queueManualStep);
    }

    public boolean isSimulatedFailureArmed() {
        return simulatedFailureArmed;
    }

    public boolean isRecoveryQuarantineActive() {
        return recoveryQuarantineActive;
    }

    public SimulationSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public void saveSystem(Path path) {
        runSynchronousCommand(() -> saveSystemState(path));
    }

    public void loadSystem(Path path) {
        runSynchronousCommand(() -> loadPersistedSystemState(path));
    }

    public void loadScenario(Path path) {
        runSynchronousCommand(() -> loadExternalScenario(path));
    }

    public void armSimulatedFailure() {
        runSynchronousCommand(this::armSimulatedFailureInternal);
    }

    public void recoverPendingJournalEntries() {
        runSynchronousCommand(this::recoverPendingJournalEntriesInMemory);
    }

    public void resetSimulation() {
        runSynchronousCommand(() -> resetSimulationState(disk.getTotalBlocks()));
    }

    public void resetSimulation(int totalBlocks) {
        if (totalBlocks <= 0) {
            throw new IllegalArgumentException("totalBlocks must be > 0");
        }
        runSynchronousCommand(() -> resetSimulationState(totalBlocks));
    }

    boolean consumeStepPermitIfNeeded() {
        if (!stepModeEnabled) {
            return true;
        }
        if (pendingStepPermits <= 0) {
            return false;
        }
        pendingStepPermits--;
        return true;
    }

    boolean tryDispatchNextReady() {
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

        ProcessExecutionContext context = processStore.requireContext(selected.getProcessId());
        OperationApplyResult preExecutionFailure = resolvePreExecutionFailure(context);
        if (preExecutionFailure != null) {
            releaseProcessLock(selected);
            selected.markTerminated(preExecutionFailure.getResultStatus(), nextTick++, preExecutionFailure.getErrorMessage());
            processStore.addTerminatedProcess(selected);
            recordEvent(
                    "PROCESS",
                    "process " + selected.getProcessId() + " terminated with " + preExecutionFailure.getResultStatus());
            return true;
        }

        selected.markRunning(nextTick++);
        processStore.setRunningProcess(selected);

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

    private OperationApplyResult resolvePreExecutionFailure(ProcessExecutionContext context) {
        PreparedOperationCommand command = context.getCommand();
        if (command.getOperationType() != IoOperationType.UPDATE || command.getPreparedJournalData() == null) {
            return null;
        }
        if (!(command.getPreparedJournalData().getUndoData() instanceof UpdateRenameUndoData renameUndoData)) {
            return null;
        }

        FsNode currentTarget = applicationState.getFileSystemCatalog().findById(command.getTargetNodeId());
        if (currentTarget == null) {
            return null;
        }
        if (renameUndoData.getNewName().equals(currentTarget.getName())) {
            return OperationApplyResult.failed("rename target already has requested name");
        }
        return null;
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

    void finishRunningProcess(DiskServiceResult diskResult) {
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
        if (context.getCommand().requiresJournal()) {
            releaseReservedBlocksForUndoData(context.getCommand().getPreparedJournalData().getUndoData());
        }
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

    boolean reconcileBlockedLockWaiters() {
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

    void publishSnapshot() {
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

    void saveSystemState(Path path) {
        ensureCoordinatorIdleForAdminOperation();
        systemPersistenceService.save(
                path,
                activePolicy,
                disk,
                applicationState,
                journalManager);
        recordEvent("PERSISTENCE", "system saved to " + path);
    }

    void loadPersistedSystemState(Path path) {
        ensureCoordinatorIdleForAdminOperation();
        boolean previousStepModeEnabled = stepModeEnabled;
        LoadedSystemState loadedState = systemPersistenceService.load(path);
        journalRecoveryService.recoverPendingEntries(
                loadedState.applicationState(),
                loadedState.disk(),
                loadedState.journalManager());
        loadedState.applicationState().realignGeneratedIdsToCatalog();
        diskAllocationConsistencyService.validateDiskMatchesCatalog(
                loadedState.applicationState(),
                loadedState.disk());
        applyLoadedState(
                loadedState.disk(),
                loadedState.applicationState(),
                loadedState.journalManager(),
                loadedState.policy());
        stepModeEnabled = previousStepModeEnabled;
        pendingStepPermits = 0;
        recordEvent("PERSISTENCE", "system loaded from " + path);
        publishSnapshot();
    }

    void loadExternalScenario(Path path) {
        ensureCoordinatorIdleForAdminOperation();
        boolean previousStepModeEnabled = stepModeEnabled;
        LoadedScenarioState loadedScenarioState =
                scenarioLoader.load(path, disk.getTotalBlocks(), disk.getHead().getDirection());
        ScenarioReadyRegistration[] stagedRegistrations = scenarioProcessStager.stageReadyProcesses(
                loadedScenarioState.scenarioIntents(),
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
        stepModeEnabled = previousStepModeEnabled;
        pendingStepPermits = 0;
        recordEvent("SCENARIO", "scenario loaded from " + path + " with " + stagedRegistrations.length + " requests");
        publishSnapshot();
    }

    void recoverPendingJournalEntriesInMemory() {
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

    void resetSimulationState(int totalBlocks) {
        ensureCoordinatorIdleForAdminOperation();
        if (totalBlocks <= 0) {
            throw new IllegalArgumentException("totalBlocks must be > 0");
        }

        DiskHeadDirection currentDirection = disk.getHead().getDirection();
        SimulationApplicationState defaultState = SimulationApplicationState.createDefault();
        SimulatedDisk resetDisk = new SimulatedDisk(totalBlocks, 0, currentDirection);

        applyLoadedState(
                resetDisk,
                defaultState,
                new JournalManager(),
                activePolicy);
        executionDelayMillis = 0L;
        stepModeEnabled = false;
        pendingStepPermits = 0;
        recordEvent("SYSTEM", "simulation reset to defaults with " + totalBlocks + " blocks");
        publishSnapshot();
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

    boolean materializePendingSubmissions() {
        boolean changed = false;

        while (!pendingSubmissions.isEmpty()) {
            PendingSubmission next = pendingSubmissions.peek();
            pendingSubmissions.dequeue();
            if (next.intent() != null) {
                boolean completed = materializeIntentSubmission(next);
                changed = true;
                if (!completed) {
                    // Deferred retry: avoid spinning through the queue in the same loop tick.
                    break;
                }
                continue;
            }

            try {
                handleSubmitOperation(next.command());
            } catch (RuntimeException exception) {
                processStore.addRejectedTerminatedProcess(next.command(), exception.getMessage());
            }
            changed = true;
        }

        return changed;
    }

    private boolean materializeIntentSubmission(PendingSubmission pendingIntent) {
        try {
            if (pendingIntent.intent() instanceof SwitchSessionIntent switchSessionIntent) {
                applicationState.getSessionContext().switchTo(
                        applicationState.getUserStore().requireById(switchSessionIntent.getTargetUserId()));
                recordEvent("SESSION", "switched session to " + switchSessionIntent.getTargetUserId());
                return true;
            }

            ApplicationOperationIntent intent = pendingIntent.intent();
            if (intent instanceof ScenarioOperationIntent scenarioOperationIntent) {
                intent = scenarioOperationIntent.resolve(applicationState, disk);
            }

            PreparedOperationCommand command = applicationIntentPlanner.plan(
                    intent,
                    pendingIntent.requestId(),
                    pendingIntent.processId());
            handleSubmitOperation(command);
            return true;
        } catch (RuntimeException exception) {
            if (shouldRetryIntentPlanning(exception)) {
                pendingSubmissions.enqueue(pendingIntent);
                return false;
            }
            processStore.addRejectedTerminatedProcess(
                    pendingIntent.processId(),
                    pendingIntent.requestId(),
                    inferIntentOperationType(pendingIntent.intent()),
                    inferIntentOwnerUserId(pendingIntent.intent()),
                    inferIntentRequiredLockType(pendingIntent.intent()),
                    describeIntentTargetPath(pendingIntent.intent()),
                    0,
                    exception.getMessage());
            recordEvent("PROCESS", "rejected intent " + pendingIntent.processId() + ": " + exception.getMessage());
            return true;
        }
    }

    private boolean shouldRetryIntentPlanning(RuntimeException exception) {
        if (exception == null || exception.getMessage() == null) {
            return false;
        }
        String message = exception.getMessage().toLowerCase();
        return processStore.hasActiveProcesses()
                && message.contains("node not found at path");
    }

    void executeCommandSafely(CoordinatorCommand command) {
        try {
            command.execute();
        } catch (RuntimeException exception) {
            recordEvent("COMMAND", "command execution failed: " + exception.getMessage());
        }
    }

    private String describeIntentTargetPath(ApplicationOperationIntent intent) {
        return applicationIntentDescriptor.describeTargetPath(intent, applicationState, disk);
    }

    private void validateTargetBlockInRange(PreparedOperationCommand command) {
        if (!disk.isValidIndex(command.getTargetBlock())) {
            throw new IllegalArgumentException("targetBlock is out of disk range: " + command.getTargetBlock());
        }
    }

    private IoOperationType inferIntentOperationType(ApplicationOperationIntent intent) {
        return applicationIntentDescriptor.inferOperationType(intent);
    }

    private LockType inferIntentRequiredLockType(ApplicationOperationIntent intent) {
        return applicationIntentDescriptor.inferRequiredLockType(intent);
    }

    private String inferIntentOwnerUserId(ApplicationOperationIntent intent) {
        return applicationIntentDescriptor.inferOwnerUserId(intent, applicationState);
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
        return pendingContains(submission ->
                (submission.command() != null && requestId.equals(submission.command().getRequestId()))
                        || requestId.equals(submission.requestId()));
    }

    private boolean pendingContainsProcessId(String processId) {
        return pendingContains(submission ->
                (submission.command() != null && processId.equals(submission.command().getProcessId()))
                        || processId.equals(submission.processId()));
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
        recordEvent("CRASH", "system quarantined until recovery");
    }

    private void cancelPendingSubmissionsDueToCrash(String reason) {
        Object[] pendingSnapshot = pendingSubmissions.toArray();
        pendingSubmissions.clear();
        for (Object object : pendingSnapshot) {
            PendingSubmission submission = (PendingSubmission) object;
            if (submission.command() != null) {
                releaseReservedBlocksForCommand(submission.command());
                processStore.addCancelledTerminatedProcess(submission.command(), reason);
                continue;
            }
            processStore.addCancelledTerminatedProcess(
                    submission.processId(),
                    submission.requestId(),
                    inferIntentOperationType(submission.intent()),
                    resolveCancelledIntentOwnerUserId(submission.intent()),
                    inferIntentRequiredLockType(submission.intent()),
                    describeIntentTargetPath(submission.intent()),
                    0,
                    reason);
            advanceQueuedIntentCancellationState(submission.intent());
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
        if (command != null && command.requiresJournal()) {
            releaseReservedBlocksForUndoData(command.getPreparedJournalData().getUndoData());
        }
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

    void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    CoordinatorCommand pollNextCommand() {
        return channels.pollCommand();
    }

    DiskServiceResult pollCompletedDiskResult() {
        return channels.pollCompletedDiskResult();
    }

    boolean hasRunningProcess() {
        return processStore.getRunningProcess() != null;
    }

    boolean shouldStopCoordinatorLoop() {
        return shutdownRequested
                && processStore.getRunningProcess() == null
                && channels.isCommandQueueEmpty()
                && !channels.hasPendingDiskItems();
    }

    long getExecutionDelayMillisForWorker() {
        return executionDelayMillis;
    }

    boolean isShutdownRequestedForWorker() {
        return shutdownRequested;
    }

    DiskTask awaitNextDiskTask() {
        return channels.awaitNextDiskTask();
    }

    void completeDiskTask(DiskTask task, String workerName) {
        recordEvent("DISK", "serviced request " + task.getRequestId() + " for process " + task.getProcessId());
        channels.publishCompletedDiskResult(new DiskServiceResult(
                task.getProcessId(),
                task.getRequestId(),
                task.getPreviousHeadBlock(),
                task.getNewHeadBlock(),
                task.getTraveledDistance(),
                workerName));
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

    void enqueueSubmittedOperation(PreparedOperationCommand command) {
        if (recoveryQuarantineActive) {
            releaseReservedBlocksForCommand(command);
            processStore.addCancelledTerminatedProcess(command, "cancelled due to pending crash recovery");
            recordEvent("CRASH", "cancelled queued operation " + command.getProcessId() + " during recovery quarantine");
            return;
        }
        pendingSubmissions.enqueue(PendingSubmission.forCommand(command));
    }

    void enqueueApplicationIntent(ApplicationOperationIntent intent) {
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
        pendingSubmissions.enqueue(PendingSubmission.forIntent(intent, requestId, processId));
    }

    void applyPolicyChange(DiskSchedulingPolicy policy) {
        if (ignoreIfRecovering("ignored policy change during recovery quarantine")) {
            return;
        }
        activePolicy = policy;
        recordEvent("SCHEDULER", "disk scheduling policy changed to " + policy.name());
    }

    void applyHeadDirectionChange(DiskHeadDirection direction) {
        if (ignoreIfRecovering("ignored head direction change during recovery quarantine")) {
            return;
        }
        disk.setHeadDirection(direction);
    }

    void applyExecutionDelayChange(long delayMillis) {
        if (ignoreIfRecovering("ignored execution delay change during recovery quarantine")) {
            return;
        }
        executionDelayMillis = delayMillis;
        recordEvent("PLAYBACK", "execution delay set to " + delayMillis + " ms");
    }

    void applyStepModeChange(boolean enabled) {
        if (ignoreIfRecovering("ignored step mode change during recovery quarantine")) {
            return;
        }
        stepModeEnabled = enabled;
        if (!enabled) {
            pendingStepPermits = 0;
        }
        recordEvent("PLAYBACK", enabled ? "step mode enabled" : "step mode disabled");
    }

    void queueManualStep() {
        if (ignoreIfRecovering("ignored step command during recovery quarantine")) {
            return;
        }
        stepModeEnabled = true;
        pendingStepPermits++;
        recordEvent("PLAYBACK", "queued manual simulation step");
    }

    void armSimulatedFailureInternal() {
        if (ignoreIfRecovering("ignored simulated failure arm during recovery quarantine")) {
            return;
        }
        simulatedFailureArmed = true;
        recordEvent("CRASH", "simulated failure armed for next successful journaled operation");
    }

    private void enqueueOperationalCommand(CoordinatorCommand command) {
        requireStartedAndAcceptingCommands();
        ensureNotAwaitingRecovery();
        channels.enqueueCommand(command);
    }

    private void runSynchronousCommand(CoordinatorCommand command) {
        requireStartedAndAcceptingCommands();
        runSynchronously(command);
    }

    private boolean pendingContains(Predicate<PendingSubmission> matcher) {
        final boolean[] found = new boolean[] {false};
        pendingSubmissions.forEach(submission -> {
            if (!found[0] && matcher.test(submission)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private void releaseReservedBlocksForUndoData(JournalUndoData undoData) {
        if (undoData instanceof CreateFileUndoData createFileUndoData) {
            applicationState.releaseReservedBlockIndexes(createFileUndoData.getAllocatedBlockIndexesSnapshot());
        }
    }

    private boolean ignoreIfRecovering(String message) {
        if (!recoveryQuarantineActive) {
            return false;
        }
        recordEvent("CRASH", message);
        return true;
    }

}
