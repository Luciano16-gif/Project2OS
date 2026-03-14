package ve.edu.unimet.so.project2.coordinator.channel;

import java.util.concurrent.Semaphore;
import ve.edu.unimet.so.project2.coordinator.command.CoordinatorCommand;
import ve.edu.unimet.so.project2.datastructures.LinkedQueue;

public final class CoordinatorChannels {

    private final LinkedQueue<CoordinatorCommand> incomingCommandQueue;
    private final Semaphore commandQueueMutex;
    private final Semaphore commandAvailable;
    private final Semaphore diskChannelMutex;
    private final Semaphore diskWorkAvailable;
    private final Semaphore diskResultAvailable;

    private DiskTask pendingDiskTask;
    private DiskServiceResult completedDiskResult;

    public CoordinatorChannels() {
        this.incomingCommandQueue = new LinkedQueue<>();
        this.commandQueueMutex = new Semaphore(1);
        this.commandAvailable = new Semaphore(0);
        this.diskChannelMutex = new Semaphore(1);
        this.diskWorkAvailable = new Semaphore(0);
        this.diskResultAvailable = new Semaphore(0);
        this.pendingDiskTask = null;
        this.completedDiskResult = null;
    }

    public void enqueueCommand(CoordinatorCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command cannot be null");
        }
        commandQueueMutex.acquireUninterruptibly();
        try {
            incomingCommandQueue.enqueue(command);
        } finally {
            commandQueueMutex.release();
        }
        commandAvailable.release();
    }

    public CoordinatorCommand pollCommand() {
        if (!commandAvailable.tryAcquire()) {
            return null;
        }

        commandQueueMutex.acquireUninterruptibly();
        try {
            return incomingCommandQueue.dequeue();
        } finally {
            commandQueueMutex.release();
        }
    }

    public boolean isCommandQueueEmpty() {
        commandQueueMutex.acquireUninterruptibly();
        try {
            return incomingCommandQueue.isEmpty();
        } finally {
            commandQueueMutex.release();
        }
    }

    public void publishDiskTask(DiskTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task cannot be null");
        }
        diskChannelMutex.acquireUninterruptibly();
        try {
            if (pendingDiskTask != null) {
                throw new IllegalStateException("pendingDiskTask is already occupied");
            }
            pendingDiskTask = task;
        } finally {
            diskChannelMutex.release();
        }
        diskWorkAvailable.release();
    }

    public DiskTask awaitNextDiskTask() {
        diskWorkAvailable.acquireUninterruptibly();
        diskChannelMutex.acquireUninterruptibly();
        try {
            DiskTask task = pendingDiskTask;
            pendingDiskTask = null;
            return task;
        } finally {
            diskChannelMutex.release();
        }
    }

    public void publishCompletedDiskResult(DiskServiceResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result cannot be null");
        }
        diskChannelMutex.acquireUninterruptibly();
        try {
            if (completedDiskResult != null) {
                throw new IllegalStateException("completedDiskResult is already occupied");
            }
            completedDiskResult = result;
        } finally {
            diskChannelMutex.release();
        }
        diskResultAvailable.release();
    }

    public DiskServiceResult pollCompletedDiskResult() {
        if (!diskResultAvailable.tryAcquire()) {
            return null;
        }

        diskChannelMutex.acquireUninterruptibly();
        try {
            DiskServiceResult result = completedDiskResult;
            completedDiskResult = null;
            return result;
        } finally {
            diskChannelMutex.release();
        }
    }

    public boolean hasPendingDiskItems() {
        diskChannelMutex.acquireUninterruptibly();
        try {
            return pendingDiskTask != null || completedDiskResult != null;
        } finally {
            diskChannelMutex.release();
        }
    }

    public void releaseForShutdown() {
        commandAvailable.release();
        diskWorkAvailable.release();
    }
}
