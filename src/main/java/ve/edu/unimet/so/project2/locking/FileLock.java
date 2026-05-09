package ve.edu.unimet.so.project2.locking;

public record FileLock(
        String fileId,
        LockType type,
        String ownerProcessId) {

    public FileLock {
        fileId = requireNonBlank(fileId, "fileId");
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        ownerProcessId = requireNonBlank(ownerProcessId, "ownerProcessId");
    }

    public String getFileId() { return fileId; }
    public LockType getType() { return type; }
    public String getOwnerProcessId() { return ownerProcessId; }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
