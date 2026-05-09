package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.coordinator.channel.DiskServiceResult;
import ve.edu.unimet.so.project2.coordinator.command.CoordinatorCommand;

final class CoordinatorLoopWorker implements Runnable {

    private static final long IDLE_SLEEP_MILLIS = 2L;

    private final SimulationCoordinator coordinator;

    CoordinatorLoopWorker(SimulationCoordinator coordinator) {
        if (coordinator == null) {
            throw new IllegalArgumentException("coordinator cannot be null");
        }
        this.coordinator = coordinator;
    }

    @Override
    public void run() {
        while (true) {
            boolean worked = drainPendingCommands();

            boolean canAdvanceSimulation = coordinator.consumeStepPermitIfNeeded();
            if (canAdvanceSimulation) {
                DiskServiceResult diskResult = coordinator.pollCompletedDiskResult();
                if (diskResult != null) {
                    coordinator.finishRunningProcess(diskResult);
                    worked = true;
                }
            }

            if (coordinator.reconcileBlockedLockWaiters()) {
                worked = true;
            }

            if (coordinator.materializePendingSubmissions()) {
                worked = true;
            }

            if (canAdvanceSimulation && !coordinator.hasRunningProcess() && coordinator.tryDispatchNextReady()) {
                worked = true;
            }

            coordinator.publishSnapshot();
            if (coordinator.shouldStopCoordinatorLoop()) {
                break;
            }
            pause(worked);
        }

        coordinator.publishSnapshot();
    }

    private boolean drainPendingCommands() {
        boolean worked = false;
        while (true) {
            CoordinatorCommand command = coordinator.pollNextCommand();
            if (command == null) {
                return worked;
            }
            coordinator.executeCommandSafely(command);
            worked = true;
        }
    }

    private void pause(boolean worked) {
        long executionDelayMillis = coordinator.getExecutionDelayMillisForWorker();
        if (worked && executionDelayMillis > 0L) {
            coordinator.sleepQuietly(executionDelayMillis);
            return;
        }
        if (!worked) {
            coordinator.sleepQuietly(IDLE_SLEEP_MILLIS);
        }
    }
}
