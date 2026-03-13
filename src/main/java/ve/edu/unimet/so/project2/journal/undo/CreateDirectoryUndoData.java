package ve.edu.unimet.so.project2.journal.undo;

public final class CreateDirectoryUndoData implements JournalUndoData {

    private final String createdDirectoryId;
    private final String parentDirectoryId;
    private final String parentDirectoryPath;

    public CreateDirectoryUndoData(
            String createdDirectoryId,
            String parentDirectoryId,
            String parentDirectoryPath) {
        this.createdDirectoryId = requireNonBlank(createdDirectoryId, "createdDirectoryId");
        this.parentDirectoryId = requireNonBlank(parentDirectoryId, "parentDirectoryId");
        this.parentDirectoryPath = requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
    }

    public String getCreatedDirectoryId() {
        return createdDirectoryId;
    }

    public String getParentDirectoryId() {
        return parentDirectoryId;
    }

    public String getParentDirectoryPath() {
        return parentDirectoryPath;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
