package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.coordinator.channel.DiskTask;

final class DiskExecutionWorker implements Runnable {

    private final SimulationCoordinator coordinator;

    DiskExecutionWorker(SimulationCoordinator coordinator) {
        if (coordinator == null) {
            throw new IllegalArgumentException("coordinator cannot be null");
        }
        this.coordinator = coordinator;
    }

    @Override
    public void run() {
        while (true) {
            DiskTask task = coordinator.awaitNextDiskTask();
            if (task == null) {
                if (coordinator.isShutdownRequestedForWorker()) {
                    break;
                }
                continue;
            }

            coordinator.completeDiskTask(task, Thread.currentThread().getName());
        }
    }
}
