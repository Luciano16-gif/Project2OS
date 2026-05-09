package ve.edu.unimet.so.project2.locking;

import ve.edu.unimet.so.project2.datastructures.SimpleList;

public record LockReleaseResult(
        String fileId,
        String processId,
        boolean released,
        SimpleList<LockWaitEntry> awakenedEntries) {

    public LockReleaseResult {
        fileId = requireNonBlank(fileId, "fileId");
        processId = requireNonBlank(processId, "processId");
        if (awakenedEntries == null) {
            throw new IllegalArgumentException("awakenedEntries cannot be null");
        }
    }

    public String getFileId() { return fileId; }
    public String getProcessId() { return processId; }
    public boolean isReleased() { return released; }
    public int getAwakenedCount() { return awakenedEntries.size(); }
    public LockWaitEntry getAwakenedEntryAt(int index) { return awakenedEntries.get(index); }
    public Object[] getAwakenedEntriesSnapshot() { return awakenedEntries.toArray(); }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
