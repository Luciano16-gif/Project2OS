package ve.edu.unimet.so.project2.locking;

public final class LockWaitEntry {

    private final String fileId;
    private final String processId;
    private final LockType requestedLockType;

    public LockWaitEntry(String fileId, String processId, LockType requestedLockType) {
        this.fileId = requireNonBlank(fileId, "fileId");
        this.processId = requireNonBlank(processId, "processId");
        if (requestedLockType == null) {
            throw new IllegalArgumentException("requestedLockType cannot be null");
        }
        this.requestedLockType = requestedLockType;
    }

    public String getFileId() {
        return fileId;
    }

    public String getProcessId() {
        return processId;
    }

    public LockType getRequestedLockType() {
        return requestedLockType;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
