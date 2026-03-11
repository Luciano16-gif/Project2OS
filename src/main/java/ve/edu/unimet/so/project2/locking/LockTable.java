package ve.edu.unimet.so.project2.locking;

import ve.edu.unimet.so.project2.datastructures.SimpleList;

public class LockTable {

    private final SimpleList<FileLockState> statesByFile;

    public LockTable() {
        this.statesByFile = new SimpleList<>();
    }

    public LockAcquireResult tryAcquire(String fileId, String processId, LockType requestedLockType) {
        requireNonBlank(fileId, "fileId");
        requireNonBlank(processId, "processId");
        if (requestedLockType == null) {
            throw new IllegalArgumentException("requestedLockType cannot be null");
        }
        FileLockState state = getOrCreateState(fileId);
        return state.tryAcquire(processId, requestedLockType);
    }

    public LockReleaseResult releaseByProcess(String fileId, String processId) {
        FileLockState state = findState(fileId);
        if (state == null) {
            return new LockReleaseResult(fileId, processId, false, new SimpleList<>());
        }

        LockReleaseResult result = state.releaseByProcess(processId);
        if (!state.hasActiveLocks() && !state.hasWaitingEntries() && !state.hasPendingGrants()) {
            statesByFile.removeFirst(state);
        }
        return result;
    }

    public boolean hasStateForFile(String fileId) {
        return findState(fileId) != null;
    }

    public int getTrackedFileCount() {
        return statesByFile.size();
    }

    public int getActiveLockCount(String fileId) {
        FileLockState state = findState(fileId);
        return state == null ? 0 : state.getActiveLockCount();
    }

    public int getWaitingCount(String fileId) {
        FileLockState state = findState(fileId);
        return state == null ? 0 : state.getWaitingCount();
    }

    public int getPendingGrantCount(String fileId) {
        FileLockState state = findState(fileId);
        return state == null ? 0 : state.getPendingGrantCount();
    }

    public Object[] getActiveLocksSnapshot(String fileId) {
        FileLockState state = findState(fileId);
        return state == null ? new Object[0] : state.getActiveLocksSnapshot();
    }

    public Object[] getWaitingQueueSnapshot(String fileId) {
        FileLockState state = findState(fileId);
        return state == null ? new Object[0] : state.getWaitingQueueSnapshot();
    }

    public Object[] getPendingGrantSnapshot(String fileId) {
        FileLockState state = findState(fileId);
        return state == null ? new Object[0] : state.getPendingGrantSnapshot();
    }

    public void clear() {
        statesByFile.clear();
    }

    private FileLockState getOrCreateState(String fileId) {
        FileLockState state = findState(fileId);
        if (state != null) {
            return state;
        }

        FileLockState created = new FileLockState(fileId);
        statesByFile.add(created);
        return created;
    }

    private FileLockState findState(String fileId) {
        requireNonBlank(fileId, "fileId");
        for (int i = 0; i < statesByFile.size(); i++) {
            FileLockState state = statesByFile.get(i);
            if (state.getFileId().equals(fileId)) {
                return state;
            }
        }
        return null;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
