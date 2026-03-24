package ve.edu.unimet.so.project2.journal.undo;

public final class UpdateRenameUndoData implements JournalUndoData {

    private final String targetNodeId;
    private final String parentDirectoryId;
    private final String parentDirectoryPath;
    private final String oldName;
    private final String newName;

    public UpdateRenameUndoData(
            String targetNodeId,
            String parentDirectoryId,
            String parentDirectoryPath,
            String oldName,
            String newName) {
        this.targetNodeId = requireNonBlank(targetNodeId, "targetNodeId");
        this.parentDirectoryId = requireNonBlank(parentDirectoryId, "parentDirectoryId");
        this.parentDirectoryPath = requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        validateNodeName(oldName, "oldName");
        validateNodeName(newName, "newName");
        if (oldName.equals(newName)) {
            throw new IllegalArgumentException("el nuevo nombre debe ser diferente al actual");
        }
        this.oldName = oldName;
        this.newName = newName;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public String getParentDirectoryId() {
        return parentDirectoryId;
    }

    public String getParentDirectoryPath() {
        return parentDirectoryPath;
    }

    public String getOldName() {
        return oldName;
    }

    public String getNewName() {
        return newName;
    }

    private static void validateNodeName(String value, String fieldName) {
        requireNonBlank(value, fieldName);
        if (value.contains("/")) {
            throw new IllegalArgumentException(fieldName + " cannot contain /");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be . or ..");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
