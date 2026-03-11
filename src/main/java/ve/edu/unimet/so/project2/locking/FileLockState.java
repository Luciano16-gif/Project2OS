package ve.edu.unimet.so.project2.locking;

import ve.edu.unimet.so.project2.datastructures.LinkedQueue;
import ve.edu.unimet.so.project2.datastructures.SimpleList;

final class FileLockState {

    private final String fileId;
    private final SimpleList<FileLock> activeLocks;
    private final LinkedQueue<LockWaitEntry> waitingQueue;
    private final SimpleList<LockWaitEntry> pendingGrants;

    FileLockState(String fileId) {
        this.fileId = requireNonBlank(fileId, "fileId");
        this.activeLocks = new SimpleList<>();
        this.waitingQueue = new LinkedQueue<>();
        this.pendingGrants = new SimpleList<>();
    }

    String getFileId() {
        return fileId;
    }

    boolean hasActiveLocks() {
        return !activeLocks.isEmpty();
    }

    boolean hasWaitingEntries() {
        return !waitingQueue.isEmpty();
    }

    boolean hasPendingGrants() {
        return !pendingGrants.isEmpty();
    }

    int getActiveLockCount() {
        return activeLocks.size();
    }

    int getWaitingCount() {
        return waitingQueue.size();
    }

    Object[] getActiveLocksSnapshot() {
        return activeLocks.toArray();
    }

    Object[] getWaitingQueueSnapshot() {
        return waitingQueue.toArray();
    }

    LockAcquireResult tryAcquire(String processId, LockType requestedLockType) {
        requireNonBlank(processId, "processId");
        if (requestedLockType == null) {
            throw new IllegalArgumentException("requestedLockType cannot be null");
        }

        FileLock heldLock = findActiveLockByOwner(processId);
        if (heldLock != null) {
            if (heldLock.getType() == requestedLockType) {
                return new LockAcquireResult(
                        LockAcquireDecision.ALREADY_HELD,
                        fileId,
                        processId,
                        requestedLockType,
                        null);
            }
            throw new IllegalStateException("lock upgrades or downgrades are not supported");
        }

        LockWaitEntry pendingGrant = findPendingGrantByProcess(processId);
        if (pendingGrant != null) {
            if (pendingGrant.getRequestedLockType() != requestedLockType) {
                throw new IllegalStateException("pending grant type does not match requested lock type");
            }
            pendingGrants.removeFirst(pendingGrant);
            activeLocks.add(new FileLock(fileId, requestedLockType, processId));
            return new LockAcquireResult(
                    LockAcquireDecision.GRANTED,
                    fileId,
                    processId,
                    requestedLockType,
                    null);
        }

        if (findWaitingEntryByProcess(processId) != null) {
            return new LockAcquireResult(
                    LockAcquireDecision.BLOCKED,
                    fileId,
                    processId,
                    requestedLockType,
                    findBlockingProcessIdForQueue(processId));
        }

        if (hasWaitingEntries()) {
            return enqueueBlocked(processId, requestedLockType, findBlockingProcessIdForQueue(processId));
        }

        if (canGrantImmediately(requestedLockType)) {
            activeLocks.add(new FileLock(fileId, requestedLockType, processId));
            return new LockAcquireResult(
                    LockAcquireDecision.GRANTED,
                    fileId,
                    processId,
                    requestedLockType,
                    null);
        }

        return enqueueBlocked(processId, requestedLockType, findBlockingProcessIdForQueue(processId));
    }

    LockReleaseResult releaseByProcess(String processId) {
        requireNonBlank(processId, "processId");

        boolean released = removeActiveLocksByOwner(processId);
        boolean removedPending = removePendingGrantByOwner(processId);
        boolean removedWaiting = removeWaitingEntryByOwner(processId);
        SimpleList<LockWaitEntry> awakenedEntries = collectAwakenedEntries(released || removedPending || removedWaiting);

        return new LockReleaseResult(fileId, processId, released, awakenedEntries);
    }

    private LockAcquireResult enqueueBlocked(String processId, LockType requestedLockType, String blockingProcessId) {
        if (findWaitingEntryByProcess(processId) == null) {
            waitingQueue.enqueue(new LockWaitEntry(fileId, processId, requestedLockType));
        }
        return new LockAcquireResult(
                LockAcquireDecision.BLOCKED,
                fileId,
                processId,
                requestedLockType,
                blockingProcessId);
    }

    private boolean canGrantImmediately(LockType requestedLockType) {
        if (!hasActiveLocks() && !hasPendingGrants()) {
            return true;
        }

        if (requestedLockType == LockType.SHARED) {
            return !hasExclusiveReservation();
        }

        return false;
    }

    private boolean hasExclusiveReservation() {
        for (int i = 0; i < activeLocks.size(); i++) {
            if (activeLocks.get(i).getType() == LockType.EXCLUSIVE) {
                return true;
            }
        }
        for (int i = 0; i < pendingGrants.size(); i++) {
            if (pendingGrants.get(i).getRequestedLockType() == LockType.EXCLUSIVE) {
                return true;
            }
        }
        return false;
    }

    private FileLock findActiveLockByOwner(String processId) {
        for (int i = 0; i < activeLocks.size(); i++) {
            FileLock lock = activeLocks.get(i);
            if (lock.getOwnerProcessId().equals(processId)) {
                return lock;
            }
        }
        return null;
    }

    private LockWaitEntry findWaitingEntryByProcess(String processId) {
        Object[] snapshot = waitingQueue.toArray();
        for (Object item : snapshot) {
            LockWaitEntry entry = (LockWaitEntry) item;
            if (entry.getProcessId().equals(processId)) {
                return entry;
            }
        }
        return null;
    }

    private LockWaitEntry findPendingGrantByProcess(String processId) {
        for (int i = 0; i < pendingGrants.size(); i++) {
            LockWaitEntry entry = pendingGrants.get(i);
            if (entry.getProcessId().equals(processId)) {
                return entry;
            }
        }
        return null;
    }

    private String findBlockingOwnerProcessId() {
        if (activeLocks.isEmpty()) {
            return null;
        }
        return activeLocks.get(0).getOwnerProcessId();
    }

    private String findBlockingProcessIdForQueue(String requesterProcessId) {
        if (!pendingGrants.isEmpty()) {
            for (int i = 0; i < pendingGrants.size(); i++) {
                String pendingOwner = pendingGrants.get(i).getProcessId();
                if (!pendingOwner.equals(requesterProcessId)) {
                    return pendingOwner;
                }
            }
        }

        Object[] queueSnapshot = waitingQueue.toArray();
        for (Object item : queueSnapshot) {
            LockWaitEntry entry = (LockWaitEntry) item;
            if (!entry.getProcessId().equals(requesterProcessId)) {
                return entry.getProcessId();
            }
        }

        return findBlockingOwnerProcessId();
    }

    private boolean removeActiveLocksByOwner(String processId) {
        boolean removed = false;

        for (int i = activeLocks.size() - 1; i >= 0; i--) {
            FileLock lock = activeLocks.get(i);
            if (lock.getOwnerProcessId().equals(processId)) {
                activeLocks.removeAt(i);
                removed = true;
            }
        }

        return removed;
    }

    private boolean removePendingGrantByOwner(String processId) {
        LockWaitEntry pendingEntry = findPendingGrantByProcess(processId);
        return pendingEntry != null && pendingGrants.removeFirst(pendingEntry);
    }

    private boolean removeWaitingEntryByOwner(String processId) {
        LockWaitEntry waitingEntry = findWaitingEntryByProcess(processId);
        return waitingEntry != null && waitingQueue.removeFirst(waitingEntry);
    }

    private SimpleList<LockWaitEntry> collectAwakenedEntries(boolean shouldReevaluate) {
        SimpleList<LockWaitEntry> awakenedEntries = new SimpleList<>();

        if (!shouldReevaluate || !hasWaitingEntries()) {
            return awakenedEntries;
        }

        LockWaitEntry first = waitingQueue.peek();
        if (first.getRequestedLockType() == LockType.EXCLUSIVE) {
            if (!hasActiveLocks() && !hasPendingGrants()) {
                awakenedEntries.add(moveNextWaitingEntryToPendingGrants());
            }
            return awakenedEntries;
        }

        while (hasWaitingEntries()) {
            LockWaitEntry next = waitingQueue.peek();
            if (next.getRequestedLockType() != LockType.SHARED) {
                break;
            }
            if (hasExclusiveReservation()) {
                break;
            }
            awakenedEntries.add(moveNextWaitingEntryToPendingGrants());
        }

        return awakenedEntries;
    }

    private LockWaitEntry moveNextWaitingEntryToPendingGrants() {
        LockWaitEntry entry = waitingQueue.dequeue();
        pendingGrants.add(entry);
        return entry;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
