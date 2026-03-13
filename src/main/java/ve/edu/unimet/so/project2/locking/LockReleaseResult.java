package ve.edu.unimet.so.project2.locking;

import ve.edu.unimet.so.project2.datastructures.SimpleList;

public final class LockReleaseResult {

    private final String fileId;
    private final String processId;
    private final boolean released;
    private final SimpleList<LockWaitEntry> awakenedEntries;

    public LockReleaseResult(String fileId, String processId, boolean released, SimpleList<LockWaitEntry> awakenedEntries) {
        this.fileId = requireNonBlank(fileId, "fileId");
        this.processId = requireNonBlank(processId, "processId");
        if (awakenedEntries == null) {
            throw new IllegalArgumentException("awakenedEntries cannot be null");
        }
        this.released = released;
        this.awakenedEntries = awakenedEntries;
    }

    public String getFileId() {
        return fileId;
    }

    public String getProcessId() {
        return processId;
    }

    public boolean isReleased() {
        return released;
    }

    public int getAwakenedCount() {
        return awakenedEntries.size();
    }

    public LockWaitEntry getAwakenedEntryAt(int index) {
        return awakenedEntries.get(index);
    }

    public Object[] getAwakenedEntriesSnapshot() {
        return awakenedEntries.toArray();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
