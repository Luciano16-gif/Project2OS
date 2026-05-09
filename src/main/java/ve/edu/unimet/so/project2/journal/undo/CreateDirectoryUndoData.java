package ve.edu.unimet.so.project2.journal.undo;

public record CreateDirectoryUndoData(
        String createdDirectoryId,
        String parentDirectoryId,
        String parentDirectoryPath) implements JournalUndoData {

    public CreateDirectoryUndoData {
        createdDirectoryId = JournalUndoData.requireNonBlank(createdDirectoryId, "createdDirectoryId");
        parentDirectoryId = JournalUndoData.requireNonBlank(parentDirectoryId, "parentDirectoryId");
        parentDirectoryPath = JournalUndoData.requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
    }

    public String getCreatedDirectoryId() { return createdDirectoryId; }
    public String getParentDirectoryId() { return parentDirectoryId; }
    public String getParentDirectoryPath() { return parentDirectoryPath; }
}
