package ve.edu.unimet.so.project2.locking;

public final class FileLock {

    private final String fileId;
    private final LockType type;
    private final String ownerProcessId;

    public FileLock(String fileId, LockType type, String ownerProcessId) {
        this.fileId = requireNonBlank(fileId, "fileId");
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        this.type = type;
        this.ownerProcessId = requireNonBlank(ownerProcessId, "ownerProcessId");
    }

    public String getFileId() {
        return fileId;
    }

    public LockType getType() {
        return type;
    }

    public String getOwnerProcessId() {
        return ownerProcessId;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
