package ve.edu.unimet.so.project2.journal.undo;

public record UpdateRenameUndoData(
        String targetNodeId,
        String parentDirectoryId,
        String parentDirectoryPath,
        String oldName,
        String newName) implements JournalUndoData {

    public UpdateRenameUndoData {
        targetNodeId = JournalUndoData.requireNonBlank(targetNodeId, "targetNodeId");
        parentDirectoryId = JournalUndoData.requireNonBlank(parentDirectoryId, "parentDirectoryId");
        parentDirectoryPath = JournalUndoData.requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        validateNodeName(oldName, "oldName");
        validateNodeName(newName, "newName");
        if (oldName.equals(newName)) {
            throw new IllegalArgumentException("el nuevo nombre debe ser diferente al actual");
        }
    }

    public String getTargetNodeId() { return targetNodeId; }
    public String getParentDirectoryId() { return parentDirectoryId; }
    public String getParentDirectoryPath() { return parentDirectoryPath; }
    public String getOldName() { return oldName; }
    public String getNewName() { return newName; }

    private static void validateNodeName(String value, String fieldName) {
        JournalUndoData.requireNonBlank(value, fieldName);
        if (value.contains("/")) {
            throw new IllegalArgumentException(fieldName + " cannot contain /");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be . or ..");
        }
    }
}
