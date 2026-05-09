package ve.edu.unimet.so.project2.locking;

public record LockWaitEntry(
        String fileId,
        String processId,
        LockType requestedLockType) {

    public LockWaitEntry {
        fileId = requireNonBlank(fileId, "fileId");
        processId = requireNonBlank(processId, "processId");
        if (requestedLockType == null) {
            throw new IllegalArgumentException("requestedLockType cannot be null");
        }
    }

    public String getFileId() { return fileId; }
    public String getProcessId() { return processId; }
    public LockType getRequestedLockType() { return requestedLockType; }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
